"""Change player name fields only; preserve every launch argument and ID."""
import json
from pathlib import Path
import re

def clean_names(directory):
    count = 0
    for path in directory.glob("*.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        platform = data.get("platform", {})
        prefixes = {str(platform.get(key, "")).strip().casefold() for key in ("shortname", "uniqueId", "name")}
        changed = False
        for player in data.get("playerList", []):
            name = player.get("name", "")
            if "MelonDualDS" in name:
                name = name.replace("MelonDualDS", "WatermelonDS")
                player["name"] = name
                changed = True
                count += 1
            parts = re.split(r"\s+-\s+", name.strip(), maxsplit=1)
            if len(parts) == 2 and parts[0].casefold() in prefixes:
                player["name"] = parts[1].strip()
                count += 1
                changed = True
        if changed:
            path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return count

if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    print("Player names cleaned:", clean_names(root / "app/src/main/assets/systems"))
