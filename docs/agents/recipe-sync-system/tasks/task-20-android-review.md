# task-20-android-review: PreparingManager pour integration + golden parity CI

**Session:** `recipe-sync-system`  
**Task:** [task-20.md](../../../../wiva-telemetry/docs/agents/recipe-sync-system/tasks/task-20.md) — Cross-repo integration + AC-20 matrix (Android slice)  
**Prior review:** [task-19-review.md](./task-19-review.md) (R-3 open → task-20)  
**Test report:** [task-20-test-report.md](./task-20-test-report.md)  
**AC-20 matrix:** [ac-20-matrix.md](../../../../wiva-telemetry/docs/agents/recipe-sync-system/tasks/ac-20-matrix.md)  
**Review:** round 1 — 2026-08-06  
**Scope:** task-20 Android touch map + `PreparingManager.pourFromEffectiveOverrideForTests`; **no code edits in review**

---

## Files reviewed

| Path | Role |
|------|------|
| `services/preparing/PreparingManager.kt` | Pour path: gate → `getEffective` → `resolvePourSetup` → `chooseDrink` → frozen context → `enqueueDispenseTelemetry` |
| `services/preparing/RecipePourIntegrationTest.kt` | AND-6 / AC-30 integration (4 tests) |
| `services/preparing/PreparingManagerRecipeTest.kt` | task-19 resolution matrix (10 tests) — cumulative context |
| `domain/inventory/InventoryCellRecipeSupport.kt` | `isPourFromEffectivePermitted`, `resolvePourSetup` |
| `data/local/recipe/RecipeSyncFeatureFlags.kt` | Compile-time Phase C gates (`false` / `false`) |
| `domain/recipe/RecipeGoldenParityCiTest.kt` | PAR-4 byte/hash parity gate |
| `app/src/test/resources/recipe/golden-v1.json` | Android copy of canonical fixture |
| `app/src/test/resources/recipe/scale-v1.json` | Android copy of scale vectors |
| `wiva-telemetry/scripts/recipe-parity.mjs` | PAR-1/PAR-2 cross-repo script (read for CI portability) |

**Context read:** task-20 spec/report, task-19 review (R-3), architecture §11.3 step 7, ac-20-matrix AND-6 / AC-30 rows.

---

## Verification run (review)

| Command | Result |
|---------|--------|
| `gradlew.bat :app:testDebugUnitTest --tests RecipePourIntegrationTest --tests RecipeGoldenParityCiTest --max-workers=1` | **PASS** (4/4 + 3/3) |
| Cross-repo SHA-256 `golden-v1.json` / `scale-v1.json` (telemetry vs Android) | **byte-identical** — matches embedded constants in `RecipeGoldenParityCiTest` |

---

## Integration test credibility — does it bypass production semantics?

### What `RecipePourIntegrationTest` genuinely exercises

| Step | Production code? | Notes |
|------|------------------|-------|
| `PreparingManager.prepareDrink` mutex + snapshot lookup | ✅ | Real manager instance |
| `effectiveRecipeStore.getEffective(uuid)` | ✅ | Real `CellEffectiveRecipeStore` + `FakeCellEffectiveRecipeDao` |
| `InventoryCellRecipeSupport.resolvePourSetup` | ✅ | Not mocked; same path as prod |
| `containerForPour` → `drinkSelection.chooseDrink(...)` | ✅ | Captures **scaled** `controllerDosage` vs **base** telemetry fields |
| Success path → `enqueueDispenseTelemetry` → `DispenseTelemetryFactory.paidComplete` | ✅ | Test 1 captures `PaidCompleteSnapshot` after `DrinkPreparingSuccess` |
| Managed coordinator setup | ✅ | `RecipeSyncCoordinator.forTests()` hello + uplink + ready |

The test **does not** stub `resolvePourSetup`, `getEffective`, or telemetry factory wiring inside `PreparingManager`. It is a legitimate **PreparingManager → chooseDrink → frozen paid telemetry** integration, not a re-test of `InventoryCellRecipeSupport` in isolation.

### What is mocked or bypassed (material)

| Seam | Impact |
|------|--------|
| `ViwaDrinkSelectionService.chooseDrink` | **Mocked** — verifies arguments passed to pour layer, not controller serial/commands or `ViwaDrinkPreparingService.startDrinkPreparing` |
| `pourFromEffectiveOverrideForTests` | **Bypasses** compile-time `RecipeSyncFeatureFlags.FEATURE_RECIPE_SYNC` + `FEATURE_RECIPE_POUR_FROM_EFFECTIVE` and the `managedGateActive && flags` conjunction in `isPourFromEffectivePermitted` |
| Compile-time flags remain `false` in prod APK | Expected rollout gate; override is the intended test-only substitute |

