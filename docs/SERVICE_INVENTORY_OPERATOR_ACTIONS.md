# Service inventory — operator actions (Android)

Scope: **Обслуживание → Остатки** (`ViwaInventoryVolumesTab`).

## Catalog source

- Product picker uses **`snapshot.products[]`** from local `telemetryCellsSnapshot` (MVP WS downlink).
- No REST product fetch in service menu.

## Сменить вкус (operator override)

| Aspect | Behavior |
|--------|----------|
| Wire | `cells.content.report` with **`operatorOverride: true`** on affected cell only |
| Prices | `dosage1Price` / `dosage2Price` (300/700 ml) **unchanged** on assignment |
| Persistence | **Send-before-persist**: local snapshot + customer UI update **only after** WS ack `{ ok: true, applied: N>0 }` |
| Failure | Offline, send error, `applied=0`, timeout → **no local change**, honest error in UI |
| Reconnect | Automatic/reconnect content reports **omit** `operatorOverride` |

Dashboard PATCH remains source of truth for content/prices when conflicting with machine reports without operator override.

## Рецепт (local preview)

- Uses shared **`TelemetryCellsDefaultDosage`** (same basis as `TelemetryCellsSnapshotAdapter` customer ChooseDrink template).
- **Not** server-managed recipe or telemetry recipe snapshot.
- Dialog shows scaled 300/700 ml water/syrup/dispenser from Android template + cell `conversionFactor`.

## Volume drafts (Остатки tab)

- Edited volume fields tracked as **dirty** per cell number.
- Snapshot refresh merges server volumes only into **non-dirty** rows.

## Legacy tab

`ViwaTelemetryInventoryTab` is **not mounted** in service menu rail. Dashboard owns content/prices; that tab’s save is best-effort uplink without server-ack gate — do not use for operator taste override.
