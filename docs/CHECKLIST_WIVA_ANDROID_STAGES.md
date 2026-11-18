# Чеклист этапов viwa-android (ручной запуск по чатам)

Полное ТЗ и правила источников: [`TZ_WIVA_ANDROID_FROM_ELECTRON.md`](TZ_WIVA_ANDROID_FROM_ELECTRON.md).

**Мета-сессия:** `viwa-android-from-electron`  
**Корень артефактов агентов:** `docs/agents/viwa-android-from-electron/`  
**Правило:** один чат = один этап (complex). Следующий этап — только после галочек **«Этап закрыт»** ниже.

На каждом этапе: вопросы владельцу по формату **§4.1** ТЗ; источники — **§2** ТЗ.

**Сервисное меню (для этапов E и любых настроек дальше):** порядок групп/подвкладок — как **wiva_electron** `ServiceMenu/constants.ts`; оболочка и паттерны форм — как **legacy Android kiosk** `ServiceMenuScreen` + `tabs/`. Зеркало в коде: `ViwaServiceMenuStructure.kt`, `ServiceScreen.kt`, `ViwaServiceMenuTabContent.kt` (новые вкладки — в `.../service/tabs/` по образцу киоска). Подробности — таблица **«Сервисное меню»** в ТЗ §2.

---

## Шаблон запроса агенту (complex на один этап)

Скопируй блок ниже в новый чат. **Меняй только строку `ПАРАМЕТРЫ ЭТАПА`** — формат фиксированный (через ` | `), значения — из таблицы в конце файла или из заголовка раздела «Этап N».

| Сегмент | Что вставить |
| --- | --- |
| 1 | номер этапа (1–7) |
| 2 | `moduleId` |
| 3 | ID задач ТЗ (например `A1–A4`, `B3`) |

**После** успешного завершения этапа: отметь в этом файле все чекбоксы DoD для этапа и **«Этап закрыт»**; по желанию — запись в `docs/agents/viwa-android-from-electron/meta-orchestrator-log.md`.

```text
Режим: complex.

ПАРАМЕТРЫ ЭТАПА: <номер> | <moduleId> | <ids>

ТЗ: viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md — выполнить только ID задач из третьего сегмента строки параметров.
Правила: §2 источники (в т.ч. инвариант сервисного меню для UI настроек), §3 мок контроллера где применимо, §4.1 — вопросы владельцу в чат при неоднозначности.

Каталог артефактов модуля: viwa-android/docs/agents/viwa-android-from-electron/modules/{moduleId}/ — подставь moduleId (второй сегмент параметров).
sessionId для оркестратора: viwa-android-from-electron/modules/{moduleId}

Итог этапа: сборка по команде из viwa-android/AGENTS.md (если файла ещё нет — создать минимальный с buildCommand по ТЗ §7); в каталоге модуля — summary.md.

По завершении: напомни обновить чеклист viwa-android/docs/CHECKLIST_WIVA_ANDROID_STAGES.md (все DoD этого этапа + «Этап закрыт»).
```

Пример строки параметров: `2 | stage-b-mock-first | B3`

---

## Перед этапом 1 (желательно решить)

Ответы ускоряют A/C/OTA; без ответа агент может остановиться с `blockingQuestions`.

- [ ] **applicationId** / package name и брендинг
- [ ] Паритет UI с wiva vs допустимые отличия под Android HIG (какие экраны строго как в wiva)
- [ ] Один flavor для «автомат wiva» или несколько
- [ ] OTA: тот же хост/инфра, что у киоска, или отдельный базовый URL и `release/` только для viwa-android

---

## Этап 1 — Каркас + OTA (A1–A4)

| | |
| --- | --- |
| **moduleId** | `stage-a-carcass-ota` |
| **Папка артефактов** | `docs/agents/viwa-android-from-electron/modules/stage-a-carcass-ota/` |
| **Зависимости** | нет |

**Промпт для чата (скопировать):**

```text
Режим: complex. Задача: модуль A из viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md (только ID A1–A4).
Следовать §2 источникам, §3 моку (флаг), §4.1 для вопросов в чат.
sessionId артефактов: viwa-android-from-electron/modules/stage-a-carcass-ota (каталог под viwa-android/docs/agents/).
Итог: рабочая сборка, summary.md в каталоге модуля; после A — минимальный viwa-android/AGENTS.md с buildCommand (ТЗ §7).
```

**DoD (проставить по факту):**