**Assessment:** Credible for **R-3 / ac-20-matrix AND-6-1…3 and AC30-1** (wiring + dosage semantics). **Not** a substitute for task-23 manual AND-6 on release AVD or physical board.

---

## `@VisibleForTesting` override — safety and leakage

```kotlin
@VisibleForTesting
@JvmField
internal var pourFromEffectiveOverrideForTests: Boolean? = null
```

| Concern | Verdict | Notes |
|---------|---------|-------|
| Production behavior when unset | ✅ Safe | `null` → `isPourFromEffectivePermitted(recipeSyncCoordinator.isManagedModeActive())` |
| Production code setting override | ✅ | Grep: **only** `PreparingManager.kt` + `RecipePourIntegrationTest.kt` |
| Test isolation | ✅ | `@Before` / `@After` reset to `null`; `@After` calls `manager.resetSession()` |
| APK / R8 surface | 🟡 | Field lives in **main** source set (dead path when `null`); `internal` — not public API; acceptable for MVP |
| DEBUG-only guard | 🟡 | No `BuildConfig.DEBUG` check — reliance on discipline + `null` default is sufficient for merge; optional hardening |
| Parallel test suites | 🟡 | Static mutable state — safe if only this class sets it; risk if future tests reuse without cleanup |

**Leakage into production behavior:** **None** with default `null`. Override affects pour only when non-null; it does not alter telemetry wire schema or fallback logging.

---

## Scenario matrix (task-20 Android acceptance)

| ID | Scenario | Verdict | Evidence |
|----|----------|---------|----------|
| **AND-6-1** | Effective pour Phase C — scaled `chooseDrink` | ✅ | Test 1 @ 700 ml: controller water/product match `dosageScaledToPourVolumeOrNull(customizedTriple)` |
| **AC30-1** | Frozen paid telemetry base integers | ✅ | Test 1: `recipeDrinkVolumeMl=300`, `recipeWaterMl=260`, `recipeProductMl=40` after success (not scaled pour volume) |
| **AND-6-2** | Missing effective fallback | ✅ | Test 2: legacy 270/30 @ 300 ml with gate override `true` |
| **AND-6-3** | Gate off despite stored effective | ✅ | Test 3: override `false` + persisted triple → legacy 270/30 |
| **AC30-2** | Telemetry factory parity | 🟡 | Test 4 validates `DispenseTelemetryFactory` **in isolation** — duplicate of `PreparingManagerRecipeTest`; does not add PreparingManager coverage |
| **PAR-1/2** | golden-v1 / scale-v1 byte parity | ✅ | Fixtures byte-identical; `RecipeGoldenParityCiTest` SHA-256 constants match live files |
| **PAR-4** | Kotlin CI gate | ✅ | 3/3 pass locally; included in standard `:app:testDebugUnitTest` when run |
| **Frozen mid-pour** | Recipe change during active pour | ⏭ | Not in task-20 suite; task-19 mutex + frozen context — unit-level only |
| **Fallback variants** | incomplete / invalid / scale_failed | ⏭ | `PreparingManagerRecipeTest` (task-19), not repeated in integration |
| **Prod flag path** | All three gates true without override | ⏭ | Blocked until compile-time flags flip + task-23 |

---

## Canonical fixture source / CI portability

| Item | Status |
|------|--------|
| **Canonical source** | `wiva-telemetry/apps/api/test/fixtures/recipe/golden-v1.json` (+ `scale-v1.json`) |
| **Android copy path** | `app/src/test/resources/recipe/{golden,scale}-v1.json` |
| **Sync mechanism** | Manual copy + `RecipeGoldenParityCiTest` embedded SHA-256 + `npm run test:recipe-parity` |
| **Local parity (this machine)** | ✅ Both pairs byte-identical |
| **`recipe-parity.mjs` default Android root** | `../wiva-android` — on `viwa-android`-only checkouts set **`VIWA_ANDROID_ROOT`** (documented in script) |
| **Android GitLab CI** | ⚠️ `.gitlab-ci.yml` runs **release Docker build only** — no `:app:testDebugUnitTest`; parity gate relies on local/telemetry CI, not Android pipeline today |

---

## Findings synthesis (prioritized)

### 🔴 Critical — merge blockers

**None** for task-20 Android artifact scope.

### 🟠 High — credibility / gate gaps

| ID | Finding | Required fix |
|----|---------|--------------|
| **F-20-1** | `pourFromEffectiveOverrideForTests` bypasses compile-time triple gate — prod `FEATURE_*` + managed conjunction **not** integration-tested | Accept for task-20 merge; **mandatory** re-verify on flag flip (task-23 AND-6 release smoke). Optional: one test with override `null` + test-only flag injection (if ever added) |
| **F-20-2** | AC30-2 test does not traverse `PreparingManager` | Non-blocking; consider moving to `DispenseTelemetryFactoryTest` or dropping duplicate — does not block R-3 |

