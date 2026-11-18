# Summary: stage-e-client-ui-polish (E3 полировка клиентских экранов)

**Дата:** 2026-04-01  
**Версия приложения:** `26.04.01.12` (versionCode 12)

## Соответствие экранов: electron → Android

| Клиентский UI (electron) | Файлы / компоненты electron | Файлы viwa-android (Kotlin/Compose) |
|--------------------------|----------------------------|-------------------------------------|
| Список напитков, шапка опций, низ с primary | `DrinkListPage.tsx`, `DrinkListPageContent.tsx`, `DrinkListPage.module.scss` | `DrinkListScreen.kt`, `ViwaCustomerUiTokens.kt` |
| Опции воды / объёма | `PurchaseMenu/HeaderAction/HeaderAction.tsx`, `HeaderAction.module.scss` | `HeaderActionStrip`, `HeaderOptionChip`, `OptionGroupRow` в `DrinkListScreen.kt` |
| Карточка напитка | `DrinkList/DrinkCard/DrinkCard.tsx`, `DrinkCard.module.scss` | `ViwaDrinkCard` в `DrinkListScreen.kt` (без PNG из assets — градиент+инициал, как временная замена изображений) |
| Основная кнопка | `PurchaseMenu/PrimaryActionButton/*` | `WivaPrimaryActionBar` в `DrinkListScreen.kt` |
| Модалка оплаты | `PurchaseMenu/PaymentModal/PaymentModal.tsx`, `PaymentModal.module.scss`, контент card/sbp | `CustomerPaymentSheet.kt` + состояние в `DrinkListViewModel.kt` (`PaymentSheetStep`, `paymentSheetVisible`, …) |
| Экран готовки (прогресс) | `Preparing/PreparingPage.tsx`, `PreparingPage.module.scss`, `PreparingProgressView.tsx` | `PreparingScreen.kt` (панель + `CircularProgressIndicator` с анимацией по `estSeconds`; без видеослоя) |
| Оболочка «дом» | Роутинг в electron | `HomeScreen.kt` → `DrinkListScreen`; навигация `WivaNavGraph.kt` |

**Логика оплаты / контроллер / телеметрия:** без изменения протокола; при `DEV_FREE_MODE = false` сначала показывается лист оплаты, затем вызовы `PaymentTerminalService.sendSumToTerminal` (карта / СБП) и прежний мок Pax на мок-контроллере.

## Паттерны производительности (ориентир legacy Android kiosk)

- `collectAsStateWithLifecycle()` для state ViewModel.
- Список напитков: `LazyRow` + `items(..., key = { it.containerNumber })`.
- Анимации карточки и primary: `animateFloatAsState`; подсказка — `androidx.compose.animation.AnimatedVisibility` (явное FQN из-за конфликта с `ColumnScope.AnimatedVisibility` в Compose 1.7+).

## §7 ТЗ / HIG

- Подпись primary-кнопки при отсутствии выбора: **«Налить воду»** как в `DrinkListPageContent.tsx` (`primaryButtonLabel`), хотя на текущем Android-флоу нет режима удержания налива воды — кнопка неактивна до выбора напитка. При необходимости унификации с реальным налипом воды — вопрос владельцу по §4.1.
- Подпись **«Стандартная»** для воды вместо «Фильтр» — как в `HeaderAction.tsx`.

## Сборка

- `gradlew.bat assembleDebug` — OK  
- `gradlew.bat :app:clean :app:assembleRelease` — OK (lintVitalRelease, R8)

## Ручная проверка на AVD (владелец)

По `wiva-android/AGENTS.md`: AVD **`wiva`**, API 25, `installRelease`, сценарий список → (при выключенном free mode) модалка → оплата → готовка → назад.
