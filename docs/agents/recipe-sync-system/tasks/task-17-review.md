# task-17-review: Android outbox recipe report/ack

**Session:** `recipe-sync-system`  
**Task:** [task-17.md](../../../../viwa-telemetry/docs/agents/recipe-sync-system/tasks/task-17.md) — Android outbox recipe report/ack (+ resilience polish)  
**Test report:** [task-17-test-report.md](./task-17-test-report.md)  
**Review:** round 1 + **round 2 (final)** — 2026-08-06  
**Scope:** task-17 touch map + resilience polish + test report; architecture §11.4/§11.5; contract `recipe-sync.md` + `machine-outbox.md`; task-10/16 reviews; **no code edits in review**

## Files reviewed

### Round 1 (outbox core)

| Path | Role |
|------|------|
| `data/local/outbox/MachineOutboxKind.kt` | Recipe kinds + drain priority |
| `data/local/outbox/MachineOutboxDao.kt` | Report-before-ack ORDER BY |
| `data/local/outbox/RecipeOutboxStore.kt` | Enqueue, idempotency, recovery |
| `domain/recipe/RecipeCommandAckEmitter.kt` | Durable enqueue before drain |
| `data/remote/telemetry/mvp/TelemetryCellsSyncCoordinator.kt` | Reconnect outbox-first + sync.request |
| `data/remote/telemetry/mvp/MachineOutboxDrainCoordinator.kt` | WS/REST drain |
| `data/remote/telemetry/mvp/MvpTelemetryWebSocketManager.kt` | `onRecipeAck` completion |

### Round 2 (resilience polish — v7 + full recovery)

| Path | Role |
|------|------|
| `data/local/recipe/CellEffectiveRecipeEntity.kt` | v7: `lastTerminalCommandGeneration`, `lastTerminalAckFailureCode`, `terminalAckDelivered` |
| `domain/recipe/CellEffectiveRecipe.kt` | Domain mirror |
| `domain/recipe/RecipeTerminalAckRecovery.kt` | **Created** — exact ack reconstruction from Room |
| `data/local/db/ViwaDatabase.kt` | `MIGRATION_6_7`, version **7** |
| `di/DatabaseModule.kt` | Register `MIGRATION_6_7` |
| `app/schemas/.../7.json` | Exported schema (`identityHash` `793749963da098722e60d948bdd96be2`) |
| `data/local/recipe/CellEffectiveRecipeDao.kt` | `findUndeliveredTerminalAcks`, `markTerminalAckDelivered` |
| `data/local/recipe/CellEffectiveRecipeStore.kt` | Atomic terminal persist all outcomes; redelivery via `RecipeTerminalAckRecovery` |
| `data/local/outbox/RecipeOutboxStore.kt` | All-status recovery; `onCommandAckOutboxDelivered`; EXISTS delegation |
| `data/local/outbox/MachineOutboxDao.kt` | `hasUnsentRecipeEntries` EXISTS query |
| `data/remote/telemetry/mvp/MvpTelemetryWebSocketManager.kt` | Mark terminal delivered on WS ack |
| `data/remote/telemetry/mvp/MachineOutboxDrainCoordinator.kt` | Mark terminal delivered on REST batch ack/idempotent |
| `app/src/test/.../RecipeTerminalAckRecoveryTest.kt` | **Created** — 9-test process-death matrix |
| `app/src/test/.../CellEffectiveRecipeRoomTest.kt` | v3→7 chain + applied backfill assertion |

**Context read:** [architecture.md](../../../../viwa-telemetry/docs/agents/recipe-sync-system/architecture.md), [task-10-review.md](../../../../viwa-telemetry/docs/agents/recipe-sync-system/tasks/task-10-review.md), [task-16-review.md](../../../../viwa-telemetry/docs/agents/recipe-sync-system/tasks/task-16-review.md), `AGENTS.md`

---

## Review agents

| Agent | Round 1 | Round 2 (final) |
|-------|---------|-----------------|
| review-general (Android) | ✅ G-17 closed; 🟡 non-applied recovery gap | ✅ **All terminal statuses recovered locally**; delivery mark WS+REST |
| review-kotlin-optimization | ✅ Lazy drain; 🟡 full-table scan | ✅ EXISTS optimization; redelivery uses shared recovery object |
| review-performance (Room/DAO) | 🟡 per-cell rows; Int.MAX_VALUE scan | ✅ EXISTS query; 🟡 `markCommandAckDelivered` linear scan undelivered list |
| review-docs | 🟡 test count drift | ✅ v7 export documented; 🟡 report says 119 vs reviewer **137** |
| review-final | PASS (partial G-17-4) | **✅ PASS final** — round-1 N-1/N-2/N-4 closed |

---

## Stress focus — round 2 (final)

