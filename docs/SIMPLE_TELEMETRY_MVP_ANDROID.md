# Simple Telemetry MVP — Android



См. также сервер: `c:\viwa\viwa-telemetry\AGENTS.md`, deploy runbook `c:\viwa\viwa-telemetry\docs\deployment\server.md` (SSH **`viwa-server`**), enrollment key в `c:\viwa\viwa-telemetry\.env.example`.



## Локальная конфигурация



Ключ enrollment **не коммитить**. Значение должно **совпадать** с `MACHINE_ENROLLMENT_KEY` на сервере телеметрии.



| Источник | Ключ | Пример |

|----------|------|--------|

| `local.properties` | `telemetry.enrollmentKey` | `telemetry.enrollmentKey=dev-enrollment-key-change-me` |

| переменная окружения | `VIWA_TELEMETRY_ENROLLMENT_KEY` | см. PowerShell ниже |



Шаблон: `local.properties.sample` в корне репозитория.



### PowerShell (Windows)



```powershell

# Одна сессия терминала

$env:VIWA_TELEMETRY_ENROLLMENT_KEY = "dev-enrollment-key-change-me"

.\gradlew.bat assembleDebug

```



Постоянно (пользователь): `[Environment]::SetEnvironmentVariable("VIWA_TELEMETRY_ENROLLMENT_KEY", "...", "User")` — затем новый терминал.



CMD (legacy): `set VIWA_TELEMETRY_ENROLLMENT_KEY=...`



После изменения `local.properties` или env пересоберите APK — ключ попадает в `BuildConfig.TELEMETRY_ENROLLMENT_KEY`.



## REST (machine)



- Base URL: поле `apiUrl` в `TelemetryConfig` (JsonStore `telemetryConfig`), по умолчанию `https://tl.vitamin-water.ru` (`TelemetryConfig.DEFAULT_API_URL`).

- **Активный MVP-поток:** REG register + JWT WS (без `X-Enrollment-Key`).

- **Legacy (gated):** reserve/enroll с `X-Enrollment-Key: <BuildConfig.TELEMETRY_ENROLLMENT_KEY>`.

### OTA app updates (Phase 3)

- Feature gate: WS hello `featureFlags.appUpdates=true` (fail-closed if absent/false).
- REST: `/api/v1/machines/app-updates/*` с machine JWT — см. `docs/OTA_UPDATE.md` и `viwa-telemetry/docs/contracts/app-updates.md`.
- Pinned key: `ota.signingKeyId` / `ota.signingPublicKeyPem` в `local.properties` → `BuildConfig`.
- Install scope: `firmware.update` (online-only technician key).




Компактный JSON для QR:



```json

{

  "type": "VIWA_TELEMETRY_REGISTRATION",

  "version": 1,

  "registrationKey": "REG-0123456789AB",

  "serialNumber": "VIWA-000004",

  "apiUrl": "https://tl.vitamin-water.ru"

}

```



Также принимается ручной ввод / raw scan `REG-` + 12 символов Crockford (`0-9`, `A-H`, `J-N`, `P-T`, `V-Z`; без `I`, `L`, `O`, `U`). Пример: `REG-0123456789AB`. Сканер заполняет поля в «Телеметрия → Подключение» без автоматической регистрации (баннер «QR считан»). `apiUrl` из QR принимается только по **HTTPS** и только если host+port совпадает с сохранённым адресом API.



### Register (REG)



`POST /api/v1/machines/register`



- Тело: `{ registrationKey, serialNumber, installationId, device?, app }`.

- Ответ `201`: `{ id, machineId, serialNumber, installationId, machineSecret, tokenEndpoint, wsUrl, protocolVersion, heartbeatIntervalSeconds }`.

- `machineSecret` сохраняется только в `EncryptedSharedPreferences` (`MachineSecretStore`), **не** в JsonStore и не в logcat/traffic.



### Token (JWT)



`POST /api/v1/machines/token`



- Тело: `{ serialNumber, machineSecret }`.

- Ответ: `{ accessToken, tokenType, expiresIn }`.

- JWT **не персистится**; при каждом reconnect/expiry запрашивается заново.



### Миграция VIWA-000004



- До REG: legacy `mch_…` в JsonStore используется как Bearer для WS (fallback).

- После успешного `/register` с тем же `installationId` + serial: stable secret в encrypted store, credential очищается из JSON, WS переключается на JWT.

- Повторная выдача REG-ключа на сервере **не** требует повторного сканирования на уже зарегистрированном Android — reconnect использует сохранённый secret.



### Provision (авторегистрация)



`POST /api/v1/machines/provision`



