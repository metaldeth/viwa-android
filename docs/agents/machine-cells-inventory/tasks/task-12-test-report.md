# task-12 — Integration E2E tests — test report

**Date:** 2026-07-19  
**Repo (primary):** `viwa-telemetry`  
**Optional Android:** not implemented (non-blocker)

## Deliverables

| File | Action |
|------|--------|
| `viwa-telemetry/apps/api/test/machine-cells-inventory.e2e-spec.ts` | **created** — 6 integration E2E cases |
| `viwa-telemetry/apps/api/jest.config.js` | **updated** — `testRegex` includes `.e2e-spec.ts` |
| `viwa-android/.../TelemetryCellsContractFixturesTest.kt` | **skipped** — cross-repo fixture path awkward; codec parity covered by existing `TelemetryCellsMessageCodecTest` |

## E2E test cases

| # | Case | Status | Notes |
|---|------|--------|-------|
| 1 | Full happy path (TZ #31): register → hello v2 → schema → POST product → PATCH → snapshot → volume → GET | **SKIP** | No `DATABASE_URL` / DB unreachable |
| 2 | Register → 0 cells in DB | **SKIP** | Same |
| 3 | Reconcile preserve: second schema same uuids keeps product/prices/volume | **SKIP** | Same |
| 4 | AuthZ: machine JWT REST PATCH → 403 | **SKIP** | Same |
| 5 | Reconnect: stale `clientContentRevision` → `cells.snapshot` | **SKIP** | Same |
| 6 | Duplicate `messageId` → dedup ack, no double apply | **SKIP** | Same |

**Skip reason:** `process.env.DATABASE_URL` unset in agent environment; with example URL (`localhost:5432`) Prisma fails — no local PostgreSQL/Docker.

Pattern matches existing integration specs (`machines-ws-cells.spec.ts`, `machine-cells.spec.ts`): `describeIfDb = process.env.DATABASE_URL ? describe : describe.skip`.

## Verification commands

```powershell
cd c:\viwa\viwa-telemetry
npm run test -w @wiva/api -- machine-cells-inventory.e2e-spec.ts
# Result: 6 skipped (no DATABASE_URL)

npm run test -w @wiva/api
# Result: 23 passed, 9 skipped (74 tests skipped total), exit 0

npm test
# Result: exit 0 (api + web)
```

With reachable PostgreSQL + migrations:

```powershell
$env:DATABASE_URL = "postgresql://wiva:wiva@localhost:5432/wiva_telemetry?schema=public"
npm run test -w @wiva/api -- machine-cells-inventory.e2e-spec.ts
```

## Coverage vs task-12 acceptance

| Requirement | Covered in E2E file |
|-------------|-------------------|
| Register → 0 cells | ✅ test #2 + step in happy path |
| WS hello v2 | ✅ happy path |
| `cells.schema.report` → ack `schemaHash` | ✅ happy path (validates `computeSchemaHash`) |
| REST POST product | ✅ happy path |
| REST PATCH → `contentSource` DASHBOARD | ✅ happy path |
| WS `cells.snapshot` with `products[]` + denormalized | ✅ happy path |
| `cells.volume.report` → GET reflects volume | ✅ happy path |
| Duplicate messageId dedup | ✅ dedicated test |
| Reconnect stale revision → snapshot | ✅ dedicated test |
| Machine JWT PATCH → 403 | ✅ dedicated test |
| Reconcile preserve | ✅ dedicated test |

Fixtures: reuses `test/fixtures/cells/schema-first-report.json` (no new fixture files required).

## Blockers / next steps

1. **Local/staging DB** required to execute E2E (not a code blocker).
2. Optional Android contract fixture test deferred — add `src/test/resources/cells/schema-first-report.json` copy if cross-repo parity test desired in CI.

## Git

Commit **not** created (per task instruction).
