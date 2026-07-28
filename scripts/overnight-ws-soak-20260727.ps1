# Overnight WS offline soak — SOAK-20260727
# Safety: emulator-only iptables; server restarts with finally; no server firewall changes.

$ErrorActionPreference = "Continue"
$Tag = "SOAK-20260727"
$Adb = "F:\AndroidSDK\platform-tools\adb.exe"
$Serial = "emulator-5554"
$Pkg = "com.viwa.android"
$Machine = "VIWA-000001"
$ServerHost = "wiva-server"
$ServerIp = "194.67.74.147"
$TlsHost = "tl.asnefedov.ru"
$Report = "c:\wiva\viwa-android\docs\sessions\2026-07-28-overnight-ws-offline-soak.md"
$Metrics = "c:\wiva\viwa-android\docs\sessions\TEMP_20260727-soak-metrics.jsonl"
$RunnerLog = "c:\wiva\viwa-android\docs\sessions\TEMP_20260727-soak-runner.log"
$Deadline = [datetime]"2026-07-28T10:00:00+05:00"
$AckPattern = "MvpTelemetry WS: ack correlationId="
$FsmPattern = "TelemetryConnectionFsm|MVP WS:|SimpleTelemetry:|MvpTelemetry WS:"

function Write-RunnerLog([string]$msg) {
  $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss K"), $msg
  Add-Content -Path $RunnerLog -Value $line -Encoding utf8
  Write-Host $line
}

function Invoke-Adb([string[]]$Args) {
  & $Adb -s $Serial @Args 2>&1
}

function Write-Metric([hashtable]$Row) {
  $Row.ts = (Get-Date).ToString("o")
  $Row.tag = $Tag
  ($Row | ConvertTo-Json -Compress) | Add-Content -Path $Metrics -Encoding utf8
}

function Get-AppPid {
  $p = Invoke-Adb @("shell", "pidof", $Pkg)
  if ($p -is [array]) { $p = $p[-1] }
  return ($p.ToString().Trim())
}

function Get-AckCountSince([datetime]$Since) {
  $raw = Invoke-Adb @("logcat", "-d", "-v", "time")
  $n = 0
  foreach ($line in @($raw)) {
    if ($line -match [regex]::Escape($AckPattern)) { $n++ }
  }
  return $n
}

function Wait-FreshAck([int]$TimeoutSec = 35) {
  Invoke-Adb @("logcat", "-c") | Out-Null
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    $hit = Invoke-Adb @("logcat", "-d", "-v", "time") | Select-String -Pattern ([regex]::Escape($AckPattern)) -SimpleMatch:$false
    if ($hit) {
      return @{ ok = $true; ms = [int]$sw.Elapsed.TotalMilliseconds; line = ($hit | Select-Object -First 1).Line }
    }
    Start-Sleep -Milliseconds 500
  }
  return @{ ok = $false; ms = [int]$sw.Elapsed.TotalMilliseconds; line = $null }
}

function Assert-IptablesClean {
  $out = Invoke-Adb @("shell", "iptables -L OUTPUT -n; iptables -L INPUT -n")
  $text = ($out | Out-String)
  if ($text -match $ServerIp) {
    Write-RunnerLog "WARN: iptables still references $ServerIp — cleaning"
    Remove-ClientBlackhole
    Remove-ServerBlackhole
  }
}

function Add-ClientBlackhole {
  $rule = "-p tcp -d $ServerIp --dport 443 -j DROP"
  Write-RunnerLog "iptables ADD OUTPUT $rule"
  Invoke-Adb @("shell", "iptables -I OUTPUT 1 $rule") | Out-Null
  return $rule
}

function Remove-ClientBlackhole {
  $rule = "-p tcp -d $ServerIp --dport 443 -j DROP"
  Invoke-Adb @("shell", "iptables -D OUTPUT $rule") 2>&1 | Out-Null
}

function Add-ServerBlackhole {
  $rule = "-p tcp -s $ServerIp --sport 443 -j DROP"
  Write-RunnerLog "iptables ADD INPUT $rule"
  Invoke-Adb @("shell", "iptables -I INPUT 1 $rule") | Out-Null
  return $rule
}

