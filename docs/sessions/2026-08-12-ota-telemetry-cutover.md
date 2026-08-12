# 2026-08-12 — OTA telemetry cutover

## Done
- Legacy Android OTA removed from repo (`update-server`, Legacy HTTP UI, `version.json` client path).
- `release-android.ps1` uploads to telemetry `POST /api/v1/app-releases` with `OTA_RELEASE_UPLOAD_TOKEN`; key alias `viwa-release`.
- Production: `FEATURE_APP_UPDATES=true`, Ed25519 keys, cert pin, SDK build-tools, nginx `internal-ota`.
- Fixed Fastify multipart deadlock (`part.toBuffer()` before parts loop ends).
- Published STABLE `26.08.12.01` / versionCode `209` (releaseId `8e6e2861-e0f6-4e91-af6c-4464969ae6f5`).
- Legacy `viwa-android-update.service` stopped/disabled; `/android-ota/` returns 410.

## Decisions
- Canonical OTA is telemetry Phase 3 only (signed manifest + machine JWT).
- APK signing cert pin: `f2646e94465d238f1b62dd39151f8359c9c5542ad83cd8b3859619e2cc140f2d` (`viwa-release` in `release.jks`).
- Manifest key id: `ota-prod-v1` (pinned in `local.properties` + hello `otaSigningPublicKeys`).

## Risks
- Device install still needs interactive PackageInstaller + online `firmware.update` scope.
- Full device E2E (check→download→install→report) not run in this session; API publish + endpoints verified.
- Multipart fix deployed surgically to current release dist; ensure next full telemetry deploy includes controller source.

## Verification
- `GET /api/v1/app-releases` without auth → 401 (feature on).
- Upload STABLE 209 → 201; publish → PUBLISHED active pointer.
- `GET /android-ota/version.json` → 410.
- `assembleRelease` APK `viwa-android-26.08.12.01-release.apk` built locally.

## Git facts
- repo: viwa-android (version bump 209 / 26.08.12.01, legacy removal, release script)
- repo: viwa-telemetry (release-token auth, multipart upload fix)
- prod releaseId STABLE: `8e6e2861-e0f6-4e91-af6c-4464969ae6f5`

## Next
- Commit/push pending changes in both repos when ready.
- Smoke on kiosk/AVD: service menu → check update → install with technician key.
- Next APK: `release-android.cmd -Publish` (STABLE).
