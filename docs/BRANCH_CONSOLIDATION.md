# Консолидация Git-веток NekoFlash

Дата решения: **2026-08-03**
Репозиторий: `Ncorror/NekoFlash`
Целевая модель: **одна каноническая ветка `main` + только короткоживущие Pull Request ветки**

## Почему модель уточнена

GitHub защищает `main` и отклоняет прямой push с ошибкой `GH006: Changes must be made through a pull request`.

Это правильная safety-граница. Защиту `main` не отключать и не обходить. Поэтому:

- `main` остаётся единственной постоянной рабочей линией;
- любые изменения попадают в `main` только через Pull Request;
- временная PR-ветка удаляется сразу после merge;
- force push и rebase опубликованной истории запрещены;
- recovery tag сохраняет reviewed feature-tip до удаления старой feature-ветки.

## Подтверждённое исходное состояние

Перед защищённой консолидацией:

- source branch: `feature/recovery-first-quick-flash`;
- reviewed source SHA: `e47fc664a5b6e8b3368c366b2573fb56fcad0edd`;
- exact-head CI run: `30850881076`;
- recovery tag: `archive/recovery-first-quick-flash-final-2026-08-03`;
- `main` является предком source branch;
- direct push в `main` был отклонён GitHub branch protection до изменения remote history.

Локальный `main` мог быть fast-forward обновлён до source SHA до отказа push. Это безопасно: remote `main` не изменился, source branch и tag сохранены.

## Итоговая политика

После консолидации:

- `main` — единственная постоянная ветка;
- `scripts/termux-publish.sh` запускается из актуального `main`, создаёт короткоживущую `termux/update-*` ветку и Pull Request;
- GitHub Actions выполняется на Pull Request и повторно на push/merge в `main`;
- PR-ветка удаляется после merge;
- новые долгоживущие feature-ветки не создаются без отдельного решения maintainer;
- direct push в protected `main`, force push и отключение protection запрещены.

## Одноразовая защищённая консолидация

Запускать из source branch:

```bash
cd "$HOME/NekoFlash"
git switch feature/recovery-first-quick-flash
bash scripts/termux-consolidate-to-main.sh
```

Скрипт fail-closed:

1. требует чистое рабочее дерево;
2. проверяет local/remote source SHA;
3. проверяет, что `main` является предком source SHA;
4. требует зелёный exact-head CI;
5. создаёт или проверяет recovery tag;
6. создаёт или повторно использует Pull Request в protected `main`;
7. проверяет, что PR head равен reviewed source SHA;
8. ждёт GitHub Actions checks;
9. пытается merge через GitHub API;
10. если нужны ручное approval/merge — ничего не удаляет и выводит PR URL;
11. после merge проверяет, что reviewed source SHA является предком remote `main`;
12. проверяет Gradle Wrapper в remote `main`;
13. только после этих проверок удаляет feature-ветку;
14. синхронизирует локальный `main` и подтверждает, что постоянной remote-веткой осталась только `main`.

GitHub может создать merge commit. Поэтому итоговый `main` SHA может отличаться от source SHA, но reviewed source SHA обязан быть его предком.

## Если скрипт остановился на ручном approval

Открыть выведенный PR URL, выполнить требуемое review/approval и merge, затем повторно запустить:

```bash
cd "$HOME/NekoFlash"
git switch feature/recovery-first-quick-flash
bash scripts/termux-consolidate-to-main.sh
```

Повторный запуск безопасен: существующие tag и PR будут переиспользованы.

## CI после merge

Найти новый run на `main`:

```bash
gh run list \
  --repo Ncorror/NekoFlash \
  --workflow build.yml \
  --branch main \
  --limit 5
```

Собрать evidence:

```bash
cd "$HOME/NekoFlash"
bash scripts/termux-ci.sh --run-id RUN_ID
```

Скачать APK для Gate 0:

```bash
bash scripts/termux-ci.sh --run-id RUN_ID --with-apk
```

## Обычная публикация после консолидации

```bash
cd "$HOME/NekoFlash"
git switch main
git pull --ff-only origin main

bash scripts/termux-publish.sh \
  --source-zip "$HOME/storage/downloads/NekoFlash-source.zip" \
  --sha256 "EXPECTED_SHA256" \
  "Описание изменения"
```

Publisher создаст `termux/update-*` и PR, но не будет сам merge-ить его.

Проверка PR:

```bash
gh pr checks --repo Ncorror/NekoFlash --watch PR_URL
```

Merge после зелёных checks:

```bash
gh pr merge --repo Ncorror/NekoFlash --merge --delete-branch PR_URL
```

После merge:

```bash
git switch main
git pull --ff-only origin main
git fetch origin --prune
```

## Восстановление старой feature-линии

```bash
git fetch origin --tags
git switch -c feature/recovery-first-quick-flash \
  archive/recovery-first-quick-flash-final-2026-08-03
git push -u origin feature/recovery-first-quick-flash
```

Это аварийная процедура. Обычная работа продолжается через protected `main` и короткоживущие PR-ветки.

## Stop conditions

Не продолжать, если:

- exact source head не имеет зелёного CI;
- `main` не является предком source head;
- присутствует неизвестная долгоживущая remote-ветка;
- рабочее дерево содержит изменения;
- local и remote source SHA различаются;
- recovery tag указывает на другой commit;
- PR head отличается от reviewed source SHA;
- PR checks не прошли;
- reviewed source SHA не является предком merged `main`;
- Gradle Wrapper отсутствует в merged `main`.
