# Orbit — Android frontend 0.1

**Orbit** is a native Android frontend, written in Kotlin, built for the AYN Thor and other dual-screen handhelds. It turns your ROM folders into a controller-friendly library: systems and games on one screen, with hero art, box art and video previews on the other.

- **Dual-screen by design:** keep the library on the top or bottom screen, swap screens at any time, and choose which screen each system or game launches on.
- **195 systems:** a catalogue built from the ES-DE Android configuration, with Cocoon-compatible launch profiles for 119 of them. You supply your own ROMs and emulators.
- **Themes:** built-in themes, including a 3DS-style tile layout, plus custom themes with your own background, hero and per-page artwork, and ES-DE theme import.
- **Page transitions:** Fade, Slide, Zoom, Scale, Flip, Dip or Instant, with direction and speed settings, applied to all themes or per theme. Optional tile cascade, gliding selection and artwork crossfades.
- **Artwork:** scrape from providers like ScreenScraper, search for artwork in-app, or import media from NeoStation and ES-DE.
- **Your data stays yours:** settings, media and profiles live in a user data folder you choose.

Orbit doesn't include any games or emulators. Console artwork is credited in `app/src/main/assets/system-artwork/ARTWORK-CREDITS.md`.

## System catalogue

The system catalogue contains all 195 entries in the ES-DE Android configuration snapshot retrieved on 2026-10-19. Each has a JSON file under the user data `systems` folder. For 119 matching systems, files use the published Cocoon snapshot with its launch arguments unchanged. System prefixes are removed from player name fields only. Other systems retain their existing shared-format definitions because no published Cocoon equivalent was found. Source provenance and unsupported-command notes are kept separately in system-catalog.json. These profiles require emulator/core setup and are not all tested on hardware. All systems appear in one continuous horizontal carousel with touch and D-pad navigation. Use Systems to search the catalogue or jump directly to a console.

## Media transfer

Settings → Media → Media transfer imports artwork from NeoStation, NeoStation Personal, or ES-DE. Select the frontend's data folder, its `media`/`downloaded_media` folder, or one system's media folder. The personal build's short system names (including `2600`, `32x`, `dc`, `gg`, and `vita`) are mapped to Orbit's system IDs.

Preview matches media against games already scanned in Orbit. Filename case and punctuation are normalized; ambiguous names, unsupported files and media for unscanned games are skipped. Copy keeps the originals. Move streams each file into Orbit, checks its SHA-256, saves and verifies a new immutable media record, then rechecks the source before removing that source file. Existing Orbit artwork and source folders are preserved. Cancellation keeps completed imports and never removes an unverified source. Imported media follows `media/<system>/<type>/<game filename>.<format>` and is available to the game list, themes and companion display. New records are stored separately under `media-index`; previous game-folder records and artwork remain readable. A different existing file with the same destination name is kept and reported as a conflict. Large videos are streamed through temporary storage rather than loaded into memory.

## Build and install

Requires JDK 17 and Android SDK 36. Open this folder in Android Studio, let Gradle sync, then run `gradlew.bat assembleDebug` on Windows. Set `sdk.dir` in your local, ignored `local.properties` if necessary.

Install `app/build/outputs/apk/debug/app-debug.apk` on the handheld. This is a development APK signed with a debug key.

## First run

1. Select a system, then choose its ROM folder using Android's folder picker. Folder permission persists across restarts. Each system has its own folder.
2. Browse games. Nested directories are scanned in a background thread. Supported file extensions appear in the empty library view. ZIP archives and multi-disc playlist grouping are not supported yet.
3. PlayStation uses the bundled DuckStation JSON profile when DuckStation is installed. Use Emulator to review/select the saved system assignment. Other systems currently use Android ACTION_VIEW with a read-granted content URI. Only emulators that accept that interface will work; being listed as a handler does not establish game-format compatibility. See `app/src/main/assets/systems/README.md` for the versioned profile format and URI limitations.
4. The frontend automatically detects available displays and maintains its second-screen status panel in its own task. Display changes and returning Home restore a missing panel. There is no Close button; Back/B leaves the panel open. Existing panels do not override an app already using the second screen. Use Displays to select a gameplay display by its Android ID. IDs deliberately do not assume which physical screen is top or bottom. Select the gameplay display accordingly. Alternatively launch gameplay on the display opposite the library to keep the library visible.

