# Build troubleshooting log — viwa-android

Журнал повторяющихся проблем сборки, тестов и локальной верификации. Формат — `.cursor/rules/universal/build-troubleshooting-log.mdc`.

## Open issues

| ID | Severity | Summary | Status |
|----|----------|---------|--------|
| BT-001 | high | Full `:app:testDebugUnitTest` зависает без CPU и вывода даже с `--no-daemon` и `--max-workers=1` (повтор 2026-08-05; hang локализован в `TechnicianAllowlistStoreDeltaTest`) | `open` |
| BT-005 | medium | `TelemetryPourMessageCodecTest > encodePayload anonymous plain hold allows null clientId` — `TelemetryV3CodecTest.kt:107` | `open` |
| BT-002 | medium | `TechnicianAllowlistSyncCoordinatorDisconnectTest` — `UncompletedCoroutinesError` | `open` |
| BT-003 | medium | `OfflineGrantVerifierTest` — OOM при параллельных workers | `open` |
| BT-004 | high | `assembleRelease` падает до R8 — signing env / keystore не настроены | `open` |

---

### 2026-07-27 — full verification blocked (Gradle contention, unit tests, release signing)

- **Repo:** `viwa-android` (`c:\wiva\viwa-android`)
- **Команда:** `gradlew.bat :app:testDebugUnitTest` (full); также затронуты `assembleRelease`, параллельные Gradle-запуски / `gradlew --stop`
- **Симптом:** Полная unit-test верификация не завершилась. Несколько одновременных владельцев Gradle (`gradlew`, daemon, агентные retry) и `gradlew --stop` создали contention за file locks. Один из прогонов до lock-конфликта показал:
  ```
  TechnicianAllowlistSyncCoordinatorDisconnectTest — UncompletedCoroutinesError
  OfflineGrantVerifierTest — OutOfMemoryError
  ```
  Отдельно `assembleRelease` упал до этапа R8: signing env / keystore не сконфигурированы локально.
- **Причина:** (1) Несerialized Gradle ownership на Windows — параллельные агентные/ручные сборки и `--stop` без координации. (2) Нестабильный coroutine teardown в disconnect-тесте. (3) OOM в `OfflineGrantVerifierTest` при дефолтном heap и нескольких test workers. (4) Release signing не задан в локальном окружении (переменные / keystore path).
- **Workaround / fix:** **Требуется (не workaround):** сериализовать Gradle ownership (один owner, без параллельных `gradlew` / агентных retry; `--stop` только после подтверждения завершения процессов). Стабилизировать coroutine test (`TechnicianAllowlistSyncCoordinatorDisconnectTest`). Перезапустить OOM-тест с `--max-workers=1` и достаточным `-Xmx` для test JVM. Настроить release signing безопасно (env / local.properties / CI secrets — не коммитить keystore). Затем повторить full `:app:testDebugUnitTest` и `assembleRelease`.
- **Статус:** `open`
- **Связи:** [docs/sessions/2026-07-28-overnight-ws-offline-soak.md](../sessions/2026-07-28-overnight-ws-offline-soak.md) (ws-offline soak; partial `assembleDebug` OK, full verification отложена из-за блокеров выше)

### 2026-07-30 — full unit suite hangs with a single Gradle owner

- **Repo:** `viwa-android` (`c:\wiva\viwa-android`)
- **Команда:** `gradlew.bat :app:testDebugUnitTest --no-daemon`; retry: `gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1`
- **Симптом:** оба запуска дошли до `:app:testDebugUnitTest` и зависли без новых строк. Во втором запуске через ~11 минут CPU delta Gradle-процессов оставался `0` более 30 секунд; JUnit XML не сформирован.
- **Причина:** не установлена. Параллельных Gradle owners не было; `--max-workers=1` hang не устранил.
- **Workaround / fix:** процессы конкретного запуска остановлены, затем выполнен `gradlew.bat --stop`. Для диагностики локализовать зависший test-класс через разбиение suite или JUnit launcher logging; полный suite не считать пройденным.
- **Статус:** `open`
- **Связи:** локальная верификация Android `26.07.30.02`.

### 2026-08-03 — full unit suite hang (combined drink payment verification)

- **Repo:** `viwa-android` (`c:\wiva\wiva-android`)
- **Команда:** `gradlew.bat :app:testDebugUnitTest`; первый запуск `--no-daemon`; единственный retry после остановки repo processes + `gradlew.bat --stop`: `--max-workers=1`
- **Симптом:** полный suite дважды завис на `:app:testDebugUnitTest` после >10 минут. Gradle Test Executor: CPU delta ~0, лог без прогресса, JUnit XML не сформирован. В stdout (unrelated к hang) зафиксирован failure:
  ```
  TelemetryPourMessageCodecTest > encodePayload anonymous plain hold allows null clientId
  at TelemetryV3CodecTest.kt:107
  ```
- **Причина:** hang — не установлена; `--no-daemon` и `--max-workers=1` не помогли. Telemetry failure — отдельная проблема codec-теста, не диагностирована в рамках сессии.
- **Workaround / fix:** оба запуска остановлены, фоновых Gradle/Java workers не оставлено. Для hang — локализовать зависший test-класс (разбиение suite, JUnit launcher logging). Для telemetry — отдельно починить `TelemetryPourMessageCodecTest` / `TelemetryV3CodecTest.kt:107`. Полный suite не считать пройденным.
- **Статус:** `open`
- **Связи:** [docs/sessions/2026-08-03-combined-drink-payment.md](../sessions/2026-08-03-combined-drink-payment.md); release signing — см. BT-004 (не дублировать)

### 2026-08-05 — hang полного suite локализован до одного теста

- **Repo:** `viwa-android` (`c:\wiva\wiva-android`)
- **Команда:** `gradlew.bat :app:testDebugUnitTest`; единственный retry после остановки repo processes + `gradlew.bat --stop`: `--max-workers=1`
- **Симптом:** оба прогона повисли в одной точке. `jstack` по `Gradle Test Executor` показал зависший тест:
  ```
  TechnicianAllowlistStoreDeltaTest > hello enabled policy survives delta apply and cold-start offline auth succeeds
  at TechnicianKeyTest.kt:447 (invokeSuspend :452) — waiting on condition в runTest
  ```
  Параллельно воспроизвёлся известный BT-005 (`TelemetryV3CodecTest.kt:107`).
- **Причина:** `runTest` в этом тесте ждёт условие, которое не наступает — корутина не завершается и держит test executor. Точная причина внутри теста не диагностирована.
- **Workaround / fix:** до починки прогонять точечные тесты по затронутым классам (`--tests "*DrinkList*"` и т.п.), полный suite не считать gate. Для BT-001 следующий шаг конкретный: чинить ожидание в `TechnicianKeyTest.kt:447`, а не искать зависший класс.
- **Статус:** `open`
- **Связи:** BT-005 (тот же прогон); `assembleDebug` в этом же прогоне — exit 0
