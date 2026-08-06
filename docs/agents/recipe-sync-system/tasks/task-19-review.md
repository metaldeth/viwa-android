# task-19-review: Android preparing/pour Phase C + service assign A4

**Session:** `recipe-sync-system`  
**Task:** [task-19.md](../../../../wiva-telemetry/docs/agents/recipe-sync-system/tasks/task-19.md) — Phase C pour from effective + telemetry snapshot + A4 assign path  
**Test report:** [task-19-test-report.md](./task-19-test-report.md)  
**Review:** round 1 + **round 2 (final)** — 2026-08-06  
**Scope:** task-19 touch map + R-1/R-2 polish; **no code edits in review**

---

## Files reviewed

### Round 1 (core)

| Path | Role |
|------|------|
| `data/local/recipe/RecipeSyncFeatureFlags.kt` | Separate Phase C pour gate |
| `services/preparing/PreparingManager.kt` | Effective lookup, frozen context, telemetry dosage |
| `domain/telemetry/DispenseTelemetryFactory.kt` | Optional recipe wire fields from base dosage |
| `domain/customer/TelemetryCellsSnapshotAdapter.kt` | Customer UI legacy template + doc note |
| `data/remote/telemetry/mvp/TelemetryCellsSyncCoordinator.kt` | A4 content-only assign |
| `data/remote/telemetry/mvp/cells/RecipeSyncCoordinator.kt` | Managed hello/uplink gate |
| `data/local/recipe/CellEffectiveRecipeStore.kt` | Durable effective reads |
| `domain/recipe/RecipeCanonical.kt` | `scaleRecipeDeci` integer scaling / overflow guards |
| `docs/contracts/telemetry-v3-dispense-android.md` | Effective source + A4 note |

### Round 2 (R-1/R-2)

| Path | Role |
|------|------|
| `domain/inventory/InventoryCellRecipeSupport.kt` | **`resolvePourSetup`** — total fail-safe resolution; `PourRecipeFallbackReason`; `actualWaterMlForResolution` |
| `services/preparing/PreparingManager.kt` | `pourRecipeFallback` structured log; context fallback fields; paid continues on fallback |
| `app/src/test/.../PreparingManagerRecipeTest.kt` | **10 tests** — exception/fallback matrix + paid policy |

**Context read:** [architecture.md](../../../../wiva-telemetry/docs/agents/recipe-sync-system/architecture.md) §3.3, §8.1 steps 5–7, §11.3; round-1 review; [task-19-test-report.md](./task-19-test-report.md) round 2.

---

## Round 2 stress focus — R-1 / R-2

| Scenario | Verdict | Notes |
|----------|---------|-------|
| **`resolvePourSetup` total / non-throwing** | ✅ | All branches return `PourRecipeResolution`; no `check()` on pour path; `scaleDosageToPourVolume` → `ScaleDosageOutcome` |
| **Null effective (Phase C on)** | ✅ | `FALLBACK_LEGACY` + `MISSING_EFFECTIVE` + `diagnostics=effective=null` |
| **Incomplete effective** | ✅ | `INCOMPLETE_EFFECTIVE` for `UNINITIALIZED` / `triple=null`; test uses `controlOnly` |
| **Invalid effective (corrupt persist)** | ✅ | `RecipeCanonical.validate` → `INVALID_EFFECTIVE` + `validationErrors=<enum names>`; test uses sum-invariant break with `isRecipeComplete=true` |
| **Scale out-of-range** | ✅ | 1500 ml → `SCALE_FAILED` + `scaleErrors=…`; `dosageScaledToPourVolumeOrNull` returns null |
| **Integer overflow path** | ✅ / 🟡 | Handled by same `scaleRecipeDeci` failure branch (no throw); **no dedicated OVERFLOW unit test** in task-19 suite |
| **Paid fallback policy** | ✅ | No fail-closed branch; same `resolvePourSetup` for CARD/SBP; log `paid=true`; test asserts legacy `controllerDosage` at 300 ml base |
| **Diagnostics — sensitive data** | ✅ | Diagnostics = enum names + `source=<CellEffectiveRecipeSource>`; no fingerprint/triple/customer fields; log uses cell UUID (ops id) only |
| **Diagnostics — wire coupling** | ✅ | Fallback reason/diagnostics **not** encoded in `DispenseTelemetryFactory` / v3 pour wire; local `CurrentPreparingContext` + Timber only |
| **Frozen context semantics** | ✅ | Snapshot at mutex start: `baseDosage` for telemetry recipe fields; `controllerDosage` for hardware; `actualWaterMlForResolution` matches source (EFFECTIVE → scaled controller water; fallback → legacy ratio) |
| **Telemetry on FALLBACK_LEGACY** | ✅ / 🟡 | Wire fields = **legacy template** base (270/30) — matches actual pour math; server cannot see fallback reason without log grep (R-2 ops gate, task-23) |

