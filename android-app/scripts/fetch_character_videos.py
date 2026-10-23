"""Downloads the character's source WebM clips when they are not in the tree.

The repository keeps the small files (``manifest.json``, ``move_strides.json``,
``FILES.txt``) but not the 52 MB of source video, so a plugin-only clone stays
fast. Building the APK needs the real clips, so run this once first:

    python scripts/fetch_character_videos.py

Files already present are skipped, and every download is retried across the
same mirrors the app itself uses for its animation packs.
"""
from __future__ import annotations

import argparse
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VIDEOS = ROOT / "assets" / "characters" / "shenshen" / "videos"
MIRRORS = [
    "https://cdn.jsdelivr.net/gh/Jcnet-9527/dsh-Jpet-phone-assets@main/source/shenshen/videos/",
    "https://raw.githubusercontent.com/Jcnet-9527/dsh-Jpet-phone-assets/main/source/shenshen/videos/",
    "https://ghfast.top/raw.githubusercontent.com/Jcnet-9527/dsh-Jpet-phone-assets/main/source/shenshen/videos/",
]


def wanted() -> list[str]:
    index = VIDEOS / "FILES.txt"
    if index.is_file():
        return [line.strip() for line in index.read_text(encoding="utf-8").splitlines()
                if line.strip()]
    raise FileNotFoundError(f"missing file list: {index}")


def fetch(name: str, destination: Path) -> bool:
    for base in MIRRORS:
        try:
            request = urllib.request.Request(base + urllib.parse.quote(name.replace("\\", "/")),
                headers={"User-Agent": "dsh-pet-mobile"})
            with urllib.request.urlopen(request, timeout=120) as response:
                payload = response.read()
        except (urllib.error.URLError, TimeoutError, OSError):
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(destination.suffix + ".part")
        temporary.write_bytes(payload)
        temporary.replace(destination)
        return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="re-download everything")
    args = parser.parse_args()
    names = wanted()
    missing = [n for n in names
               if args.force or not (VIDEOS / n).is_file() or (VIDEOS / n).stat().st_size == 0]
    if not missing:
        print(f"character source complete: {len(names)} clips")
        return 0
    started = time.perf_counter()
    for index, name in enumerate(missing, start=1):
        if not fetch(name, VIDEOS / name):
            print(f"FAILED {name}", file=sys.stderr)
            return 1
        if index % 10 == 0 or index == len(missing):
            print(f"{index}/{len(missing)}")
    print(f"fetched {len(missing)} clips in {time.perf_counter() - started:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
