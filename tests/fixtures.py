"""Build synthetic Chrome history inputs for tests and demos."""

import json
import os
import sqlite3

# 2026-10-09 19:00:00 UTC as WebKit µs since 1601.
BASE = 13_405_000_000_000_000 + 0

SCHEMA = """
CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR);
CREATE TABLE urls(id INTEGER PRIMARY KEY AUTOINCREMENT, url LONGVARCHAR, title LONGVARCHAR,
  visit_count INTEGER DEFAULT 0 NOT NULL, typed_count INTEGER DEFAULT 0 NOT NULL,
  last_visit_time INTEGER NOT NULL, hidden INTEGER DEFAULT 0 NOT NULL);
CREATE TABLE visits(id INTEGER PRIMARY KEY AUTOINCREMENT, url INTEGER NOT NULL,
  visit_time INTEGER NOT NULL, from_visit INTEGER, external_referrer_url TEXT,
  transition INTEGER DEFAULT 0 NOT NULL, segment_id INTEGER, visit_duration INTEGER DEFAULT 0 NOT NULL,
  incremented_omnibox_typed_score BOOLEAN DEFAULT FALSE NOT NULL, opener_visit INTEGER,
  originator_cache_guid TEXT, originator_visit_id INTEGER, originator_from_visit INTEGER,
  originator_opener_visit INTEGER, is_known_to_sync BOOLEAN DEFAULT FALSE NOT NULL,
  consider_for_ntp_most_visited BOOLEAN DEFAULT FALSE NOT NULL, visited_link_id INTEGER DEFAULT 0 NOT NULL,
  app_id TEXT);
CREATE TABLE visit_source(id INTEGER PRIMARY KEY, source INTEGER NOT NULL);
CREATE TABLE keyword_search_terms(keyword_id INTEGER NOT NULL, url_id INTEGER NOT NULL,
  term LONGVARCHAR NOT NULL, normalized_term LONGVARCHAR NOT NULL);
CREATE TABLE context_annotations(visit_id INTEGER PRIMARY KEY, context_annotation_flags INTEGER NOT NULL,
  duration_since_last_visit INTEGER, page_end_reason INTEGER, total_foreground_duration INTEGER,
  browser_type INTEGER DEFAULT 0 NOT NULL, window_id INTEGER DEFAULT -1 NOT NULL,
  tab_id INTEGER DEFAULT -1 NOT NULL, task_id INTEGER DEFAULT -1 NOT NULL,
  root_task_id INTEGER DEFAULT -1 NOT NULL, parent_task_id INTEGER DEFAULT -1 NOT NULL,
  response_code INTEGER DEFAULT 0 NOT NULL);
CREATE TABLE downloads(id INTEGER PRIMARY KEY, guid VARCHAR NOT NULL, current_path LONGVARCHAR NOT NULL,
  target_path LONGVARCHAR NOT NULL, start_time INTEGER NOT NULL, received_bytes INTEGER NOT NULL,
  total_bytes INTEGER NOT NULL, state INTEGER NOT NULL, danger_type INTEGER NOT NULL,
  interrupt_reason INTEGER NOT NULL, hash BLOB NOT NULL, end_time INTEGER NOT NULL,
  opened INTEGER NOT NULL, last_access_time INTEGER NOT NULL, transient INTEGER NOT NULL,
  referrer VARCHAR NOT NULL, site_url VARCHAR NOT NULL, tab_url VARCHAR NOT NULL,
  tab_referrer_url VARCHAR NOT NULL, http_method VARCHAR NOT NULL, mime_type VARCHAR(255) NOT NULL,
  original_mime_type VARCHAR(255) NOT NULL);
CREATE TABLE downloads_url_chains(id INTEGER NOT NULL, chain_index INTEGER NOT NULL,
  url LONGVARCHAR NOT NULL, PRIMARY KEY (id, chain_index));
"""


