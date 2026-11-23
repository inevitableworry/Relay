#!/usr/bin/env python3
"""Validate original emulator preview media and write platform-specific notes."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / "docs/release-validation/media"


def verify(platform: str, version: str):
    from PIL import Image
    prefix = f"Relay-{platform}-v{version}-menu"
    still, animated = MEDIA / (prefix + ".png"), MEDIA / (prefix + ".gif")
    if not still.is_file() or not animated.is_file():
        raise SystemExit("No matching versioned screenshot/GIF. Skipping release.")
    with Image.open(still) as im:
        if im.width < 400 or im.height < 240:
            raise SystemExit("Screenshot is too small")
    with Image.open(animated) as im:
        if im.n_frames < 4:
            raise SystemExit("GIF must include at least four captured animation frames")
        if im.info.get("comment", b"").decode("utf-8", "ignore").find("Azahar captured frames") < 0:
            raise SystemExit("GIF lacks real-emulator capture provenance")
    print("Menu preview screenshot and recorded GIF verified for", platform, version)


def notes(platform: str, version: str, output: Path):
    if platform != "3DS":
        raise SystemExit("Unsupported platform release notes")
    output.write_text(f"""# Relay {version} — Nintendo 3DS family

## Platform support

| Device | Tested environment | Status |
| --- | --- | --- |
| Nintendo 3DS XL | Azahar original 3DS emulation | Animated Relay menu and lower controls rendered |
| New Nintendo 3DS XL | Azahar New 3DS emulation | Animated menu, lower keyboard and local button entry rendered |
| Physical 3DS hardware | Not verified | Experimental homebrew; no performance guarantee |

## Included file

**Relay-3DS-v{version}.3dsx** — native ARM11 homebrew application for compatible 3DS Homebrew Launcher setups.

The upper display shows the Relay menu animation and status. The lower touchscreen holds navigation, Search and an on-screen keyboard. This is **an experimental interface preview, not a working PS3, PS4 or PS5 Remote Play streamer**. No PSN pairing, streamed game video/audio or remote-controller delivery is implemented in this build.

## Versioned visual preview

One actual Azahar main-menu screenshot and one capture-derived animated GIF are included as release assets, labeled `v{version}`. The GIF demonstrates the menu animation only, not game streaming.

## Installation

Use a legally configured Nintendo 3DS Homebrew Launcher and transfer the `.3dsx` to its supported application location. Do not attempt to install it as a CIA, and do not use the Android APK on 3DS.

## Integrity

Verify the included `SHA256SUMS.txt` before installing. Releases are immutable and only updated with increasing version numbers.

## Project and credits

Source, feature roadmap and third-party licenses: [Relay](https://github.com/inevitableworry/Relay) and its `CREDITS.md`. Relay is independent and not affiliated with Sony or Nintendo.
""", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["verify", "notes"])
    parser.add_argument("--platform", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.command == "verify":
        verify(args.platform, args.version)
    else:
        notes(args.platform, args.version, Path(args.output))
