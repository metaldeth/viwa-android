# Plan Review: machine-cells-inventory

**Дата:** 2026-07-19  
**Ревьюер:** plan-reviewer (complex)  
**Входы:** `tz.md`, `plan.md`, `architecture.md`, `tasks/task-01.md` … `task-12.md`

---

## Краткий вывод

План **готов к исполнению**. Все 9 юзер-кейсов (UC-1 … UC-9) и UI-сценарии WEB-1 … WEB-7 из ТЗ покрыты задачами с прослеживаемой трассировкой. У каждой из 12 задач есть формулировка, точки изменения кода и зависимости. Android-задачи (task-08 … task-10) содержат явные TDD test-кases в соответствии с `tdd: true` в `viwa-android/AGENTS.md`. Telemetry backend-задачи (task-01 … task-05, task-12) имеют unit/integration/E2E кейсы; web-задачи (task-06, task-07) — manual smoke (допустимо: `browserTesting` не задан, `TEMP_TEST_SCENARIOS` не требуется).

Волны 1–7 логичны: нет задач в ранних волнах, зависящих от поздних; параллелизация (task-02 ∥ task-04, task-03 ∥ task-08, task-05 ∥ task-06, task-07 ∥ task-09, task-10 ∥ task-11) согласована с dependency graph.

**Критических блокеров нет.** Есть несколько некритичных замечаний по полноте edge-case покрытия (см. ниже).

---

## Покрытие юзер-кейсов

