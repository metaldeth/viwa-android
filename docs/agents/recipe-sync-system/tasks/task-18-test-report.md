# task-18-test-report: Android assignment base cache + service UI edit/reset/drift



**Session:** `recipe-sync-system`  

**Task:** task-18 — assignment base cache + service UI view/edit/reset/drift + outbox drain  

**Date:** 2026-08-06 (round 2 — review blocker fixes C-2/C-3/C-4)  

**Repo:** `viwa-android`



## Summary



Implemented separate Room `cell_assignment_base` snapshot store (v7→v8), extended `RecipeMessageCodec` sync.control assignment parsing, orchestrator persistence before inbox, lossless downlink channel (N8), Service > Обслуживание > Остатки managed recipe UI with drift badge/edit/reset, local edit outbox + drain trigger.



**Round 2 (review BLOCK fixes):** per-cell pending sync from outbox query; pending clears on WS/REST report ack + drain; `RecipeDownlinkOverflow` wired into telemetry lifecycle with debounced reconnect.



## Schema



| Item | Value |

|------|-------|

| Room version | **8** (unchanged in round 2) |

| Migration | `MIGRATION_7_8` — additive `cell_assignment_base` table |

| Export | `app/schemas/.../ViwaDatabase/8.json` |

| Chain test | `CellEffectiveRecipeRoomTest` 3→4→5→6→7→8 |



## Rollout gate (C-1 — intentional)



| Item | Value |

|------|------|

| `FEATURE_RECIPE_SYNC` | **`false`** (compile-time; not flipped) |

| Implementation review | Can pass with flag gated — managed path coded + unit-tested |

| Runtime E2E | **G-18-4 / task-23** — requires staging flag-on + emulator smoke |



## Round 2 blocker fixes



| Blocker | Fix | Verification |

|---------|-----|--------------|

| **C-2** Global pending on all cells | `buildInventoryRecipePanel` uses `recipeOutboxStore.hasUnsentReportForCell(row.uuid)` only; removed `_recipePendingSyncCellUuids` + global `hasUnsentRecipeEntries()` OR | `RecipeOutboxStorePendingTest` per-cell isolation |

| **C-3** Pending never cleared | `MachineOutboxDao.hasUnsentRecipeReportForCell` (LIKE `{uuid}|%`); WS ack + REST drain call `onRecipeReportOutboxDelivered`; UI observes `reportDeliveryEvents` via `_recipeSyncUiRevision` | `RecipeOutboxStorePendingTest` ack clears cell A only |

| **C-4** Overflow not wired | `TelemetryCellsSyncCoordinator.startRecipeDownlinkOverflowHandling` → debounced reset + `scheduleConnect("recipe-downlink-overflow")` from `SimpleTelemetryCoordinator` | `TelemetryCellsSyncCoordinatorOverflowTest` |



## Files touched (task-18 scope)



### Round 1 (core)



| Path | Action |

|------|--------|

| `domain/recipe/CellAssignmentBase.kt` | **Created** |

| `data/local/recipe/*` | Assignment base Room store |

| `data/local/db/ViwaDatabase.kt` | v8 + `MIGRATION_7_8` |

| `data/remote/telemetry/mvp/cells/RecipeMessageCodec.kt` | Assignment wire decode |

| `data/remote/telemetry/mvp/cells/RecipeSyncCoordinator.kt` | Lossless channel + overflow signal |

| `domain/recipe/RecipeSyncOrchestrator.kt` | Assignment persist before inbox |

| `domain/inventory/InventoryManagedRecipeSupport.kt` | Drift/panel/edit validation |

| `ui/screens/service/ServiceViewModel.kt` | Recipe edit/reset/outbox/drain |

| `ui/screens/service/tabs/ViwaInventoryVolumesTab.kt` | Managed recipe dialog UI |



### Round 2 (C-2/C-3/C-4)



| Path | Action |

|------|--------|

| `data/local/outbox/MachineOutboxDao.kt` | `hasUnsentRecipeReportForCell` |

| `data/local/outbox/MachineOutboxStore.kt` | Facade per-cell pending query |

