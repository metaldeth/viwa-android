# task-18 — review-compose-layout (static)

**Session:** `recipe-sync-system`  
**Task:** task-18 — assignment base cache + service UI edit/reset/drift + outbox drain  
**Date:** 2026-08-06  
**Repo:** `viwa-android`  
**Reviewer:** compose-layout subagent (static only; no code edits)

**Scope:** `ViwaInventoryVolumesTab.kt`, `ServiceMenuTestTags.kt` (task-18 UI touch map); service-menu shell checked for integration (`SettingsColumn`, `ServiceScreen`).

**Rules/skills:** `review-compose-layout` SKILL, `android-compose-scroll-layout.mdc`, `android-compose-optimization.mdc`, `AGENTS.md`.

---

## Ревью: Compose layout / scroll

### Статический анализ

#### Task-18 additions (recipe / taste dialogs)

| Area | Verdict | Notes |
|------|---------|-------|
| `ManagedInventoryRecipeDialog` | ✅ | `AlertDialog` text: single `Column(Modifier.heightIn(max = 420.dp).verticalScroll(...))` — bounded dialog scroll per project canon |
| Edit mode fields | ✅ | `SettingsTextField` inside scrolled column; no second `verticalScroll` on modifier; no `SettingsColumn` inside dialog |
| Edit/reset confirm dialogs | ✅ | Static `Column` / `Text`; no nested scroll |
| Loading stub dialog | ✅ | Minimal text; no scroll |
| `LegacyInventoryRecipeDialog` (`FEATURE_RECIPE_SYNC=false`) | ✅ | `heightIn(max = 360.dp)` + single `verticalScroll` |
| `InventoryTasteConfirmDialog` | ✅ | Short static content; no scroll needed |
| Row action buttons (`MvpVolumeRow`) | ✅ | `weight(1f)` in `Row`; not inside conflicting scroll parent |
| `LazyColumn` / weight split | ✅ | Not used in task-18 changes; no table+detail weight panel added |

🟡 **Риск** (non-blocking static):

- [`ViwaInventoryVolumesTab.kt:589-592`](../../../../app/src/main/java/com/viwa/android/ui/screens/service/tabs/ViwaInventoryVolumesTab.kt) — `InventoryTastePickerDialog`: `Column(Modifier.verticalScroll(...))` without explicit `heightIn`. Acceptable inside `AlertDialog` (bounded dialog slot per `android-compose-scroll-layout.mdc`), but a large `products` list may feel tight on landscape 768dp; follow-up: add `heightIn(max = …)` mirroring `FindScannerDialog` (`LazyColumn` + `heightIn(max = 200.dp)`) if smoke shows clipping.

🟡 **Carried pre-task-18** (not introduced by task-18 diff):

- [`ViwaInventoryVolumesTab.kt:84-114`](../../../../app/src/main/java/com/viwa/android/ui/screens/service/tabs/ViwaInventoryVolumesTab.kt) — `SettingsColumn` (outer `verticalScroll` at [`ServiceTabUtils.kt:73-74`](../../../../app/src/main/java/com/viwa/android/ui/screens/service/ServiceTabUtils.kt)) wraps inner `Column(Modifier.heightIn(max = 640.dp).verticalScroll(...))`. Nested scroll is discouraged; inner `heightIn` bounds height and mitigates `infinity maximum height` risk. Same pattern as task-10 inventory tab; task-18 only added `testTag` on `SettingsColumn`. Recommend eventual refactor (single scroll or `LazyColumn` in weighted region) — out of task-18 scope.

🔴 **Критично** (crash on device): **none found** in task-18 UI diff.

#### Service-menu integration

- Tab hosted via `ViwaServiceMenuTabContent` → `ViwaInventoryVolumesTab`; no new split `weight` table/detail layout.
- `ServiceScreen` sidebar/content shell unchanged by task-18; no new unbounded overlay scroll.

### Runtime smoke

| Item | Status |
|------|--------|
| Scenario | Service menu → Обслуживание → Остатки → «Рецепт» (legacy + managed when flag on), «Изменить»/edit fields, «Сброс», «Сменить вкус» picker/confirm |
| Device / emulator | **Не выполнялся** — explicitly deferred to **task-23** (per task-18 test report + parent brief) |
| `FEATURE_RECIPE_SYNC` | **`false`** — managed recipe dialog path (`ManagedInventoryRecipeDialog` L286+) not exercised at runtime until flag flip; smoke must cover **both** legacy and flag-on paths in task-23 |
| logcat (`infinity maximum height` / FATAL) | **Not checked** — deferred |

Runtime smoke is a **task-completion gate for full UI sign-off**, not a failure of this static pass. Do not treat smoke as passed.

### Checklist (static)

| Check | Result |
|-------|--------|
| No `SettingsColumn` + extra `verticalScroll` on same modifier | ✅ (dialog uses raw `Column`, not `SettingsColumn`) |
| No nested `verticalScroll` in **new** dialog code | ✅ |
| No `LazyColumn` inside `Column(verticalScroll)` without bounded height | ✅ (no LazyColumn in scope) |
| Dialog scroll bounded (`heightIn`) for recipe panels | ✅ managed 420dp, legacy 360dp |
| Weight + list split layout | N/A |
| Service-menu block order / scroll hierarchy on panel expand | N/A (dialogs only) |

### Итог

**✅ PASS — static Compose layout review**

No blocking `infinity maximum height` / unbounded lazy-list patterns in task-18 UI changes. Recipe dialogs follow recommended bounded single-scroll pattern. Runtime smoke and logcat verification remain **open** (task-23, `FEATURE_RECIPE_SYNC=true` path).

**Static layout:** PASS  
**Runtime smoke:** DEFERRED (not PASS)

### Suggested task-23 smoke steps

1. `installDebug` / release per `AGENTS.md`; open service menu → Inventory tab.
2. With `FEATURE_RECIPE_SYNC=false`: open «Рецепт» → legacy dialog scroll.
3. With flag on (staging build): open «Рецепт» → drift badge → «Изменить» (3 fields + save confirm) → «Сброс» confirm.
4. «Сменить вкус» → picker scroll with many products → confirm dialog.
5. logcat filter: `FATAL|infinity maximum height|IllegalStateException`.
