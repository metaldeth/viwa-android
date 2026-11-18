# План выполнения: machine-cells-inventory

**SessionId:** `machine-cells-inventory`  
**Repos:** `viwa-telemetry` (API + web), `viwa-android` (`tdd: true`)  
**Источники:** `tz.md`, `architecture.md`, `docs/FEATURE_MACHINE_CELLS_INVENTORY.md` (M1–M7)

## Запреты

- Shaker legacy topics (`cellStoreImportTopic`, `cellVolumeImportTopic`, merge-inventory) — не расширять для MVP path.
- Docker-файлы — без явного согласия пользователя.
- Web PATCH `volume`, operator refill, `cells.volume.patch` downlink — v1, не MVP.
- `TEMP_TEST_SCENARIOS.md` — **не создавать** (`browserTesting` не задан в AGENTS.md).

## Контрольные проверки

| После задач | Repo | Команда |
|-------------|------|---------|
| task-01 … task-05 | viwa-telemetry | `npm test` (минимум по затронутым модулям) |
| task-06, task-07 | viwa-telemetry/web | `npm run lint`, `npm run build` |
| task-08 … task-10 | viwa-android | `gradlew.bat :app:testDebugUnitTest` |
| task-12 (финал) | both | `npm test` + E2E сценарий; Android unit tests |

Web smoke (manual, из TZ §WEB-1..WEB-7): ProductsPage CRUD, VIEWER read-only, MachineDetail cells, block/sos индикаторы, цены рубли→копейки.

## Инварианты (не нарушать между задачами)

1. `POST /machines/register` **не** создаёт `machine_cells`.
2. Цены — **Int копейки** end-to-end; объёмы — Int мл.
3. `cells.volume.report` — только volume (+ optional block/sos); **не** product/prices.
4. Dashboard PATCH cells → `contentSource=DASHBOARD`; machine content report **не** перезаписывает product/prices при `DASHBOARD`.
5. Downlink `cells.snapshot` — **полная замена** `telemetryCellsSnapshot` (cells + `products[]`).
6. Snapshot downlink и REST `CellDto` — denormalized `productName` / `tasteMediaKey`; uplink content report — только `productUuid`.
7. При `useMvpProtocol=true` legacy cell topics остаются no-op.

## Таблица задач

| ID | Файл | Repo | Кратко | Зависимости |
|----|------|------|--------|-------------|
| task-01 | `tasks/task-01.md` | viwa-telemetry | Prisma: Product, MachineCell, dedup, Machine fields, contentSource enum, taste allowlist | — |
| task-02 | `tasks/task-02.md` | viwa-telemetry | REST products CRUD + GET `/products/tastes` | task-01 |
| task-03 | `tasks/task-03.md` | viwa-telemetry | REST GET/PATCH cells, contentSource на PATCH, без WS push | task-01, task-02 |
| task-04 | `tasks/task-04.md` | viwa-telemetry | Apply-сервисы: reconcile, volume, content (OQ-8), dedup, snapshot builder | task-01 |
| task-05 | `tasks/task-05.md` | viwa-telemetry | WS v2 hello/handlers, snapshot push, reconnect, Nest forwardRef wiring | task-03, task-04 |
| task-06 | `tasks/task-06.md` | viwa-telemetry/web | API client, money helpers, ProductsPage, nav `/products` | task-02 |
| task-07 | `tasks/task-07.md` | viwa-telemetry/web | MachineCellsSection на MachineDetail, polling, block/sos | task-03, task-06 |
| task-08 | `tasks/task-08.md` | viwa-android | Domain models, JsonStore, codec, TasteMediaKeyCatalog, unit tests | — (контракт из architecture) |
| task-09 | `tasks/task-09.md` | viwa-android | TelemetryCellsSyncCoordinator, WS uplink/downlink, hello wiring | task-05, task-08 |
| task-10 | `tasks/task-10.md` | viwa-android | Service menu tabs, customer drink adapter, useMvpProtocol gate | task-09 |
| task-11 | `tasks/task-11.md` | both | Contract doc `machine-cells-inventory.md` + ссылка в Android docs | task-05 |
| task-12 | `tasks/task-12.md` | both | Integration E2E: register→schema→web PATCH→snapshot→volume | task-05, task-07, task-09, task-11 |

## Волны выполнения

**Волна 1** — фундамент данных  
- task-01

**Волна 2** — backend REST foundation + domain services (параллельно)  
- task-02 (от task-01)  
- task-04 (от task-01)

**Волна 3** — cells REST + Android foundation (параллельно)  
- task-03 (от task-01, task-02)  
- task-08 (контракт зафиксирован в architecture; параллельно с backend до freeze WS в task-05)

**Волна 4** — WS backend + web products (параллельно)  
- task-05 (от task-03, task-04)  
- task-06 (от task-02)

**Волна 5** — web cells UI + Android sync (параллельно)  
- task-07 (от task-03, task-06)  
- task-09 (от task-05, task-08)

**Волна 6** — Android UI + contract doc (параллельно)  
- task-10 (от task-09)  
- task-11 (от task-05)

**Волна 7** — финал  
- task-12 (от task-05, task-07, task-09, task-11)

## Параллелизация

| Трек | Задачи | Условие |
|------|--------|---------|
| **A — telemetry backend** | task-01 → task-02 → task-03 → task-05 | Линейно по REST→WS; task-04 параллельно task-02 после task-01 |
| **B — telemetry web** | task-06 → task-07 | После task-02 API; task-07 желательно после task-03 |
| **C — android** | task-08 → task-09 → task-10 | Codec/unit до server WS (task-08); E2E uplink после task-05 |
| **D — contract + E2E** | task-11 → task-12 | После freeze WS (task-05) |

**Не параллелить:** task-05 до task-03 (PATCH trigger); task-09 E2E uplink до task-05; task-12 до M3+M5+M6 minimal paths.

## Contract freeze point

После **task-05** (или параллельно с task-11): envelope types, C-1 denormalization, C-2 `products[]`, C-3 handshake fields, C-5 `contentSource`, error codes — канон в `docs/contracts/machine-cells-inventory.md`.

## Blocking questions

Нет блокирующих вопросов для старта; OQ-1…OQ-10 и C-1…C-5 зафиксированы в `tz.md` / `architecture.md`.
