# Запрос: Модуль G — Калибровки, готовка, остатки

## Источник

Пользователь: `/complex` / `/meta-complex` на 4 задачи из ТЗ `docs/TZ_G_CALIBRATION_COOKING_INVENTORY.md`.

## Задача

Реализовать полный цикл для viwa-android:

1. **G1 — Калибровка воды** — сервисное меню, тестовый налив, запись flowRateMlPerSec
2. **G2 — Калибровка сиропов** — тестовый налив, пересчёт conversionFactor, телеметрия
3. **G3 — Процесс готовки напитка** — chooseDrink → startDrinkPreparing, прогресс, состояния
4. **G4 — Остатки + телеметрия** — списание после SUCCESS, cellVolumeImportTopic, saleImportTopic

## Ключевые ограничения

- Эталон: **wiva_electron** (команды, формулы, порядок)
- `flowRateMlPerSec` обязателен для готовки — без дефолта, без fallback
- Расчёт времени: `preparingTime = round(waterMl / flowRateMlPerSec)` — только из калибровки
- После SUCCESS: списание + cellVolumeImportTopic + saleImportTopic

## Контекст проекта

- Проект: `c:\viwa\viwa-android`
- Предыдущие этапы A–F завершены (контроллер, телеметрия, оплаты, UI)
- ТЗ: `viwa-android/docs/TZ_G_CALIBRATION_COOKING_INVENTORY.md`
