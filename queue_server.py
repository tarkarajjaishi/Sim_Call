#!/usr/bin/env python3
"""Call queue -- the VPS half. The phone polls this; nothing ever connects to the phone.

    your code ──POST /enqueue──> [ queue_server.py on VPS ] <──GET /next── phone (call_poller.sh)
                                                                              │
                                                                              └─> dials via SIM

Both arrows point AT the VPS, which is the whole trick: the phone is behind
carrier NAT and cannot accept connections, but it can always make them.

Run on the VPS:
    export QUEUE_TOKEN=$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")
    echo "$QUEUE_TOKEN"          # put this same value in the phone's poller
    python3 queue_server.py

Put HTTPS in front of it. The token is a bearer credential and plain HTTP leaks
it to anyone on the path. One line of Caddy does it:
    caddy reverse-proxy --from calls.yourdomain.com --to :8000

Enqueue from your own Python:
    from queue_server import enqueue
    enqueue("9876543210")
"""
import itertools
import json
import os
import re
import secrets
import sys
import threading
import time
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def _tel(number):
    """Normalise and validate a phone number. Raises ValueError on anything else."""
    n = re.sub(r"[^\d+]", "", str(number))
    if not re.fullmatch(r"\+?\d{4,15}", n):
        raise ValueError(f"bad number: {number!r}")
    return n

TOKEN = os.environ.get("QUEUE_TOKEN", "")
PORT = int(os.environ.get("QUEUE_PORT", "8000"))
# Localhost by default: TLS is terminated by the reverse proxy in front. Binding
# 0.0.0.0 would expose the bearer token over plain HTTP to anyone who port-scans
# the VPS. Override only if you genuinely terminate TLS elsewhere.
BIND = os.environ.get("QUEUE_BIND", "127.0.0.1")
BASE = os.environ.get("QUEUE_URL", f"http://127.0.0.1:{PORT}")
HOLD = float(os.environ.get("QUEUE_HOLD", "25"))  # long-poll: seconds to hold an empty GET

# ponytail: in-memory, so a restart drops pending calls. Swap deque for a sqlite
# table if losing the queue on deploy ever actually costs you something.
QUEUE = deque()
ARRIVED = threading.Event()

# id -> {"job":..., "ok":bool, "error":str, "at":epoch}. Without this a job that
# the phone accepts but cannot execute (revoked permission, no signal) vanishes
# silently and the queue just looks drained.
RESULTS = {}
_IDS = itertools.count(1)


