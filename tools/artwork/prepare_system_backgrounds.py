"""Prepare Orbit's local system artwork from the pinned sources.

SteamGridDB heroes are wide banners (about 3.1:1). They are kept whole, never cropped
to 16:9; Orbit fills the rest of each screen with a blurred copy and draws the system
name at runtime. Iconic fallbacks are composed as 16:9 images with a name and photo.

Requires Pillow and @resvg/resvg-js (pass its module directory explicitly).
Original downloads stay in --cache; only display derivatives enter app assets.
"""
import argparse
import base64
import concurrent.futures
import io
import json
from pathlib import Path
import subprocess
import urllib.request
from xml.sax.saxutils import escape

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
REVISION = "0a549ffbe5a392bd818ee4c11a7175b2372591e4"
ICONIC = f"https://raw.githubusercontent.com/Siddy212/iconic-es-de/{REVISION}/_inc/systems/default-backgrounds/"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--resvg-module", type=Path, required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    assets = ROOT / "app/src/main/assets"
    systems = json.loads((assets / "system-catalog.json").read_text(encoding="utf-8"))["systems"]
    selections = json.loads((Path(__file__).with_name("steamgriddb-system-selections.json")).read_text(encoding="utf-8"))
    jobs, manifest = [], {}

    def source(spec):
        system_id = spec["id"]
        selected = selections.get(system_id)
        upstream_id = "astrocade" if system_id == "astrocde" else system_id
        url = selected["url"] if selected else ICONIC + upstream_id + ".webp"
        path = args.cache / ((str(selected["id"]) + Path(url).suffix) if selected else "iconic-" + system_id + ".webp")
        if not path.exists():
            request = urllib.request.Request(url, headers={"User-Agent": "Orbit/0.1"})
            with urllib.request.urlopen(request, timeout=40) as response:
                data = response.read(32 * 1024 * 1024 + 1)
                if len(data) > 32 * 1024 * 1024:
                    raise ValueError("Artwork too large: " + system_id)
                with Image.open(io.BytesIO(data)) as image:
                    image.verify()
                path.write_bytes(data)
        return spec, path, selected, url

    def png_data(path, trim=False):
        with Image.open(path) as opened:
            image = opened.convert("RGBA")
            if trim:
                box = image.getchannel("A").getbbox()
                if box:
                    image = image.crop(box)
            output = io.BytesIO()
            image.save(output, format="PNG")
            return "data:image/png;base64," + base64.b64encode(output.getvalue()).decode(), image.size

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        sources = list(pool.map(source, systems))
    for spec, original, selected, url in sources:
        system_id = spec["id"]
        if selected:
            manifest[system_id] = {
                "provider": "SteamGridDB",
                "source": selected["source"],
                "url": url,
                "author": selected["author"],
                "heroId": selected["id"],
                "gameId": selected["gameId"],
                "layout": "whole hero banner; Orbit adds a blurred fill and the system name",
            }
            continue
        hero, _ = png_data(original)
        logo, (width, height) = png_data(assets / "system-wordmarks" / (system_id + ".png"), trim=True)
        # Keep the whole wide wordmark readable; never stretch a compact emblem.
        if width / height >= 2.5:
            name = f'<image x="690" y="573" width="530" height="100" href="{logo}" preserveAspectRatio="xMidYMid meet" filter="url(#glow)"/>'
        else:
            title = escape(spec["title"])
            size = min(38, 850 / max(len(spec["title"]), 1))
            name = f'<text x="955" y="639" fill="white" font-family="Segoe UI" font-weight="700" font-size="{size}" text-anchor="middle" filter="url(#glow)">{title}</text>'
        photo = ""
        if not selected:
            console, _ = png_data(assets / "system-artwork" / (system_id + ".webp"), trim=True)
            photo = f'<image x="690" y="145" width="530" height="380" href="{console}" preserveAspectRatio="xMidYMid meet"/>'
        svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
<defs><linearGradient id="shade" x1="0" y1="0" x2="0" y2="1"><stop offset="0.6" stop-color="black" stop-opacity="0"/><stop offset="1" stop-color="black" stop-opacity="0.55"/></linearGradient>
<filter id="glow" x="-20%" y="-40%" width="140%" height="180%"><feDropShadow dx="0" dy="0" stdDeviation="2.5" flood-color="white" flood-opacity="0.6"/><feDropShadow dx="0" dy="2" stdDeviation="5" flood-color="black" flood-opacity="0.8"/></filter></defs>
<image width="1280" height="720" href="{hero}" preserveAspectRatio="xMidYMid slice"/>{photo}
<rect width="1280" height="720" fill="url(#shade)"/>{name}</svg>'''
        svg_path = args.cache / (system_id + ".svg")
        svg_path.write_text(svg, encoding="utf-8")
        png_path = args.cache / (system_id + "-prepared.png")
        jobs.append({"svg": str(svg_path), "png": str(png_path)})
        manifest[system_id] = {
            "provider": "SteamGridDB" if selected else "Iconic",
            "source": selected["source"] if selected else f"https://github.com/Siddy212/iconic-es-de/tree/{REVISION}",
            "url": url,
            "author": selected["author"] if selected else "Siddy212 and credited contributors",
            "heroId": selected["id"] if selected else None,
            "gameId": selected["gameId"] if selected else None,
            "layout": "16:9 cover, safe name placement" + (", system photograph" if not selected else ""),
        }
    jobs_path = args.cache / "render-jobs.json"
    jobs_path.write_text(json.dumps(jobs), encoding="utf-8")
    script = '''const fs=require('fs'); const {Resvg}=require(process.argv[1]);
const jobs=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
for(const j of jobs){const svg=fs.readFileSync(j.svg,'utf8');
fs.writeFileSync(j.png,new Resvg(svg,{font:{loadSystemFonts:true}}).render().asPng());}'''
    subprocess.run(["node", "-e", script, str(args.resvg_module), str(jobs_path)], check=True)
    for spec, original, selected, url in sources:
        system_id = spec["id"]
        target = assets / "system-backgrounds" / (system_id + ".webp")
        if selected:
            with Image.open(original) as image:
                image = image.convert("RGB")
                if image.width > 1920:
                    image = image.resize((1920, round(image.height * 1920 / image.width)), Image.LANCZOS)
                image.save(target, quality=90, method=6)
            continue
        with Image.open(args.cache / (system_id + "-prepared.png")) as image:
            image.convert("RGB").save(target, quality=88, method=6)
    # Horizontal span (fractions of width) holding each hero's logo or main subject; Orbit crops around it.
    focus = json.loads(Path(__file__).with_name("steamgriddb-hero-focus.json").read_text(encoding="utf-8"))
    (assets / "system-backgrounds/focus.json").write_text(json.dumps(dict(sorted(focus.items())), separators=(",", ":")) + "\n", encoding="utf-8")
    (assets / "system-backgrounds/manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    selected_count = sum(v["provider"] == "SteamGridDB" for v in manifest.values())
    print(f"Prepared {len(systems)} backgrounds: {selected_count} SteamGridDB, {len(systems)-selected_count} dedicated fallbacks.")


if __name__ == "__main__":
    main()
