# Telemetry v3 — Android dispense contract

Aligned with backend `wiva-telemetry/docs/contracts/telemetry-v3-ingest.md`.

## Events

| Wire type | When | Idempotency key |
|-----------|------|-----------------|
| `telemetry.pour.report` | Online subscription flavored + plain hold pours | `requestUuid` |
| `telemetry.paid.complete` | Paid CARD/SBP flavored dispense — atomic transaction + linked pour | `transactionId` |

Paid pours do **not** emit a separate `telemetry.pour.report`; backend creates pour from paid payload.

Offline subscription grant usage goes through **offline reconcile batch** (`soldAt` DTO), not live `telemetry.pour.report`.

## PourEvent (`telemetry.pour.report`)

Required: `clientId`, `requestUuid`, `pouredAt`, `pourKind`, `volumeMl`.

- `pourKind`: `FLAVORED` | `PLAIN_WATER`
- **Must not** include `grantId` (backend rejects)
- Subscription flavored: `productId`, `productNameSnapshot`, `strength` (`WEAK`|`STANDARD`|`STRONG`), `strengthRatio` (0.9/1.0/1.1), `syrupMlActual` (integer)
- Plain hold: `plainWaterType` (`FILTERED`|`COLD` only — no `SPARKLING`), measured `volumeMl`, no product fields

## Paid complete (`telemetry.paid.complete`)

Flat atomic payload (no nested `pour`):

Required: `transactionId`, `requestUuid`, `occurredAt`, `productId`, `productNameSnapshot`, `volumeMl` (300|700), `strength`, `strengthRatio`, `syrupMlActual`, `amountKopecks`, `payMethod` (`CASH`|`CARD`|`SBP`|`OTHER`).

Ack: `{ ok: true, transactionId, pourId }` or idempotent variant; outbox ack by `transactionId` or WS `correlationId`.

Pour ack: `{ requestUuid?, volumeMl, dailyRemainingMl, pourId }`; outbox ack by `requestUuid` or `correlationId`.

## Syrup integer (canonical 300 ml recipe base)

`dosage.product=30`, `recipeDrinkVolumeMl=300`:

`syrupMlActual = round(product * (volumeMl / 300) * strengthRatio)`

| volumeMl | WEAK (0.9) | STANDARD (1.0) | STRONG (1.1) |
|----------|------------|----------------|--------------|
| 300 | 27 | 30 | 33 |
| 700 | 63 | 70 | 77 |

Product ID source: `TelemetryCell.productUuid` propagated through `DrinkContainer.productUuid` → wire `productId`.

## Offline

Room `machine_outbox` + subscription offline ledger FSM; stable UUIDs at reserve/start; no duplicate enqueue per idempotency key. Offline pours skip `telemetry.pour.report` and sync via reconcile path with `soldAt`.
