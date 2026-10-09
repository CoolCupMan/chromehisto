"""Readers for Chrome history sources.

Two kinds of input are supported:

* the local ``History`` SQLite database of a Chrome/Chromium profile, which
  holds the richest per-visit metadata Chrome keeps;
* Google Takeout exports (``BrowserHistory.json``, ``Verlauf.json``, or the
  Takeout ``.zip``), whose layout varies between Takeout versions and is
  therefore parsed generically.

Every record is normalised into a plain dict with a fixed set of top-level keys
(see ``_entry``) plus a ``meta`` dict that carries every other field verbatim,
so nothing Chrome stored is dropped.
"""

import hashlib
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import zipfile
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qsl, urlsplit

WEBKIT_EPOCH = datetime(1601, 1, 1, tzinfo=timezone.utc)

# ui/base/page_transition_types.h
CORE_TRANSITIONS = {
    0: "LINK", 1: "TYPED", 2: "AUTO_BOOKMARK", 3: "AUTO_SUBFRAME",
    4: "MANUAL_SUBFRAME", 5: "GENERATED", 6: "AUTO_TOPLEVEL",
    7: "FORM_SUBMIT", 8: "RELOAD", 9: "KEYWORD", 10: "KEYWORD_GENERATED",
}
TRANSITION_QUALIFIERS = {
    0x00800000: "BLOCKED", 0x01000000: "FORWARD_BACK",
    0x02000000: "FROM_ADDRESS_BAR", 0x04000000: "HOME_PAGE",
    0x08000000: "FROM_API", 0x10000000: "CHAIN_START",
    0x20000000: "CHAIN_END", 0x40000000: "CLIENT_REDIRECT",
    0x80000000: "SERVER_REDIRECT",
}
VISIT_SOURCES = {
    0: "SYNCED (visit came from another device via Chrome Sync)",
    1: "BROWSED (visit made in this profile on this device)",
    2: "EXTENSION (added by an extension)",
    3: "FIREFOX_IMPORTED", 4: "IE_IMPORTED", 5: "SAFARI_IMPORTED",
    6: "OS_MIGRATION_IMPORTED",
}
BROWSER_TYPES = {0: "UNKNOWN", 1: "TABBED", 2: "POPUP", 3: "CUSTOM_TAB",
                 4: "AUTH_TAB"}
DOWNLOAD_STATES = {0: "IN_PROGRESS", 1: "COMPLETE", 2: "CANCELLED",
                   3: "INTERRUPTED (legacy)", 4: "INTERRUPTED"}


# --------------------------------------------------------------------------
# time helpers
# --------------------------------------------------------------------------

def webkit_to_dt(value):
    """Chrome stores times as microseconds since 1601-01-01 UTC."""
    try:
        value = int(value)
    except (TypeError, ValueError):
        return None
    if value <= 0:
        return None
    try:
        return WEBKIT_EPOCH + timedelta(microseconds=value)
    except OverflowError:
        return None


def unix_us_to_dt(value):
    try:
        return datetime.fromtimestamp(int(value) / 1_000_000, tz=timezone.utc)
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def unix_ms_to_dt(value):
    try:
        return datetime.fromtimestamp(int(value) / 1000, tz=timezone.utc)
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def iso(dt):
    return dt.isoformat(timespec="microseconds") if dt else None


def guess_timestamp(value):
    """Best-effort decode of an integer that *looks like* a timestamp.

    Returns (datetime, encoding-name) or (None, None). Used to annotate raw
    Takeout fields such as ``global_id`` which Chrome fills with a WebKit
    timestamp. Purely a heuristic and labelled as such in the report.
    """
    if isinstance(value, bool):
        return None, None
    if isinstance(value, str):
        if not value.isdigit():
            return None, None
        value = int(value)
    if not isinstance(value, int):
        return None, None
    # 2001..2100 expressed in each encoding.
    if 12_600_000_000_000_000 <= value <= 15_800_000_000_000_000:
        return webkit_to_dt(value), "WebKit µs since 1601"
    if 978_307_200_000_000 <= value <= 4_102_444_800_000_000:
        return unix_us_to_dt(value), "Unix µs"
    if 978_307_200_000 <= value <= 4_102_444_800_000:
        return unix_ms_to_dt(value), "Unix ms"
    if 978_307_200 <= value <= 4_102_444_800:
        return datetime.fromtimestamp(value, tz=timezone.utc), "Unix s"
    return None, None


