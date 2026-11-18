# План GitLab CI/CD для viwa-android

## Цель

Нужно добиться следующего сценария:

1. При изменениях в ветке `main` запускается GitLab pipeline.
2. Pipeline собирает `release` APK.
3. После успешной сборки APK копируется в каталог `release/` на сервере OTA.
4. `update-server` автоматически начинает отдавать новый APK через `version.json`.

## Что уже готово в репозитории

- Release signing уже настроен в `app/build.gradle.kts`.
- Имя APK стандартизовано: `viwa-android-{versionName}-release.apk`.
- OTA-сервер уже реализован в `update-server/server.js`.
- Docker-конфиг уже монтирует каталог `./release:/app/release:ro`.
- Документация OTA уже есть в `docs/OTA_UPDATE.md`.
- Runner для проекта создаётся как `viwa-android-runner`.

## Общая модель деплоя

Для `viwa-android` подходит та же модель, что и для `legacy Android kiosk`:

1. GitLab runner собирает подписанный `release` APK.
2. APK сохраняется как artifact pipeline.
3. На deploy-стадии APK копируется на OTA-сервер в каталог, который читает `update-server`.
4. Проверяется, что `version.json` отдаёт новую версию.

Важно: систему деплоя можно сделать общей с `legacy Android kiosk`, но каталог релизов и URL для `viwa-android` должны быть отдельными.

## Шаг 1. Подготовить runner

На сервере уже должен быть зарегистрирован runner:

- `viwa-android-runner`

Проверки:

```bash
sudo gitlab-runner list
sudo systemctl status gitlab-runner
```

В GitLab UI у runner'а должны быть выставлены теги:

- `android`
- `wiva`

И runner не должен быть на паузе.

## Шаг 2. Подготовить окружение runner'а

На сервере runner'а должны быть установлены:

- Java 17
- Android SDK
- Gradle/gradle wrapper
- необходимые Android build-tools и platform-tools

Нужно проверить, что на runner'е вручную проходят команды:

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew assembleRelease
```

Если runner работает под Linux shell executor, лучше сразу определить постоянные пути:

- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`
- `JAVA_HOME`

## Шаг 3. Подготовить секреты и signing

Для release-сборки нужны:

- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`
- `signing/release.jks`

Варианты хранения:

1. `release.jks` уже лежит на сервере runner'а в ожидаемом пути.
2. GitLab CI variable хранит keystore в base64, а pipeline восстанавливает файл перед сборкой.
3. GitLab Secure Files, если решите хранить keystore там.

Предпочтительно не коммитить keystore и не хранить секреты в `.gitlab-ci.yml`.

## Шаг 4. Определить deploy-путь на OTA-сервере

Нужно заранее выбрать, куда именно deploy-стадия будет копировать APK.

Рекомендуемый вариант:

- отдельный каталог для `viwa-android`, например `/opt/viwa-android/release`

Почему это важно:

- `update-server` ищет только `viwa-android-*-release.apk`
- нельзя смешивать релизы разных приложений в одном общем каталоге без namespace

Если `update-server` уже запущен через `docker-compose.yml`, нужно убедиться, что он читает именно этот каталог.

## Шаг 5. Подготовить переменные GitLab CI/CD

В GitLab проекте нужно создать variables:

- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`
- `ANDROID_UPDATE_BASE_URL`
- `DEPLOY_HOST`
- `DEPLOY_USER`
- `DEPLOY_PATH`
- `SSH_PRIVATE_KEY`

Если keystore не хранится на сервере:

- `RELEASE_JKS_BASE64`

`DEPLOY_PATH` должен указывать на каталог release для `viwa-android`.

## Шаг 6. Создать `.gitlab-ci.yml`

Минимальный pipeline:

1. `test`
2. `build_release`
3. `deploy_ota`

Логика стадий:

### `test`

- запускать на merge request и `main`
- команда:

```bash
./gradlew :app:testDebugUnitTest
```

### `build_release`

- запускать на `main`
- команда:

```bash
./gradlew assembleRelease
```

- сохранять artifacts:
  - `app/build/outputs/apk/release/*.apk`
  - optional: `app/build/outputs/mapping/release/mapping.txt`

### `deploy_ota`

- запускать только после успешной `build_release`
- копировать APK на OTA-сервер
- затем делать проверку:

```bash
curl http://<ota-host>:9082/version.json
```

## Шаг 7. Определить способ копирования APK

Наиболее практичный вариант для shell runner:

1. runner собирает APK локально
2. deploy job копирует APK через `scp` или `rsync`
3. файл попадает в `DEPLOY_PATH`

Пример логики:

```bash
scp app/build/outputs/apk/release/viwa-android-*-release.apk "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_PATH/"
```

Если deploy выполняется на том же сервере, где живёт `update-server`, можно копировать просто локальным `cp` без `scp`.

## Шаг 8. Проверка после деплоя

После копирования APK нужно проверить:

1. файл появился в release-каталоге
2. `version.json` отдаёт новую `version`
3. `url` в JSON указывает на правильный APK
4. APK реально скачивается по этому URL

Минимальные проверки:

```bash
ls -la "$DEPLOY_PATH"
curl http://<ota-host>:9082/version.json
```

## Шаг 9. Правила запуска pipeline

Рекомендуемые правила:

1. На merge request:
   - только тесты и, при желании, debug build

2. На `main`:
   - `test`
   - `build_release`
   - `deploy_ota`

3. На production rollout:
   - либо auto deploy на `main`
   - либо `deploy_ota` как manual job, если хотите дополнительный контроль

Если цель именно "при изменениях `main` сразу собирать и деплоить", тогда `deploy_ota` можно сделать автоматическим.

## Шаг 10. Политика версий

Так как OTA сравнивает `versionName`, перед каждым релизом нужно увеличивать:

- `versionCode`
- `versionName`

Иначе приложение не увидит обновление, даже если APK будет новым.

## Шаг 11. Первый пробный прогон

Перед окончательным включением auto-deploy рекомендован такой порядок:

1. сделать pipeline без deploy, только test + build_release
2. убедиться, что runner стабильно собирает `assembleRelease`
3. добавить deploy в тестовый каталог
4. проверить `version.json`
5. переключить deploy на боевой каталог release

## Итоговый чеклист

- [ ] runner `viwa-android-runner` зарегистрирован и онлайн
- [ ] теги runner'а настроены в GitLab UI
- [ ] Java/Android SDK доступны на runner'е
- [ ] release signing работает в CI
- [ ] создан `.gitlab-ci.yml`
- [ ] APK сохраняется как artifact
- [ ] deploy копирует APK в каталог OTA
- [ ] `version.json` после деплоя отдаёт новую версию
- [ ] `main` запускает build и deploy автоматически

## Рекомендуемый порядок выполнения

1. Проверить, что runner вручную собирает `assembleRelease`.
2. Подготовить CI variables и keystore.
3. Определить точный `DEPLOY_PATH` на сервере.
4. Написать `.gitlab-ci.yml` без deploy.
5. Запустить pipeline и убедиться, что APK собирается.
6. Добавить deploy-копирование APK в release-каталог.
7. Добавить smoke-check через `curl /version.json`.
8. Включить авто-деплой для `main`.
