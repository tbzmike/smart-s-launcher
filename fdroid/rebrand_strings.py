#!/usr/bin/env python3
"""Deterministically rebrand inherited KISS user-visible strings for F-Droid builds.

Smart S Launcher is a functional fork of KISS. F-Droid requires forks to have
corresponding name/icon/string changes, including translations. This script
updates only XML text content by replacing the inherited product name "KISS"
with "Smart S" across every values*/strings.xml file. Resource identifiers
(e.g. main_kiss) are intentionally left unchanged because they are internal API
names and not user-visible branding.
"""

from __future__ import annotations

from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: rebrand_strings.py <res-directory>", file=sys.stderr)
        return 2

    res_dir = Path(sys.argv[1]).resolve()
    if not res_dir.is_dir():
        print(f"resource directory not found: {res_dir}", file=sys.stderr)
        return 2

    changed = 0
    remaining = []
    for strings_file in sorted(res_dir.glob("values*/strings.xml")):
        original = strings_file.read_text(encoding="utf-8")
        branded = original.replace("KISS", "Smart S")
        if branded != original:
            strings_file.write_text(branded, encoding="utf-8")
            changed += 1
        if "KISS" in strings_file.read_text(encoding="utf-8"):
            remaining.append(str(strings_file))

    if remaining:
        print("unrebranded KISS text remains in:", file=sys.stderr)
        for path in remaining:
            print(f"  {path}", file=sys.stderr)
        return 1

    print(f"Smart S branding applied to {changed} localized strings file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