# --------------------------------------------------------------------------
# decoding helpers
# --------------------------------------------------------------------------

def decode_transition(value):
    try:
        value = int(value) & 0xFFFFFFFF
    except (TypeError, ValueError):
        return None
    core = CORE_TRANSITIONS.get(value & 0xFF, "UNKNOWN(%d)" % (value & 0xFF))
    quals = [name for bit, name in TRANSITION_QUALIFIERS.items() if value & bit]
    return {"raw": value, "core": core, "qualifiers": quals}


def url_parts(url):
    try:
        p = urlsplit(url or "")
        return {
            "scheme": p.scheme, "host": p.hostname or "", "port": p.port,
            "username": p.username, "path": p.path,
            "query_params": parse_qsl(p.query, keep_blank_values=True),
            "fragment": p.fragment,
        }
    except ValueError:
        return {"scheme": "", "host": "", "port": None, "username": None,
                "path": "", "query_params": [], "fragment": ""}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def file_facts(path):
    """Filesystem facts about an evidence file (size, times, owner, hash)."""
    st = os.stat(path)
    facts = {
        "path": os.path.abspath(path),
        "size_bytes": st.st_size,
        "sha256": sha256_file(path),
        "modified_utc": iso(datetime.fromtimestamp(st.st_mtime, timezone.utc)),
        "accessed_utc": iso(datetime.fromtimestamp(st.st_atime, timezone.utc)),
        # On Windows st_ctime is creation time, elsewhere inode-change time.
        ("created_utc" if os.name == "nt" else "metadata_changed_utc"):
            iso(datetime.fromtimestamp(st.st_ctime, timezone.utc)),
    }
    birth = getattr(st, "st_birthtime", None)
    if birth:
        facts["created_utc"] = iso(datetime.fromtimestamp(birth, timezone.utc))
    try:
        import pwd  # POSIX only
        facts["file_owner"] = pwd.getpwuid(st.st_uid).pw_name
    except (ImportError, KeyError):
        pass
    return facts


def _entry(**kw):
    base = {
        "id": None, "url": "", "title": "", "time_utc": None,
        "source": "", "meta": {}, "groups": {},
    }
    base.update(kw)
    base["url_parts"] = url_parts(base["url"])
    return base


# --------------------------------------------------------------------------
# local profile discovery
# --------------------------------------------------------------------------

def _user_data_dirs():
    home = os.path.expanduser("~")
    local = os.environ.get("LOCALAPPDATA", os.path.join(home, "AppData", "Local"))
    if sys.platform.startswith("win"):
        cands = {
            "Google Chrome": os.path.join(local, "Google", "Chrome", "User Data"),
            "Google Chrome Beta": os.path.join(local, "Google", "Chrome Beta", "User Data"),
            "Google Chrome Canary": os.path.join(local, "Google", "Chrome SxS", "User Data"),
            "Chromium": os.path.join(local, "Chromium", "User Data"),
            "Microsoft Edge": os.path.join(local, "Microsoft", "Edge", "User Data"),
            "Brave": os.path.join(local, "BraveSoftware", "Brave-Browser", "User Data"),
        }
    elif sys.platform == "darwin":
        sup = os.path.join(home, "Library", "Application Support")
        cands = {
            "Google Chrome": os.path.join(sup, "Google", "Chrome"),
            "Google Chrome Canary": os.path.join(sup, "Google", "Chrome Canary"),
            "Chromium": os.path.join(sup, "Chromium"),
            "Microsoft Edge": os.path.join(sup, "Microsoft Edge"),
            "Brave": os.path.join(sup, "BraveSoftware", "Brave-Browser"),
        }
    else:
        cfg = os.environ.get("XDG_CONFIG_HOME", os.path.join(home, ".config"))
        cands = {
            "Google Chrome": os.path.join(cfg, "google-chrome"),
            "Google Chrome Beta": os.path.join(cfg, "google-chrome-beta"),
            "Chromium": os.path.join(cfg, "chromium"),
            "Microsoft Edge": os.path.join(cfg, "microsoft-edge"),
            "Brave": os.path.join(cfg, "BraveSoftware", "Brave-Browser"),
        }
    return {k: v for k, v in cands.items() if os.path.isdir(v)}


