#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / 'app/src/main/res/values/strings.xml'
RUSSIAN = ROOT / 'app/src/main/res/values-ru/strings.xml'
DEFAULT_LOCALE = ROOT / 'app/src/main/res/resources.properties'
EXPECTED_DEFAULT_LOCALE = 'en'


def names(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {node.attrib['name'] for node in root if node.tag == 'string' and node.attrib.get('translatable', 'true') != 'false'}


def properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ValueError(f'missing {path.relative_to(ROOT)}')

    result: dict[str, str] = {}
    for raw in path.read_text(encoding='utf-8').splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if '=' not in line:
            raise ValueError(f'invalid property line: {raw!r}')
        key, value = (part.strip() for part in line.split('=', 1))
        if not key or key in result:
            raise ValueError(f'invalid or duplicate property key: {key!r}')
        result[key] = value
    return result


def main() -> int:
    default = names(DEFAULT)
    russian = names(RUSSIAN)
    if default != russian:
        print(f'localization: FAIL - missing_ru={sorted(default-russian)} extra_ru={sorted(russian-default)}', file=sys.stderr)
        return 1

    try:
        locale_properties = properties(DEFAULT_LOCALE)
    except ValueError as exc:
        print(f'localization: FAIL - {exc}', file=sys.stderr)
        return 1

    default_locale = locale_properties.get('unqualifiedResLocale')
    if default_locale != EXPECTED_DEFAULT_LOCALE:
        print(
            'localization: FAIL - '
            f'unqualifiedResLocale={default_locale!r}, expected {EXPECTED_DEFAULT_LOCALE!r}',
            file=sys.stderr,
        )
        return 1

    print(f'localization: PASS ({len(default)} strings EN/RU; default locale {default_locale})')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
