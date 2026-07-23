#!/usr/bin/env python3
"""Lightweight static checks for NekoFlash.

This script intentionally avoids Android SDK/Gradle so it can run in CI before
network-dependent dependency resolution and in restricted environments.
"""
from __future__ import annotations

import hashlib
import re
import sys
import zipfile
import struct
import zlib
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []
WARNINGS: list[str] = []


def fail(message: str) -> None:
    ERRORS.append(message)


def warn(message: str) -> None:
    WARNINGS.append(message)


def parse_xml(path: Path) -> ET.ElementTree:
    try:
        return ET.parse(path)
    except Exception as exc:  # noqa: BLE001 - diagnostic script
        fail(f"XML parse failed: {path.relative_to(ROOT)}: {exc}")
        raise


def resource_names(path: Path) -> set[str]:
    if not path.exists():
        fail(f"Missing resource file: {path.relative_to(ROOT)}")
        return set()
    tree = parse_xml(path)
    names: set[str] = set()
    for node in tree.getroot().iter():
        if node.tag == "string" and "name" in node.attrib:
            names.add(node.attrib["name"])
    return names


def check_xml_files() -> None:
    for path in sorted((ROOT / "app/src/main/res").rglob("*.xml")):
        parse_xml(path)


def check_string_parity() -> None:
    base = resource_names(ROOT / "app/src/main/res/values/strings.xml")
    ru = resource_names(ROOT / "app/src/main/res/values-ru/strings.xml")
    missing_ru = sorted(base - ru)
    missing_base = sorted(ru - base)
    if missing_ru:
        fail("Missing in values-ru/strings.xml: " + ", ".join(missing_ru))
    if missing_base:
        fail("Missing in values/strings.xml: " + ", ".join(missing_base))
    if base and ru:
        print(f"strings parity: OK ({len(base)} keys)")


def check_kotlin_char_literals() -> None:
    """
    Ловит повреждённые символьные литералы вида  append('  с переносом строки
    внутри кавычек (escape \\n потерял слеш при копировании/распаковке).
    Такое не видно глазом, но валит компиляцию 'Incorrect character literal'.
    """
    import re, os
    bad = []
    src_dir = ROOT / "app/src/main/java"
    for root, _, files in os.walk(src_dir):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(root, fn)
            lines = open(path, encoding="utf-8").read().split("\n")
            for i, line in enumerate(lines, 1):
                # строка заканчивается одиночной кавычкой-литералом без закрытия
                if re.search(r"\('$", line.rstrip()) or re.search(r"=\s*'$", line.rstrip()):
                    bad.append(f"{os.path.relpath(path, ROOT)}:{i}")
    if bad:
        fail("Broken char literals (unclosed '): " + ", ".join(bad))
    else:
        print("kotlin char literals: OK")



def check_kotlin_literal_structure() -> None:
    """Reject raw newlines inside regular Kotlin string/char literals.

    Triple-quoted strings may span lines; ordinary "..." and '...' literals may not.
    This catches copy/patch damage that simple regex checks miss.
    """
    bad: list[str] = []
    src_dir = ROOT / "app/src/main/java"

    for path in sorted(src_dir.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        state = "normal"
        block_depth = 0
        line = 1
        start_line = 1
        i = 0
        while i < len(text):
            ch = text[i]
            nxt = text[i + 1] if i + 1 < len(text) else ""
            tri = text[i:i + 3]

            if state == "normal":
                if ch == "/" and nxt == "/":
                    state = "line_comment"
                    i += 2
                    continue
                if ch == "/" and nxt == "*":
                    state = "block_comment"
                    block_depth = 1
                    i += 2
                    continue
                if tri == '"""':
                    state = "triple"
                    i += 3
                    continue
                if ch == '"':
                    state = "string"
                    start_line = line
                    i += 1
                    continue
                if ch == "'":
                    state = "char"
                    start_line = line
                    i += 1
                    continue

            elif state == "line_comment":
                if ch == "\n":
                    state = "normal"

            elif state == "block_comment":
                if ch == "/" and nxt == "*":
                    block_depth += 1
                    i += 2
                    continue
                if ch == "*" and nxt == "/":
                    block_depth -= 1
                    i += 2
                    if block_depth == 0:
                        state = "normal"
                    continue

            elif state == "triple":
                if tri == '"""':
                    state = "normal"
                    i += 3
                    continue

            elif state in {"string", "char"}:
                if ch == "\\":
                    i += 2
                    continue
                if ch == "\n":
                    bad.append(f"{path.relative_to(ROOT)}:{start_line} raw newline in {state} literal")
                    state = "normal"
                elif (state == "string" and ch == '"') or (state == "char" and ch == "'"):
                    state = "normal"

            if ch == "\n":
                line += 1
            i += 1

    if bad:
        fail("Broken Kotlin literals:\n  " + "\n  ".join(bad))
    else:
        print("kotlin literal structure: OK")


def split_top_level_params(body: str) -> list[str]:
    parts: list[str] = []
    start = 0
    depth = 0
    in_string = False
    escaped = False
    for i, ch in enumerate(body):
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch in "<([{":
            depth += 1
        elif ch in ">)]}":
            depth = max(0, depth - 1)
        elif ch == ',' and depth == 0:
            parts.append(body[start:i])
            start = i + 1
    parts.append(body[start:])
    return parts


def check_duplicate_kotlin_constructor_params() -> None:
    """Catch duplicate primary-constructor parameter/property names."""
    duplicate_ctor_params: list[str] = []
    ctor_re = re.compile(
        r"(?:data\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\((.*?)\)\s*(?:[:{]|$)",
        re.S | re.M,
    )
    param_name_re = re.compile(
        r"(?:^|\s)(?:val|var)?\s*([A-Za-z_][A-Za-z0-9_]*)\s*:"
    )
    for kt in sorted((ROOT / "app/src/main/java").rglob("*.kt")):
        source = kt.read_text(encoding="utf-8")
        for match in ctor_re.finditer(source):
            seen: set[str] = set()
            for raw_param in split_top_level_params(match.group(2)):
                clean = re.sub(
                    r"@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?",
                    "",
                    raw_param,
                ).strip()
                name_match = param_name_re.search(clean)
                if not name_match:
                    continue
                name = name_match.group(1)
                if name in seen:
                    duplicate_ctor_params.append(
                        f"{kt.relative_to(ROOT)}: class {match.group(1)} duplicate parameter {name}"
                    )
                seen.add(name)
    if duplicate_ctor_params:
        fail(
            "duplicate Kotlin constructor parameters:\n  "
            + "\n  ".join(duplicate_ctor_params[:20])
        )
    else:
        print("kotlin constructor parameters: OK")


def check_string_values_aapt_safe() -> None:
    """
    Значение <string>, начинающееся с '?' или '@', AAPT трактует как ссылку
    на ресурс/атрибут (?attr/..., @id/...) и падает с 'resource not found'.
    Такие значения должны быть экранированы '\\?' / '\\@'.
    """
    import re
    bad = []
    for rel in ("app/src/main/res/values/strings.xml", "app/src/main/res/values-ru/strings.xml"):
        path = ROOT / rel
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        # значение строки: <string name="x">VALUE</string>
        for m in re.finditer(r'<string name="(\w+)"[^>]*>(.*?)</string>', text, re.DOTALL):
            name, value = m.group(1), m.group(2)
            if value[:1] in ("?", "@"):
                bad.append(f"{rel}: {name} starts with '{value[:1]}' (escape as '\\{value[:1]}')")
            # Неэкранированный апостроф ломает AAPT (нужно \' или строка в "...").
            unescaped = re.sub(r"\\'", "", value)
            if "'" in unescaped and not (value.startswith('"') and value.endswith('"')):
                bad.append(f"{rel}: {name} has an unescaped apostrophe (use \\' )")
    if bad:
        fail("AAPT-unsafe string values:\n  " + "\n  ".join(bad))
    else:
        print("string values AAPT-safe: OK")


def check_versions() -> None:
    build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    code = re.search(r"versionCode\s+(\d+)", build)
    name = re.search(r"versionName\s+\"([^\"]+)\"", build)
    if not code or not name:
        fail("Could not parse versionCode/versionName from app/build.gradle")
        return
    print(f"version: code={code.group(1)}, name={name.group(1)}")
    if int(code.group(1)) < 31:
        fail("versionCode must be >= 31 for NekoFlash rebrand")
    version_name = name.group(1)
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-alpha\d+(?:-dev)?)?-nekoflash", version_name):
        fail("versionName must match x.y.z[-alphaN[-dev]]-nekoflash")
    build_id = re.search(r'buildConfigField\s+"String",\s+"BUILD_ID",\s+"\\"([^+]+)\+', build)
    if not build_id or build_id.group(1) != version_name:
        fail("BuildConfig BUILD_ID prefix must match versionName")


