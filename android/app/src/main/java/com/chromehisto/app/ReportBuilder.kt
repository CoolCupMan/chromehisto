package com.chromehisto.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime

/** Port of chromehisto/report.py: fills the shared template with the payload. */
object ReportBuilder {

    fun reportsDir(ctx: Context): File = File(ctx.filesDir, "reports").apply { mkdirs() }

    private fun embed(payload: JSONObject): String =
        // org.json already escapes "/" as "\/", so "</script>" cannot appear.
        payload.toString()
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

    /** Builds the report, returns the written HTML file. */
    fun build(ctx: Context, sources: List<JSONObject>, context: JSONObject, baseName: String): File {
        val all = ArrayList<JSONObject>()
        val downloads = JSONArray()
        val infos = JSONArray()
        sources.forEachIndexed { si, src ->
            infos.put(src.getJSONObject("info"))
            val es = src.getJSONArray("entries")
            for (i in 0 until es.length()) all.add(es.getJSONObject(i).put("src", si))
            val ds = src.optJSONArray("downloads") ?: JSONArray()
            for (i in 0 until ds.length()) downloads.put(ds.getJSONObject(i).put("_src", si))
        }
        all.sortByDescending { it.opt("time_utc") as? String ?: "" }
        all.forEachIndexed { n, e -> e.put("n", n) }

        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        val payload = JSONObject()
            .put("tool", JSONObject().put("name", "chromehisto-android").put("version", version))
            .put("generated_utc", HistorySources.iso(Instant.now()))
            .put("context", context)
            .put("sources", infos)
            .put("entries", JSONArray(all))
            .put("downloads", downloads)
        val data = embed(payload)
        val dataHash = HistorySources.sha256(data.toByteArray(Charsets.UTF_8))
        val template = ctx.assets.open("template.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val html = template.replace("__DATA_SHA256__", dataHash).replace("__DATA_JSON__", data)

        val out = File(reportsDir(ctx), "$baseName.html")
        out.writeText(html, Charsets.UTF_8)
        val fileHash = HistorySources.sha256(html.toByteArray(Charsets.UTF_8))
        File(reportsDir(ctx), "$baseName.html.sha256").writeText("$fileHash  ${out.name}\n")
        File(reportsDir(ctx), "$baseName.meta.json").writeText(JSONObject()
            .put("file", out.name)
            .put("records", all.size)
            .put("downloads", downloads.length())
            .put("sources", JSONArray((0 until infos.length()).map { infos.getJSONObject(it).optString("label") }))
            .put("created_utc", HistorySources.iso(Instant.now()))
            .put("data_sha256", dataHash)
            .put("file_sha256", fileHash)
            .toString())
        return out
    }

    fun list(ctx: Context): JSONArray {
        val arr = JSONArray()
        reportsDir(ctx).listFiles { f -> f.name.endsWith(".meta.json") }
            ?.sortedByDescending { it.lastModified() }
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
