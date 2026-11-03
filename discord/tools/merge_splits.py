#!/usr/bin/env python3
"""Merge decoded config splits into a decoded base without clobbering the
base's master values/public.xml + values/attrs.xml.

Rules:
- lib/  : copy from the ABI split (config.arm64_v8a)
- res/  : copy from every split EXCEPT the split's default `values/` dir
          (splits only put public.xml/attrs.xml there, which must NOT
          overwrite the base's master copies)
- public.xml : merge entries from every split into the base's public.xml
          (dedup by (type,name), keep base id on conflict, else split id)
- attrs.xml  : keep the base's only (splits only carry APKTOOL_DUMMY stubs)

Usage:
    merge_splits.py <decoded-base-dir> <decoded-splits-parent-dir>
"""

import os
import re
import shutil
import sys


def read_public(path):
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for m in re.finditer(
            r'<public type="([^"]+)" name="([^"]+)" id="(0x[0-9a-f]+)"',
            f.read(),
        ):
            out[(m.group(1), m.group(2))] = m.group(3)
    return out


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    base = os.path.abspath(sys.argv[1])
    dc = os.path.abspath(sys.argv[2])

    splits = sorted(d for d in os.listdir(dc) if d.startswith("config."))
    if not splits:
        print("[merge] no decoded config.* splits found", file=sys.stderr)
        return 1

    # 1. native libs
    abi = os.path.join(dc, "config.arm64_v8a", "lib")
    if os.path.isdir(abi):
        shutil.copytree(abi, os.path.join(base, "lib"), dirs_exist_ok=True)
        abi_dir = os.path.join(base, "lib", "arm64-v8a")
        print(f"[merge] libs copied: {len(os.listdir(abi_dir))}")

    # 2. res files (skip split's default values/ dir)
    for s in splits:
        sres = os.path.join(dc, s, "res")
        if not os.path.isdir(sres):
            continue
        for entry in os.listdir(sres):
            if entry == "values":  # split default values dir must not win
                continue
            src = os.path.join(sres, entry)
            dst = os.path.join(base, "res", entry)
            if os.path.isdir(src):
                shutil.copytree(src, dst, dirs_exist_ok=True)
            else:
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)
    print("[merge] res files copied (split default values/ skipped)")

    # 3. merge public.xml
    base_pub = read_public(os.path.join(base, "res/values/public.xml"))
    added = 0
    for s in splits:
        for k, v in read_public(
            os.path.join(dc, s, "res/values/public.xml")
        ).items():
            # skip split-local placeholders; step 5 resolves the real names
            if k[1].startswith("APKTOOL_DUMMY_"):
                continue
            if k not in base_pub:
                base_pub[k] = v
                added += 1
    print(
        f"[merge] public.xml merged: total {len(base_pub)} entries (+{added} from splits)"
    )

    # 4. split-install metadata is meaningless in a merged universal APK
    splits0 = os.path.join(base, "res/xml/splits0.xml")
    if os.path.exists(splits0):
        os.remove(splits0)
        print("[merge] splits0.xml removed")
    if base_pub.pop(("xml", "splits0"), None) is not None:
        print("[merge] dropped public.xml row for xml/splits0")

    # 5. Resolve APKTOOL_DUMMY_* placeholders.
    #
    # Splits are decoded standalone, so attrs defined only in the base show up
    # in a split's styles as APKTOOL_DUMMY_0x... . After merging, the base has
    # both the real name and the placeholder, which makes aapt2 fail with
    # "can't assign ID ... entry ID is already assigned to ...". Rewrite every
    # placeholder we can resolve and drop the now-redundant public.xml rows.
    id_to_name = {}
    for (typ, name), rid in base_pub.items():
        if not name.startswith("APKTOOL_DUMMY_"):
            id_to_name.setdefault(rid, name)

    dropped = 0
    for key in list(base_pub):
        typ, name = key
        if name.startswith("APKTOOL_DUMMY_") and base_pub[key] in id_to_name:
            del base_pub[key]
            dropped += 1
    if dropped:
        print(
            f"[merge] dropped {dropped} redundant APKTOOL_DUMMY public.xml entries"
        )

    rewritten = 0
    for root, _dirs, files in os.walk(os.path.join(base, "res")):
        for fn in files:
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(root, fn)
            try:
                with open(path, encoding="utf-8") as f:
                    text = f.read()
            except (UnicodeDecodeError, IsADirectoryError):
                continue
            if "APKTOOL_DUMMY_" not in text:
                continue
            new = text
            for rid, name in id_to_name.items():
                new = new.replace(f"APKTOOL_DUMMY_{rid}", name)
            if new != text:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(new)
                rewritten += 1
    if rewritten:
        print(f"[merge] resolved APKTOOL_DUMMY refs in {rewritten} res file(s)")

    # 6. write the final public.xml
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for typ, name in sorted(base_pub):
        lines.append(
            f'    <public type="{typ}" name="{name}" id="{base_pub[(typ, name)]}" />'
        )
    lines.append("</resources>")
    with open(
        os.path.join(base, "res/values/public.xml"), "w", encoding="utf-8"
    ) as f:
        f.write("\n".join(lines) + "\n")
    print(f"[merge] public.xml written: {len(base_pub)} entries")

    return 0


if __name__ == "__main__":
    sys.exit(main())
