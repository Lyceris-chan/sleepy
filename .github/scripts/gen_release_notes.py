#!/usr/bin/env python3
"""Generate transparent GitHub release notes from sources.json."""
import json
import sys
from pathlib import Path

def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "v1.0.0"
    sources_file = Path("sources.json")
    
    if not sources_file.exists():
        print(f"Error: sources.json not found", file=sys.stderr)
        sys.exit(1)
        
    data = json.loads(sources_file.read_text(encoding="utf-8"))
    app_name = data.get("app_name", "sleepy")
    sources = data.get("sources", [])

    lines = [
        f"# {app_name} release {tag}",
        "",
        "Transparent on-device APK patcher built for privacy and performance.",
        "",
        "### 🔍 Auditable Patch Sources & Supported Builds",
        "The table below shows the exact APK sources, versions, and patch sets embedded directly into this release build from [`sources.json`](sources.json):",
        "",
        "| Target App | Version | Source URL | Active Patches |",
        "| :--- | :--- | :--- | :--- |"
    ]

    for s in sources:
        patch_badges = " ".join([f"`{pid}`" for pid in s.get("patch_ids", [])])
        lines.append(
            f"| **{s['display_name']}** | `{s['version_name']}` (`{s['version_code']}`) | [{s['url']}]({s['url']}) | {patch_badges} |"
        )

    lines.extend([
        "",
        "### 🛡️ Security & Privacy Standards",
        "- **100% Native Kotlin**: No React Native, no Java sources, no web-view wrapper.",
        "- **Zero Telemetry**: No analytics SDKs, no trackers, no crash reporters in the app.",
        "- **RAM-First Processing**: APK decompression and Dex patching happen in memory buffers.",
        "- **Target SDK 37 (Android 17 QPR2 Ready)**: Full support for edge-to-edge and predictive back gestures.",
        "- **Reproducible**: [`sources.json`](sources.json) is attached below for source-code verification.",
        "",
        "### 📦 Release Assets",
        f"- **`sleepy-{tag}-signed.apk`**: Production-signed installable APK",
        "- **`sources.json`**: Source manifest embedded in this build",
        "- **`sha256.txt`**: Cryptographic checksum of the signed release"
    ])

    print("\n".join(lines))

if __name__ == "__main__":
    main()