| `data/local/outbox/RecipeOutboxStore.kt` | `hasUnsentReportForCell`, `reportDeliveryEvents`, `onRecipeReportOutboxDelivered` |

| `data/remote/telemetry/mvp/MvpTelemetryWebSocketManager.kt` | Report ack → delivery event |

| `data/remote/telemetry/mvp/MachineOutboxDrainCoordinator.kt` | REST batch ack → delivery event |

| `data/remote/telemetry/mvp/TelemetryCellsSyncCoordinator.kt` | Overflow → reset + debounced reconnect |

| `data/remote/telemetry/mvp/SimpleTelemetryCoordinator.kt` | Subscribe overflow handling on app scope |

| `ui/screens/service/ServiceViewModel.kt` | Per-cell pending + revision bump on ack |

| `ui/screens/service/tabs/ViwaInventoryVolumesTab.kt` | `LaunchedEffect` refresh on revision |

| `app/src/test/.../RecipeOutboxStorePendingTest.kt` | **Created** |

| `app/src/test/.../TelemetryCellsSyncCoordinatorOverflowTest.kt` | **Created** |



## Tests



| Suite | Count | Result |

|-------|-------|--------|

| `CellAssignmentBaseStoreTest` | 4 | PASS |

| `RecipeSyncCoordinatorLosslessTest` | 3 | PASS |

| `InventoryManagedRecipeSupportTest` | 5 | PASS |

| `ServiceViewModelRecipeTest` | 3 | PASS |

| `CellEffectiveRecipeRoomTest` (v7→8 chain) | 4 | PASS |

| `RecipeOutboxStorePendingTest` | 3 | PASS |

| `TelemetryCellsSyncCoordinatorOverflowTest` | 1 | PASS |

| **Targeted filter total** | **23** | **PASS** |



Command:



```powershell

gradlew.bat :app:testDebugUnitTest --tests "com.viwa.android.data.local.recipe.CellAssignmentBaseStoreTest" --tests "com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinatorLosslessTest" --tests "com.viwa.android.domain.inventory.InventoryManagedRecipeSupportTest" --tests "com.viwa.android.ui.screens.service.ServiceViewModelRecipeTest" --tests "com.viwa.android.data.local.recipe.CellEffectiveRecipeRoomTest" --tests "com.viwa.android.data.local.outbox.RecipeOutboxStorePendingTest" --tests "com.viwa.android.data.remote.telemetry.mvp.TelemetryCellsSyncCoordinatorOverflowTest" --max-workers=1

```



`assembleDebug --max-workers=1` — **PASS** (2026-08-06 round 2)



## G-18 gates



| Gate | Status | Notes |

|------|--------|-------|

| G-18-1 Service UI edit/reset + outbox + drain | ✅ | Coded + unit-tested; runtime blocked by flag until staging |

| G-18-2 review-compose-layout inventory tab | 🟡 | Bounded dialog; **emulator smoke deferred task-23** |

| G-18-4 staging E2E FEATURE_RECIPE_SYNC=true | ⏳ | Flag still `false`; task-23 |

| G-18-5 device smoke | ⏳ | task-23 |



## Review verdict (post round 2)



| Blocker | Status |

|---------|--------|

| C-1 `FEATURE_RECIPE_SYNC=false` | **Intentional rollout gate** — not flipped; G-18-4 remains open |

| C-2 global pending | **Fixed** |

| C-3 sticky pending | **Fixed** |

| C-4 overflow reconnect | **Fixed** |



**Implementation review:** can proceed to **PASS (gated)** — code blockers C-2/C-3/C-4 resolved; acceptance sign-off still requires G-18-4 staging E2E + G-18-2/G-18-5.



## Verification not run



- Emulator/UI Automator smoke (task-23)

- Full recipe suite (137+) — targeted filter only

- `review-compose-layout` subagent — pre-merge / task-23



## Risks / open (non-blocking)



- Pre-fence overflow still drops triggering frame (signal + reconnect recovery — N-1)

- No single transaction across effective persist + outbox enqueue (N-2)

- Offline edit blocked when managed gate inactive (N-3 — confirm product intent)

- `OFFLINE_STALE` drift path untested (N-4)

- Pour-preview AC deferred **task-19**


