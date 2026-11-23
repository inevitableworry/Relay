"""Extract only the Portal's central, text-free sphere from Sony's official UI photo.

The references in docs/portal-reference/ are source imagery for personal comparison.
The Android runtime draws all live UI elements natively and uses this soft-masked
orb as a decorative backdrop.
"""
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs" / "portal-reference" / "remote-play-2025.jpg"
DEST = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable-nodpi" / "portal_orb.png"
DRAWABLE_DIR = DEST.parent

def main():
    original = Image.open(SOURCE).convert("RGB")
    assert original.size == (1920, 1080), original.size
    left, top, right, bottom = 565, 135, 1455, 1015
    crop = original.crop((left, top, right, bottom)).convert("RGBA")

    # Keep the globe and its atmospheric halo, excluding Sony's static title,
    # navigation controls, status indicators and Connect button.
    mask = Image.new("L", crop.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((95, 35, 805, 765), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(45))
    # The official title ends close to the left edge of the sphere. Remove
    # the few text pixels at that border while preserving its central stars.
    text_clear = Image.new("L", crop.size, 0)
    ImageDraw.Draw(text_clear).rectangle((0, 65, 225, 235), fill=255)
    text_clear = text_clear.filter(ImageFilter.GaussianBlur(30))
    mask = ImageChops.subtract(mask, text_clear)
    crop.putalpha(mask)
    DEST.parent.mkdir(parents=True, exist_ok=True)
    crop.save(DEST, optimize=True)
    print(f"Prepared {DEST} ({crop.size[0]}x{crop.size[1]})")

    # The three navigation states are genuine Sony pixel references. Cropping
    # the switcher gives the exact icon glyphs, selected pill, opacity and
    # spacing. Android lays three transparent hit targets over the raster.
    for tab, source in {
        "remote": "remote-play-2025.jpg",
        "cloud": "cloud-2025.jpg",
        "search": "search-2025.jpg",
    }.items():
        image = Image.open(ROOT / "docs" / "portal-reference" / source).convert("RGBA")
        rect = (76, 77, 428, 175)
        nav = image.crop(rect)
        nav_mask = Image.new("L", nav.size, 0)
        ImageDraw.Draw(nav_mask).rounded_rectangle(
            (1, 1, nav.width - 2, nav.height - 2), radius=47, fill=255
        )
        nav.putalpha(nav_mask)
        target = DRAWABLE_DIR / f"portal_nav_{tab}.png"
        nav.save(target, optimize=True)
        print(f"Prepared {target}")

if __name__ == "__main__":
    main()
