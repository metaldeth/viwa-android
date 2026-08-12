# OTA-обновления viwa-android

Production OTA — только **telemetry Phase 3** через `viwa-telemetry`. Legacy HTTP (`version.json`, `update-server`, Docker) удалён.

## Поток на устройстве

| Шаг | Endpoint | Auth |
|-----|----------|------|
| Check | `GET /api/v1/public/app-updates/check?currentVersionCode=` | **нет** (public) |
| Download | URL из signed manifest (`downloadUrl`) | **нет** (public; HMAC/token в query если задан сервером) |
| Report | `POST /api/v1/machines/app-updates/report` | Bearer machine JWT (**best-effort**) |

Legacy machine endpoints (`/api/v1/machines/app-updates/check|download`) на сервере сохранены; клиент использует **public** check/download.

Контракт: `c:\viwa\viwa-telemetry\docs\contracts\app-updates.md`.

### Клиент (Android)

- Сравнение **`versionCode`**, не `versionName`.
- Ed25519 canonical manifest + pinned/hello OTA public key (`local.properties`: `ota.signingKeyId`, `ota.signingPublicKeyPem`).
- Download: max 200 MB, SHA-256, expiry URL, pre-install verify package/versionCode/cert — **без** machine JWT.
- **Manual check** (Настройки → Обновления): всегда public check при настроенном `telemetryConfig.apiUrl`; **не** зависит от WS hello / machine JWT.
- **Auto-check** (раз в 6 ч): только при `hello.featureFlags.appUpdates=true`.
- **Report**: best-effort с machine JWT; ошибка/no JWT **не** меняет phase и **не** блокирует install; `reportedKeys` — только после успешного HTTP.
- State machine: Idle → Checking → Offered → Downloading → Verifying → Installing → AwaitingUser → Success/Failed; persistence в JsonStore.
- **Transient recovery** (check/download): transport `IOException` / `OtaDownloadTransportException`, HTTP **5xx** — offer сохраняется, фаза Idle (без offer) или Offered (с offer), retry по расписанию (auto-check only); **без** report `FAILED`. **Terminal:** integrity/size/SHA/max-size, HTTP **4xx**, manifest/APK verify/signature/cert/package/version → `Failed` + report `FAILED` (report best-effort).
- **Stale recovery** при restore: если установленный `versionCode` ≥ target (offer / `toVersionCode` / pending APK) — очистка persisted state и partial APK.
- **Process/reboot recovery:** persisted `AwaitingUser` / `Installing` / `Downloading` / `Verifying` после restart не считаются активной PackageInstaller session — при валидном offer → `Offered` (retry install/download); pending APK сохраняется только если файл в app `filesDir` и `archiveVersionCode` > installed; иначе partial удаляется. Без offer → `Idle` + очистка.
- Автопроверка **раз в 6 ч** только при `hello.featureFlags.appUpdates=true`; **ручная** — **Настройки → Обновления** (public check, без hello/JWT).
- **Mandatory** enforcement выключен по умолчанию (server + client flag).
- Install: `firmware.update` scope (online-only).
- **Install path** (API 21+): `PackageInstaller` session — primary (`OtaInstallLauncher`). Device owner → silent commit; без DO → `STATUS_PENDING_USER_ACTION` + системный confirmation UI (`OtaInstallResultReceiver`). `ACTION_VIEW` — только fallback при ошибке create/write/commit session.

## Сборка release APK (локальная подпись)

Keystore и пароли **не в git** — каталог `signing/` (gitignored):

- `signing/release.jks` (alias по умолчанию **`viwa-release`**, файл `signing/key-alias` или env `KEY_ALIAS`)
- `signing/.storepass`, `signing/.keypass` (или env `STORE_PASSWORD`, `KEY_PASSWORD`)
- опционально `signing/release-remote.env` — `OTA_RELEASE_UPLOAD_TOKEN`, `TELEMETRY_API_URL`
- pin манифеста: `local.properties` → `ota.signingKeyId=ota-prod-v1`, `ota.signingPublicKeyPem=...`

```bat
gradlew.bat assembleRelease
```

APK: `app/build/outputs/apk/release/viwa-android-{versionName}-release.apk`.

## Публикация релиза (Windows)

```bat
release-android.cmd
release-android.cmd -Publish
```

Скрипт `scripts/release-android.ps1`:

1. Собирает `assembleRelease` с **локальной** подписью (`signing/` или env).
2. Загружает APK: `POST {TELEMETRY_API_URL}/api/v1/app-releases/upload` с Bearer `OTA_RELEASE_UPLOAD_TOKEN`.
3. С `-Publish`: `POST .../:id/publish` с `rolloutPercent: 100`.
4. Печатает `releaseId`, `versionName`, `versionCode`.

Переменные: `TELEMETRY_API_URL` (по умолчанию `https://tl.vitamin-water.ru`), `OTA_RELEASE_UPLOAD_TOKEN` — из окружения или `signing/release-remote.env`.

## CI (GitLab)

Job `build_release_apk` собирает signed APK в Docker. Копирование в `WIVA_OTA_RELEASE_DIR` — legacy file-drop; для production rollout используйте telemetry upload (`OTA_RELEASE_UPLOAD_TOKEN`) через `release-android.ps1` или отдельный CI job.

Секреты в документацию не добавлять.
