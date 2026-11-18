# task-01 — code review

**Session:** `machine-cells-inventory`  
**Repo:** `viwa-telemetry`  
**Reviewer:** code-reviewer-complex (structured review: general, performance, docs, final)  
**Date:** 2026-07-19

## Краткий вывод

Реализация task-01 соответствует `task-01.md`, канону `architecture.md` §«Модели данных» и ключевым проверкам из brief. Prisma-схема, migration SQL, allowlist из 14 вкусов и расширение TTL-cleanup для `machine_ws_message_dedup` (7 дней) согласованы между собой. Unit-тесты и `npm test` / `npm run build` проходят; применение migration на реальной БД не проверено (нет `DATABASE_URL` локально).

## Сверка с acceptance (task-01)

| Критерий | Статус | Комментарий |
|----------|--------|-------------|
| Модели `Product`, `MachineCell`, `MachineWsMessageDedup` | ✅ | Поля, типы и relations совпадают с architecture §Модели данных |
| Расширение `Machine`: `cellSchemaHash`, `cellSchemaSyncedAt`, `cellsContentRevision` | ✅ | `@default(0)` на `cellsContentRevision` |
| Enum `ContentSource` (`MACHINE` \| `DASHBOARD`) | ✅ | На `MachineCell.contentSource` с `@default(MACHINE)` |
| `MachineCell.id` без server `@default(uuid())` | ✅ | `String @id` — uuid приходит с автомата |
| Индексы: `(machineId, isActive)`, unique `(machineId, cellNumber)`, `(productId)` | ✅ | Дополнительно `(machineId)` — как в FEATURE §3.2, не противоречит task |
| FK: `productId` → SetNull, `machineId` → Cascade | ✅ | В schema и migration |
| `TASTE_MEDIA_KEYS` — 14 ключей + RU labels | ✅ | Совпадает с FEATURE §3.1.4 / architecture allowlist |
| Prisma migration | ✅ (SQL) | SQL соответствует schema; **apply на БД — NOT RUN** |
| Dedup TTL cleanup 7d | ✅ | `MACHINE_WS_MESSAGE_DEDUP_RETENTION_DAYS = 7`, index `received_at` |
| Register / auto-create cells не затронуты | ✅ | Изменений в register-логике нет; schema не триггерит insert cells |

## Специальные проверки

### `MachineCell.id` без default uuid

```prisma
model MachineCell {
  id String @id
  ...
}
```

Migration: `"id" TEXT NOT NULL` без `DEFAULT` — корректно.

### `ContentSource` enum

Schema + migration создают enum `'MACHINE', 'DASHBOARD'`; колонка `content_source` с default `'MACHINE'`.

### 14 tastes

Ключи и RU-лейблы в `taste-media-keys.ts` совпадают с таблицей FEATURE §3.1.4 (включая `peach-mango` → «Манго-персик»).

### Dedup TTL 7d

`ExpiredRecordsCleanupService` удаляет записи с `receivedAt < now - 7 days`; константа экспортирована для тестов; индекс `(received_at)` в migration для эффективного cleanup.

### Indexes / FK

Migration SQL зеркалит schema: все индексы, unique constraint и три FK (`machine_cells` ×2, `machine_ws_message_dedup` ×1) присутствуют с правильными `ON DELETE`.

### Migration SQL ↔ schema

Полное соответствие: enum, alter `machines`, create `products` / `machine_cells` / `machine_ws_message_dedup`, индексы, FK. Паттерн `updated_at NOT NULL` без DB default — консистентен с init-migration проекта (Prisma `@updatedAt`).

---

## Ревью по skills

### General (архитектура)

- **Суммаризация:** добавлены доменные таблицы инвентаря ячеек, расширен cleanup для WS dedup, вынесен shared allowlist вкусов.
- **Качество:** код минимален, типизация строгая (`as const`, `TasteMediaKey`), без лишних абстракций.
- **Граничные случаи:** валидация taste — через `isValidTasteMediaKey` (для последующих REST/WS task); DB-level check на allowlist отсутствует — ожидаемо для task-01.
- **Замечаний по архитектуре нет.**

### Performance

- Индекс `machine_ws_message_dedup(received_at)` поддерживает периодический `deleteMany`.
- Cleanup: параллельные `deleteMany` через `Promise.all` — приемлемо для фонового job.
- Таймер cleanup очищается в `onModuleDestroy` (без изменений в этом task, поведение сохранено).
- **Минимальный риск, UI не затронут.**

### Docs

- JSDoc есть на `TASTE_MEDIA_KEYS`, `TASTE_MEDIA_KEY_LABELS_RU`, type `TasteMediaKey`.
- 🟡 Экспорты `isValidTasteMediaKey`, `listTasteMediaKeysWithLabelsRu` без JSDoc — некритично, но стоит добавить в follow-up.

### Final (целостность)

- Нет `TEMP_*`, закомментированного кода, scope creep (register/import новых модулей не тронуты).
- Тесты покрывают allowlist и cutoff dedup cleanup.
- Единственный пробел verification: migration apply на живой БД.

---

## Критичные проблемы

*Нет.*

---

## Некритичные замечания

1. **Migration apply не проверен** — test report: `NOT RUN` (нет `.env` / `DATABASE_URL`). Перед merge в shared/staging окружение стоит прогнать `npm run prisma:migrate -w @wiva/api`.
2. **JSDoc на экспортируемых helper-функциях** — `isValidTasteMediaKey`, `listTasteMediaKeysWithLabelsRu` (docs skill).
3. **Дублирование allowlist в тесте** — `EXPECTED_KEYS` копирует константу; оправдано как regression guard, но при изменении списка нужно править два места.
4. **FEATURE §3.2 vs architecture.md** — в FEATURE snippet нет `contentSource` / `cellsContentRevision`; канон — `architecture.md`, код следует ему. Обновление FEATURE — вне scope task-01.

---

## Вердикт

**approve**

Код и schema готовы к следующим task (REST/WS). Рекомендуется прогнать migration apply в CI или staging до production deploy.
