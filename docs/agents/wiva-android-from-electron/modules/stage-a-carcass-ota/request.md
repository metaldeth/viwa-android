# Запрос complex-сессии (модуль A)

**ПАРАМЕТРЫ ЭТАПА:** `1 | stage-a-carcass-ota | A1–A4`

**sessionId (относительно `viwa-android/docs/agents/`):** `viwa-android-from-electron/modules/stage-a-carcass-ota`

**Режим:** complex (`orchestrator-agents`).

## Задача

Модуль **A** из `viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md` — только ID **A1–A4**.

- **A1:** Gradle app по образцу `legacy Android kiosk` (Kotlin, Compose, minSdk/target, ProGuard-заготовка). DoD: `assembleDebug` OK.
- **A2:** Базовая навигация, пустой главный экран, dev-флаг «мок контроллера». DoD: ручная проверка переключения флага.
- **A3:** OTA как у legacy Android kiosk: `update-server/`, Docker `update-server`, `version.json`, переменная `ANDROID_UPDATE_BASE_URL`; док в `viwa-android/docs/` по образцу `legacy Android kiosk/docs/OTA_UPDATE.md`. DoD: `curl` к `version.json` локально OK.
- **A4:** Клиент OTA в приложении из legacy Android kiosk (пакет wiva). DoD: ручной сценарий обновления на эмуляторе/девайсе.

## Правила

- Источники: **§2 ТЗ** (OTA — legacy Android kiosk; не выдумывать формат `version.json`).
- Вопросы владельцу: **§4.1 ТЗ** (если неоднозначность влияет на поведение).
- После модуля A: минимальный **`viwa-android/AGENTS.md`** с `buildCommand` (ТЗ §7).

## Итог этапа

Рабочая сборка; артефакты complex в этом каталоге; обязательно **`summary.md`** по завершении.
