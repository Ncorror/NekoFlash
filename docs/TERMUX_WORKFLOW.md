# Рабочий процесс NekoFlash в Termux

## Первичная подготовка

Из корня репозитория:

```bash
bash scripts/termux-bootstrap.sh
```

После установки пакетов требуется авторизация и Git identity:

```bash
gh auth login
gh auth setup-git
git config --global user.name "YOUR_GITHUB_LOGIN"
git config --global user.email "YOUR_EMAIL"
git config --global init.defaultBranch main
```

## Быстрая публикация без локальной сборки

Когда изменения уже находятся в рабочем дереве:

```bash
bash scripts/termux-publish.sh "Описание изменения"
```

Когда новый проверенный source ZIP скачан в Android Download:

```bash
bash scripts/termux-publish.sh \
  --source-zip "$HOME/storage/downloads/NekoFlash-source.zip" \
  --sha256 "ОЖИДАЕМЫЙ_SHA256" \
  "Описание изменения"
```

Скрипт:

- запрещает прямую публикацию в `main`;
- при переданном ZIP сначала требует чистое рабочее дерево и выполняет только `git pull --ff-only`;
- переносит source tree через `rsync`, сохраняя `.git`, локальные Android properties и signing-файлы;
- создаёт обычный commit и отправляет текущую feature-ветку без force push;
- сравнивает локальный и удалённый SHA;
- **не запускает** Gradle, pure/JVM matrix, локальную сборку или GitHub Actions.

Проверки исходников выполняются до передачи ZIP, а Android-сборка запускается отдельно на GitHub Actions. Generated Python cache (`__pycache__`, `*.pyc`, `*.pyo`) не входит в source ZIP и запрещён `.gitignore`.

## Новый запуск GitHub Actions

```bash
bash scripts/termux-ci.sh
```

Скрипт запускает `build.yml`, получает новый `RUN_ID`, опрашивает GitHub до `status=completed` и только после завершения сохраняет метаданные, полный лог и report-artifacts. По умолчанию APK не скачиваются и не входят в CI evidence ZIP.

## Сбор уже существующего запуска

```bash
bash scripts/termux-ci.sh --run-id RUN_ID
```

Новый run при этом не создаётся. Обычный режим сохраняет лёгкий evidence archive без APK.

APK нужны только для установки или аппаратного теста. Для них используется явный флаг:

```bash
bash scripts/termux-ci.sh --run-id RUN_ID --with-apk
```

При этом CI evidence остаётся отдельным `NekoFlash-CI-<RUN_ID>.zip`, а APK сохраняются отдельно в `NekoFlash-APK-<RUN_ID>.zip`.

## Результаты в Android Download

```text
Download/NekoFlash-CI-<RUN_ID>/
Download/NekoFlash-CI-<RUN_ID>.zip
```

Для успешного run внутри находятся `run-info.txt`, `run-result.json`, `jobs.tsv`, `full.log`, список доступных artifacts и папка `reports`. APK в этот архив не помещаются.

Для неуспешного run дополнительно создаются:

- `failed.log`;
- `compiler-errors.log`;
- `source-locations.log`;
- `error-summary.txt`.

## Почему скрипт не использует код возврата `gh run watch` как статус CI

Обрыв сети или завершение Termux может дать ненулевой код команды, пока workflow ещё выполняется. Поэтому source-of-truth — поля `status` и `conclusion` из `gh run view`. Логи скачиваются только после `status=completed`.

## Экспорт контекста для нового чата

```bash
bash scripts/export-chat-context.sh
```

Скрипт объединяет точку входа, tracker, Recovery-first/hardware-polish планы, sanitised hardware summary, scope, safety, Termux workflow и changelog в `Download/NekoFlash-chat-context.txt`. Generated-файл не коммитится и загружается в новый чат рядом с source ZIP.

## Политика тегов

Тег версии создаётся только после зелёного Android CI для точного commit SHA:

```bash
git tag -a v6.0.0-alpha4 -m "NekoFlash V6.0.0-alpha4"
git push origin v6.0.0-alpha4
```

Перед тегом необходимо сравнить commit из run с локальным `HEAD`. Development baseline `alpha5-dev` не является готовым release tag.
