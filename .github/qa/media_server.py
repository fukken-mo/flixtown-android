#!/usr/bin/env python3
"""Local test media server for the player/splash QA (emulator reaches it at 10.0.2.2).

  /<file>          normal file with HTTP Range support (seeking)
  /cut/<file>      announces the full length but drops the connection inside bytes 95–99 %:
                   a real network failure near the end of a title (never a clean end). The last
                   1 % (where the Matroska index lives) stays readable, so the title opens normally.
  /cut50/<file>    the same for bytes 50–99 % (an interrupted title, mid-way)
  /live/<file>     no length and no Range: an unseekable stream
  /missing/...     404
Usage: media_server.py PORT DIR
"""
import os, re, sys
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

ROOT = os.path.abspath(sys.argv[2])

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_GET(self):
        path = self.path.split("?")[0]
        mode = "plain"
        for prefix in ("/cut/", "/cut50/", "/live/"):
            if path.startswith(prefix): mode = prefix.strip("/"); path = "/" + path[len(prefix):]
        f = os.path.abspath(os.path.join(ROOT, path.lstrip("/")))
        if not f.startswith(ROOT + os.sep) or not os.path.isfile(f) or path.startswith("/missing/"):
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        size = os.path.getsize(f)
        ctype = "image/jpeg" if f.endswith(".jpg") else "video/mp2t" if f.endswith(".ts") else "video/x-matroska"
        with open(f, "rb") as src:
            if mode == "live":
                self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Connection", "close"); self.end_headers()
                self.close_connection = True
                while True:
                    b = src.read(65536)
                    if not b: return
                    try: self.wfile.write(b)
                    except Exception: return
            start, end = 0, size - 1
            m = re.match(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
            if m:
                start = int(m.group(1)); end = int(m.group(2)) if m.group(2) else size - 1
                self.send_response(206); self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Accept-Ranges", "bytes"); self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(end - start + 1)); self.end_headers()
            cut = int(size * 0.95) if mode == "cut" else int(size * 0.5) if mode == "cut50" else size
            side = f + "." + mode + "-offset"                # byte offset of a playback time, measured with ffprobe
            if mode in ("cut", "cut50") and os.path.exists(side): cut = int(open(side).read().strip())
            tail = int(size * 0.99)
            if start >= tail: cut = size                       # index reads at the very end are served
            src.seek(start); pos = start
            while pos <= end:
                if cut <= pos < tail: self.close_connection = True; return   # drop the connection: the client sees a broken transfer
                n = min(65536, end - pos + 1, max(1, cut - pos) if pos < cut else 65536)
                b = src.read(n)
                if not b: return
                try: self.wfile.write(b)
                except Exception: return
                pos += len(b)

ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1])), H).serve_forever()
