#!/usr/bin/env python3
"""Read-only check of which configured endpoints use http or https.

Prints only scheme://host[:port] for every address (never paths, queries or credentials). Requests:
GET config.php and app-update.php from the public panel API, unauthenticated GETs to the content
server, and one stream-shaped URL with placeholder credentials ("0") to see where streams redirect.
Nothing is created or changed. Output: qa/endpoints/report.txt
"""
import json, os, ssl, sys, urllib.error, urllib.parse, urllib.request

PANEL = "https://panelsandapps.com/panels/flixtown2027/api/"
OUT = "qa/endpoints"; os.makedirs(OUT, exist_ok=True)
lines = []
def note(s): print(s); lines.append(s)
def origin(u):
    p = urllib.parse.urlparse(u); return f"{p.scheme}://{p.netloc}" if p.scheme and p.netloc else "(none)"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None
opener = urllib.request.build_opener(NoRedirect)
def probe(url):
    """(status, redirect origin) without following redirects."""
    try:
        r = opener.open(urllib.request.Request(url, headers={"User-Agent": "FlixTown-audit"}), timeout=20)
        return r.status, ""
    except urllib.error.HTTPError as e:
        return e.code, origin(e.headers.get("Location", "")) if e.headers.get("Location") else ""
    except Exception as e:
        return type(e).__name__, ""
def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "FlixTown-audit"}), timeout=25) as r:
        return json.loads(r.read().decode("utf-8", "replace"))

note("== Panel config.php (address fields, origin only)")
cfg = get_json(PANEL + "config.php")
for k in sorted(cfg):
    v = cfg[k]
    if isinstance(v, str) and "://" in v: note(f"  {k}: {origin(v)}")
xt = (cfg.get("xtream_url") or "").rstrip("/")

note("== app-update.php (production package)")
try:
    upd = get_json(PANEL + "app-update.php?package=com.myflixtown.tv.native&version_code=1")
    note(f"  update_apk_url: {origin(upd.get('update_apk_url',''))}  versionCode={upd.get('update_version_code')}")
except Exception as e: note(f"  error {type(e).__name__}")

note("== Content server (Xtream) used for catalog, account and playback URLs")
if xt:
    p = urllib.parse.urlparse(xt); host = p.hostname; port = p.port
    note(f"  configured: {origin(xt)}")
    s, loc = probe(xt + "/player_api.php"); note(f"  {origin(xt)}/player_api.php (no credentials) -> {s} {('redirect ' + loc) if loc else ''}")
    for alt in [f"https://{host}" + (f":{port}" if port else ""), f"https://{host}"]:
        if alt == origin(xt): continue
        s, loc = probe(alt + "/player_api.php"); note(f"  try {alt}/player_api.php -> {s} {('redirect ' + loc) if loc else ''}")
    s, loc = probe(xt + "/movie/0/0/0.mp4"); note(f"  stream-shaped URL with placeholder credentials -> {s} {('redirect ' + loc) if loc else '(no redirect)'}")

note("== Other service hosts (root, no credentials)")
for u in ["https://panel.myflixtown.com/", "https://api.myflixtown.com/", "https://requests.myflixtown.com/",
          "https://panelsandapps.com/panels/onepanel/", PANEL + "config.php"]:
    s, loc = probe(u); note(f"  {origin(u)} -> {s} {('redirect ' + loc) if loc else ''}")
open(OUT + "/report.txt", "w").write("\n".join(lines) + "\n")
