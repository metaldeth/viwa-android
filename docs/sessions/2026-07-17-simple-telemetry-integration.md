# 2026-07-17 — simple telemetry integration

## Done

- MVP telemetry client: REST reserve/enroll, WSS с Bearer credential, fallback URL resolver, 409/rebind UI, service menu testTags.
- Новый пакет `data/remote/telemetry/mvp/`, расширены `TelemetryConfig`, `MachineRegistration`, `ViwaTelemetryService`, service menu (Подключение/Адреса).
- `local.properties.sample` — шаблон `telemetry.enrollmentKey` (gitignored `local.properties` не коммитится).
- Документация клиента: `docs/SIMPLE_TELEMETRY_MVP_ANDROID.md`; ссылка в `AGENTS.md`.
- Functional debug E2E на AVD **`wiva`**: enroll → **VIWA-000002** **ONLINE**, heartbeat, подтверждение в БД prod.
- Обнаружена физическая плата: `192.168.50.163:5555` (`k3568_a`, Android 11/API 30, arm64, 1080×1920@160, 2 GB).
- Создан основной AVD проекта **`wiva-android`** (`F:\AndroidAVD`, `emulator-5556`, Android 11/API 30 x86_64, physical 768×1024@120, landscape logical 1024×768) — runbook: `docs/AVD_WIVA_ANDROID.md`.

## Decisions

- `useMvpProtocol: true` по умолчанию; legacy Keycloak/topic-WS при `false`.
- UI `ConnectionState.Connected` — только после server `hello`; dashboard ONLINE — по server-side WS-auth (см. server docs).
- Enrollment key из `BuildConfig` / `local.properties` / `VIWA_TELEMETRY_ENROLLMENT_KEY`; совпадает с server `MACHINE_ENROLLMENT_KEY`.
- Debug smoke на prod HTTPS endpoint; release smoke отложен из‑за отсутствия env для существующего `release.jks`.
- Browser formal skill не применялся к Android repo.

## Risks

- **Release/R8 smoke NOT verified** — пароли для существующего `release.jks` не заданы в окружении; новый keystore не создавался.
- Prod enroll с debug APK — приемлемо для MVP smoke; release signing/env нужен перед field rollout.
- Physical board (`arm64`) vs dev AVD (`x86_64`) — разные ABI; для платы нужна arm64-сборка/install.
- `local.properties` с enrollment key — только локально; потеря ключа блокирует enroll без server rotate.

## Verification

- `gradlew.bat :app:testDebugUnitTest` — **201** tests pass.
- `gradlew.bat assembleDebug` — pass.
- Functional debug E2E (AVD `wiva`): registered **VIWA-000002**, **ONLINE**, heartbeat/DB — pass.
- logcat: no FATAL, no SSL/cleartext errors, no Compose `infinity maximum height`.
- Release install/smoke — **не проверялся** (signing env absent).
- Physical board — обнаружена по ADB; полный telemetry E2E на плате в этой сессии не зафиксирован отдельно от emulator smoke.

## Git facts

- repo: `c:\viwa\viwa-android`
- branch: `main` (tracks `origin/main`)
- commit: **pending (working tree before final commit)** (HEAD `7e68ac7` — pre-MVP baseline)
- diff/stat: 11 files changed, 546 insertions(+), 130 deletions(-); untracked: `docs/SIMPLE_TELEMETRY_MVP_ANDROID.md`, `local.properties.sample`, `app/.../telemetry/mvp/`, unit tests, `ServiceMenuTestTags.kt`

## Next

- Commit telemetry MVP integration + docs; не коммитить `local.properties`.
- Настроить `release.jks` signing env → `installRelease` smoke на AVD `wiva` против prod HTTPS.
- E2E на physical board `192.168.50.163:5555` (arm64 APK).
- Удалить/не восстанавливать temporary enrollment pickup artifacts на клиенте.
