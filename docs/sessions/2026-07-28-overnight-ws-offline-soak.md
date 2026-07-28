# 2026-07-28 — overnight WS offline soak (SOAK-20260727)

**Status:** IN PROGRESS (runner background)  
**Deadline:** 2026-07-28 10:00 +05:00  
**Device:** emulator-5554, machine VIWA-000001, package com.viwa.android  
**Server:** wiva-server / https://tl.asnefedov.ru (194.67.74.147)  
**Tag for cleanup:** SOAK-20260727  

## Safety acknowledgements
- No server iptables/UFW changes (emulator-scoped faults only).
- API/nginx restarts wrapped with ensure-active in runner finally.
- No commits/push; fake data only when tagged.

## Pre-flight (2026-07-27 19:08:50 +05:00)

| Check | Result |
|-------|--------|
| Emulator ADB | device (Android 11, GMT clock on device) |
| HTTPS tl.asnefedov.ru | 200 |
| viwa-telemetry-api | active |
| nginx | active |
| Machine registration | valid (public by-serial VIWA-000001) |
| App version | 26.07.27.01 (versionCode 189) |
| App PID (baseline) | 28939 (may change — runner tracks) |
| WS to server | ESTAB tcp :443 to 194.67.74.147 |
| Emulator root + iptables test | 5s OUTPUT drop tested; CLEAN after remove |
| Fresh ACK detector | logcat clear → `MvpTelemetry WS: ack correlationId=` within ~15s |
| Mem baseline PSS/RSS | 178164 / 293040 KB |
| Outbox Room | **Note:** wiva.db has json_data only (no machine_outbox table on this device DB) |

## Timeline (scheduled local +05)
- **A** Baseline steady WS: start +20m, duration 90m
- **B** API restart: after A
- **C** nginx reload: +15m
- **D** client OUTPUT blackhole 90s: +15m
- **E** server INPUT blackhole 90s: +15m
- **F** emulator offline 5m: +15m
- **G** API SIGKILL: +20m
- **H** long offline 20m: +15m
- **I** post-chaos stable until 10:00

## Scenario results
_(filled by runner and manual queue checks)_

## Queue / functional validation
- **REST batch outbox:** TBD (record capability during run)
- **Fake sales UI/uiautomator2:** TBD
- **Subscriptions/QR/new contracts:** record BLOCKED_BY_NOT_DEPLOYED if not on prod

## Metrics trend
See TEMP_20260727-soak-metrics.jsonl during run; summarized at end.

## Cleanup list (SOAK-20260727)
_(IDs only — no secrets)_


## Runner
- **PID:** 1680 (file: docs/sessions/TEMP_20260727-soak-runner.pid)
- **Script:** scripts/overnight-ws-soak-20260727.ps1
- **Logs:** TEMP_20260727-soak-runner.log, TEMP_20260727-soak-metrics.jsonl
- **Started:** 2026-07-27 19:11:10 +05:00


- **Runner correction 19:12 +05:** initial detached PID 1680 exited before scenarios; no fault was active. Relaunched managed runner PID 19080; schedule recalculated from relaunch.

## Wake-lock fix verification (2026-07-27 19:35–19:39 +05)
- **Build:** `gradlew.bat assembleDebug` — SUCCESS (no clean; manifest processed).
- **Install:** `adb -s emulator-5554 install -r viwa-android-26.07.27.01-debug.apk` — Success.
- **Permission:** `android.permission.WAKE_LOCK: granted=true` (`dumpsys package com.viwa.android`).
- **Stability:** MainActivity + idle video (`IdleVideoOverlay`); logcat cleared 19:35:50 +05; **181 s** soak; **PID 30342** unchanged (T+60/120/180 s).
- **Crashes:** no `FATAL EXCEPTION: ExoPlayer:Playback`; no `SecurityException` WAKE_LOCK.
- **Telemetry:** fresh WS ACKs after launch (gen=2), e.g. `22eaa5fb-81b4-4971-8221-aae53e57b1e7`; no telemetry traffic during idle window (expected).
- **Baseline PID note:** prior `[19:32:30] manual-install … pid_last=30139 … ExoPlayer_wakeLock_emulator_crash` explains overnight baseline PID restart; post-fix process stable.
- **Runner log:** `docs/sessions/TEMP_20260727-soak-runner.log` entry `wake-lock-fix-verify`.

