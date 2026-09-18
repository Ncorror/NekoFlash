#!/usr/bin/env python3
"""Ненарушаемое правило №1 — единственное, у которого не было гейта.

`01` §3, `03` §5.1 и `16` §2 запрещают искусственные ограничения возможностей:
профили «Новичок/Эксперт», скрытые capability tiers, allowlist/denylist команд,
product-level hard guards. Правило это главное в проекте — и до появления этого
файла оно было единственным, которое проверял только человек. Все остальные
(локализация, согласованность документов, стиль, проводка Compose, гигиена
репозитория) имеют по гейту, а это держалось на внимании.

**Что гейт доказывает.** Что в production-коде Kotlin нет имён и строк, которыми
такое ограничение обычно и заводится: список разрешённого, список запрещённого,
уровень пользователя, режим эксперта. Появиться они могут только вместе с самой
идеей, а идея — с обоснованием, которое `16` §2.2 велит проверять вслух.

**Чего гейт не доказывает, и это важнее.** Он не видит ограничения, написанного
нейтральными словами: `if (partition !in known) return` — это ровно тот запрет,
и ни одного запрещённого слова в нём нет. Такое ловит только чтение кода и
вопрос из `16` §2.2: «устройство отказало бы само?». Гейт — грубое сито, а не
доказательство; выдавать его PASS за соблюдение правила нельзя.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# Модули production-кода. Тесты не сканируются намеренно: имя `whitelist` в
# тесте, проверяющем, что списка нет, — это описание проверки, а не запрет.
SOURCES = ("app", "core", "usb", "protocol")

FORBIDDEN = {
    "allowlist": "список разрешённого — это hard guard (`01` §3)",
    "denylist": "список запрещённого — это hard guard (`01` §3)",
    "whitelist": "список разрешённого — это hard guard (`01` §3)",
    "blacklist": "список запрещённого — это hard guard (`01` §3)",
    "capabilitytier": "скрытых capability tiers не бывает (`03` §5.1)",
    "expertmode": "профилей «Новичок/Эксперт» не бывает (`01` §3)",
    "novicemode": "профилей «Новичок/Эксперт» не бывает (`01` §3)",
    "beginnermode": "профилей «Новичок/Эксперт» не бывает (`01` §3)",
    "advancedmode": "режим «для продвинутых» — это профиль под другим именем",
    "userlevel": "уровень пользователя решал бы, что ему можно",
    "unlockfeature": "возможности не «открываются» — они есть с самого начала",
}

BLOCK_OPEN = re.compile(r"/\*")
BLOCK_CLOSE = re.compile(r"\*/")


def code_lines(path: pathlib.Path) -> list[tuple[int, str]]:
    """Строки кода без комментариев.

    Комментарии выброшены не ради скорости: правило обсуждается в KDoc по всему
    дереву — `WelcomeScreen` прямо говорит, что пугалок перед «экспертными»
    инструментами не будет, — и ловить гейтом собственную формулировку запрета
    значило бы запретить о нём писать.
    """
    out: list[tuple[int, str]] = []
    inside_block = False
    for number, raw in enumerate(path.read_text(errors="replace").splitlines(), start=1):
        line = raw
        if inside_block:
            closed = BLOCK_CLOSE.search(line)
            if not closed:
                continue
            line = line[closed.end():]
            inside_block = False
        while True:
            opened = BLOCK_OPEN.search(line)
            if not opened:
                break
            closed = BLOCK_CLOSE.search(line, opened.end())
            if closed:
                line = line[: opened.start()] + line[closed.end():]
                continue
            line = line[: opened.start()]
            inside_block = True
            break
        line = re.sub(r"//.*$", "", line)
        if line.strip():
            out.append((number, line))
    return out


def scan() -> list[str]:
    problems: list[str] = []
    for module in SOURCES:
        base = ROOT / module
        if not base.exists():
            continue
        for path in sorted(base.rglob("*.kt")):
            text = str(path)
            if "/build/" in text or "/src/test/" in text or "/src/androidTest/" in text:
                continue
            for number, line in code_lines(path):
                lowered = line.lower()
                for token, why in FORBIDDEN.items():
                    if token in lowered:
                        location = path.relative_to(ROOT)
                        problems.append(f"{location}:{number}: «{token}» — {why}\n    {line.strip()}")
    return problems


def main() -> int:
    problems = scan()
    if problems:
        print("no-guards: FAIL")
        for problem in problems:
            print("  " + problem)
        print()
        print("Если это не ограничение возможностей, а что-то другое, назовите его иначе:")
        print("имя, которым заводят запрет, читается как запрет и без него.")
        return 1
    print("no-guards: PASS (имён hard guard в production-коде нет)")
    print("  Гейт грубый: ограничение, написанное нейтральными словами, он не увидит.")
    print("  Вопрос из `16` §2.2 — «устройство отказало бы само?» — по-прежнему задаётся руками.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
