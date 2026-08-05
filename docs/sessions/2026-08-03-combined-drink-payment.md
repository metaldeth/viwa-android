# 2026-08-03 — combined drink payment

## Done
- Напитки сразу запускают card+SBP (combined payment flow).
- Fullscreen translucent touch-block overlay на время оплаты.
- Увеличенный QR, timer и card statuses в UI оплаты.
- Атомарный session gate: first-success для card/SBP и блокировка late-success после cancel/timeout.
- Cancel и timeout останавливают оба канала, закрывают overlay и возвращают чистое меню.
- Paid-prepare recovery после успешной оплаты.
- Unit-тесты combined flow и `CardPaymentUiStatus`.

## Decisions
- Combined card+SBP без промежуточного выбора метода — продуктовое решение для kiosk drink flow.
- Touch-block overlay — блокировка случайных касаний во время активной оплаты.
- First-success и cancel/timeout синхронизированы одним session gate — закрытая сессия не принимает поздние callback.

## Risks
- Реальный AQSI USB + Paymaster race не проверен end-to-end на железе.
- `onCleared` — best-effort cleanup, не гарантия при жёстком kill процесса.
- Ранний `installDebug` через Gradle также обновил connected `k3568_a` до targeted install на `emulator-5554` — side effect, не скрывать.

## Verification
- Focused compile + tests (combined payment, `CardPaymentUiStatus`) — 25 passed.
- `assembleDebug` — exit 0.
- IDE lints — none на затронутых файлах.
- Final safety review — PASS.
- Compose static review — PASS.
- Emulator `emulator-5554`, debug `26.08.03.03`: targeted `adb install`, combined smoke / cancel / touch-block / logcat — PASS.
- Реальный timeout 120 секунд на эмуляторе — overlay закрылся, выбор и payment state сброшены, чистое меню восстановлено.
- Timeout regression: late non-cooperative card success не запускает приготовление; stale SBP ограничен текущим `orderId`.
- Release — не проверено: `installRelease` task отсутствует, signing secrets недоступны локально (см. BT-004 в build log).
- Full `:app:testDebugUnitTest` — incomplete: hang на suite + unrelated telemetry failure (см. BT-001, BT-005).

## Git facts
- repo: `C:\wiva\wiva-android`
- branch: `main`
- commit: отсутствует (HEAD base `9c1cbf86168b05bd9cc95d15ce88d0423e287bf7`)
- diff/stat: обновляется вместе с рабочим diff задачи; commit отсутствует.

## Next
- Real kiosk payment acceptance (AQSI USB + Paymaster на устройстве).
- Отдельно: fix full unit-suite hang (BT-001) и `TelemetryPourMessageCodecTest` (BT-005).
