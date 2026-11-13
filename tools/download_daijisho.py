"""Download a consistent snapshot of official Daijishō platform definitions."""
import concurrent.futures
import json
from pathlib import Path
import urllib.request

destination = Path(__file__).resolve().parent / "sources/daijisho"
destination.mkdir(parents=True, exist_ok=True)
def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Orbit-frontend-profile-import"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()
commit = json.loads(fetch("https://api.github.com/repos/TapiocaFox/Daijishou/commits?path=platforms&per_page=1"))[0]["sha"]
base = f"https://raw.githubusercontent.com/TapiocaFox/Daijishou/{commit}/"
index_bytes = fetch(base + "platforms/index.json")
(destination / "index.json").write_bytes(index_bytes)
(destination / "LICENSE.MD").write_bytes(fetch(base + "LICENSE.MD"))
index = json.loads(index_bytes)
def download(entry):
    filename = entry["filename"]
    (destination / filename).write_bytes(fetch(base + "platforms/" + urllib.parse.quote(filename)))
    return filename
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
    files = list(executor.map(download, index["platformList"]))
(destination / "snapshot.json").write_text(json.dumps({"repository": "https://github.com/TapiocaFox/Daijishou", "commit": commit, "retrieved": "2026-10-19", "files": files}, indent=2) + "\n", encoding="utf-8")
print(f"Downloaded {len(files)} official platform files at {commit}")
