# Shelf

A small, fast web file manager with a media gallery. One Go binary plus ffmpeg, no database.

- Thumbnail grid for pictures and videos, with a list view, sorting and an adjustable tile size
- Full-screen viewer for images, video and audio: arrow keys or swipe to step through, `F` for fullscreen, pinch / wheel / double-tap to zoom pictures
- Plays almost any video or audio format: files the browser cannot play are transcoded on the fly, with seeking, and a switch in the viewer chooses between the original file and a transcode at full size, 1080p, 720p or 480p
- Works with touch and resizes itself for phones, tablets and desktops
- Upload (button, drag and drop, whole folders), new folder, rename, move, copy, delete
- Download single files, or several files and folders as one zip
- Recursive search by name
- Optional login and a read-only mode

## Run

Edit the folder path and password in `docker-compose.yml`, then:

```bash
docker compose up -d --build
```

Open http://localhost:8080.

## Unraid

Every push to `main` builds a new image and publishes it as
`ghcr.io/elremeithi/shelf:latest`, so Unraid can install and update it like any
other app.

1. Save the template to the Community Applications private folder:

   ```bash
   mkdir -p /boot/config/plugins/community.applications/private/elremeithi
   wget -qO /boot/config/plugins/community.applications/private/elremeithi/shelf.xml https://raw.githubusercontent.com/elremeithi/shelf/main/unraid/shelf.xml
   ```

2. Open **Apps** → **Private Apps** → **shelf** → **Install**. Set **Files** to the share you want to browse, set a **Password** → **Apply**.

The template runs Shelf as `nobody:users` (99:100) with umask 000, so uploads have the same ownership and permissions as files written over SMB. Thumbnails go to `/mnt/user/appdata/shelf`.

Updates show up in the Docker tab like any other container. The **Auto Update Applications** plugin can install them on a schedule.

## Android app

`android/` holds **Shelf sync**, an Android app (8.0 and newer) that pairs folders on the phone with folders on the server.

- **Two-way**: new and changed files are copied both ways. Deleting a file on one side never deletes it on the other. When a phone edit replaces a server file, the old one is kept in the folder's hidden `.shelf-versions`; when both sides changed a file, both copies are kept.
- **Move to server**: frees space on the phone. Each file is uploaded, the server confirms it stored identical bytes (SHA-256), and only then is it removed from the phone. A different file already on the server under the same name is kept, and the phone's copy is stored beside it.
- Any phone folder can go to any server folder; missing server folders are created.
- Uploads are sent in 8 MB chunks and resume after a dropped connection; files appear on the server only once complete and checked.
- Syncs run automatically (every 15 minutes to once a day, optionally only on Wi-Fi or while charging) or with **Sync now**.
- **Open Shelf** shows the full web interface inside the app, already signed in.

Files on the server are never deleted by the app.

Build it with JDK 17+ and the Android SDK (platform 35):

```bash
cd android && ./gradlew assembleDebug
```

For a release build, sign with your own key; it must stay the same for every update:

```bash
SHELF_KEYSTORE=/path/release.jks SHELF_KEYSTORE_PASSWORD=... ./gradlew assembleRelease
```

The sync engine tests run against a real server: start Shelf, then set `SHELF_TEST_URL`, `SHELF_TEST_DATA` (the folder it serves), `SHELF_TEST_USER` and `SHELF_TEST_PASS` and run `./gradlew testDebugUnitTest`.

## Settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `SHELF_USER`, `SHELF_PASSWORD` | unset | Login. With either unset there is no login at all. |
| `SHELF_READONLY` | off | `1` disables uploads and every change. |
| `SHELF_SHOW_HIDDEN` | off | `1` shows dotfiles. |
| `SHELF_ROOT` | `/data` | Folder to serve. |
| `SHELF_CACHE` | `/cache` | Thumbnail cache. Safe to delete. |
| `SHELF_ADDR` | `:8080` | Listen address. |

| `PUID`, `PGID` | `1000` | User and group the server runs as, and that own uploads. |
| `UMASK` | `022` | Permission mask for uploaded files and new folders. |

Put it behind a reverse proxy with HTTPS before exposing it beyond your own network.

## Keys

| Where | Key | Action |
| --- | --- | --- |
| Browser | `/` | Search |
| Browser | Ctrl/Cmd-click, Shift-click | Select, select a range |
| Browser | `Ctrl+A`, `Delete`, `Backspace` | Select all, delete, go up |
| Viewer | `←` `→` | Previous, next |
| Viewer | `F` | Fullscreen |
| Viewer | `Space`, `M` | Play/pause, mute |
| Viewer | `Esc` | Close |

Touch: long-press selects, swipe left/right steps through the viewer, swipe down closes it, tap hides or shows its bars.

## Notes

- **Playback.** "Original" sends the file untouched. If the browser cannot play it (HEVC or 10-bit video in most browsers, AVI, WMV, MPEG-2, AC3/DTS sound, WMA, ...) Shelf switches to a transcode by itself. A transcode is H.264 video with AAC stereo sound, streamed as HLS; "full size" keeps the source resolution at near-lossless quality (x264 CRF 18).
- **Transcoding cost.** It is done in software by ffmpeg, one encode per viewer, at most two at once. 1080p is fine on most CPUs; 4K HEVC sources may not reach real time on a small NAS, so pick 1080p or 720p there. There is no GPU acceleration, HDR is not tone-mapped (colours look flat), and only the first audio track is used, without subtitles.
- **Disk.** Transcoded segments are kept under the cache folder (`hls/`) while you watch and removed 15 minutes after the last request.
- HEIC and TIFF pictures are shown through a server-made JPEG preview, as far as the bundled ffmpeg can read them.
- There is a single account, not per-user permissions or share links.

## Troubleshooting uploads

- **"Shelf cannot write to this folder"** (banner) or **"permission denied: Shelf runs as user …"**: the user set by `PUID`/`PGID` has no write access to that folder on the server. Either give it access (on Unraid, shares normally belong to `nobody:users`, which is 99:100; a folder created by root, or by Docker for a path that did not exist yet, does not) or set `PUID`/`PGID` to the folder's owner. The container log also notes at start-up when the top folder is not writable.
- **Uploading at the top level fails, subfolders work**: the top folder is the mapped path itself. On Unraid, map a share (for example `/mnt/user/media`) rather than `/mnt/user/`, or upload inside a share.
- **"the connection was cut"** or **"refused the file as too large"**: a reverse proxy or tunnel in front of Shelf is blocking or limiting uploads. For nginx, set `client_max_body_size 0;` and `proxy_request_buffering off;` for Shelf.

## Develop

```bash
SHELF_ROOT=./some-folder SHELF_CACHE=./.cache go run .
```

Needs Go 1.26+ and `ffmpeg`/`ffprobe` (with libx264) on the PATH.

The bundled player library is hls.js (Apache 2.0), see `THIRD-PARTY.txt`.
