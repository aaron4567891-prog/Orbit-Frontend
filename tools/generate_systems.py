"""Reproduce the Android system catalogue from the checked-in ES-DE source snapshot."""
import hashlib
import json
from pathlib import Path
import re
import shlex
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
SOURCES = ROOT / "tools/sources"
URL = "https://gitlab.com/es-de/emulationstation-de/-/blob/master/resources/systems/android/es_systems.xml"
rules = {}
for emulator in ET.parse(SOURCES / "es_find_rules_android.xml").getroot():
    rules[emulator.attrib["name"]] = [e.text for r in emulator.findall("rule") if r.attrib.get("type") == "androidpackage" for e in r.findall("entry")]

def convert(command, component):
    package = component.split("/")[0]
    text = re.sub(r"%EMULATOR_[^%]+%", "", command).strip()
    replacements = {
        "%ANDROIDPACKAGE%": package, "%EXTERNALDATA%": "/storage/emulated/0",
        "%INTERNALDATA%": "/data/data", "%ROMSAF%": "{file.uri}",
        "%ROMPROVIDER%": "{file.uri}", "%ROM%": "{file.path}",
        "%ROMRAW%": "{file.path}", "%BASENAME%": "{file.basename}",
        "%ACTIVITY_CLEAR_TASK%": "--activity-clear-task", "%ACTIVITY_CLEAR_TOP%": "--activity-clear-top",
    }
    for key, value in replacements.items():
        text = text.replace(key, value)
    args = ["-n", component]
    for token in shlex.split(text):
        if token in ("--activity-clear-task", "--activity-clear-top"):
            args.append(token); continue
        match = re.fullmatch(r"%(ACTION|DATA|MIMETYPE|EXTRA_[^%]+|EXTRABOOL_[^%]+|EXTRAINTEGER_[^%]+)%=(.*)", token)
        if not match:
            raise ValueError("Requires unsupported ES-DE command features")
        kind, value = match.groups()
        if re.search(r"%[^%]+%", value):
            raise ValueError("Requires injection, directory or provider-specific integration")
        if kind in ("ACTION", "DATA", "MIMETYPE"):
            args.extend([{"ACTION": "-a", "DATA": "-d", "MIMETYPE": "-t"}[kind], value])
        else:
            prefix = "--ez" if kind.startswith("EXTRABOOL_") else "--ei" if kind.startswith("EXTRAINTEGER_") else "--es"
            args.extend([prefix, kind.split("_", 1)[1], value])
    if not any("{file." in arg for arg in args):
        raise ValueError("Command does not pass a game file")
    return " ".join(json.dumps(arg) if any(c.isspace() or c in '\\"' for c in arg) or not arg else arg for arg in args)

catalogue = []
converted = 0
unconverted = 0
for node in ET.parse(SOURCES / "es_systems_android.xml").getroot().findall("system"):
    system = node.findtext("name")
    extensions = sorted({ext.lstrip(".").lower() for ext in node.findtext("extension", "").split()})
    pattern = r"^(?!\.)(.*)\.(?:" + "|".join(re.escape(e) for e in extensions) + ")$"
    players, skipped = [], []
    for command in node.findall("command"):
        original = command.text or ""
        label = command.attrib.get("label", "Unavailable")
        emulator = re.search(r"%EMULATOR_([^%]+)%", original)
        if not emulator or not rules.get(emulator.group(1)):
            skipped.append({"label": label, "reason": "ES-DE placeholder or no Android package rule", "command": original})
            continue
        for component in rules[emulator.group(1)]:
            try:
                arguments = convert(original, component)
                identity = hashlib.sha256((original + component).encode()).hexdigest()[:12]
                package = component.split('/')[0]
                clean_label = label.replace(" (Standalone)", "")
                if package.startswith("com.retroarch"):
                    arch = "RetroArch (64 bits)" if package == "com.retroarch.aarch64" else "RetroArch (32 bits)" if package == "com.retroarch.ra32" else "RetroArch"
                    clean_label = f"{arch} - {clean_label}"
                elif len(rules[emulator.group(1)]) > 1:
                    variant = rules[emulator.group(1)].index(component) + 1
                    if variant > 1:
                        clean_label += f" (variant {variant})"
                players.append({"name": clean_label, "uniqueId": f"esde.{system}.{identity}",
                    "description": "Supported extensions: " + ", ".join(extensions) + ".",
                    "acceptedFilenameRegex": pattern, "amStartArguments": arguments,
                    "killPackageProcesses": False, "killPackageProcessesWarning": True, "extra": ""})
            except ValueError as error:
                skipped.append({"label": label, "reason": str(error), "command": original})
    # Preserve the established default ID used by existing installations.
    if system == "psx":
        for player in players:
            if "com.github.stenzek.duckstation/.EmulationActivity" in player["amStartArguments"]:
                player["uniqueId"] = "duckstation"
                break
    metadata = {"source": URL, "retrieved": "2026-10-19", "extensions": extensions,
            "romFolder": system, "theme": node.findtext("theme"), "unconvertedCommands": skipped,
            "launchStatus": "requires-emulator-setup" if players else "no-compatible-launch-profile"}
    data = {"databaseVersion": 14, "revisionNumber": 2,
        "platform": {"name": node.findtext("fullname"), "uniqueId": system, "shortname": system,
            "description": None, "acceptedFilenameRegex": pattern, "scraperSourceList": [], "boxArtAspectRatioId": 0,
            "useCustomBoxArtAspectRatio": False, "customBoxArtAspectRatio": None,
            "screenAspectRatioId": 1, "useCustomScreenAspectRatio": False, "customScreenAspectRatio": None,
            "retroAchievementsConsoleIdList": [], "extra": ""}, "playerList": players}
    (ASSETS / "systems" / f"{system}.json").write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    catalogue.append({"id": system, "title": node.findtext("fullname"), "extensions": extensions,
        "players": len(players), "unconvertedCommands": len(skipped), "metadata": metadata})
    converted += len(players); unconverted += len(skipped)
(ASSETS / "system-catalog.json").write_text(json.dumps({"source": URL, "retrieved": "2026-10-19", "systems": catalogue}, indent=2) + "\n", encoding="utf-8")
(ASSETS / "systems" / "duckstation.json").unlink(missing_ok=True)
print(f"Generated {len(catalogue)} systems, {converted} Android player profiles, {unconverted} unconverted entries")
