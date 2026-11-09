# PyIDE — мобильная IDE для Python (Kotlin + Compose + Chaquopy)

## Возможности
**Редактор:** вкладки, файлы и подпапки (создать/переименовать/удалить), подсветка,
автодополнение (jedi) и Tab, ошибки на лету (pyflakes), форматирование (autopep8),
переход к определению, undo/redo, найти/заменить, поиск по проекту, комментирование строк,
панель скобок, автоотступ.

**Запуск:** ▶ запуск, ■ стоп, интерактивный input() в консоли, терминал (REPL).

**Пакеты:** меню ⋮ → Пакеты (pip). Чисто-Python пакеты ставятся с PyPI, пакеты с C-кодом
(pandas, pillow, scipy) берутся готовыми Android-сборками из репозитория Chaquopy
(адрес в ide_backend.py, переменная CHAQUO_INDEXES). Python 3.11.

**Отладчик:** тап по номеру строки = точка останова, 🐞 запускает отладку; шаги, стек,
переменные, eval выражений.

**Git (dulwich):** init, status, diff, log, commit, clone, remote, push, pull (HTTPS + токен).

## Сборка через GitHub Actions (с телефона)
Вариант А (проще всего): в репозитории достаточно двух вещей: этот zip и файл
`.github/workflows/build.yml` (его можно создать в браузере: Add file → Create new file,
имя `.github/workflows/build.yml`, вставить содержимое из архива). Workflow сам
распакует zip, найдёт проект и соберёт APK.

Вариант Б: распакуй zip и загрузи содержимое папки PyIDE в корень репозитория через git:
    git init && git add -A && git commit -m init
    git branch -M main
    git remote add origin https://github.com/<логин>/<репо>.git
    git push -u origin main --force
(пароль при push = токен с правами repo и workflow).

Дальше: Actions → Build APK → Run workflow → внизу страницы запуска скачай артефакт PyIDE-apk.

## Сборка на ПК
Android Studio → Open → Run. Из командной строки: `gradle wrapper --gradle-version 8.9`, затем `./gradlew assembleDebug`.

## Известные ограничения
- Проект не компилировался при подготовке: при первой сборке возможны мелкие ошибки, присылай лог.
- dulwich==0.21.7 (Git) и адрес репозитория Chaquopy не проверялись на реальной сборке.
- Стоп не прерывает долгий C-вызов (time.sleep) до его завершения.
- Точки останова не сдвигаются при вставке строк выше.
