#!/usr/bin/env python3
"""Re-map the Hermes function table for a new Discord bundle.

The Kotlin table in ``DiscordHermesBundlePatch.kt`` names Hermes function ids,
and Hermes numbers functions per bundle. Every Discord release moves them, so
each entry must be mapped to the corresponding function in the target bundle
before its replacement body can be trusted.

This tool reads the old table and both bundles, then maps every old id:

1. **Unique name.** A name carried by exactly one function in each bundle is an
   anchor.
2. **Name and id window.** Duplicate names are narrowed to the functions
   bracketed by the surrounding unique-name anchors.
3. **Exact body.** A byte-identical body is strong evidence even when the name
   changed or is missing.
4. **Opcode and string similarity.** Candidates left after the steps above are
   disassembled with ``hermes-decomp`` and scored by opcode histogram plus
   referenced string literals. Without the decompiler, the tool falls back to
   byte similarity and says so.

No mapping is guessed silently. Entries the tool cannot map, candidates that
score too close together, entries whose target is gone, and entries that need a
patched replacement body are all listed in the report. A candidate Kotlin table
is written only when ``--emit-kotlin`` names an output path, and an incomplete
table is refused unless ``--allow-incomplete`` is passed as well.

The tool never edits the repository. It writes only to the report and candidate
paths passed on the command line, and it refuses to write the candidate over
its input table.

Examples::

    python3 -I tools/retarget/retarget_hermes.py \\
        --old-bundle /path/353.2.0/index.android.bundle \\
        --new-bundle /path/354.1/index.android.bundle \\
        --new-bundle-size 50882260 --new-function-count 141898 \\
        --patched-bundle /path/354.1-patched/index.android.bundle \\
        --report-json /tmp/retarget.json --report-txt /tmp/retarget.txt

    python3 -I tools/retarget/retarget_hermes.py --self-test
"""

from __future__ import annotations

import argparse
import bisect
import collections
import dataclasses
import difflib
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
DEFAULT_KOTLIN = os.path.join(
    REPO_ROOT,
    "app",
    "src",
    "main",
    "kotlin",
    "dev",
    "sleepy",
    "app",
    "patches",
    "DiscordHermesBundlePatch.kt",
)

HBC_MAGIC = bytes.fromhex("c61fbc03c103191f")
HBC_HEADER_SIZE = 128
HBC_MODERN_VERSION = 97
HBC_FUNCTION_ENTRY_SIZE = 12
HBC_OVERFLOW_FLAG = 0x20
HBC_OFFSET_MASK = 0x1FFFFFF
HBC_BYTECODE_SIZE_MASK = 0x3FFF
HBC_LARGE_HEADER_SIZE_FIELD = 12
HBC_LARGE_HEADER_NAME_FIELD = 16
HBC_LARGE_HEADER_BYTES = 20

FUNCTION_PATCH_RE = re.compile(
    r"(?<![A-Za-z0-9_])FunctionPatch\(\s*"
    r"(\d+)\s*,\s*"
    r'"((?:\\.|[^"\\])*)"\s*,\s*'
    r"(\d+)\s*,\s*"
    r'"([0-9a-fA-F]*)"\s*\)',
    re.S,
)
PATCHES_HEADER_RE = re.compile(
    r"val\s+PATCHES\s*:\s*List<FunctionPatch>\s*=\s*listOf\s*\("
)
BUNDLE_SIZE_CONST_RE = re.compile(r"\bTARGET_BUNDLE_SIZE\s*=\s*(\d[\d_]*)")
FUNCTION_COUNT_CONST_RE = re.compile(r"\bTARGET_FUNCTION_COUNT\s*=\s*(\d[\d_]*)")
DUMP_LINE_RE = re.compile(
    r"^\s*(\d+)\s+(.*?)\s+(?:0x)?([0-9a-fA-F]+)\s+(\d+)\s*$"
)
ANONYMOUS_NAME_RE = re.compile(r"f\d+")
HEX_RE = re.compile(r"^[0-9a-fA-F]*$")

DEFAULT_STUB_MAX_BYTES = 12
DEFAULT_THRESHOLD = 0.80
DEFAULT_ANONYMOUS_THRESHOLD = 0.90
DEFAULT_ABSENT_THRESHOLD = 0.95
DEFAULT_RETIRE_THRESHOLD = 0.45
DEFAULT_MARGIN = 0.02
DEFAULT_ABSENT_MARGIN = 0.05
DEFAULT_MAX_CANDIDATES = 80
DEFAULT_MAX_WINDOW = 400


class ToolError(RuntimeError):
    """A fatal input or consistency error."""


@dataclasses.dataclass(frozen=True)
class FunctionInfo:
    function_id: int
    name: str
    body_offset: int
    size: int
    params: int = 0
    flags: int = 0


@dataclasses.dataclass(frozen=True)
class KotlinEntry:
    function_id: int
    name: str
    original_size: int
    replacement_hex: str


@dataclasses.dataclass
class KotlinTable:
    path: str
    source: str
    entries: list[KotlinEntry]
    bundle_size: int
    function_count: int


@dataclasses.dataclass
class SimilarityInfo:
    mode: str
    opcodes: collections.Counter
    strings: set[str]
    body: bytes