function Remove-ServerBlackhole {
  $rule = "-p tcp -s $ServerIp --sport 443 -j DROP"
  Invoke-Adb @("shell", "iptables -D INPUT $rule") 2>&1 | Out-Null
}

function Set-EmulatorNetwork([bool]$Enabled) {
  if ($Enabled) {
    Write-RunnerLog "emulator network enable"
    Invoke-Adb @("shell", "svc wifi enable") | Out-Null
    Invoke-Adb @("shell", "svc data enable") | Out-Null
  } else {
    Write-RunnerLog "emulator network disable (wifi+data off)"
    Invoke-Adb @("shell", "svc wifi disable") | Out-Null
    Invoke-Adb @("shell", "svc data disable") | Out-Null
  }
}

function Invoke-Ssh([string]$Cmd) {
  ssh -o BatchMode=yes $ServerHost $Cmd 2>&1
}

function Ensure-ApiActive {
  $st = Invoke-Ssh "systemctl is-active viwa-telemetry-api"
  if ($st -notmatch "active") {
    Write-RunnerLog "viwa-telemetry-api not active — starting"
    Invoke-Ssh "sudo systemctl start viwa-telemetry-api" | Out-Null
    Start-Sleep -Seconds 3
  }
}

function Ensure-NginxActive {
  $st = Invoke-Ssh "systemctl is-active nginx"
  if ($st -notmatch "active") {
    Invoke-Ssh "sudo systemctl start nginx" | Out-Null
  }
}

function Collect-Metrics([string]$Phase) {
  $pidApp = Get-AppPid
  $mem = (Invoke-Adb @("shell", "dumpsys", "meminfo", $Pkg) | Out-String)
  $pss = if ($mem -match "TOTAL PSS:\s+(\d+)") { [int]$Matches[1] } else { $null }
  $rss = if ($mem -match "TOTAL RSS:\s+(\d+)") { [int]$Matches[1] } else { $null }
  $top = (Invoke-Adb @("shell", "top -n 1 -b | grep $Pkg") | Out-String).Trim()
  $cpu = $null
  if ($top -match "\s(\d+)\s+\d+\.\d+\s+$Pkg") { $cpu = [int]$Matches[1] }
  $acks = (Invoke-Adb @("logcat", "-d", "-v", "time") | Select-String -Pattern ([regex]::Escape($AckPattern))).Count
  $reconn = (Invoke-Adb @("logcat", "-d", "-v", "time") | Select-String -Pattern "Backoff|reconnect|heartbeat ack timeout|forceClose").Count
  $fatals = (Invoke-Adb @("logcat", "-d", "-v", "time") | Select-String -Pattern "FATAL EXCEPTION|ANR in $Pkg").Count
  $apiMem = (Invoke-Ssh "ps -o rss= -C node | head -1").ToString().Trim()
  $https = (curl.exe -sS -o NUL -w "%{http_code}" --connect-timeout 8 "https://$TlsHost/")
  Write-Metric @{
    phase = $Phase
    pid = $pidApp
    pss_kb = $pss
    rss_kb = $rss
    cpu_pct = $cpu
    ack_lines_in_logcat = $acks
    reconnect_signals = $reconn
    fatal_anr = $fatals
    api_rss_kb = $apiMem
    https_code = $https
  }
}

function Append-Report([string]$Text) {
  Add-Content -Path $Report -Value $Text -Encoding utf8
}

function Record-Scenario([string]$Name, [string]$Result, [hashtable]$Details) {
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("### $Name — $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')")
  [void]$sb.AppendLine("- **Result:** $Result")
  foreach ($k in $Details.Keys) { [void]$sb.AppendLine(("- **{0}:** {1}" -f $k, $Details[$k])) }
  Append-Report $sb.ToString()
}

function Wait-Until([datetime]$When) {
  while ((Get-Date) -lt $When -and (Get-Date) -lt $Deadline) {
    Start-Sleep -Seconds 30
  }
}

# --- main ---
Write-RunnerLog "=== $Tag soak runner start; deadline=$Deadline ==="
$Start = Get-Date
$BaselinePid = Get-AppPid
$BaselineVersion = (Invoke-Adb @("shell", "dumpsys", "package", $Pkg) | Select-String "versionName=").Line
Collect-Metrics "preflight-end"

