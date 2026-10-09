"""Command line interface.

    python -m chromehisto                       # all local Chrome/Edge/Brave profiles
    python -m chromehisto takeout.zip           # Google Takeout export
    python -m chromehisto Verlauf.json -o out.html
"""

import argparse
import os
import pathlib
import sys
import webbrowser
from datetime import datetime

from . import __version__, context, report, sources


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="chromehisto",
        description="Export Chrome history to a self-contained, clickable HTML "
                    "report with all metadata Chrome stores.")
    ap.add_argument("inputs", nargs="*",
                    help="History DB file, profile folder, Takeout .json/.zip or "
                         "Takeout folder. Default: every local browser profile.")
    ap.add_argument("-o", "--output", help="Output HTML path "
                    "(default: reports/chrome-history-<timestamp>.html)")
    ap.add_argument("--list-profiles", action="store_true",
                    help="List detected local profiles and exit")
    ap.add_argument("--public-ip", action="store_true",
                    help="Also record the public IP at export time (contacts ipify/ipapi)")
    ap.add_argument("--since", help="Only include records on/after this date (YYYY-MM-DD)")
    ap.add_argument("--no-open", action="store_true", help="Do not open the report in a browser")
    ap.add_argument("--version", action="version", version="%(prog)s " + __version__)
    args = ap.parse_args(argv)

    if args.list_profiles or not args.inputs:
        profiles = sources.discover_profiles()
        if args.list_profiles:
            for browser, pdir in profiles:
                ident = sources.profile_identity(pdir)
                who = ident.get("local_state.user_name") or ident.get("local_state.name") or ""
                print("%-20s %-60s %s" % (browser, pdir, who))
            return 0
        if not profiles:
            print("No local Chrome/Chromium profiles found. Pass a History file "
                  "or a Takeout export as an argument.", file=sys.stderr)
            return 1

    loaded = []
    if args.inputs:
        for path in args.inputs:
            try:
                res = sources.load_path(path)
            except (OSError, ValueError) as exc:
                print("! %s: %s" % (path, exc), file=sys.stderr)
                continue
            if not res:
                print("! %s: no history records found" % path, file=sys.stderr)
            loaded.extend(res)
    else:
        for browser, pdir in profiles:
            label = "%s / %s" % (browser, os.path.basename(pdir))
            print("Reading %s ..." % label)
            try:
                loaded.append(sources.read_history_db(
                    os.path.join(pdir, "History"), label=label, profile_dir=pdir))
            except Exception as exc:  # one broken profile should not stop the rest
                print("! %s: %s" % (label, exc), file=sys.stderr)

    if not loaded:
        print("Nothing to export.", file=sys.stderr)
        return 1

    if args.since:
        cutoff = datetime.strptime(args.since, "%Y-%m-%d").strftime("%Y-%m-%d")
        for src in loaded:
            src["entries"] = [e for e in src["entries"] if (e["time_utc"] or "") >= cutoff]

    print("Collecting export context (user, machine, network, Wi-Fi) ...")
    ctx = context.collect(include_public_ip=args.public_ip)

    out = args.output or os.path.join(
        "reports", "chrome-history-%s.html" % datetime.now().strftime("%Y%m%d-%H%M%S"))
    stats = report.build(loaded, ctx, out)
    print("Wrote %s" % os.path.abspath(out))
    print("  %d records, %d downloads" % (stats["entries"], stats["downloads"]))
    print("  data SHA-256 %s" % stats["data_sha256"])
    print("  file SHA-256 %s  (also in %s.sha256)" % (stats["file_sha256"], os.path.basename(out)))
    if not args.no_open:
        webbrowser.open(pathlib.Path(os.path.abspath(out)).as_uri())
    return 0


if __name__ == "__main__":
    sys.exit(main())