@dataclasses.dataclass
class Resolution:
    old: KotlinEntry
    old_function: FunctionInfo
    new_id: int | None
    new_function: FunctionInfo | None
    status: str
    method: str
    confidence: float
    window_lo: int | None
    window_hi: int | None
    best_score: float | None
    runner_up: float | None
    candidates: list[dict]
    reason: str
    replacement_kind: str
    replacement_detail: str
    replacement: bytes | None = None
    replacement_source: str | None = None
    emitted: bool = False
    warnings: list[str] = dataclasses.field(default_factory=list)

    def to_dict(self) -> dict:
        old_info = self.old
        new_info = self.new_function
        return {
            "old_id": old_info.function_id,
            "old_name": old_info.name,
            "old_size": old_info.original_size,
            "replacement_bytes": len(old_info.replacement_hex) // 2,
            "status": self.status,
            "method": self.method,
            "confidence": round(self.confidence, 4),
            "new_id": self.new_id,
            "new_name": new_info.name if new_info else None,
            "new_size": new_info.size if new_info else None,
            "window": [self.window_lo, self.window_hi],
            "scores": {
                "best": None if self.best_score is None else round(self.best_score, 4),
                "runner_up": None
                if self.runner_up is None
                else round(self.runner_up, 4),
            },
            "candidates": self.candidates,
            "reason": self.reason,
            "replacement_kind": self.replacement_kind,
            "replacement_detail": self.replacement_detail,
            "replacement_source": self.replacement_source,
            "replacement_size": None if self.replacement is None else len(self.replacement),
            "emitted": self.emitted,
            "warnings": self.warnings,
        }


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def read_file(path: str, label: str) -> bytes:
    try:
        with open(path, "rb") as handle:
            return handle.read()
    except OSError as exc:
        raise ToolError(f"cannot read {label} {path}: {exc}") from exc


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class HbcBundle:
    """The function and string tables of a Hermes bytecode bundle.

    The layout is the Modern12 one used by HBC 97 and newer, matching
    ``HermesFunctionTable.kt``. Every offset and count is bounds-checked so a
    malformed or unexpected bundle fails with the function id that made it
    fail instead of being read as a different function.
    """

    def __init__(
        self,
        path: str,
        label: str,
        expected_size: int | None = None,
        expected_count: int | None = None,
    ):
        self.path = os.path.abspath(path)
        self.label = label
        self.data = read_file(self.path, label)
        self.size = len(self.data)
        if self.size < HBC_HEADER_SIZE:
            raise ToolError(f"{label} is {self.size} bytes, shorter than an HBC header")
        if self.data[:8] != HBC_MAGIC:
            raise ToolError(
                f"{label} does not start with the HBC magic "
                f"{HBC_MAGIC.hex()} (found {self.data[:8].hex()})"
            )
        self.version = u32(self.data, 8)
        if self.version < HBC_MODERN_VERSION:
            raise ToolError(
                f"{label} is HBC version {self.version}; this tool reads the "
                f"Modern12 layout used by version {HBC_MODERN_VERSION} and newer"
            )
        header_size = u32(self.data, 32)
        if header_size != self.size:
            raise ToolError(
                f"{label} header says {header_size} bytes but the file is "
                f"{self.size} bytes"
            )
        if expected_size is not None and self.size != expected_size:
            raise ToolError(
                f"{label} is {self.size} bytes but {expected_size} was expected"
            )

        self.function_count = u32(self.data, 40)
        self.string_kind_count = u32(self.data, 44)
        self.identifier_count = u32(self.data, 48)
        self.string_count = u32(self.data, 52)
        self.overflow_string_count = u32(self.data, 56)
        self.string_storage_size = u32(self.data, 60)
        if expected_count is not None and self.function_count != expected_count:
            raise ToolError(
                f"{label} declares {self.function_count} functions but "
                f"{expected_count} was expected"
            )
        if self.function_count <= 0:
            raise ToolError(f"{label} declares no functions")

        self._function_table = HBC_HEADER_SIZE
        self._string_table = (
            self._function_table
            + HBC_FUNCTION_ENTRY_SIZE * self.function_count
            + 4 * self.string_kind_count
            + 4 * self.identifier_count
        )
        self._overflow_table = self._string_table + 4 * self.string_count
        self._string_storage = (
            self._overflow_table + 8 * self.overflow_string_count
        )
        if self._string_table > self.size:
            raise ToolError(
                f"{label} function and identifier tables end past the file"
            )
        if self._overflow_table > self.size:
            raise ToolError(f"{label} string table ends past the file")
        if self._string_storage > self.size:
            raise ToolError(f"{label} overflow string table ends past the file")
        if self._string_storage + self.string_storage_size > self.size:
            raise ToolError(
                f"{label} string storage needs {self.string_storage_size} bytes "
                f"but only {self.size - self._string_storage} remain"
            )

        self._strings: dict[int, str] = {}
        self._functions: list[FunctionInfo] | None = None
        self._body_hashes: dict[bytes, list[int]] | None = None

    def string(self, index: int) -> str:
        if index in self._strings:
            return self._strings[index]
        if index < 0 or index >= self.string_count:
            raise ToolError(
                f"{self.label} string index {index} is outside 0..{self.string_count - 1}"
            )
        entry = u32(self.data, self._string_table + 4 * index)
        offset_field = entry & 0xFFFFFF
        length = entry >> 24
        if length == 255:
            overflow_index = offset_field >> 1
            if overflow_index >= self.overflow_string_count:
                raise ToolError(
                    f"{self.label} string {index} points at overflow entry "
                    f"{overflow_index}, but only {self.overflow_string_count} exist"
                )
            overflow_offset = self._overflow_table + 8 * overflow_index
            raw_offset = u32(self.data, overflow_offset)
            length = u32(self.data, overflow_offset + 4)
        else:
            raw_offset = offset_field

        byte_offset = raw_offset >> 1
        utf16 = bool(raw_offset & 1)
        byte_length = length * 2 if utf16 else length
        start = self._string_storage + byte_offset
        end = start + byte_length
        if byte_offset < 0 or end > self._string_storage + self.string_storage_size:
            raise ToolError(
                f"{self.label} string {index} body [{start}, {end}) is outside "
                f"the string storage"
            )
        raw = self.data[start:end]
        try:
            value = raw.decode("utf-16-le" if utf16 else "latin-1")
        except UnicodeDecodeError as exc:
            raise ToolError(
                f"{self.label} string {index} cannot be decoded: {exc}"
            ) from exc
        self._strings[index] = value
        return value

    def function(self, function_id: int) -> FunctionInfo:
        if function_id < 0 or function_id >= self.function_count:
            raise ToolError(
                f"{self.label} function id {function_id} is outside "
                f"0..{self.function_count - 1}"
            )
        slot = self._function_table + HBC_FUNCTION_ENTRY_SIZE * function_id
        word0 = u32(self.data, slot)
        word1 = u32(self.data, slot + 4)
        word2 = u32(self.data, slot + 8)
        flags = (word2 >> 24) & 0xFF
        if flags & HBC_OVERFLOW_FLAG:
            name_bits = (word1 >> 14) & 0xFF
            large_offset = (name_bits << 24) | (word0 & HBC_OFFSET_MASK)
            if (
                large_offset <= 0
                or large_offset + HBC_LARGE_HEADER_BYTES > self.size
            ):
                raise ToolError(
                    f"{self.label} function {function_id} has an invalid large "
                    f"header offset {large_offset}"
                )
            body_offset = u32(self.data, large_offset)
            size = u32(self.data, large_offset + HBC_LARGE_HEADER_SIZE_FIELD)
            name_index = u32(self.data, large_offset + HBC_LARGE_HEADER_NAME_FIELD)
            params = 0
        else:
            body_offset = word0 & HBC_OFFSET_MASK
            size = word1 & HBC_BYTECODE_SIZE_MASK
            name_index = (word1 >> 14) & 0xFF
            params = (word0 >> 25) & 0x1F

        if body_offset < 0 or size < 0 or body_offset + size > self.size:
            raise ToolError(
                f"{self.label} function {function_id} body [{body_offset}, "
                f"{body_offset + size}) is outside the file"
            )
        return FunctionInfo(
            function_id=function_id,
            name=self.string(name_index),
            body_offset=body_offset,
            size=size,
            params=params,
            flags=flags,
        )

    def functions(self) -> list[FunctionInfo]:
        if self._functions is None:
            self._functions = [
                self.function(function_id)
                for function_id in range(self.function_count)
            ]
        return self._functions

    def body(self, function_id: int) -> bytes:
        info = self.function(function_id)
        return self.data[info.body_offset : info.body_offset + info.size]

    def body_hashes(self) -> dict[bytes, list[int]]:
        if self._body_hashes is None:
            index: dict[bytes, list[int]] = collections.defaultdict(list)
            for info in self.functions():
                digest = hashlib.sha256(
                    self.data[info.body_offset : info.body_offset + info.size]
                ).digest()
                index[digest].append(info.function_id)
            self._body_hashes = index
        return self._body_hashes

    def summary(self) -> dict:
        return {
            "path": self.path,
            "label": self.label,
            "version": self.version,
            "size": self.size,
            "sha256": sha256_bytes(self.data),
            "function_count": self.function_count,
        }


def load_function_dump(path: str, label: str) -> dict[int, FunctionInfo]:
    """Reads a function table written by ``hermes-decomp dump --kind functions``."""
    raw = read_file(path, label)
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ToolError(f"{label} {path} is not UTF-8: {exc}") from exc
    stripped = text.lstrip()
    if stripped.startswith("[") or stripped.startswith("{"):
        try:
            document = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ToolError(f"{label} {path} is not valid JSON: {exc}") from exc
        if isinstance(document, dict):
            document = document.get("functions", document.get("entries"))
        if not isinstance(document, list):
            raise ToolError(
                f"{label} {path} must hold a JSON list of function objects"
            )
        result: dict[int, FunctionInfo] = {}
        for index, item in enumerate(document):
            if not isinstance(item, dict):
                raise ToolError(f"{label} entry {index} is not an object")
            try:
                function_id = int(item["id"])
                name = item.get("name") or ""
                body_offset = item.get("offset", item.get("body_offset"))
                if isinstance(body_offset, str):
                    body_offset = int(body_offset, 16)
                size = int(item.get("size", item.get("bytecodeSize")))
                params = int(item.get("params", item.get("paramCount", 0)))
            except (KeyError, TypeError, ValueError) as exc:
                raise ToolError(
                    f"{label} entry {index} is missing id/offset/size: {exc}"
                ) from exc
            if function_id in result:
                raise ToolError(f"{label} repeats function id {function_id}")
            result[function_id] = FunctionInfo(
                function_id=function_id,
                name=str(name),
                body_offset=int(body_offset),
                size=size,
                params=params,
            )
        return result

    result = {}
    for line_number, line in enumerate(text.splitlines(), 1):
        match = DUMP_LINE_RE.match(line)
        if not match:
            continue
        function_id = int(match.group(1))
        if function_id in result:
            raise ToolError(f"{label} line {line_number} repeats id {function_id}")
        result[function_id] = FunctionInfo(
            function_id=function_id,
            name=match.group(2),
            body_offset=int(match.group(3), 16),
            size=int(match.group(4)),
        )
    if not result:
        raise ToolError(f"{label} {path} holds no function rows")
    return result


def verify_dump_against_bundle(
    dump: dict[int, FunctionInfo],
    bundle: HbcBundle,
    dump_label: str,
) -> None:
    if len(dump) != bundle.function_count:
        raise ToolError(
            f"{dump_label} has {len(dump)} functions but {bundle.label} has "
            f"{bundle.function_count}"
        )
    for function_id, dumped in dump.items():
        if function_id < 0 or function_id >= bundle.function_count:
            raise ToolError(
                f"{dump_label} function id {function_id} is outside the bundle"
            )
        actual = bundle.function(function_id)
        if (
            dumped.name != actual.name
            or dumped.size != actual.size
            or dumped.body_offset != actual.body_offset
        ):
            raise ToolError(
                f"{dump_label} disagrees with {bundle.label} at function "
                f"{function_id}: dump name={dumped.name!r} offset={dumped.body_offset} "
                f"size={dumped.size}; bundle name={actual.name!r} "
                f"offset={actual.body_offset} size={actual.size}"
            )


def decode_kotlin_string(raw: str, context: str) -> str:
    """Decodes the escapes used in the table's ordinary Kotlin string literals."""
    out: list[str] = []
    index = 0
    while index < len(raw):
        char = raw[index]
        if char != "\\":
            out.append(char)
            index += 1
            continue
        index += 1
        if index >= len(raw):
            raise ToolError(f"unterminated escape in {context}")
        escape = raw[index]
        index += 1
        if escape == "u":
            digits = raw[index : index + 4]
            if len(digits) != 4 or not all(c in "0123456789abcdefABCDEF" for c in digits):
                raise ToolError(f"invalid \\u escape in {context}")
            out.append(chr(int(digits, 16)))
            index += 4
        elif escape == "n":
            out.append("\n")
        elif escape == "r":
            out.append("\r")
        elif escape == "t":
            out.append("\t")
        elif escape == "b":
            out.append("\b")
        elif escape == "\\":
            out.append("\\")
        elif escape == '"':
            out.append('"')
        elif escape == "'":
            out.append("'")
        elif escape == "$":
            out.append("$")
        else:
            raise ToolError(f"unsupported escape \\{escape} in {context}")
    return "".join(out)


