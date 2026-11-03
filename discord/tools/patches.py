#!/usr/bin/env python3
"""List, change, and document the build's patch options.

The manifest (``patches/manifest.json``) is the source of truth for the patch
list and toggle defaults. Five advanced values (capture size, framerate,
surface timeout, and kept locales) remain constants in ``core.py`` and are not
exposed here. The CLI writes a selection file, and ``docs`` regenerates
``OPTIONS.md``. That is the same model ReVanced uses for patch options,
without needing a manager app.

    tools/patches.py list [--json]
    tools/patches.py show <id|KEY>
    tools/patches.py set KEY on|off|true|false
    tools/patches.py toggle <id|KEY>
    tools/patches.py reset
    tools/patches.py docs [--write]
    tools/patches.py edit            # interactive, curses

Selections live in ``discord/options.json``. Run the patcher again to apply
them: ``scripts/02-patch.sh build/<version>``.
"""

import argparse
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MANIFEST = os.path.join(ROOT, "patches", "manifest.json")
SELECTION = os.path.join(ROOT, "options.json")
OPTIONS_MD = os.path.join(ROOT, "OPTIONS.md")

RISK_LABEL = {
    "safe": "safe",
    "device-test": "test on device",
    "experimental": "experimental",
    "warning": "warning",
}


def load():
    try:
        with open(MANIFEST, encoding="utf-8") as f:
            manifest = json.load(f)
    except (OSError, ValueError) as exc:
        raise SystemExit(f"cannot read {MANIFEST}: {exc}")
    selection = {}
    if os.path.exists(SELECTION):
        try:
            with open(SELECTION, encoding="utf-8") as f:
                raw = json.load(f)
        except (OSError, ValueError) as exc:
            print(f"warning: cannot read {SELECTION}: {exc}", file=sys.stderr)
            raw = {}
        if isinstance(raw, dict):
            selection = raw.get("options", raw)
        if not isinstance(selection, dict):
            print(
                f"warning: {SELECTION} must contain an object; using defaults",
                file=sys.stderr,
            )
            selection = {}
    defaults = {
        p["option"]: p.get("default")
        for p in manifest["patches"]
        if p.get("option")
    }
    state = dict(defaults)
    for key, value in selection.items():
        if key in defaults:
            state[key] = as_bool(value, defaults[key])
    return manifest, state


def save(state):
    # Write and rename, so an interrupted save cannot leave a truncated
    # selection file that the patcher would silently ignore.
    tmp = SELECTION + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(
            {"schema": 1, "options": state}, f, indent=2, sort_keys=True
        )
        f.write("\n")
    os.replace(tmp, SELECTION)


def categories(manifest):
    return {c["id"]: c for c in manifest["categories"]}


def options(manifest):
    return [p for p in manifest["patches"] if p.get("type") == "option"]


def find(manifest, needle):
    for p in manifest["patches"]:
        if p["id"] == needle or p.get("option") == needle:
            return p
    return None


def as_bool(value, default):
    """Normalize hand-edited values the same way core.py's opt_bool does."""
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "yes", "on")
    if isinstance(value, (int, float)):
        return bool(value)
    return default


def fmt_bool(v):
    return "on" if as_bool(v, False) else "off"


def cmd_list(args):
    manifest, state = load()
    cats = categories(manifest)
    if args.json:
        print(
            json.dumps(
                {
                    "app": manifest["app"],
                    "patches": [
                        {**p, "value": state.get(p.get("option"), p.get("default"))}
                        for p in manifest["patches"]
                    ],
                },
                indent=2,
            )
        )
        return 0
    app = manifest["app"]
    print(f"{app['name']} {app['version']} ({app['version_code']})")
    print()
    print("Core patches (always applied)")
    for p in manifest["patches"]:
        if p.get("type") == "core":
            print(f"  [x] {p['name']}")
            print(f"      {p['summary']}")
    print()
    print("Optional patches")
    for cid, cat in cats.items():
        rows = [p for p in options(manifest) if p["category"] == cid]
        if not rows:
            continue
        print(f"  {cat['name']}")
        for p in rows:
            value = state.get(p["option"], p.get("default"))
            risk = RISK_LABEL.get(p.get("risk"), p.get("risk", ""))
            print(
                f"    [{fmt_bool(value)}] {p['name']}  ({risk}; {p['option']})"
            )
            print(f"          {p['summary']}")
    print()
    print(f"Selection: {os.path.relpath(SELECTION, ROOT)}"
          if os.path.exists(SELECTION) else "Selection: defaults (no options.json)")
    shadow = glob.glob(os.path.join(ROOT, "build", "*", "options.json"))
    if shadow:
        print(
            "Note: these per-build selections take precedence over the one "
            "above:"
        )
        for p in shadow:
            print(f"  {os.path.relpath(p, ROOT)}")
    print("Change with: tools/patches.py set <KEY> on|off   (or: edit)")
    return 0


