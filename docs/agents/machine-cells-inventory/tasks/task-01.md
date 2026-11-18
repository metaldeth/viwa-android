# task-01 — Prisma schema + taste allowlist

**Repo:** `viwa-telemetry`  
**Зависимости:** —

## Связь с этапом

M1 — Prisma migration: Product, MachineCell, dedup, поля Machine.

## Что сделать

- Добавить модели Prisma: `Product`, `MachineCell`, `MachineWsMessageDedup`.
- Расширить `Machine`: `cellSchemaHash`, `cellSchemaSyncedAt`, `cellsContentRevision Int @default(0)`.
- Enum `ContentSource` (`MACHINE` | `DASHBOARD`) на `MachineCell.contentSource` @default(MACHINE).
- `MachineCell.id` — uuid **с автомата** (без `@default(uuid())` на server).
- Индексы: `(machineId, isActive)`, unique `(machineId, cellNumber)`, `(productId)`.
- FK: `productId` → `Product` onDelete SetNull; `machineId` → Machine onDelete Cascade.
- Shared константа `TASTE_MEDIA_KEYS` (14 ключей) + map RU display names (для `/products/tastes` и валидации).
- Prisma migration в `apps/api/prisma/`.
- Расширить `ExpiredRecordsCleanupService` для TTL cleanup `machine_ws_message_dedup` (7 дней).
- Import новых модулей **не** в этом task — только schema + shared constants.

## Точки изменения

- `viwa-telemetry/apps/api/prisma/schema.prisma`
- `viwa-telemetry/apps/api/prisma/migrations/` (новая migration)
- `viwa-telemetry/apps/api/src/products/taste-media-keys.ts` (или `shared/`) — allowlist + RU labels
- `viwa-telemetry/apps/api/src/cleanup/expired-records.cleanup.ts` — dedup TTL

## Тест-кейсы

1. Migration applies cleanly на пустой/существующей БД (`npm run prisma:migrate -w @wiva/api`).
2. Unit: `TASTE_MEDIA_KEYS` содержит ровно 14 ключей из brief §3.1.4.
3. Unit: каждый allowlisted key имеет непустой RU label.
4. Schema introspection / Prisma client генерируется без ошибок.

## Критерии приёмки (TZ)

- Модели соответствуют architecture §Модели данных.
- Register endpoint **не** трогается в этом task, но схема **не** предполагает auto-create cells при register.

## Verification

`npm test` (если добавлены unit-тесты constants); `npm run build`.