| Scenario | Verdict | Notes |
|----------|---------|-------|
| **Room v6→v7 migration + export** | ✅ | `MIGRATION_6_7` additive columns; `7.json` exported; chain 3→4→5→6→7 in `CellEffectiveRecipeRoomTest`; Room opens post-migrate |
| **Terminal generation / failure / delivered columns** | ✅ | `lastTerminalCommandGeneration`, `lastTerminalAckFailureCode`, `terminalAckDelivered` on entity + domain |
| **Atomic persistence every outcome** | ✅ | Single `inTransaction` in `applyManagedCommand`; `persistTerminalOutcome`/`persistRecipeApply` set generation+failure+delivered=false atomically |
| **Exact ack reconstruction** | ✅ | `RecipeTerminalAckRecovery.toAckEntry`: APPLIED includes triple when complete; UNASSIGN APPLIED → `appliedRecipe=null`; FAILED includes `failureCode` |
| **All-status startup recovery (no server redelivery)** | ✅ | `findUndeliveredTerminalAcks` + `recoverPendingTerminalAcks`; 9 tests: cancelled, superseded, skipped_diverged, failed, applied, unassign, redelivery, duplicate coalesce, mark delivered |
| **Redelivery dedup after recovery** | ✅ | `applyManagedCommand` Redelivered path uses `RecipeTerminalAckRecovery.toAckEntry(current)` — exact persisted ack |
| **Delivery mark race / idempotency WS+REST** | ✅ | Both paths: `markAcked` → `onCommandAckOutboxDelivered` → purge; `markTerminalAckDelivered` keyed on `(cellId, commandId, status, generation)`; idempotent UPDATE returns 0 on repeat |
| **Crash: Room persisted, outbox empty, server down** | ✅ | Recovery re-enqueues from Room without server frame (`RecipeTerminalAckRecoveryTest` redelivery test) |
| **Stable idempotency keys** | ✅ | Unchanged: report `cellId\|revision`; ack `commandId\|status\|generation`; HTTP `messageId` |
| **EXISTS optimization** | ✅ | `MachineOutboxDao.hasUnsentRecipeEntries` — 🟡 PENDING-only (excludes IN_FLIGHT; aligns with fence-on-send, not fence-on-ack) |
| **Migration data preservation** | 🟡 | v6→v7 backfill sets `last_terminal_command_generation` **only for `applied`** rows; pre-existing v6 non-applied terminal rows keep generation=0 → not recoverable (acceptable pre-prod; `FEATURE_RECIPE_SYNC=false`) |
| **7.json identityHash explicit assert** | 🟡 | Column backfill asserted; no literal `793749963…` compare (same pattern as v6) |
| **Report-before-ack / sync.request / water regression** | ✅ | Unchanged from round 1 — still valid |
| **Multi-cell framing** | 🟡 | Still per-cell outbox rows — deferred |
| **`FEATURE_RECIPE_SYNC` false** | 🟡 | Persistence/recovery unconditional; E2E deferred task-18 |
| **Tests + build** | ✅ | Reviewer-run **137 PASS** (report claimed 119); `assembleDebug` **PASS** |

---

## G-17 gates — final verification

| Gate ID | Requirement | Status |
|---------|-------------|--------|
| **G-17-1** | Outbox kinds + report-before-ack ordering | ✅ **Closed** |
| **G-17-2** | Ack emitter → outbox + real `onRecipeAck` | ✅ **Closed** |
| **G-17-3** | `sync.request` WS-only | ✅ **Closed** |
| **G-17-4** | E2E ack delivery after apply; crash-before-outbox; **all terminal statuses** | ✅ **Closed** (round 2) |

---

## Findings synthesis (final)

### 🔴 Critical (task-17 blockers)

**None.**

### Round-1 items closed in round 2

| ID | Finding | Status |
|----|---------|--------|
| **N-1** | Non-applied terminal startup recovery | ✅ **Closed** — v7 + `RecipeTerminalAckRecovery` |
| **N-2** | No Android crash-before-outbox test (non-applied) | ✅ **Closed** — `RecipeTerminalAckRecoveryTest` matrix |
| **N-4** | `hasUnsentRecipeEntries` full scan | ✅ **Closed** — EXISTS DAO query |

### 🟡 Non-blocking (carried)

| ID | Finding | Owner |
|----|---------|-------|
| N-3 | Multi-cell report batching — per-cell rows | task-18/21 optional |
| N-5 | `enqueueReportAfterLocalEdit` no drain until Service UI | task-18 |
| N-7 | `FEATURE_RECIPE_SYNC=false` + no device E2E | task-18/19 |
| N-8 | task-16 DROP_OLDEST / gate-collector race | task-19 |
| N-9 | v6→v7 backfill skips non-applied pre-migration terminal rows | acceptable pre-prod; document if staging had v6 traffic |
| N-10 | `7.json` identityHash not literal-asserted | optional hardening |
| N-11 | `markCommandAckDelivered` scans undelivered list O(n) | task-21 perf |
| N-12 | Test report count 119 vs reviewer **137** in same Gradle filter | docs nit |