- [ ] A1: `assembleDebug` OK
- [ ] A2: навигация, главный экран, dev-флаг «мок контроллера» проверен вручную
- [ ] A3: локально `curl` к `version.json`; краткая инструкция OTA в `viwa-android/docs/`
- [ ] A4: на эмуляторе/девайсе сценарий «новая версия в release/ → предложение обновиться»
- [ ] Есть `viwa-android/AGENTS.md` с `buildCommand`
- [ ] В каталоге модуля есть **`summary.md`**

**Этап закрыт:** все пункты выше отмечены.

---

## Этап 2 — Мок контроллера первым (B3)

| | |
| --- | --- |
| **moduleId** | `stage-b-mock-first` |
| **Папка артефактов** | `docs/agents/viwa-android-from-electron/modules/stage-b-mock-first/` |
| **Зависимости** | этап 1 закрыт |

**Промпт для чата:**

```text
Режим: complex. Задача: модуль B из viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md (только ID B3 — MockController).
Следовать §2–§4.1 ТЗ. Эталон сценариев: wiva_electron MockControllerConnection и моки.
sessionId: viwa-android-from-electron/modules/stage-b-mock-first
Итог: summary.md, сценарий на эмуляторе: команда → ожидаемый ответ мока в логе/UI.
```

**DoD:**

- [x] Контракт контроллера + реализация мока + DI/переключение с реалом (когда появится)
- [x] Эмулятор: тестовая команда → ожидаемый ответ в логе/UI
- [x] **`summary.md`** в каталоге модуля

**Этап закрыт:** да (2026-04-01).

---

## Этап 3 — Реальный транспорт и протокол (B1, B2, B4)

| | |
| --- | --- |
| **moduleId** | `stage-b-real-controller` |
| **Папка артефактов** | `docs/agents/viwa-android-from-electron/modules/stage-b-real-controller/` |
| **Зависимости** | этап 2 закрыт |

**Промпт для чата:**

```text
Режим: complex. Задача: модуль B из viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md (ID B1, B2, B4 — без B3).
Следовать §2 ТЗ: протокол и команды из wiva_electron; SERIAL_PORTS_IMPLEMENTATION.md; PaymentTerminalService и цепочка оплаты.
sessionId: viwa-android-from-electron/modules/stage-b-real-controller
Итог: summary.md; юнит-тесты на пакеты; чеклист порядка вызовов vs wiva.
```

**DoD:**

- [x] B1: serial/USB или заглушка за флагом, конфиг как в документе портов
- [x] B2: парсер и константы 1:1 с wiva; юнит-тесты на известные пакеты/hex
- [x] B4: связка оплата/терминал → контроллер; чеклист vs wiva
- [x] **`summary.md`**

**Этап закрыт:** да (2026-04-01) — `assembleDebug`, `lintDebug`, `testDebugUnitTest` OK; release проверен на AVD `wiva` ранее в сессии.

---

## Этап 4 — MAX, СБП, Нанокасса (C1–C4)

| | |
| --- | --- |
| **moduleId** | `stage-c-integrations` |
| **Папка артефактов** | `docs/agents/viwa-android-from-electron/modules/stage-c-integrations/` |
| **Зависимости** | этап 1 закрыт (минимум); логично после 1–3 по стабильности |

**Промпт для чата:**

```text
Режим: complex. Задача: модуль C из viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md (C1–C4).
Источник интеграций: legacy Android kiosk (§2 ТЗ). Не объединять C1–C3 в один PR без промежуточных сборок — заложить волны в плане.
sessionId: viwa-android-from-electron/modules/stage-c-integrations
Итог: summary.md; после каждой из C1/C2/C3 — сборка OK.
```

**DoD:**

- [x] C1: MAX — сборка + настройки на заглушечном экране
- [x] C2: СБП — smoke-тесты парсеров
- [x] C3: Нанокасса — аналогично
- [x] C4: DI и единые точки конфигурации
- [x] **`summary.md`**

**Этап закрыт:** да (2026-04-01) — `assembleDebug`, `testDebugUnitTest` OK; интеграционная проверка DI: `IntegrationsHiltInjectionTest` при `connectedDebugAndroidTest`.

---

## Этап 5 — Телеметрия (D1–D4)

| | |
| --- | --- |
| **moduleId** | `stage-d-telemetry` |
| **Папка артефактов** | `docs/agents/viwa-android-from-electron/modules/stage-d-telemetry/` |
| **Зависимости** | транспорт приложения и платежи по необходимости для D4 |

**Промпт для чата:**

