# ТЗ: инвентарь ячеек автомата (machine-cells-inventory)

**sessionId:** `machine-cells-inventory`  
**Дата:** 2026-07-19  
**Repos:** `viwa-telemetry` (API, WS, web dashboard), `viwa-android` (автомат)  
**Канонический brief:** `docs/FEATURE_MACHINE_CELLS_INVENTORY.md`  
**Legacy (не целевой протокол):** `docs/CELL_FILLING_REQUESTS_AND_STORAGE.md`

---

## Описание задачи

### Контекст

В стеке **Wiva** (не Shaker legacy) нужна сквозная фича **инвентаря продуктовых ячеек** автомата: оператор видит остатки и содержимое на web-дашборде, может менять продукт и цены; автомат синхронизирует состояние с бэкендом через **MVP WebSocket** (расширение протокола v2), REST — для dashboard и CRUD каталога продуктов.

Сейчас:
- в `viwa-telemetry` нет таблиц `products` / `machine_cells`, нет REST/WS для ячеек;
- в `viwa-android` есть legacy merge-inventory (Shaker topics), при `useMvpProtocol=true` uplink **no-op**;
- web `MachineDetailPage` показывает телеметрию, но не инвентарь.

### Цель

1. **Web:** на странице автомата — таблица ячеек (остатки, продукт, пороги, цены); отдельный раздел **«База продуктов»** с CRUD.
2. **Backend:** хранение flat-модели ячеек и каталога продуктов; reconcile схемы после регистрации; два типа uplink с автомата; push snapshot при правках с dashboard.
3. **Android:** после register + WS `hello` — schema report; далее volume-only и content uplink; приём `cells.snapshot` с сервера; локальный snapshot для service menu и (адаптер для) customer UI.

### Границы (in scope)

| # | Требование |
|---|------------|
| 1 | Flat-модель ячейки: `uuid`, `cellNumber`, `productUuid`, `blockVolume`, `sosVolume`, `volume`, `maxVolume`, `dosage1Price`, `dosage2Price` |
| 2 | Каталог **Product**: `uuid`, `name`, `tasteMediaKey` (allowlist 14 ключей из Android-ассетов) |
| 3 | WS uplink: `cells.schema.report`, `cells.volume.report`, `cells.content.report` |
| 4 | WS downlink: `cells.snapshot` (обязательно MVP), `cells.volume.patch` (v1, не MVP) |
| 5 | REST: products CRUD + tastes allowlist; GET/PATCH cells на машине |
| 6 | Reconcile схемы **после** успешной регистрации — ячейки **не** создаются при `POST /api/v1/machines/register` |
| 7 | Частые volume-updates — best-effort, без строгого ordering |
| 8 | Dedup WS по `(machineId, messageId)` |

### Вне scope (явно)

- Стаканы, расходники, вода, mixOfTastes, categoryConfigMachine / калибровка CF
- Shaker legacy: Kafka, `telemetry-machine-control`, `cellStoreImportTopic` / `cellVolumeImportTopic`, `viwa-client-web-app`
- Snack / kiosk / coffee concentrate
- Продажи, рецепты, автоматическое списание при cook (подготовка uplink volume — да; интеграция cook — v1)
- Web PATCH `volume` и operator refill (v1)
- Docker-изменения без явного согласия пользователя

### Актёры

| Актёр | Роль |
|-------|------|
| **Оператор / ADMIN / MASTER** | CRUD продуктов; PATCH содержимого и цен ячеек на dashboard |
| **VIEWER** | Только чтение продуктов и ячеек |
| **Автомат (viwa-android)** | Источник правды для schema, volume; uplink content из service menu; приём snapshot |
| **Backend (viwa-telemetry)** | Persist, reconcile, authZ, push WS, dedup |
| **Web dashboard** | UI для продуктов и ячеек |

### Модель данных (целевая)

**Cell** — одна физическая продуктовая ячейка:

- `uuid` — стабильный ID, **генерируется на автомате** при первой инициализации схемы, сохраняется навсегда (TBD подтверждено brief: OQ-5 → автомат)
- `cellNumber` — 1..N, уникален в рамках машины
- `productUuid` — FK на Product; `null` = пустая ячейка
- `blockVolume`, `sosVolume` — пороги в мл (см. семантику ниже)
- `volume`, `maxVolume` — мл, non-negative int
- `dosage1Price`, `dosage2Price` — **копейки (int)** end-to-end; `null` допустимо

**Product:**