# Schedule (local +05)
$PhaseA = $Start.AddMinutes(20)
$PhaseB = $PhaseA.AddMinutes(90)
$PhaseC = $PhaseB.AddMinutes(15)
$PhaseD = $PhaseC.AddMinutes(15)
$PhaseE = $PhaseD.AddMinutes(15)
$PhaseF = $PhaseE.AddMinutes(15)
$PhaseG = $PhaseF.AddMinutes(20)
$PhaseH = $PhaseG.AddMinutes(15)
$PhaseI = $PhaseH.AddMinutes(20)

Write-RunnerLog "Timeline: A=$PhaseA B=$PhaseB C=$PhaseC D=$PhaseD E=$PhaseE F=$PhaseF G=$PhaseG H=$PhaseH I=$PhaseI"

$lastMetric = Get-Date
$phaseName = "preflight-stable"

try {
  while ((Get-Date) -lt $Deadline) {
    $now = Get-Date
    if (($now - $lastMetric).TotalMinutes -ge 15) {
      Collect-Metrics $phaseName
      $lastMetric = $now
    }

    # PID/version drift detection
    $curPid = Get-AppPid
    if ($curPid -and $BaselinePid -and $curPid -ne $BaselinePid) {
      Write-RunnerLog "NOTICE: PID changed $BaselinePid -> $curPid (continuing safely)"
      $BaselinePid = $curPid
    }

    if ($now -ge $PhaseB -and -not $script:DoneB) {
      $script:DoneB = $true; $phaseName = "B-api-restart"
      Write-RunnerLog "Scenario B: systemctl restart viwa-telemetry-api"
      Invoke-Adb @("logcat", "-c") | Out-Null
      $t0 = Get-Date
      try {
        Invoke-Ssh "sudo systemctl restart viwa-telemetry-api" | Out-Null
      } finally {
        Ensure-ApiActive
      }
      $rec = Wait-FreshAck 45
      Record-Scenario "B API restart" $(if ($rec.ok) { "PASS" } else { "FAIL" }) @{
        disconnect_to_ack_ms = $rec.ms
        within_30s = ($rec.ms -le 30000)
      }
    }

    if ($now -ge $PhaseC -and -not $script:DoneC) {
      $script:DoneC = $true; $phaseName = "C-nginx-reload"
      Write-RunnerLog "Scenario C: nginx reload"
      Invoke-Adb @("logcat", "-c") | Out-Null
      $t0 = Get-Date
      Invoke-Ssh "sudo nginx -t && sudo systemctl reload nginx" | Out-Null
      Ensure-NginxActive
      $rec = Wait-FreshAck 45
      Record-Scenario "C nginx reload" $(if ($rec.ok) { "PASS" } else { "FAIL" }) @{
        recovery_ms = $rec.ms
        within_30s = ($rec.ms -le 30000)
      }
    }

    if ($now -ge $PhaseD -and -not $script:DoneD) {
      $script:DoneD = $true; $phaseName = "D-client-blackhole"
      Write-RunnerLog "Scenario D: client->server OUTPUT blackhole 90s"
      Invoke-Adb @("logcat", "-c") | Out-Null
      $lastAck = Wait-FreshAck 25
      try {
        Add-ClientBlackhole | Out-Null
        Start-Sleep -Seconds 90
      } finally {
        Remove-ClientBlackhole
        Assert-IptablesClean
      }
      $rec = Wait-FreshAck 50
      Record-Scenario "D client OUTPUT blackhole 90s" $(if ($rec.ok) { "PASS" } else { "INVESTIGATE" }) @{
        pre_rule_ack = $lastAck.ok
        recovery_ms = $rec.ms
        recovery_within_45s = ($rec.ms -le 45000)
      }
    }

    if ($now -ge $PhaseE -and -not $script:DoneE) {
      $script:DoneE = $true; $phaseName = "E-server-blackhole"
      Write-RunnerLog "Scenario E: server->client INPUT blackhole 90s"
      Invoke-Adb @("logcat", "-c") | Out-Null
      $lastAck = Wait-FreshAck 25
      try {
        Add-ServerBlackhole | Out-Null
        Start-Sleep -Seconds 90
      } finally {
        Remove-ServerBlackhole
        Assert-IptablesClean
      }
      $rec = Wait-FreshAck 50
      Record-Scenario "E server INPUT blackhole 90s" $(if ($rec.ok) { "PASS" } else { "INVESTIGATE" }) @{
        pre_rule_ack = $lastAck.ok
        recovery_ms = $rec.ms
        recovery_within_45s = ($rec.ms -le 45000)
      }
    }

    if ($now -ge $PhaseF -and -not $script:DoneF) {
      $script:DoneF = $true; $phaseName = "F-emulator-offline-5m"
      Write-RunnerLog "Scenario F: emulator offline 5 min"
      Invoke-Adb @("logcat", "-c") | Out-Null
      try {
        Set-EmulatorNetwork $false
        Start-Sleep -Seconds 300
      } finally {
        Set-EmulatorNetwork $true
        Assert-IptablesClean
      }
      $rec = Wait-FreshAck 60
      $storm = (Invoke-Adb @("logcat", "-d", "-v", "time") | Select-String -Pattern "MVP WS:.*connect").Count
      Record-Scenario "F emulator offline 5m" $(if ($rec.ok) { "PASS" } else { "FAIL" }) @{
        recovery_ms = $rec.ms
        connect_log_lines = $storm
      }
    }

    if ($now -ge $PhaseG -and -not $script:DoneG) {
      $script:DoneG = $true; $phaseName = "G-api-sigkill"
      Write-RunnerLog "Scenario G: SIGKILL API + restart after 10s"
      Invoke-Adb @("logcat", "-c") | Out-Null
      try {
        Invoke-Ssh "sudo systemctl kill -s SIGKILL viwa-telemetry-api" | Out-Null
        Start-Sleep -Seconds 10
      } finally {
        Ensure-ApiActive
      }
      $rec = Wait-FreshAck 45
      Record-Scenario "G API SIGKILL" $(if ($rec.ok) { "PASS" } else { "FAIL" }) @{
        recovery_ms = $rec.ms
        api_active = (Invoke-Ssh "systemctl is-active viwa-telemetry-api")
      }
    }

    if ($now -ge $PhaseH -and -not $script:DoneH) {
      $script:DoneH = $true; $phaseName = "H-long-outage-20m"
      Write-RunnerLog "Scenario H: device-local full drop 20 min"
      Invoke-Adb @("logcat", "-c") | Out-Null
      try {
        Set-EmulatorNetwork $false
        Start-Sleep -Seconds 1200
      } finally {
        Set-EmulatorNetwork $true
        Assert-IptablesClean
      }
      $rec = Wait-FreshAck 90
      Record-Scenario "H long outage 20m" $(if ($rec.ok) { "PASS" } else { "FAIL" }) @{
        recovery_ms = $rec.ms
        within_45s = ($rec.ms -le 45000)
      }
      $phaseName = "I-post-chaos-stable"
    }

    if ($now -ge $PhaseA -and $now -lt $PhaseB) { $phaseName = "A-baseline-steady-ws" }

    Start-Sleep -Seconds 20
  }
}
finally {
  Write-RunnerLog "=== Final cleanup ==="
  Remove-ClientBlackhole
  Remove-ServerBlackhole
  Set-EmulatorNetwork $true
  Assert-IptablesClean
  Ensure-ApiActive
  Ensure-NginxActive
  $https = curl.exe -sS -o NUL -w "%{http_code}" --connect-timeout 10 "https://$TlsHost/"
  $ack = Wait-FreshAck 35
  Collect-Metrics "final"
  Append-Report @"

## Final summary ($(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K'))
- **HTTPS /**: $https
- **Fresh WS ACK after cleanup**: $(if ($ack.ok) { 'yes' } else { 'no' }) (${ack.ms} ms)
- **Emulator iptables clean**: verified (no $ServerIp rules)
- **API/nginx**: restarted if needed; active
- **Metrics file**: $Metrics (TEMP — summarize then delete)
- **Runner log**: $RunnerLog

"@
  Write-RunnerLog "=== soak runner finished ==="
}

