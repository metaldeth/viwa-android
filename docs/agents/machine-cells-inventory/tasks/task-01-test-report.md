# task-01 — test report

**Repo:** `viwa-telemetry`  
**Date:** 2026-07-19

## Unit tests (allowlist)

| # | Case | Result |
|---|------|--------|
| 2 | `TASTE_MEDIA_KEYS` — ровно 14 ключей из FEATURE §3.1.4 | PASS |
| 3 | Каждый ключ имеет непустой RU label | PASS |
| — | `listTasteMediaKeysWithLabelsRu()` возвращает 14 пар mediaKey/nameRu | PASS |

**File:** `apps/api/test/taste-media-keys.spec.ts`

## Cleanup extension

| Case | Result |
|------|--------|
| `ExpiredRecordsCleanupService` удаляет `machine_ws_message_dedup` старше 7 дней | PASS |

**File:** `apps/api/test/expired-records.cleanup.spec.ts`

## Prisma / migration

| # | Case | Result | Notes |
|---|------|--------|-------|
| 1 | Migration applies cleanly | **NOT RUN** | Локально нет `.env` / `DATABASE_URL`; SQL создан в `prisma/migrations/20260719143000_machine_cells_inventory/` |
| 4 | Prisma client генерируется без ошибок | PASS | `npx prisma generate` exit 0 |

## Verification commands

| Command | Exit code |
|---------|-----------|
| `npm test` (root) | 0 |
| `npm run build` (root) | 0 |

## Changed files (telemetry)

- `apps/api/prisma/schema.prisma`
- `apps/api/prisma/migrations/20260719143000_machine_cells_inventory/migration.sql`
- `apps/api/src/products/taste-media-keys.ts`
- `apps/api/src/cleanup/expired-records.cleanup.ts`
- `apps/api/test/taste-media-keys.spec.ts`
- `apps/api/test/expired-records.cleanup.spec.ts`