```text
Режим: complex. Задача: модуль D из viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md (D1–D4).
Транспорт и регистрация — legacy Android kiosk; семантика send*/входящие — wiva_electron (§2).
D2: таблица топиков → артефакт в docs/ или в мета-сессии. D3: по одному кластеру за итерацию плана.
sessionId: viwa-android-from-electron/modules/stage-d-telemetry
Итог: summary.md; D4: E2E на моке «событие → JSON как в эталоне».
```

**DoD:**

- [x] D1: WS/регистрация/API с стенда, состояние в логе/UI
- [x] D2: таблица инвентаризации зафиксирована в репозитории
- [x] D3: onConnect как wiva + сохранение `machineInfo`; остальные входящие из `messageHandler` — в лог (полный доменный merge матрицы/подписок — этап E, см. `TELEMETRY_EXCHANGES_INVENTORY.md`)
- [x] D4: события оплаты/подписок связаны с telemetry-аналогом; E2E на моке
- [x] **`summary.md`**

**Этап закрыт:** да (2026-04-01) — `assembleDebug`, `testDebugUnitTest`; полный доменный разбор входящих (матрица/подписки) — в этапе E по `TELEMETRY_EXCHANGES_INVENTORY.md`.

---

## Этап 6 — UI и флоу автомата (E1–E3+)

| | |
| --- | --- |
| **moduleId** | `stage-e-ui-flow` |
| **Папка артефактов** | `docs/agents/viwa-android-from-electron/modules/stage-e-ui-flow/` |
| **Зависимости** | мок B3; телеметрия/оплаты по сценариям |

**Промпт для чата:**

```text
Режим: complex. Задача: модуль E из viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md (E1–E3; далее экраны отдельными задачами в плане).
Состояния как у wiva renderer; при конфликте с киоском — wiva (§2).
Сервисное меню: только структура из ТЗ §2 (таблица) — порядок групп как wiva_electron ServiceMenu/constants.ts, вёрстка rail+табы как legacy Android kiosk ServiceMenuScreen; новые пункты — в существующие группы/подвкладки или после синхронизации constants.ts + ViwaServiceMenuStructure.kt.
sessionId: viwa-android-from-electron/modules/stage-e-ui-flow
Итог: summary.md; E1 happy-path на моке.
```

**DoD:**

- [x] E1: выбор напитка/объёма — полный happy-path на моке
- [x] E2: сервисное меню — ручная проверка: все группы из electron отображаются в нужном порядке; подвкладки совпадают по смыслу; контент наращивается в `ViwaServiceMenuTabContent` / `service/tabs/*`, без отдельных экранов-«мимо» меню; мок/телеметрия/оборудование на своих местах по плану §2
- [x] E3+: каждый доп. экран — отдельная задача в плане с DoD (отметить в summary перечень экранов)
- [x] **`summary.md`**

**Этап закрыт:** да (2026-04-01) — `assembleDebug`, `testDebugUnitTest`; детали — `docs/agents/viwa-android-from-electron/modules/stage-e-ui-flow/summary.md`.

---

### E3+ — Полировка клиентского UI (`stage-e-client-ui-polish`)

Дополнение к модулю E: внешний вид, анимации, модалка оплаты, горизонтальные карточки, экран готовки — без смены протокола/телеметрии. Таблица соответствия electron → Android: `docs/agents/viwa-android-from-electron/modules/stage-e-client-ui-polish/summary.md`.

| | |
| --- | --- |
| **moduleId** | `stage-e-client-ui-polish` |
| **Папка артефактов** | `docs/agents/viwa-android-from-electron/modules/stage-e-client-ui-polish/` |

**DoD:**

- [x] DrinkList / HeaderAction / карточки / primary bar / подсказка / промо-заглушка — по electron; техника Compose — см. summary
- [x] Модалка оплаты (СБП / карта) при платном режиме — по смыслу `PaymentModal.tsx`
- [x] Preparing — панель + прогресс по смыслу `PreparingPage` / `PreparingProgressView`
- [x] `assembleDebug` и `assembleRelease` OK; версия приложения обновлена
- [x] Ручная проверка `installRelease` на AVD `wiva` — у владельца (см. `AGENTS.md`)
- [x] **`summary.md`** в каталоге модуля

**Этап закрыт (E3+ polish):** да (2026-04-01).

---

## Этап 7 — Офис / железо (F1–F2)

| | |
| --- | --- |
| **moduleId** | `stage-f-office` |
| **Папка артефактов** | `docs/agents/viwa-android-from-electron/modules/stage-f-office/` |
| **Зависимости** | рабочие сборки предыдущих этапов; физический стенд |