def check_gradle_wrapper() -> None:
    wrapper_props = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    gradlew = ROOT / "gradlew"
    if not wrapper_props.exists():
        fail("Missing gradle/wrapper/gradle-wrapper.properties")
    else:
        text = wrapper_props.read_text(encoding="utf-8")
        if "gradle-8.4-bin.zip" not in text:
            fail("gradle-wrapper.properties must point to Gradle 8.4 bin distribution")
        if "distributionSha256Sum=3e1af3ae886920c3ac87f7a91f816c0c7c436f276a6eefdb3da152100fef72ae" not in text:
            fail("gradle-wrapper.properties must pin the official Gradle 8.4 bin SHA-256")
    if not gradlew.exists():
        fail("Missing gradlew")
    else:
        text = gradlew.read_text(encoding="utf-8", errors="ignore")
        if "GradleWrapperMain" not in text and "using Gradle from PATH" not in text:
            fail("gradlew must call GradleWrapperMain or provide a PATH fallback")
    if not wrapper_jar.exists():
        fail("Missing gradle/wrapper/gradle-wrapper.jar")
    else:
        expected_wrapper_sha256 = "0336f591bc0ec9aa0c9988929b93ecc916b3c1d52aed202c7381db144aa0ef15"
        actual_wrapper_sha256 = hashlib.sha256(wrapper_jar.read_bytes()).hexdigest()
        if actual_wrapper_sha256 != expected_wrapper_sha256:
            fail(
                "gradle-wrapper.jar SHA-256 mismatch: "
                f"expected {expected_wrapper_sha256}, got {actual_wrapper_sha256}"
            )
        try:
            with zipfile.ZipFile(wrapper_jar) as jar:
                if "org/gradle/wrapper/GradleWrapperMain.class" not in jar.namelist():
                    fail("gradle-wrapper.jar does not contain GradleWrapperMain")
        except zipfile.BadZipFile:
            fail("gradle-wrapper.jar is not a valid jar/zip")


def check_workflows() -> None:
    build_yml = ROOT / ".github/workflows/build.yml"
    if not build_yml.exists():
        fail(f"Missing workflow: {build_yml.relative_to(ROOT)}")
        return
    text = build_yml.read_text(encoding="utf-8")
    for token in (
        "gradle/actions/wrapper-validation@v4",
        "gradle/actions/setup-gradle@v4",
        "scripts/check_project.py",
        "scripts/check-documentation.py",
        "scripts/test-checksum-inventory.py",
        'SAFETY_LOG="$RUNNER_TEMP/safety-checks.log"',
        'trap \'cp "$SAFETY_LOG" safety-checks.log 2>/dev/null || true\' EXIT',
        "scripts/check-ab-safety.py",
        "scripts/check-usb-connectivity.py",
        "scripts/run-tests.sh",
        "./gradlew :app:lintDebug",
        "./gradlew :app:assembleDebug",
        "./gradlew :app:assembleRelease",
        "NEKOFLASH_KEYSTORE_BASE64",
        "forum-build/*.apk",
        "checksums-sha256.txt",
    ):
        if token not in text:
            fail(f"build.yml does not contain required token: {token}")



def check_layout_strings_are_resources() -> None:
    layout_dir = ROOT / "app/src/main/res/layout"
    pattern = re.compile(r"android:(text|hint)=\"([^\"]*)\"")
    for path in sorted(layout_dir.glob("*.xml")):
        text = path.read_text(encoding="utf-8")
        for attr, value in pattern.findall(text):
            if value and not value.startswith("@"):
                fail(f"Hardcoded android:{attr} in {path.relative_to(ROOT)}: {value}")


def check_fileprovider_scope() -> None:
    path = ROOT / "app/src/main/res/xml/file_paths.xml"
    if not path.exists():
        fail("Missing app/src/main/res/xml/file_paths.xml")
        return
    text = path.read_text(encoding="utf-8")
    if 'path="."' in text:
        fail("FileProvider must not expose the whole external storage with path=\".\"")
    for token in ('path="Download/NekoFlash/logs/"', 'path="Download/NekoFlash/reports/"'):
        if token not in text:
            fail(f"FileProvider missing scoped entry: {token}")



def png_dimensions(path: Path) -> tuple[int, int] | None:
    """Read PNG dimensions from the IHDR header without third-party packages."""
    try:
        data = path.read_bytes()[:24]
    except OSError as exc:
        fail(f"Could not read PNG: {path.relative_to(ROOT)}: {exc}")
        return None
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        fail(f"Invalid PNG header: {path.relative_to(ROOT)}")
        return None
    return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")




