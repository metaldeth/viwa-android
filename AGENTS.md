# Viwa Android — конфигурация для AI

| Поле | Значение |
|------|----------|
| **type** | android |
| **project** | viwa-android |
| **productName** | Viwa |
| **branch** | main |
| **workBranch** | main |
| **commitTarget** | main |
| **deployBranch** | main |
| **buildCommand** | `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`) |
| **releaseCommand** | `release-android.cmd` — локальная подпись, `assembleRelease`, upload в telemetry (`POST /api/v1/app-releases/upload`), опционально `-Publish` |
| **tdd** | true |
| **testCommand** | `gradlew.bat :app:testDebugUnitTest` (Windows) |
| **mrTarget** | main |
| **workspace** | `c:\viwa` |
| **agentRules** | `c:\viwa\.cursor\rules\` (общие правила и скиллы — не дублировать в репозитории) |

> **Transitional note (folder cutover task-11):** на этой машине каталог может ещё называться `c:\wiva\wiva-android`. Целевые пути workspace — `c:\viwa\viwa-*`. GitHub remote после rename — `metaldeth/viwa-android`.

## Сервисное меню

Настройки и служебные сценарии — только в рамках структуры из **ТЗ §2** (таблица «Сервисное меню»): порядок вкладок как в `wiva_electron` `ServiceMenu/constants.ts`, вёрстка как в `legacy Android kiosk` `ServiceMenuScreen`. Код: `ui/screens/service/ViwaServiceMenuStructure.kt`, `ServiceScreen.kt`, `ViwaServiceMenuTabContent.kt`; новые вкладки — пакет `.../service/tabs/` по образцу киоска.

## Физическая плата (wifi-adb)

**Рабочая плата проекта Viwa Android** — устройство, на котором стоит `com.viwa.android`.

| Параметр | Значение |
|----------|----------|
| ADB serial (wifi) | **`192.168.1.107:5555`** |
| Модель / product | `k3568_a` |
| Package | `com.viwa.android` |
| Launch activity | `com.viwa.android/.ui.MainActivity` |

В офисе часто одновременно видны **две** платы `k3568_a` по wifi-adb. Выбирать **только** ту, где установлен `com.viwa.android` (проверка: `adb -s <serial> shell pm path com.viwa.android`). Другая плата (например `192.168.1.63:5555` с другим софтом) — **не** целевая для этого проекта.

Установка и запуск на плате:

```bat
adb -s 192.168.1.107:5555 wait-for-device
gradlew.bat installDebug
adb -s 192.168.1.107:5555 shell am start -n com.viwa.android/.ui.MainActivity
```

Если IP платы сменится — обновить эту таблицу после проверки `pm path com.viwa.android`.

## Эмулятор для проверки релиза

**Основная платформа проекта:** приложение **Viwa Android** (`com.viwa.android`) должно
устанавливаться, запускаться и проходить smoke на AVD **`viwa-android`**
(целевое имя профиля; до переименования в AVD Manager физический профиль может называться `wiva-android`).
Профиль повторяет экран исходного `evoq`, использует
Android 11 как подключённая плата и всегда запускается в landscape.

| Параметр | Значение |
|----------|----------|
| Имя AVD | **`viwa-android`** (legacy physical profile: `wiva-android` до rename) |
| API | **30** (Android 11), образ **Google APIs**, **x86_64** |
| Экран | physical **768×1024**, **mdpi (120 dpi)**; landscape даёт logical **1024×768** |
| Ориентация | **landscape** (`hw.initialOrientation=landscape`) |
| Типичный ADB serial | **`emulator-5556`** |
| Каталог AVD (эталон Windows) | **`F:\AndroidAVD`** |

Чтобы профиль был виден в Device Manager и `emulator -list-avds`, задайте
**`ANDROID_AVD_HOME=F:\AndroidAVD`** и
**`ANDROID_SDK_ROOT=F:\AndroidSDK`** (или `ANDROID_HOME`).

AVD `viwa` (legacy `wiva`, API 25) остаётся дополнительной проверкой совместимости с
`minSdk`, но не является основным профилем проекта.

Для теста на этом эмуляторе используйте **release**, а не debug: так проверяются R8/ProGuard, Hilt и поведение, близкое к продакшену (как в `legacy Android kiosk`).

1. Запустите AVD **viwa-android** (см. таблицу выше; runbook: `docs/AVD_VIWA_ANDROID.md`).
2. Установка и запуск (Windows, SDK из `local.properties` → `sdk.dir`):

```bat
set ANDROID_HOME=F:\AndroidSDK
gradlew.bat installRelease
%ANDROID_HOME%\platform-tools\adb.exe -s emulator-5556 shell am start -n com.viwa.android/.ui.MainActivity
```

Если уже стоит сборка с другой подписью: `adb uninstall com.viwa.android`, затем снова `installRelease`.

Полный OTA: `docs/OTA_UPDATE.md` (telemetry Phase 3).

## Simple Telemetry MVP

- Документация клиента: **`docs/SIMPLE_TELEMETRY_MVP_ANDROID.md`** (REST enroll, WS fallback, 409/rebind, ONLINE после `hello`).
- Локальные ключи (не коммитить): **`local.properties.sample`** → скопировать в `local.properties`; `telemetry.enrollmentKey` = `MACHINE_ENROLLMENT_KEY` на сервере (`viwa-telemetry/.env.example`).
- **DEBUG test bench** (`installDebug` only): в `local.properties` задать `telemetry.debug.serial` (default `VIWA-TEST01`), `telemetry.debug.regKey=REG-...`, `telemetry.debug.autoConnect=true`. После первой успешной регистрации `machineSecret` хранится в EncryptedSharedPreferences — перезапуск и `installDebug` поверх существующей сборки переподключаются без повторного REG. Ключ `telemetry.debug.regKey` **не коммитить**.
- Серверная документация: `c:\viwa\viwa-telemetry\AGENTS.md`, deploy: `c:\viwa\viwa-telemetry\docs\deployment\server.md`.

## Офис / железо (этап F)

Офисная приёмка контроллера и платёжного терминала — шаблон чеклиста с полями подписи, версии APK/build, мок↔реал и ссылками на эталон **wiva_electron** (external repo, historical name):

- **`docs/OFFICE_HARDWARE_CHECKLIST.md`** — основной шаблон (F1 контроллер, F2 терминал, PCI, без PAN/CVV в примерах).
- Основное ТЗ: `docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md` (**§2** источники по контроллеру/терминалу, **§3** мок).
- Порты serial: `wiva_electron/docs/SERIAL_PORTS_IMPLEMENTATION.md` (external reference repo).

Агентский трек модуля F: `docs/agents/wiva-android-from-electron/modules/stage-f-office/summary.md`.

## Медиа экрана выбора напитка (из wiva_electron)

Ролики промо и PNG горизонтальных карточек копируются из `wiva_electron/src/renderer/assets/video` и `.../assets/img/horizontalCard` в `app/src/main/assets/viwa_electron/video/` и `.../img/horizontalCard/` (те же имена файлов). Маппинг в коде: `ui/screens/customer/ViwaElectronAssets.kt`; воспроизведение: `ViwaPromoVideoCard.kt`, Coil в `ViwaDrinkCard` (`DrinkListScreen.kt`). При смене набора роликов в electron обновить список `PROMO_VIDEO_FILES` и при необходимости пересинхронизировать файлы.
