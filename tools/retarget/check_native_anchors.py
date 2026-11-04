#!/usr/bin/env python3
"""Check every Discord SmaliPatch against a decompiled Discord tree.

``SmaliPatch`` entries in ``DiscordNativePatches.kt`` and ``DiscordPatches.kt``
name a smali file and, depending on the entry, a method signature, an anchor
string, or a switch-case label. R8 moves classes and rewrites those strings on
every release, so an entry can compile and still point at nothing.

This tool parses the named arguments of every ``SmaliPatch`` call, resolves the
path under the smali roots of a supplied decompiled tree, and counts exact
occurrences of each anchor. It reports:

- a missing or ambiguous path, method signature, anchor, or switch-case label;
- an inferred class move when the exact method or anchor occurs in exactly one
  other file;
- an ambiguous move when several files carry the same evidence.

It does not rewrite Kotlin, generate smali, or touch the tree. Reports are
written only to the paths passed with ``--report-json`` or ``--report-txt``.
Run it after decompiling the target build, before reviewing native edits.

Exit status is 0 when every check passes, 1 when any entry needs attention, and
2 for invalid input. Use ``--report-only`` to produce the report without making
findings change the exit status.

Examples::

    python3 -I tools/retarget/check_native_anchors.py \\
        --tree /path/to/354.1/decompiled/base \\
        --report-json /tmp/anchors.json --report-txt /tmp/anchors.txt

    python3 -I tools/retarget/check_native_anchors.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile

sys.dont_write_bytecode = True

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
PATCHES_DIR = os.path.join(
    REPO_ROOT, "app", "src", "main", "kotlin", "dev", "sleepy", "app", "patches"
)
DEFAULT_KOTLIN = [
    os.path.join(PATCHES_DIR, "DiscordNativePatches.kt"),
    os.path.join(PATCHES_DIR, "DiscordPatches.kt"),
]

SMALI_FIELD_ORDER = [
    "title",
    "explanation",
    "dexName",
    "smaliPath",
    "methodSignature",
    "replacementBody",
    "anchor",
    "replacement",
    "switchCaseLabel",
    "switchCaseBody",
    "versionTag",
]
CHECKED_FIELDS = ("methodSignature", "anchor", "switchCaseLabel")


class ToolError(RuntimeError):
    """A malformed Kotlin source or an unusable tree."""


class KotlinLexer:
    """A small Kotlin lexer for the literals and comments these files use."""

    @staticmethod
    def skip_comment(source: str, index: int) -> int:
        if source.startswith("//", index):
            end = source.find("\n", index + 2)
            return len(source) if end < 0 else end + 1
        if source.startswith("/*", index):
            depth = 1
            index += 2
            while index < len(source):
                if source.startswith("/*", index):
                    depth += 1
                    index += 2
                elif source.startswith("*/", index):
                    depth -= 1
                    index += 2
                    if depth == 0:
                        return index
                else:
                    index += 1
            raise ToolError("unterminated Kotlin block comment")
        return index

    @classmethod
    def raw_string_end(cls, source: str, index: int) -> int:
        """Returns the index just past a raw string's closing delimiter.

        A raw string may end with quotes that belong to its content. Kotlin
        treats the last three quotes of a run as the delimiter, so a run of
        four quotes means one literal quote followed by ``\"\"\"``. Choosing
        the first three would truncate such a string.
        """
        if not source.startswith('"""', index):
            raise ToolError("expected a Kotlin raw string")
        cursor = index + 3
        while cursor < len(source):
            if source[cursor] != '"':
                cursor += 1
                continue
            run_start = cursor
            while cursor < len(source) and source[cursor] == '"':
                cursor += 1
            if cursor - run_start >= 3:
                return cursor
        raise ToolError("unterminated Kotlin raw string")

    @classmethod
    def skip_string(cls, source: str, index: int) -> int:
        if source[index] == "'":
            return cls.skip_char(source, index)
        if source.startswith('"""', index):
            return cls.raw_string_end(source, index)
        if source[index] != '"':
            return index
        index += 1
        while index < len(source):
            char = source[index]
            if char == "\\":
                index += 2
            elif char == '"':
                return index + 1
            elif char == "$" and index + 1 < len(source) and source[index + 1] == "{":
                index = cls.skip_interpolation(source, index + 2)
            else:
                index += 1
        raise ToolError("unterminated Kotlin string")

    @classmethod
    def skip_interpolation(cls, source: str, index: int) -> int:
        depth = 1
        while index < len(source):
            if source.startswith("//", index) or source.startswith("/*", index):
                index = cls.skip_comment(source, index)
            elif source[index] in ('"', "'"):
                index = cls.skip_string(source, index)
            elif source[index] == "{":
                depth += 1
                index += 1
            elif source[index] == "}":
                depth -= 1
                index += 1
                if depth == 0:
                    return index
            else:
                index += 1
        raise ToolError("unterminated Kotlin string interpolation")

    @staticmethod
    def skip_char(source: str, index: int) -> int:
        index += 1
        while index < len(source):
            if source[index] == "\\":
                index += 2
            elif source[index] == "'":
                return index + 1
            else:
                index += 1
        raise ToolError("unterminated Kotlin character literal")

    @classmethod
    def skip_space_and_comments(cls, source: str, index: int) -> int:
        while index < len(source):
            if source[index].isspace():
                index += 1
            elif source.startswith("//", index) or source.startswith("/*", index):
                index = cls.skip_comment(source, index)
            else:
                break
        return index