- Заголовок: `X-Factory-Provision-Key: <factory key>` — ключ обфусцирован в APK (`FactoryProvisionKey`), должен совпадать с `MACHINE_FACTORY_PROVISION_KEY` на сервере.

- Тело: `{ installationId, device?, app }` — **без** `serialNumber` и `registrationKey`.

- Ответ `201`: тот же контракт, что у `/register`, плюс опционально `registrationKey` (REG для веб-панели).

- UI: сервисное меню «Телеметрия → Подключение» — кнопка **«Авторегистрация»** (`telemetry_auto_provision`); enabled когда машина ещё не enrolled. После успеха сохраняются serial, `machineSecret`, `registrationKey`, подключается WS.

- Старый путь с REG-ключом (`POST /register`) сохранён без изменений.



### Reserve serial (legacy)



`POST /api/v1/machines/serials/reserve`



- Тело (опционально): `{ "installationId": "<uuid>" }`.

- Ответ `201`: `{ "serialNumber": "VIWA-000001", "reservationToken": "…", "expiresAt": "<ISO8601>" }`.

- TTL резерва: **15 минут**; для enroll с зарезервированным serial нужен тот же `reservationToken`.



### Enroll (legacy)



`POST /api/v1/machines/enroll`



- Тело: `installationId`, `serialNumber`, `credential` (`mch_…`), `app`, опционально `reservationToken`, `rebind`, `device`.

- Ответ `201` (новый) / `200` (идемпотентный повтор):



```json

{

  "id": "<uuid>",

  "machineId": "<uuid>",

  "serialNumber": "VIWA-000001",

  "installationId": "<uuid>"

}

```



`id` и `machineId` — один и тот же UUID (клиент читает `machineId`). Полей `wsUrl` / `wsProtocolUrl` в ответе нет.



### 409 SERIAL_ALREADY_BOUND



Nest возвращает вложенный объект в `message`:



```json

{

  "statusCode": 409,

  "message": {

    "code": "SERIAL_ALREADY_BOUND",

    "message": "Serial is already bound to another installation"

  },

  "error": "Conflict"

}

```



Клиент (`MvpTelemetryApiClient`): парсит `message.code` (Nest nested) или flat `code`; при `SERIAL_ALREADY_BOUND` (409) — `SerialAlreadyBoundException` → rebind UI. При `REBIND_NOT_ALLOWED` (403) — `RebindNotAllowedException` → баннер «MASTER/ADMIN должен разрешить rebind на 15 минут в веб-интерфейсе» (без rebind-confirm). Другие 409 (например `INSTALLATION_ALREADY_ENROLLED`) — обычная ошибка без rebind.



## WebSocket



### URL (fallback)



Порядок в `MvpTelemetryUrlResolver`:



1. `wsProtocolUrl` или `wsUrl` из ответа enroll (если сервер когда‑либо начнёт их отдавать), **кроме legacy** `ws://185.46.8.39:8315/ws`;

2. `telemetryConfig.wsUrl` из JsonStore (**legacy WS при MVP игнорируется**);

3. производный от `apiUrl`: `https://…` → `wss://…/api/v1/machines/ws`, `http://…` → `ws://…/api/v1/machines/ws`.



### Auth и протокол



- Auth: `Authorization: Bearer <JWT>` (stable secret flow) или legacy `Bearer <mch_…>` до REG.

- Конверт: `{ type, messageId, sentAt, payload [, correlationId] }`; `ack` с `correlationId`.

- Heartbeat по интервалу из `hello` (сервер по умолчанию 10 с).

- **RFC6455 ping/pong:** сервер шлёт transport PING; клиент отвечает PONG через `onWebsocketPing` (Java-WebSocket). В traffic log — sampled `MVP WS transport: PING/PONG` без секретов.

- **Phase 1 resilience (`ws-offline-resilience`):** явный FSM + `sessionGeneration` — stale inbound (hello/ack/error/cells/loyalty) от предыдущего сокета отбрасываются; dual liveness (hello timeout 15 s, heartbeat ACK watchdog, `connectionLostTimeout=18`); reconnect backoff 1/2/5/10/30 s с **full jitter**; close **4001** → bump generation + flat 60 s backoff, flush outbox только после нового hello; `ConnectivityManager.registerDefaultNetworkCallback` (validated, debounce 500 ms) → expedited reconnect через coordinator; при потере сети сокет не рвётся сразу — degraded signal для watchdog. Structured logs: `MVP WS FSM: <from> → <to> gen=N reason=…`.

