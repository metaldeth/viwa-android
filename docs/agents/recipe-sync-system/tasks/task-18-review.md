# task-18-review: Android assignment base cache + service UI edit/reset/drift

**Session:** `recipe-sync-system`  
**Task:** [task-18.md](../../../../wiva-telemetry/docs/agents/recipe-sync-system/tasks/task-18.md) — assignment base cache + service UI view/edit/reset/drift + lossless downlink  
**Test report:** [task-18-test-report.md](./task-18-test-report.md)  
**Review:** round 1 + **round 2 (final)** — 2026-08-06  
**Scope:** task-18 touch map + round-2 C-2/C-3/C-4 fixes; **no code edits in review**

---

## Files reviewed

### Round 1 (core)

| Path | Role |
|------|------|
| `domain/recipe/CellAssignmentBase.kt` | Domain + drift enums |
| `data/local/recipe/CellAssignmentBaseStore.kt` | Partial merge, validate, fingerprint |
| `data/local/db/ViwaDatabase.kt` | v8 + `MIGRATION_7_8` |
| `data/remote/telemetry/mvp/cells/RecipeMessageCodec.kt` | Assignment wire decode |
| `data/remote/telemetry/mvp/cells/RecipeSyncCoordinator.kt` | Lossless channel + overflow signal |
| `domain/recipe/RecipeSyncOrchestrator.kt` | Assignment persist before inbox |
| `domain/inventory/InventoryManagedRecipeSupport.kt` | Drift/panel/edit validation |
| `ui/screens/service/ServiceViewModel.kt` | Edit/reset/outbox/drain/panel |
| `ui/screens/service/tabs/ViwaInventoryVolumesTab.kt` | Managed recipe dialog UI |
| `data/local/outbox/RecipeOutboxStore.kt` | Local-edit report enqueue |

### Round 2 (C-2/C-3/C-4 fixes)

| Path | Role |
|------|------|
| `data/local/outbox/MachineOutboxDao.kt` | `hasUnsentRecipeReportForCell` (`LIKE '{uuid}|%'`, PENDING+IN_FLIGHT) |
| `data/local/outbox/MachineOutboxStore.kt` | Per-cell pending facade |
| `data/local/outbox/MachineOutboxPersistence.kt` | Room + interface delegation |
| `data/local/outbox/RecipeOutboxStore.kt` | `hasUnsentReportForCell`, `reportDeliveryEvents`, `onRecipeReportOutboxDelivered`, `cellUuidFromReportIdempotencyKey` |
| `data/remote/telemetry/mvp/MvpTelemetryWebSocketManager.kt` | WS report ack → delivery event |
| `data/remote/telemetry/mvp/MachineOutboxDrainCoordinator.kt` | REST batch ack → delivery event |
| `data/remote/telemetry/mvp/TelemetryCellsSyncCoordinator.kt` | `startRecipeDownlinkOverflowHandling`, debounced reconnect |
| `data/remote/telemetry/mvp/SimpleTelemetryCoordinator.kt` | Single overflow subscription in `init` |
| `ui/screens/service/ServiceViewModel.kt` | Per-cell pending query; `_recipeSyncUiRevision`; `observeRecipeReportDelivery()` |
| `ui/screens/service/tabs/ViwaInventoryVolumesTab.kt` | `LaunchedEffect(row.uuid, recipeSyncRevision)` panel refresh |
| `app/src/test/.../RecipeOutboxStorePendingTest.kt` | **Created** — per-cell pending + ack isolation |
| `app/src/test/.../TelemetryCellsSyncCoordinatorOverflowTest.kt` | **Created** — debounce + reset |

**Context read:** round-1 review, [task-18-test-report.md](./task-18-test-report.md) (round 2)

---

## Round 2 stress focus — C-2 / C-3 / C-4

| Scenario | Verdict | Notes |
|----------|---------|-------|
| **C-2 per-cell pending query** | ✅ | `buildInventoryRecipePanel` uses `hasUnsentReportForCell(row.uuid)` only; `_recipePendingSyncCellUuids` removed; global `hasUnsentRecipeEntries()` retained for sync fence only |
| **C-2 query correctness** | ✅ | Idempotency key `{cellId}\|{revision}`; DAO `LIKE :cellId \|\| '\|%'`; fake uses `startsWith("$cellId\|")` — pipe delimiter avoids `cell-1` vs `cell-10` prefix collision |
| **C-2 collision safety** | ✅ / 🟡 | Format `{uuid}\|{rev}` + mandatory `\|` after uuid — safe for standard cell UUIDs; **no explicit collision regression test** |
| **C-3 pending clears on WS ack** | ✅ | `MvpTelemetryWebSocketManager`: `markAcked` → `onRecipeReportOutboxDelivered` |
| **C-3 pending clears on REST ack** | ✅ | `MachineOutboxDrainCoordinator` batch accepted → same hook |
| **C-3 lifecycle-safe UI refresh** | ✅ | `reportDeliveryEvents` → `observeRecipeReportDelivery()` bumps `_recipeSyncUiRevision`; dialog `LaunchedEffect(row.uuid, recipeSyncRevision)` rebuilds panel; edit/reset also bump revision on enqueue |
| **C-3 source of truth** | ✅ | Pending derived from outbox row state (not sticky in-memory set) |
| **C-4 overflow wiring** | ✅ | `SimpleTelemetryCoordinator.init` → `startRecipeDownlinkOverflowHandling(appScope) { scheduleConnect(reason) }` |
| **C-4 single collector** | ✅ | `if (overflowHandlingJob?.isActive == true) return` prevents duplicate collectors |
| **C-4 debounce / no reconnect loop** | ✅ | `OVERFLOW_RECONNECT_DEBOUNCE_MS = 5_000`; mutex + `lastOverflowReconnectAtMs`; second overflow within window skipped — tested via `handleRecipeDownlinkOverflow` |
| **C-4 reset before reconnect** | ✅ | Handler calls `recipeSyncOrchestrator.onDisconnect()` + `recipeSyncCoordinator.resetOnDisconnect()` before `requestReconnect` |

