# 2026-07-19 — legacy telemetry removal

## Done
- Соседний complex machine-cells-inventory дождались (DONE)
- Удалён legacy Shaker/Keycloak/topic-WS dual-path; runtime только MVP + cells snapshot
- Stubs: sales/subscriptions uplink, temperature setMachineInfo no-op
- assembleDebug SUCCESS после ENV_LOCK cleanup

## Decisions
- Ждали соседний чат перед удалением
- Sales/subscriptions оставлены как no-op stubs до MVP message types

## Risks
- Продажи и подписки не уходят в телеметрию
- Температура не в heartbeat

## Verification
- assembleDebug exit 0 — SUCCESS, APK `viwa-android-26.07.19.02-debug.apk`
- Unit tests telemetry — не прогонялись полноценно из-за ENV_LOCK ранее; после успешного assemble не перезапускались

## Git facts
- repo: `viwa-android` (`c:\viwa\viwa-android`)
- branch: `main` (up to date with `origin/main`)
- commit (HEAD): `79ae0123ec3d7cbd74262cd3eab42dfd02992226` — feat: показывать REG-ключ и заполнять его из QR на вкладке телеметрии (2026-07-19 16:22:26 +0500)
- working tree: изменения **не закоммичены** (46 tracked files + untracked MVP cells/inventory файлы)
- diff/stat (tracked vs HEAD): 46 files changed, 712 insertions(+), 3145 deletions(-)

## Next
- MVP sales/subscriptions messages
- temperatureProvider в heartbeat
- коммит по просьбе пользователя
