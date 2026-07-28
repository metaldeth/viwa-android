# WS Offline Resilience — Architecture

**Session:** `ws-offline-resilience`  
**Repos:** `viwa-android`, `viwa-telemetry`  
**Status:** Approved for implementation (architect draft)

## Goal

Stable machine telemetry WS, durable replayable operations, bounded offline subscription pours, and telemetry-managed technician keys — **additive** to protocol v2.

**Delivery guarantee:** at-least-once transport. **Effect guarantee:** exactly-once via domain idempotency keys (never claim end-to-end exactly-once).

---

## ADRs

| ID | Decision |
|----|----------|
| 001 | At-least-once transport; stable `idempotencyKey`; `(machineId, messageId)` dedup is best-effort |
| 002 | WS primary; `POST /api/v1/machines/outbox/batch` fallback when v3 + `outboxRestSync` |
| 003 | Unified outbox; `ACKED` only on server ACK (WS/REST), never socket write |
| 004 | Room for outbox, entitlement cache, offline ledger; migrate JsonStore `pending_sales` |
| 005 | Connection FSM + `sessionGeneration` fencing; drop stale inbound |
| 006 | Dual liveness (RFC6455 ping + heartbeat ACK) + `NetworkCallback` |
| 007 | Signed offline grant + append-only ledger; reconcile promotes to outbox |
| 008 | Technician keys server-managed, online-gated; `KEY-[A-Z0-9]{8}` |
| 009 | Typed `AckRouter` (not `schemaHash` heuristic alone) |
| 010 | Protocol v3 + env/hello feature flags |

---

## WS Connection FSM

### States

| Internal state | UI `ConnectionState` |
|----------------|---------------------|
| `Active` | `Connected` |
| `Connecting`, `AwaitingHello`, `TokenRefresh` | `Connecting` |
| `Backoff`, `AwaitingNetwork` | `Disconnected(retryInMs)` |
| `AuthError` | `Error` |
| `Idle` | `Disconnected(0)` |

### Transitions

```
Idle → AwaitingNetwork → TokenRefresh → Connecting → AwaitingHello → Active
AwaitingHello → Backoff (hello timeout)
Active → Backoff (watchdog / close / idle 4008)
Active → Superseded (4001) → Backoff
Backoff → AwaitingNetwork (after delay)
AuthError → Idle
```

### Timeouts and backoff (defaults)

| Parameter | Value | Notes |
|-----------|-------|-------|
| Hello timeout | 15_000 ms | Keep uncommitted Android value |
| `connectionLostTimeout` | 18 s | Java-WebSocket transport |
| Server RFC6455 ping | 10 s | `WS_PING_INTERVAL_MS` |
| Server idle | 90 s | **Owner pending:** 120 s for slow networks |
| Client heartbeat | 10 s from hello (min 5 s) | |
| Heartbeat ACK watchdog | `(2 × interval + 5 s)` → 25 s @ 10s | Poll every 1 s |
| Reconnect backoff | 1, 2, 5, 10, 30 s + **full jitter** | Cap 30 s; infinite retries |
| Post-4001 / auth flap | 60 s flat backoff | |
| Outbox ACK timeout | 30 s per entry | |
| REST fallback trigger | 3 failed WS ACK cycles | Per entry |
| NetworkCallback debounce | 500 ms | On `NET_CAPABILITY_VALIDATED` |
| JWT refresh margin | 120 s before exp | |

### Duplicate-session rules

| Event | Server | Client |
|-------|--------|--------|
| Second WS same machine | Close old with **4001** | Increment generation; no outbox flush until new hello |
| Stale ACK after reconnect | — | Drop if generation mismatch |
| Concurrent connect jobs | — | Cancel prior; only latest generation sends |
| Process death | — | Outbox persists; reconnect after boot |

**Invariant:** One active session per machine; one flush per hello per generation.

### ACK routing

```
onMessage:
  if stale generation → DROP
  if hello → handleHello(); flushOutbox(generation)
  if ack → outbox.markAcked(correlationId) ?? ackRouter.dispatch(envelope)
  if error → outbox.markError(correlationId, code)
```

| ACK payload signal | Handler |
|--------------------|---------|
| `payload.saleId` | Sales outbox |
| `payload.dailyRemainingMl` / `volumeAfterMl` | Loyalty water |
| `payload.schemaHash` | Cells sync |
| `payload.technicianKeyId` | Technician session |
| Pending outbox `correlationId` | Outbox store |
| Else | Orphan log + metric |

**Retryable errors:** `TIMEOUT`, `INTERNAL`, 503-class.  
**Terminal:** `INVALID_PAYLOAD`, `NOT_FOUND` → `REJECTED` or `DEAD` after max attempts.

### Network and lifecycle (Android)

`NetworkCallback` on validated network → connect (500ms debounce). On loss: degraded until watchdog closes socket. Foreground service for kiosk; respect telemetry pause. Doze: watchdog + reconnect only.

