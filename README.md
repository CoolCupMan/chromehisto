# chromehisto

Exports Chrome browsing history into **one self-contained HTML file**:

- **Every link is clickable.** Each entry shows the page title as a link and the **full explicit URL** as a second link, and opens in a new tab.
- **Every piece of metadata Chrome stores is included**, decoded, under each entry's **Details** button.
- **Forensic extras:** the source file's SHA-256 hash, a check for deleted visits, who owns the profile, the export environment (including Wi-Fi), and a tamper-evident click and note log.

Runs on Windows, macOS and Linux. It needs only Python 3.8 or newer, with no packages to install.

## Usage

```bash
# All local Chrome / Edge / Brave / Chromium profiles (Windows: double-click ChromeHistoryExport.bat)
python -m chromehisto

# A Google Takeout export: the zip, a folder, or a single file such as Verlauf.json / BrowserHistory.json
python -m chromehisto takeout-20261009T194439Z-1-001.zip
python -m chromehisto "C:\...\Takeout\Chrome\Verlauf.json" -o verlauf.html

# A specific History database or profile folder
python -m chromehisto "%LOCALAPPDATA%\Google\Chrome\User Data\Default"

# Options
python -m chromehisto --list-profiles       # show detected profiles and the accounts they belong to
python -m chromehisto --public-ip           # also record the public IP at export time
python -m chromehisto --since 2026-09-01     # only records from this date onward
```

The tool copies the `History` database before reading it. This means it works while Chrome is running, and the original evidence file is never opened for writing. Its hash is the same before and after the export.

The report is written to `reports/chrome-history-<timestamp>.html`, together with a `.sha256` file containing the report's hash.

## What the report contains

| Area | Contents |
|---|---|
| **Per visit** (local `History` DB) | URL, title, visit time to the microsecond (UTC and local), time on page, transition type and qualifiers (typed, link, reload, form submit, back/forward, address bar, redirects), the referring page and the tab that opened it, external referrer, search terms, the URL's visit and typed counts, whether the visit happened on this device or came from another device through Chrome Sync, tab and window ids, page-end reason, HTTP response code, page language, password state, and Journeys clusters. Columns are read generically, so new Chrome versions still work. |
| **Per record** (Takeout) | Every field in the JSON, including fields from its parent object (session tag, tab type, …). Timestamp-like numbers are decoded: for example, `global_id` turns out to be a WebKit timestamp equal to `timestamp_msec`. |
| **URL breakdown** | Scheme, host, port, path, fragment, and every query parameter decoded. |
| **Downloads** | Target path, full redirect chain, start and end times, size, state, danger type, MIME type, referrer, and the tab URL. |
| **Evidence tab** | Source file path, size, timestamps and SHA-256; Chrome profile name, Google account e-mail and name, the OS account; **gaps in visit ids** (they show history that was deleted or expired); export time and time zone, computer name, OS user and full name, local IPs, the **Wi-Fi link at export time** (SSID, BSSID, signal % and estimated dBm, channel), and optionally the public IP. |
| **Click & note log** | Each time you open a link from the report, it records: timestamp and time zone, the examiner name you entered, connection type, speed and RTT, user agent, screen size and battery. If you opt in, it also records **GPS/geolocation** and the **public IP**. Notes you add are stamped with your name and the time, and each edit is kept. Every record is SHA-256 hash-chained, so **Verify chain** detects any change. Use **Export log** to save it as JSON. |

Other report features: full-text search across all metadata, filters for date, source, transition and origin, sorting, paging, CSV export of the filtered rows, and dark mode.

## What cannot be recovered and why

Chrome does **not** store Wi-Fi strength, IP address, GPS position or a person's name with a history entry. No tool can reconstruct these values for visits that already happened. A tool that shows them for past visits is making them up. chromehisto records these values only where they can be measured truthfully:

- **when the report is exported** (Evidence tab), and
- **when you open a link from the report** (Click log).

The person behind a visit is identified by the Chrome profile, Google account and OS account that own the history file. A visit marked `SYNCED` came from another device signed in to the same account.

Browsers do not let web pages read the Wi-Fi RSSI, so the click log records the browser's connection estimate instead. Geolocation needs your permission. If the browser blocks it on `file://` pages, the error is written to the log.

## Tests

```bash
python -m unittest discover -s tests -v
```
