"""Send SMS (and place calls) from your own SIM, with the phone anywhere on the internet.

SMS goes through the SMS Gateway for Android app (https://sms-gate.app).
In Cloud mode the phone connects *outbound* to sms-gate.app, so this script
runs anywhere -- laptop, VPS, cron -- with no cable, no port forwarding and
no USB debugging.

SMS setup:
  1. Install "SMS Gateway for Android" (F-Droid, or github.com/capcom6/android-sms-gateway)
  2. Open it, flip "Cloud Server" ON. Credentials appear on screen -- no signup.
  3. Set (leave SMSGATE_URL unset to use the cloud default):
       $env:SMSGATE_USER="<shown>"
       $env:SMSGATE_PASS="<shown>"
  For LAN-only use instead, flip "Local Server" ON and set
  SMSGATE_URL="http://<phone-ip>:8080".

CALLS: call() below drives the phone over adb and therefore needs USB
debugging. With no USB debugging there is no gateway app that can dial --
run call_poller.sh under Termux on the phone instead (see that file).

usage: python sim.py call NUM [SECONDS] | sms NUM TEXT | test
"""
import base64
import glob
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
import urllib.request

GATEWAY = os.environ.get("SMSGATE_URL", "https://api.sms-gate.app/3rdparty/v1")
USER = os.environ.get("SMSGATE_USER", "")
PASSWORD = os.environ.get("SMSGATE_PASS", "")


from queue_server import _tel  # noqa: E402  (single source of truth for numbers)


# ---------------------------------------------------------------- SMS (wifi)

def _req(number, text, sim=None):
    body = {"textMessage": {"text": text}, "phoneNumbers": [_tel(number)]}
    if sim is not None:
        body["simNumber"] = int(sim)  # 1-based; which SIM slot sends it
    auth = base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
    return urllib.request.Request(
        GATEWAY.rstrip("/") + "/message",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Basic {auth}"},
    )


def sms(number, text, sim=None):
    """Send an SMS. sim=1 or 2 picks the SIM slot on a dual-SIM phone."""
    if not (GATEWAY and USER and PASSWORD):
        sys.exit("set SMSGATE_URL / SMSGATE_USER / SMSGATE_PASS first -- see `python sim.py`")
    with urllib.request.urlopen(_req(number, text, sim), timeout=15) as r:
        return json.load(r)


# ---------------------------------------------------------------- CALL (usb)

def _adb_exe():
    """adb isn't on PATH after `winget install Google.PlatformTools`."""
    found = shutil.which("adb")
    if found:
        return found
    pattern = r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe"
    hits = glob.glob(os.path.expandvars(pattern))
    if not hits:
        sys.exit("adb not found -- run: winget install Google.PlatformTools")
    return hits[0]


def _adb(*args):
    # Quote for the *device's* shell -- adb concatenates argv and re-parses it there.
    cmd = " ".join(shlex.quote(a) for a in args)
    r = subprocess.run([_adb_exe(), "shell", cmd], capture_output=True, text=True, check=True)
    return r.stdout


def call(number, seconds=None):
    """Dial number. If seconds is given, hang up after that long."""
    _adb("am", "start", "-a", "android.intent.action.CALL", "-d", f"tel:{_tel(number)}")
    if seconds:
        time.sleep(seconds)
        hangup()


def hangup():
    _adb("input", "keyevent", "KEYCODE_ENDCALL")


# ponytail: calls always use the phone's default SIM. Picking a slot needs a
# per-vendor intent extra (com.android.phone.extra.slot / simSlot / subscription)
# that differs by manufacturer -- add it only if the default turns out wrong.


def _test():
    global GATEWAY, USER, PASSWORD
    assert _tel("+91 98765 43210") == "+919876543210"
    assert _tel("(987) 654-3210") == "9876543210"
    for bad in ("", "abc", "123", "9" * 20, "12+34"):
        try:
            _tel(bad)
        except ValueError:
            continue
        raise AssertionError(f"should have rejected {bad!r}")

    GATEWAY, USER, PASSWORD = "http://192.168.1.5:8080/", "bob", "hunter2"
    r = _req("+91 98765 43210", "hi there", sim=2)
    assert r.full_url == "http://192.168.1.5:8080/message", r.full_url
    assert base64.b64decode(r.headers["Authorization"].split()[1]) == b"bob:hunter2"
    assert json.loads(r.data) == {
        "textMessage": {"text": "hi there"},
        "phoneNumbers": ["+919876543210"],
        "simNumber": 2,
    }
    assert "simNumber" not in json.loads(_req("1234567", "x").data)

    GATEWAY = "https://api.sms-gate.app/3rdparty/v1"  # cloud mode uses the same builder
    assert _req("1234567", "x").full_url == "https://api.sms-gate.app/3rdparty/v1/message"

    assert " ".join(shlex.quote(a) for a in ("--es", "sms_body", "a b")) == "--es sms_body 'a b'"
    print("ok")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["test"]:
        _test()
    elif a[:1] == ["call"] and len(a) in (2, 3):
        call(a[1], float(a[2]) if len(a) == 3 else None)
    elif a[:1] == ["sms"] and len(a) >= 3:
        print(sms(a[1], " ".join(a[2:])))
    else:
        print(__doc__)