- `uuid`, `name`, `tasteMediaKey` — один из 14 allowlisted ключей

**Allowlist `tasteMediaKey`:**  
`cherry`, `blackberry-lime`, `coconut`, `cucumber`, `grapefruit`, `lemon`, `lime`, `lime-mint`, `orange`, `peach-mango`, `pomegranate-blueberry`, `raspberry`, `strawberry-lemongrass`, `watermelon`.

Значение вне списка → HTTP 400 `INVALID_TASTE`.

**Семантика порогов:**

| Поле | Смысл | UI |
|------|-------|-----|
| `blockVolume` | при `volume <= blockVolume` — ячейка **недоступна** (стоп) | красный / «стоп» |
| `sosVolume` | при `volume <= sosVolume` и > blockVolume — **warning**, продажа возможна | жёлтый / «мало» |

Валидация: `0 <= blockVolume <= sosVolume <= maxVolume`. Defaults при создании ячейки: `blockVolume=0`, `sosVolume=0`.

**Source of truth:**

| Аспект | Источник | Правило |
|--------|----------|---------|
| Structural: uuid, cellNumber, maxVolume, block/sos (schema) | Автомат schema report | Reconcile на backend; **MVP: web read-only** для structural (OQ-3) |
| Content: productUuid, dosage prices | **Dashboard PATCH** — source of truth для content/prices; при одновременном dashboard PATCH и `cells.content.report` побеждает **dashboard PATCH**. LWW по `updatedAt` — **только** между двумя machine content reports (не dashboard vs machine) | Volume-only **никогда** не меняет product/prices |
| volume | Автомат | Web **не** PATCH volume в MVP |
| Наличие ячеек в БД | Автомат после register | Backend не создаёт при register |

**Политики с пометками TBD (решения MVP из brief):**

| ID | Вопрос | Решение MVP |
|----|--------|-------------|
| OQ-1 | Лишние ячейки при schema diff | Soft: `isActive=false` |
| OQ-2 | Новый uuid при том же cellNumber | Deactivate old, insert new |
| OQ-3 | Web правит max/block/sos | Read-only на web |
| OQ-4 | block/sos в volume report | Optional patch вместе с volume |
| OQ-5 | Кто генерирует `uuid` ячейки | **Автомат** при первой инициализации; server принимает as-is |
| OQ-6 | DELETE продукта in use | SetNull на cells + delete product |
| OQ-7 | Ack обязателен для volume reports | Best-effort ack; UI автомата **не** блокируется |
| OQ-8 | Conflict: web PATCH vs `cells.content.report` | **Dashboard PATCH побеждает**; LWW по `updatedAt` — только между двумя machine content reports; revision field — v1 |
| OQ-9 | content report сразу после schema | Recommended, не блокирующий |
| OQ-10 | Soft-delete продуктов vs hard delete | Hard delete (см. OQ-6) |

### Протоколы (контрактный уровень)

**WS envelope** (существующий MVP): `{ type, messageId, sentAt, payload?, correlationId? }`.  
**Protocol version:** bump до **2**; в `hello.payload` — `supportedMessageTypes`.

**Uplink (автомат → server):**

| type | Когда | Эффект на server |
|------|-------|------------------|
| `cells.schema.report` | После hello/reconnect, смена физ. схемы | Reconcile; **не** трогает volume/product/prices существующих |
| `cells.volume.report` | Часто: dispense, ручной edit остатков | UPDATE только volume (+ optional block/sos) |
| `cells.content.report` | Смена продукта/цен в service menu | Upsert content + optional volumes в одной транзакции |

**Downlink (server → автомат):**

| type | Когда |
|------|-------|
| `cells.snapshot` | После успешного web PATCH (machine ONLINE); после `hello` при reconnect, если server content/schema новее локального (MVP); optional после schema ack |
| `cells.volume.patch` | v1 (refill с web) |

**REST** (base `/api/v1`, session cookie для dashboard; роли как в `UserRole`):

| Method | Path | Назначение |
|--------|------|------------|
| GET/POST/PATCH/DELETE | `/products`, `/products/:id` | CRUD каталога |
| GET | `/products/tastes` | Allowlist с RU display names |
| GET | `/machines/:id/cells` | Список ячеек + schemaHash |
| PATCH | `/machines/:id/cells` | Bulk content/prices; **volume запрещён** → 400 `VOLUME_READ_ONLY` |

Side-effect PATCH cells: если машина ONLINE → push `cells.snapshot`.

