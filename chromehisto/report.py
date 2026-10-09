"""Build the self-contained HTML report."""

import hashlib
import json
import os
from datetime import datetime, timezone

from . import __version__

TEMPLATE = os.path.join(os.path.dirname(__file__), "template.html")


def _embed(obj):
    """JSON safe to place inside <script type="application/json">."""
    text = json.dumps(obj, ensure_ascii=False, separators=(",", ":"), default=str)
    return (text.replace("</", "<\\/").replace("<!--", "<\\u0021--")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"))


def build(sources, context, out_path):
    entries, downloads, infos = [], [], []
    for si, src in enumerate(sources):
        infos.append(src["info"])
        for e in src["entries"]:
            e["src"] = si
            entries.append(e)
        for d in src["downloads"]:
            d["_src"] = si
            downloads.append(d)
    entries.sort(key=lambda e: e.get("time_utc") or "", reverse=True)
    for n, e in enumerate(entries):
        e["n"] = n  # stable row key used by the click/annotation log

    payload = {
        "tool": {"name": "chromehisto", "version": __version__},
        "generated_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "context": context,
        "sources": infos,
        "entries": entries,
        "downloads": downloads,
    }
    data = _embed(payload)
    data_hash = hashlib.sha256(data.encode("utf-8")).hexdigest()

    with open(TEMPLATE, "r", encoding="utf-8") as fh:
        html = fh.read()
    html = (html.replace("__DATA_SHA256__", data_hash)
                .replace("__DATA_JSON__", data))

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(html)
    file_hash = hashlib.sha256(html.encode("utf-8")).hexdigest()
    with open(out_path + ".sha256", "w", encoding="utf-8") as fh:
        fh.write("%s  %s\n" % (file_hash, os.path.basename(out_path)))
    return {"entries": len(entries), "downloads": len(downloads),
            "data_sha256": data_hash, "file_sha256": file_hash}
