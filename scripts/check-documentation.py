#!/usr/bin/env python3
"""Validate NekoFlash documentation ownership, links, versions and generated files."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote

from checksum_inventory import iter_source_files

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs" / "documentation-manifest.json"
BUILD_GRADLE = ROOT / "app" / "build.gradle"


def fail(message: str) -> None:
    print(f"[docs] FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_version() -> tuple[str, str]:
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    name = re.search(r'versionName\s+["\']([^"\']+)["\']', text)
    code = re.search(r"versionCode\s+(\d+)", text)
    if not name or not code:
        fail("cannot parse versionName/versionCode from app/build.gradle")
    return name.group(1), code.group(1)


def check_canonical_files(manifest: dict) -> None:
    canonical = manifest["canonical_documents"]
    missing = [path for path in canonical if not (ROOT / path).is_file()]
    if missing:
        fail("missing canonical documents: " + ", ".join(missing))

    for path in manifest["forbidden_live_files"]:
        if (ROOT / path).exists():
            fail(f"legacy live document must not exist: {path}")

    root_md = sorted(path.name for path in ROOT.glob("*.md"))
    allowed = sorted(manifest["root_markdown_allowlist"])
    if root_md != allowed:
        fail(f"root Markdown set differs from manifest: actual={root_md}, allowed={allowed}")

    for document in sorted(ROOT.rglob("*.md")):
        text = document.read_text(encoding="utf-8")
        h1 = re.findall(r"^#\s+.+$", text, flags=re.MULTILINE)
        if len(h1) != 1:
            fail(
                f"{document.relative_to(ROOT)} must contain exactly one H1, "
                f"found {len(h1)}"
            )


def check_version_sync(manifest: dict) -> tuple[str, str, str]:
    version_name, version_code = parse_version()
    tracker = (ROOT / "PROJECT_MASTER_TRACKER.md").read_text(encoding="utf-8")
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")

    if f"**`{version_name}`**" not in tracker:
        fail(f"tracker does not contain current versionName {version_name}")
    if f"**`{version_code}`**" not in tracker:
        fail(f"tracker does not contain current versionCode {version_code}")

    milestone = re.search(r"Текущий milestone:\s*\*\*(V\d+\.\d+\.\d+)", tracker)
    if not milestone:
        fail("cannot parse current milestone from tracker")
    milestone_name = milestone.group(1)

    first_release = re.search(r"^##\s+([^\n]+)", changelog, flags=re.MULTILINE)
    if not first_release or milestone_name not in first_release.group(1):
        fail(f"top CHANGELOG entry must match tracker milestone {milestone_name}")

    first_section = changelog.split("\n## ", 2)[1] if "\n## " in changelog else ""
    if version_name not in first_section or version_code not in first_section:
        fail("top CHANGELOG section must contain current versionName and versionCode")

    return version_name, version_code, milestone_name


def relative_target(source: Path, raw: str) -> Path | None:
    raw = raw.strip()
    if not raw or raw.startswith(("http://", "https://", "mailto:", "tel:", "#")):
        return None
    raw = raw.split("#", 1)[0].split("?", 1)[0]
    if not raw:
        return None
    raw = unquote(raw)
    return (source.parent / raw).resolve()


def check_links() -> int:
    count = 0
    link_re = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
    for source in sorted(ROOT.rglob("*.md")):
        for match in link_re.finditer(source.read_text(encoding="utf-8")):
            raw = match.group(1)
            target = relative_target(source, raw)
            if target is None:
                continue
            count += 1
            try:
                target.relative_to(ROOT)
            except ValueError:
                fail(f"link escapes repository: {source.relative_to(ROOT)} -> {raw}")
            if not target.exists():
                fail(f"broken relative link: {source.relative_to(ROOT)} -> {raw}")
    return count


def check_index(manifest: dict) -> None:
    index = (ROOT / "docs" / "README.md").read_text(encoding="utf-8")
    for canonical in manifest["canonical_documents"]:
        if canonical == "docs/README.md":
            continue
        relative = Path(canonical)
        if relative.parts[0] == "docs":
            expected = str(Path(*relative.parts[1:]))
        else:
            expected = "../" + canonical
        if expected not in index:
            fail(f"docs/README.md does not link canonical document {canonical}")


def check_no_legacy_references(manifest: dict) -> None:
    forbidden_targets = {(ROOT / path).resolve(): path for path in manifest["forbidden_live_files"]}
    canonical = [ROOT / path for path in manifest["canonical_documents"]]
    link_re = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
    for source in canonical:
        text = source.read_text(encoding="utf-8")
        for match in link_re.finditer(text):
            target = relative_target(source, match.group(1))
            if target in forbidden_targets:
                fail(
                    f"canonical document links legacy file: "
                    f"{source.relative_to(ROOT)} -> {forbidden_targets[target]}"
                )

    for path in ROOT.rglob("*"):
        if not path.is_file() or "history" in path.parts or path == Path(__file__).resolve():
            continue
        if path.suffix.lower() not in {".md", ".py", ".sh", ".bat", ".yml", ".yaml"}:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if "check-project-tracker.py" in text:
            fail(f"obsolete tracker guard reference remains in {path.relative_to(ROOT)}")



def check_welcome_lock() -> None:
    manifest_path = ROOT / "WELCOME_ARTWORK_SHA256.txt"
    artwork_path = ROOT / "app" / "src" / "main" / "res" / "drawable" / "bg_welcome.jpg"
    doc_path = ROOT / "docs" / "WELCOME_ASSET_LOCK.md"
    if not manifest_path.is_file() or not artwork_path.is_file():
        fail("welcome artwork or its SHA-256 manifest is missing")
    expected = manifest_path.read_text(encoding="utf-8").strip().split()[0]
    actual = hashlib.sha256(artwork_path.read_bytes()).hexdigest()
    if expected != actual:
        fail("WELCOME_ARTWORK_SHA256.txt does not match bg_welcome.jpg")
    if expected not in doc_path.read_text(encoding="utf-8"):
        fail("docs/WELCOME_ASSET_LOCK.md does not contain the locked artwork SHA-256")

def check_generated_artifacts(manifest: dict) -> None:
    if not (ROOT / "scripts" / "update-checksums.py").is_file():
        fail("scripts/update-checksums.py is missing")
    if (ROOT / "validation" / "logs").exists() or (ROOT / "validation" / "journal.jsonl").exists():
        fail("raw hardware logs/journal must not be committed in the active V6 source tree")
    hardware = (ROOT / "docs" / "HARDWARE_VALIDATION.md").read_text(encoding="utf-8")
    if "AUTO-JOURNAL" in hardware:
        fail("HARDWARE_VALIDATION.md must be a reviewed sanitized summary, not a generated journal")
    generated = manifest.get("generated_artifacts", {})
    if set(generated) != {"SHA256SUMS"}:
        fail("documentation manifest may declare only SHA256SUMS as a generated artifact")




def check_v6_plan_hygiene(manifest: dict) -> None:
    allowed_archive_refs = {"README.md", "PROJECT_MASTER_TRACKER.md", "CHANGELOG.md", "docs/SCOPE.md", "docs/V6_AUDIT.md"}
    for rel in manifest["canonical_documents"]:
        path = ROOT / rel
        text = path.read_text(encoding="utf-8")
        if rel not in allowed_archive_refs and re.search(r"\bMIFLASH-|\bNEXT-0|\bV5\.[0-9]", text):
            fail(f"stale V5/Mi Flash planning vocabulary remains in canonical document: {rel}")

def check_canonical_line_endings() -> int:
    checked = 0
    binary_suffixes = {".jar", ".jpg", ".jpeg", ".png", ".zip"}
    for path in iter_source_files(ROOT):
        data = path.read_bytes()
        if path.suffix.lower() in binary_suffixes or b"\x00" in data:
            continue
        relative = path.relative_to(ROOT)
        checked += 1
        if path.suffix.lower() == ".bat":
            without_crlf = data.replace(b"\r\n", b"")
            if b"\n" in without_crlf or b"\r" in without_crlf:
                fail(f"batch file must use canonical CRLF: {relative}")
        elif b"\r\n" in data or b"\r" in data:
            fail(f"text file must use canonical LF: {relative}")
    return checked

def check_checksums() -> int:
    checksum_path = ROOT / "SHA256SUMS"
    if not checksum_path.is_file():
        fail("SHA256SUMS is missing")
    expected = []
    for path in iter_source_files(ROOT):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        expected.append(f"{digest}  ./{path.relative_to(ROOT).as_posix()}")
    actual = checksum_path.read_text(encoding="utf-8").splitlines()
    if actual != expected:
        fail("SHA256SUMS is stale; run python3 scripts/update-checksums.py")
    return len(expected)


def check_integration() -> None:
    required = {
        "scripts/build-apk.sh": "scripts/check-documentation.py",
        "scripts/build-apk.bat": "scripts\\check-documentation.py",
        ".github/workflows/build.yml": "scripts/check-documentation.py",
        "scripts/check_project.py": "check-documentation.py",
    }
    for path, token in required.items():
        text = (ROOT / path).read_text(encoding="utf-8")
        if token not in text:
            fail(f"{path} does not invoke/reference documentation guard")


def main() -> None:
    if not MANIFEST.is_file():
        fail("docs/documentation-manifest.json is missing")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if manifest.get("schema") != 1:
        fail("unsupported documentation manifest schema")

    check_canonical_files(manifest)
    version_name, version_code, milestone = check_version_sync(manifest)
    check_index(manifest)
    check_no_legacy_references(manifest)
    check_welcome_lock()
    links = check_links()
    check_generated_artifacts(manifest)
    check_integration()
    check_v6_plan_hygiene(manifest)
    canonical_text = check_canonical_line_endings()
    checksums = check_checksums()

    print(
        f"[docs] PASS: {version_name} ({version_code}), milestone={milestone}, "
        f"canonical={len(manifest['canonical_documents'])}, links={links}, "
        f"text_files={canonical_text}, checksums={checksums}"
    )


if __name__ == "__main__":
    main()