### ✅ Strengths (cumulative)

- Full offline terminal-ack recovery independent of server command redelivery
- Delivery flag prevents duplicate recovery after WS/REST success while preserving dedup columns for inbox redelivery
- Redelivery path reconstructs exact ack from Room (not rebuilt from wire command)
- v7 migration additive with applied-row backfill; production table chain preserved
- Round-1 outbox/idempotency/ordering/REST/404 semantics intact

---

## Test verification

| Round | Command | Result |
|-------|---------|--------|
| 1 | Scoped Gradle filter (see round-1 log) | **128 PASS** |
| **2 (final)** | Same filter + `RecipeTerminalAckRecoveryTest` | **137 PASS**, 0 failures |
| **2 (final)** | `assembleDebug --max-workers=1` | **PASS** |

Test breakdown (137): prior suite (128) + `RecipeTerminalAckRecoveryTest` (9).

---

## task-18 gates (post task-17 final)

| Gate ID | Requirement |
|---------|-------------|
| **G-18-1** | Service UI edit/reset + `enqueueReportAfterLocalEdit` + drain trigger |
| **G-18-2** | `review-compose-layout` inventory tab |
| **G-18-4** | Staging E2E `FEATURE_RECIPE_SYNC=true` — outbox flush + ack on wire |
| **G-18-5** | Device smoke after flag flip |

~~G-18-3~~ (optional Room column) — **removed**; closed in round 2.

## task-19 gates (unchanged)

| Gate ID | Requirement |
|---------|-------------|
| **G-19-1** | `PreparingManager` effective recipe Phase C |
| **G-19-2** | Pour telemetry snapshot ml from effective |
| **G-19-3** | Service assign recipe uplink path |
| **G-19-4** | Async managed gate/collector race with pour |
| **G-19-5** | Report-only rollout before pour switch |

---

## Final verdict

**✅ PASS — task-17 complete (round 2 final).** Outbox report/ack plus Room v7 resilience polish closes all G-17 gates including full terminal-ack startup recovery without server redelivery. Round-1 blockers N-1/N-2/N-4 are closed. **Prod enable still blocked on task-18 UI wiring + staging E2E (G-18-4) and task-19 pour Phase C (G-19-1).**

---

## JSON (code-reviewer-complex output — final)

```json
{
  "reviewReportFile": "docs/agents/recipe-sync-system/tasks/task-17-review.md",
  "reviewRound": 2,
  "hasCriticalIssues": false,
  "task17Verdict": "PASS",
  "blockers": [],
  "resiliencePolish": "COMPLETE",
  "schemaVersion": 7,
  "identityHash7": "793749963da098722e60d948bdd96be2",
  "g17GatesClosed": [
    "G-17-1 outbox recipe report and command ack kinds flush order report before ack",
    "G-17-2 RecipeCommandAckEmitter to outbox plus onRecipeAck completion not log-only",
    "G-17-3 cells.recipe.sync.request WS-only not HTTP outbox",
    "G-17-4 all terminal statuses crash-after-persist recovery independent of server redelivery"
  ],
  "g17GatesPartial": [],
  "round1ItemsClosed": ["N-1", "N-2", "N-4"],
  "task18Gates": [
    "G-18-1 service UI edit reset uplink and drain trigger",
    "G-18-2 review-compose-layout inventory tab",
    "G-18-4 staging E2E FEATURE_RECIPE_SYNC true outbox ack on wire",
    "G-18-5 device smoke after flag flip"
  ],
  "task19Gates": [
    "G-19-1 PreparingManager effective recipe Phase C",
    "G-19-2 pour telemetry snapshot ml from effective",
    "G-19-3 service assign recipe uplink path",
    "G-19-4 async managed gate collector race with pour",
    "G-19-5 report-only rollout before pour switch"
  ],
  "testsRun": 137,
  "testsRunClaimedInReport": 119,
  "buildPass": true,
  "featureRecipeSync": false,
  "commentsSummary": "Round 2 PASS: Room v7 migration+7.json export; terminal generation/failure/delivered columns; RecipeTerminalAckRecovery exact ack for all statuses including unassign appliedRecipe=null; recoverPendingTerminalAcks from findUndeliveredTerminalAcks without server redelivery; onCommandAckOutboxDelivered on WS+REST ack/idempotent; EXISTS hasUnsentRecipeEntries; 9 process-death tests. No blockers. Carried: v6 non-applied backfill gap pre-prod; multi-cell batching; local-edit drain task-18; FEATURE_RECIPE_SYNC false E2E; test count 137 vs report 119."
}
```