def parse_kotlin_table(path: str) -> KotlinTable:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            source = handle.read()
    except OSError as exc:
        raise ToolError(f"cannot read Kotlin table {path}: {exc}") from exc

    entries: list[KotlinEntry] = []
    seen: set[int] = set()
    for match in FUNCTION_PATCH_RE.finditer(source):
        function_id = int(match.group(1))
        if function_id in seen:
            raise ToolError(
                f"{path} lists function id {function_id} more than once"
            )
        seen.add(function_id)
        name = decode_kotlin_string(
            match.group(2), f"{path} function {function_id} name"
        )
        replacement_hex = match.group(4)
        if len(replacement_hex) % 2 != 0 or not HEX_RE.fullmatch(replacement_hex):
            raise ToolError(
                f"{path} function {function_id} has an invalid replacement hex string"
            )
        if not replacement_hex:
            raise ToolError(
                f"{path} function {function_id} has an empty replacement body"
            )
        entries.append(
            KotlinEntry(
                function_id=function_id,
                name=name,
                original_size=int(match.group(3)),
                replacement_hex=replacement_hex,
            )
        )

    if not entries:
        raise ToolError(f"{path} holds no FunctionPatch entries")
    call_lines = len(
        re.findall(r"^[ \t]*FunctionPatch\s*\(", source, flags=re.MULTILINE)
    )
    if call_lines != len(entries):
        raise ToolError(
            f"{path} has {call_lines} FunctionPatch call lines but only "
            f"{len(entries)} parsed; an entry may not use the expected "
            f"functionId, name, originalSize, replacementHex order"
        )

    size_match = BUNDLE_SIZE_CONST_RE.search(source)
    count_match = FUNCTION_COUNT_CONST_RE.search(source)
    if not size_match or not count_match:
        raise ToolError(
            f"{path} does not declare TARGET_BUNDLE_SIZE and "
            f"TARGET_FUNCTION_COUNT"
        )
    bundle_size = int(size_match.group(1).replace("_", ""))
    function_count = int(count_match.group(1).replace("_", ""))
    return KotlinTable(
        path=os.path.abspath(path),
        source=source,
        entries=entries,
        bundle_size=bundle_size,
        function_count=function_count,
    )


def _skip_kotlin_string(source: str, index: int) -> int:
    """Returns the index just past a string literal starting at ``index``."""
    if source.startswith('"""', index):
        end = source.find('"""', index + 3)
        if end < 0:
            raise ToolError("unterminated Kotlin raw string")
        return end + 3
    if source[index] == '"':
        index += 1
        while index < len(source):
            if source[index] == "\\":
                index += 2
            elif source[index] == '"':
                return index + 1
            else:
                index += 1
        raise ToolError("unterminated Kotlin string")
    if source[index] == "'":
        index += 1
        while index < len(source):
            if source[index] == "\\":
                index += 2
            elif source[index] == "'":
                return index + 1
            else:
                index += 1
        raise ToolError("unterminated Kotlin character literal")
    return index


def _skip_kotlin_comment(source: str, index: int) -> int:
    if source.startswith("//", index):
        end = source.find("\n", index + 2)
        return len(source) if end < 0 else end + 1
    if source.startswith("/*", index):
        end = source.find("*/", index + 2)
        if end < 0:
            raise ToolError("unterminated Kotlin block comment")
        return end + 2
    return index


def find_matching_paren(source: str, open_index: int) -> int:
    if source[open_index] != "(":
        raise ToolError("internal error: expected an opening parenthesis")
    depth = 1
    index = open_index + 1
    while index < len(source):
        char = source[index]
        if char in ('"', "'"):
            index = _skip_kotlin_string(source, index)
        elif source.startswith("//", index) or source.startswith("/*", index):
            index = _skip_kotlin_comment(source, index)
        elif char == "(":
            depth += 1
            index += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index
            index += 1
        else:
            index += 1
    raise ToolError("unterminated Kotlin call: no matching ')'")


def kotlin_string(value: str) -> str:
    out = ['"']
    for char in value:
        if char == "\\":
            out.append("\\\\")
        elif char == '"':
            out.append('\\"')
        elif char == "$":
            out.append("${'$'}")
        elif char == "\n":
            out.append("\\n")
        elif char == "\r":
            out.append("\\r")
        elif char == "\t":
            out.append("\\t")
        elif ord(char) < 0x20:
            out.append(f"\\u{ord(char):04x}")
        else:
            out.append(char)
    out.append('"')
    return "".join(out)


def render_candidate_table(table: KotlinTable, entries: list[KotlinEntry]) -> str:
    if not entries:
        raise ToolError("refusing to write a candidate table with no entries")
    if not BUNDLE_SIZE_CONST_RE.search(table.source) or not FUNCTION_COUNT_CONST_RE.search(
        table.source
    ):
        raise ToolError("could not find the table's target constants")

    header = PATCHES_HEADER_RE.search(table.source)
    if not header:
        raise ToolError("could not find the PATCHES list in the Kotlin table")
    list_start = table.source.find("listOf", header.start(), header.end())
    if list_start < 0:
        raise ToolError("could not find the listOf call in the PATCHES list")
    open_paren = table.source.find("(", list_start)
    close_paren = find_matching_paren(table.source, open_paren)

    # The table is assembled from the input so comments and helper functions
    # survive, then its target constants are replaced with the observed target.
    lines = ["listOf("]
    for entry in entries:
        name = kotlin_string(entry.name)
        if len(entry.replacement_hex) <= 64:
            lines.append(
                f'        FunctionPatch({entry.function_id}, {name}, '
                f'{entry.original_size}, "{entry.replacement_hex}"),'
            )
        else:
            lines.append("        FunctionPatch(")
            lines.append(
                f"            {entry.function_id}, {name}, "
                f"{entry.original_size},"
            )
            lines.append(f'            "{entry.replacement_hex}"')
            lines.append("        ),")
    lines.append("    )")
    candidate = table.source[:list_start] + "\n".join(lines) + table.source[close_paren + 1 :]

    # APPLY the constants to the assembled text, not the original source.
    candidate = BUNDLE_SIZE_CONST_RE.sub(
        lambda match: match.group(0).replace(
            match.group(1), str(table.bundle_size)
        ),
        candidate,
        count=1,
    )
    candidate = FUNCTION_COUNT_CONST_RE.sub(
        lambda match: match.group(0).replace(
            match.group(1), str(table.function_count)
        ),
        candidate,
        count=1,
    )
    return candidate


def byte_similarity(left: bytes, right: bytes) -> float:
    if left == right:
        return 1.0
    if not left or not right:
        return 0.0
    if max(len(left), len(right)) <= 1024:
        return difflib.SequenceMatcher(None, left, right, autojunk=False).ratio()
    window = 4
    left_grams = collections.Counter(
        left[index : index + window] for index in range(len(left) - window + 1)
    )
    right_grams = collections.Counter(
        right[index : index + window] for index in range(len(right) - window + 1)
    )
    intersection = sum((left_grams & right_grams).values())
    union = sum((left_grams | right_grams).values())
    return intersection / union if union else 0.0


