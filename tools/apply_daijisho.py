"""Overlay exact official Daijishō files on matching catalogue systems."""
import json
from pathlib import Path
import shutil
from clean_player_names import clean_names

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/main/assets"
sources = root / "tools/sources/daijisho"
index = json.loads((sources / "index.json").read_text(encoding="utf-8"))["platformList"]
by_id = {entry["platformShortname"]: entry for entry in index}
aliases = {
    "amstradcpc": "cpc", "apple2": "appleii", "atarijaguar": "jaguar", "atarijaguarcd": "jaguarcd",
    "atarilynx": "lynx", "cdimono1": "cdi", "colecovision": "coleco", "gameandwatch": "gw",
    "mastersystem": "master", "mark3": "master", "sg-1000": "sg1000", "n3ds": "3ds",
    "pcengine": "tg16", "pcenginecd": "tgcd", "tg-cd": "tgcd", "psvita": "vita",
    "wonderswan": "ws", "wonderswancolor": "wsc", "megadrive": "genesis", "megadrivejp": "genesis",
    "megacd": "segacd", "megacdjp": "segacd", "saturnjp": "saturn", "famicom": "nes",
    "sfc": "snes", "snesna": "snes", "sega32xjp": "sega32x", "sega32xna": "sega32x",
    "msx1": "msx", "msx2": "msx", "msxturbor": "msx", "amiga1200": "amiga", "amiga600": "amiga",
    "neogeocdjp": "neogeocd", "naomi2": "naomi", "naomigd": "naomi",
}
catalogue = json.loads((assets / "system-catalog.json").read_text(encoding="utf-8"))
matched = 0
players = 0
for system in catalogue["systems"]:
    key = system["id"] if system["id"] in by_id else aliases.get(system["id"])
    entry = by_id.get(key)
    if entry:
        filename = entry["filename"]
        shutil.copyfile(sources / filename, assets / "systems" / (system["id"] + ".json"))
        data = json.loads((sources / filename).read_text(encoding="utf-8"))
        system["players"] = len(data["playerList"])
        system["playerSource"] = "daijisho"
        system["sourceFile"] = filename
        matched += 1
        players += len(data["playerList"])
    else:
        system["playerSource"] = "esde"
catalogue["daijishoSnapshot"] = json.loads((sources / "snapshot.json").read_text(encoding="utf-8"))
(assets / "system-catalog.json").write_text(json.dumps(catalogue, indent=2) + "\n", encoding="utf-8")
shutil.copyfile(sources / "LICENSE.MD", assets / "Daijisho-LICENSE.txt")
clean_names(assets / "systems")
print(f"Exact Daijisho files: {matched}/{len(catalogue['systems'])} systems, {players} player entries")
print("No Daijisho definition:", ", ".join(s["id"] for s in catalogue["systems"] if s["playerSource"] != "daijisho"))