- **Phase 2 outbox (`ws-offline-resilience`):** Room `machine_outbox` (DB v2) — durable `sale.report` с `idempotencyKey=saleId`; ACK-gated (socket write → `IN_FLIGHT`, server ack/error → `ACKED`/`PENDING`/`REJECTED`/`DEAD`); one-time import JsonStore `pending_sales` (marker `outbox_pending_sales_imported_v1`, source preserved); `TelemetryAckRouter` для ack/error; flush на hello/Active, каждые 30 s, validated network, enqueue; REST batch fallback (`POST /api/v1/machines/outbox/batch`) после 3 WS ACK failures или WS down + `capabilities.outboxBatch` — gated `OutboxFeatureFlags.FEATURE_OUTBOX_REST_SYNC=false` until soak. Contract: `viwa-telemetry/docs/contracts/machine-outbox.md`.



### ONLINE: сервер vs клиент



| Сторона | Когда ONLINE |

|---------|----------------|

| **Сервер (БД / dashboard)** | Сразу после успешной WS-аутентификации (Bearer + валидный credential); до client `hello`. |

| **Android (UI / ConnectionState)** | Только после входящего `hello` от сервера; до этого — `Connecting`, не `Connected`. |



## JsonStore



- `telemetryConfig.useMvpProtocol` — `true` по умолчанию.

- `machineRegistration`: `installationId`, `serialNumber`, `machineId`, `wsProtocolUrl`, `tokenEndpoint`, `authScheme`, `enrolled` — **без** `machineSecret` / JWT / plaintext REG.



## Related contracts

| Document | Purpose |
|----------|---------|
| [`wiva-telemetry/docs/contracts/registration-machine-jwt.md`](../../wiva-telemetry/docs/contracts/registration-machine-jwt.md) | REG register, machine JWT, WS envelope v1 baseline |
| [`wiva-telemetry/docs/contracts/machine-cells-inventory.md`](../../wiva-telemetry/docs/contracts/machine-cells-inventory.md) | WS v2 cell types, REST products/cells, snapshot, reconnect (C-1–C-5) |

## Legacy



При `useMvpProtocol: false` — Keycloak + topic-WS (`WivaTelemetryWebSocketManager`). Business-topics при MVP — no-op с логом.



## UI automation (Compose testTag / UiAutomator)



Сервисное меню и экран **Телеметрия → Подключение** помечены стабильными `testTag` для ADB/UIAutomator smoke. На корне `ServiceScreen` включено `testTagsAsResourceId` — теги попадают в accessibility tree как synthetic resource id (`com.viwa.android:id/<tag>`).



### Навигация до «Подключение»



| Шаг | testTag | Примечание |
|-----|---------|------------|
| Корень сервисного меню | `service_menu_root` | `testTagsAsResourceId` на ancestor |
| Группа rail «Телеметрия» | `service_group_telemetry` | `NavigationRailItem` |
| Subtab «Подключение» | `service_subtab_telemetry_connection` | `ScrollableTabRow` |
| Subtab «Адреса» (API/WS URL) | `service_subtab_telemetry_addresses` | при настройке endpoint'ов |



Пример (Python uiautomator2):



```python

d(resourceId="com.viwa.android:id/service_group_telemetry").click()

d(resourceId="com.viwa.android:id/service_subtab_telemetry_connection").click()

d(resourceId="com.viwa.android:id/telemetry_reserve_serial").click()

```



### Телеметрия → Подключение



| Элемент | testTag |
|---------|---------|
| Корень вкладки | `telemetry_connection_root` |
| Карточка статуса WS | `telemetry_connection_status_card` |
| Текст «Подключено» / «Не подключено» | `telemetry_connection_status_text` |
| Кнопка «Реконнект» | `telemetry_connection_reconnect` |
| Поле serial | `telemetry_serial_input` |
| REG-ключ | `telemetry_reg_key_input` |
| Баннер «QR считан» | `telemetry_qr_scanned_banner` |
| «Register» | `telemetry_register` |
| «Подключить WS» | `telemetry_connect_ws` |
| «Отключить WS» | `telemetry_disconnect_ws` |
| Карточка rebind 409 | `telemetry_rebind_card` |
| «Перепривязать» | `telemetry_rebind_confirm` |
| «Отмена» (rebind) | `telemetry_rebind_cancel` |
| Баннер успеха/ошибки | `telemetry_banner` |
| Индикатор «Запрос к серверу…» | `telemetry_busy` |
| regKey (legacy) | `telemetry_reg_key_input` |



### Телеметрия → Адреса



| Элемент | testTag |
|---------|---------|
| Корень вкладки | `telemetry_addresses_root` |
| API URL | `telemetry_api_url_input` |
| WebSocket URL | `telemetry_ws_url_input` |
| «Сохранить адреса» | `telemetry_save_addresses` |



Константы в коде: `ServiceMenuTestTags.kt`. Unit-тест уникальности: `ServiceMenuTestTagsTest`.


