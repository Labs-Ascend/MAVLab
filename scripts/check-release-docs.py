#!/usr/bin/env python3
"""Read-only checks of release identity and current local documentation links."""
import argparse
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def targets(text):
    # Ignore code examples; include Markdown image/nested poster links and HTML media.
    text = re.sub(r"(?ms)^```.*?^```[^\n]*", "", text)
    found = re.findall(r"\]\((<[^>]+>|[^)\n]+)\)", text)
    found += re.findall(r'(?:src|href)=["\']([^"\']+)["\']', text)
    return [value[1:-1] if value.startswith("<") else value.split()[0] for value in found]


def local_target(document, target):
    url = urlsplit(target)
    if url.scheme or url.netloc or not url.path:
        return None
    return (document.parent / unquote(url.path)).resolve()


def check(root=ROOT, publish=False, tag=None):
    errors = []
    config = (root / "mavlab-android/app/build.gradle.kts").read_text()
    version = re.search(r'versionName = "([^"]+)"', config).group(1)
    code = re.search(r"versionCode = (\d+)", config).group(1)
    notes = root / f"docs/v{version.replace('.', '_')}_release_notes.md"
    if not notes.is_file():
        errors.append(f"Missing versioned release notes: {notes.relative_to(root)}")
    else:
        text = notes.read_text()
        if not text.startswith(f"# MAVLab v{version} Release Notes"):
            errors.append("Release-note heading does not match versionName")
        if f"Android version code: {code}." not in text:
            errors.append("Release-note Android version code does not match metadata")
        if publish and re.search(r"^Status: (Release candidate|Draft)", text, re.M):
            errors.append("Publication blocked: release notes are still candidate/draft")
    if tag and tag != f"v{version}":
        errors.append(f"Tag {tag} does not match v{version}")
    if not (root / f"fastlane/metadata/android/en-US/changelogs/{code}.txt").is_file():
        errors.append(f"Missing Android changelog for version code {code}")
    docs = {root / "README.md", root / "AGENTS.md", root / "mavlab-android/README.md",
            root / "docs/README.md", root / "docs/setup_guide.md", root / "docs/product_purpose.md",
            root / "docs/known_first_run_issues.md",
            root / "docs/mavlab_product_surface_definition.md", notes,
            root / "docs/validation/README.md", root / "docs/validation/results/README.md",
            root / "docs/validation/v1.6.0-acceptance.md", root / "docs/v1_6_demo_script.md"}
    for directory in ("docs/user", "docs/internals", "docs/operations", "assets/media/2026-10"):
        docs.update((root / directory).rglob("*.md"))
    count = 0
    for document in sorted(docs):
        if not document.is_file():
            errors.append(f"Missing current document: {document.relative_to(root)}")
            continue
        for target in targets(document.read_text()):
            local = local_target(document, target)
            if local is None:
                continue
            count += 1
            if not local.is_relative_to(root) or not local.exists():
                errors.append(f"{document.relative_to(root)}: unresolved local link {target}")
    return version, code, len(docs), count, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="Also reject draft/candidate notes")
    parser.add_argument("--tag", help="Expected release tag")
    args = parser.parse_args()
    version, code, documents, links, errors = check(publish=args.publish, tag=args.tag)
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        return 1
    print(f"OK: v{version}/code {code}, {documents} current documents, {links} local links")
    print("Path checks do not validate remote URLs, anchor fragments, rendering, or release acceptance.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
