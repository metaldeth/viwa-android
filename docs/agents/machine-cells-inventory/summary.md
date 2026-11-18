# Summary — machine-cells-inventory

**SessionId:** `machine-cells-inventory`  
**Date:** 2026-07-19  
**Repos:** `viwa-telemetry` (API + web), `viwa-android` (автомат)

## Outcome

Сквозная MVP-фича **инвентаря продуктовых ячеек** реализована end-to-end по архитектуре complex-сессии:

- **Backend + web:** каталог продуктов (14 `tasteMediaKey`), flat-модель `machine_cells`, REST GET/PATCH ячеек на dashboard, WS v2 (schema/volume/content uplink + `cells.snapshot` downlink), reconcile схемы после register (без auto-create при register), dedup по `messageId`, push snapshot при PATCH и reconnect.
- **Android:** domain/codec/repository для `telemetryCellsSnapshot`, `TelemetryCellsSyncCoordinator` (post-hello schema, uplink/downlink, legacy gate), service menu tabs (volumes/inventory) и customer drink list через MVP snapshot adapter при `useMvpProtocol=true`.
- **Контракт:** канон `viwa-telemetry/docs/contracts/machine-cells-inventory.md` + ссылки из Android docs.

Продуктовый сценарий «оператор задаёт продукт/цены на web → автомат получает snapshot → service menu и customer UI читают локальный snapshot» **собран в коде**; runtime E2E на PostgreSQL и emulator smoke **не подтверждены** в agent-окружении.

## Deliverables by repo

### viwa-telemetry