def _read_json(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def profile_identity(profile_dir):
    """Who the profile belongs to, from Chrome's own Local State/Preferences."""
    ident = {"profile_directory": os.path.basename(profile_dir)}
    local_state = _read_json(os.path.join(os.path.dirname(profile_dir), "Local State"))
    if local_state:
        info = (local_state.get("profile", {}).get("info_cache", {})
                .get(os.path.basename(profile_dir), {}))
        for key in ("name", "gaia_name", "gaia_given_name", "user_name",
                    "is_consented_primary_account", "managed_user_id",
                    "hosted_domain", "active_time", "is_ephemeral"):
            if key in info:
                ident["local_state." + key] = info[key]
        if "active_time" in info:
            dt = unix_ms_to_dt(int(float(info["active_time"]) * 1000))
            ident["local_state.active_time_utc"] = iso(dt)
    prefs = _read_json(os.path.join(profile_dir, "Preferences"))
    if prefs:
        prof = prefs.get("profile", {})
        if "name" in prof:
            ident["preferences.profile.name"] = prof["name"]
        if "created_by_version" in prof:
            ident["preferences.profile.created_by_version"] = prof["created_by_version"]
        if "creation_time" in prof:
            ident["preferences.profile.creation_time_utc"] = iso(
                webkit_to_dt(prof["creation_time"]))
        for i, acct in enumerate(prefs.get("account_info", []) or []):
            for key in ("email", "full_name", "given_name", "hd", "locale"):
                if acct.get(key):
                    ident["account_info[%d].%s" % (i, key)] = acct[key]
        sync = prefs.get("sync", {})
        if "last_synced_time" in sync:
            ident["sync.last_synced_time_utc"] = iso(webkit_to_dt(sync["last_synced_time"]))
    # The OS account that owns the profile is part of the path.
    parts = os.path.abspath(profile_dir).replace("\\", "/").split("/")
    for marker in ("Users", "home"):
        if marker in parts and parts.index(marker) + 1 < len(parts):
            ident["os_account_from_path"] = parts[parts.index(marker) + 1]
            break
    return ident


def discover_profiles():
    """Yield (browser, profile_dir) for every profile with a History DB."""
    found = []
    for browser, udd in _user_data_dirs().items():
        for name in sorted(os.listdir(udd)):
            pdir = os.path.join(udd, name)
            if os.path.isfile(os.path.join(pdir, "History")):
                found.append((browser, pdir))
    return found


# --------------------------------------------------------------------------
# SQLite History database
# --------------------------------------------------------------------------

def _columns(con, table):
    return [r[1] for r in con.execute("PRAGMA table_info(%s)" % table)]


def _has_table(con, table):
    return con.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                       (table,)).fetchone() is not None


def _copy_db(path):
    """Copy the DB (and WAL/journal) so a running Chrome's lock is no issue
    and the original evidence file is never opened for writing."""
    tmp = tempfile.mkdtemp(prefix="chromehisto-")
    dst = os.path.join(tmp, "History")
    shutil.copy2(path, dst)
    for suffix in ("-wal", "-journal", "-shm"):
        if os.path.exists(path + suffix):
            shutil.copy2(path + suffix, dst + suffix)
    return tmp, dst


def read_history_db(path, label=None, profile_dir=None):
    """Read a Chrome ``History`` SQLite file. Returns a source dict."""
    facts = file_facts(path)
    tmp, copy = _copy_db(path)
    try:
        con = sqlite3.connect(copy)
        con.row_factory = sqlite3.Row
        return _read_con(con, path, facts, label, profile_dir)
    finally:
        try:
            con.close()
        except Exception:
            pass
        shutil.rmtree(tmp, ignore_errors=True)


