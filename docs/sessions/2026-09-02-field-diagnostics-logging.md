# 2026-09-02 — field diagnostics logging

## Done
- Field diagnostics release for white/external Video screen incidents — **no** water-pour or kiosk behavior changes.
- Version bump: `234` / `26.08.28.01` → `235` / `26.09.02.01`.
- Controller TX: `opId` begin/written/failed; pour phases by `pourId`; serialized durable last-state breadcrumb.
- Main-thread watchdog; startup `ApplicationExitInfo`/boot; crash sanitizer/flush.
- `MainActivity` lifecycle/window/foreground; outgoing intent phases.
- UsageStats: exact external package/component; provisioning `GET_USAGE_STATS` app-op; backup exclusions.
- Photo investigation identified soft kiosk / external OEM Video activity — **no** lock-task or relaunch behavior added.
- Review: 3 independent reviews + final; no P0; P1 fixed. Direct-boot receiver intentionally Timber-only (CE storage unavailable at boot).

## Decisions
- Diagnostics-only scope: observe and log field incidents without altering pour flow or kiosk lock policy.
- `BootCompletedReceiver` / direct-boot path: Timber-only logging (no CE-backed durable write at boot).
- Usage Access is provision-time (`appops GET_USAGE_STATS allow`), not granted by manifest OTA alone.

## Risks
- **Operational:** manifest OTA alone does **not** grant Usage Access. Existing fleet needs updated `app/oem/provision-viwa-kiosk.ps1` (`appops GET_USAGE_STATS allow`); otherwise logs show `foreground.probe_unavailable` and exact destination logging cannot be guaranteed until provisioned.
- **Verification gap:** controller/pour release UI smoke not exercised — machine unavailable overlay / no real hardware. Controller/pour diagnostics are unit-covered; residual limitation until field hardware available.
- Deployment (commit/push `main`, STABLE 100%) requested but **not** performed at session-log time.

## Verification
- `:app:testDebugUnitTest` — **841** tests, **0** failed, **0** skipped.
- `assembleDebug` — PASS.
- Signed `assembleRelease` — PASS.
- Release APK installed on API 30 AVD `emulator-5554`; version **235** confirmed.
- Settings intent logged exact `com.android.settings/...SettingsHomepageActivity`.
- `OPEN_DOCUMENT` `video/*` logged `com.google.android.documentsui/com.android.documentsui.picker.PickActivity`.
- `startup.prev_snapshot` preserved `lastFg`.
- No Viwa FATAL / Hilt / R8 / ANR / OOM / Compose infinity in logcat.
- **Not exercised:** controller/pour release UI smoke (machine unavailable overlay; see Risks).

### Release APK (public hashes)
- Size: **144,751,300** bytes.
- SHA256: `25B421480AB40B1EE84E841A8ECD15A20CE14D60CABC67C9F0D7017D8E4B4F0E`.
- Signing cert (SHA-256): `f2646e94465d238f1b62dd39151f8359c9c5542ad83cd8b3859619e2cc140f2d`.

## Git facts
- repo: `viwa-android` (`c:\viwa\viwa-android`)
- branch: `main` (tracking `origin/main`; uncommitted diagnostics work at log time)
- commit (HEAD): `e72a6922cf51135e6444922bf260d28e2f82ffe5` — feat: вкусы peach/mint/pineapple — карточки, fallback видео, docs
- diff/stat: 20 tracked files changed, 167 insertions(+), 18 deletions(-); untracked `logging/diagnostics/`, `DiagnosticsModule.kt`, diagnostics unit tests

## Next
- Commit and push `main`; publish STABLE 100% OTA (`26.09.02.01` / versionCode 235).
- Roll out updated `provision-viwa-kiosk.ps1` on fleet for Usage Access.
- Field smoke: controller/pour + external Video destination when hardware available.