- **Prisma (task-01):** `Product`, `MachineCell`, `MachineWsMessageDedup`; поля `Machine.cellSchemaHash`, `cellSchemaSyncedAt`, `cellsContentRevision`; enum `ContentSource`; migration SQL; allowlist 14 вкусов; TTL cleanup dedup 7d.
- **REST (task-02, task-03):** `ProductsModule` CRUD + `/products/tastes`; `MachineCellsModule` GET/PATCH cells (denormalized GET, `contentSource=DASHBOARD` на PATCH, `VOLUME_READ_ONLY`, authZ session + RejectMachineJwtGuard).
- **Apply-сервисы (task-04):** reconcile schema (OQ-1 soft deactivate, OQ-2 re-key с preserve content), volume-only apply, content apply с per-cell gate (OQ-8/C-5), dedup, snapshot builder с `products[]`.
- **WS v2 (task-05):** hello protocol v2, dispatch uplink types, C-3 reconnect snapshot push, `MachinesWsRegistry.sendToMachine`, Nest `forwardRef` wiring, PATCH → snapshot side-effect.
- **Web (task-06, task-07):** `ProductsPage` CRUD, `/products` nav; `MachineCellsSection` на MachineDetail (polling ~20s, block/sos индикаторы, цены рубли↔копейки, VIEWER read-only).
- **Contract (task-11):** `docs/contracts/machine-cells-inventory.md` — WS v2, REST, error codes, C-1…C-5, schemaHash, AuthZ, fixtures.
- **E2E (task-12):** `machine-cells-inventory.e2e-spec.ts` — 6 сценариев (happy path TZ #31, register→0 cells, reconcile preserve, AuthZ 403, reconnect snapshot, dedup); **все SKIP** без `DATABASE_URL`.

### viwa-android

- **Domain/data (task-08):** `TelemetryCell`, `TelemetryProduct`, `TelemetryCellsSnapshot`, JsonStore repository, `TelemetryCellsMessageCodec`, `TasteMediaKeyCatalog` (14 keys), `CellUuidAllocator`; unit tests 7/7 + 3 PASS.
- **Sync (task-09):** `TelemetryCellsSyncCoordinator` — post-hello schema report, volume/content uplink, snapshot full replace, reconnect fields, OQ-9 optional content, legacy gate; unit 8/8 PASS.
- **UI (task-10):** Service menu MVP tabs (`ViwaInventoryVolumesTab`, `ViwaTelemetryInventoryTab`), `ServiceViewModel` wiring uplink callers, `TelemetryCellsSnapshotAdapter` + `DrinkListViewModel` MVP path, block/sos в service UI; unit 8/8 PASS; **emulator smoke не запускался**.

## Tasks status

| Task | Status | Notes |
|------|--------|-------|
| task-01 | DONE + APPROVE | Prisma schema/migration/allowlist/cleanup; migration apply NOT RUN (no DB) |
| task-02 | DONE + APPROVE | Products CRUD; unit 11 PASS; integration SKIP (no DATABASE_URL) |
| task-03 | DONE + APPROVE | REST GET/PATCH cells; integration 7 cases written, SKIP без DB |
| task-04 | DONE + APPROVE | Apply services; OQ-2 re-key hotfix (preserve content); unit tests PASS |
| task-05 | DONE + APPROVE | WS v2 wiring, snapshot push, forwardRef; integration SKIP без DB |
| task-06 | DONE + APPROVE | ProductsPage + API client; lint/build/vitest PASS |
| task-07 | DONE + APPROVE | MachineCellsSection; lint/build/vitest PASS; manual WEB smoke deferred |
| task-08 | DONE + APPROVE | Android domain/codec/repository; 7/7 unit PASS |
| task-09 | DONE + APPROVE | Coordinator + WS wiring; 8/8 unit PASS; uuid persist edge (M-1) |
| task-10 | DONE + APPROVE | Service tabs + customer adapter; 8/8 unit PASS; no emulator smoke |
| task-11 | DONE + APPROVE | Contract doc cross-check PASS; INVALID_THRESHOLDS reserved |
| task-12 | DONE + APPROVE | E2E 6 scenarios written; **6/6 SKIP** (no DATABASE_URL) |

## Verification

| Область | Прогнано | SKIP / не прогнано | Причина |
|---------|----------|-------------------|---------|
| viwa-telemetry unit | task-01…05 apply/handler/guard specs | — | `npm test` exit 0 |
| viwa-telemetry integration | Specs написаны (products, machine-cells, machines-ws-cells) | **SKIP** все DB-dependent кейсы | Нет `DATABASE_URL` / PostgreSQL в agent env |
| viwa-telemetry migration | `prisma generate` PASS | **NOT RUN** apply migration | Нет `.env` / DB |
| viwa-telemetry web | lint + build + vitest (task-06, task-07) | Manual WEB-3 polling, WEB-4 snapshot latency | Требует running stack / E2E |
| viwa-telemetry E2E (task-12) | Jest suite собран, exit 0 | **6/6 SKIP** | `describeIfDb` — no DATABASE_URL |
| viwa-android unit | task-08 (10), task-09 (8), task-10 (8) — all PASS | — | `gradlew :app:testDebugUnitTest` |
| viwa-android instrumented / emulator | — | **SKIP** service menu smoke, WS on device | Не запускалось в сессии |
| Contract doc (task-11) | 15-point cross-check PASS | — | Static review vs implementation |

## Residual risks / follow-ups

Из reviews и test reports (некритичные, APPROVE с оговорками):

1. **E2E / integration без PostgreSQL** — migration apply, REST integration (task-02/03), WS integration (task-05), task-12 E2E (6 сценариев) не выполнялись на живой DB; runtime, migration drift, WS races не подтверждены.
2. **UUID persist до snapshot (task-09 M-1)** — `CellUuidAllocator` может выдать новые uuid на каждый hello при пустом JsonStore до первого snapshot downlink; риск duplicate reconcile при обрыве между hello и snapshot.
3. **snapshot-before-ack wire order (task-05/11 M-1)** — реализация может отправить `cells.snapshot` **до** schema ack на wire; contract двусмыслен; Android должен принимать оба порядка.
4. **STOP boundary service vs customer (task-10 M-1)** — service: `volume <= blockVolume` → STOP; customer `DrinkContainer.isUnavailable`: `volume < minVolumeMl` (strict `<`); при `volume == blockVolume` расхождение UC-9.
5. **INVALID_THRESHOLDS** — зарезервирован в contract/web types; API пока не эмитит (info, не blocker).
6. **Dedup + validation error (task-05 M-1)** — повтор uplink с тем же `messageId` после WS `error` может «сжечь» dedup slot; не задокументировано в contract.
7. **Web MachineCellsSection (task-07 M-1)** — Save disabled на background poll (`cellsQuery.isFetching` каждые ~20s).
8. **MVP dashboard cells (task-10 M-2)** — при `useMvpProtocol=true` dashboard stats остаются legacy; отдельная задача.
9. **Emulator smoke** — service menu tabs, nested scroll, DrinkList MVP gate only at init — не проверялись на устройстве.

## Commits

**not created** — complex mid/end; пользователь не просил commit/push. Изменения в рабочих деревьях `viwa-telemetry` и `viwa-android` без финального task-completion.

## Next for human

1. **PostgreSQL + DATABASE_URL:** применить migration, прогнать integration specs и **task-12 E2E** (6 сценариев); убедиться в happy path TZ #31.
2. **Emulator smoke:** service menu volumes/inventory tabs, block/sos labels, customer drink list при `useMvpProtocol=true`; logcat без layout errors.
3. **Product decisions (optional):** STOP boundary `<` vs `<=`; persist uuid после allocate; wire-order в contract.
4. **Commit/push + `/task-completion`:** bump versionName в обоих проектах, lint/build/test по AGENTS.md, session log при необходимости.