def _read_con(con, path, facts, label, profile_dir):
    vcols = _columns(con, "visits")
    ucols = _columns(con, "urls")
    sel = ", ".join(["v.%s AS v_%s" % (c, c) for c in vcols] +
                    ["u.%s AS u_%s" % (c, c) for c in ucols])
    rows = con.execute("SELECT %s FROM visits v LEFT JOIN urls u ON u.id = v.url "
                       "ORDER BY v.visit_time DESC" % sel).fetchall()

    # Optional per-visit tables, attached generically so new Chrome columns
    # show up without code changes.
    per_visit = {}
    for table, key in (("context_annotations", "visit_id"),
                       ("content_annotations", "visit_id"),
                       ("visit_source", "id")):
        if _has_table(con, table):
            for r in con.execute("SELECT * FROM %s" % table):
                d = dict(r)
                vid = d.pop(key)
                per_visit.setdefault(vid, {})[table] = d

    searches = {}
    if _has_table(con, "keyword_search_terms"):
        for r in con.execute("SELECT * FROM keyword_search_terms"):
            searches.setdefault(r["url_id"], []).append(r["term"])

    clusters = {}
    if _has_table(con, "clusters_and_visits") and _has_table(con, "clusters"):
        ccols = _columns(con, "clusters")
        label_col = "label" if "label" in ccols else None
        q = ("SELECT cv.visit_id, cv.cluster_id%s FROM clusters_and_visits cv "
             "LEFT JOIN clusters c ON c.cluster_id = cv.cluster_id"
             % (", c.label" if label_col else ""))
        for r in con.execute(q):
            clusters.setdefault(r[0], []).append(
                "%s%s" % (r[1], (" — " + r[2]) if label_col and r[2] else ""))

    url_by_visit = {r["v_id"]: r["u_url"] for r in rows}

    entries = []
    for r in rows:
        d = dict(r)
        vid = d["v_id"]
        when = webkit_to_dt(d.get("v_visit_time"))
        groups = {}

        visit = {}
        visit["visit_id"] = vid
        visit["visit_time_raw (WebKit µs)"] = d.get("v_visit_time")
        visit["visit_time_utc"] = iso(when)
        dur = d.get("v_visit_duration")
        if dur is not None:
            visit["visit_duration_raw (µs)"] = dur
            visit["visit_duration_seconds"] = round(dur / 1e6, 3)
        tr = decode_transition(d.get("v_transition"))
        if tr:
            visit["transition_raw"] = tr["raw"]
            visit["transition_core"] = tr["core"]
            visit["transition_qualifiers"] = ", ".join(tr["qualifiers"]) or "—"
        for c in vcols:
            if c in ("id", "url", "visit_time", "visit_duration", "transition"):
                continue
            visit[c] = d.get("v_" + c)
        groups["Visit (visits table)"] = visit

        nav = {}
        fv = d.get("v_from_visit")
        if fv:
            nav["referring_visit_id (from_visit)"] = fv
            nav["referring_url"] = url_by_visit.get(fv, "(visit not in DB — expired or deleted)")
        ov = d.get("v_opener_visit")
        if ov:
            nav["opener_visit_id"] = ov
            nav["opener_url"] = url_by_visit.get(ov, "(visit not in DB — expired or deleted)")
        if d.get("v_external_referrer_url"):
            nav["external_referrer_url"] = d["v_external_referrer_url"]
        if searches.get(d.get("u_id")):
            nav["search_terms_for_this_url"] = searches[d["u_id"]]
        if clusters.get(vid):
            nav["journeys_clusters"] = clusters[vid]
        if nav:
            groups["Navigation chain"] = nav

        urlg = {"url_id": d.get("u_id")}
        for c in ucols:
            if c in ("id", "url", "title"):
                continue
            v = d.get("u_" + c)
            urlg[c] = v
            if c == "last_visit_time":
                urlg["last_visit_time_utc"] = iso(webkit_to_dt(v))
        groups["URL record (urls table)"] = urlg

        extra = per_visit.get(vid, {})
        if "visit_source" in extra:
            src = extra["visit_source"].get("source")
            groups["Origin of record"] = {
                "visit_source_raw": src,
                "visit_source": VISIT_SOURCES.get(src, "UNKNOWN(%s)" % src),
            }
        else:
            groups["Origin of record"] = {
                "visit_source": "BROWSED (no visit_source row; Chrome omits it for local visits)"}
        if d.get("v_originator_cache_guid"):
            groups["Origin of record"]["originating_sync_device_guid"] = d["v_originator_cache_guid"]
        if "context_annotations" in extra:
            ca = dict(extra["context_annotations"])
            if "browser_type" in ca:
                ca["browser_type_decoded"] = BROWSER_TYPES.get(ca["browser_type"], "?")
            for k in ("duration_since_last_visit", "total_foreground_duration"):
                if isinstance(ca.get(k), int) and ca[k] >= 0:
                    ca[k + "_seconds"] = round(ca[k] / 1e6, 3)
            groups["Tab / window context (context_annotations)"] = ca
        if "content_annotations" in extra:
            groups["Page content (content_annotations)"] = extra["content_annotations"]

        groups["Attribution"] = {
            "recorded_by_profile": label or "",
            "note": "Chrome stores no per-visit user name. The visit is attributable "
                    "to the owner of this profile (see Report → Profile identity); "
                    "SYNCED visits came from another device on the same account.",
        }

        entries.append(_entry(
            id=vid, url=d.get("u_url") or "", title=d.get("u_title") or "",
            time_utc=iso(when), source=label or path, groups=groups,
            meta={"transition": tr["core"] if tr else "",
                  "duration_s": visit.get("visit_duration_seconds"),
                  "visit_count": d.get("u_visit_count"),
                  "typed_count": d.get("u_typed_count"),
                  "origin": groups["Origin of record"]["visit_source"].split(" ")[0]},
        ))

    downloads = _read_downloads(con)
    gaps = _id_gaps(con)

    info = {
        "kind": "Chrome History SQLite database",
        "label": label or path,
        "evidence_file": facts,
        "row_counts": {t: con.execute("SELECT COUNT(*) FROM %s" % t).fetchone()[0]
                       for t in ("urls", "visits", "downloads", "keyword_search_terms")
                       if _has_table(con, t)},
        "visit_id_gaps": gaps,
    }
    meta_row = con.execute("SELECT key, value FROM meta").fetchall() if _has_table(con, "meta") else []
    if meta_row:
        info["db_meta_table"] = {r[0]: r[1] for r in meta_row}
    if profile_dir:
        info["profile_identity"] = profile_identity(profile_dir)
    return {"info": info, "entries": entries, "downloads": downloads}


