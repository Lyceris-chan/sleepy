#!/usr/bin/env python3
"""Refresh the SHA-256 values that sources.json publishes for its downloads.

The script downloads every file a source declares, checks that the file is the build
the source describes, hashes it, and writes the results into both copies of
sources.json. It stops at the first failure instead of publishing a partial or empty
set: an unreachable source, a download that belongs to another package or version,
and a split whose content does not match its configuration all fail the run.

Identity checks:

- The package name and version code must equal the values the source configures.
- The version name must match after case and punctuation are removed, because the
  tracker spells one build as "348.5 - Alpha" where the source records "348.5 Alpha".
- A split must declare the name its URL ends with and carry the content its
  configuration implies: an ABI split carries lib/, a density split carries res/,
  and any other resource split carries a resources.arsc.

The script writes schema version 2 and moves a version 1 document's `split_urls`
strings into the `splits` array, where each split records its URL, hash and size.

The workflow runs this script from the repository root. The script needs aapt2 from
the Android SDK build tools, named with --aapt2 or found on PATH or under
ANDROID_HOME.
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

USER_AGENT = "sleepy-source-hasher/1.0"
DOWNLOAD_ATTEMPTS = 3
RETRY_DELAY_SECONDS = 5
READ_CHUNK_BYTES = 1024 * 1024

SCHEMA_VERSION = 2
MANIFEST_NAME = "sources.json"
BUNDLED_MANIFEST = Path("app/src/main/assets/sources.json")

# ABI configuration names, and the lib/ directory each one ships its shared objects in.
ABI_LIB_DIRECTORIES = {
    "armeabi": "armeabi",
    "armeabi_v7a": "armeabi-v7a",
    "arm64_v8a": "arm64-v8a",
    "x86": "x86",
    "x86_64": "x86_64",
    "mips": "mips",
    "mips64": "mips64",
    "riscv64": "riscv64",
}

# Density configuration names, whose payload is the res/ tree they carry.
DENSITY_CONFIGURATIONS = {
    "ldpi",
    "mdpi",
    "tvdpi",
    "hdpi",
    "xhdpi",
    "xxhdpi",
    "xxxhdpi",
    "anydpi",
    "nodpi",
}

PACKAGE_LINE = re.compile(
    r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'"
)
SPLIT_NAME = re.compile(r"\bsplit='([^']*)'")


class SourceHashError(Exception):
    """A source failed a check the manifest depends on, so nothing is published."""


def parse_arguments() -> argparse.Namespace:
    """Reads the command line."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path("."),
        help="the checkout that holds sources.json",
    )
    parser.add_argument(
        "--aapt2",
        type=Path,
        default=None,
        help="the aapt2 binary used to read each APK's package and version",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=None,
        help="where downloads are written; a temporary directory by default",
    )
    return parser.parse_args()


def resolve_aapt2(explicit: Path | None) -> Path:
    """Returns the aapt2 binary to use, from the argument or from the SDK layout."""
    if explicit is not None:
        if not explicit.is_file():
            raise SourceHashError(f"--aapt2 {explicit} is not a file")
        return explicit

    on_path = shutil.which("aapt2")
    if on_path is not None:
        return Path(on_path)

    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk_root:
        candidates = sorted(
            (Path(sdk_root) / "build-tools").glob("*/aapt2"),
            key=lambda path: tuple(
                int(part) for part in path.parent.name.split(".") if part.isdigit()
            ),
        )
        if candidates:
            return candidates[-1]

    raise SourceHashError("aapt2 was not found; pass --aapt2 or set ANDROID_HOME")


def download(url: str, destination: Path) -> int:
    """Downloads url to destination and returns the number of bytes written.

    A failed attempt is retried, because a mirror can be transiently unavailable. The
    call fails after the last attempt. A response whose final URL is not HTTPS, whose
    body is not a ZIP archive, or whose length differs from the declared one is not
    written into the manifest either.
    """
    last_error: Exception | None = None
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        try:
            return _download_once(url, destination)
        except (
            SourceHashError,
            urllib.error.URLError,
            http.client.HTTPException,
            TimeoutError,
            OSError,
        ) as error:
            last_error = error
            print(
                f"  attempt {attempt} of {DOWNLOAD_ATTEMPTS} failed: {error}",
                file=sys.stderr,
            )
            if attempt < DOWNLOAD_ATTEMPTS:
                time.sleep(RETRY_DELAY_SECONDS * attempt)
    raise SourceHashError(f"could not download {url}: {last_error}")