def cmd_show(args):
    manifest, state = load()
    p = find(manifest, args.target)
    if p is None:
        print(f"unknown patch: {args.target}", file=sys.stderr)
        return 1
    cat = categories(manifest).get(p["category"], {}).get("name", p["category"])
    print(p["name"])
    print(f"  id:       {p['id']}")
    print(f"  category: {cat}")
    print(f"  type:     {p.get('type')}")
    if p.get("option"):
        print(f"  option:   {p['option']}")
        print(f"  value:    {fmt_bool(state.get(p['option'], p.get('default')))}")
    print(f"  default:  {fmt_bool(p.get('default'))}")
    print(f"  risk:     {RISK_LABEL.get(p.get('risk'), p.get('risk', ''))}")
    print(f"  {p['summary']}")
    if p.get("notes"):
        print(f"  note:     {p['notes']}")
    return 0


def cmd_set(args):
    manifest, state = load()
    key = args.key
    if not any(p.get("option") == key for p in manifest["patches"]):
        print(f"unknown option: {key}", file=sys.stderr)
        return 1
    value = args.value.lower()
    if value in ("on", "true", "1", "yes"):
        state[key] = True
    elif value in ("off", "false", "0", "no"):
        state[key] = False
    else:
        print("value must be on/off or true/false", file=sys.stderr)
        return 1
    save(state)
    print(f"{key} = {fmt_bool(state[key])}  ({os.path.relpath(SELECTION, ROOT)})")
    return 0


def cmd_toggle(args):
    manifest, state = load()
    p = find(manifest, args.target)
    if p is None or not p.get("option"):
        print("not a toggleable option", file=sys.stderr)
        return 1
    key = p["option"]
    state[key] = not state.get(key, p.get("default"))
    save(state)
    print(f"{p['name']}: {fmt_bool(state[key])}")
    return 0


def cmd_reset(args):
    if os.path.exists(SELECTION):
        os.remove(SELECTION)
    print("selection removed; defaults from the manifest apply")
    return 0


def markdown(manifest, state):
    cats = categories(manifest)
    app = manifest["app"]
    lines = [
        "# Options",
        "",
        f"This is the option list for Discord {app['version']} "
        f"(`{app['package']}`).",
        "",
        "Change an option with the patch manager. It writes "
        "`options.json`; the next `scripts/02-patch.sh` run applies it:",
        "",
        "```bash",
        "tools/patches.py edit                 # interactive list",
        "tools/patches.py set EXTERNAL_BROWSER off",
        "scripts/02-patch.sh build/<version>",
        "scripts/03-build.sh  build/<version>",
        "```",
        "",
        "`tools/patches.py list` prints the same list without the detail.",
        "Core patches are always enabled; if a target moved, the patch log",
        "reports a skip instead of guessing.",
        "",
        "A per-build `build/<version>/options.json`, when present, takes",
        "precedence over `discord/options.json`.",
        "",
        "## Quick reference",
        "",
    ]
    for cid, cat in cats.items():
        rows = [p for p in options(manifest) if p["category"] == cid]
        if not rows:
            continue
        lines += [
            f"### {cat['name']}",
            "",
            cat["description"],
            "",
            "| Patch | Default | Current | Risk | Option |",
            "| --- | --- | --- | --- | --- |",
        ]
        for p in rows:
            value = state.get(p["option"], p.get("default"))
            risk = RISK_LABEL.get(p.get("risk"), p.get("risk", ""))
            lines.append(
                f"| {p['name']} | {'On' if p.get('default') else 'Off'} | "
                f"{'On' if value else 'Off'} | {risk} | "
                f"`{p['option']}` |"
            )
        lines.append("")
    lines += ["## Core patches", "", "These are always applied.", ""]
    for p in manifest["patches"]:
        if p.get("type") == "core":
            risk = RISK_LABEL.get(p.get("risk"), p.get("risk", ""))
            lines.append(f"- **{p['name']}** ({risk}). {p['summary']}")
    lines.append("")
    lines += ["## Option details", ""]
    for cid, cat in cats.items():
        rows = [p for p in options(manifest) if p["category"] == cid]
        if not rows:
            continue
        lines += [f"### {cat['name']}", ""]
        for p in rows:
            value = state.get(p["option"], p.get("default"))
            risk = RISK_LABEL.get(p.get("risk"), p.get("risk", ""))
            lines += [
                f"#### {p['name']}",
                "",
                f"`{p['option']}` · default **{'on' if p.get('default') else 'off'}** "
                f"· currently **{'on' if value else 'off'}** · {risk}",
                "",
                p["summary"],
                "",
            ]
            if p.get("notes"):
                lines += [p["notes"], ""]
    return "\n".join(lines).rstrip() + "\n"


def cmd_docs(args):
    manifest, state = load()
    text = markdown(manifest, state)
    if args.write:
        with open(OPTIONS_MD, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"wrote {os.path.relpath(OPTIONS_MD, ROOT)}")
    else:
        sys.stdout.write(text)
    return 0


