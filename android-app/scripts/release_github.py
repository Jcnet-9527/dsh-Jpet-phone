#!/usr/bin/env python3
"""Bump, build and publish this fork as a GitHub Release.

The APK is uploaded as a release asset (never committed): 80 MB in git would
bloat the repository. The release tag is created against the branch head, so
push the code first.

    python3 scripts/release_github.py                  # bump + release build + publish
    python3 scripts/release_github.py --no-bump        # keep the current version
    python3 scripts/release_github.py --dry-run        # build only
    python3 scripts/release_github.py --notes-file notes.md
    python3 scripts/release_github.py --debug          # allow a debug-signed build

Secrets never live in this file: the token comes from $GITHUB_TOKEN or
--token-file, and signing comes from android/keystore.properties.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE_FILE = ROOT / "android/app/build.gradle.kts"
KEYSTORE_PROPS = ROOT / "android/keystore.properties"
API = "https://api.github.com"


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    print("+", " ".join(cmd), flush=True)
    return subprocess.run(cmd, check=True, **kwargs)


def gradle_bin() -> str:
    return os.environ.get("GRADLE") or shutil.which("gradle") or "/opt/gradle-8.13/bin/gradle"


def read_version() -> str:
    text = GRADLE_FILE.read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not match:
        sys.exit("versionName not found in build.gradle.kts")
    return match.group(1)


def api(method: str, path: str, token: str, payload: dict | None = None) -> dict:
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(API + path, data=data, method=method)
    request.add_header("Authorization", "token " + token)
    request.add_header("Accept", "application/vnd.github+json")
    if data:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        print("GitHub API error:", error.code, error.read().decode()[:800], file=sys.stderr)
        raise


def read_token(path: str) -> str:
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        return token.strip()
    file = Path(path)
    if not file.exists():
        sys.exit(f"No token: set $GITHUB_TOKEN or {path}")
    return file.read_text().strip()


def default_notes(version: str, release_build: bool, commit: str) -> str:
    signing = "release keystore" if release_build else "debug key (test build)"
    return f"""## 深深桌宠 · DSH 定制版 {version}

- 包名 `io.github.dshpet.mobile.dsh`，可与上游正式版并存
- 签名：{signing}
- 源码：`dsh-integration` @ `{commit[:8]}`

### 安装
1. 下载 `apk` 附件
2. 允许「安装未知来源应用」
3. 首次安装需要「悬浮窗」权限

> 若之前装的是不同签名的版本，需要先卸载旧版（设置与素材会重置）。
"""


def main() -> int:
    parser = argparse.ArgumentParser(description="Build and publish a GitHub Release")
    parser.add_argument("--repo", default="ranw524/dsh-Jpet-phone")
    parser.add_argument("--branch", default="dsh-integration")
    parser.add_argument("--token-file", default="/root/.gh-token")
    parser.add_argument("--no-bump", action="store_true", help="do not increment the version")
    parser.add_argument("--debug", action="store_true", help="allow publishing a debug build")
    parser.add_argument("--dry-run", action="store_true", help="build but do not publish")
    parser.add_argument("--draft", action="store_true", help="leave the release as a draft")
    parser.add_argument("--notes-file", help="markdown file used as the release body")
    parser.add_argument("--apk", help="use an existing APK instead of building")
    args = parser.parse_args()

    if not args.no_bump and not args.apk:
        run([sys.executable, str(ROOT / "scripts/bump_dsh_version.py")])

    release_build = KEYSTORE_PROPS.exists()
    if not release_build and not args.debug and not args.apk:
        sys.exit("android/keystore.properties is missing: refusing to publish a debug build "
                 "(pass --debug to allow it)")

    if args.apk:
        apk = Path(args.apk)
    else:
        flavor = "Release" if release_build else "Debug"
        run([gradle_bin(), "-p", str(ROOT / "android"), f":app:assemble{flavor}",
             "-PpetPython=python3", "--console=plain", "--no-daemon"])
        name = "app-release.apk" if release_build else "app-debug.apk"
        apk = ROOT / f"android/app/build/outputs/apk/{flavor.lower()}/{name}"
    if not apk.exists():
        sys.exit(f"APK not found: {apk}")
    size = apk.stat().st_size
    version = read_version()
    tag = "v" + version
    print(f"APK {apk} ({size} bytes) version {version}")

    if args.dry_run:
        print("dry run: not publishing")
        return 0

    token = read_token(args.token_file)
    head = api("GET", f"/repos/{args.repo}/git/ref/heads/{args.branch}", token)["object"]["sha"]
    body = Path(args.notes_file).read_text(encoding="utf-8") if args.notes_file \
        else default_notes(version, release_build, head)

    release = api("POST", f"/repos/{args.repo}/releases", token, {
        "tag_name": tag,
        "target_commitish": args.branch,
        "name": f"{tag} · 深深桌宠",
        "body": body,
        "draft": True,
        "prerelease": False,
    })
    print("draft release", release["id"])

    asset_name = f"shenshen-{version}.apk"
    upload = f"https://uploads.github.com/repos/{args.repo}/releases/{release['id']}/assets?name={asset_name}"
    run(["curl", "-sS", "-X", "POST", "-H", f"Authorization: token {token}",
         "-H", "Content-Type: application/vnd.android.package-archive",
         "--data-binary", f"@{apk}", upload])

    if not args.draft:
        api("PATCH", f"/repos/{args.repo}/releases/{release['id']}", token, {"draft": False})
    print("release:", f"https://github.com/{args.repo}/releases/tag/{tag}")
    print("asset:", f"https://github.com/{args.repo}/releases/download/{tag}/{asset_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
