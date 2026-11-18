# GitLab CI Runbook

Краткая инструкция по CI/CD для `viwa-android`, повторяющая рабочую схему `legacy Android kiosk`.

## Что делает pipeline

1. На коммит в `main` запускается job `build_release_apk`.
2. APK собирается через `docker build --target ci-build`, а не в workspace раннера.
3. APK копируется в локальный `release/`.
4. После выравнивания владельца файл копируется в `WIVA_OTA_RELEASE_DIR`.

## Почему схема такая

В `legacy Android kiosk` уже была проблема с правами:

- Gradle в контейнере создавал root-owned файлы в workspace.
- затем `git clean` и обычный `rm` падали с `Permission denied`.

В `viwa-android` применена та же защита:

- сборка идёт внутри Docker image, без bind-mount исходников в Gradle;
- перед и после job запускается `scripts/ci-runner-cleanup.sh`;
- после `docker cp` запускается `scripts/ci-align-release-owner.sh`;
- в GitLab задан `GIT_CLEAN_FLAGS: "-ffd"`, без `-x`.

## Что должно быть на сервере

- установлен `docker`
- установлен `gitlab-runner`
- runner работает через `shell executor`
- runner имеет теги `android` и `wiva`
- пользователь `gitlab-runner` имеет доступ к Docker

## Права на каталог OTA

Если APK копируется в `/opt/viwa-android/release`, каталог нужно подготовить заранее:

```bash
sudo mkdir -p /opt/viwa-android/release
sudo chown gitlab-runner:gitlab-runner /opt/viwa-android/release
```

После этого в GitLab Variables:

```text
WIVA_OTA_RELEASE_DIR=/opt/viwa-android/release
```

## Когда нужен sudo cleanup

Если после старых сборок или особенностей Docker всё ещё остаются root-owned файлы, включите:

```text
WIVA_CI_SUDO_CLEANUP=1
```

И добавьте `sudoers` правило:

```text
gitlab-runner ALL=(root) NOPASSWD: /usr/bin/rm, /usr/bin/find
```

Без хранения пароля root в GitLab.

## Какие переменные нужно задать

### Обязательно

| Переменная | Нужна? | Зачем |
|------------|--------|-------|
| `WIVA_OTA_RELEASE_DIR` | да | Абсолютный путь, куда pipeline копирует собранный APK для OTA-раздачи. |

### Обычно не секреты, а просто конфиг

| Переменная | Нужна? | Зачем |
|------------|--------|-------|
| `WIVA_CI_SUDO_CLEANUP` | опционально | Включает `sudo -n rm/find`, если остались проблемы с правами. |
| `WIVA_APT_HTTP_MIRROR` | опционально | Зеркало `apt`, если Docker build не может достучаться до стандартных репозиториев Ubuntu. |
| `WIVA_GRADLE_DIST_URL` | опционально | Принудительный URL архива Gradle. |
| `WIVA_GRADLE_CACHE_FILE` | опционально | Путь к локально сохранённому `gradle-*-bin.zip` на сервере. |

### Секреты подписи

Для текущей схемы, как в `legacy Android kiosk`, **обычно не обязательны**, потому что:

- `signing/release.jks` уже есть в репозитории;
- в `app/build.gradle.kts` и build-скриптах есть дефолтные значения.

Задавайте их только если хотите использовать другой keystore или другие пароли:

| Переменная | Нужна? | Зачем |
|------------|--------|-------|
| `STORE_PASSWORD` | опционально | Пароль keystore. |
| `KEY_PASSWORD` | опционально | Пароль ключа внутри keystore. |
| `KEY_ALIAS` | опционально | Alias ключа. |
| `KEYSTORE_PATH` | опционально | Путь к другому keystore внутри Docker build. |

## Минимальный рабочий набор для вашего сценария

Если всё делать так же, как в `legacy Android kiosk`, достаточно:

1. подготовить каталог OTA и права на него;
2. задать `WIVA_OTA_RELEASE_DIR`;
3. при необходимости задать `WIVA_CI_SUDO_CLEANUP=1`.

Остальные переменные нужны только при сетевых проблемах или если вы меняете подпись.
