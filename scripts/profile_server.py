#!/usr/bin/env python3
"""Optional lightweight HTTP server to serve an iOS/iPadOS .mobileconfig AirPrint profile."""

import http.server
import os
import socketserver
import sys

PORT = int(os.environ.get("PORT", "8080"))
PROFILE_PATH = (
    sys.argv[1]
    if len(sys.argv) > 1
    else os.environ.get("MOBILECONFIG_PATH", "HP_LaserJet_AirPrint.mobileconfig")
)

HTML = """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PrintBox AirPrint Profile Setup</title>
<style>
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; text-align: center; padding: 40px 20px; background: #0B111E; color: #F1F5F9; }
.card { background: #131C2E; border: 1px solid #233252; border-radius: 16px; padding: 30px 20px; max-width: 380px; margin: 0 auto; }
h1 { font-size: 22px; margin-bottom: 8px; }
p { color: #94A3B8; font-size: 15px; line-height: 1.4; margin-bottom: 24px; }
.btn { display: block; background: #2563EB; color: white; text-decoration: none; padding: 16px; border-radius: 12px; font-weight: 600; font-size: 17px; margin-bottom: 16px; }
.step { text-align: left; font-size: 14px; color: #CBD5E1; margin-top: 10px; line-height: 1.5; }
</style>
</head>
<body>
<div class="card">
    <h1>PrintBox AirPrint Setup</h1>
    <p>Install a fallback iOS configuration profile for your PrintBox server.</p>
    <a href="/airprint.mobileconfig" class="btn">Install AirPrint Profile</a>
    <div class="step">
        <b>Quick Steps:</b><br>
        1. Tap <b>Install AirPrint Profile</b> above.<br>
        2. Tap <b>Allow</b> when prompted.<br>
        3. Open <b>Settings</b> on your iPhone/iPad.<br>
        4. Tap <b>Profile Downloaded</b> and complete installation.
    </div>
</div>
</body>
</html>
"""


class Handler(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        if self.path == "/airprint.mobileconfig":
            if not os.path.isfile(PROFILE_PATH):
                self.send_error(404, f"Profile file not found: {PROFILE_PATH}")
                return
            try:
                with open(PROFILE_PATH, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "application/x-apple-aspen-config")
                self.send_header(
                    "Content-Disposition",
                    'attachment; filename="PrintBox_AirPrint.mobileconfig"',
                )
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except Exception as e:
                self.send_error(500, str(e))
        else:
            data = HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("", PORT), Handler) as httpd:
        print(f"Serving {PROFILE_PATH} on port {PORT}")
        httpd.serve_forever()
