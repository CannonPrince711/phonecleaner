# GhostCleaner

An Android cleaner that finds and removes junk and "ghost" files from shared storage. It scans first, shows everything it found grouped by category, and deletes nothing until you review the list and confirm.

## What it cleans

| Category | What it is |
|---|---|
| Leftover app folders | Folders in `Android/media` (and `Android/data`, `Android/obb` on Android 10 and below) belonging to apps that are no longer installed |
| Trashed files | Hidden `.trashed-*` files and `.Trash` folders |
| Thumbnail caches | `.thumbnails` and similar folders; rebuilt automatically when needed |
| Temp & log files | `.tmp`, `.temp`, `.log`, `.bak`, `.old`, `.dmp`, `~$` files |
| Empty files (ghost files) | Zero-byte files |
| Empty folders (ghost folders) | Folders with nothing in them, including chains of nested empty folders |
| App cache | GhostCleaner's own cache |

It never walks into the `Android/` system folder (other than the orphan check above) and never deletes `.nomedia` files.

**Limits set by Android:** since Android 8, apps can't clear other apps' caches without root, and since Android 11, `Android/data` and `Android/obb` are locked even with All-files access. Use *Settings → Storage* for those.

## Install

Download the latest APK from [Releases](../../releases), open it on your phone, and allow installing from that source. On first scan, grant **All files access** when prompted.

Every build is signed with the same release key and gets a higher version number, so new APKs install over the old one and keep your settings.

## Building

GitHub Actions builds and signs an APK on every push to `main` and publishes it as a release. It needs two repository secrets (*Settings → Secrets and variables → Actions*):

- `KEYSTORE_BASE64` — the release keystore, base64-encoded
- `KEYSTORE_PASSWORD` — its password (key alias: `ghostcleaner`)

Keep a backup of the keystore. If it's lost, future builds can't be installed over existing installs.

To build locally in Android Studio, set `SIGNING_STORE_FILE` and `SIGNING_STORE_PASSWORD` before running `assembleRelease`, or just use `assembleDebug`.