def find_matching_paren(source: str, open_index: int) -> int:
    if source[open_index] != "(":
        raise ToolError("expected an opening parenthesis")
    depth = 1
    index = open_index + 1
    while index < len(source):
        if source.startswith("//", index) or source.startswith("/*", index):
            index = KotlinLexer.skip_comment(source, index)
        elif source[index] in ('"', "'"):
            index = KotlinLexer.skip_string(source, index)
        elif source[index] == "(":
            depth += 1
            index += 1
        elif source[index] == ")":
            depth -= 1
            index += 1
            if depth == 0:
                return index
        else:
            index += 1
    raise ToolError("unterminated Kotlin call: no matching ')'")


def split_call_arguments(source: str, open_index: int, close_index: int) -> list[str]:
    arguments: list[str] = []
    start = open_index + 1
    index = start
    depth = 0
    while index < close_index:
        if source.startswith("//", index) or source.startswith("/*", index):
            index = KotlinLexer.skip_comment(source, index)
        elif source[index] in ('"', "'"):
            index = KotlinLexer.skip_string(source, index)
        elif source[index] in "([{":
            depth += 1
            index += 1
        elif source[index] in ")]}":
            depth -= 1
            index += 1
        elif source[index] == "," and depth == 0:
            arguments.append(source[start:index])
            start = index + 1
            index += 1
        else:
            index += 1
    tail = source[start:close_index]
    if tail.strip():
        arguments.append(tail)
    return arguments


def find_top_level_equals(argument: str) -> int:
    index = 0
    depth = 0
    while index < len(argument):
        if argument.startswith("//", index) or argument.startswith("/*", index):
            index = KotlinLexer.skip_comment(argument, index)
        elif argument[index] in ('"', "'"):
            index = KotlinLexer.skip_string(argument, index)
        elif argument[index] in "([{":
            depth += 1
            index += 1
        elif argument[index] in ")]}":
            depth -= 1
            index += 1
        elif argument[index] == "=" and depth == 0:
            previous = argument[index - 1] if index else ""
            following = argument[index + 1] if index + 1 < len(argument) else ""
            if previous not in "=!<>" and following != "=":
                return index
            index += 1
        else:
            index += 1
    return -1


def split_top_level_plus(expression: str) -> list[str]:
    parts: list[str] = []
    start = 0
    index = 0
    depth = 0
    while index < len(expression):
        if expression.startswith("//", index) or expression.startswith("/*", index):
            index = KotlinLexer.skip_comment(expression, index)
        elif expression[index] in ('"', "'"):
            index = KotlinLexer.skip_string(expression, index)
        elif expression[index] in "([{":
            depth += 1
            index += 1
        elif expression[index] in ")]}":
            depth -= 1
            index += 1
        elif expression[index] == "+" and depth == 0:
            parts.append(expression[start:index])
            start = index + 1
            index += 1
        else:
            index += 1
    parts.append(expression[start:])
    return parts


def decode_char_literal(literal: str) -> str:
    if len(literal) < 3 or literal[0] != "'" or literal[-1] != "'":
        raise ToolError(f"not a character literal: {literal!r}")
    content = literal[1:-1]
    if content == "\\'":
        return "'"
    if content == "\\\\":
        return "\\"
    if content == '"':
        return '"'
    if content == "$":
        return "$"
    if content == "\\n":
        return "\n"
    if content == "\\r":
        return "\r"
    if content == "\\t":
        return "\t"
    if content == "\\b":
        return "\b"
    if content.startswith("\\u") and len(content) == 6:
        return chr(int(content[2:], 16))
    if len(content) == 1:
        return content
    raise ToolError(f"unsupported character literal: {literal!r}")