## Controls

Touch or D-pad to navigate, A to select, B to return to systems. Android's normal focus navigation is used. The analog stick is not mapped yet.
In Systems view, L/R shoulder buttons select the previous/next system; the right trigger (R2) opens Android apps.
Start opens the full Settings screen immediately. The Start popup has been removed.
Scraping is available under Settings → Media → Scraping. Select retains the game/system menu.
B/Back returns through the pages you opened, preserving the game-list selection when returning from Settings.
Within Settings, B first returns from an option to its sidebar, then returns to the previous page.
ROM folders & scanner is under General; the separate Game library category has been removed.
Settings → General contains default emulator selection, system JSON import and profile reloading.
Android's navigation bar stays hidden in Orbit on both displays; there is no visibility toggle.

## Multiple ROM folders and bulk scanner

Open the global ROM library from the main header and choose Add ROM folder. Select a collection root once, without choosing a console. One or more roots can be added on internal storage, SD cards or supported document providers. Individual system assignments remain under Advanced: individual system folders. Existing single-folder settings are read automatically; adding folders stores a list in settings.json. Removing an individual system folder only removes its library reference. Overlapping paths deduplicate the same document by provider/document ID; different files with the same title remain separate.

Discovery recursively finds system subfolders (`psx`, `snes`, `gba`, etc.), including layouts such as ROMs/Nintendo/GBA. Folder matching ignores case, spaces and punctuation and accepts catalogue IDs, full names and common aliases (`n3ds`, `ps1`, `ds`, `sfc`, Game Boy Advance). Once matched, each system folder is scanned recursively. Files outside a recognized system folder are reported as unassigned, rather than guessed using overlapping extensions such as ZIP, BIN or ISO. Additional roots support collections across different storage volumes. Removing a global root stops future discovery but preserves existing system path assignments.

Scan all games discovers saved global roots and refreshes all assigned systems. Progress, cancellation and results are shown; inaccessible paths are reported while other folders continue. Completed per-system indexes are stored in `library/<system>.json` in the selected user data folder. Browsing uses saved indexes while folder lists match; Rescan refreshes changed files. Cancelled in-progress scans do not overwrite that system's previous index; previously completed systems remain saved. No ROMs are moved or modified. Index counts represent matching files, not grouped multi-disc games, and archive contents are not inspected.

ROM folder selection retains the read/write access actually offered by Android. Emulator launches share the selected ROM collection tree temporarily as well as the ROM URI so companion saves, patches and settings can be accessed. Write access is passed only when Orbit holds it. Older read-only-persisted selections are upgraded only if Android still records a write grant offered by the original picker; otherwise reselect the ROM folder or configure a separate writable save folder in the emulator. JSON launch arguments are unchanged.

## Android apps

The Android apps button opens a native app drawer with installed launcher activities, icons, alphabetical ordering and search. It includes native games, emulators, system apps and utilities. Apps without a launcher activity do not appear. The list refreshes when returning to it. The app-display preference is saved separately from the emulator display preference; Android and the target app determine whether launch requests on a secondary display are supported. A removed display falls back to the current screen. Already-running app windows may remain on their existing display.

Use D-pad and A to open an app, B to return to the library, and Home to return to the frontend. Android Settings is accessible from the drawer. No broad package access or storage permission is needed for app discovery.

## User data folder

Use User data → Change user data folder. Android's folder picker grants persistent read/write access. Orbit uses the chosen folder directly and copies and verifies its frontend data before switching. No extra Orbit subfolder is created. Existing files are retained in the old folder. No unrelated destination files are overwritten. Folder operations run off the UI thread.

The data folder holds `settings.json` (ROM folder references, emulator assignments, display preferences), a settings backup after edits, and editable `systems/*.json`. Legacy preferences are imported on the first run of this version. Future files below this root are copied as well. ROMs, external emulator BIOS/configuration and game saves are not relocated. Android URI grants and a small settings recovery copy remain in private app storage. If access is lost, cached settings remain readable and the frontend displays an access error; choosing a new folder recovers the cached settings and bundled profiles. Inaccessible custom files cannot be copied. Settings are loaded on app startup; use User data → System profiles → Reload after editing JSON. Only the systems folder is used for profile files; the old emulators folder has been retired.

