# Launch AVD wiva-android with board-like limits for performance testing.
# Physical reference (k3568_a / RK3568): Android 11, 2 GB RAM, 4x A55, Mali-G52, arm64.
# Emulator matches RAM/CPU/API/UI logical size; GPU is software (swiftshader) so host GPU
# does not hide Compose/ExoPlayer jank. ABI remains x86_64 — final sign-off still on the board.
#
# Usage:
#   powershell -File scripts\launch-avd-boardlike.ps1
#   powershell -File scripts\launch-avd-boardlike.ps1 -Port 5556

param(
  [int]$Port = 5556,
  [string]$Avd = "wiva-android",
  [switch]$Foreground
)

$ErrorActionPreference = "Stop"
$env:ANDROID_AVD_HOME = "F:\AndroidAVD"
$env:ANDROID_SDK_ROOT = "F:\AndroidSDK"
$emu = Join-Path $env:ANDROID_SDK_ROOT "emulator\emulator.exe"
$adb = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"

if (-not (Test-Path $emu)) { throw "emulator not found: $emu" }

$args = @(
  "-avd", $Avd,
  "-port", "$Port",
  "-gpu", "swiftshader_indirect",
  "-no-snapshot-load",
  "-no-snapshot-save",
  "-netdelay", "none",
  "-netspeed", "full"
)

Write-Host "Launching AVD=$Avd port=$Port (board-like: 2GB/4cpu, swiftshader, cold boot)"

if ($Foreground) {
  & $emu @args
} else {
  Start-Process -FilePath $emu -ArgumentList $args -WorkingDirectory (Split-Path $emu)
  Write-Host "Started in background. Wait for boot, then: adb -s emulator-$Port wait-for-device"
  if (Test-Path $adb) {
    Write-Host "Checking devices..."
    Start-Sleep -Seconds 3
    & $adb devices
  }
}
