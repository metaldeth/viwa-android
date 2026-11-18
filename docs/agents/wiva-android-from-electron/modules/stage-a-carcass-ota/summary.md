# Итог complex-сессии: этап 1 — `stage-a-carcass-ota` (A1–A4)

## Статус задач ТЗ

| ID | Статус | Примечание |
|----|--------|------------|
| A1 | Выполнено | `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, ProGuard, signing release, `assembleDebug` / `assembleRelease` OK |
| A2 | Выполнено | Compose Navigation: главный экран + экран «Сервис»; переключатель «мок контроллера» в Room JsonStore (`USE_MOCK_CONTROLLER`) |
| A3 | Выполнено | `update-server/` (шаблон APK `viwa-android-*-release.apk`), `docker-compose.yml`, `docs/OTA_UPDATE.md`; проверка `version.json` — см. ниже |
| A4 | Выполнено | Порт `UpdateRepository` / `UpdateRepositoryImpl` и UI проверки/установки на экране «Сервис» (логика как в legacy Android kiosk) |

## Сборка

- Команда из `viwa-android/AGENTS.md`: **`gradlew.bat assembleDebug`** (Linux/macOS: `./gradlew assembleDebug`).

## OTA — быстрая проверка

1. Собрать release APK и положить в **`viwa-android/release/`** с именем `viwa-android-{versionName}-release.apk`.
2. `docker compose up -d update-server` из корня `viwa-android`.
3. `curl http://localhost:9082/version.json` — ожидается JSON или 404, если нет подходящего APK.

## Артефакты кода (ключевые пути)

- Приложение: `viwa-android/app/src/main/java/com/wiva/android/`
- Сервер обновлений: `viwa-android/update-server/server.js`
- Документация OTA: `viwa-android/docs/OTA_UPDATE.md`
- Конфиг AI: `viwa-android/AGENTS.md`

## Риски / follow-up

- **`signing/release.jks`:** при копировании с киоска убедиться, что политика репозитория допускает общий keystore; иначе заменить и не коммитить секреты.
- **Ручной сценарий A4 на устройстве:** сервисный экран → URL сервера (например `http://<LAN-IP>:9082`) → Сохранить → Проверить обновления → при более новой версии в `version.json` — Установить (нужен разрешённый cleartext для HTTP или HTTPS-стенд).

## Субагенты

- Реализация: **generalPurpose (fast)** — полный список созданных файлов в ответе субагента в чате оркестратора.