---

## Safety stress matrix (cumulative)

| Scenario | Verdict |
|----------|---------|
| Rollout gating (G-19-5) | ✅ Both flags default `false`; report-only decoupled |
| Triple pour gate (G-19-1) | ✅ sync + pour flag + `isManagedModeActive()` |
| Recipe change mid-pour | ✅ Mutex + frozen `CurrentPreparingContext` |
| A4 assign (G-19-3) | ✅ Content-only + coordinator test |
| Managed gate race (G-19-4) | ✅ / 🟡 N-6 residual — permitted + unreadable effective → legacy pour |

---

## G-19 gates (final)

| Gate ID | Requirement | Status |
|---------|-------------|--------|
| **G-19-1** | Phase C pour from effective when triple gate on | ✅ Code + 10 unit tests; runtime blocked on flags |
| **G-19-2** | Telemetry snapshot base ml from effective | ✅ EFFECTIVE uses effective base; fallback uses legacy base consistently |
| **G-19-3** | Service assign content-then-recipe (A4) | ✅ |
| **G-19-4** | Gate race → legacy pour | ✅ Report-only + missing-effective fallback; N-6 residual |
| **G-19-5** | Report-only before pour switch | ✅ |

---

## Findings synthesis (round 2 final)

### 🔴 Critical — merge blockers

**None.** With default flags `false`, no Phase C pour path activates; `resolvePourSetup` eliminates crash-on-scale regression (R-1).

### 🟠 Phase C / prod gates (task-20 / task-23) — not merge blockers

| ID | Finding | Status |
|----|---------|--------|
| **R-1** | `check()` crash on scale failure | ✅ **Closed** — `resolvePourSetup` fail-safe |
| **R-2** | Generic fallback warning only | ✅ **Code closed** — `PourRecipeFallbackReason` + `pourRecipeFallback` log + context fields; **ops monitoring runbook** → task-23 |
| **R-3** | No PreparingManager integration (chooseDrink/controller) | ⏳ **Open** — task-20 `RecipePourIntegrationTest` (AND-6 / AC-30) |

### 🟡 Non-blocking

| ID | Finding | Owner |
|----|---------|-------|
| **N-1** | Dual managed gate (coordinator vs store runtime) | Optional hardening |
| **N-2** | No customized `syrupMlActual` at 700 ml codec test | task-20 |
| **N-3** | Integer OVERFLOW not explicitly named in pour fallback test | Optional (same `SCALE_FAILED` branch) |
| **N-4** | Fallback reason absent from telemetry wire (by design) — server sees legacy recipe fields on Phase C fallback | task-23 ops grep `pourRecipeFallback` |
| **N-5** | `PreparingTimeRecord` does not persist fallback metadata | Low; logs sufficient for MVP |

### ✅ Round 2 strengths

- Single entry point `resolvePourSetup` — total function for pour safety
- Stable diagnostic vocabulary: `wireValue` for reasons; enum `.name` for validation/scale errors
- Paid and subscription pours share identical fallback policy (no silent crash, no inconsistent branch)
- Telemetry contract unchanged — backward compatible optional recipe fields only

---

## Test verification

