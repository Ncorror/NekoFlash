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

## Публикация проверенного дерева

```bash
bash scripts/termux-publish.sh "Описание изменения"
```

Скрипт:

- делает `git fetch`, а для уже опубликованной текущей ветки — только `git pull --ff-only`;
- обновляет `SHA256SUMS`;
- запускает canonical documentation/source/safety guards и pure/JVM matrix;
- очищает generated-каталоги;
- создаёт обычный commit;
- отправляет текущую ветку без force push;
- сравнивает локальный и удалённый SHA.

## Новый запуск GitHub Actions

```bash
bash scripts/termux-ci.sh
```

Скрипт запускает `build.yml`, получает новый `RUN_ID`, опрашивает GitHub до `status=completed` и только после завершения скачивает логи и artifacts.

## Сбор уже существующего запуска

```bash
bash scripts/termux-ci.sh --run-id 29832274659
```

Новый run при этом не создаётся.

## Результаты в Android Download

```text
Download/NekoFlash-CI-<RUN_ID>/
Download/NekoFlash-CI-<RUN_ID>.zip
```

Для успешного run внутри находятся `run-info.txt`, `run-result.json`, `jobs.tsv`, `full.log` и папка `artifacts`.

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

Скрипт объединяет каноническую точку входа, tracker, Recovery-first plan, scope, safety, Termux workflow и changelog в `Download/NekoFlash-chat-context.txt`. Generated-файл не коммитится и загружается в новый чат рядом с source ZIP.

## Политика тегов

Тег версии создаётся только после зелёного Android CI для точного commit SHA:

```bash
git tag -a v6.0.0-alpha4 -m "NekoFlash V6.0.0-alpha4"
git push origin v6.0.0-alpha4
```

Перед тегом необходимо сравнить commit из run с локальным `HEAD`. Development baseline `alpha5-dev` не является готовым release tag.