**Dedup:** таблица `(machineId, messageId)`; повтор → ack без повторного apply.

### Этапы поставки (ориентир для planner)

| # | Deliverable | Repo |
|---|-------------|------|
| M1 | Prisma: Product, MachineCell, dedup, поля Machine | viwa-telemetry |
| M2 | REST products + cells | viwa-telemetry |
| M3 | WS v2 handlers + snapshot push | viwa-telemetry |
| M4 | Web «База продуктов» | viwa-telemetry/apps/web |
| M5 | Web секция «Ячейки» на MachineDetail | viwa-telemetry/apps/web |
| M6 | Android snapshot, schema/volume/content uplink, snapshot apply | viwa-android |
| M7 | Contract doc + integration test E2E | both |

---

## Юзер-кейсы и сценарии

### UC-1. Регистрация автомата без предсоздания ячеек

**Актёры:** автомат, backend  
**Предусловие:** валидный registration key  
**Основной сценарий:**

1. Автомат вызывает `POST /api/v1/machines/register`.
2. Backend создаёт/находит Machine **без** строк в `machine_cells`.
3. Автомат получает credentials и подключается к WS.
4. Server отправляет `hello` (protocolVersion=2).
5. Автомат отправляет `cells.schema.report` с массивом ячеек `{ uuid, cellNumber, maxVolume, blockVolume?, sosVolume? }`.
6. Backend выполняет reconcile (§5 brief): INSERT новых, UPDATE structural существующих, deactivate отсутствующих; **сохраняет** volume/product/prices у существующих uuid.
7. Backend отвечает `ack` с `schemaHash`, счётчиками created/updated/deactivated.
8. *(Recommended)* Автомат отправляет `cells.content.report` с полным начальным состоянием.

**Альтернативы:**

- A1: content report не отправлен сразу — ячейки в БД с defaults (volume=0, product=null) до первого edit/report.
- A2: reconnect — повторный schema report только при изменении физической схемы или по политике клиента после hello.
- A3: **reconnect snapshot (MVP):** после `hello`, если server content/schema новее локального (по `schemaHash` или content revision) — сервер отправляет downlink `cells.snapshot`; автомат **полностью заменяет** локальный snapshot.

**Постусловие:** в БД есть активные ячейки с uuid и structural fields; register сам по себе ячейки не создаёт.

---

### UC-2. Reconcile при изменении физической схемы

**Актёры:** автомат, backend  
**Триггер:** новая/удалённая ячейка, смена maxVolume, замена контроллера (новый uuid на том же cellNumber)

**Основной сценарий:**

1. Автомат шлёт `cells.schema.report` с актуальным списком.
2. Backend сопоставляет по uuid и cellNumber (алгоритм §5 brief).
3. Для uuid из report — update structural, preserve content/volume.
4. Для cellNumber с новым uuid — deactivate старую запись, insert новую (MVP OQ-2).
5. Ячейки в БД, отсутствующие в report — `isActive=false` (OQ-1).
6. Обновляется `cellSchemaHash`, `cellSchemaSyncedAt`.

**Альтернативы:**

- A1: *(v1, не MVP)* hard DELETE вместо soft deactivate — **не** реализовывать в MVP.

---

### UC-3. Синхронизация остатков (volume-only uplink)

**Актёры:** автомат (service menu, позже cook path), backend  
**Частота:** высокая, low-priority consistency

**Основной сценарий:**

1. На автомате изменяется `volume` (ручной ввод, dispense, калибровка).
2. Автомат сохраняет локальный snapshot и шлёт `cells.volume.report`: `{ updates: [{ uuid, volume, blockVolume?, sosVolume? }] }`.
3. Backend UPDATE **только** volume (+ optional block/sos если переданы).
4. Backend **не** изменяет productUuid, dosage prices.
5. Backend отвечает `ack` (best-effort; UI автомата не блокируется на ack — OQ-7).

**Альтернативы:**

- A1: duplicate `messageId` → ack без double apply (dedup).
- A2: out-of-order reports → last write wins по timestamp/порядку применения на server (ordering не гарантируется).
- A3: server под нагрузкой — допустим drop/retry на клиенте без строгих SLA.

**Постусловие:** GET `/machines/:id/cells` отражает volume с eventual consistency (polling web 15–30 с).

---

### UC-4. Смена содержимого ячейки на автомате (content uplink)

**Актёры:** сервисный инженер, автомат, backend

**Основной сценарий:**