def _download_once(url: str, destination: Path) -> int:
    """Downloads url once, validating the response before returning its length."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        final_url = response.geturl()
        if not final_url.lower().startswith("https://"):
            raise SourceHashError(f"{url} redirected to a non-HTTPS URL: {final_url}")
        if response.status != 200:
            raise SourceHashError(f"{url} answered with HTTP {response.status}")

        declared_header = response.headers.get("Content-Length")
        declared = int(declared_header) if declared_header else None

        written = 0
        with destination.open("wb") as output:
            while True:
                chunk = response.read(READ_CHUNK_BYTES)
                if not chunk:
                    break
                output.write(chunk)
                written += len(chunk)

    if declared is not None and written != declared:
        raise SourceHashError(
            f"{url} declared {declared} bytes and sent {written}"
        )
    with destination.open("rb") as downloaded:
        if downloaded.read(2) != b"PK":
            raise SourceHashError(f"{url} did not serve a ZIP archive")
    return written


def sha256_of(path: Path) -> str:
    """Returns the SHA-256 of the file at path, read a chunk at a time."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(READ_CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stable_sha256(path: Path, declared_size: int | None) -> tuple[str, int]:
    """Hashes path twice and returns the digest and size that both reads agree on.

    Two reads of the same file catch a download that did not receive every byte. A
    length the server declared is checked against the file before it is hashed.
    """
    size = path.stat().st_size
    if declared_size is not None and size != declared_size:
        raise SourceHashError(
            f"{path.name} is {size} bytes where the server declared {declared_size}"
        )
    first = sha256_of(path)
    second = sha256_of(path)
    if first != second:
        raise SourceHashError(f"two reads of {path.name} produced different hashes")
    return first, size