def _id_gaps(con, limit=200):
    """Missing visit ids between min and max id.

    Gaps are a forensic indicator: they appear when history entries were
    deleted (by the user, "Clear browsing data", or Chrome's 90-day expiry).
    """
    ids = [r[0] for r in con.execute("SELECT id FROM visits ORDER BY id")]
    gaps, missing = [], 0
    for a, b in zip(ids, ids[1:]):
        if b - a > 1:
            missing += b - a - 1
            if len(gaps) < limit:
                gaps.append([a + 1, b - 1])
    return {"first_id": ids[0] if ids else None, "last_id": ids[-1] if ids else None,
            "missing_ids_total": missing, "ranges": gaps,
            "ranges_truncated": len(gaps) >= limit,
            "interpretation": "Missing visit ids mean rows were removed after they "
                              "were written (manual deletion, Clear browsing data, "
                              "or automatic expiry of old history)."}


def _read_downloads(con):
    if not _has_table(con, "downloads"):
        return []
    chains = {}
    if _has_table(con, "downloads_url_chains"):
        for r in con.execute("SELECT id, chain_index, url FROM downloads_url_chains "
                             "ORDER BY id, chain_index"):
            chains.setdefault(r[0], []).append(r[2])
    out = []
    for r in con.execute("SELECT * FROM downloads ORDER BY start_time DESC"):
        d = dict(r)
        for k in ("start_time", "end_time", "last_access_time"):
            if k in d:
                d[k + "_utc"] = iso(webkit_to_dt(d[k]))
        if "state" in d:
            d["state_decoded"] = DOWNLOAD_STATES.get(d["state"], "?")
        if isinstance(d.get("hash"), bytes):
            d["hash"] = d["hash"].hex()
        for k, v in list(d.items()):
            if isinstance(v, bytes):
                d[k] = v.hex()
        d["url_chain"] = chains.get(d.get("id"), [])
        out.append(d)
    return out


# --------------------------------------------------------------------------
# Google Takeout JSON
# --------------------------------------------------------------------------

URL_KEYS = ("url", "virtual_url", "original_request_url")
TIME_KEYS = (("time_usec", unix_us_to_dt), ("timestamp_msec", unix_ms_to_dt),
             ("last_active_time_unix_epoch_millis", unix_ms_to_dt),
             ("timestamp", unix_ms_to_dt))


def _scalars(d):
    return {k: v for k, v in d.items() if not isinstance(v, (dict, list))}


def _walk(node, path, ctx, out):
    if isinstance(node, dict):
        url = next((node[k] for k in URL_KEYS if isinstance(node.get(k), str) and node[k]), None)
        if url:
            out.append((node, path, ctx))
            return
        sub_ctx = dict(ctx)
        sc = _scalars(node)
        if sc:
            sub_ctx[path or "$"] = sc
        for k, v in node.items():
            _walk(v, "%s.%s" % (path, k) if path else k, sub_ctx, out)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            _walk(v, "%s[%d]" % (path, i), ctx, out)


def _annotate(value):
    dt, enc = guess_timestamp(value)
    if dt:
        return "%s   ⟶ %s (decoded as %s — heuristic)" % (value, iso(dt), enc)
    return value