---

## Round 1 blockers — resolution

| ID | Round 1 | Round 2 |
|----|---------|---------|
| **C-1** | Flag off — managed path dead | **Intentional rollout gate** — not an implementation defect; G-18-4 / task-23 |
| **C-2** | Global pending on all cells | **Closed** |
| **C-3** | Sticky in-memory pending | **Closed** |
| **C-4** | Overflow unwired | **Closed** |

---

## G-18 gates (final)

| Gate ID | Requirement | Status |
|---------|-------------|--------|
| **G-18-1** | Service UI edit/reset + outbox + drain | ✅ **Closed (code + unit tests)**; runtime E2E waits on flag |
| **G-18-2** | `review-compose-layout` inventory tab | ⏳ **Open** — task-23 / pre-merge |
| **G-18-4** | Staging E2E `FEATURE_RECIPE_SYNC=true` | ⏳ **Open** |
| **G-18-5** | Device smoke after flag flip | ⏳ **Open** (task-23) |

---

## Findings synthesis (round 2 final)

### 🔴 Critical — implementation blockers

**None.** Round-1 implementation blockers C-2/C-3/C-4 are closed in code with focused tests.

### 🟡 Non-blocking (carried + round 2)

| ID | Finding | Owner |
|----|---------|-------|
| N-1 | Pre-fence overflow drops triggering frame (signal + reconnect recovery) | task-19 |
| N-2 | No single transaction effective persist + outbox enqueue | task-21 |
| N-3 | Offline edit blocked when managed gate inactive | confirm product intent |
| N-4 | `OFFLINE_STALE` drift path untested | add unit test |
| N-5 | `cell_assignment_base` not asserted in v3→8 chain test | optional |
| N-6 | `ServiceViewModelRecipeTest` still store-level, not ViewModel auth/gate matrix | optional |
| N-8 | Pour-preview AC | **task-19** |
| **N-9** | Prefix collision (`cell-1` vs `cell-10`) not explicitly regression-tested | optional hardening |
| **N-10** | Overflow test covers `handleRecipeDownlinkOverflow` only, not `startRecipeDownlinkOverflowHandling` collector wiring | optional integration test |
| **N-11** | `reportDeliveryEvents.tryEmit` — under extreme backpressure UI revision may lag until next panel open (outbox query still correct) | low risk |

### ✅ Strengths (cumulative)

- Per-cell pending aligned with outbox idempotency key contract
- WS + REST symmetric delivery notification path
- UI refresh decoupled from sticky session state via revision counter
- Overflow handling: single subscriber, debounced reconnect, orchestrator reset
- Round-1 strengths (assignment cache, orchestrator order, drift, v8 migration) unchanged

---

## Test verification

| Round | Scope | Result |
|-------|-------|--------|
| 1 | Targeted filter (19 tests) + assembleDebug | **PASS** (test report) |
| **2 (final)** | + `RecipeOutboxStorePendingTest` (3) + `TelemetryCellsSyncCoordinatorOverflowTest` (1) → **23 PASS** + assembleDebug | **PASS** (test report; not re-run in review) |

Focused tests **meaningfully cover** round-2 fixes: per-cell isolation, ack clears one cell, idempotency key parse, overflow debounce + managed-mode reset. Gaps: prefix collision (N-9), collector subscription idempotency (N-10), ViewModel delivery observer (N-11).

---

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

| Layer | Verdict |
|-------|---------|
| **Implementation review** | **✅ PASS (gated rollout)** — task-18 code complete; C-2/C-3/C-4 fixed and tested; no remaining implementation blockers |
| **Acceptance / release sign-off** | **⛔ BLOCK until staging gates** — `FEATURE_RECIPE_SYNC=true` on staging (G-18-4), compose smoke (G-18-2), device smoke (G-18-5 / task-23) |

C-1 (`FEATURE_RECIPE_SYNC=false`) is an **intentional rollout gate**, not a code defect.

---

## JSON (review output — final)

```json
{
  "reviewReportFile": "docs/agents/recipe-sync-system/tasks/task-18-review.md",
  "reviewRound": 2,
  "hasCriticalIssues": false,
  "task18ImplementationVerdict": "PASS_GATED",
  "task18AcceptanceVerdict": "BLOCK_UNTIL_FLAG_AND_E2E",
  "blockersResolved": ["C-2", "C-3", "C-4"],
  "blockersIntentional": ["C-1"],
  "g18GatesClosed": ["G-18-1"],
  "g18GatesOpen": ["G-18-2", "G-18-4", "G-18-5"],
  "testsRun": 0,
  "testsRunFromReport": 23,
  "buildPassFromReport": true,
  "featureRecipeSync": false,
  "commentsSummary": "Round 2 PASS implementation: per-cell hasUnsentReportForCell via uuid|rev idempotency key; WS+REST onRecipeReportOutboxDelivered; recipeSyncUiRevision lifecycle refresh; overflow single collector + 5s debounce + reset before scheduleConnect. No impl blockers. Acceptance blocked on flag flip + G-18-2/4/5. Minor gaps: prefix collision test, overflow collector integration test, tryEmit backpressure."
}
```
