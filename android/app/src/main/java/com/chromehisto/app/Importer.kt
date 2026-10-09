package com.chromehisto.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile

/** Turns picked files (or a rooted device's Chrome data) into report sources. */
class Importer(private val ctx: Context, private val progress: (String) -> Unit) {

    private val work = File(ctx.cacheDir, "import").apply { mkdirs() }

    fun importUris(uris: List<Uri>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        uris.forEachIndexed { i, uri ->
            val name = displayName(uri) ?: "file$i"
            progress("Copying $name …")
            val copy = File(work, "in_$i")
            val facts = copyWithFacts(uri, copy, name)
            out.addAll(importFile(copy, name, facts))
        }
        work.listFiles()?.forEach { it.delete() }
        return out
    }

    private fun importFile(copy: File, name: String, facts: JSONObject): List<JSONObject> {
        val head = copy.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        return when {
            String(head, Charsets.ISO_8859_1).startsWith("SQLite format 3") -> {
                progress("Reading Chrome History database $name …")
                listOf(HistorySources.readHistoryDb(copy, name, facts, null))
            }
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> importZip(copy, name, facts)
            else -> {
                progress("Parsing $name …")
                val res = HistorySources.readTakeoutJson(copy.readText(Charsets.UTF_8), name, facts)
                if (res.getJSONArray("entries").length() == 0) throw IllegalArgumentException("$name contains no history records")
                listOf(res)
            }
        }
    }

    private fun importZip(copy: File, name: String, facts: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        ZipFile(copy).use { zf ->
            for (ze in zf.entries()) {
                if (ze.isDirectory) continue
                val low = ze.name.lowercase()
                val base = low.substringAfterLast('/')
                val isJson = low.endsWith(".json") && "chrome" in low
                val isDb = base == "history"
                if (!isJson && !isDb) continue
                progress("Reading ${ze.name} …")
                val bytes = zf.getInputStream(ze).use { it.readBytes() }
                val member = JSONObject(facts.toString())
                    .put("zip_member", ze.name)
                    .put("zip_member_sha256", HistorySources.sha256(bytes))
                    .put("zip_member_mtime_utc", HistorySources.iso(Instant.ofEpochMilli(ze.time)))
                val label = "$name!${ze.name}"
                try {
                    if (isDb && String(bytes, 0, minOf(15, bytes.size), Charsets.ISO_8859_1) == "SQLite format 3") {
                        val dbFile = File(work, "zip_history").apply { writeBytes(bytes) }
                        out.add(HistorySources.readHistoryDb(dbFile, label, member, null))
                        dbFile.delete()
                    } else if (isJson) {
                        val res = HistorySources.readTakeoutJson(String(bytes, Charsets.UTF_8), label, member)
                        if (res.getJSONArray("entries").length() > 0) out.add(res)
                    }
                } catch (e: Exception) {
                    progress("Skipped ${ze.name}: ${e.message}")
                }
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("No Chrome history found in $name")
        return out
    }

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null } ?: uri.lastPathSegment

    private fun copyWithFacts(uri: Uri, dst: File, name: String): JSONObject {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            dst.outputStream().use { outs ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); outs.write(buf, 0, n); size += n
                }
            }
        }
        val facts = JSONObject()
            .put("path", name)
            .put("content_uri", uri.toString())
            .put("size_bytes", size)
            .put("sha256", md.digest().joinToString("") { "%02x".format(it) })
            .put("imported_utc", HistorySources.iso(Instant.now()))
            .put("handling", "Read through Android's document picker into a private copy; the original was not modified.")
        try {
            ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    facts.put("modified_utc", HistorySources.iso(Instant.ofEpochMilli(it.getLong(0))))
                }
            }
        } catch (e: Exception) { /* provider does not expose it */ }
        return facts
    }

    // ------------------------------------------------------------- root
    private val chromePackages = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "com.chrome.canary", "org.chromium.chrome", "com.microsoft.emmx", "com.brave.browser")

    private fun su(cmd: String): Pair<Int, String> {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    /** Reads Chrome's own History DB directly. Only possible on rooted devices. */
    fun importRoot(): List<JSONObject> {
        progress("Requesting root access …")
        val (rc, _) = try { su("id") } catch (e: Exception) {
            throw IllegalStateException("This phone is not rooted. Android does not let other apps read " +
                "Chrome's history; use Google Takeout instead.")
        }
        if (rc != 0) throw IllegalStateException("Root access was denied.")
        val out = ArrayList<JSONObject>()
        val uid = android.os.Process.myUid()
        for (pkg in chromePackages) {
            val base = "/data/data/$pkg/app_chrome"
            val dir = File(work, "root_$pkg").apply { mkdirs() }
            val (ok, _) = su("[ -f '$base/Default/History' ]")
            if (ok != 0) continue
            progress("Copying $pkg history …")
            val stat = su("stat -c '%s|%Y|%X|%Z|%U' '$base/Default/History'").second.trim()
            su("for f in History History-wal History-journal Preferences; do " +
                "[ -f '$base/Default/'\$f ] && cp '$base/Default/'\$f '${dir.path}/'\$f; done; " +
                "[ -f '$base/Local State' ] && cp '$base/Local State' '${dir.path}/Local State'; " +
                "chown -R $uid:$uid '${dir.path}'; chmod -R 600 '${dir.path}'/*")
            val db = File(dir, "History")
            if (!db.isFile) continue
            val parts = stat.split('|')
            val facts = JSONObject()
                .put("path", "$base/Default/History")
                .put("sha256", HistorySources.sha256(db))
                .put("size_bytes", db.length())
                .put("handling", "Copied with root (cp) into app storage; the original was not opened.")
            if (parts.size == 5) {
                parts[1].toLongOrNull()?.let { facts.put("modified_utc", HistorySources.iso(Instant.ofEpochSecond(it))) }
                parts[2].toLongOrNull()?.let { facts.put("accessed_utc", HistorySources.iso(Instant.ofEpochSecond(it))) }
                parts[3].toLongOrNull()?.let { facts.put("metadata_changed_utc", HistorySources.iso(Instant.ofEpochSecond(it))) }
                facts.put("file_owner", parts[4])
            }
            val ident = HistorySources.profileIdentity("Default",
                File(dir, "Local State").takeIf { it.isFile }?.readText(),
                File(dir, "Preferences").takeIf { it.isFile }?.readText())
            ident.put("android_package", pkg)
            progress("Reading $pkg history …")
            out.add(HistorySources.readHistoryDb(db, "$pkg / Default", facts, ident))
            dir.deleteRecursively()
        }
        if (out.isEmpty()) throw IllegalStateException("No Chrome history database found on this device.")
        return out
    }
}
