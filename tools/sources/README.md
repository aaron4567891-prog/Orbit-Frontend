# ES-DE source snapshot

Retrieved 2026-10-19 from the upstream master branch:

- https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_systems.xml
- https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_find_rules.xml
- https://gitlab.com/es-de/emulationstation-de/-/raw/master/LICENSE

The source files are distributed under the included MIT license. Reproduce generated JSON files and the catalogue with `python tools/generate_systems.py` from the repository root. The generator preserves unconverted commands and reasons; it does not fabricate Android integrations for placeholder systems.

Then run `python tools/apply_daijisho.py` to overlay unchanged official Daijishō definitions. The pinned snapshot and MIT license are in daijisho/. `download_daijisho.py` refreshes that source snapshot only when an explicit update is wanted. Matching regional hardware aliases can share the same official file; the filename supplies Orbit's system ID without modifying upstream JSON. Player launch arguments are never reformatted by this overlay. Existing profile files are backed up to system-profile-backup during the one-time upgrade.

Finally run `python tools/apply_cocoon.py` to prefer Cocoon's published definitions. The pinned snapshot is in cocoon/ at commit 17ba7aa5ef1b490c870292a0ee2baa6cd4bb81ff. Nine index entries point to unpublished files and are skipped. The overlay covers 119 catalogue systems; remaining systems retain their previous definition. `clean_player_names.py` removes only system prefixes from player name fields, as requested. IDs, flags, descriptions and launch arguments are otherwise retained exactly. Original source files remain unmodified in tools/sources. Cocoon's platform configuration credits Daijishō as its base; the upstream MIT notice remains included in the APK.

Arcade display names: app/src/main/assets/arcade-names.json contains 42,777 identifier/title pairs extracted from ES-DE resources/MAME/mamenames.xml (MAME 0.289). Retrieval URL and source SHA-256 are recorded in arcade-names-source.json. ES-DE's included MIT notice applies. Only display titles change; ROM filenames and launch arguments are preserved.
