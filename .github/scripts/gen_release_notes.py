#!/usr/bin/env python3
"""Generate a GitHub release body for one tag, in Keep a Changelog shape.

The body has two halves, and each half has exactly one source:

- The release's own entries come from CHANGELOG.md, which is checked in. That file is the
  single source of truth for what a release changed, in the words a reader of the changelog
  reads, so the body repeats its section for the tag verbatim rather than restating it. The
  git log is deliberately not used for this half: it records commits rather than user-visible
  changes, and its subjects ("feat(ui): choose patches one at a time") would neither read as
  changelog entries nor cover the same set of changes the changelog does.

- The supported builds, where each one is downloaded from, and the known limitations come
  from sources.json, which is attached to the release. Nothing is written twice, so the notes
  and the manifest cannot drift apart. An unstated gap reads as a guarantee, which is why the
  limitations are rendered here in full rather than summarised.

Usage:
    python3 .github/scripts/gen_release_notes.py <tag>

The tag may carry a leading "v" or not. The body is written to stdout. Both input files are
read from the working directory, and from the repository root next to this script if they are
not there, which is how the release workflow calls it:

    python3 .github/scripts/gen_release_notes.py v1.3.0 > RELEASE_CHANGELOG.md
"""
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

CHANGELOG_NAME = "CHANGELOG.md"
SOURCES_NAME = "sources.json"

# A version heading in CHANGELOG.md: "## [1.3.0] - 2026-09-27", or "## [Unreleased]".
_VERSION_HEADING = re.compile(r"^##\s+\[(?P<label>[^\]]+)\]")

# A link reference at the foot of the changelog: "[1.3.0]: https://...". The last version
# section is followed by these rather than by another heading, so the section reader has to
# stop at them.
_LINK_REFERENCE = re.compile(r"^\[[^\]]+\]:\s+\S")


def _read(name):
    """Read a file from the working directory, else from the repository root.

    The working directory is tried first because that is the documented contract: the release
    workflow runs this script from the repository root. The fallback keeps the script usable
    from anywhere without a second copy of either file.
    """
    candidates = [Path(name), Path(__file__).resolve().parents[2] / name]
    for candidate in candidates:
        if candidate.is_file():
            return candidate.read_text(encoding="utf-8")
    return None


def _section(text, label):
    """The `## [label]` heading and the lines under it, or None when there is no such heading.

    The heading is returned with the body because a released version's heading carries its date,
    and repeating it verbatim is what keeps the release body and the changelog identical. A
    section with no content comes back as a heading and an empty body, which is not the same
    answer as a label with no heading at all, so the caller can tell an empty release from a
    missing one.
    """
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = _VERSION_HEADING.match(line)
        if not match or match.group("label") != label:
            continue

        section = []
        for following in lines[index + 1:]:
            if _VERSION_HEADING.match(following) or _LINK_REFERENCE.match(following):
                break
            section.append(following)

        # Blank lines at either end are the gap between headings, not part of this section.
        while section and not section[-1].strip():
            section.pop()
        while section and not section[0].strip():
            section.pop(0)
        return line.strip(), section
    return None


def changelog_section(tag):
    """The changelog section to publish for `tag` as (heading, lines), or None when there is none.

    The tag is matched with and without a leading "v", because the git tags carry one and the
    changelog headings do not. A tag with no section of its own falls back to the Unreleased
    section, which is where a release cut before its changelog entry was written keeps its
    changes; its heading is replaced with the tag, since "Unreleased" is not what is being
    published. Neither being present is not an error: the notes are still worth publishing with
    the sources and the limitations in them, and the caller says so on stderr.
    """
    text = _read(CHANGELOG_NAME)
    if text is None:
        return None

    # The tag's own section is looked for first and on its own, so the empty Unreleased section
    # at the top of the file cannot answer for a release that has a section further down.
    for label in (tag, tag.lstrip("v")):
        section = _section(text, label)
        if section is not None:
            heading, lines = section
            return (heading, lines) if lines else None

    section = _section(text, "Unreleased")
    if section is None or not section[1]:
        return None
    return f"## [{tag.lstrip('v')}]", section[1]


def source_hosts(source):
    """The distinct hosts a source's files are downloaded from, in declaration order.

    A source can be a base APK plus split configuration files, and those can sit on different
    hosts; every one is listed so a reader can see the full set of servers a build contacts.
    """
    hosts = []
    for url in [source.get("url", "")] + source.get("split_urls", []):
        host = urlsplit(url).hostname
        if host and host not in hosts:
            hosts.append(host)
    return hosts


