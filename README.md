# GhostCleaner

An Android cleaner that finds and removes junk and "ghost" files from shared storage. It scans first, shows everything it found grouped by category, and deletes nothing until you review the list and confirm.

## What it finds

| Category | What it is | Pre-selected |
|---|---|---|
| Leftover app folders | Folders in `Android/media` (and `Android/data`, `Android/obb` on Android 10 and below) from apps no longer installed | Yes |
| Leftover APK installers | `.apk` files; ones for apps you already have or that are broken | Installed/broken only |
| Trashed files | Hidden `.trashed-*` files and `.Trash` folders | Yes |
| Thumbnail caches | `.thumbnails` and similar; rebuilt when needed | Yes |
| Temp & log files | `.tmp`, `.temp`, `.log`, `.bak`, `.old`, `.dmp`, `~$` files | Yes |
| Empty files (ghost files) | Zero-byte files | Yes |
| Empty folders (ghost folders) | Empty folders, including chains of nested empty folders | Yes |
| Duplicate files | Identical files over 512 KB (checked by content hash); the oldest copy is always kept | Yes |
| Large files | Anything over 100 MB | No |
| App cache | GhostCleaner's own cache | Yes |

It never walks into the `Android/` system folder (other than the orphan check above) and never deletes `.nomedia` files.

## Other features

- **Safety bin with Undo.** Cleaned files are moved to a hidden `.GhostCleanerBin` folder for 3 days, then deleted automatically. Tap **Undo** to put the last clean back, or empty the bin early from the menu. Turn it off in the menu to delete immediately.
- **Ignore list.** Long-press any result to exclude its folder from future scans. Manage it from the menu.
- **Storage bar.** Shows used and free space, plus how much GhostCleaner has cleaned overall.

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