---

## Unified Machine Outbox

### Entry model (Room + Prisma canonical)

```kotlin
MachineOutboxEntry(
  localId: UUID,
  machineId: UUID,
  kind: OutboxKind,           // sale_report | loyalty_water_use | loyalty_water_use_offline
  idempotencyKey: String,
  messageId: UUID,             // new each attempt allowed
  payloadJson: String,
  status: PENDING | IN_FLIGHT | ACKED | REJECTED | DEAD,
  attempts: Int,
  nextRetryAt: Instant,
  lastErrorCode: String?,
  sessionGenerationAtSend: Long?,
  createdAt / ackedAt
)
```

### Idempotency keys

| Kind | Client `idempotencyKey` | Server domain key |
|------|-------------------------|-------------------|
| `sale_report` | `saleId` | `(machineId, saleId)` |
| `loyalty_water_use` | `requestUuid` | `WaterHistory.requestUuid` |
| `loyalty_water_use_offline` | `requestUuid` | Same on promote |

### Lifecycle

```
enqueue → PENDING
send (WS/REST) → IN_FLIGHT (+ 30s ACK timer)
ack ok | idempotent | deduplicated → ACKED → archive/delete after 7d
terminal error → REJECTED
timeout / socket fail → PENDING + backoff
attempts > 50 → DEAD (ops alert)   // owner pending: threshold + alert route
```

### WS uplink

Unchanged envelope v2 for `sale.report` and `loyalty.water.use`. Optional audit field `clientSequence: Long` (ignored if unknown).

Fix today: **do not** `markSent` on socket write; wait for ACK with `saleId`.

### REST batch fallback

```
POST /api/v1/machines/outbox/batch
Authorization: Bearer <machine JWT>

{
  "batchId": "<uuid>",
  "entries": [{ "kind", "messageId", "sentAt", "idempotencyKey", "payload" }]
}
```

Response per entry: `{ messageId, status: acked|rejected, payload|code }`.

Server rules:

- Batch idempotent by `(machineId, batchId)`.
- Max **50** entries per batch.
- Same domain handlers as WS.

### Flush triggers

1. On `hello` (primary).
2. On `Active` after supersede recovery.
3. Every **30 s** while Active if PENDING non-empty.
4. On network validated (REST if WS down).
5. Debug/service-menu manual flush.

---

## Offline Entitlement

### Components

| Component | Storage | Role |
|-----------|---------|------|
| `EntitlementCache` | Room | Latest signed grant + status snapshot per `(clientId, machineId)` |
| `GrantValidator` | — | Signature, TTL, allowance checks |
| `OfflineUsageLedger` | Room append-only | Offline pour records before sync |
| `ReconcileService` | — | Promote ledger → outbox on reconnect |

### Grant payload (server-issued)

```json
{
  "grantId": "uuid",
  "clientId": "uuid",
  "machineId": "uuid",
  "issuedAt": "ISO",
  "expiresAt": "ISO",
  "dailyRemainingMlAtIssue": 500,
  "maxOfflinePours": 1,
  "maxOfflineVolumeMl": 500,
  "subscriptionLevelId": "uuid",
  "signature": "base64url(...)"
}
```

Delivery: WS `loyalty.offline.grant` push or embedded in `loyalty.status.get` ack (v3).

Signing: **Ed25519 preferred** (owner pending: HMAC vs Ed25519 rotation policy). Server-only key.

### Provisional limits (security review — until owner O-1)

| Parameter | Value |
|-----------|-------|
| Grant TTL | **4 h** |
| Max offline pours / grant / client | **1** |
| Max offline volume / grant / client | **500 ml** |
| Hard deny stale grant | **72 h** |
| Clock skew tolerance | **±5 min** |

Tag constants `PROVISIONAL_OFFLINE_LIMITS`; bump only after owner sign-off.

### Offline pour gate

```
canPourOffline(clientId, volumeMl):
  grant = cache.validGrant(clientId)
  verify signature + expiry + machineId match
  check ledger pours/volume against grant caps and dailyRemainingMlAtIssue
  allow → append ledger(requestUuid, volumeMl, drinkId, at)
        → enqueue loyalty_water_use_offline
```

Deny codes: `OFFLINE_NO_GRANT`, `OFFLINE_GRANT_EXPIRED`, `OFFLINE_POUR_LIMIT`, `OFFLINE_VOLUME_LIMIT`, `OFFLINE_DAILY_EXCEEDED`.

**Scope (brief non-goal):** offline card/SBP payments. Offline pours for **known subscription only** — not new subscribe/payment flows.

### Reconcile (on hello + grant refresh)

1. Load unsynced `loyalty_water_use_offline` ledger rows.
2. Promote each to outbox `loyalty_water_use` (same `requestUuid`).
3. Server idempotent ack or `CONFLICT` / `NOT_FOUND`.
4. Update cache from ack; on reject → `REJECTED` + audit (**owner pending O-3:** compensation policy).

