# WS Offline Resilience — Decomposition

**Session:** `ws-offline-resilience`  
**Repos:** `viwa-android` (Android), `viwa-telemetry` (backend)  
**Architecture:** `architecture.md`

Parallel work is grouped by phase. Critical path: **P0 → P1 → P2 → P3 → P5**. Phase 4 can start after P1.

---

## Repo ownership

| Area | Primary repo | Key modules |
|------|--------------|-------------|
| Connection FSM, NetworkCallback, outbox Room | `viwa-android` | `MvpTelemetryWebSocketManager`, new `TelemetryConnectionFsm`, `MachineOutboxDao` |
| ACK router, sales/loyalty sync | `viwa-android` | `TelemetrySalesSyncCoordinator`, `ViwaTelemetryService` |
| Offline grant cache/ledger/reconcile | `viwa-android` | new `EntitlementCache`, `OfflineUsageLedger`, `ReconcileService` |
| Technician scan coordinator | `viwa-android` | `TechnicianKeyServiceMenuCoordinator` |
| WS server, registry, hello | `viwa-telemetry` | `machines-ws.service.ts`, `machines-ws.registry.ts` |
| Outbox batch REST | `viwa-telemetry` | new `machines-outbox.controller.ts` |
| Domain handlers (sale, water) | `viwa-telemetry` | `sales-ws.handler.ts`, `loyalty-machine-ws.handler.ts` |
| Offline grant issue | `viwa-telemetry` | new grant service + push |
| Technician keys admin + validate | `viwa-telemetry` | new module + Prisma models |
| Contracts | `viwa-telemetry` | `docs/contracts/machine-outbox.md` (new) |
| Chaos/soak harness | `viwa-android` | emulator scripts, session docs |

**Cross-repo contract first:** P0-1 blocks P2-4 and P2-1 payload alignment.

---

## Phase 0 — Foundation

**Goal:** Contracts, ACK routing skeleton, preserve uncommitted WS fixes, backend hygiene.

| ID | Task | Repo | Owner | Deps |
|----|------|------|-------|------|
| P0-1 | Write `machine-outbox.md` contract (entry model, batch REST, ACK equivalence) | telemetry | backend | — |
| P0-2 | `AckRouter` interface + table-driven unit tests | android | android | — |
| P0-3 | Review/preserve uncommitted hello timeout, heartbeat watchdog, `connectionLostTimeout=18` | android | android | — |
| P0-4 | `WS_IDLE_TIMEOUT_MS` from env; `loyalty.payment.complete` idempotency by `requestUuid` | telemetry | backend | — |

**Acceptance**

- [ ] Contract reviewed; idempotency key table matches `architecture.md`
- [ ] `AckRouterTest`: routes `saleId`, water ack fields, `schemaHash`, orphan
- [ ] Existing WS manager unit tests green with uncommitted changes
- [ ] `ws.spec.ts` / loyalty tests cover payment.complete replay

**Parallel:** P0-1, P0-2, P0-3, P0-4 independent.

---

## Phase 1 — Connection resilience

**Goal:** Explicit FSM, session fencing, network-triggered reconnect, supersede safety.

| ID | Task | Repo | Owner | Deps |
|----|------|------|-------|------|
| P1-1 | `TelemetryConnectionFsm` + `sessionGeneration`; wire into coordinator | android | android | P0-2 |
| P1-2 | `ConnectivityManager.NetworkCallback` (validated, debounce 500ms) | android | android | P1-1 |
| P1-3 | Close 4001 handling; defer flush until new hello | android | android | P1-1 |
| P1-4 | Structured FSM transition logging / debug metrics | android | android | P1-1 |
| P1-5 | Fix chaos soak harness (iptables target IP, timezone in Wait-ForOnline) | android | android | — |

**Acceptance**

- [ ] Unit: stale ACK dropped after generation bump
- [ ] Unit: 4001 increments generation; no duplicate flush callback
- [ ] Emulator: airplane mode toggle → reconnect without user action < 30s
- [ ] Emulator: dual connect → one active session; other gets 4001
- [ ] Soak script runs 30 min without harness false failures

**Gate:** Brief acceptance #1 and #2.

---

## Phase 2 — Unified outbox (sales)

**Goal:** Room outbox; sales ACK-gated; REST batch fallback behind flag.

| ID | Task | Repo | Owner | Deps |
|----|------|------|-------|------|
| P2-1 | Room `machine_outbox` + migration from JsonStore `pending_sales` | android | android | P0-1 |
| P2-2 | `TelemetrySalesSyncCoordinator` → ACK-gated (not socket write) | android | android | P1-1, P2-1 |
| P2-3 | Wire `sale.report` ack `{ saleId }` through `AckRouter` | android | android | P0-2, P2-2 |
| P2-4 | `POST /api/v1/machines/outbox/batch` + batch dedup table | telemetry | backend | P0-1 |
| P2-5 | Android REST fallback sender (after 3 WS ACK failures) | android | android | P2-4, ADR-010 flag |
| P2-6 | Dead-letter at 50 attempts + log/metric hook | both | both | P2-1, P2-4 |