class SimilarityProvider:
    """Opcode and string signatures, or a byte fallback when no decompiler exists."""

    def __init__(self, decompiler: str | None, timeout: int, verbose: bool):
        self.decompiler = decompiler
        self.timeout = timeout
        self.verbose = verbose
        self._cache: dict[tuple[str, int], SimilarityInfo] = {}
        self._warned: set[str] = set()

    def _warn(self, message: str) -> None:
        if message not in self._warned:
            self._warned.add(message)
            print(f"warning: {message}", file=sys.stderr)

    def _disassemble(self, bundle: HbcBundle, function_id: int) -> SimilarityInfo | None:
        if not self.decompiler:
            return None
        try:
            completed = subprocess.run(
                [
                    self.decompiler,
                    "disasm",
                    "--function",
                    str(function_id),
                    bundle.path,
                ],
                capture_output=True,
                text=True,
                timeout=self.timeout,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            self._warn(
                f"hermes-decomp could not disassemble {bundle.label} function "
                f"{function_id} ({exc}); using byte similarity"
            )
            return None
        if completed.returncode != 0:
            self._warn(
                f"hermes-decomp exited {completed.returncode} for {bundle.label} "
                f"function {function_id}; using byte similarity"
            )
            return None
        opcodes: collections.Counter = collections.Counter()
        strings: set[str] = set()
        for line in completed.stdout.splitlines():
            match = re.match(r"^\s*(?:L\d+:\s*)?([A-Z][A-Za-z0-9]+)", line)
            if match:
                opcodes[match.group(1)] += 1
            string_match = re.search(
                r"LoadConstString(?:LongIndex)?\s+r\d+,\s*(.*?)\s*$", line
            )
            if string_match:
                strings.add(string_match.group(1).strip())
        if not opcodes:
            self._warn(
                f"hermes-decomp produced no instructions for {bundle.label} "
                f"function {function_id}; using byte similarity"
            )
            return None
        return SimilarityInfo(
            mode="opcode+string",
            opcodes=opcodes,
            strings=strings,
            body=b"",
        )

    def signature(self, bundle: HbcBundle, function_id: int) -> SimilarityInfo:
        key = (bundle.path, function_id)
        if key in self._cache:
            return self._cache[key]
        info = self._disassemble(bundle, function_id)
        if info is None:
            info = SimilarityInfo(
                mode="byte",
                opcodes=collections.Counter(),
                strings=set(),
                body=bundle.body(function_id),
            )
        self._cache[key] = info
        return info

    def score(
        self,
        old_bundle: HbcBundle,
        old_id: int,
        new_bundle: HbcBundle,
        new_id: int,
    ) -> tuple[float, str]:
        old = self.signature(old_bundle, old_id)
        new = self.signature(new_bundle, new_id)
        if old.mode == "opcode+string" and new.mode == "opcode+string":
            if not old.opcodes and not new.opcodes:
                return 0.0, "opcode+string"
            opcode_similarity = sum((old.opcodes & new.opcodes).values()) / max(
                1, sum((old.opcodes | new.opcodes).values())
            )
            if old.strings or new.strings:
                string_similarity = (
                    2
                    * len(old.strings & new.strings)
                    / (len(old.strings) + len(new.strings))
                )
            else:
                string_similarity = 1.0
            return 0.6 * opcode_similarity + 0.4 * string_similarity, "opcode+string"
        return byte_similarity(old.body, new.body), "byte"


def bracket_anchors(
    anchor_old: list[int],
    anchor_new: list[int],
    old_id: int,
) -> tuple[int | None, int | None]:
    """The new-id range between the nearest anchors that still move forward.

    Metro module order is mostly stable, so the nearest unique-name anchor on
    each side is the strongest positional evidence available. A few dozen
    anchors move backwards across a release; when the nearest pair is inverted,
    walk outwards for the nearest pair that is still ordered. If no ordered pair
    exists, return an open or empty range, and the caller reports the entry
    rather than inventing a window.
    """
    index = bisect.bisect_left(anchor_old, old_id)
    lower = anchor_new[index - 1] if index > 0 else None
    upper = anchor_new[index] if index < len(anchor_new) else None
    if lower is None or upper is None or lower < upper:
        return lower, upper

    # Search a bounded neighbourhood first; an inversion is local in practice.
    left = index - 1
    right = index
    for _ in range(256):
        if left > 0:
            left -= 1
        if right + 1 < len(anchor_new):
            right += 1
        if anchor_new[left] < anchor_new[right]:
            return anchor_new[left], anchor_new[right]
        if left == 0 and right + 1 >= len(anchor_new):
            break
    return None, None


def build_anchors(
    old_functions: list[FunctionInfo],
    new_functions: list[FunctionInfo],
    verbose: bool,
) -> tuple[list[int], list[int], int]:
    old_by_name: dict[str, list[int]] = collections.defaultdict(list)
    new_by_name: dict[str, list[int]] = collections.defaultdict(list)
    for info in old_functions:
        if info.name:
            old_by_name[info.name].append(info.function_id)
    for info in new_functions:
        if info.name:
            new_by_name[info.name].append(info.function_id)

    raw: list[tuple[int, int]] = []
    for name, old_ids in old_by_name.items():
        new_ids = new_by_name.get(name)
        if len(old_ids) == 1 and new_ids is not None and len(new_ids) == 1:
            raw.append((old_ids[0], new_ids[0]))
    raw.sort()

    # Keep every anchor. Metro's module order is stable enough that the
    # nearest anchors are useful even where a few dozen individual functions
    # moved backwards; bracket_anchors() steps outwards only around those
    # inversions. Filtering to a monotonic subsequence would discard almost
    # all 30,000 anchors and turn every window into the whole bundle.
    inversions = sum(
        1 for (_, first), (_, second) in zip(raw, raw[1:]) if second <= first
    )
    if inversions and verbose:
        print(
            f"note: {inversions} unique-name anchors moved backwards in the new "
            f"bundle; local id windows will step around them",
            file=sys.stderr,
        )
    return (
        [old for old, _ in raw],
        [new for _, new in raw],
        len(raw),
    )


def meaningful_name(name: str) -> bool:
    return bool(name) and ANONYMOUS_NAME_RE.fullmatch(name) is None


def classify_replacement(
    entry: KotlinEntry,
    old_version: int,
    new_version: int,
    stub_max_bytes: int,
) -> tuple[str, str]:
    body = bytes.fromhex(entry.replacement_hex)
    length = len(body)
    if length <= stub_max_bytes and body.endswith(b"\x76\x00"):
        if old_version == new_version:
            return (
                "generic_stub",
                f"{length}-byte constant stub; no bundle-specific operands",
            )
        return (
            "needs_patched_body",
            f"{length}-byte stub, but bytecode version changed "
            f"{old_version}->{new_version}; re-encode it",
        )
    if length <= stub_max_bytes:
        return (
            "needs_patched_body",
            f"{length}-byte replacement does not end in Ret; not a reusable stub",
        )
    if 16 <= length <= 32 and body.endswith(b"\x76\x00"):
        return (
            "needs_patched_body",
            f"{length}-byte promise-shaped replacement; extract it from the "
            f"patched bundle",
        )
    return (
        "needs_patched_body",
        f"{length}-byte replacement body; extract it from the patched bundle",
    )


def candidate_summary(
    new_functions: dict[int, FunctionInfo],
    scores: list[tuple[float, int]],
    limit: int = 5,
) -> list[dict]:
    result = []
    for score, function_id in scores[:limit]:
        info = new_functions[function_id]
        result.append(
            {
                "id": function_id,
                "name": info.name,
                "size": info.size,
                "score": round(score, 4),
            }
        )
    return result


def resolve_entries(
    table: KotlinTable,
    old_bundle: HbcBundle,
    new_bundle: HbcBundle,
    old_functions: dict[int, FunctionInfo],
    new_functions: dict[int, FunctionInfo],
    new_by_name: dict[str, list[int]],
    new_body_hashes: dict[bytes, list[int]],
    anchor_old: list[int],
    anchor_new: list[int],
    similarity: SimilarityProvider,
    args: argparse.Namespace,
) -> list[Resolution]:
    new_ids_sorted = sorted(new_functions)
    resolutions: list[Resolution] = []

    for entry in table.entries:
        old_info = old_functions.get(entry.function_id)
        if old_info is None:
            raise ToolError(
                f"Kotlin table names function {entry.function_id}, which is not "
                f"in {old_bundle.label}"
            )
        if old_info.size != entry.original_size:
            raise ToolError(
                f"Kotlin table function {entry.function_id} declares size "
                f"{entry.original_size} but {old_bundle.label} has "
                f"{old_info.size}; this table was not built for that bundle"
            )
        if entry.name and old_info.name and entry.name != old_info.name:
            raise ToolError(
                f"Kotlin table function {entry.function_id} is named "
                f"{entry.name!r} but {old_bundle.label} names it "
                f"{old_info.name!r}"
            )
        old_body = old_bundle.data[
            old_info.body_offset : old_info.body_offset + old_info.size
        ]
        old_hash = hashlib.sha256(old_body).digest()
        replacement_kind, replacement_detail = classify_replacement(
            entry,
            old_bundle.version,
            new_bundle.version,
            args.stub_max_bytes,
        )

        lo, hi = bracket_anchors(anchor_old, anchor_new, entry.function_id)
        window_size = 0
        window_ids: list[int] | None = None
        if lo is not None and hi is not None and hi > lo:
            window_size = bisect.bisect_left(new_ids_sorted, hi) - bisect.bisect_right(
                new_ids_sorted, lo
            )
            if window_size <= args.max_window:
                window_ids = [
                    function_id
                    for function_id in new_ids_sorted
                    if lo < function_id < hi
                ]

        def make_resolution(
            new_id: int,
            status: str,
            method: str,
            confidence: float,
            reason: str,
            best_score: float | None = None,
            runner_up: float | None = None,
            candidates: list[dict] | None = None,
        ) -> Resolution:
            return Resolution(
                old=entry,
                old_function=old_info,
                new_id=new_id,
                new_function=new_functions.get(new_id),
                status=status,
                method=method,
                confidence=confidence,
                window_lo=lo,
                window_hi=hi,
                best_score=best_score,
                runner_up=runner_up,
                candidates=candidates or [],
                reason=reason,
                replacement_kind=replacement_kind,
                replacement_detail=replacement_detail,
            )

        def stop(
            status: str,
            method: str,
            confidence: float,
            reason: str,
            best_score: float | None = None,
            runner_up: float | None = None,
            candidates: list[dict] | None = None,
        ) -> Resolution:
            return make_resolution(
                None,
                status,
                method,
                confidence,
                reason,
                best_score,
                runner_up,
                candidates,
            )

        exact_set = set(new_body_hashes.get(old_hash, []))

        def score_pool(pool: list[int]) -> tuple[list[tuple[float, int]], str]:
            # Exact bodies first: they cost one hash lookup and are the
            # strongest evidence available without disassembling.
            exact = [function_id for function_id in pool if function_id in exact_set]
            if len(exact) == 1:
                return [(1.0, exact[0])], "exact-body"
            scored: list[tuple[float, int]] = []
            mode = "byte"
            for function_id in pool:
                score, score_mode = similarity.score(
                    old_bundle, entry.function_id, new_bundle, function_id
                )
                if score_mode == "opcode+string":
                    mode = "opcode+string"
                scored.append((score, function_id))
            scored.sort(key=lambda item: (-item[0], item[1]))
            return scored, mode

        if meaningful_name(old_info.name):
            named = new_by_name.get(old_info.name, [])
            if len(named) == 1:
                resolutions.append(
                    make_resolution(
                        named[0],
                        "resolved",
                        "unique-name",
                        1.0,
                        "the new bundle carries this name exactly once",
                    )
                )
                continue
            if named:
                pool = [
                    function_id
                    for function_id in named
                    if lo is None or hi is None or lo < function_id < hi
                ]
                if len(pool) == 1:
                    resolutions.append(
                        make_resolution(
                            pool[0],
                            "resolved",
                            "name+id-window",
                            0.95,
                            "the name is duplicated but only one occurrence is "
                            "inside the surrounding unique-name anchors",
                        )
                    )
                    continue
                if not pool:
                    pool = named
                    window_note = "no candidate is inside the id window"
                else:
                    window_note = "candidates narrowed by the id window"
                if len(pool) > args.max_window:
                    resolutions.append(
                        stop(
                            "unresolved",
                            "none",
                            0.0,
                            f"{len(pool)} name candidates and the id window is too "
                            f"wide to score (limit {args.max_window}); {window_note}",
                        )
                    )
                    continue
                if len(pool) > args.max_candidates:
                    pool = _shortlist_by_bytes(
                        old_bundle, entry.function_id, new_bundle, pool, old_body,
                        args.max_candidates,
                    )
                scored, mode = score_pool(pool)
                best_score, best_id = scored[0]
                runner = scored[1][0] if len(scored) > 1 else 0.0
                candidates = candidate_summary(new_functions, scored)
                if (
                    best_score >= args.threshold
                    and best_score - runner >= args.margin
                ):
                    resolutions.append(
                        make_resolution(
                            best_id,
                            "resolved",
                            f"name+{mode}-similarity",
                            best_score,
                            f"{len(named)} functions carry this name; {window_note}; "
                            f"best score beats the runner-up by at least "
                            f"{args.margin}",
                            best_score,
                            runner,
                            candidates,
                        )
                    )
                else:
                    resolutions.append(
                        stop(
                            "ambiguous",
                            "none",
                            0.0,
                            f"{len(named)} functions carry this name; {window_note}; "
                            f"best={best_score:.3f}, runner-up={runner:.3f}, "
                            f"margin={args.margin}",
                            best_score,
                            runner,
                            candidates,
                        )
                    )
                continue

            # The name is gone. Try the window and body evidence.
            exact_ids = [
                function_id
                for function_id in new_body_hashes.get(old_hash, [])
                if lo is None or hi is None or lo < function_id < hi
            ]
            if len(exact_ids) == 1 and (window_ids is not None or lo is None):
                method = "name-absent+exact-body"
                note = "name is gone but exactly one body in the id window is identical"
                if lo is None:
                    method = "name-absent+exact-body-global"
                    note = "name is gone but exactly one body in the bundle is identical"
                resolutions.append(
                    make_resolution(
                        exact_ids[0],
                        "resolved",
                        method,
                        0.98,
                        note,
                        1.0,
                        0.0,
                        [
                            {
                                "id": exact_ids[0],
                                "name": new_functions[exact_ids[0]].name,
                                "size": new_functions[exact_ids[0]].size,
                                "score": 1.0,
                            }
                        ],
                    )
                )
                continue
            if window_ids is None or not window_ids:
                resolutions.append(
                    stop(
                        "unresolved",
                        "none",
                        0.0,
                        "the name is absent and no id-window candidate exists; "
                        "a human must identify the target",
                    )
                )
                continue
            if replacement_kind == "generic_stub":
                resolutions.append(
                    stop(
                        "unresolved",
                        "none",
                        0.0,
                        "the name is absent and the replacement is a "
                        f"{len(entry.replacement_hex) // 2}-byte generic stub; "
                        "identical stubs occur throughout the bundle, so body "
                        "similarity cannot identify the target",
                    )
                )
                continue
            if len(window_ids) > args.max_window:
                resolutions.append(
                    stop(
                        "unresolved",
                        "none",
                        0.0,
                        f"the name is absent and the id window holds "
                        f"{window_size} functions (limit {args.max_window})",
                    )
                )
                continue
            pool = window_ids
            if len(pool) > args.max_candidates:
                pool = _shortlist_by_bytes(
                    old_bundle, entry.function_id, new_bundle, pool, old_body,
                    args.max_candidates,
                )
            scored, mode = score_pool(pool)
            best_score, best_id = scored[0]
            runner = scored[1][0] if len(scored) > 1 else 0.0
            candidates = candidate_summary(new_functions, scored)
            absent_margin = max(args.margin, args.absent_margin)
            if (
                best_score >= args.absent_threshold
                and best_score - runner >= absent_margin
            ):
                resolutions.append(
                    make_resolution(
                        best_id,
                        "resolved",
                        f"name-absent+{mode}-similarity",
                        best_score,
                        "the name is absent; the best candidate in the id window "
                        f"scores {best_score:.3f} against {runner:.3f}, above the "
                        f"stricter absent-name threshold {args.absent_threshold}",
                        best_score,
                        runner,
                        candidates,
                    )
                )
            elif best_score < args.retire_threshold:
                resolutions.append(
                    stop(
                        "retired",
                        "none",
                        0.0,
                        f"the name is absent and no candidate reaches the retire "
                        f"threshold {args.retire_threshold} (best={best_score:.3f})",
                        best_score,
                        runner,
                        candidates,
                    )
                )
            else:
                resolutions.append(
                    stop(
                        "ambiguous",
                        "none",
                        0.0,
                        f"the name is absent; best={best_score:.3f}, "
                        f"runner-up={runner:.3f}, needs {args.absent_threshold} "
                        f"with margin {absent_margin}",
                        best_score,
                        runner,
                        candidates,
                    )
                )
            continue

        # Anonymous and empty names have no name evidence at all.
        exact_ids = [
            function_id
            for function_id in new_body_hashes.get(old_hash, [])
            if lo is None or hi is None or lo < function_id < hi
        ]
        if len(exact_ids) == 1 and (window_ids is not None or lo is None):
            method = "anonymous+exact-body"
            note = "exactly one body in the id window is identical"
            if lo is None:
                method = "anonymous+exact-body-global"
                note = "exactly one body in the bundle is identical"
            resolutions.append(
                make_resolution(
                    exact_ids[0],
                    "resolved",
                    method,
                    0.98,
                    note,
                    1.0,
                    0.0,
                    [
                        {
                            "id": exact_ids[0],
                            "name": new_functions[exact_ids[0]].name,
                            "size": new_functions[exact_ids[0]].size,
                            "score": 1.0,
                        }
                    ],
                )
            )
            continue
        if window_ids is None or not window_ids:
            resolutions.append(
                stop(
                    "unresolved",
                    "none",
                    0.0,
                    "the function has no name and the surrounding unique-name "
                    "anchors do not bracket a usable id window",
                )
            )
            continue
        if replacement_kind == "generic_stub":
            resolutions.append(
                stop(
                    "ambiguous",
                    "none",
                    0.0,
                    "the function has no name and the replacement is a "
                    f"{len(entry.replacement_hex) // 2}-byte generic stub; "
                    "identical stubs occur throughout the bundle, so body "
                    "similarity cannot identify the target",
                )
            )
            continue
        if len(window_ids) > args.max_window:
            resolutions.append(
                stop(
                    "unresolved",
                    "none",
                    0.0,
                    f"the function has no name and the id window holds "
                    f"{window_size} functions (limit {args.max_window}); exact "
                    f"body matching did not narrow it",
                )
            )
            continue
        pool = window_ids
        if len(pool) > args.max_candidates:
            pool = _shortlist_by_bytes(
                old_bundle, entry.function_id, new_bundle, pool, old_body,
                args.max_candidates,
            )
        scored, mode = score_pool(pool)
        best_score, best_id = scored[0]
        runner = scored[1][0] if len(scored) > 1 else 0.0
        candidates = candidate_summary(new_functions, scored)
        if (
            best_score >= args.anonymous_threshold
            and best_score - runner >= args.margin
        ):
            resolutions.append(
                make_resolution(
                    best_id,
                    "resolved",
                    f"anonymous+{mode}-similarity",
                    best_score,
                    "the function has no name; best candidate in the id window "
                    f"scores {best_score:.3f} against {runner:.3f}",
                    best_score,
                    runner,
                    candidates,
                )
            )
        else:
            resolutions.append(
                stop(
                    "ambiguous",
                    "none",
                    0.0,
                    f"the function has no name; best={best_score:.3f}, "
                    f"runner-up={runner:.3f}, anonymous threshold="
                    f"{args.anonymous_threshold}, margin={args.margin}",
                    best_score,
                    runner,
                    candidates,
                )
            )

    return resolutions


def _shortlist_by_bytes(
    old_bundle: HbcBundle,
    old_id: int,
    new_bundle: HbcBundle,
    pool: list[int],
    old_body: bytes,
    limit: int,
) -> list[int]:
    scored = []
    for function_id in pool:
        scored.append(
            (byte_similarity(old_body, new_bundle.body(function_id)), function_id)
        )
    scored.sort(key=lambda item: (-item[0], item[1]))
    return [function_id for _, function_id in scored[:limit]]


def attach_replacements(
    resolutions: list[Resolution],
    patched_bundle: HbcBundle | None,
    new_bundle: HbcBundle,
    verbose: bool,
) -> None:
    for resolution in resolutions:
        if resolution.status != "resolved" or resolution.new_id is None:
            continue
        if resolution.replacement_kind == "generic_stub":
            replacement = bytes.fromhex(resolution.old.replacement_hex)
            if len(replacement) > resolution.new_function.size:
                resolution.warnings.append(
                    f"stub is {len(replacement)} bytes but the new function is "
                    f"{resolution.new_function.size}; the engine can relocate it"
                )
            resolution.replacement = replacement
            resolution.replacement_source = "old-stub"
            resolution.emitted = True
            continue
        if patched_bundle is None:
            resolution.reason = (
                resolution.reason
                + "; a patched bundle is required to read this replacement body"
            )
            continue
        patched = patched_bundle.function(resolution.new_id)
        replacement = patched_bundle.data[
            patched.body_offset : patched.body_offset + patched.size
        ]
        stock = new_bundle.body(resolution.new_id)
        if replacement == stock:
            resolution.reason = (
                resolution.reason
                + "; the supplied patched bundle has stock bytes at the new id, "
                "so it does not carry this replacement"
            )
            if verbose:
                print(
                    f"warning: patched bundle has stock bytes at new id "
                    f"{resolution.new_id} (old id {resolution.old.function_id})",
                    file=sys.stderr,
                )
            continue
        if not replacement:
            resolution.reason = resolution.reason + "; the patched body is empty"
            continue
        resolution.replacement = replacement
        resolution.replacement_source = "patched-bundle"
        resolution.emitted = True
        if len(replacement) > resolution.new_function.size:
            resolution.warnings.append(
                f"replacement is {len(replacement)} bytes, larger than the "
                f"{resolution.new_function.size}-byte original; the engine will "
                f"relocate it"
            )


def choose_emitted_entries(
    resolutions: list[Resolution],
    new_bundle: HbcBundle,
) -> tuple[list[KotlinEntry], list[dict], list[dict], list[dict]]:
    by_new_id: dict[int, list[Resolution]] = collections.defaultdict(list)
    order: list[int] = []
    for resolution in resolutions:
        if resolution.emitted and resolution.new_id is not None:
            if resolution.new_id not in by_new_id:
                order.append(resolution.new_id)
            by_new_id[resolution.new_id].append(resolution)

    emitted: list[KotlinEntry] = []
    collapsed: list[dict] = []
    conflicts: list[dict] = []
    missing: list[dict] = []
    for new_id in order:
        group = by_new_id[new_id]
        group.sort(key=lambda item: item.old.function_id)
        # Prefer an extracted body over a re-used stub: the patched bundle is
        # the authority for a target the table already edits.
        keeper = max(
            group,
            key=lambda item: (
                item.replacement_source == "patched-bundle",
                item.old.function_id == min(
                    candidate.old.function_id for candidate in group
                ),
            ),
        )
        new_info = new_bundle.function(new_id)
        if len(group) > 1:
            collapsed.append(
                {
                    "new_id": new_id,
                    "kept_old_id": keeper.old.function_id,
                    "dropped_old_ids": [
                        item.old.function_id
                        for item in group
                        if item is not keeper
                    ],
                    "replacement_sources": sorted(
                        {item.replacement_source or "none" for item in group}
                    ),
                }
            )
            byte_forms = {
                None if item.replacement is None else bytes(item.replacement)
                for item in group
            }
            if len(byte_forms) > 1:
                conflicts.append(
                    {
                        "new_id": new_id,
                        "kept_old_id": keeper.old.function_id,
                        "old_ids": [item.old.function_id for item in group],
                        "sources": [item.replacement_source for item in group],
                        "message": "aliases produced different replacement bytes; "
                        "kept the extracted body or the first entry",
                    }
                )
        if keeper.replacement is None:
            missing.append(
                {
                    "new_id": new_id,
                    "old_ids": [item.old.function_id for item in group],
                    "message": "group has no usable replacement",
                }
            )
            continue
        name = new_info.name
        if len(keeper.replacement) > new_info.size:
            keeper.warnings.append(
                f"replacement {len(keeper.replacement)} bytes exceeds original "
                f"{new_info.size}; relocation is expected"
            )
        emitted.append(
            KotlinEntry(
                function_id=new_id,
                name=name,
                original_size=new_info.size,
                replacement_hex=keeper.replacement.hex(),
            )
        )
    return emitted, collapsed, conflicts, missing


def build_report(
    args: argparse.Namespace,
    table: KotlinTable,
    old_bundle: HbcBundle,
    new_bundle: HbcBundle,
    patched_bundle: HbcBundle | None,
    resolutions: list[Resolution],
    emitted: list[KotlinEntry],
    collapsed: list[dict],
    conflicts: list[dict],
    missing: list[dict],
    anchor_count: int,
    similarity_mode: str,
) -> dict:
    status_counts = collections.Counter(item.status for item in resolutions)
    special = [
        item.to_dict()
        for item in resolutions
        if item.status == "resolved" and not item.emitted
    ]
    resolved = [item.to_dict() for item in resolutions if item.status == "resolved"]
    ambiguous = [
        item.to_dict() for item in resolutions if item.status == "ambiguous"
    ]
    unresolved = [
        item.to_dict() for item in resolutions if item.status == "unresolved"
    ]
    retired = [item.to_dict() for item in resolutions if item.status == "retired"]
    emitted_old_ids = {
        item.old.function_id for item in resolutions if item.emitted
    }
    not_emitted = [
        item.to_dict()
        for item in resolutions
        if item.status == "resolved" and not item.emitted
    ]
    return {
        "tool": "retarget_hermes",
        "tool_version": 1,
        "dry_run": not bool(args.emit_kotlin),
        "inputs": {
            "kotlin": table.path,
            "old": old_bundle.summary(),
            "new": new_bundle.summary(),
            "patched": patched_bundle.summary() if patched_bundle else None,
            "declared": {
                "old_bundle_size": table.bundle_size,
                "old_function_count": table.function_count,
                "new_bundle_size": args.new_bundle_size,
                "new_function_count": args.new_function_count,
            },
            "similarity": similarity_mode,
            "decompiler": args.decompiler,
        },
        "summary": {
            "old_entries": len(table.entries),
            "anchors": anchor_count,
            "resolved": status_counts.get("resolved", 0),
            "emitted": len(emitted),
            "ambiguous": status_counts.get("ambiguous", 0),
            "unresolved": status_counts.get("unresolved", 0),
            "retired": status_counts.get("retired", 0),
            "special": len(special),
            "collapsed": len(collapsed),
            "conflicts": len(conflicts),
            "missing_groups": len(missing),
            "old_ids_not_emitted": len(table.entries) - len(emitted_old_ids),
        },
        "resolved": resolved,
        "ambiguous": ambiguous,
        "unresolved": unresolved,
        "retired": retired,
        "special": special,
        "not_emitted": not_emitted,
        "collapsed": collapsed,
        "conflicts": conflicts,
        "missing_groups": missing,
    }


def write_json(path: str, document: dict) -> None:
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".retarget-", suffix=".json", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(document, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def write_text(path: str, text: str) -> None:
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".retarget-", suffix=".txt", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def render_report_text(report: dict) -> str:
    lines = ["retarget_hermes report", ""]
    inputs = report["inputs"]
    for label in ("old", "new", "patched"):
        entry = inputs.get(label)
        if entry:
            lines.append(
                f"{label:<8} {entry['path']}  {entry['size']} bytes  "
                f"{entry['function_count']} functions  HBC {entry['version']}  "
                f"sha256 {entry['sha256']}"
            )
    lines.append(f"kotlin   {inputs['kotlin']}")
    lines.append(f"similarity: {inputs['similarity']}")
    lines.append("")
    summary = report["summary"]
    lines.append(
        "summary  "
        + "  ".join(f"{key}={value}" for key, value in summary.items())
    )
    lines.append("")
    sections = [
        ("RESOLVED", report["resolved"], True),
        ("SPECIAL (mapped, replacement body not available)", report["special"], True),
        ("AMBIGUOUS (not emitted)", report["ambiguous"], True),
        ("UNRESOLVED (not emitted)", report["unresolved"], True),
        ("RETIRED CANDIDATES (not emitted)", report["retired"], True),
        ("COLLAPSED ALIASES", report["collapsed"], True),
        ("DUPLICATE CONFLICTS", report["conflicts"], True),
        ("MISSING GROUPS", report["missing_groups"], True),
    ]
    for heading, rows, _ in sections:
        lines.append(f"{heading}: {len(rows)}")
        for row in rows:
            if heading == "COLLAPSED ALIASES":
                lines.append(
                    f"  new {row['new_id']} <- old {row['kept_old_id']} "
                    f"(dropped {row['dropped_old_ids']})"
                )
                continue
            if heading == "DUPLICATE CONFLICTS":
                lines.append(
                    f"  new {row['new_id']}: old {row['old_ids']} {row['message']}"
                )
                continue
            if heading == "MISSING GROUPS":
                lines.append(
                    f"  new {row['new_id']}: old {row['old_ids']} {row['message']}"
                )
                continue
            old_id = row["old_id"]
            old_name = row["old_name"] or "<anonymous>"
            new_id = row["new_id"]
            new_name = row["new_name"]
            if new_id is None:
                lines.append(
                    f"  old {old_id} {old_name!r}: [{row['status']}] "
                    f"{row['reason']}"
                )
                continue
            name_note = "" if not new_name else f" {new_name!r}"
            extra = ""
            if row["replacement_source"]:
                extra = (
                    f" replacement={row['replacement_source']}"
                    f"({row['replacement_size']} bytes)"
                )
            lines.append(
                f"  old {old_id} {old_name!r} -> new {new_id}{name_note} "
                f"[{row['method']} {row['confidence']:.2f}]{extra}"
            )
            if row["warnings"]:
                for warning in row["warnings"]:
                    lines.append(f"      warning: {warning}")
        if rows:
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def choose_decompiler(explicit: str | None) -> str | None:
    if explicit:
        path = os.path.abspath(explicit)
        if not os.path.isfile(path) or not os.access(path, os.X_OK):
            raise ToolError(f"hermes-decomp is not executable: {path}")
        return path
    candidates = []
    env = os.environ.get("HERMES_DECOMP")
    if env:
        candidates.append(env)
    found = shutil.which("hermes-decomp")
    if found:
        candidates.append(found)
    candidates.append(os.path.join(REPO_ROOT, "discord", "tools", "hermes-decomp"))
    for candidate in candidates:
        if candidate and os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return os.path.abspath(candidate)
    return None


def run_self_test() -> int:
    checks = 0

    def check(condition: bool, message: str) -> None:
        nonlocal checks
        if not condition:
            raise ToolError(f"self-test failed: {message}")
        checks += 1

    with tempfile.TemporaryDirectory(prefix="retarget-hermes-test-") as temp:
        bundle_path = os.path.join(temp, "test.bundle")
        _write_test_bundle(bundle_path)
        bundle = HbcBundle(bundle_path, "self-test bundle")
        check(bundle.version == 98, "test bundle version")
        check(bundle.function_count == 4, "test bundle function count")
        check(bundle.function(0).name == "global", "ascii function name")
        check(bundle.function(1).name == "caf\u00e9", "UTF-16 function name")
        check(bundle.function(2).name == "x" * 300, "overflow string name")
        check(bundle.function(3).name == "", "empty function name")
        check(bundle.body(0) == b"\x93\x00\x76\x00", "direct function body")
        check(bundle.body(2) == b"body-two" * 10, "overflow function body")
        check(bundle.body_hashes()[hashlib.sha256(b"\x93\x00\x76\x00").digest()], "body index")

        dump_path = os.path.join(temp, "functions.txt")
        with open(dump_path, "w", encoding="utf-8") as handle:
            handle.write("Function Table (4 entries):\n")
            handle.write("ID    Name                           Offset     Size\n")
            for info in bundle.functions():
                handle.write(
                    f"{info.function_id}     {info.name}     "
                    f"{info.body_offset:x}     {info.size}\n"
                )
        dumped = load_function_dump(dump_path, "self-test dump")
        check(dumped[0].name == "global", "text dump ASCII name")
        check(dumped[1].name == "caf\u00e9", "text dump UTF-16 name")
        verify_dump_against_bundle(dumped, bundle, "self-test dump")

        json_path = os.path.join(temp, "functions.json")
        with open(json_path, "w", encoding="utf-8") as handle:
            json.dump(
                [
                    {
                        "id": info.function_id,
                        "name": info.name,
                        "offset": info.body_offset,
                        "size": info.size,
                    }
                    for info in bundle.functions()
                ],
                handle,
            )
        verify_dump_against_bundle(
            load_function_dump(json_path, "self-test JSON dump"),
            bundle,
            "self-test JSON dump",
        )
        check(True, "JSON dump")

        kotlin_path = os.path.join(temp, "Table.kt")
        with open(kotlin_path, "w", encoding="utf-8") as handle:
            handle.write(
                "object Table {\n"
                "    const val TARGET_BUNDLE_SIZE = 100\n"
                "    const val TARGET_FUNCTION_COUNT = 4\n"
                "    val PATCHES: List<FunctionPatch> = listOf(\n"
                '        FunctionPatch(0, "global", 4, "93007600"),\n'
                "    )\n"
                "}\n"
            )
        table = parse_kotlin_table(kotlin_path)
        check(table.bundle_size == 100, "Kotlin bundle size")
        check(table.function_count == 4, "Kotlin function count")
        check(table.entries[0].name == "global", "Kotlin function name")
        rendered = render_candidate_table(
            KotlinTable(
                path=kotlin_path,
                source=table.source,
                entries=table.entries,
                bundle_size=200,
                function_count=4,
            ),
            [KotlinEntry(0, "a$b", 4, "93007600")],
        )
        check("TARGET_BUNDLE_SIZE = 200" in rendered, "candidate constant")
        check("${'$'}" in rendered, "candidate dollar escaping")
        check('FunctionPatch(0, "a${\'$\'}b", 4, "93007600")' in rendered,
              "candidate entry")

        kind, _ = classify_replacement(
            KotlinEntry(0, "", 4, "93007600"), 98, 98, DEFAULT_STUB_MAX_BYTES
        )
        check(kind == "generic_stub", "constant stub classification")
        kind, _ = classify_replacement(
            KotlinEntry(0, "", 4, "93007e7e7600"), 98, 98, DEFAULT_STUB_MAX_BYTES
        )
        check(kind == "generic_stub", "long constant stub classification")
        kind, _ = classify_replacement(
            KotlinEntry(0, "", 30, "00" * 30), 98, 98, DEFAULT_STUB_MAX_BYTES
        )
        check(kind == "needs_patched_body", "body classification")
        kind, _ = classify_replacement(
            KotlinEntry(0, "", 21, "00" * 21), 98, 98, DEFAULT_STUB_MAX_BYTES
        )
        check(kind == "needs_patched_body", "promise classification")
        check(byte_similarity(b"abc", b"abc") == 1.0, "byte similarity")

        anchor_old = [10, 20, 30]
        anchor_new = [100, 200, 300]
        check(bracket_anchors(anchor_old, anchor_new, 15) == (100, 200),
              "anchor bracket")

    print(f"self-test: ok ({checks} checks)")
    return 0


def _write_test_bundle(path: str) -> None:
    names = ["global", "caf\u00e9", "", "x" * 300]
    bodies = [b"\x93\x00\x76\x00", b"\x94\x00\x7e\x76\x00", b"body-two" * 10, b"short"]
    string_count = len(names)
    function_count = len(names)
    storage = bytearray()
    string_entries: list[int] = []
    overflow_entries: list[tuple[int, int]] = []
    for name in names:
        if any(ord(char) > 0xFF for char in name):
            raw_bytes = name.encode("utf-16-le")
            utf16 = 1
        else:
            raw_bytes = name.encode("latin-1")
            utf16 = 0
        raw_offset = (len(storage) << 1) | utf16
        if len(name) > 254:
            overflow_index = len(overflow_entries)
            overflow_entries.append((raw_offset, len(name)))
            string_entries.append((255 << 24) | (overflow_index << 1))
        else:
            string_entries.append((len(name) << 24) | raw_offset)
        storage.extend(raw_bytes)

    string_kind_count = 2
    identifier_count = 1
    overflow_count = len(overflow_entries)
    functions_start = HBC_HEADER_SIZE
    string_table_start = (
        functions_start
        + HBC_FUNCTION_ENTRY_SIZE * function_count
        + 4 * string_kind_count
        + 4 * identifier_count
    )
    overflow_table_start = string_table_start + 4 * string_count
    storage_start = overflow_table_start + 8 * overflow_count
    body_start = (storage_start + len(storage) + 7) & ~7
    large_header_start = (body_start + sum(len(body) for body in bodies) + 3) & ~3
    file_length = large_header_start + HBC_LARGE_HEADER_BYTES
    data = bytearray(file_length)
    data[:8] = HBC_MAGIC
    for offset, value in (
        (8, 98),
        (32, file_length),
        (40, function_count),
        (44, string_kind_count),
        (48, identifier_count),
        (52, string_count),
        (56, overflow_count),
        (60, len(storage)),
    ):
        struct.pack_into("<I", data, offset, value)
    data[storage_start : storage_start + len(storage)] = storage
    for index, entry in enumerate(string_entries):
        struct.pack_into("<I", data, string_table_start + 4 * index, entry)
    for index, (raw_offset, length) in enumerate(overflow_entries):
        struct.pack_into(
            "<II", data, overflow_table_start + 8 * index, raw_offset, length
        )

    body_offset = body_start
    name_indices = [0, 1, 3, 2]
    for function_id, (name_index, body) in enumerate(zip(name_indices, bodies)):
        data[body_offset : body_offset + len(body)] = body
        if function_id == 2:
            large_offset = large_header_start
            struct.pack_into("<I", data, large_offset, body_offset)
            struct.pack_into("<I", data, large_offset + 4, 0)
            struct.pack_into("<I", data, large_offset + 8, 0)
            struct.pack_into("<I", data, large_offset + 12, len(body))
            struct.pack_into("<I", data, large_offset + 16, name_index)
            word0 = large_offset & HBC_OFFSET_MASK
            # The high eight bits of the large header's address live in the
            # functionName field; the function's own name index is read from
            # the large header at +16.
            word1 = (((large_offset >> 24) & 0xFF)) << 14
            word2 = HBC_OVERFLOW_FLAG << 24
        else:
            word0 = body_offset & HBC_OFFSET_MASK
            word1 = (len(body) & HBC_BYTECODE_SIZE_MASK) | (
                (name_index & 0xFF) << 14
            )
            word2 = 0
        slot = functions_start + HBC_FUNCTION_ENTRY_SIZE * function_id
        struct.pack_into("<III", data, slot, word0, word1, word2)
        body_offset += len(body)

    with open(path, "wb") as handle:
        handle.write(data)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="retarget_hermes.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            "Re-map DiscordHermesBundlePatch.kt from an old Discord Hermes "
            "bundle to a new one. No file is written to the repository; the "
            "candidate Kotlin table and reports go only to the paths named."
        ),
        epilog=(
            "Exit status: 0 when the report was produced; 1 when the candidate "
            "table was refused or --strict found entries that need a human; "
            "2 for invalid input or an operational failure."
        ),
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run parser and table tests without reading any Discord input",
    )
    parser.add_argument("--old-bundle", help="Hermes bundle for the current table")
    parser.add_argument("--new-bundle", help="Hermes bundle for the target release")
    parser.add_argument(
        "--patched-bundle",
        help="target bundle after the replacement bodies were applied; required "
        "for entries that are not short constant stubs",
    )
    parser.add_argument(
        "--kotlin",
        default=DEFAULT_KOTLIN,
        help=f"current Kotlin table (default: {DEFAULT_KOTLIN})",
    )
    parser.add_argument(
        "--old-bundle-size",
        type=int,
        help="expected old bundle size; defaults to TARGET_BUNDLE_SIZE",
    )
    parser.add_argument(
        "--old-function-count",
        type=int,
        help="expected old function count; defaults to TARGET_FUNCTION_COUNT",
    )
    parser.add_argument(
        "--new-bundle-size",
        type=int,
        help="expected new bundle size; without it the observed size is reported",
    )
    parser.add_argument(
        "--new-function-count",
        type=int,
        help="expected new function count; without it the observed count is reported",
    )
    parser.add_argument(
        "--patched-size", type=int, help="expected patched bundle size, when supplied"
    )
    parser.add_argument(
        "--patched-count",
        type=int,
        help="expected patched function count, when supplied",
    )
    parser.add_argument("--old-functions", help="optional hermes-decomp function dump for the old bundle")
    parser.add_argument("--new-functions", help="optional hermes-decomp function dump for the new bundle")
    parser.add_argument(
        "--patched-functions",
        help="optional hermes-decomp function dump for the patched bundle",
    )
    parser.add_argument(
        "--decompiler",
        help="path to hermes-decomp; auto-detected from HERMES_DECOMP, PATH, "
        "or <repo>/discord/tools/hermes-decomp",
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=DEFAULT_THRESHOLD,
        help=f"minimum similarity for a named entry (default: {DEFAULT_THRESHOLD})",
    )
    parser.add_argument(
        "--anonymous-threshold",
        type=float,
        default=DEFAULT_ANONYMOUS_THRESHOLD,
        help="minimum similarity for a function with no name "
        f"(default: {DEFAULT_ANONYMOUS_THRESHOLD})",
    )
    parser.add_argument(
        "--absent-threshold",
        type=float,
        default=DEFAULT_ABSENT_THRESHOLD,
        help="minimum similarity when the old name is absent from the new "
        f"bundle (default: {DEFAULT_ABSENT_THRESHOLD})",
    )
    parser.add_argument(
        "--absent-margin",
        type=float,
        default=DEFAULT_ABSENT_MARGIN,
        help="required score gap when the old name is absent "
        f"(default: {DEFAULT_ABSENT_MARGIN})",
    )
    parser.add_argument(
        "--retire-threshold",
        type=float,
        default=DEFAULT_RETIRE_THRESHOLD,
        help="below this, an absent name is reported as a retired candidate "
        f"(default: {DEFAULT_RETIRE_THRESHOLD})",
    )
    parser.add_argument(
        "--margin",
        type=float,
        default=DEFAULT_MARGIN,
        help=f"required gap between the best and second score (default: {DEFAULT_MARGIN})",
    )
    parser.add_argument(
        "--max-candidates",
        type=int,
        default=DEFAULT_MAX_CANDIDATES,
        help=f"score at most this many candidates per entry (default: {DEFAULT_MAX_CANDIDATES})",
    )
    parser.add_argument(
        "--max-window",
        type=int,
        default=DEFAULT_MAX_WINDOW,
        help="refuse to score an id window larger than this "
        f"(default: {DEFAULT_MAX_WINDOW})",
    )
    parser.add_argument(
        "--stub-max-bytes",
        type=int,
        default=DEFAULT_STUB_MAX_BYTES,
        help="largest replacement treated as a reusable constant stub "
        f"(default: {DEFAULT_STUB_MAX_BYTES})",
    )
    parser.add_argument(
        "--emit-kotlin",
        help="write a candidate Kotlin table to this path; never the input path",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="overwrite an existing --emit-kotlin output",
    )
    parser.add_argument(
        "--allow-incomplete",
        action="store_true",
        help="write the candidate even when entries are ambiguous, unresolved, "
        "retired, or missing a replacement body",
    )
    parser.add_argument("--report-json", help="write the full report as JSON")
    parser.add_argument("--report-txt", help="write the report as plain text")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="exit 1 when any entry needs a human, even without --emit-kotlin",
    )
    parser.add_argument("--timeout", type=int, default=120, help="per-function hermes-decomp timeout")
    parser.add_argument("-v", "--verbose", action="store_true")
    parser.add_argument("--version", action="version", version="retarget_hermes.py 1")
    args = parser.parse_args(argv)

    if args.max_candidates < 1:
        parser.error("--max-candidates must be at least 1")
    if args.max_window < 1:
        parser.error("--max-window must be at least 1")
    if args.stub_max_bytes < 1:
        parser.error("--stub-max-bytes must be at least 1")
    if not 0.0 <= args.threshold <= 1.0:
        parser.error("--threshold must be between 0 and 1")
    if not 0.0 <= args.anonymous_threshold <= 1.0:
        parser.error("--anonymous-threshold must be between 0 and 1")
    if not 0.0 <= args.absent_threshold <= 1.0:
        parser.error("--absent-threshold must be between 0 and 1")
    if not 0.0 <= args.absent_margin <= 1.0:
        parser.error("--absent-margin must be between 0 and 1")
    if not 0.0 <= args.retire_threshold <= 1.0:
        parser.error("--retire-threshold must be between 0 and 1")
    if not 0.0 <= args.margin <= 1.0:
        parser.error("--margin must be between 0 and 1")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.self_test:
        return run_self_test()
    if not args.old_bundle or not args.new_bundle:
        print("error: --old-bundle and --new-bundle are required", file=sys.stderr)
        return 2

    try:
        decompiler = choose_decompiler(args.decompiler)
        if decompiler:
            args.decompiler = decompiler
        table = parse_kotlin_table(args.kotlin)
        old_expected_size = (
            args.old_bundle_size
            if args.old_bundle_size is not None
            else table.bundle_size
        )
        old_expected_count = (
            args.old_function_count
            if args.old_function_count is not None
            else table.function_count
        )
        old_bundle = HbcBundle(
            args.old_bundle,
            "old bundle",
            old_expected_size,
            old_expected_count,
        )
        new_bundle = HbcBundle(
            args.new_bundle, "new bundle", args.new_bundle_size, args.new_function_count
        )
        patched_bundle = None
        if args.patched_bundle:
            patched_bundle = HbcBundle(
                args.patched_bundle,
                "patched bundle",
                args.patched_size,
                args.patched_count,
            )
        if args.old_functions:
            verify_dump_against_bundle(
                load_function_dump(args.old_functions, "old function dump"),
                old_bundle,
                "old function dump",
            )
        if args.new_functions:
            verify_dump_against_bundle(
                load_function_dump(args.new_functions, "new function dump"),
                new_bundle,
                "new function dump",
            )
        if args.patched_functions:
            if patched_bundle is None:
                raise ToolError("--patched-functions needs --patched-bundle")
            verify_dump_against_bundle(
                load_function_dump(args.patched_functions, "patched function dump"),
                patched_bundle,
                "patched function dump",
            )
        if args.new_bundle_size is None or args.new_function_count is None:
            print(
                "warning: new bundle size/function count were not both declared; "
                "pass --new-bundle-size and --new-function-count for a release gate",
                file=sys.stderr,
            )

        if args.verbose:
            print(
                f"old: {old_bundle.summary()}\nnew: {new_bundle.summary()}",
                file=sys.stderr,
            )
        old_function_list = old_bundle.functions()
        new_function_list = new_bundle.functions()
        old_functions = {info.function_id: info for info in old_function_list}
        new_functions = {info.function_id: info for info in new_function_list}
        new_by_name: dict[str, list[int]] = collections.defaultdict(list)
        for info in new_function_list:
            if info.name:
                new_by_name[info.name].append(info.function_id)
        anchor_old, anchor_new, anchor_count = build_anchors(
            old_function_list, new_function_list, args.verbose
        )
        similarity = SimilarityProvider(decompiler, args.timeout, args.verbose)
        resolutions = resolve_entries(
            table,
            old_bundle,
            new_bundle,
            old_functions,
            new_functions,
            new_by_name,
            new_bundle.body_hashes(),
            anchor_old,
            anchor_new,
            similarity,
            args,
        )
        attach_replacements(resolutions, patched_bundle, new_bundle, args.verbose)
        emitted, collapsed, conflicts, missing = choose_emitted_entries(
            resolutions, new_bundle
        )

        needs_human = [
            item
            for item in resolutions
            if item.status in ("ambiguous", "unresolved", "retired")
            or (item.status == "resolved" and not item.emitted)
        ]
        # Rewrite the candidate table's constants from the observed target.
        candidate_table = KotlinTable(
            path=table.path,
            source=table.source,
            entries=table.entries,
            bundle_size=new_bundle.size,
            function_count=new_bundle.function_count,
        )
        report = build_report(
            args,
            table,
            old_bundle,
            new_bundle,
            patched_bundle,
            resolutions,
            emitted,
            collapsed,
            conflicts,
            missing,
            anchor_count,
            "byte" if decompiler is None else "opcode+string (byte fallback per function)",
        )
        report["summary"]["needs_human"] = len(needs_human)
        report_text = render_report_text(report)

        if args.report_json:
            write_json(args.report_json, report)
        if args.report_txt:
            write_text(args.report_txt, report_text)

        print(
            f"mapped {report['summary']['resolved']} / {len(table.entries)} old "
            f"entries; emitted {len(emitted)}; ambiguous "
            f"{report['summary']['ambiguous']}; unresolved "
            f"{report['summary']['unresolved']}; retired "
            f"{report['summary']['retired']}; special "
            f"{report['summary']['special']}; collapsed "
            f"{report['summary']['collapsed']}"
        )
        if args.verbose or not (args.report_txt or args.report_json):
            print()
            print(report_text)

        kotlin_refused = False
        if args.emit_kotlin:
            output = os.path.abspath(args.emit_kotlin)
            if output == os.path.abspath(args.kotlin):
                raise ToolError(
                    "--emit-kotlin may not overwrite the input table; write a "
                    "candidate elsewhere and review it before copying"
                )
            if needs_human and not args.allow_incomplete:
                kotlin_refused = True
                print(
                    f"error: {len(needs_human)} entries need a human; the "
                    f"candidate table was not written. Resolve them, or pass "
                    f"--allow-incomplete to write a partial candidate.",
                    file=sys.stderr,
                )
            else:
                if os.path.exists(output) and not args.force:
                    raise ToolError(
                        f"{output} already exists; pass --force to replace it"
                    )
                rendered = render_candidate_table(candidate_table, emitted)
                write_text(output, rendered)
                print(f"wrote candidate Kotlin table: {output}")

        if args.report_json:
            print(f"wrote JSON report: {os.path.abspath(args.report_json)}")
        if args.report_txt:
            print(f"wrote text report: {os.path.abspath(args.report_txt)}")
        if kotlin_refused:
            return 1
        if args.strict and needs_human:
            return 1
        return 0
    except ToolError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