1. В service menu (inventory tab) оператор выбирает продукт и/или цены для ячейки.
2. Автомат обновляет локальный snapshot.
3. Автомат шлёт `cells.content.report` с массивом полных Cell (желательно с актуальными volume в том же сообщении).
4. Backend upsert product reference + prices + optional volumes в транзакции.
5. Backend `ack`.

**Альтернативы:**

- A1: conflict с одновременным web PATCH — **dashboard PATCH побеждает** (source of truth для content/prices); LWW по `updatedAt` применяется **только** между двумя machine content reports, не dashboard vs machine (OQ-8); revision field — v1.

---

### UC-5. Управление каталогом продуктов (web)

**Актёры:** OPERATOR+, web, backend

**UC-5a. Создание продукта**

1. Оператор открывает раздел «База продуктов».
2. Вводит название, выбирает вкус из allowlist (`GET /products/tastes`).
3. `POST /products` → продукт в таблице.

**UC-5b. Редактирование**

1. Оператор меняет name и/или tasteMediaKey.
2. `PATCH /products/:id`.

**UC-5c. Удаление**

1. Оператор подтверждает удаление.
2. `DELETE /products/:id`.
3. Если продукт привязан к ячейкам — SetNull на `product_id` в ячейках, затем delete (OQ-6).

**Альтернативы:**

- A1: tasteMediaKey вне allowlist → 400 `INVALID_TASTE`.
- A2: VIEWER — список без кнопок мутации.

---

### UC-6. Просмотр и редактирование ячеек на странице автомата (web)

**Актёры:** OPERATOR+, VIEWER (read-only), web, backend, автомат (если ONLINE)

**Основной сценарий (read):**

1. Пользователь открывает MachineDetail.
2. Секция «Ячейки» загружает `GET /machines/:id/cells`.
3. Таблица: №, продукт (name + вкус), volume **read-only**, maxVolume, block, sos, dosage1/2, статус warning/block по порогам.
4. Polling 15–30 с или refetch после save.

**Основной сценарий (write):**

1. OPERATOR меняет product (select из GET `/products`) и/или dosage prices для одной или нескольких строк.
2. «Сохранить» → `PATCH /machines/:id/cells` bulk dirty rows.
3. Backend валидирует: **нет volume в body**; обновляет content/prices.
4. Если машина ONLINE — push `cells.snapshot` на WS.
5. Автомат заменяет локальный `telemetryCellsSnapshot`.

**Альтернативы:**

- A1: PATCH с volume → 400 `VOLUME_READ_ONLY`.
- A2: машина OFFLINE — изменения persist в БД; snapshot доставится при следующем online + hello (server-initiated `cells.snapshot` если content/schema на сервере новее — см. UC-1 A3) или через content report.
- A3: VIEWER — без кнопки «Сохранить».

**Ограничение MVP:** web **не** редактирует maxVolume, blockVolume, sosVolume (только отображение).

---

### UC-7. Приём snapshot на автомате после правки с dashboard

**Актёры:** backend, автомат

1. После успешного PATCH cells backend отправляет `cells.snapshot` с полным списком Cell full.
2. Автомат atomically заменяет локальный snapshot store.
3. Service menu и customer drink list отражают новые product/prices.
4. `tasteMediaKey` из product используется для PNG/видео карточек (маппинг allowlist ↔ assets).

**Альтернативы:**

- A1: snapshot приходит во время локального edit — **MVP: полный replace** локального snapshot (downlink `cells.snapshot` перезаписывает `telemetryCellsSnapshot` целиком; merge policy — v1).

---

### UC-8. Авторизация и разделение каналов

**Сценарии:**

- Machine JWT: только WS uplink (schema/volume/content); **не** может вызывать dashboard REST PATCH.
- VIEWER: GET products/cells only.
- OPERATOR / ADMIN / MASTER: CRUD products, PATCH cells.
- Session cookie для web; Bearer JWT для machine WS (как в registration contract).

---

### UC-9. Индикация порогов на автомате (service menu)

**Актёры:** сервисный инженер, автомат UI

1. Volumes tab: редактирование volume → local save → `cells.volume.report`.
2. Inventory tab: редактирование product/prices → `cells.content.report`.
3. UI показывает block (красный/стоп) и sos (жёлтый/мало) по §3.1.1.
4. Customer drink list: adapter snapshot → DrinkContainer без legacy merge (отдельный adapter, без Shaker merge path для MVP protocol).

---

## UI-сценарии (web, рекомендуемые)

