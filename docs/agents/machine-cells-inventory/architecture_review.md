# Architecture Review: machine-cells-inventory (круг 2)

**Дата:** 2026-07-19  
**Ревьюер:** architecture-reviewer (complex, круг 2)  
**Входы:** `architecture.md` (обновлённый), `architecture_review.md` (круг 1), `tz.md`, `AGENTS.md` (viwa-telemetry, viwa-android)

---

## Краткий вывод

Обновлённая архитектура **закрывает все критичные замечания C-1…C-5** из ревью круга 1. Контракты WS/REST, Android domain models, NestJS module wiring и алгоритм OQ-8/C-5 описаны однозначно и согласованы с ТЗ (UC-1…UC-9, критерии приёмки #16, #24–#27).

**Готовность к planner/dev:** **да** — блокеров нет. Contract freeze (M2+M3) может включать типы C-1/C-2/C-3/C-5 без ожидания дополнительных архитектурных правок.

| Поле | Значение |
|------|----------|
| hasCriticalIssues | **false** |
| Закрыто C-1…C-5 | **5 / 5** |
| Готовность к planner | **да** |

---

## Проверка закрытия C-1…C-5

### C-1. Denormalized `productName` / `tasteMediaKey` в snapshot — **ЗАКРЫТО**

| Требование (круг 1) | Статус в `architecture.md` |
|---------------------|----------------------------|
| `CellFull` / downlink snapshot несут `productName`, `tasteMediaKey` | § Интерфейсы: `CellFull` JSON (стр. 147–162); payload `cells.snapshot` — denormalized `CellFull[]` (стр. 176–186) |
| REST `CellDto` с теми же полями | § REST: `CellDto` (стр. 205); инвариант C-1 (стр. 16) |
| Uplink `cells.content.report` — только `productUuid` | `CellContentReport` без denormalized fields (стр. 131–145, 307–308); явное «ignore if present» (стр. 165) |
| Android `TelemetryCell` | Domain models (стр. 277–291) |
| Join на сервере | `MachineCellsSnapshotService` LEFT JOIN (стр. 165); UC-4: server join → next snapshot (стр. 371–372) |

**Верификация vs ТЗ:** критерии #26, #27 (tasteMediaKey → `ViwaElectronAssets`, customer drink list из snapshot) выполнимы без скрытого REST.

---

### C-2. Каталог `products[]` в snapshot; machine без GET /products — **ЗАКРЫТО**

| Требование (круг 1) | Статус в `architecture.md` |
|---------------------|----------------------------|
| Snapshot включает `products[]` | Payload `cells.snapshot` (стр. 176–191); `TelemetryCellsSnapshot.products` (стр. 299–305) |
| Machine JWT не вызывает GET /products | AuthZ matrix (стр. 257); инвариант C-2 (стр. 17) |
| Inventory picker на автомате | `ViwaTelemetryInventoryTab` ← `snapshot.products[]` (стр. 83); UC-4 (стр. 368–369) |
| Refresh после products CRUD | Lazy on next reconnect / schema report (стр. 17, 189–190, UC-5 стр. 380) |

**Scope-cut не потребовался:** UC-4 (смена продукта на автомате) сохранён; MVP trade-off — задержка обновления каталога после web products CRUD до reconnect (документировано явно).

---

### C-3. Handshake `clientContentRevision` / `clientSchemaHash` + push algorithm — **ЗАКРЫТО**

| Требование (круг 1) | Статус в `architecture.md` |
|---------------------|----------------------------|
| Поля в uplink `cells.schema.report` | Таблица uplink (стр. 127–128); JSON пример (стр. 227–235) |
| Android заполняет из локального store | § Reconnect snapshot (стр. 237); coordinator persist (стр. 249) |
| Алгоритм «server новее» | `shouldPushSnapshotAfterSchemaReport` — 4 условия (стр. 239–247) |
| Push после schema ack | П. 4 (стр. 247); UC-1 / UC-7 flows |
| `contentRevision` в downlink snapshot | Payload snapshot (стр. 180–181) |

**Верификация vs ТЗ:** UC-1 A3, критерий #16 — покрыты; сравнение `cellsContentRevision` и `cellSchemaHash` однозначно.

---

### C-4. Nest Variant B: `forwardRef` + `sendToMachine` — **ЗАКРЫТО**

| Требование (круг 1) | Статус в `architecture.md` |
|---------------------|----------------------------|
| Выбор паттерна | § Module dependency (C-4 Variant B) (стр. 413–428); политика C-4 (стр. 532) |
| Export `MachinesWsRegistry` | Компоненты (стр. 42–44); import graph (стр. 419–422) |
| Публичный `sendToMachine(machineId, envelope)` | Стр. 42, 247, 388, 427–428 |
| `forwardRef` на обоих модулях | Import graph + runtime `@Inject(forwardRef(...))` (стр. 419–427) |
| Отказ от Variant A/C в MVP | Явно (стр. 428) |
| Mermaid wiring | Диаграмма (стр. 430–449) |

**Риск bootstrap:** снят; planner может декомпозировать M3 с указанными import/inject точками.

---

### C-5. Per-cell `contentSource` + алгоритм dashboard vs content report — **ЗАКРЫТО**

| Требование (круг 1) | Статус в `architecture.md` |
|---------------------|----------------------------|
| Enum `MACHINE` \| `DASHBOARD` на `MachineCell` | Prisma (стр. 269); § Dashboard PATCH vs content report (стр. 211) |
| Dashboard PATCH → `contentSource=DASHBOARD` + revision bump | Шаг 1 таблицы (стр. 215) |
| Content report: skip product/prices для `DASHBOARD` | Шаг 2 (стр. 217–218) |
| Volume always apply from content report | Шаг 2 (стр. 217–218) |
| LWW только между `MACHINE` reports | Шаг 3 (стр. 219); инвариант OQ-8 (стр. 15) |
| Единый owner алгоритма | `CellContentApplyService` (стр. 38, 222) |
| Unpin MVP | Шаг 4: только новый dashboard PATCH (стр. 220) |

**Верификация vs ТZ:** OQ-8, UC-4 A1, acceptance #6–7, #25 — алгоритм канонизирован; «три альтернативы без выбора» из круга 1 устранены.

---

## Сводная таблица C-1…C-5

| ID | Тема | Круг 1 | Круг 2 |
|----|------|--------|--------|
| C-1 | Denormalized product в snapshot | Блокер | **Закрыто** |
| C-2 | `products[]` + no machine GET /products | Блокер | **Закрыто** |
| C-3 | Reconnect handshake + push | Блокер | **Закрыто** |
| C-4 | Nest module cycle | Блокер | **Закрыто** |
| C-5 | OQ-8 per-cell algorithm | Блокер | **Закрыто** |

---

## Рекомендации (non-blocking, для planner / M7)

Замечания круга 1, **не блокирующие** планирование:

| # | Тема | Рекомендация |
|---|------|--------------|
| R-1 | `schemaRevision` на `MachineCell` | Семантика не описана — уточнить в Prisma migration или удалить из MVP schema |
| R-2 | `PhysicalCellSchemaProvider` | Связать с `USE_MOCK_CONTROLLER` / конфигом N ячеек в planner tasks |
| R-3 | Web data fetching | Добавить react-query hooks (`useMachineCellsPoll`, products query) по аналогии с `useMachineDetailLongPoll` |
| R-4 | WEB-1…WEB-7 | Опционально: таблица маппинга smoke → компоненты в planner backlog |
| R-5 | Android Hilt | DI wiring для `TelemetryCellsSyncCoordinator`, repository — в planner M6 |
| R-6 | `INVALID_THRESHOLDS` | Привязать к `CellSchemaReconcileService` / `CellVolumeApplyService` в contract doc |
| R-7 | Products CRUD lazy refresh | Задокументировать в contract doc UX-ограничение: новый продукт на автомате виден после reconnect (не immediate push) |
| R-8 | Polling interval | Зафиксировать одно значение (напр. 20 с) или refetch-on-focus в web tasks |

---

## Покрытие ТЗ и стека (без регрессии)

| Область | Статус |
|---------|--------|
| UC-1…UC-9 | Покрыты UC-1…UC-8 + service tabs / adapter |
| OQ-1…OQ-10 + C-1…C-5 | Зафиксированы в «Политики MVP» |
| Contract freeze gate | C-1/C-2/C-3/C-5 типы перечислены в § Contract freeze point |
| AGENTS viwa-telemetry / viwa-android | Стек и команды verification согласованы |
| Docker | Явный запрет — OK |
| Legacy isolation | `useMvpProtocol` / `skipLegacyTopic` — OK |

---

## Verification (architect → planner)

Минимальные тесты из architecture § Verification дополнить в planner:

- Unit: snapshot denormalization join, `shouldPushSnapshotAfterSchemaReport`, OQ-8 per-cell apply, Nest `forwardRef` bootstrap
- Android: codec uplink vs downlink, snapshot replace incl. `products[]`, reconnect handshake fields
- Integration M7: register → schema → web PATCH → snapshot → volume report (без изменений vs TZ #31)

---

## Итог для orchestrator

| Поле | Значение |
|------|----------|
| reviewFile | `docs/agents/machine-cells-inventory/architecture_review.md` |
| hasCriticalIssues | **false** |
| commentsSummary | Все C-1…C-5 закрыты в обновлённом architecture.md: denormalized CellFull + products[] в snapshot, reconnect handshake (clientSchemaHash/clientContentRevision), Nest Variant B (forwardRef + sendToMachine), per-cell contentSource algorithm. Архитектура готова к planner/dev и contract freeze. |
| Готовность к planner | **да** |
| Следующий шаг | Planner: M1–M7 breakdown; contract doc `machine-cells-inventory.md` с типами C-1…C-5 |