**Acceptance**

- [x] Unit: send fail → stays PENDING; ack → ACKED/removed
- [x] Unit: duplicate ack `idempotent: true` → ACKED once
- [ ] Integration: mock WS ack with `saleId` clears outbox
- [ ] API test: batch `(machineId, batchId)` idempotent replay
- [ ] Kill app after pour → reboot → exactly **one** sale row server-side
- [x] `concentrationRatio`: omit when null (contract compliance)

**Gate:** Brief acceptance #4 (sales path).

**Flag:** `FEATURE_OUTBOX_REST_SYNC=false` until soak pass.

---

## Phase 3 — Loyalty water + offline entitlement

**Goal:** Durable water.use; signed grant delta-sync; offline pour gate + reconcile.

| ID | Task | Repo | Owner | Deps |
|----|------|------|-------|------|
| P3-1 | `loyalty.water.use` → outbox (online path) | android | android | P2-1 |
| P3-2 | Grant issue + delta REST + reconcile batch | telemetry | backend | P0-1 |
| P3-3 | Room `entitlement_cache` + `GrantValidator` (Ed25519) | android | android | P3-2 |
| P3-4 | Room `offline_usage_ledger` + pour gate (tariff limits) | android | android | P3-3 |
| P3-5 | Delta sync + `ReconcileCoordinator` on hello/network | android | android | P3-1, P3-4 |
| P3-6 | Hello v3 signing keys + bounded clock | android | android | P3-2 |

**Android deliverables (2026-07-27):**

- Room DB **v3**: `entitlement_cache`, `offline_usage_ledger`
- `OfflineGrantVerifier`, `OfflineGrantsDeltaSyncCoordinator`, `OfflineReconcileCoordinator`
- Crash-safe `OfflinePourTransactionCoordinator` (RESERVED → POURING → FINALIZED → ENQUEUED)
- Online `loyalty.water.use` via durable outbox; offline scan + pour without WS
- Unit tests: canonical signing, verifier, limits, crash recovery

**Acceptance**

- [x] Unit: invalid signature → deny pour
- [x] Unit: second pour same grant → `OFFLINE_POUR_LIMIT`
- [x] Unit: 600ml request → `OFFLINE_VOLUME_LIMIT`
- [ ] Emulator: offline 1 pour → reconnect → one `WaterHistory` row (`requestUuid`)
- [ ] Replay same `requestUuid` → server idempotent ack; no double deduct
- [ ] Grant expired after TTL → offline deny until online refresh

**Gate:** Brief acceptance #3 and #4 (loyalty path).

**Flag:** `FEATURE_OFFLINE_ENTITLEMENT=false` until owner O-1/O-2 confirmed or provisional explicitly accepted for pilot.

---

## Phase 4 — Technician keys

**Goal:** Telemetry-managed technician keys with online validate + signed offline allowlist.

| ID | Task | Repo | Owner | Deps |
|----|------|------|-------|------|
| P4-1 | Prisma `TechnicianKey`, `TechnicianKeyAudit` + admin REST | telemetry | backend | — |
| P4-2 | WS `technician.key.validate` handler (scope + machine binding) | telemetry | backend | P4-1 |
| P4-3 | `TechnicianKeyServiceMenuCoordinator` → online/offline validate | android | android | P1-1, P4-2 |
| P4-4 | Dashboard QR generate/print for KEY codes | telemetry | backend | P4-1 |
| P4-5 | Remove dead `sendAuthCodeRequest` / orphan WS types | android | android | P4-3 |

**Android deliverables (2026-07-27):**

- Room DB **v4**: `technician_allowlist_cache`, `technician_allowlist_state`, `technician_audit_outbox`
- `TechnicianKeyNormalizer`, `TechnicianKeyFingerprint`, `TechnicianAllowlistVerifier`, delta sync (5 min)
- Online REST/WS validate + offline signed allowlist for `service.menu`
- Durable audit outbox with idempotent `requestUuid`; in-memory session TTL 15 min
- `TechnicianServiceMenuAccess` exposed via `ServiceViewModel.technicianSession`
- Unit tests: normalization/fingerprint, signature/tamper, offline deny paths, audit idempotency

**Acceptance**

- [x] Android: scan KEY online → service menu opens (when server validates)
- [x] Android: scan KEY offline → opens when signed allowlist valid
- [x] Android: offline stale/revoked/scope denied → blocked
- [x] Android: high-risk scopes blocked offline
- [ ] API: create/revoke key; plaintext shown once
- [ ] WS: valid KEY + scope → ack with `sessionToken`
- [ ] WS: revoked key → `KEY_REVOKED` within 1s; audit row
- [ ] WS: wrong machine → `KEY_MACHINE_DENIED`

