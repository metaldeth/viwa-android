# OTA-обновления viwa-android

## Phase 3 — telemetry OTA (production-safe)

Основной поток: **machine JWT** + signed manifest из `viwa-telemetry`.

| Шаг | Endpoint | Auth |
|-----|----------|------|
| Check | `GET /api/v1/machines/app-updates/check?currentVersionCode=` | Bearer JWT |
| Report | `POST /api/v1/machines/app-updates/report` | Bearer JWT |
| Download | `GET /api/v1/machines/app-updates/download/:releaseId?token=` | Bearer JWT + HMAC token |

Контракт: `c:\viwa\viwa-telemetry\docs\contracts\app-updates.md`.

### Клиент (Android)

- Сравнение **`versionCode`**, не `versionName`.
- Ed25519 canonical manifest (`app-release-manifest-v1|…`) + pinned/hello OTA public key (`local.properties`: `ota.signingKeyId`, `ota.signingPublicKeyPem`).
- Download: max 200 MB, SHA-256, expiry URL, pre-install verify package/versionCode/cert.
- State machine: Idle → Checking → Offered → Downloading → Verifying → Installing → AwaitingUser → Success/Failed; persistence в JsonStore.
- Автопроверка **раз в 6 ч** только при `hello.featureFlags.appUpdates=true`; manual — вкладка «Обновления» сервисного меню.
- **Mandatory** enforcement выключен по умолчанию (server + client flag).
- Install: `firmware.update` scope (online-only); check/view — сервисное меню.
- Silent install **не** используется без device-owner; K3568 OEM follow-up.

### Legacy HTTP (debug/fallback)

Явный переключатель «Legacy HTTP» в UI. Старый `version.json` + прямой URL APK — только для отладки.

| Поле | Значение |
|------|----------|
| Хост (prod legacy) | `https://tl.vitamin-water.ru/android-ota` |
| Имя APK | `viwa-android-{versionName}-release.apk` или `wiva-android-*` (legacy) |

## Legacy update-server (Docker)

```bash
docker compose up -d update-server
curl http://localhost:9082/version.json
```

Каталог `release/`, переменная `ANDROID_UPDATE_BASE_URL`. Подробнее — `.cursor/rules/universal/infra-android-update-server.mdc`.

## Сборка APK

```bat
gradlew.bat assembleRelease
```

APK: `app/build/outputs/apk/release/viwa-android-{versionName}-release.apk`.

## Локальный релиз (release-android)

Keystore и пароли **не хранятся в git** — они лежат на OTA-сервере в `/opt/viwa-android/signing/` (`release.jks`, `credentials.json` или `.storepass`/`.keypass`). Собранные APK — в `/opt/viwa-android/release/`.

Публичный манифест: `https://tl.vitamin-water.ru/android-ota/version.json`.

### Однократная настройка сервера

Если signing materials ещё только локально:

```powershell
.\scripts\bootstrap-signing-to-server.ps1
# при необходимости: -Token <RELEASE_TOKEN>
```

Скрипт копирует `signing/release.jks` и пароли на `wiva-server`, создаёт `credentials.json` и шаблон `signing/release-remote.env` (добавьте `RELEASE_TOKEN`).

### Сборка и публикация с Windows

```bat
release-android.cmd
```

Скрипт:

1. Скачивает keystore и credentials с update-server (Bearer `RELEASE_TOKEN`)
2. Собирает `gradlew.bat assembleRelease` с подписью
3. Загружает APK на сервер (`POST /admin/upload`)
4. Печатает актуальный `version.json`

Переменные: `UPDATE_BASE_URL` (по умолчанию `https://tl.vitamin-water.ru/android-ota`), `RELEASE_TOKEN` — из окружения или `signing/release-remote.env`.

Каталог `signing/` в репозитории gitignored; секреты в документацию не добавлять.
