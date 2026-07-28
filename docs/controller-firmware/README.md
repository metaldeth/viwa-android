# Прошивка контроллера автомата (Vita Flow)

Исходники MCU-контроллера станции (не Android-приложение).

| Поле | Значение |
|------|----------|
| Проект | Vita Flow Station |
| Версия (из `Vita_Flow.c`) | 2.0 |
| Чип | ATmega2560 |
| Генератор | CodeWizardAVR V3.12 |
| Компания | Shaker Tech |
| Источник архива | `2026_04_17.zip` (Downloads) |

## Снимки

| Каталог | Дата архива | Файлы |
|---------|-------------|--------|
| `2026_04_17/` | 2026-04-17 | `Vita_Flow.c`, `Func.c`, `LowInit.c`, `Prepare_Beverage.c`, `USART.c` |

Связь с Android: протокол UART/USB serial в `app/.../hardware/controller/`.
