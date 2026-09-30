package com.ghostcleaner

import android.content.Context
import java.io.File

/** A simple log of past cleans, newest first. */
class CleanHistory(context: Context) {

    data class Entry(val time: Long, val count: Int, val bytes: Long, val toBin: Boolean)

    private val file = File(context.filesDir, "history.tsv")

    fun add(count: Int, bytes: Long, toBin: Boolean) {
        if (count <= 0) return
        val kept = list().take(MAX_ENTRIES - 1).reversed()
        val all = kept + Entry(System.currentTimeMillis(), count, bytes, toBin)
        try {
            file.writeText(all.joinToString("") { "${it.time}\t${it.count}\t${it.bytes}\t${if (it.toBin) 1 else 0}\n" })
        } catch (e: Exception) { /* history is best-effort */ }
    }

    fun list(): List<Entry> = try {
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4) return@mapNotNull null
            val t = p[0].toLongOrNull() ?: return@mapNotNull null
            val c = p[1].toIntOrNull() ?: return@mapNotNull null
            val b = p[2].toLongOrNull() ?: return@mapNotNull null
            Entry(t, c, b, p[3] == "1")
        }.reversed()
    } catch (e: Exception) { emptyList() }

    fun clear() { file.delete() }

    companion object { private const val MAX_ENTRIES = 200 }
}
