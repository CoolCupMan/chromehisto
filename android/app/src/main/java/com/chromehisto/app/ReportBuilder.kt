package com.chromehisto.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Port of chromehisto/report.py that streams: records are written one at a time
 * from the [EntryStore], so report size is not limited by the app's heap. Large
 * histories are split into parts that a phone's WebView can open comfortably.
 */
object ReportBuilder {

    /** Records per report file. */
    const val PART_SIZE = 15000

    fun reportsDir(ctx: Context): File = File(ctx.filesDir, "reports").apply { mkdirs() }

    /** Escapes a JSON fragment for embedding inside <script type="application/json">. */
    private fun escape(s: String): String =
        s.replace("</", "<\\/")
            .replace("<!--", "<\\u0021--")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")

    fun exportContext(ctx: Context, native: NativeContext): JSONObject {
        val snap = native.snapshot()
        val dev = snap.optJSONObject("device")
        val deviceName = dev?.optString("device_name")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        return JSONObject()
            .put("export_time_utc", HistorySources.iso(Instant.now()))
            .put("export_time_local", ZonedDateTime.now().toString())
            .put("os_user", "android-user-" + (dev?.opt("android_user_id") ?: "?"))
            .put("os_user_full_name", JSONObject.NULL)
            .put("hostname", deviceName)
            .put("os", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            .put("exported_with", "chromehisto Android app")
            .put("device_network_location_at_export", snap)
    }

    fun build(ctx: Context, sources: List<SourceResult>, store: EntryStore, context: JSONObject,
              baseName: String): List<File> {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        val template = ctx.assets.open("template.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return write(reportsDir(ctx), template, version, sources, store, context, baseName)
    }

    private class HashingOut(val out: OutputStream) {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        fun write(s: String) = write(s.toByteArray(Charsets.UTF_8))
        fun write(b: ByteArray, n: Int = b.size) { md.update(b, 0, n); out.write(b, 0, n) }
        fun hex(): String = md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Context-free core, also used by the JVM tests. Returns the written HTML files. */
    fun write(dir: File, template: String, version: String, sources: List<SourceResult>,
              store: EntryStore, context: JSONObject, baseName: String,
              partSize: Int = PART_SIZE): List<File> {
        val cut = template.indexOf("__DATA_JSON__")
        require(cut >= 0) { "template has no data placeholder" }
        val head = template.substring(0, cut)
        val tail = template.substring(cut + "__DATA_JSON__".length)

        val order = store.newestFirst()
        val parts = maxOf(1, (order.size + partSize - 1) / partSize)
        val infos = JSONArray(sources.map { it.info })
        val downloads = JSONArray()
        sources.forEachIndexed { si, s ->
            for (i in 0 until s.downloads.length()) downloads.put(s.downloads.getJSONObject(i).put("_src", si))
        }
        val generated = HistorySources.iso(Instant.now()) as String
        val tool = JSONObject().put("name", "chromehisto-android").put("version", version)
        val files = ArrayList<File>()

        store.reader().use { rd ->
            for (p in 0 until parts) {
                val from = p * partSize
                val to = minOf(order.size, from + partSize)
                val name = if (parts == 1) baseName else "%s-part%02dof%02d".format(baseName, p + 1, parts)
                val newest = if (to > from) store.time(order[from]) else ""
                val oldest = if (to > from) store.time(order[to - 1]) else ""
                val part = JSONObject().put("index", p + 1).put("count", parts)
                    .put("records_in_part", to - from).put("total_records", order.size)
                    .put("newest_utc", newest).put("oldest_utc", oldest)

                // 1) data block, hashed while it is written
                val tmp = File(dir, "$name.data.tmp")
                val dataHash: String
                BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { os ->
                    val h = HashingOut(os)
                    h.write(escape("{\"tool\":$tool,\"generated_utc\":${JSONObject.quote(generated)}," +
                        "\"context\":$context,\"sources\":$infos,\"part\":$part,\"entries\":["))
                    for (j in from until to) {
                        val i = order[j]
                        val e = sources[store.source(i)].finish(rd.read(i))
                        if (j > from) h.write(",")
                        h.write(escape("{\"n\":${j - from}," + e.substring(1)))
                    }
                    h.write(escape("],\"downloads\":$downloads}"))
                    dataHash = h.hex()
                }

                // 2) final HTML = head (with hash) + data + tail
                val out = File(dir, "$name.html")
                val fileHash: String
                BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                    val h = HashingOut(os)
                    h.write(head.replace("__DATA_SHA256__", dataHash))
                    tmp.inputStream().use { ins ->
                        val buf = ByteArray(1 shl 16)
                        while (true) { val n = ins.read(buf); if (n < 0) break; h.write(buf, n) }
                    }
                    h.write(tail)
                    fileHash = h.hex()
                }
                tmp.delete()
                File(dir, "$name.html.sha256").writeText("$fileHash  ${out.name}\n")
                File(dir, "$name.meta.json").writeText(JSONObject()
                    .put("file", out.name)
                    .put("records", to - from)
                    .put("part", p + 1).put("parts", parts)
                    .put("newest_utc", newest).put("oldest_utc", oldest)
                    .put("downloads", downloads.length())
                    .put("sources", JSONArray((0 until infos.length()).map { infos.getJSONObject(it).optString("label") }))
                    .put("created_utc", generated)
                    .put("data_sha256", dataHash)
                    .put("file_sha256", fileHash)
                    .toString())
                files.add(out)
            }
        }
        return files
    }

    fun list(ctx: Context): JSONArray {
        val arr = JSONArray()
        reportsDir(ctx).listFiles { f -> f.name.endsWith(".meta.json") }
            ?.sortedWith(compareByDescending<File> { it.lastModified() / 60000 }.thenBy { it.name })
            ?.forEach { f ->
                try {
                    val m = JSONObject(f.readText())
                    m.put("size_bytes", File(reportsDir(ctx), m.getString("file")).length())
                    arr.put(m)
                } catch (e: Exception) { }
            }
        return arr
    }

    fun delete(ctx: Context, file: String) {
        val base = file.removeSuffix(".html")
        for (suffix in listOf(".html", ".html.sha256", ".meta.json")) {
            File(reportsDir(ctx), File(base + suffix).name).delete()
        }
    }
}
