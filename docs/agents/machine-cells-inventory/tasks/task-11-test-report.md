# task-11 — Contract documentation review

**Date:** 2026-07-19  
**Artifact:** `viwa-telemetry/docs/contracts/machine-cells-inventory.md`

## Doc review checklist

| # | Check | Result | Notes |
|---|-------|--------|-------|
| 1 | Every task-05 uplink type has payload schema + example | **PASS** | `cells.schema.report`, `cells.volume.report`, `cells.content.report` |
| 2 | Every task-05 downlink type documented | **PASS** | `cells.snapshot`; legacy `cells.volume.patch` noted as non-MVP |
| 3 | Hello v2 vs v1 delta documented | **PASS** | Table + link to `registration-machine-jwt.md` |
| 4 | Ack payloads match implementation | **PASS** | schema ack counts; volume/content `applied`; dedup |
| 5 | REST paths match controllers | **PASS** | See cross-check table below |
| 6 | Error codes listed | **PASS** | `INVALID_TASTE`, `VOLUME_READ_ONLY`, `CELL_NOT_FOUND`, `INVALID_THRESHOLDS` (+ WS codes) |
| 7 | C-1 denormalization | **PASS** | REST/snapshot vs uplink content |
| 8 | C-2 products[] + lazy refresh | **PASS** | |
| 9 | C-3 reconnect fields + push rules | **PASS** | Matches `MachineCellsSnapshotService` |
| 10 | C-5 contentSource algorithm | **PASS** | Matches `CellContentApplyService` + REST PATCH |
| 11 | schemaHash algorithm | **PASS** | Matches `schema-hash.util.ts` key order |
| 12 | AuthZ matrix | **PASS** | Session roles + RejectMachineJwtGuard |
| 13 | 14 tasteMediaKey allowlist | **PASS** | Matches `TASTE_MEDIA_KEYS` + Android catalog |
| 14 | Fixtures referenced | **PASS** | `apps/api/test/fixtures/cells/` (4 files) |
| 15 | Android doc links added | **PASS** | `SIMPLE_TELEMETRY_MVP_ANDROID.md`, FEATURE §10 |

## Cross-check: WS message types (task-05 / architecture)

| Type | Direction | In contract | In `WS_SUPPORTED_MESSAGE_TYPES` |
|------|-----------|-------------|-----------------------------------|
| `hello` | downlink | yes | yes |
| `heartbeat` | uplink | yes (baseline) | yes |
| `ack` | downlink | yes | yes |
| `error` | downlink | yes | yes |
| `cells.schema.report` | uplink | yes | yes |
| `cells.volume.report` | uplink | yes | yes |
| `cells.content.report` | uplink | yes | yes |
| `cells.snapshot` | downlink | yes | yes |

## Cross-check: REST paths

| Contract path | Controller | Match |
|---------------|------------|-------|
| `GET /api/v1/products` | `ProductsController.list` | yes |
| `GET /api/v1/products/tastes` | `ProductsController.listTastes` | yes |
| `POST /api/v1/products` | `ProductsController.create` | yes |
| `PATCH /api/v1/products/:id` | `ProductsController.update` | yes |
| `DELETE /api/v1/products/:id` | `ProductsController.delete` | yes |
| `GET /api/v1/machines/:machineId/cells` | `MachineCellsController.getCells` | yes |
| `PATCH /api/v1/machines/:machineId/cells` | `MachineCellsController.patchCells` | yes |

## Cross-check: tasteMediaKey (14)

| Source | Count | Match |
|--------|-------|-------|
| `TASTE_MEDIA_KEYS` (`taste-media-keys.ts`) | 14 | baseline |
| `TasteMediaKeyCatalog.ALL_KEYS` (Android) | 14 | **identical set** |
| Contract table | 14 | **identical set** |

Ordered keys verified equal: `cherry`, `blackberry-lime`, `coconut`, `cucumber`, `grapefruit`, `lemon`, `lime`, `lime-mint`, `orange`, `peach-mango`, `pomegranate-blueberry`, `raspberry`, `strawberry-lemongrass`, `watermelon`.

## Gaps / blockers

| ID | Item | Severity |
|----|------|----------|
| G-1 | `INVALID_THRESHOLDS` not emitted by API yet — documented as contract-reserved; web type exists | **info** (MVP gap, not doc blocker) |
| G-2 | Register response still advertises `protocolVersion: 1` while WS hello is v2 — documented explicitly | **info** |

## Verification

- Peer review: this report (review-ready).
- Links: relative paths from Android docs → `viwa-telemetry/docs/contracts/`.
- Code changes: none (docs only).
