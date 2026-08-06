#Requires -Version 5.1
param(
    [string]$UpdateBaseUrl = $env:UPDATE_BASE_URL,
    [string]$ReleaseToken = $env:RELEASE_TOKEN
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

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$SigningDir = Join-Path $RepoRoot 'signing'
$EnvFile = Join-Path $SigningDir 'release-remote.env'

Set-Location -LiteralPath $RepoRoot

Import-EnvFile -Path $EnvFile

if ([string]::IsNullOrWhiteSpace($UpdateBaseUrl)) {
    if (-not [string]::IsNullOrWhiteSpace($env:UPDATE_BASE_URL)) {
        $UpdateBaseUrl = $env:UPDATE_BASE_URL
    } else {
        $UpdateBaseUrl = 'https://tl.vitamin-water.ru/android-ota'
    }
}

if ([string]::IsNullOrWhiteSpace($ReleaseToken)) {
    if (-not [string]::IsNullOrWhiteSpace($env:RELEASE_TOKEN)) {
        $ReleaseToken = $env:RELEASE_TOKEN
    }
}

$UpdateBaseUrl = $UpdateBaseUrl.TrimEnd('/')

if ([string]::IsNullOrWhiteSpace($UpdateBaseUrl)) {
    throw 'UPDATE_BASE_URL is not set. Set env UPDATE_BASE_URL or signing/release-remote.env.'
}

if ([string]::IsNullOrWhiteSpace($ReleaseToken)) {
    throw 'RELEASE_TOKEN is not set. Set env RELEASE_TOKEN or add it to signing/release-remote.env.'
}

if (-not (Test-Path -LiteralPath $SigningDir)) {
    New-Item -ItemType Directory -Path $SigningDir | Out-Null
}

$AuthHeaders = @{
    Authorization = "Bearer $ReleaseToken"
}

Write-Step 'Downloading release keystore'
$KeystorePath = Join-Path $SigningDir 'release.jks'
Invoke-WebRequest -Uri "$UpdateBaseUrl/admin/signing/release.jks" -Headers $AuthHeaders -OutFile $KeystorePath

Write-Step 'Downloading signing credentials'
$CredentialsResponse = Invoke-WebRequest -Uri "$UpdateBaseUrl/admin/signing/credentials.json" -Headers $AuthHeaders -UseBasicParsing
$Credentials = $CredentialsResponse.Content | ConvertFrom-Json

if ([string]::IsNullOrWhiteSpace($Credentials.keyAlias)) {
    throw 'credentials.json did not contain keyAlias.'
}
if ([string]::IsNullOrWhiteSpace($Credentials.storePassword)) {
    throw 'credentials.json did not contain storePassword.'
}
if ([string]::IsNullOrWhiteSpace($Credentials.keyPassword)) {
    throw 'credentials.json did not contain keyPassword.'
}

Set-Content -LiteralPath (Join-Path $SigningDir '.storepass') -Value $Credentials.storePassword -NoNewline -Encoding ASCII
Set-Content -LiteralPath (Join-Path $SigningDir '.keypass') -Value $Credentials.keyPassword -NoNewline -Encoding ASCII

$env:KEYSTORE_PATH = 'signing/release.jks'
$env:STORE_PASSWORD = $Credentials.storePassword
$env:KEY_PASSWORD = $Credentials.keyPassword
$env:KEY_ALIAS = $Credentials.keyAlias

Write-Step 'Building assembleRelease'
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

Write-Step "Uploading $($Apk.Name)"
$UploadHeaders = @{
    Authorization = "Bearer $ReleaseToken"
    'X-Filename' = $Apk.Name
}
$UploadResponse = Invoke-WebRequest `
    -Uri "$UpdateBaseUrl/admin/upload" `
    -Method POST `
    -Headers $UploadHeaders `
    -ContentType 'application/vnd.android.package-archive' `
    -InFile $Apk.FullName `
    -UseBasicParsing
$UploadJson = $UploadResponse.Content | ConvertFrom-Json
if (-not $UploadJson.ok) {
    throw 'Upload response did not indicate success.'
}

Write-Step 'Fetching version.json'
$VersionResponse = Invoke-WebRequest -Uri "$UpdateBaseUrl/version.json" -UseBasicParsing
Write-Host $VersionResponse.Content

Write-Host ""
Write-Host 'Release complete.'
Write-Host "  APK: $($Apk.FullName)"
Write-Host "  Uploaded: $($Apk.Name)"
Write-Host "  version.json: $UpdateBaseUrl/version.json"