class Handler(BaseHTTPRequestHandler):
    def _authed(self):
        got = self.headers.get("Authorization", "")
        # compare_digest, not ==, so the token can't be guessed a byte at a time.
        return got.startswith("Bearer ") and secrets.compare_digest(got[7:], TOKEN)

    def _send(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._authed():
            return self._send(401, b"bad token")
        if self.path == "/results":
            return self._send(200, json.dumps(RESULTS).encode())
        if self.path != "/next":
            return self._send(404)
        # Long-poll: hold an empty request open instead of answering "nothing yet".
        # The phone dials the instant you enqueue, and idle polling drops to one
        # request per HOLD seconds instead of one every 10.
        if not QUEUE:
            ARRIVED.wait(HOLD)
            ARRIVED.clear()
        try:
            taken = QUEUE.popleft()            # popleft is atomic; another poller
        except IndexError:                     # may have taken it between the two
            return self._send(200, b"")
        # Mark it dispatched now. If the phone never reports back, this row stays
        # ok=None, which is how you spot a phone that died mid-job.
        RESULTS[taken["id"]] = {"job": taken, "ok": None, "error": "", "at": time.time()}
        self._send(200, json.dumps(taken).encode())

    def do_POST(self):
        if not self._authed():
            return self._send(401, b"bad token")
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
        if self.path == "/result":
            try:
                r = json.loads(raw)
                row = RESULTS.setdefault(int(r["id"]), {"job": None, "at": time.time()})
                row["ok"] = bool(r["ok"])
                row["error"] = str(r.get("error", ""))
            except (ValueError, TypeError, KeyError) as e:
                return self._send(400, f"{type(e).__name__}: {e}".encode())
            return self._send(200, b"recorded")
        if self.path != "/enqueue":
            return self._send(404)
        try:
            QUEUE.append(_job(json.loads(raw)))
        except (ValueError, TypeError, KeyError) as e:
            return self._send(400, f"{type(e).__name__}: {e}".encode())
        ARRIVED.set()                         # wake any phone parked on /next
        self._send(200, b"queued")

    def log_message(self, fmt, *a):
        print(f"{self.address_string()} {fmt % a}", file=sys.stderr)


def _job(d):
    """Validate an incoming job. Raises ValueError/KeyError on anything malformed."""
    kind = d["type"]
    if kind not in ("call", "sms"):
        raise ValueError(f"unknown type: {kind!r}")
    job = {"id": next(_IDS), "type": kind, "to": _tel(d["to"]), "sim": int(d.get("sim", 0))}
    if kind == "sms":
        text = str(d["text"])
        if not text:
            raise ValueError("empty sms text")
        job["text"] = text
    else:
        job["seconds"] = int(d.get("seconds", 45))
    return job


def _post(job, base=None):
    req = urllib.request.Request(
        (base or BASE).rstrip("/") + "/enqueue",
        data=json.dumps(job).encode(),
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status


def enqueue_call(number, seconds=45, sim=0, base=None):
    """Queue a call. sim=0 is the phone's default, 1 or 2 picks a slot."""
    return _post({"type": "call", "to": str(number), "seconds": seconds, "sim": sim}, base)


def enqueue_sms(number, text, sim=0, base=None):
    """Queue an SMS. Long text is split into parts by the app."""
    return _post({"type": "sms", "to": str(number), "text": text, "sim": sim}, base)


def results(base=None):
    """Outcome of every dispatched job. ok=True done, False failed, None no reply."""
    req = urllib.request.Request(
        (base or BASE).rstrip("/") + "/results",
        headers={"Authorization": f"Bearer {TOKEN}"},
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)


def _test():
    global TOKEN, HOLD
    import time
    import urllib.error

    TOKEN = "test-token"
    HOLD = 0.2  # keep the empty-queue asserts snappy
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_port}"

    def get(path, token=None):
        req = urllib.request.Request(
            base + path, headers={"Authorization": f"Bearer {token or TOKEN}"})
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()

    def post(path, body, token=None):
        req = urllib.request.Request(
            base + path, data=body.encode(),
            headers={"Authorization": f"Bearer {token or TOKEN}"})
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()

    def err(fn, *a):
        try:
            fn(*a)
        except urllib.error.HTTPError as e:
            return e.code
        raise AssertionError("expected an HTTP error")

    def next_job():
        code, body = get("/next")
        assert code == 200
        return json.loads(body) if body else None

    assert _tel("+91 98765 43210") == "+919876543210"   # normalises
    assert _tel("(987) 654-3210") == "9876543210"
    for bad in ("", "abc", "123", "9" * 20, "12+34"):
        try:
            _tel(bad)
        except ValueError:
            continue
        raise AssertionError(f"should have rejected {bad!r}")

    assert err(get, "/next", "wrong-token") == 401      # bad token refused on read
    assert err(get, "/nope") == 404
    assert next_job() is None                           # empty queue -> empty body

    # writing must be authed too, or anyone who finds the VPS can dial your SIM
    assert err(post, "/enqueue", '{"type":"call","to":"9876543210"}', "wrong-token") == 401

    # every malformed shape must be refused at the boundary, not passed to the phone
    for bad in ('not json', '{"type":"call"}', '{"type":"mine","to":"9876543210"}',
                '{"type":"call","to":"abc"}', '{"type":"sms","to":"9876543210"}',
                '{"type":"sms","to":"9876543210","text":""}'):
        assert err(post, "/enqueue", bad) == 400, bad

    assert enqueue_call("+91 98765 43210", seconds=30, sim=2, base=base) == 200
    assert enqueue_sms("9876543211", "hello there", base=base) == 200
    a, b = next_job(), next_job()
    assert {k: v for k, v in a.items() if k != "id"} == \
        {"type": "call", "to": "+919876543210", "sim": 2, "seconds": 30}
    assert {k: v for k, v in b.items() if k != "id"} == \
        {"type": "sms", "to": "9876543211", "sim": 0, "text": "hello there"}
    assert next_job() is None                           # drained, FIFO order held

    # dispatched-but-unreported reads as ok=None -- that's how a dead phone shows
    assert RESULTS[a["id"]]["ok"] is None
    assert post("/result", json.dumps({"id": a["id"], "ok": True}))[0] == 200
    assert post("/result", json.dumps(
        {"id": b["id"], "ok": False, "error": "revoked permission CALL_PHONE"}))[0] == 200
    got = results(base)
    assert got[str(a["id"])]["ok"] is True
    assert got[str(b["id"])]["ok"] is False
    assert "CALL_PHONE" in got[str(b["id"])]["error"]
    assert err(post, "/result", '{"id":1}') == 400      # malformed result refused

    # long-poll: a GET parked on an empty queue must wake on enqueue, not time out
    HOLD = 10
    threading.Timer(0.3, lambda: enqueue_call("5551234", base=base)).start()
    t0 = time.monotonic()
    assert next_job()["to"] == "5551234"
    assert time.monotonic() - t0 < 5, "woke on timeout, not on the enqueue"
    HOLD = 0.2

    srv.shutdown()
    print("ok")


if __name__ == "__main__":
    if sys.argv[1:2] == ["test"]:
        _test()
    elif not TOKEN:
        sys.exit('set QUEUE_TOKEN first:\n  export QUEUE_TOKEN=$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")')
    else:
        print(f"listening on {BIND}:{PORT}", file=sys.stderr)
        ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()
