"""Split the exported animation package into a small APK bundle + remote packs.

The exporter (``export_android_assets.py``) produces every clip as WebP frames.
Shipping all of them inside the APK makes it ~450 MB, so this splitter does two
things with that one export:

* a **slim** asset tree for the APK: only a handful of core clips keep their
  frames, the manifest still describes all of them (name / category / bytes) so
  the app can show the full action list immediately;
* a **remote pack** per clip: ``clips/<id>.zip``, downloaded on demand into the
  app's private storage the first time that action is played.

Nothing is re-encoded here - the frames are already in their final form, so the
two outputs are pixel-identical and the pet never changes appearance when a clip
arrives from the network.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

#: Source folder -> the category label shown in the phone's action menu.
CATEGORY_LABELS = {
    "idle": "待机",
    "random": "小动作",
    "events/balance": "余额",
    "click": "点击回应",
    "move": "移动",
    "turn": "转向",
    "drag": "拖拽",
}
#: The order the action menu lists its categories in.
CATEGORY_ORDER = ["待机", "小动作", "点击回应", "移动", "转向", "拖拽", "余额"]

#: Which clips travel inside the APK: one or two per group is enough for the pet
#: to walk, turn, react and idle before anything has been downloaded.
DEFAULT_BUNDLE = {"idle": 2, "click": 2, "move": 1, "turn": 1, "drag": 1}

#: Tried in order by the app; the first reachable one wins.
DEFAULT_BASE_URLS = [
    "https://cdn.jsdelivr.net/gh/Jcnet-9527/dsh-Jpet-phone-assets@main/",
    "https://raw.githubusercontent.com/Jcnet-9527/dsh-Jpet-phone-assets/main/",
    "https://ghfast.top/raw.githubusercontent.com/Jcnet-9527/dsh-Jpet-phone-assets/main/",
]


def category_of(source: str, group: str) -> str:
    """The menu category for a source-relative webm path (``random/x.webm``)."""
    folder = source.rsplit("/", 1)[0] if "/" in source else group
    return CATEGORY_LABELS.get(folder, CATEGORY_LABELS.get(group, group))


def dir_bytes(path: Path) -> int:
    return sum(p.stat().st_size for p in path.rglob("*") if p.is_file())


def pick_bundled(clips: list[dict], spec: dict[str, int]) -> set[str]:
    """The clips whose frames stay in the APK: a few per group, stable order.

    ``idle`` covers 96 clips (待机/小动作/余额); the pick prefers the plain 待机
    loop first so the pet has a calm default before anything is downloaded.
    """
    chosen: set[str] = set()
    for group, count in spec.items():
        pool = sorted(
            (c for c in clips if c["group"] == group),
            key=lambda c: (c["category"] != "待机", c["category"], c["name"], c["id"]),
        )
        for clip in pool[:count]:
            chosen.add(clip["id"])
    return chosen


def write_pack(clip: dict, source: Path, pack_out: Path) -> int:
    """One zip per clip, stored uncompressed: WebP is already compressed."""
    target = pack_out / "clips" / f"{clip['id']}.zip"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and target.stat().st_size > 0:
        return target.stat().st_size
    temporary = target.with_suffix(".zip.tmp")
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_STORED) as archive:
        for frame in sorted(source.glob("*.webp")):
            archive.write(frame, frame.name)
    temporary.replace(target)
    return target.stat().st_size


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True,
        help="the exporter's output tree (contains pet/manifest.json)")
    parser.add_argument("--slim-out", type=Path, required=True,
        help="APK asset tree: core frames + the full manifest")
    parser.add_argument("--pack-out", type=Path, required=True,
        help="remote pack root (clips/<id>.zip + manifest.json)")
    parser.add_argument("--bundle", default=",".join(f"{k}:{v}" for k, v in DEFAULT_BUNDLE.items()),
        help="clips kept inside the APK, e.g. 'idle:2,click:2,move:1,turn:1,drag:1'")
    parser.add_argument("--bundle-all", action="store_true",
        help="keep every clip inside the APK (the old fat build)")
    parser.add_argument("--base-urls", default="",
        help="comma separated remote roots baked into the manifest")
    args = parser.parse_args()

    assets = args.assets.resolve()
    manifest_path = assets / "pet" / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"missing exported manifest: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    slim = args.slim_out.resolve()
    packs = args.pack_out.resolve()
    pet_out = slim / "pet"
    # Never bulk-delete: the slim tree is rebuilt file by file and stale clip
    # folders are removed one directory at a time by the caller if needed.
    pet_out.mkdir(parents=True, exist_ok=True)
    for stale in sorted((pet_out / "clips").glob("*")) if (pet_out / "clips").is_dir() else []:
        if stale.is_dir() and not any(stale.iterdir()):
            stale.rmdir()
    for item in assets.iterdir():
        if item.is_file():
            shutil.copyfile(item, slim / item.name)

    spec = {}
    for part in args.bundle.split(","):
        part = part.strip()
        if not part:
            continue
        group, _, count = part.partition(":")
        spec[group.strip()] = int(count or "1")
    bundled = set(c["id"] for c in manifest["clips"]) if args.bundle_all \
        else pick_bundled([dict(c, category=category_of(c.get("source", ""), c["group"]))
                           for c in manifest["clips"]], spec)

    pack_bytes = 0
    slim_bytes = 0
    entries = []
    for clip in manifest["clips"]:
        source = assets / "pet" / clip["path"]
        if not source.is_dir():
            raise FileNotFoundError(f"missing exported frames: {source}")
        clip["category"] = category_of(clip.get("source", ""), clip["group"])
        clip["bundled"] = clip["id"] in bundled
        clip["bytes"] = dir_bytes(source)
        pack_bytes += write_pack(clip, source, packs)
        if clip["bundled"]:
            destination = pet_out / clip["path"]
            destination.mkdir(parents=True, exist_ok=True)
            for frame in sorted(source.glob("*.webp")):
                shutil.copyfile(frame, destination / frame.name)
            slim_bytes += clip["bytes"]
        entries.append(clip)

    base_urls = [u.strip() for u in args.base_urls.split(",") if u.strip()] or DEFAULT_BASE_URLS
    manifest["packs"] = {
        "schema_version": 1,
        "character_id": manifest.get("character_id", "shenshen"),
        "base_urls": base_urls,
        "layout": "clips/<id>.zip",
        "clips": len(entries),
        "frames": sum(c["frames"] for c in entries),
    }
    (pet_out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    if packs.is_relative_to(ROOT) is False:
        pass
    packs.mkdir(parents=True, exist_ok=True)
    (packs / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    (packs / "CATEGORY_ORDER.json").write_text(
        json.dumps(CATEGORY_ORDER, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps({
        "clips": len(entries),
        "bundled": len(bundled),
        "slim_bytes": slim_bytes + (pet_out / "manifest.json").stat().st_size,
        "pack_bytes": pack_bytes,
        "slim_mb": round(slim_bytes / 1048576, 1),
        "pack_mb": round(pack_bytes / 1048576, 1),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