def make_history_db(profile_dir):
    os.makedirs(profile_dir, exist_ok=True)
    path = os.path.join(profile_dir, "History")
    con = sqlite3.connect(path)
    con.executescript(SCHEMA)
    con.execute("INSERT INTO meta VALUES ('version', '68')")
    urls = [
        (1, "https://www.google.com/search?q=paypal+passwort", "paypal passwort - Google Suche", 1, 0),
        (2, "https://www.paypal.com/authflow/password-recovery/?anw_sid=AAF6k03ow", "Ihr PayPal-Team", 2, 0),
        (3, "https://www.paypal.com/authflow/challenges/email/?anw_sid=AAHmQ16", "Ihr PayPal-Team", 1, 0),
        (4, "javascript:alert(document.cookie)", "<img src=x onerror=alert(1)></script>", 1, 1),
        (5, "chrome://settings/", "Einstellungen", 1, 1),
    ]
    for uid, url, title, vc, tc in urls:
        con.execute("INSERT INTO urls VALUES (?,?,?,?,?,?,0)", (uid, url, title, vc, tc, BASE + uid * 10**6))
    # id, url, time offset (s), from_visit, transition, duration µs
    visits = [
        (1, 1, 0, 0, 0x02000001, 4_200_000),             # TYPED | FROM_ADDRESS_BAR
        (2, 2, 30, 1, 0x30000000, 61_000_000),           # LINK | CHAIN_START|CHAIN_END
        (3, 3, 95, 2, 0x30000007, 12_000_000),           # FORM_SUBMIT
        (5, 2, 130, 3, 0x30000008, 9_000_000),           # RELOAD (id 4 deleted -> gap)
        (6, 4, 200, 0, 0x00000001, 0),
        (9, 5, 260, 0, 0x00000001, 0),                   # ids 7-8 deleted
    ]
    for vid, url, off, fv, tr, dur in visits:
        con.execute("INSERT INTO visits (id,url,visit_time,from_visit,transition,visit_duration,"
                    "originator_cache_guid) VALUES (?,?,?,?,?,?,?)",
                    (vid, url, BASE + off * 10**6, fv, tr, dur, "abc-guid" if vid == 5 else ""))
    con.execute("INSERT INTO visit_source VALUES (5, 0)")
    con.execute("INSERT INTO keyword_search_terms VALUES (2, 1, 'paypal passwort', 'paypal passwort')")
    con.execute("INSERT INTO context_annotations VALUES (2,0,-1,3,58000000,1,7,42,-1,-1,-1,200)")
    con.execute("INSERT INTO downloads VALUES (1,'g','C:\\Users\\chris\\Downloads\\x.pdf',"
                "'C:\\Users\\chris\\Downloads\\x.pdf',?,1000,1000,1,0,0,?,?,1,0,0,"
                "'https://www.paypal.com/','https://www.paypal.com/','https://www.paypal.com/',"
                "'','GET','application/pdf','application/pdf')",
                (BASE + 40 * 10**6, b"\x01\x02", BASE + 41 * 10**6))
    con.execute("INSERT INTO downloads_url_chains VALUES (1,0,'https://www.paypal.com/x.pdf')")
    con.commit()
    con.close()
    with open(os.path.join(profile_dir, "Preferences"), "w", encoding="utf-8") as fh:
        json.dump({"profile": {"name": "Person 1", "creation_time": str(BASE)},
                   "account_info": [{"email": "user@example.com", "full_name": "Example User"}]}, fh)
    return path


TAKEOUT = {
    "Browser History": [
        {"favicon_url": "https://www.paypal.com/favicon.ico", "page_transition": "LINK",
         "title": "Ihr PayPal-Team", "url": "https://www.paypal.com/signin",
         "client_id": "u2Sa3xPV8yA5uvTlPmCkTQ==", "time_usec": 1790681145250079},
    ],
    "Session": [{
        "session_tag": "session_sync123", "type": "TYPE_TABBED",
        "last_active_time_unix_epoch_millis": 1791539555998, "id": 293838523,
        "navigations": [
            {"navigation_from_address_bar": False, "unique_id": 1004, "navigation_forward_back": False,
             "http_status_code": 200, "global_id": 13435154745250079, "page_transition": "RELOAD",
             "title": "Ihr PayPal-Team", "timestamp_msec": 1790681145250,
             "password_state": "PASSWORD_STATE_UNKNOWN",
             "referrer": "https://www.paypal.com/authflow/password-recovery/?anw_sid=AAF6k03ow",
             "virtual_url": "https://www.paypal.com/authflow/challenges/email/?anw_sid=AAHmQ16",
             "correct_referrer_policy": 1, "navigation_home_page": False},
            {"navigation_from_address_bar": False, "unique_id": 1005, "navigation_forward_back": False,
             "http_status_code": 200, "global_id": 13435154759675766, "page_transition": "RELOAD",
             "title": "Ihr PayPal-Team", "timestamp_msec": 1790681159675,
             "password_state": "PASSWORD_STATE_UNKNOWN",
             "referrer": "https://www.paypal.com/authflow/password-recovery/?anw_sid=AAF6k03ow",
             "virtual_url": "https://www.paypal.com/authflow/entry/?anw_sid=AAHmQ16",
             "correct_referrer_policy": 1, "navigation_home_page": False},
        ],
    }],
}


def make_takeout(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(TAKEOUT, fh, indent=2)
    return path