def cmd_edit(args):
    """Interactive selector: arrows move, space toggles, enter toggles detail."""
    try:
        import curses
    except ImportError:
        print("curses is unavailable; use `set KEY on|off`", file=sys.stderr)
        return 1
    if not sys.stdout.isatty() or os.environ.get("TERM", "dumb") == "dumb":
        print(
            "edit needs an interactive terminal; use `list` or "
            "`set KEY on|off` instead",
            file=sys.stderr,
        )
        return 1
    manifest, state = load()
    rows = []
    for cid, cat in categories(manifest).items():
        group = [p for p in options(manifest) if p["category"] == cid]
        if group:
            rows.append((cat["name"], None))
            rows += [(p["name"], p) for p in group]

    def draw(stdscr, cur, top, show_detail, notice):
        stdscr.erase()
        h, w = stdscr.getmaxyx()
        h, w = max(h, 1), max(w, 1)
        if h < 5 or w < 20:
            stdscr.addnstr(0, 0, "terminal too small; need at least 20x5", w - 1)
            stdscr.refresh()
            return
        title = (
            f"{manifest['app']['name']} {manifest['app']['version']} - "
            "space toggle, enter detail, s save, q quit"
        )
        stdscr.addnstr(0, 0, title, w - 1, curses.A_BOLD)
        visible = h - 3
        for i in range(visible):
            idx = top + i
            if idx >= len(rows):
                break
            label, patch = rows[idx]
            y = i + 1
            if patch is None:
                stdscr.addnstr(y, 0, label, w - 1, curses.A_BOLD)
                continue
            key = patch["option"]
            value = as_bool(state.get(key, patch.get("default")), False)
            mark = "[x]" if value else "[ ]"
            line = f"  {mark} {label}  ({RISK_LABEL.get(patch.get('risk'), '')})"
            attr = curses.A_REVERSE if idx == cur else curses.A_NORMAL
            stdscr.addnstr(y, 0, line, w - 1, attr)
        detail = ""
        if 0 <= cur < len(rows) and rows[cur][1] is not None:
            p = rows[cur][1]
            detail = f"{p['option']}: {p['summary']}"
            if show_detail and p.get("notes"):
                detail += "  " + p["notes"]
        stdscr.addnstr(h - 2, 0, "-" * (w - 1), w - 1)
        stdscr.addnstr(h - 1, 0, notice or detail, w - 1)
        stdscr.refresh()

    def run(stdscr):
        curses.curs_set(0)
        cur, top = 0, 0
        show_detail = False
        dirty = False
        confirm = False
        notice = ""
        while True:
            h, _ = stdscr.getmaxyx()
            visible = max(1, h - 3)
            if cur < top:
                top = cur
            if cur >= top + visible:
                top = cur - visible + 1
            draw(stdscr, cur, top, show_detail, notice)
            notice = ""
            ch = stdscr.getch()
            if confirm:
                if ch in (ord("y"), ord("Y")):
                    return None
                confirm = False
                continue
            if ch in (curses.KEY_UP, ord("k")):
                cur = max(0, cur - 1)
            elif ch in (curses.KEY_DOWN, ord("j")):
                cur = min(len(rows) - 1, cur + 1)
            elif ch in (curses.KEY_NPAGE,):
                cur = min(len(rows) - 1, cur + visible)
            elif ch in (curses.KEY_PPAGE,):
                cur = max(0, cur - visible)
            elif ch in (ord(" "), ord("\t")):
                if 0 <= cur < len(rows) and rows[cur][1] is not None:
                    p = rows[cur][1]
                    key = p["option"]
                    state[key] = not as_bool(
                        state.get(key, p.get("default")), False
                    )
                    dirty = True
            elif ch in (curses.KEY_ENTER, 10, 13):
                show_detail = not show_detail
            elif ch == ord("s"):
                save(state)
                return state
            elif ch in (ord("q"), 27):
                if dirty:
                    confirm = True
                    notice = "Unsaved changes. Press y to discard, any other key to keep editing."
                    continue
                return None

    try:
        result = curses.wrapper(run)
    except curses.error as exc:
        print(f"edit failed: {exc}; use `list` or `set KEY on|off`", file=sys.stderr)
        return 1
    if result is None:
        print("no changes saved")
    else:
        print(f"saved {os.path.relpath(SELECTION, ROOT)}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd")
    p = sub.add_parser("list", help="show patches and current options")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_list)
    p = sub.add_parser("show", help="show one patch")
    p.add_argument("target")
    p.set_defaults(func=cmd_show)
    p = sub.add_parser("set", help="set one option")
    p.add_argument("key")
    p.add_argument("value")
    p.set_defaults(func=cmd_set)
    p = sub.add_parser("toggle", help="flip one option")
    p.add_argument("target")
    p.set_defaults(func=cmd_toggle)
    p = sub.add_parser("reset", help="remove the selection file")
    p.set_defaults(func=cmd_reset)
    p = sub.add_parser("docs", help="regenerate OPTIONS.md")
    p.add_argument("--write", action="store_true")
    p.set_defaults(func=cmd_docs)
    p = sub.add_parser("edit", help="interactive selector")
    p.set_defaults(func=cmd_edit)
    args = ap.parse_args()
    if not getattr(args, "func", None):
        args.func = cmd_list
        args.json = False
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
