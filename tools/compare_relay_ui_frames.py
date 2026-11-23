#!/usr/bin/env python3
"""Create honest, reproducible Android-vs-port visual comparisons.

Accepts captured screenshots of the SAME UI state. Produces a side-by-side
image, a heatmap and numeric pixel differences; those are diagnostics, not
proof of visual parity. Different UI states or timing must be rejected by the
reviewer. Capture device/output timestamps in the master markdown first.
"""

from __future__ import annotations

import argparse
from pathlib import Path
from PIL import Image, ImageChops, ImageEnhance, ImageOps, ImageStat, ImageDraw

ROOT = Path(__file__).resolve().parents[1]


def image_for_comparison(path: Path, canvas: tuple[int, int], crop: bool) -> Image.Image:
    source = Image.open(path).convert("RGB")
    if crop:
        return ImageOps.fit(source, canvas, method=Image.Resampling.LANCZOS)
    return ImageOps.pad(
        source, canvas, method=Image.Resampling.LANCZOS, color=(0, 0, 0)
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", type=Path, required=True, help="Android screenshot")
    parser.add_argument("--target", type=Path, required=True, help="Port screenshot")
    parser.add_argument("--out", type=Path, required=True, help="Output comparison PNG")
    parser.add_argument("--crop", action="store_true", help="Center-crop to 16:9 instead of letterboxing")
    parser.add_argument("--state", default="home", help="Comparable UI state")
    parser.add_argument("--time", default="unknown", help="Frame time relative to state trigger")
    args = parser.parse_args()
    for file in (args.android, args.target):
        if not file.is_file():
            parser.error(f"Missing screenshot: {file}")
    width, height = 960, 544
    android = image_for_comparison(args.android, (width, height), args.crop)
    target = image_for_comparison(args.target, (width, height), args.crop)
    diff = ImageChops.difference(android, target)
    # RMS/MAE intentionally include the whole frame; no automatic UI score pass.
    means = ImageStat.Stat(diff).mean
    mean_absolute_difference = sum(means) / len(means)
    heatmap = ImageEnhance.Contrast(diff).enhance(2.2)
    output = Image.new("RGB", (width * 3, height + 68), (11, 12, 23))
    output.paste(android, (0, 68))
    output.paste(target, (width, 68))
    output.paste(heatmap, (width * 2, 68))
    pen = ImageDraw.Draw(output)
    pen.text((20, 12), "ANDROID REFERENCE", fill="white")
    pen.text((width + 20, 12), "TARGET PORT", fill="white")
    pen.text((width * 2 + 20, 12), "AMPLIFIED PIXEL DIFFERENCE", fill="white")
    pen.text((20, 38), f"State: {args.state}   Time: {args.time}   Mean abs channel delta: {mean_absolute_difference:.1f}/255", fill="#c5c7de")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    output.save(args.out)
    print(f"Comparison written: {args.out}")
    print(f"Mean absolute RGB channel difference: {mean_absolute_difference:.2f} / 255")
    print("This is NOT a pass/fail test; review capture state, timing, fonts, geometry, motion and audio separately.")


if __name__ == "__main__":
    main()
