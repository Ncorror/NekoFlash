#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class CheckFailure(Exception):
    pass


def text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def current_phase(relative: str, pattern: str) -> int:
    match = re.search(pattern, text(relative))
    if match is None:
        raise CheckFailure(f"{relative}: current phase marker not found")
    return int(match.group(1))


def test_method_count() -> int:
    annotation = re.compile(r"^\s*@Test\b")
    count = 0
    for path in ROOT.rglob("*.kt"):
        if "src/test" not in path.as_posix():
            continue
        count += sum(1 for line in path.read_text(encoding="utf-8").splitlines() if annotation.search(line))
    return count


def main() -> int:
    try:
        phases = {
            "README.md": current_phase("README.md", r"Phase\s+(\d+):\s+\*\*current\s*/\s*in progress\*\*"),
            "docs/README.md": current_phase("docs/README.md", r"Current work:\s+\*\*Phase\s+(\d+)\b"),
            "docs/00_START_HERE_RU.md": current_phase(
                "docs/00_START_HERE_RU.md",
                r"Текущая работа:\s+\*\*Phase\s+(\d+)\b",
            ),
            "docs/99_RECOVERY_HANDOFF_RU.md": current_phase(
                "docs/99_RECOVERY_HANDOFF_RU.md",
                r"Текущая работа\s+—\s+\*\*Phase\s+(\d+)\b",
            ),
        }
        if len(set(phases.values())) != 1:
            details = ", ".join(f"{path}=Phase {phase}" for path, phase in phases.items())
            raise CheckFailure(f"current phase markers disagree: {details}")

        evidence = text("docs/07_TESTING_CI_HARDWARE_EVIDENCE_RU.md")
        section_numbers = [int(value) for value in re.findall(r"^## 6\.(\d+)\.", evidence, flags=re.MULTILINE)]
        if not section_numbers:
            raise CheckFailure("docs/07: no numbered evidence sections found")
        expected_sections = list(range(1, max(section_numbers) + 1))
        if section_numbers != expected_sections:
            raise CheckFailure(
                "docs/07: evidence numbering must be contiguous and ordered; "
                f"got {section_numbers}, expected {expected_sections}"
            )

        listed = re.findall(r"^- §(6\.\d+\..*)$", evidence, flags=re.MULTILINE)
        titles = re.findall(r"^## (6\.\d+\..*)$", evidence, flags=re.MULTILINE)
        if listed != titles:
            only_index = [entry for entry in listed if entry not in titles]
            only_body = [entry for entry in titles if entry not in listed]
            raise CheckFailure(
                "docs/07: the §6.x index must list exactly the sections that exist, in order; "
                f"index-only={only_index or 'none'}, body-only={only_body or 'none'}"
            )

        handoff = text("docs/99_RECOVERY_HANDOFF_RU.md")
        documented_tests = re.search(r"Тестов\s+(\d+)\s+\(`@Test` в текущем дереве\)", handoff)
        if documented_tests is None:
            raise CheckFailure("docs/99: @Test inventory marker not found")
        actual_tests = test_method_count()
        expected_tests = int(documented_tests.group(1))
        if actual_tests != expected_tests:
            raise CheckFailure(
                f"docs/99: test inventory says {expected_tests}, source tree contains {actual_tests} @Test methods"
            )

    except CheckFailure as failure:
        print(f"docs consistency: FAIL — {failure}", file=sys.stderr)
        return 1

    phase = next(iter(phases.values()))
    print(
        "docs consistency: PASS "
        f"(Phase {phase}; evidence 6.1–6.{section_numbers[-1]}, index in sync; "
        f"{actual_tests} @Test methods)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
