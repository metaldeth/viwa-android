# task-03 — test report

**Repo:** `viwa-telemetry`  
**Date:** 2026-07-19

## REST integration tests

| # | Case | Result |
|---|------|--------|
| 1 | GET empty machine (post-register) → empty items, schemaHash null | SKIP (no DATABASE_URL) / written |
| 2 | GET after DB seed → denormalized productName, tasteMediaKey | SKIP / written |
| 3 | PATCH productUuid + prices → DASHBOARD, contentRevision++ | SKIP / written |
| 4 | PATCH with volume → 400 `VOLUME_READ_ONLY` | SKIP / written |
| 5 | PATCH unknown uuid → 404 `CELL_NOT_FOUND` | SKIP / written |
| 6 | VIEWER PATCH → 403 | SKIP / written |
| 7 | Machine JWT PATCH → 403 | SKIP / written |

**File:** `apps/api/test/machine-cells.spec.ts`

> Integration suite uses `describeIfDb` (same as `products.spec.ts`). Locally `DATABASE_URL` was not set — 7 DB suites skipped; tests compile and are ready for CI/Postgres.

## Implementation

| Component | Path |
|-----------|------|
| Controller | `apps/api/src/machine-cells/machine-cells.controller.ts` |
| REST service | `apps/api/src/machine-cells/machine-cells-rest.service.ts` |
| DTO | `apps/api/src/machine-cells/dto/machine-cells.dto.ts` |
| Mapper | `apps/api/src/machine-cells/machine-cells.mapper.ts` |
| Volume guard | `apps/api/src/machine-cells/reject-volume-patch.interceptor.ts` |
| Module + AppModule import | `machine-cells.module.ts`, `app.module.ts` |
| Error helper | `notFoundApiError` in `common/api-errors.util.ts` |

## Verification commands

| Command | Exit code |
|---------|-----------|
| `npm test` (root) | 0 |
| `npm run build` (root) | 0 |

## Scope notes

- Extended existing `MachineCellsModule` (apply/snapshot services unchanged).
- No WS push on PATCH (task-05).
- `MachineCellsModule` imported in `AppModule` without `MachinesModule` forwardRef.
- Structural PATCH fields stripped by ValidationPipe whitelist (ignored per architecture).

## Acceptance mapping

- TZ #10, #11, #12 — REST GET/PATCH cells, authZ, volume read-only.
- OQ-3 — structural fields not accepted on dashboard PATCH.
- OQ-8 — PATCH sets `contentSource=DASHBOARD`, bumps `cellsContentRevision`.
