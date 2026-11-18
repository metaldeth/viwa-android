# task-04 — code review, круг 2/5 (DI: AqsiModule, после исправлений и Gradle cleanup)

**sessionId:** `wiva-aqsi-card-payment`  
**project:** `c:\viwa\viwa-android`  
**Дата:** 2026-05-12  
**Входные данные:** `task-04.md`, `task-04-test-report.md`, круг 1 — `task-04-review.md` (2026-05-12). Код не изменялся ревьюером.

---

## Закрытие замечаний круга 1

| Замечание круга 1 | Статус круга 2 |
|-------------------|----------------|
| `hilt-android-testing` и прочие androidTest-зависимости — выровнять под version catalog | **Закрыто:** в `gradle/libs.versions.toml` есть `hilt-android-testing`, `androidx-test-junit`, `androidx-test-runner`; в `app/build.gradle.kts` используются `libs.hilt.android.testing`, `libs.androidx.test.junit`, `libs.androidx.test.runner`. |
| Риск «только узкого» connected-запуска с `-Pclass=…` | **Закрыто по отчёту:** `gradlew.bat :app:connectedDebugAndroidTest` — OK, **3 теста** (полный suite на эмуляторе; runner `com.viwa.android.ViwaHiltTestRunner`). |
| Ошибочная строка `dagger.hilt.android.testing.HiltTestRunner` → ClassNotFound | **Закрыто:** `ViwaHiltTestRunner` наследует `AndroidJUnitRunner` и подставляет `HiltTestApplication`; `defaultConfig.testInstrumentationRunner` указывает на `com.viwa.android.ViwaHiltTestRunner`. |
| Сборки / тесты после cleanup | **Закрыто по отчёту:** `assembleDebug`, узкий unit-прогон `AqsiModuleProvidesContractTest`, полный `connectedDebugAndroidTest` — OK. |

Итог по кругу 1: блокирующих и критических хвостов нет. Опциональная проверка на живом устройстве в ТЗ не требуется; для CI достаточно зафиксированного зелёного connected на эмуляторе — **принято**.

---

## Соответствие task-04.md (повторная сверка)

| Критерий | Оценка |
|----------|--------|
| `AqsiModule`, `@InstallIn(SingletonComponent::class)` | Выполнено |
| Биндинги aQsi только в этом модуле | Выполнено (holder, `Arcus2TerminalClient`, `AqsiRepository`) |
| Singleton holder, singleton клиент, провайд репозитория | Выполнено (`@Singleton` на трёх `@Provides`) |
| Тесты: компиляция + instrumented + JVM контракт + singleton holder | Выполнено по отчёту и коду (`AqsiHiltInjectionTest`, `AqsiModuleProvidesContractTest`) |

---

## Файлы (кратко, круг 2)

### `AqsiModule.kt`

Без изменений по сути круга 1: чистая связка `ConfigRepository` + `Arcus2TerminalClient` + holder → `AqsiRepository` / `AqsiRepositoryImpl`. Нарушений ТЗ не видно.

### `AqsiModuleProvidesContractTest.kt`

По-прежнему закрывает п. **3a** task-04 (типы провайдеров без Android/Hilt). Singleton holder на JVM «вручную» по двум вызовам `provide…` не доказывает — это ожидаемо; singleton в графе покрыт instrumented-тестом. Новых рисков круг 2 не добавляет.

### `AqsiHiltInjectionTest.kt`

Соответствует п. **2** и **4** task-04: резолв `AqsiRepository`, `assertSame` для двух инжектов holder. Проверка именно `AqsiRepositoryImpl` в графе по-прежнему не обязательна для smoke — осознанный компромисс.

### `ViwaHiltTestRunner.kt`

Соответствует документации Hilt для instrumentation tests. Регресс по «несуществующему HiltTestRunner в APK» снят.

### `app/build.gradle.kts` + `gradle/libs.versions.toml`

Единый источник версий для androidTest Hilt и AndroidX Test — выполнено. `testImplementation("junit:junit:4.13.2")` по-прежнему строкой в `dependencies` — вне фокуса task-04 и не было замечанием круга 1.

---

## Новые риски (круг 2)

Не выявлены. Устаревшее предупреждение круга 1 про несогласованность каталога для `hilt-android-testing` **снято**.

**Низкий контекстный риск (без изменения оценки):** глобальный `ViwaHiltTestRunner` для всего `:app` по-прежнему означает `HiltTestApplication` для любых будущих `androidTest`; при росте дерева тестов без Hilt может потребоваться отдельная стратегия. Текущий объём — три тестовых класса, два с `@HiltAndroidTest` — приемлемо.

---

## Итог круга 2

**Критические проблемы:** нет.  
**Рекомендация:** задачу **task-04** с точки зрения DI, тестов и Gradle после cleanup можно считать **готовой к merge**; дальнейшие усиления (проверка `AqsiRepositoryImpl` в графе, отдельный runner для не-Hilt тестов) — по желанию, не блокеры.