def interpolate_string(content: str, raw: bool, context: str) -> str:
    out: list[str] = []
    index = 0
    while index < len(content):
        char = content[index]
        if char == "\\" and not raw:
            index += 1
            if index >= len(content):
                raise ToolError(f"unterminated escape in {context}")
            escape = content[index]
            index += 1
            mapping = {
                "n": "\n",
                "r": "\r",
                "t": "\t",
                "b": "\b",
                "\\": "\\",
                '"': '"',
                "'": "'",
                "$": "$",
            }
            if escape in mapping:
                out.append(mapping[escape])
            elif escape == "u":
                digits = content[index : index + 4]
                if len(digits) != 4 or not all(
                    c in "0123456789abcdefABCDEF" for c in digits
                ):
                    raise ToolError(f"invalid \\u escape in {context}")
                out.append(chr(int(digits, 16)))
                index += 4
            else:
                raise ToolError(f"unsupported escape \\{escape} in {context}")
            continue
        if char == "$":
            if index + 1 < len(content) and content[index + 1] == "{":
                end = KotlinLexer.skip_interpolation(content, index + 2)
                expression = content[index + 2 : end - 1].strip()
                if expression.startswith("'") and expression.endswith("'"):
                    out.append(decode_char_literal(expression))
                elif expression.startswith('"') and expression.endswith('"'):
                    value, problem = evaluate_string_expression(
                        expression, context
                    )
                    if problem:
                        raise ToolError(problem)
                    out.append(value or "")
                else:
                    raise ToolError(
                        f"unsupported Kotlin string interpolation "
                        f"${{{expression}}} in {context}"
                    )
                index = end
                continue
            if index + 1 < len(content) and (
                content[index + 1].isalpha() or content[index + 1] == "_"
            ):
                raise ToolError(
                    f"unsupported Kotlin string interpolation in {context}"
                )
            out.append("$")
            index += 1
            continue
        out.append(char)
        index += 1
    return "".join(out)


def trim_indent(value: str) -> str:
    lines = value.split("\n")
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    indents = [
        len(line) - len(line.lstrip(" \t")) for line in lines if line.strip()
    ]
    if not indents:
        return ""
    cut = min(indents)
    return "\n".join(line[cut:] if line.strip() else "" for line in lines)


def evaluate_string_expression(expression: str, context: str) -> tuple[str | None, str | None]:
    """Returns ``(value, problem)`` for a Kotlin string expression."""
    expression = expression.strip()
    if expression == "null":
        return None, None
    for suffix, transform in (
        (".trimIndent()", trim_indent),
    ):
        if expression.endswith(suffix):
            value, problem = evaluate_string_expression(
                expression[: -len(suffix)], context
            )
            if problem:
                return None, problem
            return transform(value or ""), None
    if not expression:
        return None, f"empty expression in {context}"

    parts = split_top_level_plus(expression)
    if len(parts) > 1:
        values = []
        for part in parts:
            value, problem = evaluate_string_expression(part, context)
            if problem:
                return None, problem
            values.append(value or "")
        return "".join(values), None

    if expression.startswith('"""'):
        try:
            end = KotlinLexer.raw_string_end(expression, 0)
        except ToolError as exc:
            return None, str(exc)
        if expression[end:].strip():
            return None, f"unexpected text after raw string in {context}"
        content = expression[3 : end - 3]
        try:
            return interpolate_string(content, True, context), None
        except ToolError as exc:
            return None, str(exc)
    if expression.startswith('"'):
        index = 1
        out: list[str] = []
        while index < len(expression):
            if expression[index] == "\\":
                index += 2
                continue
            if expression[index] == "$" and index + 1 < len(expression) and expression[index + 1] == "{":
                index = KotlinLexer.skip_interpolation(expression, index + 2)
                continue
            if expression[index] == '"':
                content = expression[1:index]
                if expression[index + 1 :].strip():
                    return None, f"unexpected text after string in {context}"
                try:
                    return interpolate_string(content, False, context), None
                except ToolError as exc:
                    return None, str(exc)
            index += 1
        return None, f"unterminated string in {context}"
    return None, f"not a string literal in {context}: {expression[:80]!r}"


def parse_smali_patches(path: str) -> list[dict]:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            source = handle.read()
    except OSError as exc:
        raise ToolError(f"cannot read {path}: {exc}") from exc

    records: list[dict] = []
    index = 0
    previous_identifier = ""
    while index < len(source):
        if source.startswith("//", index) or source.startswith("/*", index):
            index = KotlinLexer.skip_comment(source, index)
            continue
        if source[index] in ('"', "'"):
            index = KotlinLexer.skip_string(source, index)
            continue
        match = re.match(r"[A-Za-z_][A-Za-z0-9_]*", source[index:])
        if not match:
            index += 1
            continue
        identifier = match.group(0)
        end = index + len(identifier)
        if identifier == "SmaliPatch":
            next_index = KotlinLexer.skip_space_and_comments(source, end)
            if (
                next_index < len(source)
                and source[next_index] == "("
                and previous_identifier not in ("class", "interface", "fun")
            ):
                close_index = find_matching_paren(source, next_index)
                # find_matching_paren returns the index after ')'; the
                # argument splitter takes the index of ')' as its bound.
                raw_arguments = split_call_arguments(
                    source, next_index, close_index - 1
                )
                records.append(
                    parse_patch_arguments(
                        source,
                        path,
                        index,
                        raw_arguments,
                    )
                )
                index = close_index
                previous_identifier = ""
                continue
        previous_identifier = identifier
        index = end

    return records


