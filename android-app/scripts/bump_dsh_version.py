#!/usr/bin/env python3
"""Increments this fork's versionCode/versionName before a build.

The handoff rule is one number per delivered APK; forgetting it makes the vivo
installer answer "已安装相同版本". Run this before every build:

    python3 scripts/bump_dsh_version.py

versionName is ``<upstream>-dsh.<N>`` and N is the versionCode, so the two can
never drift apart.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

GRADLE = Path(__file__).resolve().parents[1] / "android/app/build.gradle.kts"


def main() -> int:
    text = GRADLE.read_text(encoding="utf-8")
    code_match = re.search(r"versionCode\s*=\s*(\d+)", text)
    name_match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code_match or not name_match:
        print("versionCode/versionName not found in build.gradle.kts", file=sys.stderr)
        return 1
    code = int(code_match.group(1)) + 1
    upstream = name_match.group(1).split("-dsh.")[0]
    text = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {code}", text, count=1)
    text = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{upstream}-dsh.{code}"', text, count=1)
    GRADLE.write_text(text, encoding="utf-8")
    print(f"version -> {upstream}-dsh.{code} (versionCode {code})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
