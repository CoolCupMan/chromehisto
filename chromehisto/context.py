"""Facts about the machine and moment of export.

Chrome does not record Wi-Fi strength, IP address or GPS position for each
visit, so these values can never be recovered for past visits. What can be
documented honestly is the environment *at the time the report is produced*;
that is what this module collects. Every probe is best effort and failures are
recorded instead of raised.
"""

import getpass
import json
import os
import platform
import re
import socket
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone


def _run(cmd, timeout=8):
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=timeout,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.SubprocessError) as exc:
        return None, str(exc)
    raw = out.stdout
    for enc in ("utf-8", "cp850", "cp1252", "latin-1"):
        try:
            return raw.decode(enc), None
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace"), None


def _kv_lines(text):
    """Parse 'Key : Value' lines (netsh, airport) into an ordered list."""
    pairs = []
    for line in (text or "").splitlines():
        if ":" in line:
            k, _, v = line.partition(":")
            k, v = k.strip(), v.strip()
            if k and v:
                pairs.append([k, v])
    return pairs


def wifi_info():
    """Current Wi-Fi link (SSID, BSSID, signal, channel ...) at export time."""
    if sys.platform.startswith("win"):
        text, err = _run(["netsh", "wlan", "show", "interfaces"])
        if err or not text:
            return {"available": False, "error": err or "no output"}
        pairs = _kv_lines(text)
        res = {"available": True, "tool": "netsh wlan show interfaces", "fields": pairs}
        for k, v in pairs:
            if k.lower() == "signal":
                m = re.match(r"(\d+)\s*%", v)
                if m:
                    pct = int(m.group(1))
                    res["signal_percent"] = pct
                    # Windows maps RSSI -100..-50 dBm linearly onto 0..100 %.
                    res["rssi_dbm_estimated"] = int(pct / 2 - 100)
        return res
    if sys.platform == "darwin":
        airport = ("/System/Library/PrivateFrameworks/Apple80211.framework/"
                   "Versions/Current/Resources/airport")
        if os.path.exists(airport):
            text, err = _run([airport, "-I"])
            if text and "deprecated" not in text.lower():
                pairs = _kv_lines(text)
                res = {"available": True, "tool": "airport -I", "fields": pairs}
                for k, v in pairs:
                    if k == "agrCtlRSSI":
                        res["rssi_dbm"] = int(v)
                    if k == "agrCtlNoise":
                        res["noise_dbm"] = int(v)
                return res
        text, err = _run(["system_profiler", "SPAirPortDataType"], timeout=20)
        if text:
            return {"available": True, "tool": "system_profiler SPAirPortDataType",
                    "fields": _kv_lines(text)[:60]}
        return {"available": False, "error": err or "no output"}
    text, err = _run(["nmcli", "-t", "-f",
                      "ACTIVE,SSID,BSSID,SIGNAL,CHAN,FREQ,RATE,SECURITY,MODE",
                      "dev", "wifi"])
    if text:
        for line in text.splitlines():
            parts = re.split(r"(?<!\\):", line)
            if parts and parts[0] == "yes":
                names = ["active", "ssid", "bssid", "signal_percent", "channel",
                         "frequency", "rate", "security", "mode"]
                vals = [p.replace("\\:", ":") for p in parts]
                return {"available": True, "tool": "nmcli",
                        "fields": [list(x) for x in zip(names, vals)]}
    try:
        with open("/proc/net/wireless") as fh:
            lines = fh.read().splitlines()[2:]
        if lines:
            return {"available": True, "tool": "/proc/net/wireless",
                    "fields": [["raw", l.strip()] for l in lines]}
    except OSError:
        pass
    return {"available": False, "error": err or "no Wi-Fi interface found"}


def local_addresses():
    addrs = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None):
            addrs.add(info[4][0])
    except OSError:
        pass
    # Address of the interface that would route to the internet (no packet sent).
    for fam, target in ((socket.AF_INET, ("192.0.2.1", 80)),
                        (socket.AF_INET6, ("2001:db8::1", 80))):
        try:
            s = socket.socket(fam, socket.SOCK_DGRAM)
            s.connect(target)
            addrs.add(s.getsockname()[0])
            s.close()
        except OSError:
            pass
    return sorted(addrs)


def public_ip():
    """Only called when the user passes --public-ip (it contacts a server)."""
    res = {}
    for name, url in (("ipify", "https://api.ipify.org?format=json"),
                      ("ipapi", "https://ipapi.co/json/")):
        try:
            with urllib.request.urlopen(url, timeout=6) as r:
                res[name] = json.loads(r.read().decode("utf-8"))
        except Exception as exc:  # network errors of all kinds
            res[name] = {"error": str(exc)}
    return res


def collect(include_public_ip=False):
    now = datetime.now(timezone.utc)
    local = now.astimezone()
    try:
        user = getpass.getuser()
    except Exception:
        user = None
    ctx = {
        "export_time_utc": now.isoformat(timespec="microseconds"),
        "export_time_local": local.isoformat(timespec="microseconds"),
        "timezone_name": time.tzname,
        "utc_offset_minutes": int(local.utcoffset().total_seconds() // 60),
        "os_user": user,
        "os_user_full_name": _full_name(user),
        "hostname": socket.gethostname(),
        "fqdn": socket.getfqdn(),
        "os": platform.platform(),
        "os_release": platform.release(),
        "machine": platform.machine(),
        "processor": platform.processor(),
        "python": sys.version.split()[0],
        "local_ip_addresses": local_addresses(),
        "wifi_at_export": wifi_info(),
    }
    if include_public_ip:
        ctx["public_ip_at_export"] = public_ip()
    return ctx


def _full_name(user):
    if not user:
        return None
    if sys.platform.startswith("win"):
        text, _ = _run(["net", "user", user])
        if text:
            for line in text.splitlines():
                # "Full Name" / "Vollständiger Name"
                if line.lower().startswith(("full name", "vollständiger name")):
                    return line.split(None, 2)[-1].strip() or None
        return None
    try:
        import pwd
        return pwd.getpwnam(user).pw_gecos.split(",")[0] or None
    except (ImportError, KeyError):
        return None
