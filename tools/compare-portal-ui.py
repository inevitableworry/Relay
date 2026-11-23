#!/usr/bin/env python3
"""Put archived official Portal references beside offline Relay screenshots.

No console connection, Android mutation, or external image request is made.
Used to compare UI appearance at equivalent displayed sizes.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "docs/portal-reference/video/mapped"
DEST = ROOT / "docs/screenshots/relay34-glass"
DEST.mkdir(parents=True, exist_ok=True)

FONT = Path("C:/Windows/Fonts/segoeui.ttf")
FONT_BOLD = Path("C:/Windows/Fonts/segoeuib.ttf")


def font(size: int, bold: bool = False):
    path = FONT_BOLD if bold else FONT
    return ImageFont.truetype(str(path), size) if path.exists() else ImageFont.load_default()


def compare(label: str, reference: Path, screenshot: Path, output: Path):
    canvas = Image.new("RGB", (1960, 760), (20, 20, 26))
    paint = ImageDraw.Draw(canvas)
    paint.text((25, 12), "PlayStation Portal reference", font=font(30, True), fill="white")
    paint.text((1000, 12), "Relay 0.3.20 / Android", font=font(30, True), fill="white")
    paint.text((25, 56), f"{label} · archived reference", font=font(19), fill=(190, 185, 201))
    paint.text((1000, 56), f"{label} · Samsung tablet capture", font=font(19), fill=(190, 185, 201))
    for offset, image_path in ((20, reference), (1000, screenshot)):
        with Image.open(image_path) as im:
            im = ImageOps.contain(im.convert("RGB"), (940, 640), Image.Resampling.LANCZOS)
            x = offset + (940 - im.width) // 2
            y = 101 + (640 - im.height) // 2
            canvas.paste(im, (x, y))
            paint.rectangle((x - 1, y - 1, x + im.width, y + im.height),
                            outline=(110, 104, 122), width=1)
    canvas.save(output, optimize=True)
    print(f"{output.relative_to(ROOT)}: {output.stat().st_size} bytes")


compare("Quick menu", REF / "quickmenu-official-2026-rectified.png",
        DEST / "tablet-quickmenu.png", DEST / "quickmenu-side-by-side.png")
compare("Settings", REF / "settings-grid-current-rectified.png",
        DEST / "tablet-settings.png", DEST / "settings-side-by-side.png")
