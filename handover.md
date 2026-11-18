Updated 18 November 2026, Australia/Sydney.

## Latest status (takes precedence over the 15 November notes below)

- Pending development was split into feature commits on `main`: wheel artwork/background loading (`3dde436`), companion vector icons (`3fb0b40`), artwork search selection/layout (`f59f5c5`), custom collections (`f835195`), RetroAchievements and compact game settings (`9ec5647`), app uninstall actions (`4a1f738`), Joey64 for N64 (`f976c2e`), and HakuX-Mali for Xbox (`76369e9`). The user requested these commits be pushed to GitHub.
- RetroAchievements is available in main Settings and Game Settings; it is **not** an entry in the system list. Empty libraries return to the existing no-games message.
- Uninstall app is available in app tile menus and through long-press or controller Y in app lists. Android owns the uninstall confirmation. Remove shortcut remains a separate action.
- Joey64 launches `com.joeysretro.joey64/.MainActivity` with ACTION_VIEW and the ROM URI; accepted extensions are n64/v64/z64/zip/7z.
- HakuX-Mali launches the published package `com.rfandango.haku_x.debug` with the full activity name `com.rfandango.haku_x.LauncherActivity`, ACTION_VIEW and the ROM URI. Accepted extensions are iso/xiso. Complete emulator setup and grant access to the Xbox games folder first. The original hakuX entry is preserved.
- Bundled additions are recorded in `system-catalog.json` with player counts and `additionalPlayerIds`; preserve upstream profile revision numbers so the catalogue audit can compare imported fields.
- Latest source verification: `:app:testDebugUnitTest :app:assembleDebug` passed, 140 tests, after adding both emulator profiles and restoring the empty-library return.
- Last Thor install was 18 November at 22:55:15 device time, verified through ADB. It includes uninstall actions and the RetroAchievements system-list removal, but predates the two emulator additions and the empty-library return correction. The newer APK has been built but not installed.
- Thor was paired successfully on 17 November. On 18 November its discovered serial was `adb-1ff9a7f3-ICBUiS._adb-tls-connect._tcp`; always check `adb devices -l` and `adb mdns services` again because wireless ports change. Do not retain pairing codes.
- Earlier review findings about collections, achievement API errors/matching/cache behavior, and artwork retry behavior have not been addressed in this batch. See current source before fixing them.
- GBA DS was subsequently added at the user's request for Game Boy Advance: `io.github.kyannon.gbads/.LauncherActivity`, ACTION_VIEW, ROM URI, clear-task/clear-top flags, and gba/zip/7z support, following its frontend wiki. Allow All files access in GBA DS first. The profile, catalogue, and launch-contract tests passed; this addition has not been packaged or installed on Thor. The 140-test result above predates the additional GBA DS test.

## Historical snapshot (15 November)

This handover summarizes the Orbit development chat from the initial Android frontend through the latest build on Thor. The [current source](app/src/main/java/au/orbit/frontend) and device checks take precedence over early design messages and older README instructions.

## Where to resume

