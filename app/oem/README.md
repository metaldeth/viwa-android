# Viwa — system / priv-app integration (navigation bar)

OEM / firmware integrators. Viwa hides status + navigation bars on Android 7+
when it has `WRITE_SECURE_SETTINGS` and sets `Settings.Global.policy_control`.
On Kiayo K3568 boards an additional OEM overlay is controlled via
`persist.kiayo.status.naviBar` (factory ADB) — not via app broadcast.

## In APK (no extra OEM code needed)

- `AndroidManifest`: declares `WRITE_SECURE_SETTINGS` (grant from image or ADB).
- `ViwaSystemUiPolicy`: `policy_control=immersive.full=com.viwa.android`.
- `ViwaKioskSystemUi`: immersive flags when permission is missing (insufficient alone on Kiayo).
- `KiayoSystemBars`: best-effort OEM broadcast — **denied for normal uid** on Android 11+ (protected broadcast).

## Current board — normal APK install (factory ADB)

One-time per device after `installDebug` / `installRelease`. Use Wi‑Fi or USB serial from `AGENTS.md`.

```powershell
cd app\oem
.\provision-viwa-kiosk.ps1
.\provision-viwa-kiosk.ps1 -Serial YOUR_ADB_SERIAL
Get-Help .\provision-viwa-kiosk.ps1 -Full
```

На стенде с несколькими ADB-устройствами всегда передавайте `-Serial`; целевую
плату сверяйте по `AGENTS.md` и наличию пакета `com.viwa.android`.

Script applies:

1. `pm grant com.viwa.android android.permission.WRITE_SECURE_SETTINGS`
2. `settings put global policy_control immersive.full=com.viwa.android` (scoped to Viwa only)
3. On Kiayo (`com.kiayo.externservice` present): `setprop persist.kiayo.status.naviBar 0`

Manual equivalent:

```powershell
adb shell pm grant com.viwa.android android.permission.WRITE_SECURE_SETTINGS
adb shell settings put global policy_control "immersive.full=com.viwa.android"
# Только Kiayo после проверки: adb shell pm path com.kiayo.externservice
adb shell setprop persist.kiayo.status.naviBar 0
```

Verify:

```powershell
adb shell dumpsys package com.viwa.android | findstr "WRITE_SECURE_SETTINGS: granted=true"
adb shell settings get global policy_control
adb shell getprop persist.kiayo.status.naviBar
adb shell pm path com.kiayo.externservice
```

Expected: grant true, `immersive.full=com.viwa.android`, `naviBar=0` on Kiayo.

## Future boards — firmware priv-app (recommended)

No ADB per unit. Whitelist grants permission on first boot.

```text
/system/priv-app/ViwaAndroid/ViwaAndroid.apk
/system/etc/permissions/privapp-permissions-com.viwa.android.xml
```

Use `privapp-permissions-com.viwa.android.xml` from this directory. Rebuild / flash firmware.

Optional OEM firmware (soft-key boards): `qemu.hw.mainkeys=1` in **build.prop** — not from APK or `adb setprop` on production.

Some Kiayo boards: **Navigation bar → Off** in OEM settings — can combine with priv-app.

Factory image can also bake `persist.kiayo.status.naviBar=0` once; priv-app does **not** unblock Kiayo protected broadcasts.

## Persistence

| Mechanism | Reboot | Uninstall Viwa | OTA APK update |
|-----------|--------|----------------|----------------|
| `pm grant WRITE_SECURE_SETTINGS` | Yes | **Lost** | Kept if update, not uninstall |
| `policy_control` global | Yes | Stays until cleared | Stays until cleared |
| `persist.kiayo.status.naviBar` | Yes | Independent of app | Independent of app |
| priv-app whitelist | Yes | N/A (system) | N/A |

Re-run `provision-viwa-kiosk.ps1` after uninstall/reinstall. Viwa re-applies `policy_control` on resume when grant exists.

## Protected broadcast (why Kiayo app path fails)

`com.kiayo.hide.navigationBar` is a **protected broadcast**. Normal `com.viwa.android` cannot send it even with immersive flags or `WRITE_SECURE_SETTINGS`. Factory script sets `persist.kiayo.status.naviBar=0` via **shell** instead. Do not rely on app broadcast on k3568_a.

## Rollback

```powershell
.\provision-viwa-kiosk.ps1 -Rollback
.\provision-viwa-kiosk.ps1 -Serial YOUR_ADB_SERIAL -Rollback
```

Or manually:

```powershell
adb shell settings delete global policy_control
adb shell pm revoke com.viwa.android android.permission.WRITE_SECURE_SETTINGS
adb shell setprop persist.kiayo.status.naviBar 1
```

## Do not use on production

- `policy_control` with wildcards (`immersive.full=*`, `immersive=*`) — affects all apps.
- Перезаписывать существующий `policy_control`, не зафиксировав его значение для rollback.
- `adb root` + `setprop qemu.hw.mainkeys 1` except dev/emulator (`-TryMainkeys` in script).
- Global SystemUI cuts or init.rc navbar hide — breaks service/debug on other apps.
