# task-19-test-report — Android preparing/pour Phase C + assign A4

**Date:** 2026-08-06 (round 2: R-1/R-2 polish)  
**Repo:** `viwa-android`  
**Task:** [task-19.md](../../../../wiva-telemetry/docs/agents/recipe-sync-system/tasks/task-19.md)  
**Review:** [task-19-review.md](./task-19-review.md)

## Delivered (round 1 + round 2)

| Area | Change |
|------|--------|
| Pour gate | `RecipeSyncFeatureFlags.FEATURE_RECIPE_POUR_FROM_EFFECTIVE = false` (explicit Phase C gate; `FEATURE_RECIPE_SYNC` unchanged) |
| Resolution | `InventoryCellRecipeSupport.resolvePourSetup` — fail-safe, never throws on invalid/incomplete/scale failure (**R-1**) |
| Fallback reasons | `PourRecipeFallbackReason` wire values: `missing_effective`, `incomplete_effective`, `invalid_effective`, `scale_failed` (**R-2**) |
| Preparing | `PreparingManager` structured `pourRecipeFallback` log + context fields; paid pour continues on legacy fallback |
| Telemetry | Base effective ml in `CurrentPreparingContext` → `DispenseTelemetryFactory` optional recipe fields |
| Assign A4 | Documented + tested: `cells.content.report` + `operatorOverride` only; no recipe embed |
| Contract | `docs/contracts/telemetry-v3-dispense-android.md` effective source note |

## Tests (targeted, round 2)

| Suite | Result |
|-------|--------|
| `PreparingManagerRecipeTest` | **10/10 PASS** (incl. missing/incomplete/invalid/scale_failed/paid-continues) |
| `InventoryCellRecipeSupportTest` | **5/5 PASS** |
| `TelemetryCellsSyncCoordinatorTest` | **PASS** (A4 assign case) |

## Build (round 2)

| Command | Result |
|---------|--------|
| `gradlew.bat :app:testDebugUnitTest --tests PreparingManagerRecipeTest --tests InventoryCellRecipeSupportTest --tests TelemetryCellsSyncCoordinatorTest --max-workers=1` | **BUILD SUCCESSFUL** (~41s) |
| `gradlew.bat assembleDebug --max-workers=1` | **BUILD SUCCESSFUL** (~30s) |

## Review items

| ID | Status |
|----|--------|
| **R-1** | ✅ Fixed — no `check()` on pour setup; legacy fallback + log |
| **R-2** | ✅ Code ready — distinct fallback reasons in log/context; ops runbook task-23 |
| **R-3** | ⏳ task-20 PreparingManager integration |

## G-19 gates

| Gate | Status |
|------|--------|
| **G-19-1** Phase C pour from effective when flag+capability+pour gate | ✅ Code + unit tests; runtime blocked on flags |
| **G-19-2** Telemetry snapshot base ml from effective | ✅ |
| **G-19-3** Service assign content-then-recipe (A4) | ✅ Documented + coordinator test |
| **G-19-4** Gate race (managed inactive → legacy pour) | ✅ `isPourFromEffectivePermitted` + report-only test |
| **G-19-5** Rollout gate (report-only before pour switch) | ✅ Separate `FEATURE_RECIPE_POUR_FROM_EFFECTIVE`; both flags `false` |

## Rollout / blockers

- **Merge-safe:** code defaults preserve legacy pour (`FEATURE_RECIPE_SYNC=false`, `FEATURE_RECIPE_POUR_FROM_EFFECTIVE=false`).
- **Report-only (step 5–6):** enable `FEATURE_RECIPE_SYNC` only after server staging; pour stays legacy until step 7 flips pour gate.
- **Phase C (step 7):** flip `FEATURE_RECIPE_POUR_FROM_EFFECTIVE` after report-only soak + task-23 emulator AND-6 smoke.
- **Blockers:** none for task-19 scope; prod pour switch intentionally gated on explicit flag flip + office sign-off (task-23).
