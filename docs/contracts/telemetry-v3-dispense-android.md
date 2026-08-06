# Telemetry v3 — Android dispense contract

Aligned with backend `wiva-telemetry/docs/contracts/telemetry-v3-ingest.md`.

## Events

| Wire type | When | Idempotency key |
|-----------|------|-----------------|
| `telemetry.pour.report` | Online subscription flavored + plain hold pours | `requestUuid` |
| `telemetry.paid.complete` | Paid CARD/SBP flavored dispense — atomic transaction + linked pour | `transactionId` |
| `machine.water.usage.report` | After each local lifetime water increment | `reportedAt` |

Paid pours do **not** emit a separate `telemetry.pour.report`; backend creates pour from paid payload.

Offline subscription grant usage goes through **offline reconcile batch** (`soldAt` DTO), not live `telemetry.pour.report`.

Filter usage is derived server-side from immutable lifetime water total and replacement baseline — Android does **not** maintain a separate filter counter or reset lifetime total on filter replacement.

## PourEvent (`telemetry.pour.report`)

Required: `requestUuid`, `pouredAt`, `pourKind`, `volumeMl`. `clientId` optional (subscription card id when known; omitted for anonymous plain hold).

- `pourKind`: `FLAVORED` | `PLAIN_WATER`
- **Must not** include `grantId` (backend rejects)
- Subscription flavored: `productId`, `productNameSnapshot`, `strength` (`WEAK`|`STANDARD`|`STRONG`), `strengthRatio` (0.9/1.0/1.1), `syrupMlActual` (integer)
- Optional actual recipe (from dosage sent for that pour): `recipeDrinkVolumeMl` (int), `recipeWaterMl`, `recipeProductMl`, `conversionFactor` (nullable double)
- Plain hold: `plainWaterType` (`FILTERED`|`COLD`|`SPARKLING`), measured `volumeMl`, no product fields

## Paid complete (`telemetry.paid.complete`)

Flat atomic payload (no nested `pour`):

Required: `transactionId`, `requestUuid`, `occurredAt`, `productId`, `productNameSnapshot`, `volumeMl` (300|700), `strength`, `strengthRatio`, `syrupMlActual`, `amountKopecks`, `payMethod` (`CASH`|`CARD`|`SBP`|`OTHER`).

Optional actual recipe: `recipeDrinkVolumeMl`, `recipeWaterMl`, `recipeProductMl`, `conversionFactor` (nullable).

Ack: `{ ok: true, transactionId, pourId }` or idempotent variant; outbox ack by `transactionId` or WS `correlationId`.

Pour ack: `{ requestUuid?, volumeMl, dailyRemainingMl, pourId }`; outbox ack by `requestUuid` or `correlationId`.

## Machine water usage (`machine.water.usage.report`)

Payload: `{ totalMl: nonnegative integer, reportedAt: ISO-8601 UTC }`.

- `totalMl` — absolute local lifetime total after increment (not delta)
- Enqueued durably after every hardware read that contributes to `WATER_USAGE_ML`
- Backend stores max `totalMl`; filter wear is derived server-side

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

## Backward compatibility

Older payloads without optional recipe fields remain valid. Codecs omit null/absent optional keys on encode; decode ignores unknown keys.

Optional recipe fields (`recipeDrinkVolumeMl`, `recipeWaterMl`, `recipeProductMl`) are **display ml** (one decimal) derived from integer deci-ml identity on device. Values reflect the **base effective recipe** used for the pour (typically 300 ml base), not the scaled pour volume.

**Effective source (Phase C):** when `FEATURE_RECIPE_SYNC` + managed hello gate + `FEATURE_RECIPE_POUR_FROM_EFFECTIVE` are all enabled, [PreparingManager](app/src/main/java/com/viwa/android/services/preparing/PreparingManager.kt) resolves dosage via [InventoryCellRecipeSupport.resolvePourDosage](app/src/main/java/com/viwa/android/domain/inventory/InventoryCellRecipeSupport.kt) from durable `CellEffectiveRecipeStore`. Report-only rollout keeps pour on [TelemetryCellsDefaultDosage](app/src/main/java/com/viwa/android/domain/customer/TelemetryCellsDefaultDosage.kt) while recipe uplink runs.

**Service assign (UC-2 A4):** operator product change sends `cells.content.report` with `operatorOverride=true` only; server enqueues separate `ASSIGN_COPY` recipe command (not embedded in content).
