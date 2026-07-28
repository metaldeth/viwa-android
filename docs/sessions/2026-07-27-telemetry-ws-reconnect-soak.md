# 2026-07-27 — Telemetry WebSocket reconnect soak

## Environment
- Device: **emulator-5554**, package `com.viwa.android`, serial **VIWA-000001**
- WS URL: `wss://tl.asnefedov.ru/api/v1/machines/ws`
- ADB: `F:\AndroidSDK\platform-tools\adb.exe`
- Server: `wiva-server`, API `viwa-telemetry-api` (verified **active** at end)
- Emulator date at prep: Mon Jul 27 2026 (GMT)
- **Note:** Two soak script instances overlapped (~16:54–17:23 local), interleaving phases in `TEMP_ws_soak_logcat.txt`. Results below merge both runs; final JSON reflects the second run only.

## Executive summary

| Phase | Result | Key metrics |
|-------|--------|-------------|
| **A** Baseline 12 min | **PASS** (run 1) / **FAIL** (run 2) | Spontaneous reconnects: **0**; ack samples: **36** (run 1) / **0** (run 2, logcat cleared + detector issues) |
| **B** Graceful API restart | **FAIL** | Time to ONLINE: **90s timeout** (both runs) |
| **C** Nginx reload | **FAIL** (harness) | No reconnect logged in 2 min window; ONLINE heuristic did not pass |
| **D** Silent disconnect | **FAIL** | Wrong client IP **127.0.0.1** used for iptables; detect **~23s**; recover **90s timeout** |
| **E** Post-chaos 5 min | **FAIL** | ack poll aggregate **0**; no reconnect loop |

**Overall:** Product behavior **not reliably validated** — harness bugs (timezone in ONLINE check, `ss` IP selection, overlapping runs) dominate. Server cleanup OK: **no stray iptables DROP**, API **active**.

## Phase A — Baseline ping/pong (12 min)

- **PASS/FAIL:** PASS (first completed run) — `rc=0 acks=36 PASS=True` at 16:55:01
- Spontaneous reconnects: **0** (both runs)
- Transport PING/PONG in UI log: **0** sampled (acks logged every ~10s as `MvpTelemetry WS: ack`)
- Pre-test evidence (manual): acks at 16:14–16:15 UTC device time before soak

```
07-27 16:14:58.048 ... MvpTelemetry WS: ack correlationId=312cff92-...
07-27 16:15:08.052 ... MvpTelemetry WS: ack correlationId=b424a226-...
```

Second run counted **0 acks** after `logcat -c` while ONLINE waiter failed prep (see bugs).

## Phase B — Graceful server restart

- **PASS/FAIL:** **FAIL**
- Command: `systemctl stop viwa-telemetry-api; sleep 8; systemctl start` → `active`
- Recover ONLINE: **>90s** (timed out in harness both times)
- **Product note:** Manual criterion unmet within 90s window; may still reconnect later — not captured due to faulty ONLINE detector.

## Phase C — Nginx reload

- **PASS/FAIL:** **FAIL** (harness could not confirm ONLINE)
- `nginx -t` OK, `systemctl reload nginx` OK
- Log notes: **no reconnect** strings in 2 min window (survive or silent recovery not distinguished)

## Phase D — Silent / abrupt disconnect

- **PASS/FAIL:** **FAIL**
- **Method used:** `iptables -A INPUT -s 127.0.0.1 -p tcp -j DROP` — **incorrect** (nginx↔node loopback, not emulator)
- **Correct client IP on server** (from `ss`): **`212.104.84.6`** → `192.168.0.247:443`
- Detect disconnect (harness): **~22.6–23.4 s** (likely side effect of breaking localhost API path, not true silent client drop)
- Recover ONLINE: **90s timeout**
- **iptables DROP removed** in cleanup (`iptables -D INPUT -s 127.0.0.1 ...`)

## Phase E — Post-chaos soak (5 min)

- **PASS/FAIL:** **FAIL**
- ack samples (poll sum): **0**
- Reconnect loop: **no**

## Bugs / gaps found

1. **Harness: `Wait-ForOnline` timezone** — compares host local `Get-Date` to logcat timestamps (device GMT) without offset → fresh acks appear “stale”; causes false OFFLINE for phases B–E.
2. **Harness: Phase D IP parsing** — first match in `ss` was **127.0.0.1:3000**, not **`212.104.84.6`** client on `:443`.
3. **Harness: overlapping script runs** — two instances wrote the same log file; phases interleaved; invalidates single-run timing.
4. **Ack counter after `logcat -c`** — incremental poll counts only lines still in ring buffer; second run reported 0 despite possible live WS (detector not cross-checking server `ss`).
5. **MainActivity path** — launcher is `com.viwa.android/.ui.MainActivity`, not `.MainActivity`.

## Server / cleanup verification

- `viwa-telemetry-api`: **active**
- INPUT chain: no test **DROP** rule left for 127.0.0.1 (removed in finally)
- Example `ss` at end: `212.104.84.6` and other clients on **:443**

## Evidence (soak log excerpts)

```
2026-07-27 16:55:01 === PHASE A END rc=0 acks=36 PASS=True ===
2026-07-27 16:55:12 B: active
2026-07-27 16:56:44 === PHASE B END recover=90s PASS=False ===
2026-07-27 16:58:57 ss: ... 192.168.0.247:443   212.104.84.6:2083 ...
2026-07-27 16:59:20 Detect: True 22.6s
2026-07-27 16:59:21 Cleanup iptables DROP for 127.0.0.1
2026-07-27 17:09:34 === PHASE B END recover=90s PASS=False ===
2026-07-27 17:12:07 Cleanup iptables DROP for 127.0.0.1
2026-07-27 17:22:56 FINALLY: active
```

## Verification

- Duration: ~29 min wall clock (16:54–17:23) including duplicate overlap
- No git commit --trailer "Co-authored-by: Cursor <cursoragent@cursor.com>"
- TEMP artifacts removed after report (session md retained)