def read_takeout_json(data, label, facts):
    found = []
    _walk(data, "", {}, found)
    entries = []
    for n, (node, path, ctx) in enumerate(found):
        url = next(node[k] for k in URL_KEYS if isinstance(node.get(k), str) and node[k])
        when, raw_key = None, None
        for key, conv in TIME_KEYS:
            if node.get(key) not in (None, ""):
                when, raw_key = conv(node[key]), key
                if when:
                    break
        groups = {}
        rec = {"json_path": path}
        for k, v in node.items():
            if isinstance(v, (dict, list)):
                rec[k] = json.dumps(v, ensure_ascii=False)
            else:
                rec[k] = _annotate(v) if k not in ("url", "title") else v
        if when:
            rec["(decoded) time_utc from " + raw_key] = iso(when)
        tr = node.get("page_transition")
        if isinstance(tr, int):
            dec = decode_transition(tr)
            rec["page_transition_decoded"] = "%s %s" % (dec["core"], ",".join(dec["qualifiers"]))
        groups["Takeout record"] = rec
        for cpath, sc in ctx.items():
            groups["Container: " + cpath] = {k: _annotate(v) for k, v in sc.items()}
        groups["Attribution"] = {
            "recorded_by": label,
            "note": "Takeout history is tied to the Google account that requested "
                    "the export. Records with a client_id/session tag identify the "
                    "originating Chrome installation, not a person's name.",
        }
        title = node.get("title") or ""
        transition = node.get("page_transition")
        if isinstance(transition, int):
            transition = decode_transition(transition)["core"]
        entries.append(_entry(
            id=node.get("unique_id", node.get("id", n + 1)), url=url, title=title,
            time_utc=iso(when), source=label, groups=groups,
            meta={"transition": transition or "", "duration_s": None,
                  "visit_count": None, "typed_count": None,
                  "origin": "TAKEOUT",
                  "http_status": node.get("http_status_code")},
        ))
    entries.sort(key=lambda e: e["time_utc"] or "", reverse=True)
    top_keys = list(data.keys()) if isinstance(data, dict) else ["(array)"]
    return {"info": {"kind": "Google Takeout JSON", "label": label,
                     "evidence_file": facts, "top_level_keys": top_keys,
                     "records_with_url": len(entries)},
            "entries": entries, "downloads": []}


def read_takeout_file(path):
    with open(path, "rb") as fh:
        data = json.loads(fh.read().decode("utf-8-sig"))
    return read_takeout_json(data, os.path.basename(path), file_facts(path))


def read_takeout_zip(path):
    """Read every Chrome JSON file inside a Takeout zip."""
    results = []
    facts = file_facts(path)
    with zipfile.ZipFile(path) as zf:
        for zi in zf.infolist():
            name = zi.filename
            low = name.lower()
            if not low.endswith(".json") or "chrome" not in low:
                continue
            try:
                data = json.loads(zf.read(zi).decode("utf-8-sig"))
            except ValueError:
                continue
            member = dict(facts)
            member["zip_member"] = name
            member["zip_member_sha256"] = hashlib.sha256(zf.read(zi)).hexdigest()
            member["zip_member_mtime"] = "%04d-%02d-%02d %02d:%02d:%02d" % zi.date_time
            res = read_takeout_json(data, "%s!%s" % (os.path.basename(path), name), member)
            if res["entries"]:
                results.append(res)
    return results


def load_path(path):
    """Load any supported source from a path. Returns a list of source dicts."""
    if os.path.isdir(path):
        if os.path.isfile(os.path.join(path, "History")):
            return [read_history_db(os.path.join(path, "History"),
                                    label=path, profile_dir=path)]
        out = []
        for root, _dirs, files in os.walk(path):
            for f in files:
                if f.lower().endswith(".json"):
                    try:
                        res = read_takeout_file(os.path.join(root, f))
                    except (ValueError, OSError):
                        continue
                    if res["entries"]:
                        out.append(res)
        return out
    low = path.lower()
    if low.endswith(".zip"):
        return read_takeout_zip(path)
    if low.endswith(".json"):
        return [read_takeout_file(path)]
    with open(path, "rb") as fh:
        magic = fh.read(16)
    if magic.startswith(b"SQLite format 3"):
        pdir = os.path.dirname(os.path.abspath(path))
        return [read_history_db(path, label=path, profile_dir=pdir)]
    raise ValueError("Unrecognised input: %s" % path)