| UC | Описание | Задачи | Статус |
|----|----------|--------|--------|
| **UC-1** | Register без предсоздания ячеек; schema report; reconnect snapshot (A3) | task-01 (schema), task-04 (reconcile), task-05 (WS hello + schema handler + C-3 push), task-09 (Android post-hello schema + handshake fields), task-12 (E2E #1, #2) | ✅ |
| **UC-2** | Reconcile при изменении физ. схемы (OQ-1, OQ-2) | task-04 (reconcile service + tests 3–4), task-05 (schema WS), task-12 (reconcile preserve) | ✅ |
| **UC-3** | Volume-only uplink (dedup, best-effort ack) | task-04 (volume apply + dedup), task-05 (volume WS), task-07 (web polling), task-09 (Android volume uplink), task-10 (Volumes tab), task-12 (volume E2E) | ✅ |
| **UC-4** | Content uplink с service menu (OQ-8) | task-04 (content apply + DASHBOARD gate), task-05 (content WS), task-09 (content uplink), task-10 (Inventory tab) | ✅ |
| **UC-5** | Products CRUD web (5a/5b/5c) | task-02 (REST), task-06 (ProductsPage) | ✅ |
| **UC-6** | Web cells read/write (VIEWER, VOLUME_READ_ONLY, offline A2) | task-03 (REST GET/PATCH), task-07 (MachineCellsSection), task-05 (snapshot push при ONLINE) | ✅ (offline A2 — частично, см. замечания) |
| **UC-7** | Snapshot apply на автомате после dashboard PATCH | task-04 (snapshot builder), task-05 (push), task-09 (full replace), task-10 (UI refresh), task-12 (E2E snapshot) | ✅ |
| **UC-8** | AuthZ: Machine JWT WS-only; VIEWER read-only; OPERATOR+ mutate | task-02, task-03 (REST authZ), task-05 (regression), task-06, task-07 (VIEWER UI), task-12 (authZ E2E) | ✅ |
| **UC-9** | Индикация порогов; customer drink adapter; legacy gate | task-10 (tabs + adapter + thresholds), task-09 (legacy gate test) | ✅ |

### UI-сценарии (WEB-1 … WEB-7)

| Сценарий | Задачи | Статус |
|----------|--------|--------|
| WEB-1 Products CRUD | task-06 smoke | ✅ |
| WEB-2 VIEWER read-only | task-06, task-07 smoke | ✅ |
| WEB-3 Cells after schema + volume polling | task-07 smoke; task-12 E2E | ✅ |
| WEB-4 PATCH → snapshot on device | task-12 E2E + manual staging | ✅ |
| WEB-5 Volume read-only | task-03, task-07 | ✅ |
| WEB-6 Threshold statuses | task-07 smoke | ✅ |
| WEB-7 Product select from catalog | task-07 smoke | ✅ |

### Критерии приёмки TZ (выборочная сверка M1–M7)

| Deliverable / критерий | Задачи |
|------------------------|--------|
| M1 Prisma | task-01 |
| M2 REST products + cells | task-02, task-03 |
| M3 WS v2 + snapshot push | task-04, task-05 |
| M4 Web products | task-06 |
| M5 Web cells section | task-07 |
| M6 Android sync + UI | task-08, task-09, task-10 |
| M7 Contract + E2E | task-11, task-12 |

---

## Непокрытые юзер-кейсы

**Нет.** Все UC-1 … UC-9 и WEB-1 … WEB-7 имеют явное отображение на задачи.

### Некритичные пробелы в edge-case покрытии (не блокируют старт)

| ID | Сценарий из ТЗ | Замечание | Рекомендация |
|----|----------------|-----------|--------------|
| OQ-9 | Recommended initial `cells.content.report` после schema (не блокирующий) | task-09 описывает content uplink «on local change», но не фиксирует optional post-schema content report | Добавить в task-09 acceptance/note: «после первого schema report — optional content report с текущим локальным состоянием; отсутствие не блокирует flow» + unit test optional path |
| UC-6 A2 | OFFLINE PATCH → persist; snapshot при reconnect | task-03 persist без WS; task-05 C-3 reconnect — покрыто архитектурно; dedicated E2E offline→online отсутствует | Расширить task-12 optional scenario #5 или manual checklist |
| Invariant #1 | Register не создаёт cells | Нет отдельной задачи на regression register endpoint; проверка только в task-12 E2E | Достаточно для MVP; при реализации register не трогать (plan invariant) |

---

## Задачи без достаточного описания

**Нет.** Все 12 задач содержат обязательные секции:

| Задача | Формулировка | Точки изменения | Зависимости | Тест-кейсы |
|--------|--------------|-----------------|-------------|------------|
| task-01 | ✅ | ✅ | ✅ (—) | ✅ (4 unit/migration) |
| task-02 | ✅ | ✅ | ✅ task-01 | ✅ (7 REST/authZ) |
| task-03 | ✅ | ✅ | ✅ task-01, task-02 | ✅ (7 REST) |
| task-04 | ✅ | ✅ | ✅ task-01 | ✅ (10 domain) |
| task-05 | ✅ | ✅ | ✅ task-03, task-04 | ✅ (9 WS/integration) |
| task-06 | ✅ | ✅ | ✅ task-02 | ✅ (3 manual smoke) |
| task-07 | ✅ | ✅ | ✅ task-03, task-06 | ✅ (6 manual smoke) |
| task-08 | ✅ | ✅ | ✅ (architecture) | ✅ (7 TDD) |
| task-09 | ✅ | ✅ | ✅ task-05, task-08 | ✅ (7 TDD) |
| task-10 | ✅ | ✅ | ✅ task-09 | ✅ (6 TDD) |
| task-11 | ✅ | ✅ | ✅ task-05 | ✅ (3 doc review) |
| task-12 | ✅ | ✅ | ✅ task-05,07,09,11 | ✅ (5 E2E) |

---

## Замечания по тест-сценариям

`browserTesting: true` **не задан** в `AGENTS.md` — `TEMP_TEST_SCENARIOS.md` не требуется. ✅

### Android (`tdd: true`)

| Задача | TDD test-кases | Оценка |
|--------|----------------|--------|
| task-08 | 7 кейсов: snapshot replace, codec uplink/downlink, catalog, uuid allocator, JsonStore round-trip | ✅ Полно |
| task-09 | 7 кейсов: schema/volume/content uplink, snapshot apply, reconnect fields, legacy gate, full replace during edit | ✅ Полно |
| task-10 | 6 кейсов: adapter, thresholds, DrinkListViewModel MVP/legacy, product picker source | ✅ Полно |

### viwa-telemetry (желательны unit/e2e)

| Задача | Test coverage | Оценка |
|--------|---------------|--------|
| task-01 | migration + allowlist constants | ✅ |
| task-02 | REST CRUD + authZ (7) | ✅ |
| task-03 | GET/PATCH cells (7) | ✅ |
| task-04 | reconcile, volume, content, dedup, snapshot (10) | ✅ Сильное покрытие |
| task-05 | WS handlers, push, reconnect, Nest bootstrap (9) | ✅ |
| task-12 | Full E2E happy path + reconnect optional (5) | ✅ |

### Web (manual smoke — допустимо)

- task-06, task-07: manual smoke WEB-1 … WEB-7 — соответствует plan §«Web smoke (manual)» и отсутствию browserTesting gate.

---

## Замечания по волнам

| Волна | Задачи | Оценка |
|-------|--------|--------|
| 1 | task-01 | ✅ Старт без зависимостей |
| 2 | task-02, task-04 ∥ | ✅ Оба от task-01; domain services не блокируют REST |
| 3 | task-03, task-08 ∥ | ✅ task-08 по frozen architecture contract; task-03 после products API |
| 4 | task-05, task-06 ∥ | ✅ WS после PATCH trigger (task-03); web products после task-02 |
| 5 | task-07, task-09 ∥ | ✅ Web cells после REST; Android sync после WS freeze (task-05) |
| 6 | task-10, task-11 ∥ | ✅ UI после coordinator; contract doc после WS freeze |
| 7 | task-12 | ✅ Финальный gate: M3+M5+M6 paths |

**Параллелизация:** ограничения из plan («не параллелить task-05 до task-03», «task-09 E2E uplink до task-05», «task-12 до M3+M5+M6») соблюдены в wave assignment.

**Contract freeze point** после task-05 согласован с task-11 (wave 6) и Android task-09 (wave 5, integration после freeze) — корректно.

---

## Итог

| Критерий чеклиста | Результат |
|-------------------|-----------|
| Все UC из ТЗ покрыты задачами | ✅ |
| Нет висячих сценариев | ✅ |
| У каждой задачи: формулировка, точки изменения, зависимости | ✅ |
| Android TDD test-кases (tdd:true) | ✅ task-08, 09, 10 |
| Telemetry unit/e2e (желательно) | ✅ task-01…05, 12 |
| browserTesting / TEMP_TEST_SCENARIOS | N/A (не требуется) |
| Волны логичны | ✅ |

**Вердикт:** план **одобрен** для реализации. Некритичные улучшения (OQ-9 optional content report в task-09, offline E2E в task-12) можно внести по ходу без перепланирования.
