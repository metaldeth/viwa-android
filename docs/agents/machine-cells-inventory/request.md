# Request: machine-cells-inventory

**Дата:** 2026-07-19  
**Триггер:** `/complex`  
**sessionId:** `machine-cells-inventory`

## Repos

| Repo | Роль |
|------|------|
| `c:\viwa\viwa-telemetry` | БД, REST, WS MVP, web dashboard (products + machine cells) |
| `c:\viwa\viwa-android` | Автомат: schema/volume/content uplink, snapshot, UI inventory |

## Постановка (сводка чата)

Нужна фича инвентаря ячеек автомата в стеке **Wiva** (не Shaker legacy).

1. На **странице автомата** (web) показать остатки по ячейкам; с фронта менять содержимое ячеек и цены.
2. Два типа запроса автомат → телеметрия:
   - изменение остатков без изменения содержимого;
   - изменение содержимого (желательно вместе с остатками).
3. При регистрации ячейки **не создаются**. После успешной регистрации автомат шлёт схему ячеек; бэкенд создаёт/reconcile если нет или схема отличается. Остатки часто дёргаются — consistency low-priority.
4. Стаканы / расходники / вода — **out of scope**.
5. Структура ячейки: `uuid`, `productUuid`, `blockVolume`, `sosVolume`, `volume`, `maxVolume`, `dosage1Price`, `dosage2Price`, `cellNumber`.

### Дополнение: база продуктов (отдельный раздел)

- Отдельный раздел web UI: **одна таблица всех продуктов**.
- Поля продукта: **название** + **вкус** (`tasteMediaKey`) — один из допустимых в Android (привязаны к картинкам).
- Allowlist (канон `ViwaElectronAssets.kt`):  
  `cherry`, `blackberry-lime`, `coconut`, `cucumber`, `grapefruit`, `lemon`, `lime`, `lime-mint`, `orange`, `peach-mango`, `pomegranate-blueberry`, `raspberry`, `strawberry-lemongrass`, `watermelon`.
- CRUD: добавлять / редактировать / удалять. При добавлении: ввод названия + выбор вкуса из списка.

## Канонический brief

`c:\viwa\viwa-android\docs\FEATURE_MACHINE_CELLS_INVENTORY.md`  
(обновлён: §3.1.3–3.1.4 Product + tastes, REST products CRUD, UI «База продуктов», этапы M1–M7)

Справочник legacy (не целевой протокол):  
`c:\viwa\viwa-android\docs\CELL_FILLING_REQUESTS_AND_STORAGE.md`

## Ожидание от complex

Полный пайплайн: ТЗ → архитектура → план → реализация (оба репозитория) → ревью → тесты → summary → task-completion.

**Primary AGENTS для web/API:** `c:\viwa\viwa-telemetry\AGENTS.md`  
**Android:** `c:\viwa\viwa-android\AGENTS.md` (`tdd: true`)
