#!/usr/bin/env python3
"""Открывает архивы вместо того, чтобы вспоминать их содержимое.

Правило «перед реализацией открой конкретный файл» (`docs/16` §3) до сих пор
исполнялось руками: распаковать zip во временный каталог, вспомнить имя файла,
погрепать. Это стоило прогонов — не потому, что правило плохое, а потому, что
выполнять его было дорого, и на шестом заходе оно тихо переставало выполняться
(`docs/07` §6.96).

Здесь три архива, и у них разные роли:

* **legacy** — эталон поведения. Проверен на железе, юнит-тестов не имеет
  вовсе: его доказательная база — аппаратные прогоны и их записи в `.md`.
* **a2** — дополнение. Юнит-тесты есть, и в них записаны краевые случаи, до
  которых по коду не додуматься; часть архитектуры при этом прямо помечена в
  `docs/16` как антипаттерн.
* **canon** — снимок документов, кода в нём нет.

Поэтому `find` всегда печатает **все** архивы, даже когда нашёл в первом:
выбрать лучший вариант можно только увидев оба.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ARCHIVE_DIR = ROOT / "reference" / "archives"
CACHE = ROOT / ".archives"

# Порядок значимый: эталон первым, дополнение вторым. Так же читается вывод.
ARCHIVES = (
    ("legacy", "NekoFlash-main-legacy.zip", "эталон поведения; юнит-тестов нет, доказательство — прогоны"),
    ("a2", "NekoFlash-A2-frozen.zip", "дополнение; юнит-тесты есть, архитектура местами антипаттерн"),
    ("canon", "NekoFlash-canonical-20260827.zip", "снимок документов; кода нет"),
)

CODE_SUFFIXES = (".kt", ".java", ".cpp", ".c", ".h", ".kts", ".xml", ".md", ".pro", ".txt")
# Только помеченные `@Test`: обычная `fun` в тестовом классе — помощник, а
# не проверенный случай, и считать её случаем значило бы завысить покрытие.
TEST_METHOD = re.compile(r"@Test[^\n]*\n(?:\s*@[^\n]*\n)*\s*fun\s+`?([A-Za-z0-9_ ]+)`?\s*\(")
TEST_ANNOTATION = re.compile(r"@Test\b")


def unpacked(name: str) -> pathlib.Path:
    """Каталог распакованного архива; распаковывает при первом обращении."""
    entry = next((row for row in ARCHIVES if row[0] == name), None)
    if entry is None:
        raise SystemExit(f"неизвестный архив: {name}; есть {', '.join(row[0] for row in ARCHIVES)}")
    source = ARCHIVE_DIR / entry[1]
    if not source.exists():
        raise SystemExit(f"архив не найден: {source}")
    target = CACHE / name
    stamp = target / ".unpacked"
    if stamp.exists() and stamp.read_text().strip() == str(source.stat().st_mtime_ns):
        return target
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    with zipfile.ZipFile(source) as archive:
        archive.extractall(target)
    stamp.write_text(str(source.stat().st_mtime_ns))
    return target


def selected(names: list[str] | None) -> list[str]:
    return names if names else [row[0] for row in ARCHIVES]


def ripgrep() -> str | None:
    return shutil.which("rg")


def search(root: pathlib.Path, pattern: str, context: int, ignore_case: bool) -> str:
    tool = ripgrep()
    if tool:
        command = [tool, "--color=never", "--line-number", "--with-filename", f"-C{context}"]
        if ignore_case:
            command.append("-i")
        command += [pattern, str(root)]
        done = subprocess.run(command, capture_output=True, text=True, check=False)
        return done.stdout
    # Без ripgrep — свой разбор: среда ассистента не обязана его иметь, а
    # молчаливый пустой ответ читался бы как «в архиве этого нет».
    flags = re.IGNORECASE if ignore_case else 0
    expression = re.compile(pattern, flags)
    out: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in CODE_SUFFIXES:
            continue
        try:
            lines = path.read_text(errors="replace").splitlines()
        except OSError:
            continue
        for number, line in enumerate(lines, start=1):
            if expression.search(line):
                low = max(0, number - 1 - context)
                high = min(len(lines), number + context)
                for offset in range(low, high):
                    out.append(f"{path}:{offset + 1}:{lines[offset]}")
                out.append("--")
    return "\n".join(out)


def relative(path: pathlib.Path, root: pathlib.Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def command_unpack(args: argparse.Namespace) -> int:
    for name in selected(args.archive):
        target = unpacked(name)
        files = sum(1 for path in target.rglob("*") if path.is_file())
        print(f"{name}: {target} ({files} файлов)")
    return 0


def command_find(args: argparse.Namespace) -> int:
    found_anywhere = False
    for name in selected(args.archive):
        root = unpacked(name)
        text = search(root, args.pattern, args.context, args.ignore_case)
        description = next(row[2] for row in ARCHIVES if row[0] == name)
        print(f"\n=== {name} — {description}")
        if not text.strip():
            # Пустой результат называется словами: «ничего не нашлось» и «не
            # искали» — разные факты, и молчанием их не различить.
            print("  ничего не найдено")
            continue
        found_anywhere = True
        for line in text.splitlines():
            print("  " + line.replace(str(root) + "/", ""))
    if not found_anywhere:
        print("\nНи в одном архиве не нашлось. Это основание проектировать с нуля —")
        print("и отметить в changeset'е, что источника не было.")
    return 0


def command_where(args: argparse.Namespace) -> int:
    for name in selected(args.archive):
        root = unpacked(name)
        hits: list[tuple[int, str]] = []
        expression = re.compile(args.pattern, re.IGNORECASE if args.ignore_case else 0)
        for path in sorted(root.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in CODE_SUFFIXES:
                continue
            try:
                body = path.read_text(errors="replace")
            except OSError:
                continue
            count = len(expression.findall(body))
            if count:
                hits.append((count, relative(path, root)))
        print(f"\n=== {name}")
        if not hits:
            print("  ничего не найдено")
        for count, path in sorted(hits, reverse=True)[: args.limit]:
            print(f"  {count:4d}  {path}")
    return 0


def command_show(args: argparse.Namespace) -> int:
    root = unpacked(args.archive_name)
    matches = [path for path in sorted(root.rglob("*")) if path.is_file() and args.path in str(path)]
    if not matches:
        raise SystemExit(f"в {args.archive_name} нет файла, содержащего «{args.path}»")
    if len(matches) > 1 and not args.first:
        print(f"Подходит {len(matches)} файлов — уточните путь либо возьмите --first:")
        for path in matches[:20]:
            print("  " + relative(path, root))
        return 1
    path = matches[0]
    lines = path.read_text(errors="replace").splitlines()
    start, end = 1, len(lines)
    if args.lines:
        bounds = args.lines.split(",")
        start = int(bounds[0])
        end = int(bounds[1]) if len(bounds) > 1 else len(lines)
    print(f"# {args.archive_name}: {relative(path, root)} (строки {start}–{min(end, len(lines))})")
    for number in range(start, min(end, len(lines)) + 1):
        print(f"{number:5d}  {lines[number - 1]}")
    return 0


def unit_tests(root: pathlib.Path) -> list[tuple[str, list[str]]]:
    """Юнит-тесты: файл и имена методов, помеченных `@Test`."""
    found: list[tuple[str, list[str]]] = []
    for path in sorted(root.rglob("*.kt")):
        parts = str(path)
        # Своё дерево считается без чужого и без сгенерированного: `.archives/`
        # это распакованные архивы, `build/` — вывод сборки. Отчитаться ими
        # значило бы записать в своё покрытие чужие тесты.
        if root == ROOT and ("/.archives/" in parts or "/build/" in parts):
            continue
        if "/build/" in parts:
            continue
        if "/src/test/" not in parts and "/src/androidTest/" not in parts:
            continue
        body = path.read_text(errors="replace")
        if not TEST_ANNOTATION.search(body):
            continue
        names = [name.strip() for name in TEST_METHOD.findall(body)]
        found.append((relative(path, root), names))
    return found


def evidence_docs(root: pathlib.Path) -> list[str]:
    """Документы архива, где может быть записано проверенное поведение.

    Берутся **все** `.md` из каталога `docs/`, а не только те, чьё имя звучит
    как «hardware». Фильтр по ключевым словам в имени пропускал
    `docs/evidence/POCO_X3_PRO_ALPHA10_1.md` — то есть ровно запись прогона на
    конкретном аппарате, единственное доказательство, которое у Legacy вообще
    есть. Список короткий, читается целиком, и лучше показать лишнее, чем
    спрятать то, ради чего сюда пришли.
    """
    out: list[str] = []
    for path in sorted(root.rglob("*.md")):
        if "/docs/" in str(path).replace("\\", "/"):
            out.append(relative(path, root))
    return out


def command_tests(args: argparse.Namespace) -> int:
    """Что уже проверено — и чем именно: тестом или прогоном."""
    for name in selected(args.archive):
        if name == "canon":
            continue
        root = unpacked(name)
        files = unit_tests(root)
        total = sum(len(names) for _, names in files)
        print(f"\n=== {name}: {len(files)} тестовых файлов, {total} случаев")
        for path, names in files:
            if args.pattern and args.pattern.lower() not in (path + " " + " ".join(names)).lower():
                continue
            print(f"  {path}")
            if args.verbose:
                for case in names:
                    print(f"      · {case}")
        docs = evidence_docs(root)
        if docs:
            print(f"  доказательства прогонами ({len(docs)}):")
            for path in docs:
                print(f"      · {path}")
        if not files and not docs:
            print("  ни тестов, ни записей о прогонах")

    files = unit_tests(ROOT)
    total = sum(len(names) for _, names in files)
    print(f"\n=== текущее дерево: {len(files)} тестовых файлов, {total} случаев")
    if args.pattern:
        for path, names in files:
            if args.pattern.lower() in (path + " " + " ".join(names)).lower():
                print(f"  {path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    subparsers = parser.add_subparsers(dest="command", required=True)

    unpack = subparsers.add_parser("unpack", help="распаковать архивы в .archives/")
    unpack.add_argument("archive", nargs="*", help="legacy | a2 | canon")
    unpack.set_defaults(handler=command_unpack)

    find = subparsers.add_parser("find", help="искать во ВСЕХ архивах и печатать каждый")
    find.add_argument("pattern")
    find.add_argument("-C", "--context", type=int, default=3)
    find.add_argument("-i", "--ignore-case", action="store_true")
    find.add_argument("--archive", nargs="*", help="ограничить список архивов")
    find.set_defaults(handler=command_find)

    where = subparsers.add_parser("where", help="в каких файлах это встречается и сколько раз")
    where.add_argument("pattern")
    where.add_argument("-i", "--ignore-case", action="store_true")
    where.add_argument("--limit", type=int, default=15)
    where.add_argument("--archive", nargs="*")
    where.set_defaults(handler=command_where)

    show = subparsers.add_parser("show", help="напечатать файл архива целиком или диапазоном строк")
    show.add_argument("archive_name", help="legacy | a2 | canon")
    show.add_argument("path", help="часть пути, достаточная чтобы узнать файл")
    show.add_argument("--lines", help="например 1984,2012")
    show.add_argument("--first", action="store_true", help="взять первый из подошедших")
    show.set_defaults(handler=command_show)

    tests = subparsers.add_parser("tests", help="что уже проверено: тесты архивов и записи прогонов")
    tests.add_argument("pattern", nargs="?", help="ограничить именем файла или случая")
    tests.add_argument("-v", "--verbose", action="store_true", help="печатать имена случаев")
    tests.add_argument("--archive", nargs="*")
    tests.set_defaults(handler=command_tests)

    args = parser.parse_args()
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
