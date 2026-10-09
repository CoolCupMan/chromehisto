package com.chromehisto.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Disk-backed list of history records, so imports of any size stay within the
 * app's memory limit. Only a small sort index (time, offset, length, source)
 * is kept in memory; record bodies live in a temporary file.
 */
class EntryStore(dir: File) : Closeable {
    private val file = File(dir, "entries-${System.nanoTime()}.bin")
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var pos = 0L

    private val times = ArrayList<String>()
    private var offsets = LongArray(1024)
    private var lengths = IntArray(1024)
    private var srcs = IntArray(1024)

    val size: Int get() = times.size

    fun add(src: Int, timeUtc: String?, raw: String) {
        val b = raw.toByteArray(Charsets.UTF_8)
        val i = times.size
        if (i == offsets.size) {
            offsets = offsets.copyOf(i * 2); lengths = lengths.copyOf(i * 2); srcs = srcs.copyOf(i * 2)
        }
        out.write(b)
        times.add(timeUtc ?: "")
        offsets[i] = pos; lengths[i] = b.size; srcs[i] = src
        pos += b.size
    }

    /** Indices ordered newest first (records without a time go last). */
    fun newestFirst(): IntArray {
        out.flush()
        return (0 until size).sortedWith { a, b -> times[b].compareTo(times[a]) }.toIntArray()
    }

    fun time(i: Int): String = times[i]
    fun source(i: Int): Int = srcs[i]

    fun reader(): Reader = Reader()

    inner class Reader : Closeable {
        private val raf = RandomAccessFile(file, "r")
        fun read(i: Int): String {
            val buf = ByteArray(lengths[i])
            raf.seek(offsets[i])
            raf.readFully(buf)
            return String(buf, Charsets.UTF_8)
        }
        override fun close() = raf.close()
    }

    override fun close() {
        try { out.close() } catch (e: Exception) { }
        file.delete()
    }
}

/**
 * One imported evidence source. [finish] turns a stored raw record into the
 * final report entry JSON (Takeout records get their container context here,
 * once every container has been fully read).
 */
class SourceResult(
    val info: JSONObject,
    val downloads: JSONArray = JSONArray(),
    val finish: (String) -> String = { it },
)
