#Requires -Version 5.1
<#
.SYNOPSIS
  One-time Viwa kiosk provisioning over ADB (technician laptop + USB or Wi‑Fi).

.DESCRIPTION
  Factory-bench script when Viwa is installed as a normal user app (not firmware
  priv-app). Grants WRITE_SECURE_SETTINGS, sets scoped policy_control, and on Kiayo
  boards sets persist.kiayo.status.naviBar for the OEM navigation overlay.

  Copy app/oem/ to a USB stick or run from the repo:

    .\provision-viwa-kiosk.ps1
    .\provision-viwa-kiosk.ps1 -Serial 192.168.1.107:5555
    .\provision-viwa-kiosk.ps1 -Rollback
    Get-Help .\provision-viwa-kiosk.ps1 -Full

  Does NOT replace firmware priv-app (see README.md).

.PARAMETER Serial
  adb device serial (-s). Omit when exactly one device is connected.

.PARAMETER Rollback
  Undo provisioning: delete policy_control, revoke WRITE_SECURE_SETTINGS,
  restore Kiayo nav bar (persist.kiayo.status.naviBar=1).

.PARAMETER SkipKiayo
  Skip Kiayo persist property (non-Kiayo / Rockchip boards without externservice).

.PARAMETER TryMainkeys
  Attempt qemu.hw.mainkeys=1 via adb root. Dev/emulator ONLY — not for production
  Kiayo k3568 boards; bake into build.prop via OEM firmware instead.
#>
param(
    [string] $Serial = "",
    [switch] $Rollback,
    [switch] $SkipKiayo,
    [switch] $TryMainkeys
)

$ErrorActionPreference = "Stop"

$pkg = "com.viwa.android"
$policyValue = "immersive.full=$pkg"
$kiayoExternService = "com.kiayo.externservice"
$kiayoNavBarHideProp = "persist.kiayo.status.naviBar"
$kiayoNavBarHidden = "0"
$kiayoNavBarShown = "1"
# Kiayo/Rockchip also exposes Android nav via persist.sys.navibar (1=shown, 0=hidden).
$sysNavBarHideProp = "persist.sys.navibar"
$sysNavBarHidden = "0"
$sysNavBarShown = "1"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $CommandArgs)
    $adbArgs = @()
    if ($Serial) { $adbArgs += @("-s", $Serial) }
    $adbArgs += $CommandArgs
    & adb @adbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: adb $($adbArgs -join ' ')"
    }
}

function Test-KiayoBoard {
    if ($SkipKiayo) { return $false }
    $adbArgs = @()
    if ($Serial) { $adbArgs += @("-s", $Serial) }
    $adbArgs += @("shell", "pm", "list", "packages", $kiayoExternService)
    $output = (& adb @adbArgs 2>$null) -join "`n"
    return $LASTEXITCODE -eq 0 -and $output.Trim() -eq "package:$kiayoExternService"
}

function Show-Verification {
    param([bool] $KiayoApplied)

    $policy = (Invoke-Adb shell settings get global policy_control).Trim()
    $granted = Invoke-Adb shell dumpsys package $pkg |
        Select-String "WRITE_SECURE_SETTINGS: granted=true"

    Write-Host ""
    Write-Host "=== Verification ==="
    Write-Host "policy_control = $policy"
    Write-Host "WRITE_SECURE_SETTINGS granted = $($null -ne $granted)"

    if ($KiayoApplied) {
        $naviBar = (Invoke-Adb shell getprop $kiayoNavBarHideProp).Trim()
        $sysNav = (Invoke-Adb shell getprop $sysNavBarHideProp).Trim()
        Write-Host "$kiayoNavBarHideProp = $naviBar"
        Write-Host "$sysNavBarHideProp = $sysNav"
    }

    $okPolicy = $policy -eq $policyValue
    $okGrant = $null -ne $granted
    $okKiayo = -not $KiayoApplied -or (
        ((Invoke-Adb shell getprop $kiayoNavBarHideProp).Trim() -eq $kiayoNavBarHidden) -and
        ((Invoke-Adb shell getprop $sysNavBarHideProp).Trim() -eq $sysNavBarHidden)
    )

    Write-Host ""
    if ($okPolicy -and $okGrant -and $okKiayo) {
        Write-Host "Status: OK"
    } else {
        Write-Host "Status: CHECK FAILED (see values above)"
        if (-not $okPolicy) {
            Write-Host "  Expected policy_control = $policyValue"
        }
        if (-not $okGrant) {
            Write-Host "  Expected WRITE_SECURE_SETTINGS: granted=true for $pkg"
        }
        if ($KiayoApplied -and -not $okKiayo) {
            Write-Host "  Expected $kiayoNavBarHideProp = $kiayoNavBarHidden and $sysNavBarHideProp = $sysNavBarHidden"
        }
        throw "Kiosk provisioning verification failed."
    }
}

if ($Rollback) {
    Write-Host "Rolling back Viwa kiosk provisioning for $pkg ..."

    Invoke-Adb shell settings delete global policy_control
    Invoke-Adb shell pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS

    if (Test-KiayoBoard) {
        Invoke-Adb shell setprop $kiayoNavBarHideProp $kiayoNavBarShown
        Invoke-Adb shell setprop $sysNavBarHideProp $sysNavBarShown
    }

    Write-Host ""
    Write-Host "Rollback complete. Relaunch Viwa or reboot if bars did not restore."
    exit 0
}

Write-Host "Provisioning Viwa kiosk UI for $pkg ..."
if ($Serial) { Write-Host "ADB serial: $Serial" }

Invoke-Adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
Invoke-Adb shell settings put global policy_control $policyValue
Invoke-Adb shell cmd package set-home-activity $pkg/.ui.MainActivity

$kiayoApplied = Test-KiayoBoard
if ($kiayoApplied) {
    Write-Host "Kiayo board detected ($kiayoExternService) — hiding OEM navigation bar ..."
    Invoke-Adb shell setprop $kiayoNavBarHideProp $kiayoNavBarHidden
    Invoke-Adb shell setprop $sysNavBarHideProp $sysNavBarHidden
} elseif (-not $SkipKiayo) {
    Write-Host "Kiayo externservice not found — skipping $kiayoNavBarHideProp (use -SkipKiayo to silence)."
}

if ($TryMainkeys) {
    Write-Host "WARNING: TryMainkeys is dev/emulator only. Skipping on production is recommended."
    Write-Host "Trying qemu.hw.mainkeys=1 (requires adb root) ..."
    Invoke-Adb root | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb shell setprop qemu.hw.mainkeys 1
}

Show-Verification -KiayoApplied $kiayoApplied

$serialArg = if ($Serial) { " -Serial $Serial" } else { "" }
$adbSerial = if ($Serial) { " -s $Serial" } else { "" }

Write-Host ""
Write-Host "Relaunch Viwa on the device:"
Write-Host "  adb$adbSerial shell am force-stop $pkg"
Write-Host "  adb$adbSerial shell am start -n $pkg/.ui.MainActivity"
Write-Host ""
Write-Host "To undo: .\provision-viwa-kiosk.ps1$serialArg -Rollback"
