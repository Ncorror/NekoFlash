# Рабочий процесс NekoFlash в Termux

## Постоянные параметры

- Репозиторий: `Ncorror/NekoFlash`.
- Каноническая постоянная ветка: `main`.
- `main` защищён GitHub branch protection и не принимает direct push.
- Любая публикация идёт через короткоживущую PR-ветку `termux/update-*`.
- После merge PR-ветка удаляется; долгоживущие feature-ветки не создаются без отдельного решения.
- `scripts/termux-publish.sh` создаёт commit, push временной ветки и Pull Request, но не запускает локальную сборку и не merge-ит PR.
- `scripts/termux-consolidate-to-main.sh` — одноразовый protected-main переход со старой feature-ветки через Pull Request.
- `scripts/termux-ci.sh` запускает или собирает GitHub Actions evidence; по умолчанию branch=`main`.
- `scripts/export-recovery-bundle.sh` создаёт self-contained ZIP с source, chat context и restore commands без `.git`/signing/build outputs.
- Force push, обход branch protection и rebase опубликованной истории запрещены.

Обычная точка старта:

```bash
cd "$HOME/NekoFlash"
git switch main
git pull --ff-only origin main
```

## Одноразовая консолидация веток

Подробный runbook: [`BRANCH_CONSOLIDATION.md`](BRANCH_CONSOLIDATION.md).

```bash
cd "$HOME/NekoFlash"
git switch feature/recovery-first-quick-flash
bash scripts/termux-consolidate-to-main.sh
```

Скрипт создаёт/reuses PR в `main`, ждёт checks и удаляет source branch только после подтверждённого merge.

## Первичная подготовка

Только для новой или сброшенной установки Termux:

```bash
bash scripts/termux-bootstrap.sh
```

После установки пакетов:

```bash
gh auth login
gh auth setup-git
git config --global user.name "YOUR_GITHUB_LOGIN"
git config --global user.email "YOUR_EMAIL"
git config --global init.defaultBranch main
```

## Публикация проверенного source ZIP

Запускать из чистого и синхронизированного `main`:

```bash
cd "$HOME/NekoFlash"
git switch main
git pull --ff-only origin main

bash scripts/termux-publish.sh \
  --source-zip "$HOME/storage/downloads/NekoFlash-source.zip" \
  --sha256 "ОЖИДАЕМЫЙ_SHA256" \
  "Описание изменения"
```

Publisher:

- требует, чтобы local `main` точно совпадал с `origin/main`;
- создаёт короткоживущую `termux/update-YYYYMMDD-HHMMSS`;
- проверяет SHA-256, структуру проекта и Gradle Wrapper;
- принимает обычный source ZIP или nested recovery bundle и импортирует только найденный `SOURCE` project root;
- импортирует source tree через `rsync`, сохраняя `.git`, `local.properties` и signing-файлы;
- создаёт обычный commit;
- отправляет только PR-ветку;
- создаёт Pull Request в protected `main`;
- сравнивает local/remote PR-branch SHA;
- не запускает Gradle, pure/JVM matrix, CI wait или merge.

Для уже сделанных локальных изменений publisher также можно запустить без `--source-zip`, но local `main` должен совпадать с `origin/main`.

## Проверка и merge Pull Request

Publisher печатает `PR_URL`.

```bash
gh pr checks --repo Ncorror/NekoFlash --watch PR_URL
```

После зелёных checks:

```bash
gh pr merge \
  --repo Ncorror/NekoFlash \
  --merge \
  --delete-branch \
  PR_URL
```

Затем синхронизировать Termux:

```bash
cd "$HOME/NekoFlash"
git switch main
git pull --ff-only origin main
git fetch origin --prune
```

Если protection требует ручное approval, выполнить его в GitHub UI. Не отключать protection.

## Gradle Wrapper и global Git ignore

`gradle/wrapper/gradle-wrapper.jar` должен быть tracked в каждой PR-публикации. Проект защищён:

1. `.gitignore` явно разрешает Wrapper JAR.
2. `termux-publish.sh` выполняет `git add -f` и проверяет staged tree.
3. GitHub Actions проверяет JAR до Gradle и включает Wrapper checksum validation.

Не использовать `ACTIONS_ALLOW_USE_UNSECURE_NODE_VERSION=true`.

## Новый запуск GitHub Actions

```bash
bash scripts/termux-ci.sh
```

По умолчанию запускается `build.yml` на `main`. Для PR source branch обычно достаточно автоматически запущенного `pull_request` workflow и `gh pr checks --watch`.

## Сбор существующего запуска

```bash
bash scripts/termux-ci.sh --run-id RUN_ID
```

APK для установки на физическое устройство:

```bash
bash scripts/termux-ci.sh --run-id RUN_ID --with-apk
```

CI evidence и APK хранятся отдельно:

```text
Download/NekoFlash-CI-<RUN_ID>/
Download/NekoFlash-CI-<RUN_ID>.zip
Download/NekoFlash-APK-<RUN_ID>/
Download/NekoFlash-APK-<RUN_ID>.zip
```

Для failed run дополнительно сохраняются `failed.log`, `compiler-errors.log`, `source-locations.log`, `error-summary.txt`.

## Почему код возврата `gh run watch` не является source-of-truth

Обрыв сети или остановка Termux может дать ненулевой код, пока workflow ещё выполняется. Source-of-truth — `status` и `conclusion` из `gh run view`. Логи скачиваются только после `status=completed`.

## Экспорт контекста

```bash
bash scripts/export-chat-context.sh
```

Скрипт объединяет актуальные `AI_START_HERE`, `PROJECT_STATE`, `ARCHITECTURE`, `USB_PROTOCOL`, `CODE_GUIDE`, release/scope, Termux workflow и changelog в `Download/NekoFlash-chat-context.txt`.

## Полный recovery bundle

```bash
cd "$HOME/NekoFlash"
python3 scripts/update-checksums.py
bash scripts/export-recovery-bundle.sh
```

Результат:

```text
Download/NekoFlash-recovery-YYYYMMDD-HHMMSS.zip
Download/NekoFlash-recovery-YYYYMMDD-HHMMSS.zip.sha256
Download/NekoFlash-reviewed-source-YYYYMMDD-HHMMSS.zip
Download/NekoFlash-reviewed-source-YYYYMMDD-HHMMSS.zip.sha256
```

Recovery ZIP содержит `SOURCE/`, `CHAT_CONTEXT/NekoFlash-chat-context.txt`, manifest и restore commands. Его загружают в новый чат и сначала просят прочитать chat context и canonical state. Companion `reviewed-source` ZIP содержит только publishable source в layout, который понимает и старая версия publisher на текущем protected `main`, и обновлённая версия. Для первого PR этого batch использовать именно `reviewed-source` ZIP; nested recovery import становится доступен после merge обновлённого publisher.

## Политика тегов

- `archive/recovery-first-quick-flash-final-2026-08-03` — recovery tag одноразовой консолидации, не release.
- Release tag создаётся только после green exact-head CI, hardware validation, signing continuity и final mainline review.
- Development baseline с `-dev` не получает release tag.