### Conflict rules

| Situation | Resolution |
|-----------|------------|
| Duplicate `requestUuid` | Server idempotent ack |
| Server limit < local assumed | Server wins; refresh grant online |
| Subscription cancelled offline | Reject pours after `cancelledAt` |
| Invalid signature | Purge cache; require online |

---

## Technician Keys

### Format

- Code: `KEY-` + 8 Crockford alnum (`A-Z`, `2-7`).
- QR: plain text code (no URL wrapper).
- Legacy `EMP:` accepted; normalize to KEY lookup.
- Store **SHA-256 hash** only.

### Backend schema (additive)

```
TechnicianKey: id, publicCode, codeHash, label, scopes[], machineIds[], active, revokedAt, expiresAt, ...
TechnicianKeyAudit: keyId, machineId, serialNumber, action, success, failureCode, createdAt
```

Admin REST (dashboard `MASTER`/`ADMIN`):

- `POST/GET /api/v1/technician-keys`
- `POST /api/v1/technician-keys/:id/revoke`
- `POST /api/v1/technician-keys/:id/reissue`

### Machine WS (online-only)

Uplink `technician.key.validate`:

```json
{ "code": "KEY-DM1NF5KS", "requestedScope": "service.menu" }
```

Ack: `{ ok, technicianKeyId, scopes[], sessionToken, expiresAt }`  
Errors: `KEY_REVOKED`, `KEY_EXPIRED`, `KEY_SCOPE_DENIED`, `KEY_MACHINE_DENIED`, `KEY_NOT_FOUND`

**Default scopes v1:** `service.menu` only (**owner pending O-4:** add `cells.calibrate`, etc.).

### Android flow (replaces dead `authCodeRequestExport`)

```
scan KEY-* → if !Connected → user message "needs network"
           → technician.key.validate
           → ok → open service menu; sessionToken in memory (15 min)
```

No offline technician validation. No positive key cache by default.

### Revocation

Server `revokedAt` → immediate deny. Optional deny-list cache max **5 min** (disabled by default).

---

## Protocol, Compatibility, Rollback

### Version matrix

| Version | Features |
|---------|----------|
| **2** (current) | WS sales/loyalty/cells; no REST outbox; no offline grant |
| **3** (target) | Unified outbox ACK, REST batch, offline grant, technician validate |

### Hello v3 (additive)

```json
{
  "protocolVersion": 3,
  "heartbeatIntervalSeconds": 10,
  "supportedMessageTypes": ["...", "technician.key.validate", "loyalty.offline.grant"],
  "capabilities": {
    "outboxRestSync": true,
    "offlineEntitlementGrant": true,
    "technicianKeyValidate": true,
    "loyaltyWaterUseAckV2": true
  }
}
```

Android: if `protocolVersion < 3` or capability false → legacy WS-only path.

### Feature flags (telemetry env)

| Flag | Rollout |
|------|---------|
| `FEATURE_WS_PROTOCOL_V3` | true (hello only) |
| `FEATURE_OUTBOX_REST_SYNC` | false → true after phase 2 soak |
| `FEATURE_OFFLINE_ENTITLEMENT` | false → true after phase 3 |
| `FEATURE_TECHNICIAN_KEYS` | false → true after phase 4 |

### Migrations

**viwa-telemetry:** `MachineOutboxBatchDedup`, `TechnicianKey`, `TechnicianKeyAudit`, `OfflineGrantAudit` (optional); fix `loyalty.payment.complete` idempotency by `requestUuid`; expose `WS_IDLE_TIMEOUT_MS` via env.

**viwa-android:** Room tables `machine_outbox`, `entitlement_cache`, `offline_usage_ledger`; migrate JsonStore `pending_sales`; deprecate `PendingSaleStatus.SENT`.

### Rollback

| Component | Rollback action |
|-----------|-----------------|
| FSM | Feature flag → legacy manager (keep adapter one release) |
| Room outbox | Disable v3; read JsonStore fallback if Room empty |
| REST batch | Flag off → WS-only |
| Offline grant | Flag off → deny offline pours |
| Technician keys | Flag off → validate endpoint 404; no service menu via KEY |

### Owner decisions (block prod deploy until answered)

| ID | Topic |
|----|-------|
| O-1 | Offline TTL and pour/volume caps (provisional 4h / 1 / 500ml) |
| O-2 | Offline SUBSCRIBE sale.report allowed or water-only |
| O-3 | Reconcile rejection compensation |
| O-4 | Technician scopes for v1 |
| O-5 | WS idle 90s vs 120s |
| O-6 | Dead-letter threshold and alerting |
| O-7 | Grant signing algorithm and rotation |
| O-8 | Any technician offline cache ever |

Safe without owner: FSM, ACK-gated outbox, REST behind flag, dual liveness, KEY format, idempotency, v3 hello negotiation.

See also: `brief.md`, `decomposition.md`, `viwa-telemetry/docs/contracts/`.
