#!/usr/bin/env python3
"""Deterministically rebrand inherited KISS user-visible strings for F-Droid builds.

Smart S Launcher is a functional fork of KISS. F-Droid requires forks to have
corresponding name/icon/string changes, including translations. This script
updates only XML text nodes, replacing inherited product-name text "KISS"
(case-insensitively) with "Smart S" across every values*/strings.xml file.
Resource identifiers (for example main_kiss) and XML attributes are intentionally
left unchanged because they are internal API names, not user-visible branding.
"""

from __future__ import annotations

from pathlib import Path
import re
import sys

TEXT_NODE = re.compile(r">([^<]*)<", re.DOTALL)
KISS = re.compile(r"kiss", re.IGNORECASE)


def rebrand_text_nodes(xml: str) -> str:
    def replace(match: re.Match[str]) -> str:
        text = match.group(1)
        return ">" + KISS.sub("Smart S", text) + "<"

    return TEXT_NODE.sub(replace, xml)


def visible_kiss_remains(xml: str) -> bool:
    return any(KISS.search(match.group(1)) for match in TEXT_NODE.finditer(xml))


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
    files = sorted(res_dir.glob("values*/strings.xml"))
    if not files:
        print(f"no localized strings.xml files found below: {res_dir}", file=sys.stderr)
        return 1

    for strings_file in files:
        original = strings_file.read_text(encoding="utf-8")
        branded = rebrand_text_nodes(original)
        if branded != original:
            strings_file.write_text(branded, encoding="utf-8")
            changed += 1
        if visible_kiss_remains(branded):
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