> Рекомендуемые smoke-сценарии для ручной или browser-проверки. **Не** обязательный browserTesting gate (`browserTesting` в AGENTS.md не задан).

### WEB-1. База продуктов — полный CRUD

1. Логин как OPERATOR.
2. Перейти в навигацию «База продуктов» (отдельная страница, не внутри MachineDetail).
3. **Create:** «Добавить» → имя «Сироп вишня» → вкус «Чёрная вишня» (`cherry`) → сохранить → строка в таблице (название + RU вкус).
4. **Read:** обновить страницу — продукт на месте.
5. **Update:** редактировать имя → сохранить.
6. **Delete:** удалить с подтверждением → строка исчезла.
7. **Negative:** попытка создать с невалидным taste (если UI позволяет обход) → ошибка от API.

### WEB-2. VIEWER — read-only

1. Логин как VIEWER.
2. «База продуктов» — список виден, кнопок Add/Edit/Delete нет.
3. MachineDetail «Ячейки» — таблица read-only, нет «Сохранить».

### WEB-3. MachineDetail — ячейки после schema report

1. Зарегистрировать/подключить автомат (или mock WS в integration).
2. После schema report открыть MachineDetail → секция «Ячейки» показывает N строк с uuid/cellNumber/maxVolume, volume=0, продукт пустой.
3. С автомата отправить volume report → через polling/refetch volume обновился.

### WEB-4. PATCH product/prices → snapshot на device

1. Создать продукт в базе.
2. На MachineDetail выбрать продукт для ячейки, задать dosage1/dosage2 (отображение в рублях, отправка в копейках).
3. Сохранить при ONLINE автомате.
4. *(Проверка на стенде)* на автомате в service menu отображаются новый продукт и цены ≤ 5 с.

### WEB-5. Запрет PATCH volume

1. Попытка API/UI отправить volume в PATCH → ошибка `VOLUME_READ_ONLY`; UI не предоставляет редактирование volume.

### WEB-6. Статусы порогов

1. Ячейка с volume ниже sosVolume — жёлтый/warning в таблице.
2. volume ≤ blockVolume — красный/стоп.
3. volume > sosVolume — normal.

### WEB-7. Продукт в ячейке — select из базы

1. В select ячейки видны все продукты из GET `/products` с name + taste.
2. После PATCH в snapshot уходит `productUuid`; UI автомата резолвит `tasteMediaKey` для карточки.

---

## Нефункциональные требования

### Производительность и consistency

- Volume reports: **at-least-once**, ordering **не** гарантируется; UI автомата не блокируется ожиданием ack.
- Web отображение остатков: eventual consistency; polling 15–30 с достаточно для MVP.
- Dedup обязателен для идемпотентности при retry.
- **Downlink `cells.snapshot` (MVP):** при получении на автомате — **полный replace** локального `telemetryCellsSnapshot` (без merge с локальным edit-in-progress).

### Безопасность

- Роли REST согласно существующей модели `UserRole`.
- Machine credentials не дают доступ к dashboard mutation endpoints.
- Session cookie: httpOnly, sameSite lax (как в AGENTS.md).
- Секреты только в `.env`.

### Данные и валидация

- Объёмы: int, мл, ≥ 0.
- Цены: int копейки в WS, REST, PostgreSQL, Android — **без** Decimal/float.
- `blockVolume <= sosVolume <= maxVolume` — валидация на API при schema/content PATCH с structural fields.
- `tasteMediaKey` — строго из allowlist.

### Совместимость протокола

- WS protocol version **2**; backward compatibility с v1 hello для старых клиентов — в scope architect (минимум: новые типы не ломают v1 clients на том же server).
- Envelope и auth — по `docs/contracts/registration-machine-jwt.md`.

### Наблюдаемость (MVP минимум)

- Логирование reconcile (created/updated/deactivated counts).
- Ошибки WS → `error` envelope с correlationId.
- v1: метрики lag volume reports, schema drift — **не** MVP.

### Тестирование

- **viwa-telemetry:** unit/integration tests для REST, WS handlers, reconcile, dedup (`npm test`).
- **viwa-android:** unit tests обязательны (`tdd: true` в AGENTS.md) для snapshot merge, message serialization, tasteMediaKey mapping.
- **E2E (M7):** register → hello → schema report → web PATCH → snapshot на клиенте; contract doc в обоих repos или shared path.

### Локализация

- Web: RU UI для display names вкусов (из allowlist §3.1.4 brief).
- Android: существующие RU строки service menu; tasteMediaKey → assets без новых locale keys для ключей.

