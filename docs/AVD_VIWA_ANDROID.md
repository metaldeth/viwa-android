# AVD `viwa-android` — runbook (Viwa Android)

Основной профиль проекта Viwa Android (`com.viwa.android`): экран исходного AVD `evoq`, Android 11
как на офисной плате (`k3568_a`) и обязательная landscape-ориентация.

> **Transitional note:** до переименования в AVD Manager физический профиль может называться `wiva-android`. Команды ниже используют целевое имя **`viwa-android`**; подставьте `wiva-android`, если rename ещё не выполнен.

## Environment

| Variable | Value |
|----------|-------|
| `ANDROID_AVD_HOME` | `F:\AndroidAVD` |
| `ANDROID_SDK_ROOT` / `ANDROID_HOME` | `F:\AndroidSDK` (или `local.properties` → `sdk.dir`) |

## AVD profile

| Parameter | Value |
|-----------|-------|
| Name | **`viwa-android`** (legacy: `wiva-android`) |
| API | **30** (Android 11) |
| ABI | **x86_64** |
| Screen | physical **768×1024**, **120 dpi**; landscape logical **1024×768** |
| Orientation | **landscape** (`hw.initialOrientation=landscape`) |
| RAM / CPU | **2048 MB / 4 cores** (как на плате) |
| GPU (perf) | **`swiftshader_indirect`** — слабее host GPU, ближе к Mali-G52 |
| Heap | **192 M** |
| Boot (perf) | cold boot, без snapshot |
| ADB serial (typical) | **`emulator-5556`** |

### Board-like performance mode

Для тестов Compose/ExoPlayer jank ближе к физической RK3568 (слабый Mali-G52):  
`scripts\launch-avd-boardlike.ps1` или ручной запуск с `-gpu swiftshader_indirect -no-snapshot-load`.  
Экран остаётся **1024×768 landscape** (профиль evoq UI), не native 1080×1920 панели.  
Финальный sign-off — на плате **`192.168.1.107:5555`** (arm64, `com.viwa.android`).

## Physical board reference

Канон (wifi-adb serial и как отличить плату с `com.viwa.android`) — в **`AGENTS.md` → «Физическая плата (wifi-adb)»**.

| Parameter | Value |
|-----------|-------|
| ADB target | **`192.168.1.107:5555`** (актуально; старый офисный IP `192.168.50.163` устарел) |
| Device | `k3568_a` (Rockchip **RK3568**, 4× Cortex-A55, **Mali-G52**) |
| OS | Android 11 / API 30 |
| ABI | **arm64** |
| Screen | 1080×1920 @ 160 dpi (native; киоск — landscape) |
| RAM | 2 GB |
| Package | `com.viwa.android` |

## Launch (PowerShell)

Обычный запуск:

```powershell
$env:ANDROID_AVD_HOME = 'F:\AndroidAVD'
$env:ANDROID_SDK_ROOT = 'F:\AndroidSDK'
& "$env:ANDROID_SDK_ROOT\emulator\emulator.exe" -avd wiva-android -port 5556
```

Board-like (рекомендуется для оптимизации UI/видео):

```powershell
powershell -File scripts\launch-avd-boardlike.ps1 -Port 5556
```

После загрузки ожидается `emulator-5556`. Проверка:

```powershell
adb -s emulator-5556 shell getprop ro.build.version.release
adb -s emulator-5556 shell wm size
adb -s emulator-5556 shell wm density
```

## Install and run

```bat
set ANDROID_HOME=F:\AndroidSDK
gradlew.bat installRelease
%ANDROID_HOME%\platform-tools\adb.exe -s emulator-5556 shell am start -n com.viwa.android/.ui.MainActivity
```

Перед установкой release с новым `applicationId` удалите legacy package: `adb uninstall com.wiva.android` (если был).

Enrollment key хранится только в gitignored `local.properties`
(`telemetry.enrollmentKey`) или `VIWA_TELEMETRY_ENROLLMENT_KEY`; см.
`docs/SIMPLE_TELEMETRY_MVP_ANDROID.md`.

## Related

- Project config: `AGENTS.md`
- Telemetry MVP: `docs/SIMPLE_TELEMETRY_MVP_ANDROID.md`
- Production server: `c:\viwa\viwa-telemetry\docs\deployment\server.md` (SSH: **`viwa-server`**)