### 🟡 Medium — non-blocking

| ID | Finding | Owner |
|----|---------|-------|
| **F-20-3** | `chooseDrink` mocked — no controller / `startDrinkPreparing` E2E | task-23 emulator AND-6 |
| **F-20-4** | Android GitLab CI skips unit tests — `RecipeGoldenParityCiTest` not enforced on push | CI hardening (optional) |
| **F-20-5** | `recipe-parity.mjs` sibling path `wiva-android` vs `viwa-android` rename | Set `VIWA_ANDROID_ROOT` in CI/docs |

### 🟢 Strengths

- Real `PreparingManager.prepareDrink` path with durable effective store read
- AC-30 semantics correct: telemetry carries **base** effective ml, controller gets **scaled** pour dosage
- Fallback + gate-off scenarios covered at integration layer per ac-20-matrix
- Golden fixtures byte-identical with hashed CI gate — drift will fail tests if copies diverge
- `@VisibleForTesting` override is minimal, null-default, test-cleaned — no prod leakage

---

## R-3 / AND-6 / AC-30 closure credibility

| Claim | Credibility | Rationale |
|-------|-------------|-----------|
| **R-3 closed (automated wiring)** | **✅ High** | `RecipePourIntegrationTest` satisfies task-19 gap: PreparingManager → chooseDrink args → frozen `PaidCompleteSnapshot` |
| **AND-6 closed (automated)** | **✅ Medium–High** | Matrix rows AND-6-1…3 covered; manual customize-on-service-UI → customer pour **deferred to task-23** per task-20 spec |
| **AC-30 closed (automated)** | **✅ High** | AC30-1 through full success path; base integers frozen at mutex snapshot |
| **AND-6 / AC-30 closed (prod Phase C)** | **⛔ Not credible yet** | Flags `false`; override-only Phase C; task-23 release smoke + ops runbook still required |

**task-20-test-report claim** (“R-3 closed in code”) is **accurate for automated ac-20-matrix scope**, with the documented caveats above. It is **not** authorization to flip `FEATURE_RECIPE_POUR_FROM_EFFECTIVE`.

---

## Final verdict

| Layer | Verdict | Meaning |
|-------|---------|---------|
| **Task-20 Android merge (tests + parity artifacts)** | **✅ PASS** | Integration + parity tests pass; genuinely wire PreparingManager pour semantics; fixtures portable |
| **R-3 / automated AND-6 + AC-30 (ac-20-matrix)** | **✅ PASS_GATED** | Credible closure for unit/integration gate; test seam + mocked chooseDrink acknowledged |
| **Phase C prod pour flip (step 7)** | **⛔ BLOCK** | Requires task-23 AND-6 release smoke, flag flip soak, `pourRecipeFallback` ops monitoring (task-19 residual) |

---

## Required fixes before Phase C prod (unchanged from task-19)

1. **task-23** — Emulator AND-6 on **release** build (`viwa-android` AVD); physical board sign-off post-merge.
2. **Flag flip** — Soak with `FEATURE_RECIPE_SYNC` + `FEATURE_RECIPE_POUR_FROM_EFFECTIVE` true; re-run `RecipePourIntegrationTest` with override `null` or remove override dependency.
3. **Ops** — Grep/monitor `pourRecipeFallback` / `missing_effective` before pour gate (R-2 runbook).

### Optional (non-blocking for task-20 merge)

- Remove or relocate AC30-2 duplicate factory test from integration class.
- Add `@AfterClass` / rule if parallel Gradle test workers ever share JVM static override.
- Wire `RecipeGoldenParityCiTest` into Android CI or monorepo pre-push script.

---

## JSON (review output)

```json
{
  "reviewReportFile": "docs/agents/recipe-sync-system/tasks/task-20-android-review.md",
  "reviewRound": 1,
  "hasCriticalIssues": false,
  "task20AndroidMergeVerdict": "PASS",
  "r3AutomatedVerdict": "PASS_GATED",
  "and6Ac30AutomatedCredibility": "medium_high",
  "phaseCProdVerdict": "BLOCK_UNTIL_TASK23",
  "recipePourIntegrationTests": 4,
  "recipeGoldenParityCiTests": 3,
  "testsRunInReview": 7,
  "goldenFixtureByteIdentical": true,
  "visibleForTestingOverrideSafe": true,
  "requiredFixesBeforePhaseC": ["task-23-and6-release-smoke", "feature-flag-flip-soak", "pourRecipeFallback-ops"],
  "commentsSummary": "RecipePourIntegrationTest genuinely wires PreparingManager→resolvePourSetup→chooseDrink→frozen paid telemetry; test seam bypasses compile-time flags by design. Golden parity CI portable with embedded SHA-256. PASS task-20 Android; BLOCK Phase C until task-23."
}
```
