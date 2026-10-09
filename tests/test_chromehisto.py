import json
import os
import re
import shutil
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from chromehisto import report, sources  # noqa: E402
from chromehisto.__main__ import main  # noqa: E402
import fixtures  # noqa: E402


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.profile = os.path.join(self.tmp, "User Data", "Default")
        self.db = fixtures.make_history_db(self.profile)
        self.takeout = fixtures.make_takeout(os.path.join(self.tmp, "Takeout", "Chrome", "Verlauf.json"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)


class TestHelpers(Base):
    def test_webkit_time(self):
        self.assertEqual(sources.iso(sources.webkit_to_dt(13435154745250079)),
                         "2026-09-29T11:25:45.250079+00:00")
        self.assertIsNone(sources.webkit_to_dt(0))

    def test_transition(self):
        t = sources.decode_transition(0x02000001)
        self.assertEqual(t["core"], "TYPED")
        self.assertEqual(t["qualifiers"], ["FROM_ADDRESS_BAR"])
        t = sources.decode_transition(-2147483640)  # signed SERVER_REDIRECT|RELOAD
        self.assertEqual(t["core"], "RELOAD")
        self.assertIn("SERVER_REDIRECT", t["qualifiers"])

    def test_guess_timestamp(self):
        self.assertEqual(sources.guess_timestamp(1790681145250)[1], "Unix ms")
        self.assertEqual(sources.guess_timestamp(13435154745250079)[1], "WebKit µs since 1601")
        self.assertEqual(sources.guess_timestamp(1004), (None, None))


class TestHistoryDb(Base):
    def test_entries_and_metadata(self):
        src = sources.read_history_db(self.db, label="test", profile_dir=self.profile)
        entries = src["entries"]
        self.assertEqual(len(entries), 6)
        by_id = {e["id"]: e for e in entries}
        v2 = by_id[2]
        self.assertEqual(v2["groups"]["Navigation chain"]["referring_url"],
                         "https://www.google.com/search?q=paypal+passwort")
        self.assertEqual(v2["meta"]["duration_s"], 61.0)
        self.assertEqual(v2["groups"]["Tab / window context (context_annotations)"]["browser_type_decoded"], "TABBED")
        self.assertEqual(by_id[1]["groups"]["Navigation chain"]["search_terms_for_this_url"], ["paypal passwort"])
        self.assertEqual(by_id[5]["meta"]["origin"], "SYNCED")
        self.assertEqual(by_id[1]["meta"]["transition"], "TYPED")
        self.assertEqual(by_id[2]["url_parts"]["query_params"], [("anw_sid", "AAF6k03ow")])

    def test_gaps_identity_downloads(self):
        src = sources.read_history_db(self.db, label="test", profile_dir=self.profile)
        gaps = src["info"]["visit_id_gaps"]
        self.assertEqual(gaps["missing_ids_total"], 3)
        self.assertEqual(gaps["ranges"], [[4, 4], [7, 8]])
        ident = src["info"]["profile_identity"]
        self.assertEqual(ident["account_info[0].email"], "user@example.com")
        self.assertEqual(len(src["info"]["evidence_file"]["sha256"]), 64)
        dl = src["downloads"][0]
        self.assertEqual(dl["state_decoded"], "COMPLETE")
        self.assertEqual(dl["hash"], "0102")
        self.assertEqual(dl["url_chain"], ["https://www.paypal.com/x.pdf"])

    def test_original_not_modified(self):
        before = sources.sha256_file(self.db)
        sources.read_history_db(self.db)
        self.assertEqual(before, sources.sha256_file(self.db))


class TestTakeout(Base):
    def test_json(self):
        src = sources.load_path(self.takeout)[0]
        self.assertEqual(len(src["entries"]), 3)
        nav = [e for e in src["entries"] if e["id"] == 1005][0]
        self.assertEqual(nav["url"], "https://www.paypal.com/authflow/entry/?anw_sid=AAHmQ16")
        self.assertEqual(nav["time_utc"], "2026-09-29T11:25:59.675000+00:00")
        self.assertEqual(nav["meta"]["http_status"], 200)
        self.assertIn("Container: Session[0]", nav["groups"])
        self.assertIn("WebKit", nav["groups"]["Takeout record"]["global_id"])

    def test_zip(self):
        zpath = os.path.join(self.tmp, "takeout.zip")
        with zipfile.ZipFile(zpath, "w") as zf:
            zf.write(self.takeout, "Takeout/Chrome/Verlauf.json")
        res = sources.load_path(zpath)
        self.assertEqual(len(res), 1)
        self.assertEqual(res[0]["info"]["evidence_file"]["zip_member"], "Takeout/Chrome/Verlauf.json")


class TestReport(Base):
    def test_cli_end_to_end(self):
        out = os.path.join(self.tmp, "r.html")
        rc = main([self.profile, self.takeout, "-o", out, "--no-open"])
        self.assertEqual(rc, 0)
        html = open(out, encoding="utf-8").read()
        # Payload cannot break out of its <script> element.
        m = re.search(r'<script type="application/json" id="report-data">(.*?)</script>', html, re.S)
        self.assertIsNotNone(m)
        payload = json.loads(m.group(1))
        self.assertEqual(len(payload["entries"]), 9)
        self.assertIn("<\\/script>", m.group(1))
        self.assertTrue(os.path.exists(out + ".sha256"))
        import hashlib
        self.assertIn('content="%s"' % hashlib.sha256(m.group(1).encode()).hexdigest(), html)


if __name__ == "__main__":
    unittest.main()