def parse_patch_arguments(
    source: str,
    path: str,
    start_index: int,
    raw_arguments: list[str],
) -> dict:
    line = source.count("\n", 0, start_index) + 1
    named: dict[str, str] = {}
    problems: list[str] = []
    positional_index = 0
    for raw in raw_arguments:
        argument = raw.strip()
        if not argument:
            continue
        equals = find_top_level_equals(argument)
        if equals >= 0:
            name = argument[:equals].strip()
            expression = argument[equals + 1 :].strip()
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                problems.append(f"unsupported named argument {name!r}")
                continue
            if name not in SMALI_FIELD_ORDER:
                problems.append(f"unknown SmaliPatch field {name!r}")
                continue
            if name in named:
                problems.append(f"field {name!r} is set more than once")
                continue
            named[name] = expression
        else:
            if positional_index >= len(SMALI_FIELD_ORDER):
                problems.append("too many positional arguments")
                continue
            name = SMALI_FIELD_ORDER[positional_index]
            positional_index += 1
            if name in named:
                problems.append(f"field {name!r} is set more than once")
                continue
            named[name] = argument

    values: dict[str, str | None] = {}
    for field in (
        "title",
        "smaliPath",
        "methodSignature",
        "anchor",
        "switchCaseLabel",
    ):
        if field not in named:
            values[field] = None
            continue
        value, problem = evaluate_string_expression(
            named[field], f"{path}:{line} {field}"
        )
        if problem:
            problems.append(problem)
        values[field] = value

    if not values.get("smaliPath"):
        problems.append("smaliPath is missing or is not a string literal")
    return {
        "source": os.path.abspath(path),
        "line": line,
        "title": values.get("title"),
        "smaliPath": values.get("smaliPath"),
        "methodSignature": values.get("methodSignature"),
        "anchor": values.get("anchor"),
        "switchCaseLabel": values.get("switchCaseLabel"),
        "problems": problems,
    }


def discover_smali_roots(tree: str) -> list[str]:
    tree = os.path.abspath(tree)
    if not os.path.isdir(tree):
        raise ToolError(f"decompiled tree does not exist or is not a directory: {tree}")
    roots = []
    try:
        entries = sorted(os.listdir(tree))
    except OSError as exc:
        raise ToolError(f"cannot list {tree}: {exc}") from exc
    for name in entries:
        candidate = os.path.join(tree, name)
        if os.path.isdir(candidate) and (
            name == "smali" or name.startswith("smali_classes")
        ):
            roots.append(candidate)
    if not roots and any(
        name.endswith(".smali")
        for name in os.listdir(tree)
        if os.path.isfile(os.path.join(tree, name))
    ):
        roots.append(tree)
    if not roots:
        raise ToolError(
            f"{tree} holds no smali/, smali_classes*/ or direct .smali files"
        )
    return roots


def safe_join(root: str, relative: str) -> str | None:
    relative = relative.replace("\\", "/")
    if (
        not relative
        or relative.startswith("/")
        or any(part == ".." for part in relative.split("/"))
    ):
        return None
    candidate = os.path.abspath(os.path.join(root, relative))
    root_real = os.path.realpath(root)
    candidate_real = os.path.realpath(candidate)
    try:
        if os.path.commonpath([root_real, candidate_real]) != root_real:
            return None
    except ValueError:
        return None
    return candidate


def resolve_path(roots: list[str], relative: str | None) -> tuple[str | None, list[str], str | None]:
    if not relative:
        return None, [], "smaliPath is empty"
    if os.path.isabs(relative) or relative.startswith("/"):
        return None, [], "smaliPath is absolute; paths must be relative to a smali root"
    candidates = []
    for root in roots:
        candidate = safe_join(root, relative)
        if candidate and os.path.isfile(candidate):
            candidates.append(candidate)
    unique = list(dict.fromkeys(candidates))
    if len(unique) > 1:
        return None, unique, f"smaliPath resolves under {len(unique)} roots"
    if unique:
        return unique[0], unique, None
    return None, [], "smaliPath is not present under any smali root"


def occurrence_lines(text: str, needle: str, limit: int = 10) -> tuple[int, list[int]]:
    count = 0
    lines: list[int] = []
    start = 0
    while True:
        index = text.find(needle, start)
        if index < 0:
            break
        count += 1
        if len(lines) < limit:
            lines.append(text.count("\n", 0, index) + 1)
        start = index + 1
    return count, lines