def build_body(tag, data, section):
    """The release body: the changelog section, then what is being built and where from."""
    app_name = data.get("app_name", "sleepy")
    sources = data.get("sources", [])
    limitations = data.get("known_limitations", {})
    project_url = data.get("project_url", "")

    lines = [
        f"# {app_name} {tag}",
        "",
        f"{app_name} patches Discord and OctoGram on your phone, on your device. It downloads the "
        "original app from the source listed below, applies the changes you choose, signs the result "
        "and saves it. Nothing is uploaded.",
        "",
    ]

    # The changelog's own section, verbatim: heading, date and subsections.
    if section:
        heading, entries = section
        lines.extend([heading, "", *entries])
    else:
        lines.extend([
            "This tag has no section in the changelog yet. The supported builds and the known "
            "limitations for this release are below.",
        ])

    lines.extend([
        "",
        "## Supported builds",
        "",
        "This release patches the following exact builds. The URL under each one is where it is "
        "downloaded from, and is the only place the original app is fetched from.",
        "",
        "| Target | Version | Downloaded from | Download checked against |",
        "| :--- | :--- | :--- | :--- |",
    ])

    for source in sources:
        display_name = source.get("display_name", source.get("id", ""))
        version = source.get("version_name", "")
        version_code = source.get("version_code", "")
        hosts = ", ".join(source_hosts(source)) or "the URL in the manifest"

        expected = source.get("sha256_expected")
        if expected:
            check = f"SHA-256 published by the source (`{expected[:16]}...`)"
        else:
            check = "No SHA-256 is published for this source, so none is checked"

        lines.append(
            f"| **{display_name}** | `{version}` (`{version_code}`) | {hosts} | {check} |"
        )

    # The set names go below the table: GitHub renders a table cell on one line, so a collapsed
    # list inside a cell would come out as literal markup.
    for source in sources:
        patch_ids = source.get("patch_ids", [])
        display_name = source.get("display_name", source.get("id", ""))
        lines.extend([
            "",
            f"<details><summary><b>{display_name}</b>: {len(patch_ids)} patch sets</summary>",
            "",
            *[f"- `{patch_id}`" for patch_id in patch_ids],
            "",
            "</details>",
        ])

    lines.extend([
        "",
        "## Download provenance",
        "",
        f"- Every original APK this release patches comes from the source named in "
        f"[`sources.json`]({SOURCES_NAME}), which is attached to this release. The download, the "
        "patching and the signing all happen on your device.",
        "- Where the source publishes a SHA-256, the download is checked against it before anything "
        "is patched, and a mismatch stops the build. Where it does not, the app says so instead of "
        "claiming a check it cannot make.",
        "- The finished APK's signature and ZIP alignment are read back from the file rather than "
        "assumed, and a build that fails either check is reported as failed.",
        f"- This release was built from the source at [{project_url}]({project_url}), which is the "
        "repository of this patcher and of no app it patches.",
    ])

    # Limitations are stated per source, from the manifest, and are never omitted: an unstated
    # gap reads as a guarantee.
    if limitations:
        by_id = {source.get("id"): source for source in sources}
        lines.extend([
            "",
            "## Known limitations",
            "",
            "These builds do not do everything the reference patch suites do. Stated here rather "
            "than left implicit:",
        ])
        for source_id, items in limitations.items():
            source = by_id.get(source_id)
            title = source["display_name"] if source else source_id
            lines.append("")
            lines.append(f"**{title}**")
            lines.extend(f"- {item}" for item in items)

    lines.extend([
        "",
        "## Release assets",
        f"- **`sleepy-{tag}.apk`**: the signed, installable patcher",
        f"- **`{SOURCES_NAME}`**: the manifest embedded in this build, including the limitations above",
        "- **`sha256.txt`**: the SHA-256 of the signed release, as published",
        "",
        "The patched apps are not distributed here. "
        f"The signing key is {app_name}'s own, which makes a patched app a different app identity "
        "to Android: uninstall the official app before installing the patched one.",
    ])

    return lines


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "v1.0.0"

    sources_text = _read(SOURCES_NAME)
    if sources_text is None:
        print(f"Error: {SOURCES_NAME} not found", file=sys.stderr)
        sys.exit(1)

    data = json.loads(sources_text)
    section = changelog_section(tag)

    # A missing section is reported, not silent: the release page would otherwise carry the
    # sources and the limitations with no statement of what changed.
    if section is None:
        print(
            f"Warning: no {CHANGELOG_NAME} section for {tag} (or for Unreleased); "
            "the body will carry no changelog entries",
            file=sys.stderr,
        )

    print("\n".join(build_body(tag, data, section)))


if __name__ == "__main__":
    main()