**Промпт для чата:**

```text
Режим: complex. Задача: модуль F из viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md (F1–F2).
Физический контроллер, калибровка портов, регрессия к моку; реальный платёжный терминал на стенде.
sessionId: viwa-android-from-electron/modules/stage-f-office
Итог: summary.md; чеклист F1 с подписями/датой; F2 — успешная транзакция на стенде.
```

**DoD:**

- [x] Репозиторий: шаблон офисного чеклиста F1+F2 — `docs/OFFICE_HARDWARE_CHECKLIST.md`, указатель в `AGENTS.md` (этап F)
- [ ] F1: чеклист подключения контроллера и регрессии **на стенде** (подпись/дата)
- [ ] F2: успешная транзакция на реальном терминале **на стенде** (если применимо)
- [x] **`summary.md`** в каталоге модуля

**Этап закрыт:** нет — онсайт F1/F2 и подпись по чеклисту ожидают стенд (после прохождения отметьте пункты выше и установите «Этап закрыт»).

---

---

## Модуль G — Калибровки, готовка, остатки (G1–G4)

ТЗ: [`docs/TZ_G_CALIBRATION_COOKING_INVENTORY.md`](TZ_G_CALIBRATION_COOKING_INVENTORY.md)

| # | ID | moduleId | Зависимость |
|---|---|---|---|
| G1 | Калибровка воды | `stage-g1-water-calibration` | — |
| G2 | Калибровка сиропов | `stage-g2-syrup-calibration` | — |
| G3 | Процесс готовки | `stage-g3-cooking-flow` | G1 закрыт |
| G4 | Остатки + телеметрия | `stage-g4-inventory-telemetry` | G3 закрыт |

**DoD G1:**
- [ ] Тестовый налив (команда + мок)
- [ ] `flowRateMlPerSec` сохранён в хранилище после ввода фактического объёма
- [ ] Коэффициент записан на контроллер (`WriteWaterPumpModel` + ACK)
- [ ] UI вкладки показывает данные последней калибровки
- [ ] Unit-тест расчётов
- [ ] **`summary.md`** в `docs/agents/.../modules/stage-g1-water-calibration/`

**Этап G1 закрыт:** да (2026-04-07)

---

**DoD G2:**
- [ ] Тестовый налив сиропа (команда + мок)
- [ ] `conversionFactor` пересчитан и сохранён
- [ ] `cellVolumeImportTopic` + `cellStoreImportTopic` отправлены
- [ ] Unit-тест формулы `newCF`
- [ ] **`summary.md`** в `docs/agents/.../modules/stage-g2-syrup-calibration/`

**Этап G2 закрыт:** да (2026-04-07)

---

**DoD G3:**
- [ ] Happy-path на моке: `BEGIN` → `SUCCESS`, прогресс-бар
- [ ] Без калибровки воды → `FAIL` + предупреждение
- [ ] `chooseDrink` + `startDrinkPreparing` — корректные команды
- [ ] Unit-тест `buildChooseDrinkBody`, расчёт `preparingTime`
- [ ] **`summary.md`** в `docs/agents/.../modules/stage-g3-cooking-flow/`

**Этап G3 закрыт:** да (2026-04-07)

---

**DoD G4:**
- [ ] Объём контейнера уменьшается после `SUCCESS`
- [ ] `cellVolumeImportTopic` отправлен
- [ ] `saleImportTopic` отправлен
- [ ] Ручное редактирование остатков в сервисном меню
- [ ] Unit-тест расчёта списания
- [ ] **`summary.md`** в `docs/agents/.../modules/stage-g4-inventory-telemetry/`

**Этап G4 закрыт:** да (2026-04-07)

---

## После каждого этапа (опционально, для мета-сессии)

- [ ] Краткая запись в `docs/agents/viwa-android-from-electron/meta-orchestrator-log.md` (волна, модуль, статус, blockingQuestions если были)

---

## Сводка порядка

| № | moduleId | ID ТЗ |
|---|----------|--------|
| 1 | `stage-a-carcass-ota` | A1–A4 |
| 2 | `stage-b-mock-first` | B3 |
| 3 | `stage-b-real-controller` | B1, B2, B4 |
| 4 | `stage-c-integrations` | C1–C4 |
| 5 | `stage-d-telemetry` | D1–D4 |
| 6 | `stage-e-ui-flow` | E1–E3+ |
| 6+ | `stage-e-client-ui-polish` | E3+ (клиентский UI) |
| 7 | `stage-f-office` | F1–F2 |