Android may restrict storage roots, Downloads itself, and Android/data or Android/obb. Choose an allowed subfolder such as Documents or an SD-card folder. The selected folder URI is shown in User data.

## Architecture

- `LibraryRepository`: persisted Storage Access Framework folder grants and recursive scanning.
- `DualScreenManager`: display enumeration and launch options.
- `EmulatorLaunchManager`: external app intent boundary.
- `GameSessionManager`: last launch request, shared between the library and companion within this process.
- `MainActivity` / `CompanionActivity`: independent display activities.

The session is explicitly a last-launch record, not live emulator monitoring. This APK cannot pause external emulators, observe their game lifecycle, force controller focus, move their existing windows or split DS/3DS rendering across displays. Android firmware and each emulator determine multi-display support and whether both activities remain running. Display launch failures are shown to the user; no automatic relaunch risks losing progress.

## Device acceptance checklist

- Install and cold launch; navigate all system cards and actions using the Thor controller.
- Pick a real ROM folder, including a subfolder; verify game names and count. Restart Orbit and rescan to verify persisted permission.
- Revoke folder access and confirm choosing the folder again restores scanning.
- Launch a legal test ROM in a known ACTION_VIEW-compatible emulator; verify it can read the URI.
- Verify both display IDs, launch gameplay on each, and check library/companion visibility and controller focus on actual Thor firmware.
- Disconnect a secondary display and check an invalid launch produces an error rather than a crash.

Physical Thor testing is required before claiming dual-screen compatibility.

## Native Orbit themes

Settings → Customisation → Themes opens the full theme gallery with Orbit, Orbit Midnight and Orbit Mono,
including system and game-list previews. B returns to Customisation after closing theme details.
Midnight is the default: dark blue, large original console illustrations, a wider media area
and matching cached artwork on the companion display. All 195 catalogue systems have standalone
console images. These are credited separately under `app/src/main/assets/system-artwork`, including
their CC-BY-NC-SA 2.0 source notices, and in the theme details' artwork credits.
Existing video and scraped-media settings apply.

**Create theme** saves a named copy of a chosen layout. Theme JSON lives in
`themes/native/<theme ID>.json` inside the selected user data folder. Edit its colors,
`systemLayout` (`artwork` or `cards`) and `gameListFraction` (0.3–0.7), then choose
**Reload** and apply it. Built-in definitions are in `app/src/main/assets/themes/native`.
The format is identified by `"format": "orbit-native"` and `"version": 1`.
Open a custom theme's details and choose **Delete theme** to remove it. Built-in Orbit,
Midnight and Mono are protected. Deleting an active custom theme falls back to Midnight.

Settings → **Customisation** offers background and hero images for the active theme.
**Artwork theme** is a read-only display of the active theme; change themes in the **Themes** gallery.
Each image can
target the physical top screen, bottom screen, or both, across the whole theme.
Backgrounds fill the frontend, including behind status indicators and the companion dock;
heroes replace the large system artwork while browsing Systems. Use **Remove image** to
restore the default for the chosen screens. PNG, JPEG and WebP files up to 32 MB are copied
into `themes/native/artwork`; theme JSON stores their screen assignments. Rendering uses
decoded cached images and needs no continuing access to the original picture folder.
**Show system artwork** on the Customisation page controls the foreground console image independently
for the top, bottom, both, or neither screen. Custom hero and background images remain visible.

Select a system and open **System Settings → Customisation** for per-system artwork.
Choose **Top screen** or **Bottom screen** to open that screen's controls inside the same
popup. Back returns to the two screen choices. **Hero image** and **System image** previews,
selection, reset and visibility controls remain visible in a scrolling list, without dropdowns.
Selecting a preview box opens the
image picker directly for that screen. The hero overrides the global hero for this system;
the system image replaces its foreground console artwork and can appear over the hero.
**Show system image** independently shows or hides that foreground image on each screen,
without removing the hero. Reset actions restore the global hero, default console image,
or theme visibility. These overrides are saved with the active theme and copied with custom
themes. System Settings has Customisation in place of Display; Game Settings retains its
launch-display controls.

