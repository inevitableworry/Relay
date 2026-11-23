"""Generate Switch renderer resources from *unchanged* Relay Android sources.

Original UI XMLs, sounds, fonts and graphics are never rewritten.  Vector
drawables are rasterized through resvg as an exact path/paint conversion.
"""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import shutil
import json
import hashlib
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "platforms/switch1/romfs/relay"
NODE = Path(__file__).with_name("vector_to_png.js")
DRAW = ROOT / "android/app/src/main/res/drawable"
ns = "{http://schemas.android.com/apk/res/android}"

def xml_to_svg(src: Path, dest: Path) -> None:
    root = ET.parse(src).getroot()
    w = root.attrib.get(ns + "viewportWidth", "64")
    h = root.attrib.get(ns + "viewportHeight", "64")
    elements = []
    for path in root.iter():
        if path.tag != "path" and not path.tag.endswith("}path"):
            continue
        d = path.attrib.get(ns + "pathData")
        if not d:
            continue
        fill = path.attrib.get(ns + "fillColor", "#00000000")
        alpha = path.attrib.get(ns + "fillAlpha", "1")
        stroke = path.attrib.get(ns + "strokeColor", "none")
        stroke_w = path.attrib.get(ns + "strokeWidth", "0")
        if len(fill) == 9 and fill.startswith("#"):
            alpha = str(float(alpha) * int(fill[1:3],16)/255)
            fill = "#" + fill[3:]
        elems = [f'd="{d}"', f'fill="{fill}"', f'fill-opacity="{alpha}"',
                 f'fill-rule="{path.attrib.get(ns + "fillType", "nonZero").replace("evenOdd","evenodd")}"']
        if stroke != "none": elems += [f'stroke="{stroke}"', f'stroke-width="{stroke_w}"']
        elements.append("<path " + " ".join(elems) + "/>")
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + w + ' ' + h + '" width="' + w + '" height="' + h + '">' + "".join(elements) + '</svg>'
    dest.write_text(svg, encoding="utf-8")

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "audio").mkdir(exist_ok=True)
    (OUT / "fonts").mkdir(exist_ok=True)
    (OUT / "nav").mkdir(exist_ok=True)
    original = {}
    source_root = ROOT / "android/app/src/main"
    for folder,pattern in [(source_root/"assets/portal_audio", "*"),
                          (source_root/"assets/fonts", "*.ttf")]:
        for src in folder.glob(pattern):
            if not src.is_file() or (folder.name == "portal_audio" and src.suffix not in (".ogg", ".pcm")):
                continue
            target = OUT / ("audio" if folder.name == "portal_audio" else "fonts") / src.name
            shutil.copyfile(src, target)
            assert src.read_bytes() == target.read_bytes()
            original[str(src.relative_to(ROOT))] = hashlib.sha256(src.read_bytes()).hexdigest()
    for src in (source_root/"res/drawable-nodpi").glob("portal_*.png"):
        shutil.copyfile(src, OUT / "nav" / src.name)
        original[str(src.relative_to(ROOT))] = hashlib.sha256(src.read_bytes()).hexdigest()
    for name in ("portal_nav_remote", "portal_nav_cloud", "portal_nav_search",
                 "portal_status_wifi", "portal_status_battery", "portal_status_sliders",
                 "portal_hint_l1", "portal_hint_r1"):
        src = DRAW / (name + ".xml")
        svg = OUT / "nav" / (name + ".svg")
        xml_to_svg(src, svg)
        subprocess.run(["node", str(NODE), str(svg), str(svg.with_suffix(".png"))],check=True)
        svg.unlink()
        original[str(src.relative_to(ROOT))] = hashlib.sha256(src.read_bytes()).hexdigest()
    icon_src=ROOT / "docs/branding/relay-icon-original.png"
    icon=Image.open(icon_src).convert("RGB").resize((256,256),Image.Resampling.LANCZOS)
    icon.save(ROOT / "platforms/switch1/icon.jpg",quality=95,subsampling=0)
    original[str(icon_src.relative_to(ROOT))]=hashlib.sha256(icon_src.read_bytes()).hexdigest()
    (OUT / "ASSET_PROVENANCE.json").write_text(json.dumps(original, indent=2, sort_keys=True))
    print(f"Copied unchanged source audio/fonts and rasterized {len(original)} Relay source assets")

if __name__ == "__main__": main()
