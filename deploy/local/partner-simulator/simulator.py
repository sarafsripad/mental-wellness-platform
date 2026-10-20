#!/usr/bin/env python3
"""SIMULATED partner programmes for local testing only (ADR-015). Not Tele-MANAS, eSanjeevani or any
real system, and it never contacts one.

It stands in for partner endpoints so the platform's referral outbox, signatures and status
events can be tested end to end:

- POST /<partner>/referrals: checks the X-Wellness-Signature (HMAC-SHA256 over message id,
  timestamp and body) and the bundle shape. It records the message and answers 202 with a
  partner reference. For an active referral it then posts RECEIVED, CONTACT_ATTEMPTED and
  CONTACTED back to the platform, as the partner client (OAuth client credentials).
- POST /hmis/reports: checks the signature and records an aggregate report.
- GET /_simulator/messages: what was received (codes and IDs only), for tests.

Standard library only. Configuration comes from the environment (see docker-compose.yml).
"""
import hashlib
import hmac
import json
import os
import threading
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SECRET = os.environ["SIMULATOR_SIGNING_SECRET"].encode()
CLIENT_ID = os.environ.get("SIMULATOR_CLIENT_ID", "partner-simulator")
CLIENT_SECRET = os.environ["SIMULATOR_CLIENT_SECRET"]
TOKEN_URL = os.environ["SIMULATOR_TOKEN_URL"]
API_URL = os.environ["SIMULATOR_API_URL"].rstrip("/")
EVENT_DELAY = float(os.environ.get("SIMULATOR_EVENT_DELAY_SECONDS", "2"))
PARTNERS = {"tele-manas": "sim-tele-manas", "esanjeevani": "sim-esanjeevani"}
MAX_SKEW = 300

received = []
lock = threading.Lock()


def log(message):
    print(f"[partner-simulator] {message}", flush=True)


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def signature_ok(headers, body):
    message_id = headers.get("X-Wellness-Message-Id", "")
    timestamp = headers.get("X-Wellness-Timestamp", "")
    given = headers.get("X-Wellness-Signature", "")
    if not message_id or not timestamp.isdigit() or abs(time.time() - int(timestamp)) > MAX_SKEW:
        return False
    expected = "v1=" + hmac.new(SECRET, f"{message_id}.{timestamp}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, given)


def token():
    data = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": CLIENT_ID,
                                   "client_secret": CLIENT_SECRET}).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=data), timeout=10) as r:
        return json.loads(r.read())["access_token"]


def post_events(partner_id, referral_id, reference):
    """Plays the partner's side: received, a call attempt, then contact made."""
    for n, status in enumerate(["RECEIVED", "CONTACT_ATTEMPTED", "CONTACTED"], start=1):
        time.sleep(EVENT_DELAY)
        body = {"event_id": f"SIM-{uuid.uuid4().hex[:12]}", "referral_id": referral_id, "status": status,
                "occurred_at_utc": now(), "partner_reference": reference}
        req = urllib.request.Request(f"{API_URL}/internal/v1/partners/{partner_id}/referral-events",
                                     data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json",
                                              "Authorization": f"Bearer {token()}",
                                              "X-Correlation-ID": str(uuid.uuid4())})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                log(f"{partner_id} {referral_id} {status} -> {r.status}")
        except urllib.error.HTTPError as e:
            log(f"{partner_id} {referral_id} {status} -> {e.code} (stopping)")
            return
        except OSError as e:
            log(f"{partner_id} {referral_id} {status} -> unreachable ({e.__class__.__name__}); stopping")
            return


class Handler(BaseHTTPRequestHandler):
    server_version = "partner-simulator"

    def reply(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):  # requests are logged by code below, without bodies
        pass

    def do_GET(self):
        if self.path == "/_simulator/messages":
            with lock:
                return self.reply(200, {"messages": list(received)})
        if self.path == "/_simulator/health":
            return self.reply(200, {"status": "UP", "mode": "SIMULATED"})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 1_000_000:
            return self.reply(413, {"error": "too large"})
        body = self.rfile.read(length)
        parts = self.path.strip("/").split("/")
        if not signature_ok(self.headers, body):
            log(f"refused {self.path}: bad or missing signature")
            return self.reply(401, {"error": "bad signature"})
        try:
            doc = json.loads(body)
        except ValueError:
            return self.reply(400, {"error": "not JSON"})

        if parts == ["hmis", "reports"]:
            with lock:
                received.append({"partner": "sim-state-hmis", "kind": "HMIS_REPORT",
                                 "report_id": doc.get("report_id"), "received_at_utc": now()})
            log(f"HMIS report {doc.get('report_id')} received")
            return self.reply(202, {"partner_reference": f"SIM-HMIS-{uuid.uuid4().hex[:8].upper()}"})

        if len(parts) != 2 or parts[1] != "referrals" or parts[0] not in PARTNERS:
            return self.reply(404, {"error": "unknown partner endpoint"})
        partner_id = PARTNERS[parts[0]]
        try:
            request = next(e["resource"] for e in doc["entry"] if e["resource"]["resourceType"] == "ServiceRequest")
            referral_id, status = request["id"], request["status"]
            handoff = request["identifier"][0]["value"]
        except (KeyError, StopIteration, TypeError, IndexError):
            return self.reply(422, {"error": "not a referral bundle"})
        reference = f"SIM-{parts[0].upper()}-{uuid.uuid4().hex[:6].upper()}"
        with lock:
            received.append({"partner": partner_id, "kind": "REVOCATION" if status == "revoked" else "REFERRAL",
                             "referral_id": referral_id, "handoff_code": handoff, "priority": request.get("priority"),
                             "message_id": self.headers.get("X-Wellness-Message-Id"), "received_at_utc": now()})
        log(f"{partner_id} {status} {referral_id} (handoff {handoff})")
        if status == "active":
            threading.Thread(target=post_events, args=(partner_id, referral_id, reference), daemon=True).start()
        self.reply(202, {"partner_reference": reference})


if __name__ == "__main__":
    log("SIMULATED partners only: tele-manas, esanjeevani, hmis. Listening on :8090")
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
