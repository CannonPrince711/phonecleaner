package com.ghostcleaner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** What kind of junk an item is. Order here is the order shown in the list. */
enum class Category(val title: String, val description: String) {
    ORPHAN_APP_DATA("Leftover app folders", "Data folders from apps that are no longer installed"),
    APK_LEFTOVER("Leftover APK installers", "Installer files; ones for apps you already have are pre-selected"),
    TRASHED("Trashed files", "Files sitting in hidden trash (.trashed-*, .Trash)"),
    THUMBNAILS("Thumbnail caches", "Regenerated automatically when needed"),
    TEMP_LOG("Temp & log files", ".tmp, .temp, .log, .bak, crash dumps"),
    EMPTY_FILE("Empty files (ghost files)", "Zero-byte files that hold no data"),
    EMPTY_FOLDER("Empty folders (ghost folders)", "Folders with nothing inside"),
    DUPLICATE("Duplicate files", "Extra copies; the oldest copy is always kept"),
    LARGE_FILE("Large files", "Files over 100 MB — not selected, pick what you don't need"),
    OWN_CACHE("This app's cache", "Cache created by GhostCleaner itself"),
}

data class JunkItem(
    val file: File,
    val category: Category,
    val size: Long,
    var selected: Boolean = true,
    val note: String = "",
)

class Scanner(private val context: Context, ignored: Set<String>) {

    private val root: File = Environment.getExternalStorageDirectory()
    private val rootPath = root.absolutePath

    /** Folders we never walk into or touch. */
    private val protectedTopLevel = setOf("Android", SafetyBin.DIR_NAME)
    private val protectedNames = setOf(".nomedia")
    private val ignoredPaths = ignored.map { it.trimEnd('/') }

    private val tempExtensions = setOf("tmp", "temp", "log", "bak", "old", "dmp")
    private val thumbnailDirNames = setOf(".thumbnails", ".thumbcache", ".thumbdata")

    /** Regular files big enough to matter for the duplicate / large-file passes. */
    private val candidates = mutableListOf<File>()
    private var installed: Set<String> = emptySet()

    @Volatile var cancelled = false