def read_badging(aapt2: Path, apk: Path) -> dict[str, str | None]:
    """Reads the package, version and split name aapt2 reports for apk."""
    result = subprocess.run(
        [str(aapt2), "dump", "badging", str(apk)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SourceHashError(
            f"aapt2 could not read {apk.name}: {result.stderr.strip()}"
        )
    first_line = result.stdout.splitlines()[0] if result.stdout else ""
    match = PACKAGE_LINE.match(first_line)
    if match is None:
        raise SourceHashError(f"aapt2 reported no package line for {apk.name}")
    split_match = SPLIT_NAME.search(first_line)
    return {
        "package_name": match.group(1),
        "version_code": match.group(2),
        "version_name": match.group(3),
        "split": split_match.group(1) if split_match else None,
    }


def archive_names(path: Path) -> list[str]:
    """Returns the entry names of the ZIP archive at path."""
    try:
        with zipfile.ZipFile(path) as archive:
            return archive.namelist()
    except zipfile.BadZipFile as error:
        raise SourceHashError(f"{path.name} is not a readable ZIP archive: {error}")


def normalized_version_name(name: str) -> str:
    """Returns name with case and punctuation removed, for comparison."""
    return re.sub(r"[^0-9a-z]+", "", name.casefold())


def check_identity(
    source: dict, badging: dict[str, str | None], label: str, require_name: bool
) -> None:
    """Checks that a download is the package and version its source configures.

    The version name is required on a base APK and compared when a split carries one;
    a split's manifest records an empty version name, and the version code identifies
    it. The comparison removes case and punctuation because the tracker's spelling and
    the source's label differ in those alone.
    """
    if badging["package_name"] != source["package_name"]:
        raise SourceHashError(
            f"{label} declares package {badging['package_name']}, "
            f"but the source configures {source['package_name']}"
        )
    if int(badging["version_code"] or 0) != int(source["version_code"]):
        raise SourceHashError(
            f"{label} declares version code {badging['version_code']}, "
            f"but the source configures {source['version_code']}"
        )
    actual_name = badging["version_name"] or ""
    if actual_name:
        expected = normalized_version_name(source["version_name"])
        if normalized_version_name(actual_name) != expected:
            raise SourceHashError(
                f"{label} declares version name {actual_name}, "
                f"but the source configures {source['version_name']}"
            )
    elif require_name:
        raise SourceHashError(f"{label} declares no version name")


def check_split(
    label: str, badging: dict[str, str | None], url: str, names: list[str]
) -> None:
    """Checks that a split declares its URL's name and carries the content it implies.

    An ABI split carries the shared objects for the ABI it is named after, a density
    split carries a res/ tree, and a language or other resource split carries a
    resource table. A split that carries none of those is not the file the URL
    describes, whichever configuration it claims.
    """
    split_name = badging["split"]
    if not split_name:
        raise SourceHashError(f"{label} declares no split name")

    url_name = url.rstrip("/").rsplit("/", 1)[-1]
    if split_name != url_name:
        raise SourceHashError(
            f"{label} is split {split_name}, but its URL names {url_name}"
        )

    configuration = split_name.split(".", 1)[1] if "." in split_name else split_name
    if configuration in ABI_LIB_DIRECTORIES:
        library_prefix = f"lib/{ABI_LIB_DIRECTORIES[configuration]}/"
        if not any(name.startswith(library_prefix) for name in names):
            raise SourceHashError(
                f"{label} declares the {configuration} ABI but carries no "
                f"{library_prefix} entries"
            )
    elif configuration in DENSITY_CONFIGURATIONS:
        if not any(name.startswith("res/") for name in names):
            raise SourceHashError(
                f"{label} declares the {configuration} density but carries no res/ "
                f"entries"
            )
    elif "resources.arsc" not in names:
        raise SourceHashError(
            f"{label} carries no lib/ tree and no resource table, so it is not a "
            f"configuration split"
        )


def refresh_source(source: dict, work_dir: Path, aapt2: Path) -> None:
    """Downloads and checks every file of one source, then writes its hashes in place."""
    source_id = source["id"]
    print(f"[{source_id}] base: {source['url']}")

    base_path = work_dir / f"{source_id}.base.apk"
    declared = download(source["url"], base_path)
    badging = read_badging(aapt2, base_path)
    check_identity(source, badging, f"{source_id} base", require_name=True)
    digest, size = stable_sha256(base_path, declared)
    previous = source.get("sha256_expected")
    if previous != digest:
        print(f"  published hash: {previous or 'none'} -> {digest}")
    source["sha256_expected"] = digest
    print(f"  sha256 {digest} ({size} bytes)")
    base_path.unlink()

    entries = source.get("splits")
    if entries is None:
        entries = source.get("split_urls", [])

    splits = []
    for position, entry in enumerate(entries, start=1):
        url = entry["url"] if isinstance(entry, dict) else entry
        label = f"{source_id} split {position} ({url})"
        print(f"  split {position}: {url}")

        split_path = work_dir / f"{source_id}.split{position}.apk"
        declared = download(url, split_path)
        badging = read_badging(aapt2, split_path)
        check_identity(source, badging, label, require_name=False)
        check_split(label, badging, url, archive_names(split_path))
        digest, size = stable_sha256(split_path, declared)
        splits.append({"url": url, "sha256_expected": digest, "size_bytes": size})
        print(f"    sha256 {digest} ({size} bytes)")
        split_path.unlink()

    source.pop("split_urls", None)
    source["splits"] = splits


def run(repo_root: Path, aapt2_path: Path | None, work_dir: Path | None) -> None:
    """Refreshes the hashes in both copies of sources.json under repo_root."""
    manifest_path = repo_root / MANIFEST_NAME
    bundled_path = repo_root / BUNDLED_MANIFEST

    published = manifest_path.read_bytes()
    if published != bundled_path.read_bytes():
        raise SourceHashError(
            f"{MANIFEST_NAME} and {BUNDLED_MANIFEST} differ, so there is no single "
            f"document to update"
        )

    document = json.loads(published.decode("utf-8"))
    if document.get("schema_version") not in (1, SCHEMA_VERSION):
        raise SourceHashError(
            f"{MANIFEST_NAME} has schema version {document.get('schema_version')}, "
            f"and this script reads versions 1 and {SCHEMA_VERSION}"
        )
    document["schema_version"] = SCHEMA_VERSION

    aapt2 = resolve_aapt2(aapt2_path)
    print(f"aapt2: {aapt2}")

    created_work_dir = work_dir is None
    work_root = work_dir or Path(tempfile.mkdtemp(prefix="sleepy-source-hashes-"))
    work_root.mkdir(parents=True, exist_ok=True)
    try:
        for source in document["sources"]:
            refresh_source(source, work_root, aapt2)
    finally:
        if created_work_dir:
            shutil.rmtree(work_root, ignore_errors=True)

    text = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
    manifest_path.write_text(text, encoding="utf-8")
    bundled_path.write_text(text, encoding="utf-8")

    if manifest_path.read_bytes() != bundled_path.read_bytes():
        raise SourceHashError("the two copies of sources.json were written differently")
    print(f"updated {MANIFEST_NAME} and {BUNDLED_MANIFEST}")


def main() -> int:
    """Runs the refresh and reports a failure as a nonzero exit status."""
    arguments = parse_arguments()
    try:
        run(arguments.repo_root.resolve(), arguments.aapt2, arguments.work_dir)
    except SourceHashError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