def png_alpha_bbox(path: Path) -> tuple[int, int, int, int] | None:
    """Return the non-transparent RGBA bounding box using only the stdlib.

    Launcher resources generated by this project are 8-bit, non-interlaced RGBA PNGs.
    The explicit decoder keeps the permanent guard independent from Pillow/Android SDK.
    """
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"Invalid PNG signature: {path.relative_to(ROOT)}")
        return None
    pos = 8
    width = height = bit_depth = color_type = interlace = None
    compressed = bytearray()
    while pos + 12 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        chunk_type = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _compression, _filter, interlace = struct.unpack(">IIBBBBB", chunk)
        elif chunk_type == b"IDAT":
            compressed.extend(chunk)
        elif chunk_type == b"IEND":
            break
    if None in (width, height, bit_depth, color_type, interlace):
        fail(f"Incomplete PNG metadata: {path.relative_to(ROOT)}")
        return None
    if bit_depth != 8 or color_type != 6 or interlace != 0:
        fail(f"Launcher PNG must be non-interlaced 8-bit RGBA: {path.relative_to(ROOT)}")
        return None
    raw = zlib.decompress(bytes(compressed))
    stride = width * 4
    expected = height * (stride + 1)
    if len(raw) != expected:
        fail(f"Unexpected PNG payload length: {path.relative_to(ROOT)}")
        return None
    rows: list[bytearray] = []
    cursor = 0
    previous = bytearray(stride)

    def paeth(a: int, b: int, c: int) -> int:
        estimate = a + b - c
        da = abs(estimate - a)
        db = abs(estimate - b)
        dc = abs(estimate - c)
        return a if da <= db and da <= dc else b if db <= dc else c

    for _ in range(height):
        filter_type = raw[cursor]
        cursor += 1
        encoded = raw[cursor:cursor + stride]
        cursor += stride
        row = bytearray(stride)
        for i, value in enumerate(encoded):
            left = row[i - 4] if i >= 4 else 0
            up = previous[i]
            up_left = previous[i - 4] if i >= 4 else 0
            if filter_type == 0:
                decoded = value
            elif filter_type == 1:
                decoded = (value + left) & 0xFF
            elif filter_type == 2:
                decoded = (value + up) & 0xFF
            elif filter_type == 3:
                decoded = (value + ((left + up) // 2)) & 0xFF
            elif filter_type == 4:
                decoded = (value + paeth(left, up, up_left)) & 0xFF
            else:
                fail(f"Unsupported PNG filter {filter_type}: {path.relative_to(ROOT)}")
                return None
            row[i] = decoded
        rows.append(row)
        previous = row

    xs: list[int] = []
    ys: list[int] = []
    for y, row in enumerate(rows):
        for x in range(width):
            if row[x * 4 + 3] != 0:
                xs.append(x)
                ys.append(y)
    if not xs:
        return (0, 0, 0, 0)
    return min(xs), min(ys), max(xs) + 1, max(ys) + 1


def check_android_compile_regressions() -> None:
    vm = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    vm_text = vm.read_text(encoding="utf-8")
    main_text = main.read_text(encoding="utf-8")

    if re.search(r"prepareForFlash\([^\n]*\)\s*\{", vm_text):
        fail("Android compile regression: prepareForFlash must use named onLog, not a trailing lambda")
    if "ScrollView.LayoutParams(" in main_text or "ScrollView.LayoutParams." in main_text:
        fail("Android compile regression: use FrameLayout.LayoutParams for ScrollView children")
    if "import android.widget.FrameLayout" not in main_text:
        fail("Android compile hotfix missing FrameLayout import")
    if main_text.count("FrameLayout.LayoutParams(") < 1:
        fail("Android compile hotfix missing FrameLayout child LayoutParams replacement")

    pending_models = {
        "PendingUnlockVerification": (
            "product: String",
            "serial: String?",
            "expectedUnlocked: Boolean",
            "operationLabel: String",
            "createdAtMs: Long",
        ),
        "PendingSideloadVerification": (
            "packageName: String",
            "packageSize: Long",
            "packageSha256: String?",
            "device: String?",
            "createdAtMs: Long",
        ),
    }
    for model, fields in pending_models.items():
        match = re.search(
            rf"private\s+data\s+class\s+{model}\s*\((.*?)\n\s*\)",
            vm_text,
            flags=re.S,
        )
        if not match:
            fail(f"Android compile regression: missing private data class {model}")
            continue
        body = match.group(1)
        for field in fields:
            if field not in body:
                fail(f"Android compile regression: {model} missing field {field}")

    print("Android compile regression guard: OK")


def check_launcher_identity() -> None:
    manifest = ROOT / "app/src/main/AndroidManifest.xml"
    base_strings = ROOT / "app/src/main/res/values/strings.xml"
    ru_strings = ROOT / "app/src/main/res/values-ru/strings.xml"
    source = ROOT / "docs/artwork/nekoflash_launcher_circle_cat_v6.0.0.png"
    lock = ROOT / "LAUNCHER_ARTWORK_SHA256.txt"

    manifest_text = manifest.read_text(encoding="utf-8")
    for token in (
        'android:icon="@mipmap/ic_launcher"',
        'android:roundIcon="@mipmap/ic_launcher_round"',
        'android:label="@string/app_name"',
    ):
        if token not in manifest_text:
            fail(f"Launcher identity manifest token missing: {token}")

    for strings in (base_strings, ru_strings):
        tree = parse_xml(strings)
        app_name = next((node.text for node in tree.getroot() if node.tag == "string" and node.attrib.get("name") == "app_name"), None)
        if app_name != "NekoFlash":
            fail(f"Launcher label must be exactly NekoFlash in {strings.relative_to(ROOT)}")

    if not source.is_file() or not lock.is_file():
        fail("Launcher source artwork or SHA-256 lock is missing")
        return
    expected_parts = lock.read_text(encoding="utf-8").strip().split(maxsplit=1)
    if len(expected_parts) != 2 or expected_parts[1] != source.relative_to(ROOT).as_posix():
        fail("LAUNCHER_ARTWORK_SHA256.txt has invalid format or path")
    else:
        actual = hashlib.sha256(source.read_bytes()).hexdigest()
        if actual != expected_parts[0]:
            fail("Launcher source artwork hash changed; update only with an explicit launcher-identity revision")

    expected_sizes = {
        "mdpi": (48, 108),
        "hdpi": (72, 162),
        "xhdpi": (96, 216),
        "xxhdpi": (144, 324),
        "xxxhdpi": (192, 432),
    }
    for density, (legacy_size, foreground_size) in expected_sizes.items():
        directory = ROOT / f"app/src/main/res/mipmap-{density}"
        for name, expected in (
            ("ic_launcher.png", legacy_size),
            ("ic_launcher_round.png", legacy_size),
            ("ic_launcher_foreground.png", foreground_size),
        ):
            path = directory / name
            if not path.is_file():
                fail(f"Missing launcher resource: {path.relative_to(ROOT)}")
                continue
            dimensions = png_dimensions(path)
            if dimensions != (expected, expected):
                fail(f"Wrong launcher dimensions for {path.relative_to(ROOT)}: {dimensions}, expected {(expected, expected)}")
                continue
            bbox = png_alpha_bbox(path)
            if bbox is None:
                continue
            left, top, right, bottom = bbox
            minimum_inset = max(1, expected // 32)
            if min(left, top, expected - right, expected - bottom) < minimum_inset:
                fail(f"Launcher safe-zone inset is too small for {path.relative_to(ROOT)}: bbox={bbox}, minimum={minimum_inset}")

    for adaptive in (
        ROOT / "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
        ROOT / "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
    ):
        adaptive_text = adaptive.read_text(encoding="utf-8")
        required_refs = (
            '@color/ic_launcher_background',
            '@mipmap/ic_launcher_foreground',
            '@drawable/ic_launcher_monochrome',
        )
        if any(ref not in adaptive_text for ref in required_refs):
            fail(f"Adaptive launcher references are incomplete: {adaptive.relative_to(ROOT)}")

    monochrome = ROOT / "app/src/main/res/drawable/ic_launcher_monochrome.xml"
    if not monochrome.is_file():
        fail("Monochrome launcher drawable is missing")
    else:
        monochrome_text = monochrome.read_text(encoding="utf-8")
        for token in ('android:viewportWidth="108"', 'android:viewportHeight="108"', 'android:fillType="evenOdd"'):
            if token not in monochrome_text:
                fail(f"Monochrome launcher token missing: {token}")

    if not ERRORS:
        print("NekoFlash launcher identity: OK")

def check_self_test_hooks() -> None:
    vm = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    adb = ROOT / "app/src/main/java/ru/forum/adbfastboottool/AdbProtocol.kt"
    fastboot = ROOT / "app/src/main/java/ru/forum/adbfastboottool/FastbootProtocol.kt"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    layout = ROOT / "app/src/main/res/layout/activity_main.xml"
    checks = {
        vm: ["fun runSelfTest()", "=== SELF-TEST / SMOKE TEST ==="],
        adb: ["fun runSelfTest(): Boolean", "shell_v2 exit-code probe", "sync STAT /sdcard"],
        fastboot: ["fun runSelfTest(): Boolean", "FASTBOOT SELF-TEST", "Guard check"],
        main: ["TerminalAction.SelfTest", "smoke-test", "doctor"],
    }
    for path, tokens in checks.items():
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                fail(f"Self-test hook missing in {path.relative_to(ROOT)}: {token}")


def check_reports_access() -> None:
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    vm = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    layout = ROOT / "app/src/main/res/layout/activity_main.xml"
    checks = {
        main: ["showReportsMenu", "openReportsFolder", "DocumentsContract.EXTRA_INITIAL_URI", "isOpenReportsCommand"],
        vm: ["SelfTestStatus", "SelfTestResult", "selfTestStatus", "reportsDirectory"],
        layout: ["btnReportsMenu"],
    }
    for path, tokens in checks.items():
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                fail(f"Reports access hook missing in {path.relative_to(ROOT)}: {token}")


def check_private_reports() -> None:
    sanitizer = ROOT / "app/src/main/java/ru/forum/adbfastboottool/ReportSanitizer.kt"
    vm = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    for path in (sanitizer, vm):
        if not path.exists():
            fail(f"Missing private report file: {path.relative_to(ROOT)}")
            continue
    if sanitizer.exists():
        text = sanitizer.read_text(encoding="utf-8")
        for token in ("REDACTED_SERIAL", "sanitizeText", "sanitizeDeviceStoragePaths", "sanitizeLongHexIdentifiers"):
            if token not in text:
                fail(f"ReportSanitizer missing token: {token}")
    if vm.exists():
        text = vm.read_text(encoding="utf-8")
        for token in ("selftest.v3", "Privacy mode: sanitized", "ReportSanitizer.sanitizeLines"):
            if token not in text:
                fail(f"DeviceViewModel self-test privacy hook missing: {token}")


def check_forum_report_removed() -> None:
    """Keep the out-of-scope full forum ZIP exporter from returning."""
    java_dir = ROOT / "app/src/main/java/ru/forum/adbfastboottool"
    removed_files = (
        java_dir / "ForumReportManager.kt",
        java_dir / "DiagnosticReportFormatter.kt",
        java_dir / "DiagnosticArchiveVerifier.kt",
    )
    for path in removed_files:
        if path.exists():
            fail(f"Removed full forum report exporter returned: {path.relative_to(ROOT)}")

    main = (java_dir / "MainActivity.kt").read_text(encoding="utf-8")
    vm = (java_dir / "DeviceViewModel.kt").read_text(encoding="utf-8")
    readiness = (java_dir / "DiagnosticReadiness.kt").read_text(encoding="utf-8")
    strings_en = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
    strings_ru = (ROOT / "app/src/main/res/values-ru/strings.xml").read_text(encoding="utf-8")

    forbidden_main = (
        "SelfTestForumReport",
        "createForumReport(",
        "runSelfTestForumReportFromUi(",
        "reports_forum_zip",
        "reports_selftest_forum",
        "Полный диагностический ZIP создаётся",
    )
    for token in forbidden_main:
        if token in main:
            fail(f"Removed forum-report UI/command token returned: {token}")

    for token in ("createDiagnosticZipProbe", "diagnosticZipProbePassed"):
        if token in vm:
            fail(f"Removed diagnostic ZIP probe returned in DeviceViewModel: {token}")
    if "ZIP_PROBE" in readiness:
        fail("Removed ZIP_PROBE readiness check returned")

    for text, label in ((strings_en, "values"), (strings_ru, "values-ru")):
        for token in ("reports_forum_zip", "reports_selftest_forum", "dialog_report_title", "report_created_message"):
            if token in text:
                fail(f"Removed forum-report string returned in {label}: {token}")

    if not ERRORS:
        print("full forum diagnostic ZIP removal: OK")


def check_device_viewmodel_api_refs() -> None:
    """Catch accidental removal of DeviceViewModel members used by MainActivity."""
    vm_path = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    main_path = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    if not vm_path.exists() or not main_path.exists():
        return

    vm = vm_path.read_text(encoding="utf-8")
    main = main_path.read_text(encoding="utf-8")
    declared = set(re.findall(r"\bfun\s+(\w+)\s*\(", vm))
    declared.update(re.findall(r"\b(?:val|var)\s+(\w+)\b", vm))
    referenced = set(re.findall(r"\bviewModel\.(\w+)", main))
    missing = sorted(referenced - declared)
    if missing:
        fail("MainActivity references missing DeviceViewModel members: " + ", ".join(missing))
    else:
        print(f"DeviceViewModel API refs: OK ({len(referenced)} members)")




def check_interface_release_cleanup() -> None:
    main_path = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    preflight_path = ROOT / "app/src/main/java/ru/forum/adbfastboottool/PreflightValidator.kt"
    layout_path = ROOT / "app/src/main/res/layout/activity_main.xml"
    strings_path = ROOT / "app/src/main/res/values/strings.xml"
    if not all(path.exists() for path in (main_path, preflight_path, layout_path, strings_path)):
        fail("UI cleanup files missing")
        return

    main = main_path.read_text(encoding="utf-8")
    preflight = preflight_path.read_text(encoding="utf-8")
    layout = layout_path.read_text(encoding="utf-8")

    required_main = (
        "SafetyProfile.NOVICE",
        "SafetyProfile.PRO",
        "restoreWindowState(savedInstanceState)",
        "restoringWindowState",
        "PREF_LAST_WINDOW",
        "onSaveInstanceState",
        "minimizeTerminal()",
        "btnTerminalMinimize",
    )
    for token in required_main:
        if token not in main:
            fail(f"UI hook missing: {token}")

    forbidden = (
        "SafetyProfile.STANDARD",
        "SafetyProfile.EXPERT",
        "btnSafetyStandard",
        "btnSafetyExpert",
        "btnRecoveryCheckSlot",
        "btnRecoverySwitchSlot",
        "btnRecoveryFlashVbmeta",
        "btnRebootIntoSideload",
        "pageOperation",
        "btnOperationConsole",
        "btnOperationStop",
        "tabDiagnostics",
        "tabReports",
        "btnHomeService",
        "btnHomeConsole",
        "btnRebootMenu",
        "showOperationWindow",
    )
    combined = main + "\n" + layout
    for token in forbidden:
        if token in combined:
            fail(f"Stale UI tail still present: {token}")

    for token in (
        "const val MIN_HOST_BATTERY_PERCENT = 20",
        "const val MIN_TARGET_BATTERY_PERCENT = 20",
    ):
        if token not in preflight:
            fail(f"Battery threshold missing: {token}")

    # All Kotlin R.id references must resolve to a resource id.
    android_ns = "{http://schemas.android.com/apk/res/android}"
    declared_ids: set[str] = set()
    for xml_path in (ROOT / "app/src/main/res").rglob("*.xml"):
        try:
            root = ET.parse(xml_path).getroot()
        except Exception:
            continue
        for node in root.iter():
            value = node.attrib.get(android_ns + "id")
            if value and value.startswith("@+id/"):
                declared_ids.add(value.split("/", 1)[1])
            if node.tag == "item" and node.attrib.get("type") == "id" and node.attrib.get("name"):
                declared_ids.add(node.attrib["name"])
    kotlin_text = "\n".join(path.read_text(encoding="utf-8") for path in (ROOT / "app/src/main/java").rglob("*.kt"))
    referenced_ids = set(re.findall(r"R\.id\.([A-Za-z0-9_]+)", kotlin_text))
    missing_ids = sorted(referenced_ids - declared_ids)
    if missing_ids:
        fail("Missing resource ids: " + ", ".join(missing_ids))

    # Release cleanup should not leave unreferenced string resources.
    string_defs = resource_names(strings_path)
    app_text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "app/src/main").rglob("*")
        if path.is_file() and path.suffix in {".kt", ".xml"}
    )
    string_refs = set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", app_text))
    string_refs.update(re.findall(r"@string/([A-Za-z0-9_]+)", app_text))
    unused = sorted(string_defs - string_refs)
    if unused:
        fail("Unused string resources after cleanup: " + ", ".join(unused))

    print("UI/resource cleanup: OK")


def check_welcome_artwork_unchanged() -> None:
    manifest = ROOT / "WELCOME_ARTWORK_SHA256.txt"
    if not manifest.exists():
        fail("Missing WELCOME_ARTWORK_SHA256.txt")
        return
    entries = [line.strip() for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not entries:
        fail("WELCOME_ARTWORK_SHA256.txt is empty")
        return
    for entry in entries:
        parts = entry.split(maxsplit=1)
        if len(parts) != 2:
            fail(f"Malformed welcome artwork hash entry: {entry}")
            continue
        expected, relative = parts
        path = ROOT / relative.strip()
        if not path.exists():
            fail(f"Welcome artwork missing: {relative}")
            continue
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected:
            fail(
                f"Welcome artwork changed unexpectedly: {relative}; "
                f"expected {expected}, got {actual}"
            )
    if not ERRORS:
        print("welcome artwork hash: OK")

def check_app_gradle_structure() -> None:
    path = ROOT / "app/build.gradle"
    if not path.exists():
        fail("Missing app/build.gradle")
        return
    text = path.read_text(encoding="utf-8")
    if not text.lstrip().startswith("plugins {"):
        fail("app/build.gradle must start with plugins { } before arbitrary statements")
    for token in (
        'releaseSigningReady',
        'minifyEnabled true',
        'shrinkResources true',
    ):
        if token not in text:
            fail(f"app/build.gradle missing required token: {token}")

def check_build_script() -> None:
    path = ROOT / "scripts/build-apk.sh"
    if not path.exists():
        fail("Missing scripts/build-apk.sh")
        return
    text = path.read_text(encoding="utf-8")
    for token in (
        "check_project.py",
        "check-documentation.py",
        "test-checksum-inventory.py",
        "check-ab-safety.py",
        "check-usb-connectivity.py",
        "run-tests.sh",
        ":app:lintDebug",
        ":app:assembleDebug",
        ":app:assembleRelease",
        "release-unsigned.apk",
        "checksums-sha256.txt",
    ):
        if token not in text:
            fail(f"build-apk.sh does not contain required token: {token}")



def check_master_tracker_presence() -> None:
    path = ROOT / "PROJECT_MASTER_TRACKER.md"
    if not path.exists():
        fail("Missing PROJECT_MASTER_TRACKER.md")
    text = path.read_text(encoding="utf-8")
    for token in (
        "## Текущий следующий шаг",
        "docs/AI_START_HERE.md",
        "docs/RECOVERY_FIRST_PLAN.md",
        "docs/SAFETY_MODEL.md",
        "scripts/check-documentation.py",
        "scripts/termux-ci.sh",
        "внешняя резервная копия владельца вне Git",
        "TOPBAR-001",
    ):
        if token not in text:
            fail(f"PROJECT_MASTER_TRACKER.md missing required token: {token}")



def check_mi_account_and_share_hardening() -> None:
    policy = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MiAccountSecurityPolicy.kt"
    client = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MiAccountClient.kt"
    login = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MiLoginActivity.kt"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    share = ROOT / "app/src/main/java/ru/forum/adbfastboottool/SanitizedLogShare.kt"
    render = ROOT / "app/src/main/java/ru/forum/adbfastboottool/CompactLogRenderPolicy.kt"
    manifest = ROOT / "app/src/main/AndroidManifest.xml"
    paths = ROOT / "app/src/main/res/xml/file_paths.xml"
    required_files = (policy, client, login, main, share, render, manifest, paths)
    for path in required_files:
        if not path.is_file():
            fail(f"Missing Mi Account/share/console hardening file: {path.relative_to(ROOT)}")
            return

    checks = {
        policy: (
            'ACCOUNT_ROOT = "account.xiaomi.com"',
            "resolveAllowedRedirect",
            "MiAccountCookieJar",
            "hostOnly",
            "pathMatches",
        ),
        client: (
            "MiAccountSecurityPolicy.requireAllowedAuthFlowUrl",
            "MiAccountSecurityPolicy.resolveAllowedAuthRedirect",
            "jar.headerFor(currentUrl)",
            "jar.capture(currentUrl",
            "jar.serviceEntries()",
        ),
        login: (
            "setAcceptThirdPartyCookies(webView, false)",
            "allowFileAccess = false",
            "allowContentAccess = false",
            "MIXED_CONTENT_NEVER_ALLOW",
            "onReceivedSslError",
            "destroyWebViewSafely",
            "webView.destroy()",
        ),
        main: (
            "SanitizedLogShare.create",
            "CompactLogRenderPolicy.decide",
            "lifecycleScope.launch",
            "MiAuthExchangeState.LOADING",
        ),
        share: ("ReportSanitizer.sanitizeText", "DEFAULT_MAX_SOURCE_BYTES", "cleanupExpired"),
        render: ("renderedLastLine", "previousAnchorStillPresent"),
        manifest: ('android:usesCleartextTraffic="false"', "android.webkit.WebView.EnableSafeBrowsing"),
        paths: ('name="nekoflash_sanitized_shares"', 'path="shared-logs/"'),
    }
    for path, tokens in checks.items():
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                fail(f"Mi Account/share/console hardening token missing in {path.relative_to(ROOT)}: {token}")

    raw_share = re.search(r"private fun shareLogFile\(file: File\)\s*\{(.*?)\n    \}", main.read_text(encoding="utf-8"), re.S)
    if raw_share and 'shareGenericFile(file, "text/plain"' in raw_share.group(1):
        fail("shareLogFile still exposes the raw compact log")

    tests_manifest = (ROOT / "tools/tests.manifest").read_text(encoding="utf-8")
    for module in ("mi-account-security", "mi-account-client", "sanitized-log-share", "compact-log-render"):
        if not re.search(rf"^{re.escape(module)}\t", tests_manifest, re.M):
            fail(f"Mi Account/share/console test module missing from manifest: {module}")

    if not ERRORS:
        print("Mi Account/share/console hardening: OK")



def check_termux_workflow() -> None:
    required = {
        "scripts/termux-bootstrap.sh": (
            "pkg install -y",
            "termux-setup-storage",
            "gh auth login",
        ),
        "scripts/termux-publish.sh": (
            'TARGET_BRANCH="${NEKOFLASH_BRANCH:-$CURRENT_BRANCH}"',
            "--source-zip",
            'git pull --ff-only origin "$TARGET_BRANCH"',
            "rsync -a --delete",
            "main branch is protected from direct publishing",
            'git push -u origin "$TARGET_BRANCH"',
            "REMOTE_SHA",
            "No local build or CI was started",
        ),
        "scripts/termux-ci.sh": (
            "--run-id",
            "--with-apk",
            "status,conclusion,url,headSha,headBranch,event",
            'if [ "$RUN_STATUS" != "completed" ]',
            "REPORT_ARTIFACTS",
            "APK_ARTIFACTS",
            '--name "$artifact_name"',
            "compiler-errors.log",
            'RESULT_NAME="NekoFlash-CI-$RUN_ID"',
            'APK_RESULT_NAME="NekoFlash-APK-$RUN_ID"',
            "evidence only (no APK)",
        ),
        "scripts/export-chat-context.sh": (
            "PROJECT_MASTER_TRACKER.md",
            "RECOVERY_FIRST_PLAN.md",
            "NekoFlash-chat-context.txt",
        ),
        "docs/AI_START_HERE.md": (
            "PROJECT_MASTER_TRACKER.md",
            "RECOVERY_FIRST_PLAN.md",
            "TERMUX_WORKFLOW.md",
        ),
        "docs/TERMUX_WORKFLOW.md": (
            "scripts/termux-bootstrap.sh",
            "scripts/termux-publish.sh",
            "scripts/termux-ci.sh",
            "scripts/export-chat-context.sh",
            "status=completed",
        ),
        "docs/RECOVERY_FIRST_PLAN.md": (
            "QuickFlashTarget",
            "QuickFlashPlan",
            "fail-closed",
            "TOPBAR-001",
        ),
    }
    publish_script = (ROOT / "scripts/termux-publish.sh").read_text(encoding="utf-8")
    for forbidden in ("scripts/run-tests.sh", "./gradlew", "assembleDebug", "gh workflow run"):
        if forbidden in publish_script:
            fail(f"Push-only Termux publisher contains forbidden build/CI command: {forbidden}")

    ci_script = (ROOT / "scripts/termux-ci.sh").read_text(encoding="utf-8")
    if re.search(r"gh run download \"?\$RUN_ID\"?\s*\\?\s*--repo[^\n]+\s*\\?\s*-D \"?\$LOCAL_RESULT_DIR/artifacts", ci_script):
        fail("Termux CI collector must not download every artifact into the evidence archive")
    if 'endswith("-reports")' not in ci_script or 'endswith("-apk")' not in ci_script:
        fail("Termux CI collector must select report and APK artifacts explicitly")

    for rel, tokens in required.items():
        path = ROOT / rel
        if not path.is_file():
            fail(f"Missing repository continuity file: {rel}")
            continue
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                fail(f"Repository continuity token missing in {rel}: {token}")

    obsolete = ROOT / "scripts/termux-publish-both-branches.sh"
    if obsolete.exists():
        fail("Obsolete dual-branch Termux publisher must not return")

    if not ERRORS:
        print("Termux workflow and new-chat continuity: OK")

def check_quick_flash_plan_slice() -> None:
    model = ROOT / "app/src/main/java/ru/forum/adbfastboottool/QuickFlashPlan.kt"
    test = ROOT / "tools/quick-flash-plan-test/ru/forum/adbfastboottool/QuickFlashPlanTest.kt"
    manifest = ROOT / "tools/tests.manifest"
    for path in (model, test, manifest):
        if not path.is_file():
            fail(f"Missing FLASH-001 Slice A file: {path.relative_to(ROOT)}")
            return

    model_text = model.read_text(encoding="utf-8")
    required = (
        "enum class QuickFlashTarget",
        "data class QuickFlashCandidate",
        "data class QuickFlashPlan",
        "object QuickFlashPlanValidator",
        "object QuickFlashPlanCodec",
        "TARGET_AMBIGUOUS",
        "CANDIDATE_NOT_CONFIRMED",
        "MANUAL_CONFIRMATION_REQUIRED",
        'listOf("flash", partitionName)',
    )
    for token in required:
        if token not in model_text:
            fail(f"FLASH-001 Slice A token missing: {token}")

    forbidden_imports = ("android.", "androidx.")
    if any(token in model_text for token in forbidden_imports):
        fail("FLASH-001 Slice A model must remain independent from Android UI/framework")

    manifest_text = manifest.read_text(encoding="utf-8")
    if not re.search(r"^quick-flash-plan\t", manifest_text, re.M):
        fail("Quick Flash plan pure test module missing from manifest")

    if not ERRORS:
        print("Quick Flash Slice A plan model: OK")



def check_quick_flash_topology_slice() -> None:
    builder = ROOT / "app/src/main/java/ru/forum/adbfastboottool/QuickFlashTopologyCandidateBuilder.kt"
    test = ROOT / "tools/quick-flash-topology-test/ru/forum/adbfastboottool/QuickFlashTopologyCandidateBuilderTest.kt"
    planner = ROOT / "app/src/main/java/ru/forum/adbfastboottool/FastbootPartitionProbePlanner.kt"
    manifest = ROOT / "tools/tests.manifest"
    gitignore = ROOT / ".gitignore"
    for path in (builder, test, planner, manifest, gitignore):
        if not path.is_file():
            fail(f"Missing FLASH-001 Slice B file: {path.relative_to(ROOT)}")
            return

    builder_text = builder.read_text(encoding="utf-8")
    required = (
        "object QuickFlashTopologyCandidateBuilder",
        "FastbootPartitionInventory.from",
        "FastbootPartitionProbePlanner.plan",
        "FastbootSlotResolver.resolve",
        "PartitionNameResolver.resolve",
        "SLOT_TOPOLOGY_UNKNOWN",
        "SESSION_BROKEN",
        "IMAGE_ARCHIVE_REQUIRES_SIDELOAD",
        "MANUAL_PARTITION_REQUIRES_EXPERT_MODE",
        "inventory.topology != FastbootPartitionInventory.SlotTopology.UNKNOWN",
        "data class InventoryRequest",
        "fun buildFromInventory",
    )
    for token in required:
        if token not in builder_text:
            fail(f"FLASH-001 Slice B token missing: {token}")

    if any(token in builder_text for token in ("android.", "androidx.")):
        fail("FLASH-001 Slice B builder must remain independent from Android UI/framework")

    planner_text = planner.read_text(encoding="utf-8")
    if "discoveryPartitions: List<String> = emptyList()" not in planner_text:
        fail("Slice B bounded discovery partitions are missing from probe planner")
    if "val limited = sorted.take(maxQueries)" not in planner_text:
        fail("Slice B probe planner must remain bounded by maxQueries")

    manifest_text = manifest.read_text(encoding="utf-8")
    if not re.search(r"^quick-flash-topology\t", manifest_text, re.M):
        fail("Quick Flash topology pure test module missing from manifest")

    gitignore_text = gitignore.read_text(encoding="utf-8")
    if "__pycache__/" not in gitignore_text or "*.py[cod]" not in gitignore_text:
        fail("Generated Python cache must stay excluded from source control")
    checksum_policy = (ROOT / "scripts/checksum_inventory.py").read_text(encoding="utf-8")
    if '"__pycache__"' not in checksum_policy:
        fail("Generated Python cache must stay excluded from checksum inventory")

    if not ERRORS:
        print("Quick Flash Slice B topology builder: OK")

def check_quick_flash_ui_slice() -> None:
    policy = ROOT / "app/src/main/java/ru/forum/adbfastboottool/QuickFlashUiPolicy.kt"
    test = ROOT / "tools/quick-flash-ui-test/ru/forum/adbfastboottool/QuickFlashUiPolicyTest.kt"
    layout = ROOT / "app/src/main/res/layout/activity_main.xml"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    manifest = ROOT / "tools/tests.manifest"
    for path in (policy, test, layout, main, manifest):
        if not path.is_file():
            fail(f"Missing FLASH-001 Slice C file: {path.relative_to(ROOT)}")
            return

    policy_text = policy.read_text(encoding="utf-8")
    required_policy = (
        "object QuickFlashUiPolicy",
        "QuickFlashTarget.RECOVERY",
        "QuickFlashTarget.BOOT",
        "QuickFlashTarget.INIT_BOOT",
        "QuickFlashTarget.VENDOR_BOOT",
        "QuickFlashTarget.DTBO",
        "QuickFlashTarget.VBMETA",
        "QuickFlashTarget.VENDOR_KERNEL_BOOT",
        "QuickFlashTarget.MANUAL",
        "legacyQueueVisible: Boolean = false",
    )
    for token in required_policy:
        if token not in policy_text:
            fail(f"FLASH-001 Slice C policy token missing: {token}")
    if any(token in policy_text for token in ("android.", "androidx.")):
        fail("FLASH-001 Slice C UI policy must remain pure")

    layout_text = layout.read_text(encoding="utf-8")
    layout_tokens = (
        '@+id/cardQuickFlashRecoveryFirst',
        '@+id/btnFlashRecovery',
        '@+id/btnFlashBoot',
        '@+id/btnFlashInitBoot',
        '@+id/btnFlashVendorBoot',
        '@+id/switchQuickFlashExpert',
        '@+id/containerQuickFlashExpertTargets',
        '@+id/btnFlashDtbo',
        '@+id/btnFlashVbmeta',
        '@+id/btnFlashVendorKernelBoot',
        '@+id/btnFlashManual',
        '@+id/legacyFlashQueueCard',
    )
    for token in layout_tokens:
        if token not in layout_text:
            fail(f"FLASH-001 Slice C layout token missing: {token}")
    if layout_text.index('@+id/btnFlashRecovery') > layout_text.index('@+id/btnFlashBoot'):
        fail("Recovery must remain the first primary Quick Flash target")
    expert_match = re.search(
        r'android:id="@\+id/containerQuickFlashExpertTargets"[\s\S]{0,500}?android:visibility="gone"',
        layout_text,
    )
    if not expert_match:
        fail("Expert Quick Flash targets must be hidden by default")
    queue_match = re.search(
        r'android:id="@\+id/legacyFlashQueueCard"[\s\S]{0,300}?android:visibility="gone"',
        layout_text,
    )
    if not queue_match:
        fail("Legacy multi-flash queue must remain hidden in Recovery-first UI")
    if layout_text.index('@+id/cardQuickFlashRecoveryFirst') > layout_text.index('@+id/btnFastbootDataSelfTest'):
        fail("Recovery-first card must appear before diagnostic-only Fastboot DATA tools")

    main_text = main.read_text(encoding="utf-8")
    main_tokens = (
        "QuickFlashUiPolicy.isVisible",
        "QuickFlashTopologyCandidateBuilder.buildFromInventory",
        "QuickFlashPlanValidator.validate",
        "selectedPartitionName = candidate.partitionName",
        "viewModel.runConfirmedQuickFlash(",
        "currentTransportSessionId() != inventorySessionId",
        "expectedSessionId = inventorySessionId",
        "currentTransportSessionId() != expectedSessionId",
        "currentTransportSessionId() != plan.deviceSessionId",
        "showManualQuickFlashTargetDialog",
    )
    for token in main_tokens:
        if token not in main_text:
            fail(f"FLASH-001 Slice C MainActivity token missing: {token}")
    if "fun flashPartBtn(" in main_text:
        fail("Legacy target-first Quick Flash button flow must not return")
    if 'FastbootSlotResolver.RequestedSlot.BOTH' in main_text[main_text.find("private fun startQuickFlashTargetFlow"):main_text.find("private fun showFlashConfirmation")]:
        fail("Recovery-first UI must not expose a hidden both-slots mutation")

    manifest_text = manifest.read_text(encoding="utf-8")
    if not re.search(r"^quick-flash-ui\t", manifest_text, re.M):
        fail("Quick Flash UI pure test module missing from manifest")

    if not ERRORS:
        print("Quick Flash Slice C Recovery-first UI: OK")



def check_quick_flash_mutation_gate_slice() -> None:
    gate = ROOT / "app/src/main/java/ru/forum/adbfastboottool/QuickFlashMutationGate.kt"
    test = ROOT / "tools/quick-flash-mutation-gate-test/ru/forum/adbfastboottool/QuickFlashMutationGateTest.kt"
    viewmodel = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    manifest = ROOT / "tools/tests.manifest"
    for path in (gate, test, viewmodel, main, manifest):
        if not path.is_file():
            fail(f"Missing FLASH-001 Slice D file: {path.relative_to(ROOT)}")
            return

    gate_text = gate.read_text(encoding="utf-8")
    required_gate = (
        "object QuickFlashMutationGate",
        "data class ConfirmationTicket",
        "confirmationAvailable",
        "CONFIRMATION_ALREADY_CONSUMED",
        "SESSION_CHANGED",
        "IMAGE_SHA256_CHANGED",
        "TARGET_AMBIGUOUS",
        "commandCount = 1",
        "retryAllowed = false",
        "class QuickFlashConfirmationRegistry",
    )
    for token in required_gate:
        if token not in gate_text:
            fail(f"FLASH-001 Slice D gate token missing: {token}")
    if any(token in gate_text for token in ("android.", "androidx.")):
        fail("FLASH-001 Slice D mutation gate must remain pure")

    vm_text = viewmodel.read_text(encoding="utf-8")
    vm_tokens = (
        "fun runConfirmedQuickFlash(",
        "quickFlashConfirmationRegistry.consume",
        "QuickFlashMutationGate.evaluate",
        "computeFastbootArtifactId(canonicalFile)",
        "QuickFlashTopologyCandidateBuilder.buildFromInventory",
        "proto.flashPartitionDetailed(authorization.partitionName, activePrepared.stagedFile)",
        "confirmed quick flash finished",
    )
    for token in vm_tokens:
        if token not in vm_text:
            fail(f"FLASH-001 Slice D DeviceViewModel token missing: {token}")
    body = vm_text.split("fun runConfirmedQuickFlash(", 1)[-1].split("fun runFlashTarget(", 1)[0]
    if body.count("flashPartitionDetailed(") != 1:
        fail("Slice D must contain exactly one flashPartitionDetailed call")
    if body.count("proto.flashPartitionDetailed(") != 1:
        fail("Slice D mutation body must not retry or duplicate the flash command")

    main_text = main.read_text(encoding="utf-8")
    if "QuickFlashMutationGate.issueConfirmation" not in main_text or "viewModel.runConfirmedQuickFlash(" not in main_text:
        fail("Slice D confirmation ticket is not wired from MainActivity to DeviceViewModel")
    if "viewModel.runFlash(plan.partitionName, file)" in main_text:
        fail("Recovery-first UI must not bypass Slice D through the legacy runFlash path")

    manifest_text = manifest.read_text(encoding="utf-8")
    if not re.search(r"^quick-flash-mutation-gate\t", manifest_text, re.M):
        fail("Quick Flash mutation gate pure test module missing from manifest")

    if not ERRORS:
        print("Quick Flash Slice D one-shot mutation gate: OK")

def check_flash_operation_draft_state() -> None:
    draft = ROOT / "app/src/main/java/ru/forum/adbfastboottool/FlashOperationDraft.kt"
    viewmodel = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    gradle = ROOT / "app/build.gradle"
    for path in (draft, viewmodel, main, gradle):
        if not path.is_file():
            fail(f"Missing STATE-001 file: {path.relative_to(ROOT)}")
            return

    checks = {
        draft: (
            "data class FlashOperationDraft",
            "sourceUri: String",
            "expectedSizeBytes: Long",
            "expectedSha256: String",
            "NEEDS_REVALIDATION",
            "FlashOperationDraftCodec",
            "verifyAll",
        ),
        viewmodel: (
            "SavedStateHandle",
            "flashOperationDraft",
            "SAVED_FLASH_QUEUE_DRAFT",
            "revalidateFlashQueueDraft",
            "executeFlashQueueDraftAfterConfirmation",
            "FlashOperationDraftCodec.encode",
        ),
        main: (
            "viewModel.flashOperationDraft.observe",
            "viewModel.addFlashQueueFile",
            "viewModel.executeFlashQueueDraftAfterConfirmation",
        ),
        gradle: ("lifecycle-viewmodel-savedstate",),
    }
    for path, tokens in checks.items():
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                fail(f"Quick Flash lifecycle token missing in {path.relative_to(ROOT)}: {token}")

    main_text = main.read_text(encoding="utf-8")
    if re.search(r"private\s+val\s+flashQueue\s*=", main_text):
        fail("Quick Flash lifecycle regression: MainActivity owns a raw flashQueue field")

    tests_manifest = (ROOT / "tools/tests.manifest").read_text(encoding="utf-8")
    if not re.search(r"^flash-operation-draft\t", tests_manifest, re.M):
        fail("Quick Flash lifecycle test module missing from manifest: flash-operation-draft")

    if not ERRORS:
        print("Quick Flash lifecycle draft: OK")



def check_v6_scope_reset() -> None:
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    viewmodel = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    layout = ROOT / "app/src/main/res/layout/activity_main.xml"
    manifest = ROOT / "tools/tests.manifest"
    for path in (main, viewmodel, layout, manifest):
        if not path.is_file():
            fail(f"Missing V6 scope file: {path.relative_to(ROOT)}")
            return

    forbidden_paths = (
        "app/src/main/java/ru/forum/adbfastboottool/XiaomiFastbootRomManager.kt",
        "app/src/main/java/ru/forum/adbfastboottool/MiFlashWorkflow.kt",
        "app/src/main/java/ru/forum/adbfastboottool/MiFlashLifecycle.kt",
        "app/src/main/java/ru/forum/adbfastboottool/MiFlashMutationPreflight.kt",
        "app/src/main/java/ru/forum/adbfastboottool/MiFlashMutationLifecycleAdapter.kt",
        "app/src/main/java/ru/forum/adbfastboottool/MiFlashFirstOutputAtomicity.kt",
        "app/src/main/java/ru/forum/adbfastboottool/MiFlashSameCriticalSection.kt",
    )
    for rel in forbidden_paths:
        if (ROOT / rel).exists():
            fail(f"V6 scope regression: removed Mi Flash file returned: {rel}")

    removed_legacy_paths = (
        "app/src/main/java/ru/forum/adbfastboottool/DeviceProfileManager.kt",
        "app/src/main/java/ru/forum/adbfastboottool/PartitionInventoryHistory.kt",
        "docs/history",
        "validation/logs",
        "validation/journal.jsonl",
        "scripts/build-journal.py",
        "scripts/test_build_journal.py",
        "scripts/termux-publish-both-branches.sh",
    )
    for rel in removed_legacy_paths:
        if (ROOT / rel).exists():
            fail(f"V6 audit regression: removed legacy path returned: {rel}")

    production_text = "\n".join((main.read_text(encoding="utf-8"), viewmodel.read_text(encoding="utf-8"), layout.read_text(encoding="utf-8")))
    for token in ("MiFlash", "XiaomiFastbootRomManager", "btnXiaomiRom", "layout_xiaomi_rom"):
        if token in production_text:
            fail(f"V6 scope regression: Mi Flash production token remains: {token}")
    for token in ("pageDiagnostics", "DeviceProfileManager", "PartitionInventoryHistory"):
        if token in production_text:
            fail(f"V6 audit regression: removed legacy production token remains: {token}")

    main_text = main.read_text(encoding="utf-8")
    layout_text = layout.read_text(encoding="utf-8")
    for token in (
        "viewModel.connectionState.observe(this)",
        "viewModel.connectionInfo.observe(this)",
        "viewModel.fastbootDiagnostics.observe(this)",
        "viewModel.adbPeerMode.observe(this)",
        "refreshConnectionStatusLabel()",
        "updateDeviceOverview()",
    ):
        if token not in main_text:
            fail(f"TOPBAR-001 functional binding missing: {token}")
    for token in ("@+id/tvStatus", "@+id/tvOtgStatus"):
        if token not in layout_text:
            fail(f"TOPBAR-001 layout identity missing: {token}")

    # HOMEINFO-001: the home information panel is a protected functional component.
    for token in (
        "@+id/cardHomeDeviceInfo",
        "@+id/tvDeviceModeValue",
        "@+id/tvDeviceProductValue",
        "@+id/tvDeviceSlotValue",
        "@+id/tvDeviceUnlockedValue",
        "@+id/tvDeviceMaxDownloadValue",
        "@+id/tvDeviceWorkspaceValue",
        "@+id/btnHomeRefreshData",
        "@+id/btnHomePartitions",
        "@+id/btnHomeOpenWorkspace",
        "@+id/btnHomeCopyWorkspace",
    ):
        if token not in layout_text:
            fail(f"HOMEINFO-001 layout identity missing: {token}")
    for token in (
        "updateDeviceOverview()",
        "workspaceDisplayPath()",
        "private fun openWorkspaceFolder()",
        "R.id.btnHomeOpenWorkspace",
        "R.id.btnHomeCopyWorkspace",
        'DocumentsContract.buildTreeDocumentUri(',
        '"primary:Download/$folderName"',
    ):
        if token not in main_text:
            fail(f"HOMEINFO-001 functional binding missing: {token}")

    # HOMEACTIONS-001: four product scenarios stay directly reachable from Home.
    for token in (
        "@+id/cardHomePrimaryActions",
        "@+id/btnHomeTerminal",
        "@+id/btnHomeQuickFlash",
        "@+id/btnHomeSideload",
        "@+id/btnHomeMiUnlock",
    ):
        if token not in layout_text:
            fail(f"HOMEACTIONS-001 layout identity missing: {token}")
    for token in (
        'R.id.btnHomeTerminal).setOnClickListener { switchTab("console") }',
        'R.id.btnHomeQuickFlash).setOnClickListener { switchTab("fastboot") }',
        'R.id.btnHomeSideload).setOnClickListener { switchTab("adb") }',
        'R.id.btnHomeMiUnlock).setOnClickListener { switchTab("unlock") }',
    ):
        if token not in main_text:
            fail(f"HOMEACTIONS-001 functional binding missing: {token}")

    manifest_text = manifest.read_text(encoding="utf-8")
    for prefix in ("xiaomi-rom-parser\t", "miflash-"):
        if prefix in manifest_text:
            fail(f"V6 scope regression: removed Mi Flash test remains: {prefix}")
    for module in ("adb-core", "adb-sideload", "fastboot-core", "fastboot-mutation-safety", "mi-unlock-client"):
        if not re.search(rf"^{re.escape(module)}\t", manifest_text, re.M):
            fail(f"V6 required test module missing: {module}")

    if not ERRORS:
        print("V6 scope/audit cleanup, TOPBAR-001, HOMEINFO-001 and HOMEACTIONS-001: OK")


def check_alpha5_hardware_polish() -> None:
    welcome = ROOT / "app/src/main/res/layout/activity_welcome.xml"
    welcome_activity = ROOT / "app/src/main/java/ru/forum/adbfastboottool/WelcomeActivity.kt"
    layout = ROOT / "app/src/main/res/layout/activity_main.xml"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    policy = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MiAccountSecurityPolicy.kt"
    login = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MiLoginActivity.kt"

    required = (welcome, welcome_activity, layout, main, policy, login)
    for path in required:
        if not path.is_file():
            fail(f"Missing alpha5 hardware polish file: {path.relative_to(ROOT)}")
            return

    welcome_text = welcome.read_text(encoding="utf-8")
    welcome_activity_text = welcome_activity.read_text(encoding="utf-8")
    layout_text = layout.read_text(encoding="utf-8")
    main_text = main.read_text(encoding="utf-8")
    policy_text = policy.read_text(encoding="utf-8")
    login_text = login.read_text(encoding="utf-8")

    for token in ('@+id/tvStorageChip', '@+id/tvNotificationsChip', '@+id/tvBatteryChip', '@+id/riskRow'):
        if token not in welcome_text:
            fail(f"Welcome compact action token missing: {token}")
    for token in (
        'android:id="@+id/welcomeHeroArea"',
        'android:id="@+id/imgWelcomeBg"',
        'android:layout_height="match_parent"',
        'android:scaleType="centerCrop"',
        'android:id="@+id/welcomeBottomArea"',
        'android:layout_gravity="bottom"',
        'android:layout_marginBottom="@dimen/welcome_bottom_padding"',
        'android:background="@drawable/bg_welcome_panel"',
    ):
        if token not in welcome_text:
            fail(f"Welcome full-viewport overlay token missing: {token}")
    for forbidden in (
        '<ScrollView',
        'android:layout_height="0dp"',
        'android:minHeight="@dimen/welcome_art_height"',
    ):
        if forbidden in welcome_text:
            fail(f"Welcome must not restore the scrolling/oversized hero shell: {forbidden}")
    welcome_panel = ROOT / "app/src/main/res/drawable/bg_welcome_panel.xml"
    if not welcome_panel.is_file():
        fail("Welcome outline panel drawable is missing")
    else:
        welcome_panel_text = welcome_panel.read_text(encoding="utf-8")
        if '<solid android:color="#080B1119"' not in welcome_panel_text:
            fail("Welcome outer panel must remain effectively transparent")
        if '<stroke android:width="1dp" android:color="#99324052"' not in welcome_panel_text:
            fail("Welcome outer panel outline is missing")
    if '@+id/btnBatterySettings' in welcome_text or 'btnBatterySettings' in welcome_activity_text:
        fail("Welcome must not restore a separate battery settings button")
    if 'Вход выполнен (ID:' in main_text or 'Вход выполнен (ID: $userId)' in main_text:
        fail("Compact log must not expose the Xiaomi account ID")

    for token in (
        'tvStorageChip.setOnClickListener { openStoragePermissionSettings() }',
        'tvNotificationsChip.setOnClickListener { requestNotificationPermissionOrSettings() }',
        'tvBatteryChip.setOnClickListener { openBatteryOptimizationSettings() }',
        'R.id.riskRow).setOnClickListener',
    ):
        if token not in welcome_activity_text:
            fail(f"Welcome clickable status binding missing: {token}")

    if 'sideload_memo_icon' in layout_text or 'sideload_memo_text' in layout_text:
        fail("Sideload yellow memo card must remain removed")
    for token in ('@+id/btnSideloadImport', '@+id/btnSideloadCheckArchive', '@drawable/ic_nf_verify'):
        if token not in layout_text:
            fail(f"Sideload compact action token missing: {token}")
    sideload_note = re.search(
        r'<TextView\s+[^>]*android:text="@string/layout_sideload_hash_note"[^>]*/>',
        layout_text,
        re.S,
    )
    if not sideload_note:
        fail("Sideload neutral checksum note is missing")
    elif 'ic_status_check_green' in sideload_note.group(0):
        fail("Sideload pre-verification note must not show a green success icon")

    for token in ('@+id/btnFastbootDataSelfTest', '@+id/btnFastbootDataAdvanced'):
        if token not in layout_text:
            fail(f"Fastboot DATA compact UI token missing: {token}")
    for obsolete in (
        '@+id/btnFastbootDataSharedStorageProbe',
        '@+id/btnFastbootDataQualifyImage',
        '@+id/btnFastbootDataAutoMatrix',
        '@+id/btnFastbootDataContentProbe',
    ):
        if obsolete in layout_text:
            fail(f"Advanced Fastboot DATA action returned to the main card: {obsolete}")
    for token in ('openFastbootDiagnosticAction', 'showFastbootAdvancedDiagnosticsDialog', 'Fastboot DATA: нажато'):
        if token not in main_text:
            fail(f"Fastboot DATA logging/collapse token missing: {token}")

    for token in (
        'UNLOCK_CALLBACK_HOST = "unlock.update.miui.com"',
        'UNLOCK_CALLBACK_PATH = "/sts"',
        'isOfficialUnlockCallbackUrl',
        'unlockServiceHosts = setOf(',
        'isAllowedUnlockServiceUrl',
        'requireAllowedAuthFlowUrl',
        'resolveAllowedAuthRedirect',
        'isAllowedUnlockServiceCookieName',
    ):
        if token not in policy_text:
            fail(f"Mi Unlock callback/service policy token missing: {token}")
    for token in (
        'handleOfficialCompletion',
        'EXTRA_LOGIN_ERROR',
        'mi_login_missing_cookies',
        'mi_login_retry',
        'if (monitoringEnded || isFinishing || isDestroyed) return',
        'if (MiAccountSecurityPolicy.isOfficialUnlockCallbackUrl(url))',
        'late WebView callbacks are stale',
    ):
        if token not in login_text:
            fail(f"Mi Login observable callback/race token missing: {token}")
    finished_block = re.search(
        r'override fun onPageFinished\(view: WebView, url: String\) \{(?P<body>.*?)\n            \}',
        login_text,
        re.S,
    )
    if not finished_block:
        fail('Mi Login onPageFinished guard is missing')
    else:
        body = finished_block.group('body')
        terminal_guard = body.find('if (monitoringEnded || isFinishing || isDestroyed) return')
        callback_guard = body.find('isOfficialUnlockCallbackUrl(url)')
        account_guard = body.find('!MiAccountSecurityPolicy.isAllowedAccountUrl(url)')
        if min(terminal_guard, callback_guard, account_guard) < 0 or not (
            terminal_guard < callback_guard < account_guard
        ):
            fail('Mi Login onPageFinished must ignore terminal state, then consume /sts, then reject unrelated hosts')
    if 'getStringExtra(MiLoginActivity.EXTRA_LOGIN_ERROR)' not in main_text:
        fail("MainActivity must preserve the concrete Mi Login cancellation reason")

    if not ERRORS:
        print("alpha5 hardware polish and Mi Login callback guard: OK")

def main() -> int:
    check_master_tracker_presence()
    check_xml_files()
    check_string_parity()
    check_string_values_aapt_safe()
    check_kotlin_char_literals()
    check_kotlin_literal_structure()
    check_duplicate_kotlin_constructor_params()
    check_versions()
    check_gradle_wrapper()
    check_welcome_artwork_unchanged()
    check_app_gradle_structure()
    check_interface_release_cleanup()
    check_workflows()
    check_build_script()
    check_fileprovider_scope()
    check_android_compile_regressions()
    check_launcher_identity()
    check_layout_strings_are_resources()
    check_self_test_hooks()
    check_reports_access()
    check_private_reports()
    check_forum_report_removed()
    check_mi_account_and_share_hardening()
    check_alpha5_hardware_polish()
    check_termux_workflow()
    check_quick_flash_plan_slice()
    check_quick_flash_topology_slice()
    check_quick_flash_ui_slice()
    check_quick_flash_mutation_gate_slice()
    check_flash_operation_draft_state()
    check_v6_scope_reset()
    check_device_viewmodel_api_refs()

    for message in WARNINGS:
        print(f"WARNING: {message}")
    if ERRORS:
        print("\nFAILED:")
        for message in ERRORS:
            print(f" - {message}")
        return 1
    print("static project check: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