**Gate:** Brief acceptance #5.

**Flag:** `FEATURE_TECHNICIAN_KEYS=false` until admin UI ready.

**Owner before prod:** O-4 scopes (default `service.menu` only).

---

## Phase 5 — Hardening and release

**Goal:** Protocol v3 negotiation, load/soak, ops runbook.

| ID | Task | Repo | Owner | Deps |
|----|------|------|-------|------|
| P5-1 | Hello v3 + `capabilities` negotiation both sides | both | both | P2–P4 |
| P5-2 | Load test outbox batch (target 1000 entries/min/machine) | telemetry | backend | P2-4 |
| P5-3 | Full offline matrix on emulator (server restart, net loss, reboot) | android | android | P1–P4 |
| P5-4 | Session log + ops rollback runbook | docs | either | P5-1 |
| P5-5 | Enable flags staged: REST → offline (pilot) → technician | telemetry | backend | P5-3 |

**Acceptance**

- [ ] v2 client unchanged on production hello v2
- [ ] v3 client degrades gracefully when flags false
- [ ] Chaos matrix documented in session log; no operator IP firewall
- [ ] 30 min post-recovery stability (heartbeat acks resume)
- [ ] Rollback steps verified in staging

**Gate:** Brief acceptance #6 + full brief checklist.

---

## Dependency graph

```
P0-1 ──────────────────────────────► P2-4
P0-2 ──► P1-1 ──► P2-2 ──► P3-1 ──► P5-3
              ├──► P4-3
              └──► P1-2, P1-3
P2-1 ──► P2-2, P3-1
P3-2 ──► P3-3 ──► P3-4 ──► P3-5
P4-1 ──► P4-2 ──► P4-3
All ──► P5-1
```

**Suggested parallel tracks after P0:**

| Track A (Android connectivity) | P1-* → P2-1..P2-3 |
| Track B (Backend REST/outbox) | P0-1 → P2-4 → P2-6 |
| Track C (Technician) | P4-1 → P4-2 (after P1-1 for android) |
| Track D (Offline) | P3-* after P2-1 |

---

## Acceptance tests summary (brief mapping)

| Brief # | Phases | Key test |
|---------|--------|----------|
| 1 Half-open reconnect | P1 | Airplane / idle watchdog / server restart |
| 2 No duplicate effects | P1, P2, P3 | Generation fence + idempotency replay |
| 3 Bounded offline entitlement | P3 | 1 pour / 500ml / 4h grant |
| 4 Survive reboot + sync | P2, P3 | Outbox + ledger persist |
| 5 Technician keys | P4 | Validate, revoke, audit |
| 6 Chaos/soak | P1, P5 | 30 min stable + safe iptables |

---

## Verification commands

| Repo | Command | When |
|------|---------|------|
| android | `./gradlew test` | Each phase |
| android | `./gradlew assembleDebug` | UI/device phases |
| telemetry | `npm test` / project test command | Backend phases |
| telemetry | Prisma migrate dev (local) | Schema phases |
| android | Emulator chaos script (P1-5, P5-3) | Gates |

---

## Out of scope (reminder)

- Docker / compose changes
- Offline card/SBP payment flows
- Unrelated UI refactors
- Kiosk-local technician trust / browser AES offline keys

---

## Owner blockers before production

| ID | Blocks |
|----|--------|
| O-1 | Phase 3 prod flag (`FEATURE_OFFLINE_ENTITLEMENT`) — pilot may use provisional 4h/1/500ml |
| O-2 | Whether offline `sale.report` with SUBSCRIBE is allowed alongside water.use |
| O-3 | Reconcile rejection UX/compensation |
| O-4 | Technician scope list beyond `service.menu` |
| O-5 | Server idle timeout env value |
| O-6 | Dead-letter alerting destination |
| O-7 | Grant signing production key ceremony |
| O-8 | Technician offline cache policy (default: none)

---

## Deliverables checklist

- [x] `architecture.md`
- [x] `decomposition.md`
- [x] `machine-outbox.md` contract (P0-1, telemetry)
- [ ] Session log on phase gates
- [x] Android Phase 1 connection resilience (FSM, fencing, NetworkCallback) — see `docs/agents/ws-offline-resilience/decomposition.md` P1-*
- [x] Android Phase 2 unified outbox (Room, ACK-gated sales, REST fallback scaffold) — see P2-*
- [x] Android Phase 3 offline entitlement (Room v3, Ed25519 grants, ledger, delta/reconcile) — see P3-*
- [x] Android Phase 4 technician keys (Room v4, signed allowlist, audit outbox, online/offline validate) — see P4-*
