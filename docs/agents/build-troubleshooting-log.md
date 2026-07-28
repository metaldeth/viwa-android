# Build troubleshooting log — viwa-android

Журнал повторяющихся проблем сборки, тестов и локальной верификации. Формат — `.cursor/rules/universal/build-troubleshooting-log.mdc`.

## Open issues

| ID | Severity | Summary | Status |
|----|----------|---------|--------|
| BT-001 | high | Concurrent Gradle owners / `gradlew --stop` / file-lock contention — full `:app:testDebugUnitTest` не завершился | `open` |
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
