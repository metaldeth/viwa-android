#Requires -Version 5.1
param(
    [string]$SshHost = 'wiva-server',
    [string]$RemoteSigningDir = '/opt/viwa-android/signing',
    [string]$UpdateBaseUrl = 'https://tl.vitamin-water.ru/android-ota',
    [string]$Token
)

$ErrorActionPreference = 'Stop'

function Read-TrimmedFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return (Get-Content -LiteralPath $Path -Raw).Trim()
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$SigningDir = Join-Path $RepoRoot 'signing'
$KeystorePath = Join-Path $SigningDir 'release.jks'
$StorePassPath = Join-Path $SigningDir '.storepass'
$KeyPassPath = Join-Path $SigningDir '.keypass'
$KeyAliasPath = Join-Path $SigningDir 'key-alias'
$EnvFilePath = Join-Path $SigningDir 'release-remote.env'

foreach ($required in @($KeystorePath, $StorePassPath, $KeyPassPath)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Missing local signing file: $required"
    }
}

$StorePassword = Read-TrimmedFile -Path $StorePassPath
$KeyPassword = Read-TrimmedFile -Path $KeyPassPath
$KeyAlias = Read-TrimmedFile -Path $KeyAliasPath
if ([string]::IsNullOrWhiteSpace($KeyAlias)) {
    $KeyAlias = 'viwa-release'
}

Write-Host "Creating remote directory $RemoteSigningDir on $SshHost"
ssh $SshHost "mkdir -p '$RemoteSigningDir'"

Write-Host 'Uploading release.jks, .storepass, .keypass'
scp $KeystorePath "${SshHost}:${RemoteSigningDir}/release.jks"
scp $StorePassPath "${SshHost}:${RemoteSigningDir}/.storepass"
scp $KeyPassPath "${SshHost}:${RemoteSigningDir}/.keypass"

# UTF-8 without BOM — PowerShell ConvertTo-Json/heredoc often breaks JSON on Linux.
$CredentialsPath = Join-Path $SigningDir 'credentials.json'
$CredentialsJson = '{"keyAlias":"' + $KeyAlias.Replace('\', '\\').Replace('"', '\"') +
    '","storePassword":"' + $StorePassword.Replace('\', '\\').Replace('"', '\"') +
    '","keyPassword":"' + $KeyPassword.Replace('\', '\\').Replace('"', '\"') + '"}'
[System.IO.File]::WriteAllText($CredentialsPath, $CredentialsJson)
[System.IO.File]::WriteAllText($KeyAliasPath, $KeyAlias)

Write-Host 'Uploading credentials.json and key-alias'
scp $CredentialsPath "${SshHost}:${RemoteSigningDir}/credentials.json"
scp $KeyAliasPath "${SshHost}:${RemoteSigningDir}/key-alias"

Write-Host 'Hardening remote signing permissions'
ssh $SshHost "chmod 600 '$RemoteSigningDir/release.jks' '$RemoteSigningDir/.storepass' '$RemoteSigningDir/.keypass' '$RemoteSigningDir/credentials.json' '$RemoteSigningDir/key-alias'"

if (-not (Test-Path -LiteralPath $SigningDir)) {
    New-Item -ItemType Directory -Path $SigningDir | Out-Null
}

$EnvLines = @(
    '# Remote OTA release settings (do not commit secrets)',
    "UPDATE_BASE_URL=$UpdateBaseUrl",
    'RELEASE_TOKEN='
)
if (-not [string]::IsNullOrWhiteSpace($Token)) {
    $EnvLines[2] = "RELEASE_TOKEN=$Token"
}
Set-Content -LiteralPath $EnvFilePath -Value $EnvLines -Encoding ASCII

Write-Host ''
Write-Host 'Bootstrap complete.'
Write-Host "  Remote signing dir: ${SshHost}:${RemoteSigningDir}"
Write-Host "  Local env template: $EnvFilePath"
if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Host '  Fill RELEASE_TOKEN in signing/release-remote.env before running release-android.cmd'
} else {
    Write-Host '  RELEASE_TOKEN written to signing/release-remote.env'
}