## Production deploy (viwa-telemetry resilience)
- **When:** 2026-07-27 ~19:39 +05 (UTC 14:39)
- **Release ID:** 20260727193930
- **Rollback release:** 20260727171500
- **DB backup:** /var/backups/viwa-telemetry/viwa_telemetry_pre_20260727T143941Z.dump
- **Migrations applied:** 20260727140000_machine_outbox_batch, 20260727150000_offline_entitlement, 20260727180000_technician_keys (status: up to date)
- **Feature flags (prod):** MACHINE_OUTBOX_ENABLED=false, FEATURE_OFFLINE_ENTITLEMENT=false, FEATURE_TECHNICIAN_KEYS=false, FEATURE_WS_PROTOCOL_V3=true, WS_IDLE_TIMEOUT_MS=90000
- **Smoke:** login/root/technician-keys SPA 200; register empty 400; outbox/offline/tech machine routes 404; WSS no-auth 401; systemd active
- **VIWA-000001:** OFFLINE after API restart (stale sweep at 14:41:45 UTC); reconnect not observed in window
- **Git:** uncommitted local deploy (no commit/push)

### B API restart — 2026-07-27 21:03:13 +05:00
- **Runner result:** INVALID (false negative)
- **Cause:** `Wait-FreshAck()` clears logcat after the API restart completed, deleting the first recovered ACK before measurement.
- **Observed recovery:** API restart began at 21:02:25 +05; ACKs resumed at 21:02:30 +05 (`gen=6`), approximately 5 seconds later.
- **Acceptance:** PASS (`<30s`), based on preserved logcat evidence; runner's `45524 ms` value is its subsequent timeout, not reconnect latency.


### C nginx reload — 2026-07-27 21:17:57 +05:00
- **Result:** FAIL
- **recovery_ms:** 45099
- **within_30s:** False


### D client OUTPUT blackhole 90s — 2026-07-27 21:35:06 +05:00
- **Result:** INVESTIGATE
- **pre_rule_ack:** False
- **recovery_ms:** 50038
- **recovery_within_45s:** False


### E server INPUT blackhole 90s — 2026-07-27 21:50:38 +05:00
- **Result:** INVESTIGATE
- **pre_rule_ack:** False
- **recovery_ms:** 50387
- **recovery_within_45s:** False


### F emulator offline 5m — 2026-07-27 22:08:21 +05:00
- **Result:** FAIL
- **recovery_ms:** 60361
- **connect_log_lines:** 0


### G API SIGKILL — 2026-07-27 22:23:21 +05:00
- **Result:** FAIL
- **recovery_ms:** 45022
- **api_active:** active


### H long outage 20m — 2026-07-27 22:58:54 +05:00
- **Result:** FAIL
- **recovery_ms:** 90010
- **within_45s:** False

## Morning checkpoint — 2026-07-28 08:52–08:54 +05
- **App stability:** process `30342` remained alive; no fatal exception or ANR found.
- **Server health:** `viwa-telemetry-api` and nginx active; HTTPS metrics stayed 200.
- **Critical WS result:** `VIWA-000001` remained `OFFLINE`; server `last_seen_at` stopped at 2026-07-27 22:21:51 +05. The device had validated Wi-Fi/mobile connectivity but no established socket to `194.67.74.147:443`.
- **Recovery action:** emulator Wi-Fi/mobile off→on at 08:53 +05 triggered `TelemetryNetworkObserver`; WS ACK resumed on generation 8 at 08:53:40 +05 and the server returned the machine to `ONLINE`.
- **Cleanup:** emulator firewall contains no rules referencing `194.67.74.147`; API/nginx are active.
- **Verdict:** overnight stability of the installed pre-fix build **FAILS** silent/long-outage recovery. Process/media stability passes. Scenario B recovered in ~5 s, while later harness latency fields are partly invalid because `Wait-FreshAck()` clears logcat after the recovery event; the persistent OFFLINE state after scenario H is independently confirmed by server state and is a real product failure.
- **Required retest:** install the new reconnect-guard build after this soak ends and repeat API SIGKILL + 20-minute device outage with a corrected ACK detector.

## Idle video freeze evidence — 2026-07-28 08:52–08:54 +05
- **Build under test:** pre-fix installed build (PID 30342); not the reconnect-guard/stall-watchdog source fix.
- **Symptom:** after long network outage (scenario H), app process stayed alive but WS remained offline; background `IdleVideoOverlay` appeared frozen (static frame).
- **Recovery:** manual Wi-Fi/mobile off→on at 08:53 +05 restored WS (`TelemetryNetworkObserver`, ACK gen=8 at 08:53:40 +05) and triggered MediaCodec activity; playback resumed.
- **Screenshot proof:** two emulator screenshots taken 3 s apart after network toggle produced **different SHA-256** hashes, confirming video frames advanced (TEMP images discarded after hash compare).
- **Fix target:** low-frequency stall watchdog in current source (`IdleVideoStallWatchdog`) — seek/reprepare, then rotate asset; no 100 ms polling.

