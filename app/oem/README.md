# Viwa — system / priv-app integration (navigation bar)

Port of shaker snack kiosk system UI. Viwa can hide the **navigation bar** on
Android 7+ when it has `WRITE_SECURE_SETTINGS` and sets
`Settings.Global.policy_control`. On Kiayo K3568 boards it also sends OEM
broadcasts via `com.kiayo.externservice`.

This folder is for **OEM / firmware integrators**.

## What we implement in APK

- `AndroidManifest`: declares `WRITE_SECURE_SETTINGS` (grant comes from image or ADB).
- `ViwaSystemUiPolicy`: sets `policy_control=immersive.full=<package>`.
- `ViwaKioskSystemUi`: immersive flags fallback when the permission is missing.
- `KiayoSystemBars`: hide/show via `com.kiayo.hide.navigationBar` /
  `com.kiayo.show.navigationBar` when OEM service is present.

## Option A — priv-app on firmware (recommended)

1. Build **release** APK: `com.viwa.android`
2. Add to system image:

   ```text
   /system/priv-app/ViwaAndroid/ViwaAndroid.apk
   /system/etc/permissions/privapp-permissions-com.viwa.android.xml
   ```

3. Rebuild / flash firmware.

## Option B — one-time ADB at factory

```powershell
adb shell pm grant com.viwa.android android.permission.WRITE_SECURE_SETTINGS
```

Grant survives reboot; lost on uninstall.

## Verify on device

```powershell
adb shell dumpsys package com.viwa.android | findstr WRITE_SECURE_SETTINGS
adb shell settings get global policy_control
adb shell pm path com.kiayo.externservice
```

Expected when Viwa is in foreground:

```text
immersive.full=com.viwa.android
```
