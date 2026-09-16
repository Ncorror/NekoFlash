#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / 'app/src/main/res/values/strings.xml'
RUSSIAN = ROOT / 'app/src/main/res/values-ru/strings.xml'


def names(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {node.attrib['name'] for node in root if node.tag == 'string' and node.attrib.get('translatable', 'true') != 'false'}


def main() -> int:
    default = names(DEFAULT)
    russian = names(RUSSIAN)
    if default != russian:
        print(f'localization: FAIL - missing_ru={sorted(default-russian)} extra_ru={sorted(russian-default)}', file=sys.stderr)
        return 1
    print(f'localization: PASS ({len(default)} strings EN/RU)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
