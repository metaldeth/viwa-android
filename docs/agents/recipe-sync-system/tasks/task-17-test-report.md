# task-17-test-report: Android recipe report/ack outbox + resilience polish

**Session:** `recipe-sync-system`  
**Task:** task-17 — Android outbox recipe report/ack (+ resilience N-1/N-2/N-4)  
**Date:** 2026-08-06  
**Repo:** `viwa-android`

## Summary

Durable local `cells.recipe.report` / `cells.recipe.command.ack` outbox with full terminal-ack recovery after process death. Room v7 persists command generation, failure code, and delivery flag for all terminal outcomes; recovery no longer depends on server redelivery.

## Changed files (resilience polish)

| Path | Change |
|------|--------|
| `data/local/recipe/CellEffectiveRecipeEntity.kt` | v7 columns: `lastTerminalCommandGeneration`, `lastTerminalAckFailureCode`, `terminalAckDelivered` |
| `domain/recipe/CellEffectiveRecipe.kt` | Mirror terminal recovery fields |
| `data/local/db/ViwaDatabase.kt` | `MIGRATION_6_7`, version 7 |
| `di/DatabaseModule.kt` | Register `MIGRATION_6_7` |
| `app/schemas/.../7.json` | **Exported** Room schema |
| `data/local/recipe/CellEffectiveRecipeDao.kt` | `findUndeliveredTerminalAcks`, `markTerminalAckDelivered` |
| `data/local/recipe/CellEffectiveRecipeStore.kt` | Atomic terminal persist (all statuses + generation + failureCode); redelivery via `RecipeTerminalAckRecovery` |
| `domain/recipe/RecipeTerminalAckRecovery.kt` | **Created** — reconstruct ack from Room columns |
| `data/local/outbox/RecipeOutboxStore.kt` | All-status recovery; `onCommandAckOutboxDelivered`; EXISTS-based `hasUnsentRecipeEntries` |
| `data/local/outbox/MachineOutboxDao.kt` | `hasUnsentRecipeEntries` EXISTS query |
| `data/remote/telemetry/mvp/MvpTelemetryWebSocketManager.kt` | Mark terminal delivered on WS ack |
| `data/remote/telemetry/mvp/MachineOutboxDrainCoordinator.kt` | Mark terminal delivered on REST batch ack |
| `app/src/test/.../RecipeTerminalAckRecoveryTest.kt` | **Created** — process-death matrix (9 tests) |
| `app/src/test/.../CellEffectiveRecipeRoomTest.kt` | v6→v7 chain + backfill assertion |

## Reviewer items closed

| Item | Status |
|------|--------|
| N-1 Non-applied terminal startup recovery | ✅ All terminal statuses recovered from Room |
| N-2 `lastTerminalCommandGeneration` + ack reconstruction | ✅ v7 schema + `RecipeTerminalAckRecovery` |
| N-4 `hasUnsentRecipeEntries` scan | ✅ EXISTS DAO query |

## Verification

| Command | Result |
|---------|--------|
| Targeted unit tests (119) `--max-workers=1` | **PASS** |
| `assembleDebug --max-workers=1` | **PASS** |

### Test scope

- `RecipeTerminalAckRecoveryTest` — cancelled, superseded, skipped_diverged, failed, applied, unassign, redelivery, duplicate recovery, mark delivered
- `CellEffectiveRecipeRoomTest` — migration chain through v7 + applied backfill
- Prior task-17 suite unchanged and passing

## Decisions

- **`terminalAckDelivered` flag** (not outbox-only): recovery scans Room; outbox idempotency coalesces duplicates; delivery mark is idempotent on `(cellId, commandId, status, generation)`.
- **UNASSIGN APPLIED:** persisted effective cleared; ack `appliedRecipe=null` via `RecipeTerminalAckRecovery`.
- **Stale generation:** wire status `superseded` (existing contract); no new `stale_generation` enum.
- **Feature flag:** `FEATURE_RECIPE_SYNC` remains `false`; persistence/recovery unconditional.

## Open questions

1. Coalesce multi-cell reports into single outbox row (≤64) while keeping per-cell idempotency for HTTP?
2. E2E device smoke with `FEATURE_RECIPE_SYNC=true` after staging flag flip?

## JSON

```json
{
  "task17Verdict": "PASS",
  "resiliencePolish": "COMPLETE",
  "g17GatesClosed": ["G-17-1", "G-17-2", "G-17-3", "G-17-4"],
  "reviewerItemsClosed": ["N-1", "N-2", "N-4"],
  "schemaVersion": 7,
  "identityHash7": "793749963da098722e60d948bdd96be2",
  "testsRun": 119,
  "buildPass": true,
  "commitPush": false,
  "featureRecipeSync": false
}
```
