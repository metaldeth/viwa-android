# task-02 — test report (Products REST CRUD + tastes)

**Repo:** `viwa-telemetry`  
**Date:** 2026-07-19  
**Scope:** ProductsModule, RejectMachineJwtGuard, integration spec `products.spec.ts`

## Unit / guard tests (no DATABASE_URL)

| Suite | Result | Notes |
|-------|--------|-------|
| `test/taste-media-keys.spec.ts` | PASS (3) | 14-key allowlist + RU labels |
| `test/products.service.spec.ts` | PASS (5) | list, INVALID_TASTE, delete+SetNull, listTastes |
| `test/reject-machine-jwt.guard.spec.ts` | PASS (3) | no header, invalid bearer pass-through, valid JWT → 403 |

**Subtotal:** 3 suites, **11 passed**, 0 failed.

## Integration tests (`test/products.spec.ts`)

**Status:** SKIPPED — `DATABASE_URL` not set in agent environment.

When `DATABASE_URL` is configured, the suite covers task-02 acceptance cases:

1. POST valid product → 201, persisted fields
2. POST invalid tasteMediaKey → 400 `INVALID_TASTE`
3. PATCH name + taste → updated fields
4. DELETE product used in cell → `cells.productId=null`, product removed
5. GET `/products/tastes` → 14 items with RU names
6. VIEWER POST/PATCH/DELETE → 403
7. Machine JWT bearer on POST → 403
8. Machine JWT bearer on GET `/products` → 403

## Full `npm test` (repo root)

**Result:** FAIL (pre-existing, outside task-02 scope)

- `test/cell-schema-reconcile.service.spec.ts` — TS2732 JSON fixture imports (fixtures exist; `resolveJsonModule` / tsconfig issue in WIP machine-cells task)
- Other suites: 13 passed, 6 skipped (DB-dependent)

## `npm run build`

**Result:** FAIL (pre-existing WIP in `src/machine-cells/`)

- `cell-content-apply.service.ts` — `productId` vs Prisma nested `product` API
- Same JSON fixture TS errors in test files included in `tsconfig.build.json`

**task-02 files:** no TypeScript errors (`npx tsc --noEmit` filtered to `products` / `reject-machine` — clean).

## Manual verification checklist

| Criterion | Status |
|-----------|--------|
| ProductsModule wired in AppModule | Done |
| GET/POST/PATCH/DELETE `/api/v1/products` | Implemented |
| GET `/api/v1/products/tastes` (14 keys) | Implemented |
| INVALID_TASTE on bad key | Unit-tested |
| VIEWER mutation → 403 | Integration spec (needs DB) |
| Machine JWT REST → 403 | Guard unit + integration spec (needs DB) |
| DELETE SetNull on cells | Service unit + integration spec (needs DB) |
| No WS snapshot push after CRUD (C-2) | N/A — no WS hook added |

## Commands run

```powershell
cd c:\viwa\viwa-telemetry\apps\api
node --experimental-vm-modules ../../node_modules/jest/bin/jest.js --runInBand test/products.service.spec.ts test/reject-machine-jwt.guard.spec.ts test/taste-media-keys.spec.ts
# → 3 passed, 11 tests

cd c:\viwa\viwa-telemetry
npm test
# → FAIL (cell-schema-reconcile WIP)

npm run build -w @wiva/api
# → FAIL (machine-cells WIP)
```

## Recommendation

Re-run `test/products.spec.ts` and full `npm test` / `npm run build` after machine-cells WIP is merged or fixed and `DATABASE_URL` is available locally.
