package com.ghostcleaner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/** What kind of junk an item is. Order here is the order shown in the list. */
enum class Category(val title: String, val description: String) {
    ORPHAN_APP_DATA("Leftover app folders", "Data folders from apps that are no longer installed"),
    TRASHED("Trashed files", "Files sitting in hidden trash (.trashed-*, .Trash)"),
    THUMBNAILS("Thumbnail caches", "Regenerated automatically when needed"),
    TEMP_LOG("Temp & log files", ".tmp, .temp, .log, .bak, crash dumps"),
    EMPTY_FILE("Empty files (ghost files)", "Zero-byte files that hold no data"),
    EMPTY_FOLDER("Empty folders (ghost folders)", "Folders with nothing inside"),
    OWN_CACHE("This app's cache", "Cache created by GhostCleaner itself"),
}

data class JunkItem(
    val file: File,
    val category: Category,
    val size: Long,
    var selected: Boolean = true,
)

class Scanner(private val context: Context) {

    private val root: File = Environment.getExternalStorageDirectory()

    /** Folders we never walk into or touch. */
    private val protectedTopLevel = setOf("Android")
    private val protectedNames = setOf(".nomedia")

    private val tempExtensions = setOf("tmp", "temp", "log", "bak", "old", "dmp")
    private val thumbnailDirNames = setOf(".thumbnails", ".thumbcache", ".thumbdata")

    @Volatile var cancelled = false

    fun scan(onProgress: (String) -> Unit): List<JunkItem> {
        cancelled = false
        val results = mutableListOf<JunkItem>()
        val claimed = HashSet<String>() // paths already inside a reported folder

        onProgress("Checking leftover app folders…")
        scanOrphans(results, claimed)

        onProgress("Scanning storage…")
        root.listFiles()?.forEach { top ->
            if (cancelled) return results
            if (top.name in protectedTopLevel) return@forEach
            walk(top, results, claimed, onProgress)
        }

        onProgress("Checking app cache…")
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                results += JunkItem(f, Category.OWN_CACHE, sizeOf(f))
            }
        }

        return results.sortedWith(compareBy({ it.category.ordinal }, { -it.size }))
    }

    /**
     * Walks a directory tree. Returns true if the directory is (or will be,
     * after cleaning) empty, so parents of nested empty folders collapse
     * into a single "empty folder" entry.
     */
    private fun walk(
        dir: File,
        results: MutableList<JunkItem>,
        claimed: MutableSet<String>,
        onProgress: (String) -> Unit,
    ): Boolean {
        if (cancelled) return false
        if (!dir.isDirectory) return false
        if (dir.absolutePath in claimed) return false

        val name = dir.name.lowercase()

        if (name in thumbnailDirNames) {
            val size = sizeOf(dir)
            if (size > 0) {
                results += JunkItem(dir, Category.THUMBNAILS, size)
                claimed += dir.absolutePath
            }
            return false
        }
        if (name == ".trash" || name.startsWith(".trash-")) {
            val size = sizeOf(dir)
            if (size > 0 || (dir.list()?.isNotEmpty() == true)) {
                results += JunkItem(dir, Category.TRASHED, size)
                claimed += dir.absolutePath
            }
            return false
        }

        val children = dir.listFiles() ?: return false
        if (children.isEmpty()) {
            results += JunkItem(dir, Category.EMPTY_FOLDER, 0)
            return true
        }

        onProgress(dir.absolutePath.removePrefix(root.absolutePath))

        var allChildrenGhost = true
        val pendingEmpty = mutableListOf<JunkItem>()

        for (child in children) {
            if (cancelled) return false
            if (child.isDirectory) {
                val before = results.size
                val childEmpty = walk(child, results, claimed, onProgress)
                if (childEmpty) {
                    // Pull the child's empty-folder entries aside; if this folder
                    // also turns out empty we'll report just this one.
                    val added = results.subList(before, results.size)
                    pendingEmpty += added.filter { it.category == Category.EMPTY_FOLDER }
                } else {
                    allChildrenGhost = false
                }
            } else {
                val item = classifyFile(child)
                if (item != null) {
                    results += item
                    if (item.category != Category.EMPTY_FILE) allChildrenGhost = false
                } else {
                    allChildrenGhost = false
                }
            }
        }

        // A folder that only contains empty folders is itself a ghost folder.
        // (Folders that only hold empty *files* are left as-is so the empty files
        // show up individually and the user can decide.)
        val onlyEmptyFolders = allChildrenGhost && children.all { it.isDirectory }
        if (onlyEmptyFolders && pendingEmpty.isNotEmpty()) {
            results.removeAll(pendingEmpty.toSet())
            results += JunkItem(dir, Category.EMPTY_FOLDER, 0)
            return true
        }
        return false
    }

    private fun classifyFile(f: File): JunkItem? {
        val name = f.name
        if (name in protectedNames) return null
        val len = f.length()

        if (name.startsWith(".trashed-")) return JunkItem(f, Category.TRASHED, len)
        if (len == 0L) return JunkItem(f, Category.EMPTY_FILE, 0)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in tempExtensions || name.startsWith("~$")) {
            return JunkItem(f, Category.TEMP_LOG, len)
        }
        return null
    }

    /**
     * Android/data and Android/obb are locked by the OS on Android 11+ even with
     * All-files access, so on newer devices only Android/media can be checked.
     */
    private fun scanOrphans(results: MutableList<JunkItem>, claimed: MutableSet<String>) {
        val installed = installedPackages()
        if (installed.isEmpty()) return // can't tell, so don't guess
        val bases = buildList {
            add(File(root, "Android/media"))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                add(File(root, "Android/data"))
                add(File(root, "Android/obb"))
            }
        }
        for (base in bases) {
            base.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val pkg = dir.name
                if (!pkg.contains('.')) return@forEach // not a package name
                if (pkg in installed) return@forEach
                if (pkg == context.packageName) return@forEach
                results += JunkItem(dir, Category.ORPHAN_APP_DATA, sizeOf(dir))
                claimed += dir.absolutePath
            }
        }
    }

    private fun installedPackages(): Set<String> = try {
        val pm = context.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }
        list.map { it.packageName }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    companion object {
        fun sizeOf(f: File): Long =
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            else f.length()

        /** Deletes items; returns (count deleted, bytes freed). */
        fun delete(items: List<JunkItem>, onProgress: (Int) -> Unit): Pair<Int, Long> {
            var count = 0
            var bytes = 0L
            items.forEachIndexed { i, item ->
                val ok = if (item.file.isDirectory) item.file.deleteRecursively() else item.file.delete()
                if (ok) { count++; bytes += item.size }
                onProgress(i + 1)
            }
            return count to bytes
        }

        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = bytes / 1024.0
            var u = 0
            while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
            return String.format("%.1f %s", v, units[u])
        }
    }
}