| Round | Suite | Result | Assessment |
|-------|-------|--------|------------|
| 1 | `PreparingManagerRecipeTest` (6) | PASS | Resolution + gate matrix |
| **2** | `PreparingManagerRecipeTest` (**10**) | **PASS** | + missing/incomplete/invalid/scale_failed/paid-continues |
| 2 | `InventoryCellRecipeSupportTest` (5) | PASS | Canonical 700 ml |
| 2 | `TelemetryCellsSyncCoordinatorTest` (A4) | PASS | |
| 2 | Targeted unit + assembleDebug | PASS (test report) | Not re-run in review |

**Fallback coverage meaningful:** yes — each `PourRecipeFallbackReason` has a dedicated test; paid policy explicit; no test expects exception.

**Remaining gaps (Phase C gates):** PreparingManager wiring test; overflow-specific vector; customized syrup at 700 ml on wire.

---

## Final verdict

| Layer | Verdict | Meaning |
|-------|---------|---------|
| **Task-19 merge (flags default false)** | **✅ PASS** | Safe to merge: legacy pour in prod; R-1/R-2 code complete; unit coverage adequate for task scope |
| **Report-only flip (`FEATURE_RECIPE_SYNC=true`, pour flag false)** | **✅ PASS (gated)** | Architecture step 6; requires server staging + task-16…18 gates — **not blocked by task-19 review** |
| **Phase C prod pour flip (step 7 / `FEATURE_RECIPE_POUR_FROM_EFFECTIVE=true`)** | **⛔ BLOCK** | Requires **task-20** integration (R-3), **task-23** AND-6 emulator smoke, ops monitoring for `pourRecipeFallback` (R-2 runbook) |

Intentional compile-time flags at `false` are **rollout gates**, not defects.

---

## Required fixes / residual requirements

### Closed in round 2 (no further code required for merge)

1. **R-1** — Fail-safe `resolvePourSetup`; no throw on invalid/incomplete/scale failure.
2. **R-2 (code)** — Distinct fallback reasons + structured log + frozen context fields.

### Still required before Phase C prod (step 7)

1. **R-3 / task-20** — `RecipePourIntegrationTest`: PreparingManager → chooseDrink → telemetry end-to-end with effective + fallback paths.
2. **task-23** — Emulator AND-6 smoke; ops runbook: grep `pourRecipeFallback` / `missing_effective` rate before pour flag flip; confirm effective uplink complete on all pour cells.
3. **N-6 (optional hardening)** — Re-validate managed gate after `getEffective()` suspend or align store read with coordinator gate.

### Commit hygiene

Include `RecipeSyncFeatureFlags.kt`, `InventoryCellRecipeSupport.kt`, `PreparingManagerRecipeTest.kt` in the same commit as `PreparingManager` changes if still untracked.

---

## JSON (review output — final)

```json
{
  "reviewReportFile": "docs/agents/recipe-sync-system/tasks/task-19-review.md",
  "reviewRound": 2,
  "hasCriticalIssues": false,
  "task19MergeVerdict": "PASS",
  "task19ReportOnlyVerdict": "PASS_GATED",
  "task19PhaseCVerdict": "BLOCK_UNTIL_TASK20_TASK23",
  "r1Closed": true,
  "r2CodeClosed": true,
  "r2OpsClosed": false,
  "r3Open": true,
  "g19GatesClosed": ["G-19-1", "G-19-2", "G-19-3", "G-19-5"],
  "g19GatesPartial": ["G-19-4"],
  "requiredFixesBeforePhaseC": ["R-3", "task-23-ops-monitoring", "task-20-integration"],
  "testsRun": 0,
  "testsRunFromReport": 15,
  "preparingManagerRecipeTests": 10,
  "buildPassFromReport": true,
  "featureRecipeSync": false,
  "featureRecipePourFromEffective": false,
  "commentsSummary": "Round 2: resolvePourSetup is total/non-throwing for null, incomplete, invalid, and scale failure; paid pours consistently continue on legacy fallback; diagnostics use stable enum/wire tokens and stay off telemetry wire. PASS merge with default flags. BLOCK Phase C flip until task-20 integration and task-23 smoke/ops monitoring."
}
```
