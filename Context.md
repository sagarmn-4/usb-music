# USB Music: context for a new session

Read this first. It is the full picture of the project; the README is the user-facing summary.

## What this is

A plain-Java Android TV app (package `com.sagar.usbmusic`, targetSdk 34, no Kotlin, no AndroidX UI) that plays the music on a USB drive plugged into a Sony Bravia (Android 12). A phone on the same Wi-Fi controls it from a web page the TV serves on port 8080. Nothing is installed on the phone. Sound goes out through the TV, which sends it to a Yamaha RX-V479 receiver over HDMI-CEC.

The owner is Sagar (Windows PC, Git Bash and PowerShell). The repo is private: `github.com/sagarmn-4/usb-music`, branch `main`, local clone `D:\Work\AI\USB-Music`. It was split out of the unrelated repo `sagarmn-4/TV-AdDuck` on 2026-11-08 (history kept). Nothing USB Music is left in that old repo; do not work there.

## Environment facts

- TV: 192.168.1.6, adb over Wi-Fi on port 5555, app page at `http://192.168.1.6:8080`. Receiver control API is at 192.168.1.2 (not used by the app; see "Declined").
- adb is at `D:\Work\AI\AdDuck\tools\platform-tools\adb.exe` (`push-page.ps1` finds it; override with `$env:USBMUSIC_ADB`, else falls back to `adb` on PATH).
- The USB drive shows up as `F:\Music` on this PC when plugged in here (used by `scripts/`).
- Work files live outside the repo in `D:\Work\AI\usb-music-install`: `tags` and `tags-run` (tag job caches, reports, `done.json`, batch folders), `names` (`plan.csv`, `rename-log.json` = undo log for renames), `tags-backup`, `keep\covers-created.txt`, and downloaded builds `v1`..`v21`.
- Python 3.14 with mutagen and Pillow for the scripts. Run Python that reads downloaded data with `-I`.

## Layout

```
app/src/main/java/com/sagar/usbmusic/
  MusicService.java    foreground service: MediaPlayer, queue, state, wake lock while playing, volume, rescans (~800 lines)
  WebServer.java       tiny HTTP server (port 8080, next free up to 8089): /api/* commands, /art?id= cover images, serves the page
  LibraryScanner.java  scans /storage/* volumes for audio; falls back to MediaStore; skips media-db rows whose file is gone
  MainActivity.java    hosts the TV screen; permission, service start, media keys, Menu key, ambient wake
  TvBrowse.java        TV screen: left rail (Search, Folders with the disc icon, Songs, Rescan USB, small QR; Albums and Artists were removed from the rail and are reached from Search), Folders page with a Folder view / List view switch (choice kept in SharedPreferences "usbmusic_ui"), folder/album/artist pages, search page, popup menu, Go to folder button on the player bar (folder of the playing song)
  TvPlayer.java        player bar along the bottom and the now-playing page (full-page player: cover or record, tint, controls that hide after 15 s, Up next queue)
  TvUi.java, TvArt.java, LibraryIndex.java   shared TV look (sizes in 1920x1080 units), cover loading, folder tree/albums/artists (same grouping as index.html)
  AlbumArt.java        embedded cover / folder.jpg lookup for /art
  CoverFetcher.java    optional online cover fetch (writes folder.jpg). Auto mode is OFF by default; leave it off
  WavTags.java         reads ID3 from the `id3 ` chunk of WAV files
  Track.java, BootReceiver.java (start on boot)
app/src/main/assets/index.html   the whole phone page (~1,240 lines, one file, no framework)
push-page.ps1                    send an edited index.html to the TV without reinstalling (run-as, debug build only); -Reset undoes it
scripts/                         one-off PC tools for the drive's tags, covers and names (see below)
.github/workflows/usb-music-apk.yml   CI build; manual dispatch with `release_tag` also publishes a GitHub Release
```

Phone page: YouTube Music styling (Roboto, tabs Folders | Albums | Songs | Artists with Folders first, numbered track lists with durations, global search that works from any tab, mini player to full now-playing screen with a spinning record and a moving equalizer on the label, cover-colour tint, reorderable Up next queue with Play next / Add to queue, "Go to folder", lock-screen controls, TV volume as a small bar with − and + and the number). No album art is shown for folders that contain folders.

