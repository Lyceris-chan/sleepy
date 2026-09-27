#!/usr/bin/env python3
"""Generate transparent GitHub release notes from sources.json.

Everything rendered here comes out of sources.json, which is attached to the
release, so the notes and the manifest cannot drift apart.
"""
import json
import sys
from pathlib import Path


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "v1.0.0"
    sources_file = Path("sources.json")

    if not sources_file.exists():
        print("Error: sources.json not found", file=sys.stderr)
        sys.exit(1)

    data = json.loads(sources_file.read_text(encoding="utf-8"))
    app_name = data.get("app_name", "sleepy")
    sources = data.get("sources", [])
    limitations = data.get("known_limitations", {})
    project_url = data.get("project_url", "")

    lines = [
        f"# {app_name} release {tag}",
        "",
        "Transparent on-device APK patcher built for privacy and performance.",
        "",
        "### 🔍 Auditable Patch Sources & Supported Builds",
        "The table below shows the exact APK sources, versions, and patch sets embedded directly into this release build from [`sources.json`](sources.json):",
        "",
        "| Target App | Version | Source URL | Patch sets |",
        "| :--- | :--- | :--- | :--- |",
    ]

    for s in sources:
        lines.append(
            f"| **{s['display_name']}** | `{s['version_name']}` (`{s['version_code']}`) "
            f"| [{s['url']}]({s['url']}) | {len(s.get('patch_ids', []))} sets |"
        )

    # The set names go below the table: GitHub renders a table cell on one line, so a
    # collapsed list inside a cell would come out as literal markup.
    for s in sources:
        patch_ids = s.get("patch_ids", [])
        lines.extend([
            "",
            f"<details><summary><b>{s['display_name']}</b> — {len(patch_ids)} patch sets</summary>",
            "",
            *[f"- `{pid}`" for pid in patch_ids],
            "",
            "</details>",
        ])

    lines.extend([
        "",
        "### 🛡️ Security & Privacy Standards",
        "- **Native Kotlin**: no React Native, no web-view wrapper in the patcher itself.",
        "- **No telemetry**: the patcher ships no analytics SDKs, trackers or crash reporters.",
        "- **RAM-first**: APK decompression and DEX patching happen in memory buffers.",
        "- **Verifiable**: signatures and ZIP alignment are checked against the finished APK, and the source SHA-256 is checked when the upstream publishes one.",
        f"- **Source**: [{project_url}]({project_url})",
    ])

    # Limitations are stated per source, from the manifest, and are never omitted:
    # an unstated gap reads as a guarantee.
    if limitations:
        lines.extend([
            "",
            "### ⚠️ Known limitations",
            "",
            "These builds do not do everything the reference patch suites do. Stated here rather than left implicit:",
        ])
        by_id = {s["id"]: s for s in sources}
        for source_id, items in limitations.items():
            source = by_id.get(source_id)
            title = source["display_name"] if source else source_id
            lines.append("")
            lines.append(f"**{title}**")
            lines.extend(f"- {item}" for item in items)

    lines.extend([
        "",
        "### 📦 Release Assets",
        f"- **`sleepy-{tag}.apk`**: signed, installable APK",
        "- **`sources.json`**: the manifest embedded in this build, including the limitations above",
        "- **`sha256.txt`**: SHA-256 of the signed release",
    ])

    print("\n".join(lines))


if __name__ == "__main__":
    main()
