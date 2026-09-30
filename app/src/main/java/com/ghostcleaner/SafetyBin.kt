package com.ghostcleaner

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Holds cleaned files for a few days so a clean can be undone.
 * Files are moved (not copied) into /storage/emulated/0/.GhostCleanerBin/<batch>/<original path>,
 * and a small log in app storage remembers where each one came from.
 */
class SafetyBin(context: Context) {

    private val storageRoot: File = Environment.getExternalStorageDirectory()
    val binRoot = File(storageRoot, DIR_NAME)
    private val log = File(context.filesDir, "bin_log.tsv")

    private data class Entry(val batch: Long, val original: String, val stored: String)

    data class Result(val count: Int, val bytes: Long, val failed: Int)

    /** Moves items into the bin as one batch. Returns what happened; batch id is [lastBatch] afterwards. */
    fun moveToBin(items: List<JunkItem>, onProgress: (Int) -> Unit): Result {
        val batch = System.currentTimeMillis()
        val batchDir = File(binRoot, batch.toString())
        batchDir.mkdirs()
        File(binRoot, ".nomedia").let { if (!it.exists()) runCatching { it.createNewFile() } }

        val added = mutableListOf<Entry>()
        var count = 0
        var bytes = 0L
        items.forEachIndexed { i, item ->
            val src = item.file
            val rel = src.absolutePath.removePrefix(storageRoot.absolutePath).trimStart('/')
            // Files outside shared storage (e.g. our own cache) are just deleted.
            val ok = if (!src.absolutePath.startsWith(storageRoot.absolutePath)) {
                deleteNow(src)
            } else {
                val dest = File(batchDir, rel)
                dest.parentFile?.mkdirs()
                val moved = move(src, dest)
                if (moved) added += Entry(batch, src.absolutePath, dest.absolutePath)
                moved
            }
            if (ok) { count++; bytes += item.size }
            onProgress(i + 1)
        }
        appendLog(added)
        if (added.isEmpty()) batchDir.deleteRecursively()
        return Result(count, bytes, items.size - count)
    }

    /** Most recent batch that still has files in the bin, or null. */
    fun lastBatch(): Long? = readLog().maxOfOrNull { it.batch }

    /** Puts every file of a batch back where it was. Returns how many were restored. */
    fun restore(batch: Long): Int {
        val entries = readLog()
        var restored = 0
        val keep = entries.filter { e ->
            if (e.batch != batch) return@filter true
            val stored = File(e.stored)
            if (!stored.exists()) return@filter false
            var target = File(e.original)
            if (target.exists() && !(target.isDirectory && target.list().isNullOrEmpty())) {
                target = File(target.parentFile, "${target.nameWithoutExtension} (restored)" +
                        (if (target.extension.isNotEmpty()) ".${target.extension}" else ""))
            } else if (target.isDirectory) {
                target.delete() // empty folder re-created by someone; replace it
            }
            target.parentFile?.mkdirs()
            if (move(stored, target)) { restored++; false } else true
        }
        writeLog(keep)
        File(binRoot, batch.toString()).let { if (keep.none { e -> e.batch == batch }) it.deleteRecursively() }
        return restored
    }

    /** Permanently deletes batches older than [days]. Returns bytes freed. */
    fun purgeOlderThan(days: Int): Long {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        return purge { it < cutoff }
    }

    /** Permanently deletes everything in the bin. Returns bytes freed. */
    fun emptyAll(): Long = purge { true }

    fun totalSize(): Long = if (binRoot.exists()) Scanner.sizeOf(binRoot) else 0L

    fun itemCount(): Int = readLog().size

    private fun purge(shouldPurge: (Long) -> Boolean): Long {
        var freed = 0L
        binRoot.listFiles()?.forEach { dir ->
            val batch = dir.name.toLongOrNull() ?: return@forEach
            if (shouldPurge(batch)) {
                freed += Scanner.sizeOf(dir)
                dir.deleteRecursively()
            }
        }
        writeLog(readLog().filter { !shouldPurge(it.batch) })
        return freed
    }

    private fun move(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        // Different volume (e.g. SD card): copy, then delete the original.
        return try {
            if (src.copyRecursively(dest, overwrite = true)) {
                src.deleteRecursively()
                true
            } else false
        } catch (e: Exception) {
            dest.deleteRecursively()
            false
        }
    }

    private fun readLog(): List<Entry> = try {
        if (!log.exists()) emptyList()
        else log.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 3) p[0].toLongOrNull()?.let { Entry(it, p[1], p[2]) } else null
        }
    } catch (e: Exception) { emptyList() }

    private fun writeLog(entries: List<Entry>) {
        log.writeText(entries.joinToString("") { "${it.batch}\t${it.original}\t${it.stored}\n" })
    }

    private fun appendLog(entries: List<Entry>) {
        if (entries.isEmpty()) return
        log.appendText(entries.joinToString("") { "${it.batch}\t${it.original}\t${it.stored}\n" })
    }

    companion object {
        const val DIR_NAME = ".GhostCleanerBin"
        const val KEEP_DAYS = 3

        fun deleteNow(f: File): Boolean = if (f.isDirectory) f.deleteRecursively() else f.delete()
    }
}
