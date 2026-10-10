#!/usr/bin/env python3
"""
Mock Flix Town app panel + Xtream content server + OnePanel renewal API, for the real-network
startup check (real_startup.sh). The app's REAL HTTP code talks to it (no DemoData).

  /api/config.php                 panel config; renewal_api_url from the mode file
  /api/app-update.php, trending.php, ratings.php, tmdb.php, tmdb-match.php   minimal answers
  /player_api.php                 Xtream: user_info, get_vod_streams, get_series, *_categories
  /renewal                        OnePanel renewal API: ok | 500 | slow (never answers in time)

The mode file (argv[2]) holds "<renewal> [catalog]":
  renewal: none | ok | 500 | timeout | unreachable  ('timeout'/'unreachable' name a renewal URL that hangs or refuses)
  catalog: ok (default) | slow (get_vod_streams streams for ~20 s) | failN (the first N get_vod_streams
           calls answer 503, then normal) | down (always 503)
GET /__stats returns how many catalog downloads were tried; GET /__reset zeroes the counters.
Every request is logged to stdout with its time, so the log shows the order of startup calls.
"""
import json, sys, time, threading, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

PORT = int(sys.argv[1]); MODE_FILE = sys.argv[2]
USER, PASS = "0012345678", "0000111122223333"
MOVIES = [{"stream_id": 1000 + i, "num": i + 1, "name": "Mock Movie %d" % (i + 1), "stream_type": "movie",
           "stream_icon": "", "rating": "7", "added": str(1700000000 + i * 60), "category_id": str(10 + i % 6),
           "container_extension": "mp4", "year": str(1990 + i % 35)} for i in range(3000)]
SERIES = [{"series_id": 5000 + i, "num": i + 1, "name": "Mock Series %d" % (i + 1), "cover": "", "plot": "Mock",
           "category_id": str(50 + i % 4), "last_modified": str(1700000000 + i * 60), "rating": "8",
           "year": str(2000 + i % 25)} for i in range(600)]
MCATS = [{"category_id": str(10 + i), "category_name": n, "parent_id": 0} for i, n in enumerate(["Action", "Drama", "Comedy", "Thriller", "Family", "Sci-Fi"])]
SCATS = [{"category_id": str(50 + i), "category_name": n, "parent_id": 0} for i, n in enumerate(["Crime", "Documentary", "Animation", "Reality"])]
T0 = time.time()
STATS = {"vod": 0, "series": 0, "account": 0}; LOCK = threading.Lock()

def modes():
    try: words = open(MODE_FILE).read().split()
    except Exception: words = []
    return (words[0] if words else "none"), (words[1] if len(words) > 1 else "ok")
def mode(): return modes()[0]

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def handle_any(self):
        u = urllib.parse.urlparse(self.path); q = dict(urllib.parse.parse_qsl(u.query)); p = u.path
        print("%7.2f %s %s %s" % (time.time() - T0, self.command, p, q.get("action", "")), flush=True)
        if p == "/__stats": return self.send_json(STATS)
        if p == "/__reset":
            with LOCK:
                for k in STATS: STATS[k] = 0
            return self.send_json(STATS)
        host = "http://10.0.2.2:%d" % PORT
        if p == "/api/config.php":
            m = mode(); cfg = {"xtream_url": host, "intro_enabled": False, "intro_url": "", "app_name": "Flix Town",
                               "cashapp_url": "https://cash.app/$Mock", "plans": {"1m": "15", "3m": "40", "6m": "75", "12m": "135"}}
            # The app only accepts an https renewal address, so the failure modes use https hosts:
            if m == "ok": cfg["renewal_api_url"] = "https://httpbin.org/anything"           # reachable, not a renewal answer
            elif m == "500": cfg["renewal_api_url"] = "https://httpbin.org/status/500"
            elif m == "timeout": cfg["renewal_api_url"] = "https://httpbin.org/delay/40"
            elif m == "unreachable": cfg["renewal_api_url"] = "https://10.255.255.1/api/tv-renewal.php"
            return self.send_json(cfg)
        if p == "/api/app-update.php": return self.send_json({"ok": True, "update_version_code": 0, "message": "No update"})
        if p in ("/api/trending.php",): return self.send_json({"movies": [], "series": []})
        if p in ("/api/ratings.php", "/api/tmdb-match.php"): return self.send_json({"items": {}})
        if p == "/api/tmdb.php": return self.send_json({"cast": [], "trailer": ""})
        if p.startswith("/api/pair-"): return self.send_json({"status": "pending", "code": "MOCK01", "verifier": "v", "activation_url": "https://example.invalid/a"})
        if p == "/player_api.php":
            if q.get("username") != USER or q.get("password") != PASS:
                return self.send_json({"user_info": {"auth": 0}})
            a = q.get("action", "")
            if a == "get_vod_streams":
                with LOCK: STATS["vod"] += 1; n = STATS["vod"]
                cat = modes()[1]
                if cat == "down" or (cat.startswith("fail") and n <= int(cat[4:] or 1)):
                    return self.send_json({"error": "Catalog temporarily unavailable"}, 503)
                if cat == "slow": return self.send_slow(MOVIES, 20)
            if a == "get_series":
                with LOCK: STATS["series"] += 1
            if a == "":
                with LOCK: STATS["account"] += 1
            if a == "": return self.send_json({"user_info": {"username": USER, "auth": 1, "status": "Active", "exp_date": "1830254400", "max_connections": "2"},
                                               "server_info": {"timezone": "America/Denver"}})
            if a == "get_vod_streams": return self.send_json(MOVIES)
            if a == "get_series": return self.send_json(SERIES)
            if a == "get_vod_categories": return self.send_json(MCATS)
            if a == "get_series_categories": return self.send_json(SCATS)
            return self.send_json([])
        self.send_json({"error": "not found"}, 404)
    def send_slow(self, obj, seconds):
        """Starts answering at once but takes about `seconds` to deliver the whole body (a slow server)."""
        body = json.dumps(obj).encode(); parts = 10; step = len(body) // parts + 1
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers()
        for i in range(parts):
            self.wfile.write(body[i * step:(i + 1) * step]); self.wfile.flush(); time.sleep(seconds / parts)
    do_GET = handle_any
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n: self.rfile.read(n)
        self.handle_any()

ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
