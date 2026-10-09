package com.chromehisto.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile

/** Turns picked files (or a rooted device's Chrome data) into report sources. */
class Importer(private val ctx: Context, private val store: EntryStore,
               private val progress: (String) -> Unit) {

    private val work = File(ctx.cacheDir, "import").apply { mkdirs() }

    /** Sources imported so far; a source's index is its position in this list. */
    val results = ArrayList<SourceResult>()

    fun importUris(uris: List<Uri>) {
        uris.forEachIndexed { i, uri ->
            val name = displayName(uri) ?: "file$i"
            progress("Copying $name …")
            val copy = File(work, "in_$i")
            try {
                val facts = copyWithFacts(uri, copy, name)
                importFile(copy, name, facts)
            } finally {
                copy.delete()
            }
        }
    }

    private fun readTakeout(input: InputStream, label: String): HistorySources.TakeoutReader {
        val r = HistorySources.TakeoutReader(label, results.size, store)
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
        reader.mark(1)
        if (reader.read() != 0xFEFF) reader.reset()
        r.parse(reader)
        return r
    }

    private fun importFile(copy: File, name: String, facts: JSONObject) {
        val head = copy.inputStream().use { st -> ByteArray(16).also { st.read(it) } }
        when {
            String(head, Charsets.ISO_8859_1).startsWith("SQLite format 3") -> {
                progress("Reading Chrome History database $name …")
                results.add(HistorySources.readHistoryDb(copy, name, facts, null, store, results.size))
            }
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> importZip(copy, name, facts)
            else -> {
                progress("Parsing $name …")
                val r = copy.inputStream().use { readTakeout(it, name) }
                if (r.records == 0) throw IllegalArgumentException("$name contains no history records")
                results.add(r.result(facts))
                progress("$name: ${r.records} records")
            }
        }
    }

    private fun importZip(copy: File, name: String, facts: JSONObject) {
        val before = results.size
        ZipFile(copy).use { zf ->
            for (ze in zf.entries()) {
                if (ze.isDirectory) continue
                val low = ze.name.lowercase()
                val base = low.substringAfterLast('/')
                val isJson = low.endsWith(".json") && "chrome" in low
                val isDb = base == "history"
                if (!isJson && !isDb) continue
                progress("Reading ${ze.name} …")
                val label = "$name!${ze.name}"
                val member = JSONObject(facts.toString())
                    .put("zip_member", ze.name)
                    .put("zip_member_mtime_utc", HistorySources.iso(Instant.ofEpochMilli(ze.time)))
                try {
                    val md = MessageDigest.getInstance("SHA-256")
                    if (isDb) {
                        val dbFile = File(work, "zip_history")
                        DigestInputStream(zf.getInputStream(ze), md).use { ins ->
                            dbFile.outputStream().use { ins.copyTo(it, 1 shl 16) }
                        }
                        member.put("zip_member_sha256", md.digest().joinToString("") { "%02x".format(it) })
                        try {
                            val headOk = dbFile.inputStream().use { st -> ByteArray(15).also { st.read(it) } }
                            if (String(headOk, Charsets.ISO_8859_1) == "SQLite format 3") {
                                results.add(HistorySources.readHistoryDb(dbFile, label, member, null, store, results.size))
                            }
                        } finally { dbFile.delete() }
                    } else {
                        val r = DigestInputStream(zf.getInputStream(ze), md).use { ins ->
                            val res = readTakeout(ins, label)
                            val buf = ByteArray(1 shl 16)
                            while (ins.read(buf) >= 0) { /* hash the remainder */ }
                            res
                        }
                        member.put("zip_member_sha256", md.digest().joinToString("") { "%02x".format(it) })
                        if (r.records > 0) {
                            results.add(r.result(member))
                            progress("${ze.name}: ${r.records} records")
                        }
                    }
                } catch (e: Exception) {
                    progress("Skipped ${ze.name}: ${e.message}")
                }
            }
        }
        if (results.size == before) throw IllegalArgumentException("No Chrome history found in $name")
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
    fun importRoot() {
        progress("Requesting root access …")
        val (rc, _) = try { su("id") } catch (e: Exception) {
            throw IllegalStateException("This phone is not rooted. Android does not let other apps read " +
                "Chrome's history; use Google Takeout instead.")
        }
        if (rc != 0) throw IllegalStateException("Root access was denied.")
        val before = results.size
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
            results.add(HistorySources.readHistoryDb(db, "$pkg / Default", facts, ident, store, results.size))
            dir.deleteRecursively()
        }
        if (results.size == before) throw IllegalStateException("No Chrome history database found on this device.")
    }
}