- **Windows checkout:** `C:\Users\aaron\Desktop\Android Frontend`. This is the checkout outside OneDrive. The old chat workspace path may still point into OneDrive.
- **Project:** Orbit, a native Kotlin Android app for AYN Thor and other Android devices. Package `au.orbit.frontend`, version name `0.1`, JDK 17, Android SDK 36.
- **GitHub:** [Orbit-Frontend](https://github.com/aaron4567891-prog/Orbit-Frontend), branch `main`. The remote was verified at `4e5f084`.
- **Last verified Thor install:** 15 November 2026 at 9:59 AM Sydney time. The APK came from the desktop working tree and includes changes beyond the GitHub commits. Thor was offline over ADB when this handover was written.
- **Last build check:** `:app:testDebugUnitTest :app:assembleDebug` passed with 139 tests. The generated APK is `app/build/outputs/apk/debug/app-debug.apk`.
- Build and install only when requested for the current change. The user often queues several UI changes before asking for an install. When asked to push, make separate commits by feature and check for unrelated changes in files such as `MainActivity.kt`.

### Latest commits on GitHub

| Commit | Scope |
| --- | --- |
| [`4e5f084`](https://github.com/aaron4567891-prog/Orbit-Frontend/commit/4e5f0844b4f7283b7b1ff08bd08ea9bdeaa16ae1) | ScreenScraper title matching, media availability, and manual replacement |
| [`f402233`](https://github.com/aaron4567891-prog/Orbit-Frontend/commit/f40223300b13a1fa6f04d02f58a09413fc32d361) | Artwork removal, index consistency, and recovery of saved art |
| [`fc49cb2`](https://github.com/aaron4567891-prog/Orbit-Frontend/commit/fc49cb24cc855ccc148812124d50ca4dc019f929) | M3U playlist handling during ROM scanning |

### Work still in the desktop checkout

- Game wheel artwork queue, selected-row priority, wheel rendering, earlier fan-art loading, background preloading on both displays, and reduced dimming for images and video.
- Custom collections and RetroAchievements, including the library entry, game badges/progress, and main settings account UI.
- Other local changes in the artwork picker and emulator/settings UI. Use `git status` and review mixed hunks before staging. The newer wheel build is on Thor. Its changes are local to this checkout.

## Project decisions from the chat

### Library, controls, and emulators

- Orbit can act as Android Home. The library scans one or more global ROM folders, recognizes system aliases such as `psx` and `gba`, and shows systems that have games. Saved per-system indexes allow a fast reopen.
- Group multi-disc games by `.m3u`. Keep the playlist as the game entry and exclude its individual discs from bulk scraping. PlayStation folder-format work also covered PS3 disc and PSN-style folder layouts.
- System profiles are editable JSON in `systems/` in the selected user data folder. The project uses shared Daijishō/Cocoon-style launch entries with standalone emulators above RetroArch cores. Select a default automatically for first launch and retain explicit per-system and per-game choices. Users can hide entries themselves.
- Start opens the full Orbit Settings screen. Select opens the relevant system or game settings. Keep settings navigation in the same UI and make B return to the previous settings page. Use the tile menu's Top/Bottom choice for launch and settings routing where supported.
- The 3DS-style tile views support continuous sideways scrolling, empty slots, adding or removing apps and games, rearranging tiles, and resizing by pinch. Keep tile sizes consistent and overlay the tile menu without reflowing the grid. X opens and closes fullscreen game video in the 3DS theme.
- Keep Orbit Midnight as the default theme and protect it from deletion. The 3DS theme provides the library-on-bottom layout. Game and system customisation have separate scopes. Themes live under main Settings → Customisation.

### Dual screen, artwork, and motion

- The two displays have independent roles. The companion follows the selected game, system, or menu and can show media on its own background. Keep the app dock and drawer on the bottom screen. Bottom-screen settings open with an edge swipe.
- Custom hero images fill the display and can override theme backgrounds at system or game scope. A game's custom art can be edited separately from the theme. Keep media choices per screen and keep the artwork and video fills behind the dock/status area.
- The game views include horizontal and vertical wheels. The wheel follows the selected game. Media choices include marquees (default), 2D box art, 3D box art, and physical media with individual size settings. Optional disc spin applies to the selected item only. Keep the text fallback sized to its artwork slot.
- The last verified Thor build restores fan art changes **during** scrolling. The user tried holding fan art until the wheel settled, then asked to change it back. Orbit preloads nearby backgrounds, starts a background-only read for an uncached centered game after about 40 ms, and reuses that read when the wheel settles.
- Wheel loads prioritize the centered game and drop obsolete requests for recycled rows. Near-center art is applied first, artwork fades are limited to the selected row, and identical backgrounds avoid redraws. Artwork and video use lighter shading.
- Earlier Thor fixes covered controller focus when returning to Orbit, stopping music with the lid closed, resuming music on wake, and keeping hardware volume changes working at low levels. The user confirmed the volume worked after reopening the lid. Treat this as a regression area if changing media lifecycle or focus.

### Scraping and media

- Media lives at `media/<system>/<media type>/<game filename>.<ext>`. Per-game provider records live under `media-index/<system>/<game key>/`. The active user data folder is the root directly. On Thor it was observed at `/sdcard/Orbit`.
- Media transfer supports a single system or the whole library from ES-DE, NeoStation, and NeoStation Personal. It keeps source filenames and groups files into the matching media type folders. Copy keeps the source file. Move verifies the destination before removing the source file.
- Bulk scraping runs in the background, uses the provider's concurrency allowance or a lower chosen limit, and shows per-game progress tiles. System selections can include multiple systems. An empty selection means all scanned systems.
- `Missing media only` checks enabled categories and existing files. ScreenScraper availability is remembered for seven days after the provider confirms a requested category is unavailable. Games can return to the queue when a category becomes due for rechecking. Legacy removal flags are cleared for media Orbit confirms is saved.
- ScreenScraper title matching ignores ROM region tags such as `(USA, Canada)`, case, and hyphen variations. It still verifies the system and keeps sequel numbers and meaningful title qualifiers. G-Police and Formula One 99 were checked against the live API. Matching a playlist can use the game entry without scraping its discs.
- **Alone in the Dark: The New Nightmare (PlayStation):** Thor's old box art, physical media, and screenshot matched Game Boy Color assets byte for byte even though the record carried the PlayStation ID `19329`. Fresh assets for `19329` were checked and are correct. On the newer APK, **Game Settings → Scraper → Scrape game → ScreenScraper** refreshes that game's enabled media. The replacement files were not rechecked on Thor afterward.
- **Other provider cases:** The exact `Jurassic Park - The Lost World (USA) (Demo).chd` is in ScreenScraper game `19322`'s ROM list, but the demo title search was previously unresolved. King of Fighters ’95 and ’99 had malformed provider responses during an earlier run. Recheck before treating either case as a current failure.
- Custom image search applies selected results inside Orbit. Yahoo was removed. Game Settings → Scraper includes Media and View or change artwork. Artwork removal deletes the selected media where possible, and custom replacements can come from the file picker or artwork search.

### RetroAchievements and collections

- Keep account setup in main Settings. In Game Settings the RetroAchievements tab is for badges, unlocks, and progress. The library also has a RetroAchievements entry.
- Custom collections are accessible from main Customisation and per-game Customisation. There is no standalone Collections tab in main Settings.
- The relevant source and tests currently sit in the local working tree alongside newer wheel work. Preserve them when staging a scraper or artwork-only commit.

## Useful code paths

| Area | Files under `app/src/main/java/au/orbit/frontend/` |
| --- | --- |
| Game wheel, cache, and rendering | `GameWheelView.kt`, `GameLogoRows.kt`, `GameArtworkPanel.kt`, `GameMediaBackground.kt`, `GameMediaSurface.kt` |
| Dual-screen routing | `MainActivity.kt`, `DualScreenCoordinator.kt`, `CompanionPanel.kt` |
| Scan and launch | `Core.kt`, `RomScanner.kt`, `EmulatorSettings.kt` |
| ScreenScraper | `ScraperClient.kt`, `ScreenScraperMatch.kt`, `ScrapedMediaPlatform.kt`, `ScrapePolicy.kt`, `ScraperSettingsPanel.kt`, `GameScraper.kt` |
| User artwork and storage | `GameCustomArtwork.kt`, `GameCustomArtworkPanel.kt`, `MediaLayout.kt`, `UserDataStore.kt` |
| Collections and achievement UI | `CustomCollections.kt`, `RetroAchievements.kt`, `AchievementBadgeGrid.kt`, `MainActivity.kt` |

## Build, install, and verify

From the desktop checkout in PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
```

When Thor is connected over Wireless debugging and the user has requested installation, target its listed serial and update in place:

```powershell
& $adb -s '<Thor serial from adb devices -l>' install -r 'app\build\outputs\apk\debug\app-debug.apk'
& $adb -s '<Thor serial from adb devices -l>' shell dumpsys package au.orbit.frontend |
    Select-String 'versionName=|lastUpdateTime='
```

Local Android SDK configuration and ScreenScraper developer configuration use ignored `local.properties` and `screenscraper-local.properties`. Keep their contents private. If wireless ADB is unavailable, use the address and port shown on Thor's current Wireless debugging screen. Pair again only if the saved pairing fails.

### Next device checks

1. Open the PlayStation horizontal wheel. Hold a direction, reverse, then stop. Check that fan art follows the current game, nearby covers stay readable, and the last selection shows its own art.
2. Repeat with the companion set to video. Confirm it still switches cleanly and the brightness is comfortable at Thor's normal display setting.
3. If the PlayStation Alone in the Dark still shows a cartridge or Game Boy Color cover, run the single-game refresh above and verify the PlayStation disc and cover.
4. When publishing more work, review the local changes alongside `4e5f084` and split commits by feature.
