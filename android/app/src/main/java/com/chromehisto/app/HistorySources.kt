package com.chromehisto.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Kotlin port of chromehisto/sources.py. Produces the same JSON structure the
 * shared report template expects: a source = {info, entries, downloads}.
 */
object HistorySources {

    // ------------------------------------------------------------- time
    private const val WEBKIT_EPOCH_S = -11644473600L
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'")
        .withZone(ZoneOffset.UTC)

    fun iso(i: Instant?): Any = if (i == null) JSONObject.NULL else ISO.format(i)

    fun webkit(v: Long?): Instant? =
        if (v == null || v <= 0) null
        else Instant.ofEpochSecond(WEBKIT_EPOCH_S).plus(v, ChronoUnit.MICROS)

    fun unixUs(v: Long): Instant =
        Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000L), Math.floorMod(v, 1_000_000L) * 1000)

    fun unixMs(v: Long): Instant = Instant.ofEpochMilli(v)

    /** Heuristic decode of an integer that looks like a timestamp. */
    fun guess(v: Long): Pair<Instant, String>? = when (v) {
        in 12_600_000_000_000_000L..15_800_000_000_000_000L -> webkit(v)!! to "WebKit µs since 1601"
        in 978_307_200_000_000L..4_102_444_800_000_000L -> unixUs(v) to "Unix µs"
        in 978_307_200_000L..4_102_444_800_000L -> unixMs(v) to "Unix ms"
        in 978_307_200L..4_102_444_800L -> Instant.ofEpochSecond(v) to "Unix s"
        else -> null
    }

    // ------------------------------------------------------------- decoding
    private val CORE = mapOf(
        0 to "LINK", 1 to "TYPED", 2 to "AUTO_BOOKMARK", 3 to "AUTO_SUBFRAME",
        4 to "MANUAL_SUBFRAME", 5 to "GENERATED", 6 to "AUTO_TOPLEVEL",
        7 to "FORM_SUBMIT", 8 to "RELOAD", 9 to "KEYWORD", 10 to "KEYWORD_GENERATED",
    )
    private val QUALIFIERS = listOf(
        0x00800000L to "BLOCKED", 0x01000000L to "FORWARD_BACK",
        0x02000000L to "FROM_ADDRESS_BAR", 0x04000000L to "HOME_PAGE",
        0x08000000L to "FROM_API", 0x10000000L to "CHAIN_START",
        0x20000000L to "CHAIN_END", 0x40000000L to "CLIENT_REDIRECT",
        0x80000000L to "SERVER_REDIRECT",
    )
    private val VISIT_SOURCES = mapOf(
        0L to "SYNCED (visit came from another device via Chrome Sync)",
        1L to "BROWSED (visit made in this profile on this device)",
        2L to "EXTENSION (added by an extension)",
        3L to "FIREFOX_IMPORTED", 4L to "IE_IMPORTED", 5L to "SAFARI_IMPORTED",
        6L to "OS_MIGRATION_IMPORTED",
    )
    private val BROWSER_TYPES = mapOf(0L to "UNKNOWN", 1L to "TABBED", 2L to "POPUP",
        3L to "CUSTOM_TAB", 4L to "AUTH_TAB")
    private val DOWNLOAD_STATES = mapOf(0L to "IN_PROGRESS", 1L to "COMPLETE",
        2L to "CANCELLED", 3L to "INTERRUPTED (legacy)", 4L to "INTERRUPTED")

    data class Transition(val raw: Long, val core: String, val qualifiers: List<String>)

    fun decodeTransition(value: Long): Transition {
        val v = value and 0xFFFFFFFFL
        val core = CORE[(v and 0xFF).toInt()] ?: "UNKNOWN(${v and 0xFF})"
        return Transition(v, core, QUALIFIERS.filter { v and it.first != 0L }.map { it.second })
    }

    fun urlParts(url: String): JSONObject {
        val o = JSONObject()
        try {
            val u = Uri.parse(url)
            o.put("scheme", u.scheme ?: "")
            o.put("host", u.host ?: "")
            o.put("port", if (u.port >= 0) u.port else JSONObject.NULL)
            o.put("username", u.userInfo?.substringBefore(':') ?: JSONObject.NULL)
            o.put("path", u.path ?: "")
            val params = JSONArray()
            u.encodedQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach { part ->
                val k = part.substringBefore('=')
                val v = if ('=' in part) part.substringAfter('=') else ""
                params.put(JSONArray().put(decode(k)).put(decode(v)))
            }
            o.put("query_params", params)
            o.put("fragment", u.fragment ?: "")
        } catch (e: Exception) {
            o.put("scheme", "").put("host", "").put("port", JSONObject.NULL)
                .put("username", JSONObject.NULL).put("path", "")
                .put("query_params", JSONArray()).put("fragment", "")
        }
        return o
    }

    private fun decode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun entry(id: Any?, url: String, title: String, time: Instant?, source: String,
                      groups: JSONObject, meta: JSONObject): JSONObject =
        JSONObject()
            .put("id", id ?: JSONObject.NULL)
            .put("url", url)
            .put("title", title)
            .put("time_utc", iso(time))
            .put("source", source)
            .put("meta", meta)
            .put("groups", groups)
            .put("url_parts", urlParts(url))

    // ------------------------------------------------------------- SQLite
    private fun cursorValue(c: Cursor, i: Int): Any = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString("") { "%02x".format(it) }
        else -> c.getString(i)
    }

    private fun rows(db: SQLiteDatabase, sql: String): List<LinkedHashMap<String, Any>> {
        val out = ArrayList<LinkedHashMap<String, Any>>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val m = LinkedHashMap<String, Any>()
                for (i in 0 until c.columnCount) m[c.getColumnName(i)] = cursorValue(c, i)
                out.add(m)
            }
        }
        return out
    }

    private fun hasTable(db: SQLiteDatabase, t: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(t))
            .use { it.moveToFirst() }

    private fun columns(db: SQLiteDatabase, t: String): List<String> =
        rows(db, "PRAGMA table_info($t)").map { it["name"].toString() }

    private fun Any?.asLong(): Long? = (this as? Number)?.toLong()

    const val ATTRIBUTION_NOTE_DB = "Chrome stores no per-visit user name. A visit is attributable " +
        "to the owner of this profile (see Profile identity); SYNCED visits came from another " +
        "device on the same account."

    /**
     * Read a Chrome History database into [store]. [copy] must be a private,
     * writable copy of the evidence file; the original is never opened by SQLite.
     * Rows are streamed, so databases of any size fit in memory.
     */
    fun readHistoryDb(copy: File, label: String, facts: JSONObject, identity: JSONObject?,
                      store: EntryStore, src: Int): SourceResult {
        val db = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            return readDb(db, label, facts, identity, store, src)
        } finally {
            db.close()
        }
    }

    private fun readDb(db: SQLiteDatabase, label: String, facts: JSONObject, identity: JSONObject?,
                       store: EntryStore, src: Int): SourceResult {
        val vcols = columns(db, "visits")
        val ucols = columns(db, "urls")
        val sel = ArrayList<String>()
        sel += vcols.map { "v.$it AS v_$it" }
        sel += ucols.map { "u.$it AS u_$it" }
        var from = "visits v LEFT JOIN urls u ON u.id = v.url"
        // Optional per-visit tables, joined generically so new Chrome columns show up.
        val extras = LinkedHashMap<String, List<String>>()   // alias -> columns
        for ((alias, table, key) in listOf(Triple("ca", "context_annotations", "visit_id"),
                Triple("cn", "content_annotations", "visit_id"), Triple("vs", "visit_source", "id"))) {
            if (!hasTable(db, table)) continue
            val cols = columns(db, table).filter { it != key }
            from += " LEFT JOIN $table $alias ON $alias.$key = v.id"
            sel += cols.map { "$alias.$it AS ${alias}_$it" }
            sel += "$alias.$key AS ${alias}__row"
            extras[alias] = cols
        }

        val searches = HashMap<Long, MutableList<String>>()
        if (hasTable(db, "keyword_search_terms")) {
            for (r in rows(db, "SELECT url_id, term FROM keyword_search_terms")) {
                val uid = r["url_id"].asLong() ?: continue
                searches.getOrPut(uid) { ArrayList() }.add(r["term"].toString())
            }
        }
        val clusters = HashMap<Long, MutableList<String>>()
        if (hasTable(db, "clusters_and_visits") && hasTable(db, "clusters")) {
            val hasLabel = "label" in columns(db, "clusters")
            val q = "SELECT cv.visit_id AS vid, cv.cluster_id AS cid" + (if (hasLabel) ", c.label AS label" else "") +
                " FROM clusters_and_visits cv LEFT JOIN clusters c ON c.cluster_id = cv.cluster_id"
            for (r in rows(db, q)) {
                val vid = r["vid"].asLong() ?: continue
                val lab = r["label"]
                clusters.getOrPut(vid) { ArrayList() }
                    .add(r["cid"].toString() + if (lab is String && lab.isNotEmpty()) " — $lab" else "")
            }
        }
        val urlByVisit = HashMap<Long, String>()
        db.rawQuery("SELECT v.id, u.url FROM visits v LEFT JOIN urls u ON u.id = v.url", null).use { c ->
            while (c.moveToNext()) urlByVisit[c.getLong(0)] = if (c.isNull(1)) "" else c.getString(1)
        }

        db.rawQuery("SELECT ${sel.joinToString(", ")} FROM $from", null).use { c ->
            val names = Array(c.columnCount) { c.getColumnName(it) }
            val d = HashMap<String, Any>()
            while (c.moveToNext()) {
                d.clear()
                for (i in names.indices) d[names[i]] = cursorValue(c, i)
                val e = dbEntry(d, vcols, ucols, extras, label, urlByVisit, searches, clusters) ?: continue
                store.add(src, e.opt("time_utc") as? String, e.put("src", src).toString())
            }
        }

        val info = JSONObject()
            .put("kind", "Chrome History SQLite database")
            .put("label", label)
            .put("evidence_file", facts)
            .put("attribution_note", ATTRIBUTION_NOTE_DB)
        val counts = JSONObject()
        for (t in listOf("urls", "visits", "downloads", "keyword_search_terms")) {
            if (hasTable(db, t)) counts.put(t, rows(db, "SELECT COUNT(*) AS n FROM $t")[0]["n"])
        }
        info.put("row_counts", counts)
        info.put("visit_id_gaps", idGaps(db))
        if (hasTable(db, "meta")) {
            val m = JSONObject()
            for (r in rows(db, "SELECT key, value FROM meta")) m.put(r["key"].toString(), r["value"])
            info.put("db_meta_table", m)
        }
        if (identity != null) info.put("profile_identity", identity)
        return SourceResult(info, downloads(db))
    }

    private fun dbEntry(d: Map<String, Any>, vcols: List<String>, ucols: List<String>,
                        extras: Map<String, List<String>>, label: String,
                        urlByVisit: Map<Long, String>, searches: Map<Long, List<String>>,
                        clusters: Map<Long, List<String>>): JSONObject? {
        val vid = d["v_id"].asLong() ?: return null
        val whenI = webkit(d["v_visit_time"].asLong())
        val groups = JSONObject()

        val visit = JSONObject()
        visit.put("visit_id", vid)
        visit.put("visit_time_raw (WebKit µs)", d["v_visit_time"])
        visit.put("visit_time_utc", iso(whenI))
        var durationS: Any = JSONObject.NULL
        d["v_visit_duration"].asLong()?.let {
            visit.put("visit_duration_raw (µs)", it)
            durationS = Math.round(it / 1000.0) / 1000.0
            visit.put("visit_duration_seconds", durationS)
        }
        val tr = d["v_transition"].asLong()?.let { decodeTransition(it) }
        if (tr != null) {
            visit.put("transition_raw", tr.raw)
            visit.put("transition_core", tr.core)
            visit.put("transition_qualifiers", tr.qualifiers.joinToString(", ").ifEmpty { "—" })
        }
        for (c in vcols) {
            if (c in setOf("id", "url", "visit_time", "visit_duration", "transition")) continue
            visit.put(c, d["v_$c"])
        }
        groups.put("Visit (visits table)", visit)

        val nav = JSONObject()
        d["v_from_visit"].asLong()?.takeIf { it != 0L }?.let {
            nav.put("referring_visit_id (from_visit)", it)
            nav.put("referring_url", urlByVisit[it] ?: "(visit not in DB — expired or deleted)")
        }
        d["v_opener_visit"].asLong()?.takeIf { it != 0L }?.let {
            nav.put("opener_visit_id", it)
            nav.put("opener_url", urlByVisit[it] ?: "(visit not in DB — expired or deleted)")
        }
        (d["v_external_referrer_url"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            nav.put("external_referrer_url", it)
        }
        val uid = d["u_id"].asLong()
        searches[uid]?.let { nav.put("search_terms_for_this_url", JSONArray(it)) }
        clusters[vid]?.let { nav.put("journeys_clusters", JSONArray(it)) }
        if (nav.length() > 0) groups.put("Navigation chain", nav)

        val urlg = JSONObject().put("url_id", uid ?: JSONObject.NULL)
        for (c in ucols) {
            if (c in setOf("id", "url", "title")) continue
            urlg.put(c, d["u_$c"])
            if (c == "last_visit_time") urlg.put("last_visit_time_utc", iso(webkit(d["u_$c"].asLong())))
        }
        groups.put("URL record (urls table)", urlg)

        fun extra(alias: String): JSONObject? {
            val cols = extras[alias] ?: return null
            if (d["${alias}__row"] == JSONObject.NULL) return null
            val o = JSONObject()
            for (c in cols) o.put(c, d["${alias}_$c"])
            return o
        }
        val origin = JSONObject()
        val vs = extra("vs")
        if (vs != null) {
            val s = vs.opt("source").asLong()
            origin.put("visit_source_raw", s ?: JSONObject.NULL)
            origin.put("visit_source", VISIT_SOURCES[s] ?: "UNKNOWN($s)")
        } else {
            origin.put("visit_source", "BROWSED (no visit_source row; Chrome omits it for local visits)")
        }
        (d["v_originator_cache_guid"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            origin.put("originating_sync_device_guid", it)
        }
        groups.put("Origin of record", origin)
        extra("ca")?.let { ca ->
            ca.opt("browser_type").asLong()?.let { ca.put("browser_type_decoded", BROWSER_TYPES[it] ?: "?") }
            for (k in listOf("duration_since_last_visit", "total_foreground_duration")) {
                ca.opt(k).asLong()?.takeIf { it >= 0 }?.let { ca.put("${k}_seconds", Math.round(it / 1000.0) / 1000.0) }
            }
            groups.put("Tab / window context (context_annotations)", ca)
        }
        extra("cn")?.let { groups.put("Page content (content_annotations)", it) }
        groups.put("Attribution", JSONObject().put("recorded_by_profile", label))

        val meta = JSONObject()
            .put("transition", tr?.core ?: "")
            .put("duration_s", durationS)
            .put("visit_count", d["u_visit_count"] ?: JSONObject.NULL)
            .put("typed_count", d["u_typed_count"] ?: JSONObject.NULL)
            .put("origin", origin.getString("visit_source").substringBefore(' '))
        return entry(vid, d["u_url"] as? String ?: "", d["u_title"] as? String ?: "", whenI, label, groups, meta)
    }

    private fun idGaps(db: SQLiteDatabase, limit: Int = 200): JSONObject {
        val ids = ArrayList<Long>()
        db.rawQuery("SELECT id FROM visits ORDER BY id", null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        val ranges = JSONArray()
        var missing = 0L
        for (i in 0 until ids.size - 1) {
            val a = ids[i]; val b = ids[i + 1]
            if (b - a > 1) {
                missing += b - a - 1
                if (ranges.length() < limit) ranges.put(JSONArray().put(a + 1).put(b - 1))
            }
        }
        return JSONObject()
            .put("first_id", ids.firstOrNull() ?: JSONObject.NULL)
            .put("last_id", ids.lastOrNull() ?: JSONObject.NULL)
            .put("missing_ids_total", missing)
            .put("ranges", ranges)
            .put("ranges_truncated", ranges.length() >= limit)
            .put("interpretation", "Missing visit ids mean rows were removed after they " +
                "were written (manual deletion, Clear browsing data, or automatic expiry of old history).")
    }

    private fun downloads(db: SQLiteDatabase): JSONArray {
        val out = JSONArray()
        if (!hasTable(db, "downloads")) return out
        val chains = HashMap<Long, JSONArray>()
        if (hasTable(db, "downloads_url_chains")) {
            for (r in rows(db, "SELECT id, url FROM downloads_url_chains ORDER BY id, chain_index")) {
                chains.getOrPut(r["id"].asLong() ?: continue) { JSONArray() }.put(r["url"])
            }
        }
        for (r in rows(db, "SELECT * FROM downloads ORDER BY start_time DESC")) {
            val o = JSONObject(r as Map<*, *>)
            for (k in listOf("start_time", "end_time", "last_access_time")) {
                if (r.containsKey(k)) o.put("${k}_utc", iso(webkit(r[k].asLong())))
            }
            r["state"].asLong()?.let { o.put("state_decoded", DOWNLOAD_STATES[it] ?: "?") }
            o.put("url_chain", chains[r["id"].asLong()] ?: JSONArray())
            out.put(o)
        }
        return out
    }

    // ------------------------------------------------------------- Profile identity
    fun profileIdentity(profileName: String, localState: String?, prefs: String?): JSONObject {
        val ident = JSONObject().put("profile_directory", profileName)
        try {
            localState?.let {
                val info = JSONObject(it).optJSONObject("profile")?.optJSONObject("info_cache")
                    ?.optJSONObject(profileName)
                if (info != null) {
                    for (k in listOf("name", "gaia_name", "gaia_given_name", "user_name",
                            "is_consented_primary_account", "hosted_domain", "active_time")) {
                        if (info.has(k)) ident.put("local_state.$k", info.get(k))
                    }
                }
            }
        } catch (e: Exception) { ident.put("local_state_error", e.toString()) }
        try {
            prefs?.let {
                val p = JSONObject(it)
                p.optJSONObject("profile")?.let { prof ->
                    if (prof.has("name")) ident.put("preferences.profile.name", prof.get("name"))
                    if (prof.has("created_by_version")) ident.put("preferences.profile.created_by_version", prof.get("created_by_version"))
                    prof.optString("creation_time").toLongOrNull()?.let {
                        ident.put("preferences.profile.creation_time_utc", iso(webkit(it)))
                    }
                }
                p.optJSONArray("account_info")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        for (k in listOf("email", "full_name", "given_name", "hd", "locale")) {
                            val v = a.optString(k)
                            if (v.isNotEmpty()) ident.put("account_info[$i].$k", v)
                        }
                    }
                }
                p.optJSONObject("sync")?.optString("last_synced_time")?.toLongOrNull()?.let {
                    ident.put("sync.last_synced_time_utc", iso(webkit(it)))
                }
            }
        } catch (e: Exception) { ident.put("preferences_error", e.toString()) }
        return ident
    }

    // ------------------------------------------------------------- Takeout JSON
    private val URL_KEYS = listOf("url", "virtual_url", "original_request_url")
    private val TIME_KEYS = listOf<Pair<String, (Long) -> Instant>>(
        "time_usec" to ::unixUs, "timestamp_msec" to ::unixMs,
        "last_active_time_unix_epoch_millis" to ::unixMs, "timestamp" to ::unixMs)

    const val ATTRIBUTION_NOTE_TAKEOUT = "Takeout history is tied to the Google account that " +
        "requested the export. Records with a client_id/session tag identify the originating " +
        "Chrome installation, not a person's name."

    private fun urlOf(o: JSONObject): String? =
        URL_KEYS.firstNotNullOfOrNull { k -> (o.opt(k) as? String)?.takeIf { it.isNotEmpty() } }

    private fun annotate(v: Any?): Any? {
        val n = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is String -> if (v.isNotEmpty() && v.all { it.isDigit() } && v.length < 19) v.toLong() else null
            else -> null
        } ?: return v
        val g = guess(n) ?: return v
        return "$v   ⟶ ${iso(g.first)} (decoded as ${g.second} — heuristic)"
    }

    private fun recordTime(node: JSONObject): Pair<Instant, String>? {
        for ((k, conv) in TIME_KEYS) {
            val v = node.opt(k) ?: continue
            val l = (v as? Number)?.toLong() ?: v.toString().toLongOrNull() ?: continue
            return conv(l) to k
        }
        return null
    }

    /** Builds the report entry for one Takeout record (same layout as sources.py). */
    fun takeoutEntry(node: JSONObject, path: String, ctx: List<Pair<String, JSONObject>>,
                     n: Int, label: String): JSONObject {
        val url = urlOf(node) ?: ""
        val t = recordTime(node)
        val groups = JSONObject()
        val rec = JSONObject().put("json_path", path)
        for (k in node.keys()) {
            val v = node.get(k)
            rec.put(k, when {
                v is JSONObject || v is JSONArray -> v.toString()
                k == "url" || k == "title" -> v
                else -> annotate(v)
            })
        }
        if (t != null) rec.put("(decoded) time_utc from ${t.second}", iso(t.first))
        val trRaw = node.opt("page_transition")
        var trName: String = trRaw?.toString() ?: ""
        if (trRaw is Number) {
            val d = decodeTransition(trRaw.toLong())
            rec.put("page_transition_decoded", d.core + " " + d.qualifiers.joinToString(","))
            trName = d.core
        }
        groups.put("Takeout record", rec)
        for ((cpath, sc) in ctx) {
            val o = JSONObject()
            for (k in sc.keys()) o.put(k, annotate(sc.get(k)))
            groups.put("Container: $cpath", o)
        }
        groups.put("Attribution", JSONObject().put("recorded_by", label))
        val meta = JSONObject()
            .put("transition", trName)
            .put("duration_s", JSONObject.NULL)
            .put("visit_count", JSONObject.NULL)
            .put("typed_count", JSONObject.NULL)
            .put("origin", "TAKEOUT")
            .put("http_status", node.opt("http_status_code") ?: JSONObject.NULL)
        val id = node.opt("unique_id") ?: node.opt("id") ?: (n + 1)
        return entry(id, url, node.optString("title", ""), t?.first, label, groups, meta)
    }

    /**
     * Streaming Takeout reader. Walks the JSON with [JsonReader] so files far
     * larger than the app's heap can be imported; every object carrying a URL
     * is written to the [EntryStore] as it is found, together with the ids of
     * its enclosing containers. Container scalars (session tag, tab type ...)
     * are only complete once the container closes, so they are attached in
     * [result]'s finish step.
     */
    class TakeoutReader(private val label: String, private val src: Int, private val store: EntryStore) {
        // Only containers that actually hold records are remembered, so memory
        // stays proportional to the number of containers, not of records.
        private var nextId = 0
        private val containers = HashMap<Int, Pair<String, JSONObject>>()
        private val topKeys = JSONArray()
        var records = 0
            private set

        fun parse(input: Reader) {
            val jr = JsonReader(input)
            jr.isLenient = true
            readValue(jr, "", IntArray(0), 0)
        }

        private fun readValue(jr: JsonReader, path: String, ctx: IntArray, depth: Int) {
            when (jr.peek()) {
                JsonToken.BEGIN_OBJECT -> readObject(jr, path, ctx, depth)
                JsonToken.BEGIN_ARRAY -> {
                    if (depth == 0) topKeys.put("(array)")
                    jr.beginArray()
                    var i = 0
                    while (jr.hasNext()) readValue(jr, "$path[${i++}]", ctx, depth + 1)
                    jr.endArray()
                }
                else -> jr.skipValue()
            }
        }

        private fun readObject(jr: JsonReader, path: String, ctx: IntArray, depth: Int) {
            val id = nextId++
            val childCtx = ctx + id
            val recordsBefore = records
            val node = JSONObject()
            val scalars = JSONObject()
            val walked = ArrayList<String>()
            var isRecord = false
            jr.beginObject()
            while (jr.hasNext()) {
                val k = jr.nextName()
                if (depth == 0) topKeys.put(k)
                when (jr.peek()) {
                    JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY ->
                        if (isRecord) node.put(k, readTree(jr))
                        else { readValue(jr, if (path.isEmpty()) k else "$path.$k", childCtx, depth + 1); walked.add(k) }
                    else -> {
                        val v = readScalar(jr)
                        node.put(k, v); scalars.put(k, v)
                        if (k in URL_KEYS && v is String && v.isNotEmpty()) isRecord = true
                    }
                }
            }
            jr.endObject()
            if (scalars.length() > 0 && records > recordsBefore) containers[id] = path.ifEmpty { "$" } to scalars
            if (isRecord) {
                for (k in walked) node.put(k, "(nested value listed as separate records)")
                val t = recordTime(node)
                val raw = JSONObject().put("node", node).put("path", path)
                    .put("ctx", JSONArray(ctx.toList())).put("i", records)
                store.add(src, t?.let { iso(it.first) as String }, raw.toString())
                records++
            }
        }

        private fun readScalar(jr: JsonReader): Any = when (jr.peek()) {
            JsonToken.STRING -> jr.nextString()
            JsonToken.NUMBER -> jr.nextString().let { s -> s.toLongOrNull() ?: s.toDoubleOrNull() ?: s }
            JsonToken.BOOLEAN -> jr.nextBoolean()
            JsonToken.NULL -> { jr.nextNull(); JSONObject.NULL }
            else -> { jr.skipValue(); JSONObject.NULL }
        }

        private fun readTree(jr: JsonReader): Any = when (jr.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val o = JSONObject()
                jr.beginObject()
                while (jr.hasNext()) { val k = jr.nextName(); o.put(k, readTree(jr)) }
                jr.endObject(); o
            }
            JsonToken.BEGIN_ARRAY -> {
                val a = JSONArray()
                jr.beginArray()
                while (jr.hasNext()) a.put(readTree(jr))
                jr.endArray(); a
            }
            else -> readScalar(jr)
        }

        fun result(facts: JSONObject): SourceResult {
            val info = JSONObject()
                .put("kind", "Google Takeout JSON")
                .put("label", label)
                .put("evidence_file", facts)
                .put("top_level_keys", topKeys)
                .put("records_with_url", records)
                .put("attribution_note", ATTRIBUTION_NOTE_TAKEOUT)
            return SourceResult(info) { raw ->
                val r = JSONObject(raw)
                val ids = r.getJSONArray("ctx")
                val ctx = (0 until ids.length()).mapNotNull { j ->
                    val cid = ids.getInt(j)
                    containers[cid]
                }
                takeoutEntry(r.getJSONObject("node"), r.getString("path"), ctx, r.getInt("i"), label)
                    .put("src", src).toString()
            }
        }
    }
}
