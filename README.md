# USB Music

Plays the music on a USB drive plugged into the TV, through the TV speakers. A phone (same Wi-Fi) browses the songs and controls playback from a web page served by the TV app. Nothing to install on the phone.

## How it works

- `MusicService` (foreground service on the TV) scans the USB volumes under `/storage` for mp3, m4a, aac, flac, wav, ogg, opus, mid and amr files. If that finds nothing it falls back to MediaStore.
- It plays with `MediaPlayer` and serves a tiny HTTP server on port 8080 (next free port up to 8089).
- The phone opens `http://<tv-ip>:8080`. The page is laid out like YouTube Music: Songs, Albums, Artists and Folders tabs with cover art, search, a mini player that opens a full now-playing screen, and TV volume.
- The TV screen shows the address and a QR code to scan with the phone camera. The service starts on boot and rescans when a drive is mounted or removed.
- There is no password. Anyone on your Wi-Fi who knows the address can control the music.

## Build

Push to GitHub: the `Build USB Music APK` workflow builds it. Download the `usb-music-apk` artifact from the run (Actions tab). Or locally with an Android SDK: `cd usb-music && gradle assembleDebug`.

## Install on the TV

```
adb connect <tv-ip>:5555
adb install -r app-debug.apk
adb shell pm grant com.sagar.usbmusic android.permission.READ_EXTERNAL_STORAGE
adb shell am start -n com.sagar.usbmusic/.MainActivity
```

Plug in the USB drive first (or tap Rescan USB on the TV screen or the phone page). Then open the address shown on the TV on your phone. Tip: give the TV a fixed IP in your router so the address never changes.

## Troubleshooting

- "No music found": open "Scan log" on the phone page. It lists each volume the TV exposed and how many tracks were found.
- A track that will not play is skipped (after 5 failures in a row it stops). The error shows at the top of the page.

## Backlog

- Native phone app (install on the phone instead of using the browser).
- PIN for the phone page.