ES-DE import/download controls and the bundled Colourful assets are removed from the APK.
Previously imported user files are preserved but are no longer offered as active themes.
Legacy parser fixtures and their original attribution are archived under
`tools/legacy-theme-assets`; they are used only by regression tests.

## Scraper providers

Settings → Media → Scraping offers ScreenScraper, SteamGridDB, TheGamesDB and NeoAssets.
Media also contains game-list logos, video previews, companion artwork, artwork packs,
media transfer and the media types to download. Customisation and Dual Screen retain their other controls.
The companion screen always follows selected apps and menus with their icons; this has no toggle.
Select a scanned system and game, search, and confirm the correct provider match before downloading.
ScreenScraper requires developer credentials and optionally a user account; SteamGridDB and TheGamesDB require personal API keys.
Local builds automatically read `DEV_ID` and `DEV_PASSWORD` from the Git-ignored `screenscraper-local.properties` file at the project root. If supplied, the APK includes the developer connection and Settings only asks for a personal ScreenScraper account. Keep that local file when rebuilding on this computer.
Credentials stay in private device preferences and are excluded from downloaded metadata.
The shared media selector offers only physical media, marquees, videos, fan art, screenshots, box art and manuals. Unsupported types are excluded from downloads. ScreenScraper uses support-2D, wheel-hd/wheel, video-normalized/video, fanart, ss-hd/ss, box-2D and manuel respectively. One available asset per enabled category is downloaded; HD marquees and normalized videos are preferred with standard variants as fallbacks.
NeoAssets supports game metadata/media and system pack downloads.

Downloads are created on demand inside the selected user data location:

```
media/<system ID>/<media type>/<game filename>.<format>
media-index/<system ID>/<SHA-256 ROM URI>/<provider>.json
media/system-packs/<pack folder>/<original file name>
media/system-packs/<pack folder>/pack.json
```

The JSON records the confirmed match, metadata and downloaded files. Failed downloads are reported individually.
The game list displays the selected game's marquee, box art and screenshot/fan art in a side panel. Images are read from the chosen user data folder and decoded on a worker with a bounded thumbnail cache. Video playback, manual viewing, carousel artwork and unattended bulk scraping are not implemented yet.
Provider endpoints: https://www.screenscraper.fr/webapi2.php, https://www.steamgriddb.com/api/v2, https://api.thegamesdb.net/, https://api.neoassets.dev/api/v1/metadata/games and https://api.neoassets.dev/api/v1/packs.

## Additional standalone players

The bundled system profiles include PokeDaisy for Game Boy, Game Boy Color, Game Boy Advance
and Nintendo DS, Xenra for Xbox and Xbox 360 (the original app, package
`Ali.Xanite.green`), and Gopher64 for Nintendo 64. URI launches use the exported entry
activities rather than the emulation activities. Gopher64's official VIEW launch support
requires 1.1.42 or newer. Existing emulator defaults are preserved.

Launch contracts: [PokeDaisy frontend integration](https://github.com/lidor30/pokedaisy/blob/main/docs/DEVELOPMENT.md#launch-from-a-frontend-cocoon-iisu-es-de-),
[Xenra launcher](https://github.com/Yebot32/xenra/blob/main/app/main/java/Ali/Xanite/LauncherActivity.kt),
[Gopher64 Android manifest](https://github.com/gopher64/gopher64/blob/main/android-project/app/src/main/AndroidManifest.xml).

Menu pages explicitly publish their page and selected-option state for the companion display.
The settings icon stays consistent throughout a settings popup; temporary Android focus is
not interpreted as a new page or menu selection.

Original Xbox launches into Xenra 0.4.5 use its exported `ShortcutLaunchActivity`, passing a
file URI and the game's relative library path. This follows its library/shortcut core
selection instead of the generic URI frontend fallback, which can choose XEMU when the
game identity is missing. Only the original Xbox entry uses this route; the Xbox 360
entry retains its VIEW launcher. The file URI uses Xenra's existing All files access.
