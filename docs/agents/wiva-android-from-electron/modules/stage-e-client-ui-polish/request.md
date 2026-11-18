# Запрос этапа: E3 полировка клиентских экранов

**sessionId / moduleId:** `viwa-android-from-electron/modules/stage-e-client-ui-polish`

**Цель:** полировка клиентских экранов `viwa-android` (не сервисное меню) под эталон **wiva_electron** (вёрстка/композиция/тексты/состояния), с паттернами плавности и производительности в духе **legacy Android kiosk** (Compose: `remember`, `LazyRow`, `collectAsStateWithLifecycle`, минимизация лишних рекомпозиций).

**Иерархия источников:** electron > kiosk (только техника/анимации) > Figma (уточнение, при конфликте — electron).

**Обязательно:** сохранить бизнес-логику выбора/оплаты/мока; модалка оплаты при платном режиме как в `PaymentModal`; проверка `assembleDebug` / `assembleRelease`; по `AGENTS.md` — `installRelease` на AVD `wiva` (ручная проверка у владельца CI).

**ТЗ:** `viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md` (§2, §4.1, §7).
