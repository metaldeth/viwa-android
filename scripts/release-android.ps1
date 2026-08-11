#Requires -Version 5.1
param(
    [switch]$Publish,
    [string]$TelemetryApiUrl = $env:TELEMETRY_API_URL,
    [string]$UploadToken = $env:OTA_RELEASE_UPLOAD_TOKEN,
    [string]$Channel = 'STABLE',
    [string]$Changelog = ''
)

$ErrorActionPreference = 'Stop'

function Import-EnvFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) {
            return
        }
        $eq = $line.IndexOf('=')
        if ($eq -lt 1) {
            return
        }
        $name = $line.Substring(0, $eq).Trim()
        $value = $line.Substring($eq + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path "env:$name" -Value $value
    }
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message"
}

function Read-SigningSecretFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return (Get-Content -LiteralPath $Path -Raw).Trim()
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$SigningDir = Join-Path $RepoRoot 'signing'
$EnvFile = Join-Path $SigningDir 'release-remote.env'

Set-Location -LiteralPath $RepoRoot
Import-EnvFile -Path $EnvFile

if ([string]::IsNullOrWhiteSpace($TelemetryApiUrl)) {
    if (-not [string]::IsNullOrWhiteSpace($env:TELEMETRY_API_URL)) {
        $TelemetryApiUrl = $env:TELEMETRY_API_URL
    } else {
        $TelemetryApiUrl = 'https://tl.vitamin-water.ru'
    }
}

if ([string]::IsNullOrWhiteSpace($UploadToken)) {
    if (-not [string]::IsNullOrWhiteSpace($env:OTA_RELEASE_UPLOAD_TOKEN)) {
        $UploadToken = $env:OTA_RELEASE_UPLOAD_TOKEN
    }
}

$TelemetryApiUrl = $TelemetryApiUrl.TrimEnd('/')

if ([string]::IsNullOrWhiteSpace($UploadToken)) {
    throw 'OTA_RELEASE_UPLOAD_TOKEN is not set. Set env OTA_RELEASE_UPLOAD_TOKEN or add it to signing/release-remote.env.'
}

$KeystorePath = $env:KEYSTORE_PATH
if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Join-Path $SigningDir 'release.jks'
}
if (-not [System.IO.Path]::IsPathRooted($KeystorePath)) {
    $KeystorePath = Join-Path $RepoRoot $KeystorePath
}
if (-not (Test-Path -LiteralPath $KeystorePath)) {
    throw "Release keystore not found: $KeystorePath. Place signing/release.jks locally (gitignored) or set KEYSTORE_PATH."
}

$StorePassword = $env:STORE_PASSWORD
if ([string]::IsNullOrWhiteSpace($StorePassword)) {
    $StorePassword = Read-SigningSecretFile -Path (Join-Path $SigningDir '.storepass')
}
$KeyPassword = $env:KEY_PASSWORD
if ([string]::IsNullOrWhiteSpace($KeyPassword)) {
    $KeyPassword = Read-SigningSecretFile -Path (Join-Path $SigningDir '.keypass')
}
if ([string]::IsNullOrWhiteSpace($KeyPassword)) {
    $KeyPassword = $StorePassword
}
$KeyAlias = $env:KEY_ALIAS
if ([string]::IsNullOrWhiteSpace($KeyAlias)) {
    $KeyAlias = 'release'
}

if ([string]::IsNullOrWhiteSpace($StorePassword)) {
    throw 'STORE_PASSWORD is not set. Set env STORE_PASSWORD or signing/.storepass.'
}
if ([string]::IsNullOrWhiteSpace($KeyAlias)) {
    throw 'KEY_ALIAS is not set.'
}

$env:KEYSTORE_PATH = $KeystorePath
$env:STORE_PASSWORD = $StorePassword
$env:KEY_PASSWORD = $KeyPassword
$env:KEY_ALIAS = $KeyAlias

Write-Step 'Building assembleRelease (local signing)'
$Gradlew = Join-Path $RepoRoot 'gradlew.bat'
& $Gradlew assembleRelease
if ($LASTEXITCODE -ne 0) {
    throw "gradlew assembleRelease failed with exit code $LASTEXITCODE"
}

Write-Step 'Locating release APK'
$ApkPattern = Join-Path $RepoRoot 'app\build\outputs\apk\release\viwa-android-*-release.apk'
$ApkFiles = Get-ChildItem -Path $ApkPattern -File | Sort-Object Name
if (-not $ApkFiles -or $ApkFiles.Count -eq 0) {
    throw "No release APK found matching $ApkPattern"
}
$Apk = $ApkFiles[$ApkFiles.Count - 1]

Write-Step "Uploading $($Apk.Name) to telemetry"
$UploadUrl = "$TelemetryApiUrl/api/v1/app-releases/upload"
$UploadArgs = @(
    '-sS',
    '-X', 'POST',
    $UploadUrl,
    '-H', "Authorization: Bearer $UploadToken",
    '-F', "channel=$Channel",
    '-F', "file=@$($Apk.FullName)"
)
if (-not [string]::IsNullOrWhiteSpace($Changelog)) {
    $UploadArgs += @('-F', "changelog=$Changelog")
}

$UploadJsonRaw = & curl.exe @UploadArgs
if ($LASTEXITCODE -ne 0) {
    throw "Telemetry upload failed with exit code $LASTEXITCODE"
}
$UploadJson = $UploadJsonRaw | ConvertFrom-Json
if (-not $UploadJson.id) {
    throw "Upload response did not contain release id: $UploadJsonRaw"
}

$ReleaseId = $UploadJson.id
$VersionName = $UploadJson.versionName
$VersionCode = $UploadJson.versionCode

if ($Publish) {
    Write-Step "Publishing release $ReleaseId"
    $PublishUrl = "$TelemetryApiUrl/api/v1/app-releases/$ReleaseId/publish"
    $PublishBody = '{"rolloutPercent":100}'
    $PublishJsonRaw = & curl.exe -sS -X POST $PublishUrl `
        -H "Authorization: Bearer $UploadToken" `
        -H 'Content-Type: application/json' `
        -d $PublishBody
    if ($LASTEXITCODE -ne 0) {
        throw "Telemetry publish failed with exit code $LASTEXITCODE"
    }
    $PublishJson = $PublishJsonRaw | ConvertFrom-Json
    if ($PublishJson.status -ne 'PUBLISHED') {
        throw "Publish response status is not PUBLISHED: $PublishJsonRaw"
    }
}

Write-Host ""
Write-Host 'Release complete.'
Write-Host "  APK: $($Apk.FullName)"
Write-Host "  releaseId: $ReleaseId"
Write-Host "  versionName: $VersionName"
Write-Host "  versionCode: $VersionCode"
if ($Publish) {
    Write-Host '  status: PUBLISHED (rollout 100%)'
} else {
    Write-Host '  status: DRAFT (pass -Publish to publish with rollout 100%)'
}