### Версионирование и деплой

- При коммитах в deployable artifacts — bump `versionName` по AGENTS.md каждого repo.
- Prisma migration в `viwa-telemetry/apps/api`; не менять Docker без согласия.

---

## Критерии приёмки

### Backend / данные

1. **Register без cells:** после `POST /api/v1/machines/register` в `machine_cells` **0** строк для данной машины.
2. **Schema report:** первый `cells.schema.report` после hello создаёт ячейки с uuid, cellNumber, maxVolume; volume=0, product=null, prices=null.
3. **Reconcile preserve:** повторный schema report с теми же uuid **не** сбрасывает volume/product/prices.
4. **Reconcile deactivate:** ячейка active в БД, отсутствует в schema report → `isActive=false` (MVP).
5. **Volume-only:** `cells.volume.report` меняет только volume (+ optional block/sos); productUuid и dosage prices **неизменны**.
6. **Content report:** `cells.content.report` обновляет productUuid, prices, и volume если передан в payload.
7. **Dedup:** повторный тот же `messageId` → ack без повторного apply.
8. **Products CRUD:** create/read/update/delete работают; invalid taste → 400; DELETE с SetNull на занятых ячейках (OQ-6).
9. **GET tastes:** возвращает 14 ключей с RU display names.

### REST / authZ

10. **GET cells:** отражает состояние после volume reports (eventual ≤ 30 с при web polling).
11. **PATCH cells:** меняет product/prices; volume в body → 400 `VOLUME_READ_ONLY`.
12. **VIEWER:** PATCH products/cells → 403.
13. **Machine JWT:** PATCH REST cells/products → 403 (uplink только WS).

### WebSocket / push

14. **Protocol v2:** hello содержит protocolVersion=2 и supportedMessageTypes с cell types.
15. **Snapshot push:** успешный PATCH cells при ONLINE машине → автомат получает `cells.snapshot` в течение **5 с** на integration stand.
16. **Reconnect snapshot (MVP):** после `hello` при reconnect, если server content/schema новее локального — автомат получает downlink `cells.snapshot` и полностью заменяет локальный snapshot.
17. **Offline:** PATCH persist в БД; при reconnect — см. критерий #16 и UC-1 A3.

### Web UI

18. **База продуктов:** отдельная страница/раздел навигации; таблица name + вкус; add/edit/delete для OPERATOR+.
19. **MachineDetail ячейки:** таблица с read-only volume; select продукта; save → PATCH; индикаторы block/sos.
20. **Цены в UI:** ввод/отображение в рублях для человека; API — копейки.

### Android

21. **Schema on hello:** после успешного WS connect автомат отправляет `cells.schema.report`.
22. **Local snapshot:** flat list `telemetryCellsSnapshot` (не legacy merge при MVP protocol).
23. **Volume tab:** edit → `cells.volume.report`.
24. **Inventory tab:** edit product/prices → `cells.content.report` (с volumes).
25. **Snapshot apply:** при `cells.snapshot` (downlink после PATCH или reconnect) локальное состояние **полностью заменяется** — без merge с локальным edit-in-progress.
26. **tasteMediaKey:** продукт в ячейке корректно мапится на PNG/видео из allowlist (`ViwaElectronAssets` канон).
27. **Customer drink list:** customer UI (DrinkContainer / карточки напитков) строится из `telemetryCellsSnapshot` / `cells.snapshot`, **не** из legacy merge-inventory path.
28. **Legacy isolation:** при `useMvpProtocol=true` Shaker cell topics остаются no-op; legacy path не ломается за flag `false`.

### Out of scope verification

29. **Нет** API/WS/topics для cups, water, disposables.
30. **Нет** зависимости от Shaker Kafka / `cellStoreImportTopic` в MVP path.

### Integration (M7)

31. **E2E сценарий:** register → hello → schema report → web create product → web PATCH cell → snapshot на Android → volume report → web показывает новый volume.
32. **Contract documentation** опубликован и согласован с brief §4.

---

## Связанные артеfactы для следующих ролей

| Роль | Вход |
|------|------|
| Architect | Этот TZ + `FEATURE_MACHINE_CELLS_INVENTORY.md` §3–5 + registration contract |
| Planner | Этапы M1–M7, open questions OQ-* с MVP решениями |
| Developer (telemetry) | Prisma schema §3.2 brief, REST/WS §4 |
| Developer (android) | `SIMPLE_TELEMETRY_MVP_ANDROID.md`, service menu structure AGENTS.md |
