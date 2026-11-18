# task-06 — код-ревью, круг 2 (A6, сервисное меню «Оплата картой»)

**Сессия:** `wiva-aqsi-card-payment`  
**Корень проекта:** `c:\viwa\viwa-android`  
**Источники:** `task-06.md`, `task-06-test-report.md`, круг 1 — `task-06-review.md`; исходники и unit-тесты вкладок.

**Процесс:** skill `code-reviewer-complex` предполагает делегирование в review-orchestrator; по запросу родительского агента выполнено **направленное повторное ревью** по чеклисту исправлений (круг 2 из 5). Код не менялся.

---

## Итоговая оценка

| Критерий | Оценка |
|----------|--------|
| Закрытие замечаний круга 1 (ниже) | Да, по пяти обязательным пунктам |
| Соответствие ТЗ task-06 | Сохраняется |
| Тесты / сборка | По `task-06-test-report.md`: assembleDebug + фильтр пакета — SUCCESS |
| Критические блокеры | Не выявлены |

---

## Проверка закрытия замечаний круга 1

### 1. Валидация пустого / пробельного host

**Было:** пустой host после trim допускался при Save.

**Сейчас:** в `ViwaAqsiSettingsViewModel.buildConfigOrReject` при `host.isBlank()` вызывается `onInvalid("Укажите адрес хоста (FQDN или IP)")`, возвращается `null` — ни Save, ни TCP не идут в репозиторий с невалидной формой.

**Тесты:** `save_rejectsBlankHost_doesNotCallSaveConfig`, `testTcp_rejectsBlankHost_doesNotCallRepositoryPersistence` (`ViwaAqsiSettingsViewModelTest`).

**Статус:** закрыто.

### 2. Двойной refresh на вкладке «Диагностика»

**Было:** `init { refresh() }` в ViewModel и `LaunchedEffect(Unit) { refresh() }` в табе.

**Сейчас:** в `ViwaAqsiDiagnosticsTab` нет `LaunchedEffect` на `refresh`; обновление — из `init` ViewModel (и после penny test внутри VM).

**Статус:** закрыто.

### 3. Сохранение перед TCP-тестом + фиксация в тестах

**Сейчас:** `testTcpConnection()` после успешного парсинга вызывает `aqsiRepository.saveConfig(parsed)`, затем `testTcpConnection()`; комментарий KDoc на методе сохранён.

**Тест:** `testTcp_savesParsedFormBeforeTestTcpConnection` — `persistenceCallOrder` == `saveConfig`, `testTcpConnection` и совпадение `lastSaved` с полями формы.

**Статус:** закрыто.

### 4. Тест `selectAqsi`

**Сейчас:** `selectAqsi_callsSetSelectedAqsi` в `ViwaCardPaymentMethodViewModelTest` проверяет репозиторий и UI-state.

**Статус:** закрыто.

### 5. DEBUG-guard: документирование и проверяемость

**Сейчас:**

- `CardPaymentServiceMenuPolicy` — KDoc: политика UC-5, явно указано, что в сборке есть только `BuildConfig.DEBUG`, отдельного dev/mock флага для кнопки нет, `USE_MOCK_CONTROLLER` не про оплату.
- Видимость: `pennyTestButtonVisibleInThisBuild()` → `isPennyTestButtonAvailable(BuildConfig.DEBUG)`.
- **Unit:** `CardPaymentServiceMenuPolicyTest` — `false` / `true` для `isPennyTestButtonAvailable`.
- **UI-копирайт:** вкладка диагностики поясняет «только debug-сборка» / «недоступен в release».

В `task-06-test-report.md` guard задокументирован согласованно с кодом.

**Статус:** закрыто.

---

## Новые или оставшиеся содержательные замечания

1. **Мелочь (не блокер):** в успешном логе `save()` по-прежнему пишется `hostEmpty=${parsed.host.isBlank()}` — после текущей валидации host не бывает blank, флаг в логе фактически всегда `false`. Имеет смысл упростить лог при следующем касании файла, не обязательно в рамках task-06.

2. **Без изменения приоритета круга 1:** обновление диагностики при возврате на вкладку без пересоздания ViewModel по-прежнему зависит от жизненного цикла экрана; для UC-5 при переходе с заказа обычно достаточно. Отдельный `DisposableEffect`/`Lifecycle` refresh не добавлялся — это осознанный минимализм, не регрессия круга 2.

3. **UX TCP-теста (как в круге 1):** «Тест соединения» по-прежнему персистит распарсенную форму через `saveConfig` до вызова TCP — для MR это по-прежнему стоит кратко зафиксировать как принятое поведение.

---

## Критические issues

Не выявлены.