def read_text(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace", newline="") as handle:
            return handle.read()
    except OSError as exc:
        raise ToolError(f"cannot read {path}: {exc}") from exc


def check_field(
    text: str,
    value: str | None,
    field: str,
) -> dict:
    if value is None:
        return {"field": field, "status": "absent", "count": 0, "lines": []}
    if not value.strip():
        return {
            "field": field,
            "status": "invalid",
            "count": 0,
            "lines": [],
            "detail": "the value is empty",
        }
    if field == "switchCaseLabel":
        pattern = re.compile(
            r"^[ \t]*" + re.escape(value) + r"[ \t]*$", re.MULTILINE
        )
        matches = list(pattern.finditer(text))
        lines = [text.count("\n", 0, match.start()) + 1 for match in matches[:10]]
        count = len(matches)
        total = text.count(value)
        return {
            "field": field,
            "status": "ok" if count == 1 else ("missing" if count == 0 else "ambiguous"),
            "count": count,
            "total_occurrences": total,
            "lines": lines,
            "value": value,
        }
    count, lines = occurrence_lines(text, value)
    status = "ok" if count == 1 else ("missing" if count == 0 else "ambiguous")
    return {
        "field": field,
        "status": status,
        "count": count,
        "lines": lines,
        "value": value,
    }


def list_smali_files(roots: list[str]) -> list[tuple[str, str, str]]:
    """Returns ``(root, absolute path, path relative to root)`` for every file."""
    files: list[tuple[str, str, str]] = []
    for root in roots:
        for directory, subdirectories, filenames in os.walk(root, followlinks=False):
            subdirectories.sort()
            for filename in sorted(filenames):
                if not filename.endswith(".smali"):
                    continue
                absolute = os.path.join(directory, filename)
                relative = os.path.relpath(absolute, root)
                files.append((root, absolute, relative.replace(os.sep, "/")))
    return files


def build_inference_index(
    roots: list[str],
    needles: set[str],
) -> tuple[dict[str, list[tuple[str, str]]], int]:
    """Map each exact needle to the files that contain it.

    The whole tree is read once. Reading it once per needle would multiply a
    387 MB tree by the number of failing entries, which is both slower and more
    likely to hit a transient read error.
    """
    index: dict[str, list[tuple[str, str]]] = {needle: [] for needle in needles}
    files = list_smali_files(roots)
    for root, absolute, relative in files:
        if not needles:
            break
        text = read_text(absolute)
        for needle in needles:
            if needle and needle in text:
                index[needle].append((relative, absolute))
    return index, len(files)


def infer_move(
    patch: dict,
    roots: list[str],
    index: dict[str, list[tuple[str, str]]],
) -> tuple[dict | None, list[str]]:
    method = patch.get("methodSignature")
    anchor = patch.get("anchor")
    method_hits = index.get(method, []) if method else []
    anchor_hits = index.get(anchor, []) if anchor else []
    notes: list[str] = []

    if not method_hits and not anchor_hits:
        return None, notes

    if method_hits and anchor_hits:
        method_paths = {path for path, _ in method_hits}
        anchor_paths = {path for path, _ in anchor_hits}
        common = sorted(method_paths & anchor_paths)
        if len(common) == 1:
            path = common[0]
            absolutes = sorted(
                {
                    absolute
                    for relative, absolute in method_hits + anchor_hits
                    if relative == path
                }
            )
            if len(absolutes) != 1:
                notes.append(
                    "the common path exists under more than one smali root"
                )
                return {
                    "new_path": None,
                    "candidates": absolutes,
                    "evidence": "methodSignature+anchor",
                    "confidence": "ambiguous",
                    "method_candidates": sorted(method_paths),
                    "anchor_candidates": sorted(anchor_paths),
                }, notes
            return {
                "new_path": path,
                "absolute_path": absolutes[0],
                "evidence": "methodSignature+anchor",
                "confidence": "strong",
                "method_candidates": sorted(method_paths),
                "anchor_candidates": sorted(anchor_paths),
            }, notes
        if len(common) > 1:
            notes.append(
                f"both the method and anchor occur in {len(common)} common files"
            )
            return {
                "new_path": None,
                "candidates": common,
                "evidence": "methodSignature+anchor",
                "confidence": "ambiguous",
                "method_candidates": sorted(method_paths),
                "anchor_candidates": sorted(anchor_paths),
            }, notes
        if len(method_paths) == 1 and len(anchor_paths) == 1:
            notes.append(
                "the method and anchor point at different files; one of them may "
                "be a coincidental match"
            )
            return {
                "new_path": None,
                "candidates": sorted(method_paths | anchor_paths),
                "evidence": "methodSignature+anchor",
                "confidence": "conflict",
                "method_candidates": sorted(method_paths),
                "anchor_candidates": sorted(anchor_paths),
            }, notes
        notes.append("the method and anchor do not agree on a single file")
        return {
            "new_path": None,
            "candidates": sorted(method_paths | anchor_paths),
            "evidence": "methodSignature+anchor",
            "confidence": "ambiguous",
            "method_candidates": sorted(method_paths),
            "anchor_candidates": sorted(anchor_paths),
        }, notes

    hits = method_hits or anchor_hits
    evidence = "methodSignature" if method_hits else "anchor"
    paths = sorted({path for path, _ in hits})
    if len(paths) == 1:
        path = paths[0]
        absolutes = sorted(
            {absolute for relative, absolute in hits if relative == path}
        )
        if len(absolutes) != 1:
            notes.append(
                f"{evidence} matches {path} under more than one smali root"
            )
            return {
                "new_path": None,
                "candidates": absolutes,
                "evidence": evidence,
                "confidence": "ambiguous",
            }, notes
        return {
            "new_path": path,
            "absolute_path": absolutes[0],
            "evidence": evidence,
            "confidence": "medium",
            "candidates": paths,
        }, notes
    notes.append(f"{evidence} occurs in {len(paths)} files")
    return {
        "new_path": None,
        "candidates": paths,
        "evidence": evidence,
        "confidence": "ambiguous",
    }, notes


def run_checks(
    patches: list[dict],
    roots: list[str],
) -> tuple[list[dict], list[dict], int]:
    results: list[dict] = []
    moves: list[dict] = []
    inference_needles: set[str] = set()
    for patch in patches:
        if patch.get("methodSignature"):
            inference_needles.add(patch["methodSignature"])
        if patch.get("anchor"):
            inference_needles.add(patch["anchor"])
    index, file_count = build_inference_index(roots, inference_needles)

    for patch in patches:
        result = dict(patch)
        checks: list[dict] = []
        problems: list[dict] = [{"kind": "invalid", "detail": problem} for problem in patch["problems"]]
        resolved, candidates, path_problem = resolve_path(roots, patch.get("smaliPath"))
        result["resolved_path"] = resolved
        result["path_candidates"] = candidates
        if path_problem:
            problems.append(
                {
                    "kind": "missing_path",
                    "detail": path_problem,
                    "candidates": candidates,
                }
            )
            result["text"] = None
        else:
            result["text"] = read_text(resolved)

        if result.get("text") is not None:
            for field in CHECKED_FIELDS:
                check = check_field(result["text"], patch.get(field), field)
                checks.append(check)
                if check["status"] == "missing":
                    problems.append(
                        {
                            "kind": f"missing_{field}",
                            "field": field,
                            "value": check.get("value"),
                            "count": check["count"],
                        }
                    )
                elif check["status"] == "ambiguous":
                    problems.append(
                        {
                            "kind": f"ambiguous_{field}",
                            "field": field,
                            "value": check.get("value"),
                            "count": check["count"],
                            "lines": check["lines"],
                        }
                    )
                elif check["status"] == "invalid":
                    problems.append(
                        {
                            "kind": "invalid",
                            "field": field,
                            "detail": check.get("detail"),
                        }
                    )
        result["checks"] = checks
        result["problems"] = problems

        needs_move = any(
            problem["kind"]
            in (
                "missing_path",
                "missing_methodSignature",
                "missing_anchor",
                "missing_switchCaseLabel",
            )
            for problem in problems
        )
        if needs_move:
            move, notes = infer_move(patch, roots, index)
            if move:
                result["move"] = move
                if move.get("new_path"):
                    move = dict(move)
                    move["title"] = patch.get("title")
                    move["old_path"] = patch.get("smaliPath")
                    move["source"] = patch.get("source")
                    move["line"] = patch.get("line")
                    moves.append(move)
            if notes:
                result["inference_notes"] = notes
        result["status"] = "ok" if not problems else problems[0]["kind"]
        results.append(result)
    return results, moves, file_count


def summarize(results: list[dict], moves: list[dict], file_count: int) -> dict:
    kinds: dict[str, int] = {}
    for result in results:
        for problem in result["problems"]:
            kinds[problem["kind"]] = kinds.get(problem["kind"], 0) + 1
    ambiguous_moves = sum(
        1
        for result in results
        if result.get("move", {}).get("confidence") in ("ambiguous", "conflict")
    )
    return {
        "patches": len(results),
        "ok": sum(1 for result in results if not result["problems"]),
        "failures": sum(1 for result in results if result["problems"]),
        "missing_paths": kinds.get("missing_path", 0),
        "missing_methods": kinds.get("missing_methodSignature", 0),
        "missing_anchors": kinds.get("missing_anchor", 0),
        "missing_switch_labels": kinds.get("missing_switchCaseLabel", 0),
        "ambiguous_checks": sum(
            count
            for kind, count in kinds.items()
            if kind.startswith("ambiguous_")
        ),
        "invalid": kinds.get("invalid", 0),
        "moves": len(moves),
        "ambiguous_moves": ambiguous_moves,
        "smali_files_scanned": file_count,
    }


def render_text(report: dict) -> str:
    lines = ["check_native_anchors report", ""]
    lines.append(f"tree     {report['inputs']['tree']}")
    for root in report["inputs"]["smali_roots"]:
        lines.append(f"root     {root}")
    for path in report["inputs"]["kotlin"]:
        lines.append(f"kotlin   {path}")
    lines.append("")
    summary = report["summary"]
    lines.append(
        "summary  "
        + "  ".join(f"{key}={value}" for key, value in summary.items())
    )
    lines.append("")
    failed = [result for result in report["patches"] if result["problems"]]
    lines.append(f"FAILED PATCHES: {len(failed)}")
    for result in failed:
        title = result.get("title") or "(untitled)"
        source = os.path.basename(result["source"])
        lines.append(
            f"  [{result['status']}] {title} ({source}:{result['line']})"
        )
        lines.append(f"    path: {result.get('smaliPath')}")
        for problem in result["problems"]:
            kind = problem["kind"]
            if kind == "missing_path":
                lines.append(
                    f"    missing path: {problem['detail']}"
                )
                if problem.get("candidates"):
                    for candidate in problem["candidates"]:
                        lines.append(f"      candidate: {candidate}")
            elif kind in (
                "missing_methodSignature",
                "missing_anchor",
                "missing_switchCaseLabel",
                "ambiguous_methodSignature",
                "ambiguous_anchor",
                "ambiguous_switchCaseLabel",
            ):
                value = problem.get("value") or ""
                one_line = value.replace("\n", "\\n")
                if len(one_line) > 160:
                    one_line = one_line[:157] + "..."
                lines.append(
                    f"    {kind}: {one_line!r} "
                    f"(count={problem.get('count')})"
                )
                if problem.get("lines"):
                    lines.append(f"      lines: {problem['lines']}")
            else:
                lines.append(f"    {kind}: {problem.get('detail')}")
        move = result.get("move")
        if move:
            if move.get("new_path"):
                lines.append(
                    f"    inferred move: {result.get('smaliPath')} -> "
                    f"{move['new_path']} ({move['evidence']}, "
                    f"{move['confidence']})"
                )
            else:
                lines.append(
                    f"    move not resolved ({move.get('evidence')}, "
                    f"{move.get('confidence')}): {move.get('candidates')}"
                )
        if result.get("inference_notes"):
            for note in result["inference_notes"]:
                lines.append(f"    note: {note}")
    if report["moves"]:
        lines.append("")
        lines.append(f"INFERRED MOVES: {len(report['moves'])}")
        for move in report["moves"]:
            lines.append(
                f"  {move['old_path']} -> {move['new_path']} "
                f"({move['evidence']}, {move['confidence']})"
            )
    return "\n".join(lines).rstrip() + "\n"


def write_json(path: str, document: dict) -> None:
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".anchors-", suffix=".json", dir=directory)
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
    fd, temporary = tempfile.mkstemp(prefix=".anchors-", suffix=".txt", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def run_self_test() -> int:
    checks = 0

    def check(condition: bool, message: str) -> None:
        nonlocal checks
        if not condition:
            raise ToolError(f"self-test failed: {message}")
        checks += 1

    with tempfile.TemporaryDirectory(prefix="native-anchors-test-") as temp:
        tree = os.path.join(temp, "tree")
        smali = os.path.join(tree, "smali", "com", "example")
        smali2 = os.path.join(tree, "smali_classes2", "com", "example")
        os.makedirs(smali)
        os.makedirs(smali2)
        with open(os.path.join(smali, "A.smali"), "w", encoding="utf-8") as handle:
            handle.write(
                ".class public Lcom/example/A;\n"
                ".super Ljava/lang/Object;\n"
                ".method public static foo()V\n"
                '    const-string v0, "hello"\n'
                "    return-void\n"
                ".end method\n"
                ".method public static label()V\n"
                "    :pswitch_0\n"
                "    return-void\n"
                ".end method\n"
            )
        with open(os.path.join(smali, "A$B.smali"), "w", encoding="utf-8") as handle:
            handle.write(
                ".method public static dollar()V\n"
                '    const-string v0, "dollar-anchor"\n'
                "    return-void\n"
                ".end method\n"
            )
        with open(os.path.join(smali2, "B.smali"), "w", encoding="utf-8") as handle:
            handle.write(
                ".class public Lcom/example/B;\n"
                ".method public static bar()V\n"
                '    const-string v0, "moved-anchor"\n'
                "    return-void\n"
                ".end method\n"
            )
        kotlin = os.path.join(temp, "Patches.kt")
        with open(kotlin, "w", encoding="utf-8") as handle:
            handle.write(
                "val PATCHES = listOf(\n"
                "    SmaliPatch(\n"
                '        title = "pass",\n'
                '        smaliPath = "com/example/A.smali",\n'
                '        methodSignature = ".method public static foo()V",\n'
                '        anchor = "const-string v0, \\"hello\\"",\n'
                '        switchCaseLabel = ":pswitch_0"\n'
                "    ),\n"
                "    SmaliPatch(\n"
                '        title = "move",\n'
                '        smaliPath = "com/example/Gone.smali",\n'
                '        methodSignature = ".method public static bar()V",\n'
                '        anchor = """const-string v0, "moved-anchor""""\n'
                "    ),\n"
                "    SmaliPatch(\n"
                '        title = "missing",\n'
                '        smaliPath = "com/example/A.smali",\n'
                '        anchor = "not-there"\n'
                "    ),\n"
                "    SmaliPatch(\n"
                '        title = "dollar",\n'
                '        smaliPath = "com/example/A${\'$\'}B.smali",\n'
                '        anchor = "dollar-anchor"\n'
                "    )\n"
                ")\n"
            )
        patches = parse_smali_patches(kotlin)
        check(len(patches) == 4, "patch count")
        check(patches[0]["title"] == "pass", "title")
        check(patches[2]["anchor"] == "not-there", "normal string")
        check(patches[3]["smaliPath"] == "com/example/A$B.smali", "dollar escape")
        roots = discover_smali_roots(tree)
        check(len(roots) == 2, "smali roots")
        results, moves, file_count = run_checks(patches, roots)
        check(file_count == 3, "smali file count")
        by_title = {result["title"]: result for result in results}
        check(by_title["pass"]["status"] == "ok", "passing patch")
        check(by_title["dollar"]["status"] == "ok", "dollar patch")
        check(
            any(
                problem["kind"] == "missing_anchor"
                for problem in by_title["missing"]["problems"]
            ),
            "missing anchor",
        )
        check(by_title["move"].get("move", {}).get("new_path") == "com/example/B.smali",
              "inferred move")
        check(len(moves) == 1, "move list")
        report = {
            "tool": "check_native_anchors",
            "summary": summarize(results, moves, file_count),
            "inputs": {"tree": tree, "smali_roots": roots, "kotlin": [kotlin]},
            "patches": results,
            "moves": moves,
        }
        text = render_text(report)
        check("INFERRED MOVES" in text, "text report")
        check("missing_anchor" in text, "text failure")

    print(f"self-test: ok ({checks} checks)")
    return 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="check_native_anchors.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            "Check every Discord SmaliPatch path, method signature, anchor and "
            "switch-case label against a decompiled tree. No Kotlin or smali "
            "file is modified."
        ),
        epilog=(
            "Exit status: 0 when every check passes, 1 when an entry is missing "
            "or ambiguous, 2 for invalid input. --report-only keeps the report "
            "but returns 0."
        ),
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run parser and checker tests in a temporary tree",
    )
    parser.add_argument("--tree", help="decompiled target tree (apktool output)")
    parser.add_argument(
        "--kotlin",
        action="append",
        help="Kotlin file with SmaliPatch entries; repeat for more than one "
        "(default: DiscordNativePatches.kt and DiscordPatches.kt)",
    )
    parser.add_argument(
        "--smali-root",
        action="append",
        help="smali root to search; repeat for more than one (default: detect "
        "smali and smali_classes* under --tree)",
    )
    parser.add_argument("--report-json", help="write the full report as JSON")
    parser.add_argument("--report-txt", help="write the report as plain text")
    parser.add_argument(
        "--report-only",
        action="store_true",
        help="return 0 even when findings exist",
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    parser.add_argument("--version", action="version", version="check_native_anchors.py 1")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.self_test:
        return run_self_test()
    if not args.tree:
        print("error: --tree is required", file=sys.stderr)
        return 2
    try:
        kotlin_files = args.kotlin or DEFAULT_KOTLIN
        for path in kotlin_files:
            if not os.path.isfile(path):
                raise ToolError(f"Kotlin file does not exist: {path}")
        tree = os.path.abspath(args.tree)
        if args.smali_root:
            roots = [os.path.abspath(root) for root in args.smali_root]
            for root in roots:
                if not os.path.isdir(root):
                    raise ToolError(f"smali root does not exist: {root}")
        else:
            roots = discover_smali_roots(tree)
        patches: list[dict] = []
        for path in kotlin_files:
            patches.extend(parse_smali_patches(path))
        if not patches:
            raise ToolError("no SmaliPatch entries were found")
        results, moves, file_count = run_checks(patches, roots)
        summary = summarize(results, moves, file_count)
        report = {
            "tool": "check_native_anchors",
            "tool_version": 1,
            "inputs": {
                "tree": tree,
                "smali_roots": roots,
                "kotlin": [os.path.abspath(path) for path in kotlin_files],
            },
            "summary": summary,
            "patches": results,
            "moves": moves,
        }
        text = render_text(report)
        if args.report_json:
            write_json(args.report_json, report)
        if args.report_txt:
            write_text(args.report_txt, text)
        print(
            f"checked {summary['patches']} patches across "
            f"{summary['smali_files_scanned']} smali files: "
            f"{summary['ok']} pass, {summary['failures']} need attention; "
            f"{summary['moves']} inferred moves"
        )
        if args.verbose or not (args.report_txt or args.report_json):
            print()
            print(text)
        if args.report_json:
            print(f"wrote JSON report: {os.path.abspath(args.report_json)}")
        if args.report_txt:
            print(f"wrote text report: {os.path.abspath(args.report_txt)}")
        if summary["failures"] and not args.report_only:
            return 1
        return 0
    except ToolError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
