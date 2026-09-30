package com.ghostcleaner

import android.os.Environment
import android.os.StatFs
import java.io.File

/** Buckets everything in shared storage by file type. */
object StorageBreakdown {

    enum class Kind(val label: String, val color: Int) {
        IMAGES("Photos & images", 0xFF42A5F5.toInt()),
        VIDEOS("Videos", 0xFFAB47BC.toInt()),
        AUDIO("Music & audio", 0xFF26A69A.toInt()),
        DOCUMENTS("Documents", 0xFFFFA726.toInt()),
        ARCHIVES("Archives & installers", 0xFFEF5350.toInt()),
        OTHER("Other files", 0xFF8D6E63.toInt()),
        SYSTEM("Apps, app data & system", 0xFF78909C.toInt()),
        FREE("Free", 0x3380808C),
    }

    data class Result(val sizes: Map<Kind, Long>, val total: Long)

    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw")
    private val videos = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "mid")
    private val docs = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "rtf", "csv", "epub", "md")
    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "apk", "apks", "xapk", "obb", "iso")

    fun kindOf(f: File): Kind = when (f.extension.lowercase()) {
        in images -> Kind.IMAGES
        in videos -> Kind.VIDEOS
        in audio -> Kind.AUDIO
        in docs -> Kind.DOCUMENTS
        in archives -> Kind.ARCHIVES
        else -> Kind.OTHER
    }

    fun compute(isCancelled: () -> Boolean = { false }): Result {
        val root = Environment.getExternalStorageDirectory()
        val sizes = LinkedHashMap<Kind, Long>()
        Kind.values().forEach { sizes[it] = 0L }

        root.walkTopDown()
            .onEnter { !isCancelled() && it.name != SafetyBin.DIR_NAME }
            .filter { it.isFile }
            .forEach { f -> val k = kindOf(f); sizes[k] = (sizes[k] ?: 0L) + f.length() }

        val stat = StatFs(root.path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val files = sizes.values.sum()
        sizes[Kind.SYSTEM] = (total - free - files).coerceAtLeast(0L)
        sizes[Kind.FREE] = free
        return Result(sizes, total)
    }
}