TV screen (built 2026-11-08, same design language as the phone page, driven by the same `MusicService`, so phone and TV stay in sync; no second player): plain Java views, no AndroidX UI.
- Design reference: `design/tv-preview.html`, a clickable browser mockup (keyboard = remote). It follows the YouTube Music web layout: left rail, centred folder/album/artist page with shuffle / big Play / add-to-queue, full-width bottom player bar with the progress line on top, now-playing page with Up next on the right.
- Remote keys: D-pad moves, OK selects, hold OK or the Menu key opens Play next / Add to queue / Go to folder (on a queue row: Play now / Remove), Back goes up one level (menu, now playing, folder, tab). Media keys: Play/Pause (Play and Pause act only on their own), Next, Previous, Fast-forward/Rewind seek 10 s. Left/Right on the progress line seek 10 s. Down from the last row reaches the bar, Up from the progress line returns to the last list item (handled in `TvBrowse.onNavKey`; a ListView keeps those keys otherwise).
- Search is a rail item: an `EditText` with the TV's own keyboard (it has voice), live results grouped Songs / Folders / Albums / Artists. Resting on a rail item for about 0.3 s opens that tab.
- Full-page player: starting playback from the TV (Play, a song, a search result) opens it at once: one centred column (cover or spinning record, title, artist) with no rail or bar. The controls (progress, previous / Play-Pause / next, Go to folder, Up next) show for 15 s, then fade; the first D-pad or OK press brings them back and does nothing else (`TvPlayer.onUserKey`). Back closes Up next, then the page. There is no shuffle or repeat button in the player on the TV or on the phone page; the Shuffle buttons on lists start a shuffled play and Play turns shuffle off.
- No volume buttons on the TV screen on purpose (use the remote's volume keys). The QR code and address sit small at the bottom of the rail.
- Not built yet: resume the last song after a restart, fast scroll (Ch+/Ch- page jump, letter badge), a Cover/Record toggle.
- No local compiler on the PC: the CI build is the compile check. Test on the TV with `adb shell input keyevent ...` plus `adb exec-out screencap -p`; `curl http://192.168.1.6:8080/api/state` shows what the player is doing.

## Build, install, update

- **Page-only change** (fast path): edit `app/src/main/assets/index.html`, run `.\push-page.ps1`, reload the phone page. Survives until the app is reinstalled.
- **Java change**: commit and push `main`; the `Build USB Music APK` workflow builds. Wait with `gh run watch <id> --repo sagarmn-4/usb-music`, then `gh run download <id> --repo sagarmn-4/usb-music -n usb-music-apk -D <new empty dir>` (the first try often returns nothing; retry). Locally you can also run `gradle assembleDebug` (needs the Android SDK, Gradle 8.9, JDK 17).
- **Install**: every CI build has a different debug key, so `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `VERIFICATION_FAILURE`. Uninstall first, then install. A reinstall wipes the app's private files (the pushed page, `covers-created.txt`). So: back up `files/covers-created.txt` with `run-as` first, then after install run `adb shell pm grant com.sagar.usbmusic android.permission.READ_EXTERNAL_STORAGE` and `adb shell appops set com.sagar.usbmusic MANAGE_EXTERNAL_STORAGE allow`, keep cover auto off, then start `com.sagar.usbmusic/.MainActivity`.
- **Release**: `gh workflow run usb-music-apk.yml --repo sagarmn-4/usb-music --ref main -f release_tag=usb-music-vX.Y`. Current release: `usb-music-v1.1`.
- The repo is private: download with `gh`, not plain URLs.

## scripts/ (PC-side, run against the drive, not part of the app)

- `artist_tags.py`: looks albums up on the iTunes search API (about 20 requests a minute, cached), decides singer vs musician (track artist = singer when it differs from the album artist; album artist = musician), writes tags and verified covers into the files. Writers: WAV in place by appending an `id3 ` chunk with payload hash checks; MP3, FLAC, M4A through a temp copy then swap.
- `run_batch.py`: one batch = next 100 folders not in `done.json`; idempotent; no backups (the user asked for none).
- `review_sheets.py`: contact sheets for covers that could not be verified. `art_plan.py`: picture plan (official cover for matched albums; embed all but obvious junk for the rest).
- `clean_names.py`: clean file and folder names; two-step rename because case-only renames silently do nothing on exFAT; logs to `names\rename-log.json` for undo.
- State of the drive: tags and covers done for everything with an online match; names cleaned. About 2,580 songs still have no artist and about 1,060 no cover (their folders match nothing online).

## Gotchas learned the hard way

- Files must be written **without a BOM** (PowerShell `Set-Content -Encoding utf8` adds one and javac fails). Use the Write/Edit tools.
- Git Bash heredocs mangle backslashes: write scripts to files.
- The TV sleeps after 15 idle minutes ("Going to sleep due to timeout"); the app holds a screen wake lock while a song plays, which fixed it.
- The TV media database lists renamed files under old and new names; the scan skips rows whose file no longer exists.
- Page initial state must include `tree` and `folderNodes` or the page crashes on first load.
- zxing 3.5.3 hint name is `EncodeHintType.ERROR_CORRECTION`.
- The old repo's `.gitignore` ignored any folder called `tools`; not an issue here, scripts live in `scripts/`.
- HDMI-CEC: the receiver reverts volume 0, big jumps, `ADJUST_MUTE` and `ADJUST_UNMUTE`. Hence no Mute button.

## Declined by the user: do not offer again

PIN or password for the phone page, crossfade (fade in/out was built and removed), controlling the Yamaha receiver directly (so no true mute), a DLNA server, and an on/off switch for the app on the TV. A native phone app is only a someday idea; the web page is fine.

## Open items (only if the user asks)

- Per-song lookup (by title and length) for the ~2,580 songs without an artist and ~1,060 without a cover, or hand fixes for named folders.

## Working style that fits this user

Short, plain answers. Show a preview or options before changing UI. Commit and push only when asked, and say what was committed. Report outcomes faithfully (what was verified, what was not). No attribution beyond the standard `Co-Authored-By` trailer on commits.
