#!/usr/bin/env python3
"""Refresh the Discord source entry's tracker artifacts in both sources.json copies.

The tool hashes ``base.apk`` and every split named by the selected source, then
sets ``sha256_expected`` and, for splits, ``size_bytes`` to those values. It
keeps ``patch_ids`` and every other field it does not own. The source URL and
split URLs are retargeted to the target version code when the URL carries the
old one.

Both copies are written only when:

- the target source exists in both files and the two files hold the same data;
- every tracked file exists, is a regular file, is non-empty, and is an APK;
- the caller passed ``--write`` (the default is a dry run);
- every digest matches ``--checksums`` when that file is supplied;
- a same-version refresh does not silently replace a different build's hashes,
  unless ``--allow-same-version-hash-change`` is passed.

Nothing is downloaded and no other source is changed. Adding a new source
entry, rewriting known limitations, and renaming the display name remain
manual; see ``README.md``.

Examples::

    python3 -I tools/retarget/update_sources.py \\
        --tracker-dir /path/to/tracker-354201 \\
        --source-id discord_354201 \\
        --version-code 354201 --version-name "354.1 - Alpha"

    python3 -I tools/retarget/update_sources.py ... --write

    python3 -I tools/retarget/update_sources.py --self-test
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.parse

sys.dont_write_bytecode = True

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
SOURCES_PATHS = (
    "sources.json",
    os.path.join("app", "src", "main", "assets", "sources.json"),
)
CHUNK_SIZE = 1024 * 1024
HEX64_RE = re.compile(r"^[0-9a-fA-F]{64}$")


class ToolError(RuntimeError):
    """Invalid input that must stop before any file is written."""


class Refused(RuntimeError):
    """A safety check refused the update."""


def sha256_file(path: str) -> tuple[str, int]:
    if os.path.islink(path):
        raise ToolError(f"refusing to hash a symlink: {path}")
    if not os.path.isfile(path):
        raise ToolError(f"tracked file does not exist: {path}")
    size = os.path.getsize(path)
    if size <= 0:
        raise ToolError(f"tracked file is empty: {path}")
    with open(path, "rb") as handle:
        magic = handle.read(2)
    if magic != b"PK":
        raise ToolError(f"tracked file is not a ZIP/APK: {path}")
    digest = hashlib.sha256()
    total = 0
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(CHUNK_SIZE)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
    if total != size:
        raise ToolError(
            f"{path} changed while it was being read: "
            f"stat size {size}, read size {total}"
        )
    return digest.hexdigest(), size


def load_sources(path: str) -> tuple[dict, str]:
    if os.path.islink(path):
        raise ToolError(f"refusing to edit a symlink: {path}")
    if not os.path.isfile(path):
        raise ToolError(f"sources file does not exist: {path}")
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ToolError(f"cannot parse {path}: {exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("sources"), list):
        raise ToolError(f"{path} is not a sources.json document")
    if data.get("schema_version") != 2:
        raise ToolError(
            f"{path} has schema_version {data.get('schema_version')!r}; "
            f"this tool understands version 2"
        )
    return data, text


def select_source(data: dict, source_id: str | None, version_code: int) -> dict:
    sources = data["sources"]
    if source_id:
        matches = [source for source in sources if source.get("id") == source_id]
        if not matches:
            ids = ", ".join(str(source.get("id")) for source in sources)
            raise ToolError(f"no source with id {source_id!r}; known ids: {ids}")
        return matches[0]
    matches = [
        source for source in sources if source.get("version_code") == version_code
    ]
    if len(matches) == 1:
        return matches[0]
    if not matches:
        raise ToolError(
            "no source has the target version code; pass --source-id to choose "
            "the entry to refresh"
        )
    raise ToolError(
        "several sources have the target version code; pass --source-id"
    )


def split_file_name(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    name = parsed.path.rstrip("/").rsplit("/", 1)[-1]
    if not name:
        raise ToolError(f"cannot derive a split file name from URL {url!r}")
    name = urllib.parse.unquote(name)
    return name if name.endswith(".apk") else name + ".apk"


def rewrite_version_in_url(url: str, old_code: int, new_code: int) -> str:
    if old_code == new_code:
        return url
    old = f"/{old_code}/"
    new = f"/{new_code}/"
    if old in url:
        return url.replace(old, new)
    return url


def derive_split_url(base_url: str, old_split_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url)
    tail = parsed.path.rstrip("/").rsplit("/", 1)[-1]
    if tail == "base":
        prefix = base_url.rstrip("/")[: -len("base")]
        return prefix + urllib.parse.unquote(
            urllib.parse.urlsplit(old_split_url).path.rstrip("/").rsplit("/", 1)[-1]
        )
    return old_split_url


def parse_checksums(path: str) -> dict[str, str]:
    expected: dict[str, str] = {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            lines = handle.readlines()
    except OSError as exc:
        raise ToolError(f"cannot read checksum file {path}: {exc}") from exc
    for number, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        tokens = line.replace("*", " ", 1).split()
        digest = next((token for token in tokens if HEX64_RE.match(token)), None)
        names = [
            token
            for token in tokens
            if token != digest and not HEX64_RE.match(token)
        ]
        if digest is None or not names:
            raise ToolError(
                f"{path}:{number}: expected a SHA-256 and a file name: {line!r}"
            )
        name = os.path.basename(names[0])
        if name in expected and expected[name].lower() != digest.lower():
            raise ToolError(
                f"{path}:{number}: conflicting digests for {name}"
            )
        expected[name] = digest.lower()
    return expected


def format_int(value: int) -> str:
    return f"{value:,}"


def plan_source(
    source: dict,
    tracker: str,
    version_code: int,
    version_name: str | None,
    base_url: str | None,
    expected: dict[str, str] | None,
    allow_same_version_hash_change: bool,
    keep_url: bool,
) -> tuple[list[str], list[str]]:
    changes: list[str] = []
    warnings: list[str] = []
    old_code = source.get("version_code")
    if not isinstance(old_code, int):
        raise ToolError(f"source {source.get('id')!r} has no integer version_code")
    if not isinstance(version_code, int) or version_code <= 0:
        raise ToolError("--version-code must be a positive integer")
    same_version = old_code == version_code

    if source.get("version_code") != version_code:
        changes.append(f"version_code {old_code} -> {version_code}")
        source["version_code"] = version_code
    if version_name is not None:
        if not version_name.strip():
            raise ToolError("--version-name must not be empty")
        if source.get("version_name") != version_name:
            changes.append(
                f"version_name {source.get('version_name')!r} -> {version_name!r}"
            )
            source["version_name"] = version_name
    if old_code != version_code and version_name is None:
        raise ToolError(
            "the target version code changes but --version-name was not given"
        )

    if keep_url:
        if base_url is not None:
            warnings.append("--base-url was ignored because --keep-url was passed")
        if old_code != version_code:
            warnings.append(
                "URLs were kept unchanged for a new version code; verify the "
                "source and split URLs by hand"
            )
    elif base_url is not None:
        if source.get("url") != base_url:
            changes.append(f"url {source.get('url')!r} -> {base_url!r}")
            source["url"] = base_url
    else:
        old_url = source.get("url")
        if not isinstance(old_url, str):
            raise ToolError(f"source {source.get('id')!r} has no string url")
        new_url = rewrite_version_in_url(old_url, old_code, version_code)
        if new_url != old_url:
            changes.append(f"url {old_url!r} -> {new_url!r}")
            source["url"] = new_url
        elif old_code != version_code:
            raise Refused(
                "the source URL does not mention the old version code; pass "
                "--base-url so the new URL is not guessed"
            )
        if old_code != version_code:
            warnings.append(
                "split URLs were retargeted by replacing the version code; "
                "check each one with --base-url if the tracker path changed"
            )

    base_file = os.path.join(tracker, "base.apk")
    base_hash, base_size = sha256_file(base_file)
    old_base_hash = source.get("sha256_expected")
    if (
        same_version
        and old_base_hash is not None
        and old_base_hash != base_hash
        and not allow_same_version_hash_change
    ):
        raise Refused(
            f"base.apk has a different digest for version {version_code}; "
            f"refusing to replace a recorded build without "
            f"--allow-same-version-hash-change"
        )
    if old_base_hash != base_hash:
        changes.append(f"base sha256 {old_base_hash} -> {base_hash}")
        source["sha256_expected"] = base_hash
    else:
        changes.append(f"base sha256 unchanged ({base_hash})")
    changes.append(f"base size {format_int(base_size)} bytes")
    if expected is not None and expected.get("base.apk") != base_hash:
        raise Refused(
            f"base.apk digest does not match --checksums: "
            f"computed {base_hash}, expected {expected.get('base.apk')!r}"
        )

    splits = source.get("splits")
    if not isinstance(splits, list):
        raise ToolError(f"source {source.get('id')!r} has no splits list")
    used_files = {"base.apk": base_hash}
    for split in splits:
        if not isinstance(split, dict) or not isinstance(split.get("url"), str):
            raise ToolError("every split must have a string url")
        file_name = split_file_name(split["url"])
        if file_name in used_files:
            raise ToolError(f"two splits name the same tracker file {file_name}")
        path = os.path.join(tracker, file_name)
        digest, size = sha256_file(path)
        used_files[file_name] = digest
        if expected is not None and expected.get(file_name) != digest:
            raise Refused(
                f"{file_name} digest does not match --checksums: "
                f"computed {digest}, expected {expected.get(file_name)!r}"
            )
        old_digest = split.get("sha256_expected")
        old_size = split.get("size_bytes")
        if same_version and old_digest is not None and old_digest != digest:
            if not allow_same_version_hash_change:
                raise Refused(
                    f"{file_name} has a different digest for version "
                    f"{version_code}; refusing to replace a recorded build "
                    f"without --allow-same-version-hash-change"
                )
        if old_digest != digest:
            changes.append(
                f"split {file_name} sha256 {old_digest} -> {digest}"
            )
            split["sha256_expected"] = digest
        else:
            changes.append(f"split {file_name} sha256 unchanged ({digest})")
        if old_size != size:
            changes.append(
                f"split {file_name} size {old_size} -> {size} "
                f"({format_int(size)} bytes)"
            )
            split["size_bytes"] = size
        if not keep_url and (base_url is not None or old_code != version_code):
            new_url = rewrite_version_in_url(split["url"], old_code, version_code)
            if base_url is not None:
                new_url = derive_split_url(base_url, split["url"])
            if new_url != split["url"]:
                changes.append(f"split url {split['url']!r} -> {new_url!r}")
                split["url"] = new_url

    try:
        known = {name for name in os.listdir(tracker) if name.endswith(".apk")}
    except OSError as exc:
        raise ToolError(f"cannot list tracker directory {tracker}: {exc}") from exc
    extras = sorted(known - set(used_files))
    if extras:
        warnings.append(
            "tracker files not named by the source were ignored: "
            + ", ".join(extras)
        )
    return changes, warnings


def render(data: dict) -> str:
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def diff_text(old: str, new: str, path: str) -> str:
    diff = difflib.unified_diff(
        old.splitlines(keepends=True),
        new.splitlines(keepends=True),
        fromfile=path,
        tofile=path + " (proposed)",
        n=2,
    )
    return "".join(diff)


def write_atomic(path: str, text: str) -> None:
    directory = os.path.dirname(os.path.abspath(path)) or "."
    fd, temporary = tempfile.mkstemp(prefix=".sources-", suffix=".json", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def update_sources(
    repo_root: str,
    tracker: str,
    source_id: str | None,
    version_code: int,
    version_name: str | None,
    base_url: str | None,
    new_source_id: str | None,
    checksums: str | None,
    dry_run: bool,
    allow_divergent_copies: bool,
    allow_same_version_hash_change: bool,
    keep_url: bool,
) -> int:
    paths = [os.path.join(repo_root, relative) for relative in SOURCES_PATHS]
    if len({os.path.abspath(path) for path in paths}) != len(paths):
        raise ToolError("the two sources paths resolve to the same file")
    documents = []
    texts = []
    for path in paths:
        data, text = load_sources(path)
        documents.append(data)
        texts.append(text)
    if documents[0] != documents[1] and not allow_divergent_copies:
        raise Refused(
            "the two sources.json copies differ; make them identical or pass "
            "--allow-divergent-copies to write the first copy's selection to both"
        )
    if not os.path.isdir(tracker):
        raise ToolError(f"tracker directory does not exist: {tracker}")
    if not os.path.isdir(repo_root):
        raise ToolError(f"repository root does not exist: {repo_root}")
    expected = parse_checksums(checksums) if checksums else None

    copies_differ = documents[0] != documents[1]
    source = select_source(documents[0], source_id, version_code)
    old_patch_ids = list(source.get("patch_ids", []))
    old_id = source.get("id")
    changes, warnings = plan_source(
        source,
        tracker,
        version_code,
        version_name,
        base_url,
        expected,
        allow_same_version_hash_change,
        keep_url,
    )
    if list(source.get("patch_ids", [])) != old_patch_ids:
        raise ToolError("internal error: patch_ids changed")
    if new_source_id and new_source_id != old_id:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", new_source_id):
            raise ToolError("--new-source-id may contain only letters, digits, '.', '_' and '-'")
        if any(
            candidate is not source and candidate.get("id") == new_source_id
            for candidate in documents[0]["sources"]
        ):
            raise ToolError(f"a source with id {new_source_id!r} already exists")
        source["id"] = new_source_id
        limitations = documents[0].get("known_limitations")
        if isinstance(limitations, dict) and old_id in limitations:
            limitations[new_source_id] = limitations.pop(old_id)
        changes.append(f"source id {old_id!r} -> {new_source_id!r}")
    # Both copies are required to carry identical data. Even when the caller
    # allowed divergence, the first copy's selected entry is the canonical one.
    if copies_differ:
        warnings.append(
            "the second sources.json copy differed and was replaced with the "
            "first copy's data"
        )
    documents[1] = json.loads(json.dumps(documents[0]))

    new_text = render(documents[0])
    try:
        json.loads(new_text)
    except json.JSONDecodeError as exc:  # defensive: never write invalid JSON
        raise ToolError(f"internal error: generated JSON is invalid: {exc}") from exc

    print(f"source {old_id!r} -> version {version_code}")
    for change in changes:
        print(f"  {change}")
    for warning in warnings:
        print(f"  warning: {warning}")
    for path, old_text in zip(paths, texts):
        if old_text == new_text:
            print(f"unchanged: {path}")
            continue
        print()
        print(diff_text(old_text, new_text, path))
    if dry_run:
        print("dry run: no files written; pass --write to update both copies")
        return 0

    changed_paths = [
        path for path, old_text in zip(paths, texts) if old_text != new_text
    ]
    if not changed_paths:
        print("write: both copies already match; nothing to do")
        return 0
    originals = {
        path: text for path, text in zip(paths, texts) if path in changed_paths
    }
    written: list[str] = []
    try:
        for path in changed_paths:
            write_atomic(path, new_text)
            written.append(path)
    except OSError as exc:
        for path in written:
            try:
                write_atomic(path, originals[path])
            except OSError:
                pass
        raise ToolError(f"could not write both copies; rollback attempted: {exc}") from exc
    for path in changed_paths:
        print(f"wrote: {path}")
    return 0


def run_self_test() -> int:
    checks = 0

    def check(condition: bool, message: str) -> None:
        nonlocal checks
        if not condition:
            raise ToolError(f"self-test failed: {message}")
        checks += 1

    document = {
        "schema_version": 2,
        "sources": [
            {
                "id": "discord_354201",
                "display_name": "Discord Alpha 354.1",
                "version_name": "354.1 - Alpha",
                "version_code": 354201,
                "url": "https://tracker.example/download/354201/base",
                "sha256_expected": "0" * 64,
                "patch_ids": ["one", "two"],
                "splits": [
                    {
                        "url": "https://tracker.example/download/354201/config.arm64_v8a",
                        "sha256_expected": "1" * 64,
                        "size_bytes": 1,
                    }
                ],
                "known_limitations": ["stale prose"],
            }
        ],
        "known_limitations": {"discord_354201": ["stale prose"]},
    }
    with tempfile.TemporaryDirectory(prefix="update-sources-test-") as temp:
        repo = os.path.join(temp, "repo")
        assets = os.path.join(repo, "app", "src", "main", "assets")
        tracker = os.path.join(temp, "tracker")
        os.makedirs(assets)
        os.makedirs(tracker)
        original = render(document)
        for relative in SOURCES_PATHS:
            path = os.path.join(repo, relative)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(original)
        base_bytes = b"PK\x03\x04base-payload"
        split_bytes = b"PK\x03\x04split-payload"
        with open(os.path.join(tracker, "base.apk"), "wb") as handle:
            handle.write(base_bytes)
        with open(os.path.join(tracker, "config.arm64_v8a.apk"), "wb") as handle:
            handle.write(split_bytes)
        with open(os.path.join(tracker, "config.armeabi_v7a.apk"), "wb") as handle:
            handle.write(b"PK\x03\x04extra")
        with open(os.path.join(tracker, "headers.txt"), "w", encoding="utf-8") as handle:
            handle.write("ignored\n")

        before = {
            relative: open(os.path.join(repo, relative), encoding="utf-8").read()
            for relative in SOURCES_PATHS
        }
        code = update_sources(
            repo,
            tracker,
            "discord_354201",
            354201,
            None,
            None,
            None,
            None,
            dry_run=True,
            allow_divergent_copies=False,
            allow_same_version_hash_change=True,
            keep_url=False,
        )
        check(code == 0, "dry run exit")
        after = {
            relative: open(os.path.join(repo, relative), encoding="utf-8").read()
            for relative in SOURCES_PATHS
        }
        check(before == after, "dry run wrote nothing")

        code = update_sources(
            repo,
            tracker,
            "discord_354201",
            355001,
            "355.0 - Alpha",
            None,
            "discord_355001",
            None,
            dry_run=False,
            allow_divergent_copies=False,
            allow_same_version_hash_change=False,
            keep_url=False,
        )
        check(code == 0, "write exit")
        first = json.loads(open(os.path.join(repo, SOURCES_PATHS[0]), encoding="utf-8").read())
        second = json.loads(open(os.path.join(repo, SOURCES_PATHS[1]), encoding="utf-8").read())
        check(first == second, "copies agree")
        source = first["sources"][0]
        check(source["id"] == "discord_355001", "source id rename")
        check(source["version_code"] == 355001, "version code")
        check(source["patch_ids"] == ["one", "two"], "patch ids kept")
        check(source["sha256_expected"] == hashlib.sha256(base_bytes).hexdigest(), "base hash")
        check(source["splits"][0]["sha256_expected"] == hashlib.sha256(split_bytes).hexdigest(), "split hash")
        check(source["splits"][0]["size_bytes"] == len(split_bytes), "split size")
        check("/355001/" in source["url"], "base url retargeted")
        check("/355001/" in source["splits"][0]["url"], "split url retargeted")
        check("discord_355001" in first["known_limitations"], "limitations key renamed")

        written = {
            relative: open(os.path.join(repo, relative), encoding="utf-8").read()
            for relative in SOURCES_PATHS
        }
        code = update_sources(
            repo,
            tracker,
            "discord_355001",
            355001,
            None,
            None,
            None,
            None,
            dry_run=False,
            allow_divergent_copies=False,
            allow_same_version_hash_change=False,
            keep_url=False,
        )
        check(code == 0, "second write exit")
        again = {
            relative: open(os.path.join(repo, relative), encoding="utf-8").read()
            for relative in SOURCES_PATHS
        }
        check(written == again, "idempotent write")

        with open(os.path.join(tracker, "base.apk"), "wb") as handle:
            handle.write(b"PK\x03\x04different")
        protected = again
        try:
            update_sources(
                repo,
                tracker,
                "discord_355001",
                355001,
                None,
                None,
                None,
                None,
                dry_run=False,
                allow_divergent_copies=False,
                allow_same_version_hash_change=False,
                keep_url=False,
            )
        except Refused:
            check(True, "same-version hash change refused")
        else:
            check(False, "same-version hash change was not refused")
        check(
            protected
            == {
                relative: open(os.path.join(repo, relative), encoding="utf-8").read()
                for relative in SOURCES_PATHS
            },
            "refusal wrote nothing",
        )

        with open(os.path.join(tracker, "base.apk"), "wb") as handle:
            handle.write(base_bytes)
        os.unlink(os.path.join(tracker, "config.arm64_v8a.apk"))
        try:
            update_sources(
                repo,
                tracker,
                "discord_355001",
                355001,
                None,
                None,
                None,
                None,
                dry_run=False,
                allow_divergent_copies=False,
                allow_same_version_hash_change=True,
                keep_url=False,
            )
        except ToolError:
            check(True, "missing split refused")
        else:
            check(False, "missing split was not refused")

        with open(os.path.join(tracker, "config.arm64_v8a.apk"), "wb") as handle:
            handle.write(split_bytes)
        checksum_path = os.path.join(temp, "checksums.txt")
        with open(checksum_path, "w", encoding="utf-8") as handle:
            handle.write(
                f"{hashlib.sha256(base_bytes).hexdigest()}  base.apk\n"
                f"{hashlib.sha256(split_bytes).hexdigest()}  config.arm64_v8a.apk\n"
            )
        code = update_sources(
            repo,
            tracker,
            "discord_355001",
            355001,
            None,
            None,
            None,
            checksum_path,
            dry_run=True,
            allow_divergent_copies=False,
            allow_same_version_hash_change=True,
            keep_url=False,
        )
        check(code == 0, "matching checksums accepted")
        with open(checksum_path, "w", encoding="utf-8") as handle:
            handle.write(f"{'f' * 64}  base.apk\n")
        try:
            update_sources(
                repo,
                tracker,
                "discord_355001",
                355001,
                None,
                None,
                None,
                checksum_path,
                dry_run=True,
                allow_divergent_copies=False,
                allow_same_version_hash_change=True,
                keep_url=False,
            )
        except Refused:
            check(True, "wrong checksum refused")
        else:
            check(False, "wrong checksum was not refused")

    print(f"self-test: ok ({checks} checks)")
    return 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="update_sources.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            "Hash a tracker download directory and update the selected source "
            "in both sources.json copies. Dry run by default."
        ),
        epilog=(
            "Exit status: 0 when the plan succeeds, 1 when a safety check "
            "refuses the update, 2 for invalid input. Nothing is written "
            "without --write."
        ),
    )
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument(
        "--repo-root",
        default=DEFAULT_REPO_ROOT,
        help=f"repository root (default: {DEFAULT_REPO_ROOT})",
    )
    parser.add_argument("--tracker-dir", help="directory holding base.apk and split APKs")
    parser.add_argument("--source-id", help="source entry to update")
    parser.add_argument("--version-code", type=int, help="target version code")
    parser.add_argument("--version-name", help="target version name, for example '354.1 - Alpha'")
    parser.add_argument("--base-url", help="base URL to write, if it is not a tracker URL")
    parser.add_argument("--new-source-id", help="rename the source id in both copies")
    parser.add_argument(
        "--checksums",
        help="file of 'sha256  filename' pairs to verify before updating",
    )
    write_group = parser.add_mutually_exclusive_group()
    write_group.add_argument(
        "--write",
        action="store_true",
        help="update both sources.json copies; without it the tool is a dry run",
    )
    write_group.add_argument(
        "--dry-run",
        action="store_true",
        help="print the plan and write nothing (the default)",
    )
    parser.add_argument(
        "--allow-divergent-copies",
        action="store_true",
        help="replace a differing second copy with the first copy's data",
    )
    parser.add_argument(
        "--allow-same-version-hash-change",
        action="store_true",
        help="accept different hashes for a version code already recorded",
    )
    parser.add_argument(
        "--keep-url",
        action="store_true",
        help="leave the source and split URLs unchanged",
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    parser.add_argument("--version", action="version", version="update_sources.py 1")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.self_test:
        return run_self_test()
    if not args.tracker_dir:
        print("error: --tracker-dir is required", file=sys.stderr)
        return 2
    if args.version_code is None:
        print("error: --version-code is required", file=sys.stderr)
        return 2
    try:
        return update_sources(
            os.path.abspath(args.repo_root),
            os.path.abspath(args.tracker_dir),
            args.source_id,
            args.version_code,
            args.version_name,
            args.base_url,
            args.new_source_id,
            os.path.abspath(args.checksums) if args.checksums else None,
            dry_run=not args.write,
            allow_divergent_copies=args.allow_divergent_copies,
            allow_same_version_hash_change=args.allow_same_version_hash_change,
            keep_url=args.keep_url,
        )
    except Refused as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 1
    except ToolError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
