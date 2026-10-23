"""Export the desktop core clips as an offline Android asset package.

The desktop converter remains the only WebM/alpha decoder. This exporter copies
its prepared WebP frames and derives the mobile catalog, never edits source art.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import sys
import time
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
CORE_GROUPS = frozenset(("idle", "move", "turn", "click", "drag"))
#: Every source folder that travels with the APK. The desktop plays the two extra
#: folders as idle variety and as balance reactions; the phone engine only owns
#: five groups, so both fold into ``idle`` - the pet then idles through the whole
#: 96-clip pool instead of breathing on a single loop.
SUPPLY_GROUPS = CORE_GROUPS | frozenset(("random", "events"))
GROUP_ALIAS = {"random": "idle", "events": "idle"}
CLICK_SOUND_SOURCES = {"click_press.mp3": "Ya1.mp3", "click_release.mp3": "Ya2.mp3"}
#: Assets shipped with the APK as license/notice files. The click sounds below are
#: MIT assets (MeteorNOX/DeepSeek-Balance-Whale-Widget) whose full license text lives
#: in the repository's THIRD_PARTY_NOTICES.md - a pointer to the source repository is
#: not a copy of the material, so that file travels with the APK assets too.
NOTICE_FILES = (
    (ROOT / "android" / "NOTICE.txt", "NOTICE.txt"),
    (ROOT / "android" / "LICENSE-APACHE-2.0.txt", "LICENSE-APACHE-2.0.txt"),
    (ROOT / "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md"),
)


def copy_notices(output: Path) -> list[Path]:
    """Copy the license/notice files into the APK asset root (missing one is fatal)."""
    written = []
    for source, name in NOTICE_FILES:
        if not source.is_file():
            raise FileNotFoundError(f"missing notice file: {source}")
        destination = output / name
        shutil.copyfile(source, destination)
        written.append(destination)
    return written


def copy_click_sounds(sounds_root: Path, res_output: Path) -> list[Path]:
    target = res_output / "raw"
    target.mkdir(parents=True, exist_ok=True)
    written = []
    for name, origin in sorted(CLICK_SOUND_SOURCES.items()):
        source = sounds_root / origin
        if not source.is_file():
            raise FileNotFoundError(f"missing click sound: {source}")
        destination = target / name
        shutil.copyfile(source, destination)
        written.append(destination)
    return written


def publish_frames(jobs: list[tuple[Path, Path]], quality: int | None) -> None:
    """Copies frames verbatim, or re-encodes them lossy at [quality] percent.

    The lossless tier is ~50 KB per 640x360 frame; the full 106-clip set would
    be ~1 GB of APK assets. A lossy pass keeps the same pixels and the same
    manifest (crop boxes are draw-time), and 80 lands around 6x smaller with
    artifacts the pet's ~300 px body never shows.
    """
    if quality is None:
        for source, target in jobs:
            shutil.copyfile(source, target)
        return
    from concurrent.futures import ThreadPoolExecutor

    def encode(pair: tuple[Path, Path]) -> None:
        source, target = pair
        with Image.open(source) as image:
            image.save(target, "WEBP", quality=quality, method=4, exact=True)

    with ThreadPoolExecutor(max_workers=6) as pool:
        for _ in pool.map(encode, jobs):
            pass


def clip_line(name: str, lines: dict[str, str]) -> str:
    """The line spoken while ``name`` plays, or "".

    Three passes, in order of confidence: the exact clip name, the clip name
    without its group prefix (``点击回应-傲娇生气`` -> ``傲娇生气``), and finally a
    plain substring hit (``是啊，吃什么`` -> ``吃什么``). Being forgiving here is
    deliberate: the action list is written by hand and a strict lookup silently
    drops a whole line.
    """
    if not lines:
        return ""
    if name in lines:
        return lines[name]
    for key, text in lines.items():
        if name.endswith("-" + key):
            return text
    for key, text in lines.items():
        if key in name:
            return text
    return ""


def export_prepared(videos: Path, prepared: dict[Path, Path], output: Path,
                    phrases: list[str], alias: dict[str, str] | None = None,
                    quality: int | None = None,
                    lines: dict[str, str] | None = None) -> dict:
    videos, output = videos.resolve(), output.resolve()
    if output == videos or videos in output.parents or output in videos.parents:
        raise ValueError("output overlaps the source package")
    alias = dict(GROUP_ALIAS if alias is None else alias)
    groups = {alias.get(source.parent.name, source.parent.name) for source in prepared}
    if not CORE_GROUPS <= groups:
        raise ValueError(f"missing required actions: {sorted(CORE_GROUPS - groups)}")
    geometry = json.loads((videos / "manifest.json").read_text(encoding="utf-8"))
    strides_file = videos / "move_strides.json"
    strides = json.loads(strides_file.read_text(encoding="utf-8")) if strides_file.exists() else {}
    clips = []
    canvas = None
    for source, directory in sorted(prepared.items(), key=lambda pair: pair[0].as_posix()):
        relative = source.resolve().relative_to(videos).as_posix()
        raw_group = relative.split("/")[0]
        group = alias.get(raw_group, raw_group)
        if raw_group not in SUPPLY_GROUPS:
            continue
        meta = json.loads((directory / "meta.json").read_text(encoding="utf-8"))
        count, fps = int(meta["frames"]), float(meta["fps"])
        if count < 1 or not math.isfinite(fps) or fps <= 0:
            raise ValueError(f"invalid frame metadata: {relative}")
        bounds = None
        for index in range(1, count + 1):
            frame = directory / f"f_{index:04d}.webp"
            if not frame.is_file():
                raise ValueError(f"missing frame: {frame}")
            with Image.open(frame) as image:
                if canvas is None:
                    canvas = image.size
                if image.size != canvas or "A" not in image.getbands():
                    raise ValueError(f"invalid frame geometry/alpha: {frame}")
                bbox = image.getchannel("A").point(lambda a: 255 if a >= 16 else 0).getbbox()
                if bbox:
                    bounds = bbox if bounds is None else (
                        min(bounds[0], bbox[0]), min(bounds[1], bbox[1]),
                        max(bounds[2], bbox[2]), max(bounds[3], bbox[3]))
        if bounds is None:
            raise ValueError(f"empty animation: {relative}")
        # Symmetric crop keeps mirroring and the desktop body anchor aligned.
        left = max(0, min(bounds[0], canvas[0] - bounds[2]) - 2)
        crop = [left, max(0, bounds[1] - 2), canvas[0] - left, min(canvas[1], bounds[3] + 2)]
        identity = group + "_" + hashlib.sha256(relative.encode("utf-8")).hexdigest()[:12]
        movement = strides.get(source.stem, {})
        if isinstance(movement, (int, float)):
            movement = {"stride": movement}
        clips.append({"id": identity, "group": group, "source": relative,
                      "name": source.stem, "fps": fps, "frames": count,
                      "path": f"clips/{identity}", "crop_box": crop,
                      "stride": float(movement.get("stride", 180)),
                      "curve": movement.get("curve", []),
                      "line": clip_line(source.stem, lines or {}),
                      "_directory": directory})
    if canvas is None:
        raise ValueError("no frames")
    body = geometry["body_box"]
    if not (len(body) == 4 and 0 <= body[0] < body[2] <= canvas[0]
            and 0 <= body[1] < body[3] <= canvas[1]):
        raise ValueError("invalid body_box")
    pet_dir = output / "pet"
    pet_dir.mkdir(parents=True, exist_ok=True)
    jobs: list[tuple[Path, Path]] = []
    for clip in clips:
        directory = clip.pop("_directory")
        target = pet_dir / clip["path"]
        target.mkdir(parents=True, exist_ok=True)
        for index in range(1, clip["frames"] + 1):
            name = f"f_{index:04d}.webp"
            jobs.append((directory / name, target / name))
    publish_frames(jobs, quality)
    manifest = {"schema_version": 1, "character_id": videos.parent.name,
                "canvas": list(canvas), "body_box": body, "clips": clips,
                "phrases": list(phrases)}
    temporary = pet_dir / "manifest.json.tmp"
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(pet_dir / "manifest.json")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--res-output", type=Path)
    parser.add_argument("--folders", default=",".join(sorted(SUPPLY_GROUPS)),
        help="source folders to ship; default is every folder the desktop plays")
    parser.add_argument("--core-only", action="store_true",
        help="ship only idle/move/turn/click/drag (the 11-clip baseline)")
    parser.add_argument("--webp-quality", type=int, default=None,
        help="re-encode frames lossy at this quality (e.g. 80); default ships them verbatim")
    args = parser.parse_args()
    sys.path.insert(0, str(ROOT))
    from pet.frameseq_provision import clip_out_dir, convert_clip, hot_webms
    from scripts.make_icon import prepare_icon_image

    started = time.perf_counter()
    videos = ROOT / "assets/characters/shenshen/videos"
    folders = (sorted(CORE_GROUPS) if args.core_only
               else [f.strip() for f in args.folders.split(",") if f.strip()])
    prepared = {}
    for source in hot_webms(videos, folders):
        directory = clip_out_dir(source, videos, videos.parent / "frameseq")
        _, error = convert_clip(source, directory)
        if error:
            raise RuntimeError(f"{source.name}: {error}")
        prepared[source] = directory
    lines_file = videos / "clip_lines.json"
    lines = ({k: str(v) for k, v in json.loads(lines_file.read_text(encoding="utf-8"))
              .get("lines", {}).items()} if lines_file.exists() else {})
    # The generic self-talk pool is gone on purpose: the pet speaks when an
    # animation that has a line plays (``clip_lines.json``), never from a
    # hard-coded list of unrelated sentences. The desktop keeps its own pool.
    manifest = export_prepared(videos, prepared, args.output, [],
        quality=args.webp_quality, lines=lines)
    copy_notices(args.output)
    if args.res_output:
        idle = next(c for c in manifest["clips"] if c["group"] == "idle")
        first = args.output / "pet" / idle["path"] / "f_0001.webp"
        with Image.open(first) as source:
            icon = prepare_icon_image(source, side=192)
        resource = args.res_output / "drawable-nodpi"
        resource.mkdir(parents=True, exist_ok=True)
        icon.save(resource / "pet_icon.png")
        copy_click_sounds(ROOT / "assets" / "sounds" / "duck", args.res_output)
    byte_count = sum(p.stat().st_size for p in args.output.rglob("*") if p.is_file())
    print(json.dumps({"clips": len(manifest["clips"]),
                      "frames": sum(c["frames"] for c in manifest["clips"]),
                      "bytes": byte_count, "seconds": round(time.perf_counter() - started, 3)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
