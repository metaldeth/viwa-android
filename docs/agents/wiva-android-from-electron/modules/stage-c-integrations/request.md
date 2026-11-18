# Запрос сессии: stage-c-integrations

**ПАРАМЕТРЫ ЭТАПА:** `4 | stage-c-integrations | C1–C4`

- **ТЗ:** `viwa-android/docs/TZ_WIVA_ANDROID_FROM_ELECTRON.md` — модуль C (MAX, СБП, Нанокасса).
- **Правила:** §2 источники (legacy Android kiosk для интеграций), §3 мок контроллера где применимо, §4.1 — вопросы владельцу при неоднозначности.
- **Ограничение ТЗ:** не объединять C1–C3 в один PR без промежуточных сборок — волны в плане: после C1 сборка OK, после C2 сборка OK, после C3 сборка OK.
- **Итог:** `gradlew.bat assembleDebug` из `viwa-android/AGENTS.md`; `summary.md` в этом каталоге.
