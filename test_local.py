#!/usr/bin/env python3
"""End-to-end test with no VPS: run the queue on this PC and have the phone dial.

`adb reverse` makes the phone's own localhost:8000 tunnel back to this PC over
the USB cable, so the phone reaches the queue without any server, domain or TLS.

    python test_local.py 9744802942

Then in SimBridge on the phone set:
    Queue URL  http://127.0.0.1:8000/next
    Token      test-token-local-only
and tap "Save & start". Re-run this script to place another call.
"""
import subprocess
import sys
import threading
import time
from http.server import ThreadingHTTPServer

import queue_server as q
from sim import _adb_exe

TOKEN = "test-token-local-only"   # local loopback only; never used off-device
PORT = 8000


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    number = sys.argv[1]
    seconds = int(sys.argv[2]) if len(sys.argv) > 2 else 15

    # Phone's localhost:8000 -> this PC's localhost:8000, over the USB cable.
    subprocess.run([_adb_exe(), "reverse", f"tcp:{PORT}", f"tcp:{PORT}"], check=True)
    print(f"adb reverse tcp:{PORT} ready")

    q.TOKEN = TOKEN
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), q.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"queue listening on http://127.0.0.1:{PORT}")

    q.enqueue_call(number, seconds=seconds, base=f"http://127.0.0.1:{PORT}")
    print(f"queued: call {number} for {seconds}s -- waiting for the phone to take it")

    for _ in range(600):                      # 60s for the phone to poll
        if not q.QUEUE:
            print("phone took the job -- it should be dialing now")
            print(f"watch it hang up after ~{seconds}s")
            time.sleep(seconds + 5)
            return
        time.sleep(0.1)
    print("phone never collected the job. Check SimBridge is running "
          "and its URL is http://127.0.0.1:8000/next")


if __name__ == "__main__":
    main()