    fun scan(onProgress: (String) -> Unit): List<JunkItem> {
        cancelled = false
        candidates.clear()
        installed = installedPackages()
        val results = mutableListOf<JunkItem>()
        val claimed = HashSet<String>() // folders already reported as a whole

        onProgress("Checking leftover app folders…")
        scanOrphans(results, claimed)

        onProgress("Scanning storage…")
        root.listFiles()?.forEach { top ->
            if (cancelled) return finish(results)
            if (top.name in protectedTopLevel) return@forEach
            if (top.isDirectory) walk(top, results, claimed, onProgress)
            else classifyFile(top)?.let { results += it }
        }

        if (!cancelled) findDuplicates(results, onProgress)
        if (!cancelled) findLargeFiles(results)

        onProgress("Checking app cache…")
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                results += JunkItem(f, Category.OWN_CACHE, sizeOf(f))
            }
        }
        return finish(results)
    }

    private fun finish(results: List<JunkItem>) =
        results.sortedWith(compareBy({ it.category.ordinal }, { -it.size }))

    private fun isIgnored(f: File): Boolean {
        val p = f.absolutePath
        return ignoredPaths.any { p == it || p.startsWith("$it/") }
    }

    /**
     * Walks a directory tree. Returns true if the directory holds only empty
     * folders, so a chain of nested empty folders collapses into one entry.
     */
    private fun walk(
        dir: File,
        results: MutableList<JunkItem>,
        claimed: MutableSet<String>,
        onProgress: (String) -> Unit,
    ): Boolean {
        if (cancelled || !dir.isDirectory) return false
        if (dir.absolutePath in claimed || isIgnored(dir)) return false

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
            if (dir.list()?.isNotEmpty() == true) {
                results += JunkItem(dir, Category.TRASHED, sizeOf(dir))
                claimed += dir.absolutePath
            }
            return false
        }

        val children = dir.listFiles() ?: return false
        if (children.isEmpty()) {
            results += JunkItem(dir, Category.EMPTY_FOLDER, 0)
            return true
        }

        onProgress(dir.absolutePath.removePrefix(rootPath))

        var onlyEmptyFolders = true
        val pendingEmpty = mutableListOf<JunkItem>()

        for (child in children) {
            if (cancelled) return false
            if (child.isDirectory) {
                val before = results.size
                val childEmpty = walk(child, results, claimed, onProgress)
                if (childEmpty) {
                    pendingEmpty += results.subList(before, results.size)
                        .filter { it.category == Category.EMPTY_FOLDER }
                } else {
                    onlyEmptyFolders = false
                }
            } else {
                onlyEmptyFolders = false
                if (isIgnored(child)) continue
                classifyFile(child)?.let { results += it }
            }
        }

        if (onlyEmptyFolders && pendingEmpty.isNotEmpty()) {
            val drop = pendingEmpty.toSet()
            results.removeAll { it in drop }
            results += JunkItem(dir, Category.EMPTY_FOLDER, 0)
            return true
        }
        return false
    }

    /** Returns a junk item for [f], or null (and records it as a candidate) if it's a normal file. */
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
        if (ext == "apk") return classifyApk(f, len)

        if (len >= MIN_CANDIDATE_SIZE) candidates += f
        return null
    }

    private fun classifyApk(f: File, len: Long): JunkItem {
        val info = try {
            context.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
        } catch (e: Exception) { null }
        val pkg = info?.packageName
        return when {
            pkg == null -> JunkItem(f, Category.APK_LEFTOVER, len, selected = true, note = "Broken or incomplete APK")
            pkg in installed -> JunkItem(f, Category.APK_LEFTOVER, len, selected = true, note = "Already installed")
            else -> JunkItem(f, Category.APK_LEFTOVER, len, selected = false, note = "Not installed")
        }
    }

    // ---------- Duplicates ----------

    /**
     * Three passes so we only fully read files that are likely identical:
     * group by size → hash first/last 64 KB → full hash.
     */
    private fun findDuplicates(results: MutableList<JunkItem>, onProgress: (String) -> Unit) {
        val bySize = candidates.filter { it.length() >= MIN_DUPLICATE_SIZE }
            .groupBy { it.length() }.values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }
        var done = 0

        for (sameSize in bySize) {
            if (cancelled) return
            val byQuick = sameSize.groupBy { done++; quickHash(it) }.filter { it.key != null && it.value.size > 1 }
            onProgress("Checking duplicates… $done / $total")
            for (quickGroup in byQuick.values) {
                val byFull = quickGroup.groupBy { fullHash(it) }.filter { it.key != null && it.value.size > 1 }
                for (copies in byFull.values) {
                    val keep = copies.minWith(compareBy({ it.lastModified() }, { it.absolutePath.length }))
                    val keepRel = keep.absolutePath.removePrefix(rootPath)
                    copies.filter { it != keep }.forEach { dup ->
                        results += JunkItem(dup, Category.DUPLICATE, dup.length(), note = "Copy of $keepRel")
                    }
                }
            }
        }
    }

    private fun quickHash(f: File): String? = try {
        RandomAccessFile(f, "r").use { raf ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(QUICK_CHUNK)
            val len = raf.length()
            var n = raf.read(buf)
            if (n > 0) md.update(buf, 0, n)
            if (len > QUICK_CHUNK * 2) {
                raf.seek(len - QUICK_CHUNK)
                n = raf.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) { null }

    private fun fullHash(f: File): String? = try {
        f.inputStream().buffered(1 shl 16).use { input ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (cancelled) return null
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) { null }

    // ---------- Large files ----------

    private fun findLargeFiles(results: MutableList<JunkItem>) {
        val already = results.map { it.file.absolutePath }.toHashSet()
        candidates.filter { it.length() >= LARGE_FILE_SIZE && it.absolutePath !in already }
            .forEach { results += JunkItem(it, Category.LARGE_FILE, it.length(), selected = false) }
    }

    // ---------- Leftover app folders ----------

    /**
     * Android/data and Android/obb are locked by the OS on Android 11+ even with
     * All-files access, so on newer devices only Android/media can be checked.
     */
    private fun scanOrphans(results: MutableList<JunkItem>, claimed: MutableSet<String>) {
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
                if (!dir.isDirectory || isIgnored(dir)) return@forEach
                val pkg = dir.name
                if (!pkg.contains('.')) return@forEach // not a package name
                if (pkg in installed || pkg == context.packageName) return@forEach
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
        const val LARGE_FILE_SIZE = 100L * 1024 * 1024
        const val MIN_DUPLICATE_SIZE = 512L * 1024
        private const val MIN_CANDIDATE_SIZE = MIN_DUPLICATE_SIZE
        private const val QUICK_CHUNK = 64 * 1024

        fun sizeOf(f: File): Long =
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            else f.length()

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
