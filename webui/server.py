#!/usr/bin/env python3
"""
monctl-web — local control panel replicating the monitor's OSD over DDC/CI.

Binds to 127.0.0.1 by default. --lan exposes it to the LAN, and a Cloudflare
tunnel may front it for the Android companion. Both are guarded by a token
(~/.config/monctl/token); only direct loopback requests skip it.

    python3 ~/monctl/webui/server.py [--port 8770]
"""

import argparse
import json
import os
import re
import secrets
import socket
import subprocess
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
# Built React bundle (Vite). Falls back to the vanilla index.html if absent,
# so the panel still works on a machine without a Node toolchain.
DIST = os.path.normpath(os.path.join(HERE, "..", "app", "dist"))
DDCPROBE = os.environ.get("DDCPROBE") or os.path.expanduser("~/bin/ddcprobe")

# DDC is a single serial bus. Concurrent requests corrupt each other, so every
# transaction goes through one lock.
BUS = threading.Lock()

# ---------------------------------------------------------------------------
# Control catalogue — mirrors the monitor's own OSD menu structure.
# Established empirically: 61 of 256 VCP codes answer on this panel.
# ---------------------------------------------------------------------------

CATALOG = [
    {
        "id": "console", "tier": "basic", "name": "Main", "layout": "faders",
        "blurb": "Brightness, volume, contrast, white point.",
        "controls": [
            {"code": "10", "name": "Brightness", "type": "fader"},
            {"code": "62", "name": "Volume",     "type": "fader"},
            {"code": "12", "name": "Contrast",   "type": "range"},
            {"code": "14", "name": "Colour temperature", "type": "select", "options": [
                [5, "Warm"], [6, "Normal"], [8, "Cool"], [11, "User"]]},
        ],
    },
    {
        "id": "input", "tier": "basic", "name": "Input", "layout": "input",
        "blurb": "Source select and timed cycling.",
        "controls": [
            {"code": "60", "name": "Source", "type": "select", "big": True,
             "options": [[17, "HDMI 1"], [18, "HDMI 2"]],
             "confirm": "Switching input drops this display's signal. Continue?"},
            {"code": "8D", "name": "Mute", "type": "select",
             "options": [[2, "Unmuted"], [1, "Muted"]]},
        ],
    },
    {
        "id": "gains", "tier": "advanced", "name": "Gain & black level",
        "blurb": "Per-channel white point trim.",
        "controls": [
            {"code": "16", "name": "Gain R", "type": "range", "tint": "#ff3b30"},
            {"code": "18", "name": "Gain G", "type": "range", "tint": "#00ff66"},
            {"code": "1A", "name": "Gain B", "type": "range", "tint": "#0a84ff"},
            {"code": "6C", "name": "Black R", "type": "range", "tint": "#ff3b30"},
            {"code": "6E", "name": "Black G", "type": "range", "tint": "#00ff66"},
            {"code": "70", "name": "Black B", "type": "range", "tint": "#0a84ff"},
        ],
    },
    {
        "id": "saturation", "tier": "advanced", "name": "Saturation",
        "blurb": "Six-axis, per primary and secondary.",
        "controls": [
            {"code": "59", "name": "Red",     "type": "range", "tint": "#ff3b30"},
            {"code": "5A", "name": "Yellow",  "type": "range", "tint": "#ffcc00"},
            {"code": "5B", "name": "Green",   "type": "range", "tint": "#00ff66"},
            {"code": "5C", "name": "Cyan",    "type": "range", "tint": "#00d5d5"},
            {"code": "5D", "name": "Blue",    "type": "range", "tint": "#0a84ff"},
            {"code": "5E", "name": "Magenta", "type": "range", "tint": "#ff2d95"},
        ],
    },
    {
        "id": "hue", "tier": "advanced", "name": "Hue",
        "blurb": "Six-axis, per primary and secondary.",
        "controls": [
            {"code": "9B", "name": "Red",     "type": "range", "tint": "#ff3b30"},
            {"code": "9C", "name": "Yellow",  "type": "range", "tint": "#ffcc00"},
            {"code": "9D", "name": "Green",   "type": "range", "tint": "#00ff66"},
            {"code": "9E", "name": "Cyan",    "type": "range", "tint": "#00d5d5"},
            {"code": "9F", "name": "Blue",    "type": "range", "tint": "#0a84ff"},
            {"code": "A0", "name": "Magenta", "type": "range", "tint": "#ff2d95"},
        ],
    },
    {
        "id": "system", "tier": "advanced", "name": "System",
        "blurb": "Power state and on-screen menu behaviour.",
        "controls": [
            # Labels corrected 2026-09-11 after measuring both values. 4 was
            # called "Standby": it is DPMS off, the screen goes dark but the
            # power LED stays lit. 5 is the one that behaves like the button.
            # The old confirm promised "monctl wake" as the way back, which is
            # not true of either -- the bus dies with the panel, so the wake
            # command has nothing to travel down. Measured: `set D6 1` reports
            # success five times against a 4 and nothing happens.
            {"code": "D6", "name": "Power state", "type": "select",
             "options": [[1, "On"],
                         [4, "Off — DPMS (LED stays lit)"],
                         [5, "Power off — as the button (LED out)"]],
             "confirm": "This blanks the monitor, and takes the DDC bus down "
                        "with it. Nothing on this Mac can undo it -- not "
                        "`monctl wake`, which reports success and does nothing. "
                        "Recovery is the monitor's physical power button. "
                        "For a dark screen you can undo from here, use Sleep "
                        "display under Quick actions."},
            {"code": "CA", "name": "OSD buttons", "type": "select",
             "options": [[1, "Locked"], [2, "Unlocked"]]},
            {"code": "AA", "name": "Screen orientation", "type": "range"},
            {"code": "CC", "name": "OSD language", "type": "range"},
        ],
    },
    {
        "id": "restore", "tier": "advanced", "name": "Restore",
        "blurb": "Factory resets. These write immediately.",
        "controls": [
            {"code": "05", "name": "Reset brightness & contrast", "type": "action",
             "confirm": "Restore factory brightness and contrast?"},
            {"code": "08", "name": "Reset colour", "type": "action",
             "confirm": "Restore factory colour settings?"},
            {"code": "04", "name": "Restore all factory defaults", "type": "action",
             "danger": True, "confirm": "Restore ALL factory defaults? Everything resets."},
        ],
    },
    {
        "id": "info", "tier": "advanced", "name": "Information",
        "blurb": "Read-only telemetry.",
        "controls": [
            {"code": "C0", "name": "Power-on hours",       "type": "info", "unit": "h"},
            {"code": "AE", "name": "Vertical frequency",   "type": "info", "scale": 0.01, "unit": "Hz"},
            {"code": "AC", "name": "Horizontal frequency", "type": "info"},
            {"code": "C9", "name": "Firmware level",       "type": "info"},
            {"code": "C8", "name": "Controller type",      "type": "info"},
            {"code": "B6", "name": "Technology type",      "type": "info"},
            {"code": "DF", "name": "MCCS version",         "type": "info", "hex": True},
        ],
    },
]

BY_CODE = {c["code"]: c for g in CATALOG for c in g["controls"]}

# ---------------------------------------------------------------------------
# ddcprobe wrappers
# ---------------------------------------------------------------------------


def _bus_dead(rc, stderr):
    """A sleeping panel takes its DDC bus with it. That is not a failed control."""
    return rc != 0 or "no DDC-capable" in (stderr or "")


def probe(args, timeout=90, revive=False):
    """Run ddcprobe.

    revive=True wakes the display and retries once if the bus is asleep. Only
    user-initiated actions may pass it: the panel drops its bus whenever the
    screen sleeps, so a background poll that revived would turn the monitor
    back on every few seconds -- the display would never stay off.
    """
    with BUS:
        try:
            r = subprocess.run([DDCPROBE] + args, capture_output=True,
                               text=True, timeout=timeout)
            if revive and _bus_dead(r.returncode, r.stderr):
                # -u declares user activity; -t 2 keeps the assertion brief.
                subprocess.run(["/usr/bin/caffeinate", "-u", "-t", "2"], timeout=8)
                time.sleep(2)
                r = subprocess.run([DDCPROBE] + args, capture_output=True,
                                   text=True, timeout=timeout)
            return r.stdout
        except (subprocess.TimeoutExpired, FileNotFoundError, OSError) as e:
            return ""


def read_many(codes, revive=False):
    """Batch read. Returns {code: {'cur': int, 'max': int}} — absent if unsupported.

    revive follows probe(): only a read that is the first half of a user's
    write (a nudge) may pass it.
    """
    codes = [c for c in codes if re.fullmatch(r"[0-9A-Fa-f]{2}", c)]
    if not codes:
        return {}
    out = {}
    for line in probe(["many"] + codes, revive=revive).splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1] != "-":
            try:
                out[parts[0].upper()] = {"cur": int(parts[1]), "max": int(parts[2])}
            except ValueError:
                pass
    return out


# ---------------------------------------------------------------------------
# AirPlay-display watcher (~/bin/airplay_watch.sh)
#
# Not a DDC control -- it lives here because it is the other thing you reach for
# when the panel misbehaves, and having it in two places (Touch Bar, menu bar)
# but not the panel you actually have open is just friction.
#
# Toggling shells out to the same airplay_toggle.sh the Touch Bar and menu bar
# use, rather than touching the flag file directly: that script also plays the
# distinct pause/resume sounds and speaks the new state, and a second
# implementation of one toggle would drift from the first.
# ---------------------------------------------------------------------------

WATCH_FLAG = "/tmp/airplay_watch.pause"
WATCH_TOGGLE = os.path.expanduser("~/bin/airplay_toggle.sh")


# ---------------------------------------------------------------------------
# Display power
#
# TWO different mechanisms, and the difference is the whole point:
#
#   sleep/wake  — macOS drops the video signal (`pmset displaysleepnow`). The
#                 monitor sleeps the way it does at idle: DDC stays alive, and
#                 `caffeinate -u` declares user activity to bring it back. Fully
#                 reversible, works remotely. Measured 2026-08-09: power state
#                 4 -> 1 -> 4, DDC responsive throughout.
#
#   off/on      — a DDC command (VCP 0xD6 = 4) telling the panel into DPMS off.
#                 Measured on this Acer: it drops its DDC controller, so the very
#                 bus needed to send the wake command disappears. `set D6 1`
#                 reports success five times and nothing happens. The power LED
#                 stays LIT -- this is a dark screen, not a powered-down monitor.
#                 Recovery is the monitor's PHYSICAL power button.
#
#   hardoff     — VCP 0xD6 = 5, defined by MCCS 2.1 as "turn off power, as if the
#                 user pressed the power button". Advertised by this panel:
#                 caps reads D6(01 04 05). Absent from a D6 read (current=1
#                 max=4) only because 5 is write-only.
#                 Verified 2026-09-11: the LED goes fully DARK, the DDC bus dies
#                 with the panel, and macOS never notices -- IODisplayWrangler
#                 still reports CurrentPowerState=4. This is the real button.
#                 Also one-way. Recovery is the physical button.
#
# So "sleep" is the button anyone should press. "off" and "hardoff" are kept
# because they are genuinely different from it and from each other -- both
# clearly labelled, both confirmed in the UI, neither ever the default.
# ---------------------------------------------------------------------------

def display_power_state():
    """4 = on, lower = progressively asleep. From IODisplayWrangler."""
    try:
        out = subprocess.run(["/usr/sbin/ioreg", "-n", "IODisplayWrangler",
                              "-r", "-d", "1"],
                             capture_output=True, text=True, timeout=6).stdout
        m = re.search(r'"CurrentPowerState"=(\d+)', out)
        return int(m.group(1)) if m else None
    except (OSError, subprocess.SubprocessError):
        return None


def display_action(action):
    try:
        if action == "sleep":
            subprocess.run(["/usr/bin/pmset", "displaysleepnow"], timeout=8)
        elif action == "wake":
            # -u declares user activity; -t 1 stops it holding the assertion.
            subprocess.run(["/usr/bin/caffeinate", "-u", "-t", "1"], timeout=8)
        elif action == "off":
            write_one("D6", 4)
        elif action == "on":
            # Unlike off/hardoff, an unacknowledged "on" means it did nothing:
            # the panel is either already on or has no bus to hear it.
            return write_one("D6", 1)
        elif action == "hardoff":
            # Return value deliberately ignored. The panel can drop the bus
            # before ddcctl gets its acknowledgement back, so a False here means
            # "no receipt", not "no effect" -- and reporting a 502 for a command
            # that just worked would send someone hunting a bug at a dark
            # screen. The GET /api/display poll cannot tell either: macOS still
            # reports the display as on.
            write_one("D6", 5)
        else:
            return False
        return True
    except (OSError, subprocess.SubprocessError):
        return False


def watcher_state():
    running = False
    try:
        running = subprocess.run(["/usr/bin/pgrep", "-f", "airplay_watch.sh"],
                                 capture_output=True, timeout=5).returncode == 0
    except (OSError, subprocess.SubprocessError):
        pass
    return {"installed": os.access(WATCH_TOGGLE, os.X_OK),
            "paused": os.path.exists(WATCH_FLAG),
            "running": running}


def watcher_toggle():
    if not os.access(WATCH_TOGGLE, os.X_OK):
        return False
    try:
        subprocess.Popen(["/bin/bash", "-lc", WATCH_TOGGLE],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except OSError:
        return False


WATCH_AGENT = "com.dell.airplaywatch"


def watcher_set(want_on):
    """Explicit start/stop for remote callers, where a blind toggle could act
    on a stale view and flip the wrong way.

    Stop = the pause flag, via airplay_toggle.sh so the usual sound plays. The
    process itself is left alone: its LaunchAgent has KeepAlive, so killing it
    would only respawn it. Start = clear the pause, and kickstart the agent if
    the process has died.
    """
    st = watcher_state()
    if not st["installed"]:
        return False
    if want_on and not st["running"]:
        _lc("kickstart", f"gui/{_uid()}/{WATCH_AGENT}")
    if st["paused"] == want_on:          # paused and want on, or running and want off
        if not watcher_toggle():
            return False
        time.sleep(0.35)                 # toggle writes the flag, then plays a sound
    return True


def write_one(code, value):
    if not re.fullmatch(r"[0-9A-Fa-f]{2}", code):
        return False
    if not isinstance(value, int) or not (0 <= value <= 65535):
        return False
    # A write is always someone pressing something, so it may wake the panel to
    # be delivered. Reads must not -- see probe().
    ok = "<-" in probe(["set", code, str(value)], timeout=20, revive=True)
    if ok and code.upper() == "60":
        LAST_INPUT["code"] = value
        LAST_INPUT["at"] = time.time()
    return ok


# Reading VCP 0x60 on this Acer is useless: it answers 15 (DisplayPort) with
# max=1 whatever input is showing -- the query arrives over the DP cable. So
# the only input knowledge there is, is the last one *we* switched to (web UI,
# cycler, Android app). OSD-button or auto-source changes are invisible.
LAST_INPUT: dict = {"code": None, "at": None}


# ---------------------------------------------------------------------------
# Remote (Android app and widget)
#
# One request per screen refresh and one per button press. The phone may be on
# the tunnel with a few hundred ms of latency, so the snapshot and relative
# steps are assembled here instead of by chaining /api/display, /api/state and
# /api/set from the client.
# ---------------------------------------------------------------------------

# The OSD catalog lists HDMI only for the web panel; the phone also offers
# DisplayPort, which is the Mac itself, so "back to the Mac" is one tap.
REMOTE_INPUTS = [[15, "DisplayPort"], [17, "HDMI 1"], [18, "HDMI 2"]]

# Update channel for the Android app. android/publish.sh builds the APK and
# drops it here with a version.json beside it; the app polls /api/app/version
# and downloads /api/app/apk over the same authenticated route it uses for
# everything else. Android itself refuses an update signed by another key, so
# the token and that signature check are the whole trust story -- no public
# release page needed.
APK_CHANNEL = os.path.expanduser("~/.local/share/monctl/android")


def app_version():
    """{versionCode, versionName, sha256, size} of the published APK, or None."""
    try:
        with open(os.path.join(APK_CHANNEL, "version.json")) as f:
            v = json.load(f)
        if os.path.isfile(os.path.join(APK_CHANNEL, "MonCtl.apk")):
            return v
    except (OSError, ValueError):
        pass
    return None
NUDGEABLE = ("10", "62")
# Read-modify-write must not interleave: two quick widget taps would both read
# 50 and both write 60.
NUDGE = threading.Lock()


def remote_snapshot():
    """Display power plus the four controls the phone shows.

    A background read: never revives. When macOS reports the display asleep the
    DDC bus is gone with it, so the read is skipped rather than left to fail.
    reachable=False with asleep=False means the Mac thinks the panel is on but
    DDC is dead -- what 'off'/'hardoff' or the physical button leave behind.
    """
    st = display_power_state()
    asleep = st is not None and st < 4
    # "input" stays null: see LAST_INPUT for why the panel's own answer is
    # not reported. last_input is what monctl last switched it to.
    out = {"asleep": asleep, "reachable": False, "inputs": REMOTE_INPUTS,
           "brightness": None, "volume": None, "muted": None, "input": None,
           "last_input": LAST_INPUT["code"], "last_input_at": LAST_INPUT["at"],
           "watcher": watcher_state()}
    if asleep:
        return out
    v = read_many(["10", "62", "8D"])
    out["reachable"] = bool(v)
    out["brightness"] = v.get("10")
    out["volume"] = v.get("62")
    if "8D" in v:
        out["muted"] = v["8D"]["cur"] == 1
    return out


def nudge(code, delta):
    """Step a fader relative to its live value. None = the panel did not answer."""
    with NUDGE:
        v = read_many([code], revive=True).get(code)
        if not v:
            return None
        new = max(0, min(v["max"], v["cur"] + delta))
        if new != v["cur"] and not write_one(code, new):
            return None
        return {"cur": new, "max": v["max"]}


# ---------------------------------------------------------------------------
# Fine brightness — software gamma
#
# VCP 0x10 is an integer 0-100; nothing exists between two steps. Scaling the
# display's gamma ramp is continuous and costs zero DDC traffic, so it is the
# real fine adjust on top of whatever coarse step the backlight sits on.
#
# CoreGraphics reverts gamma when the process that set it exits, so it must be
# applied from THIS process, which is long-lived. A helper binary cannot hold it.
# ---------------------------------------------------------------------------

import ctypes
import ctypes.util

_cg = ctypes.CDLL(ctypes.util.find_library("CoreGraphics"))
_cg.CGGetOnlineDisplayList.argtypes = [ctypes.c_uint32,
                                       ctypes.POINTER(ctypes.c_uint32),
                                       ctypes.POINTER(ctypes.c_uint32)]
_cg.CGDisplayIsBuiltin.argtypes = [ctypes.c_uint32]
_cg.CGSetDisplayTransferByFormula.argtypes = [ctypes.c_uint32] + [ctypes.c_float] * 9
_cg.CGSetDisplayTransferByFormula.restype = ctypes.c_int32

DIM = {"level": 1.0}
DIM_FLOOR = 0.15


def _external_displays():
    ids = (ctypes.c_uint32 * 16)()
    n = ctypes.c_uint32(0)
    if _cg.CGGetOnlineDisplayList(16, ids, ctypes.byref(n)) != 0:
        return []
    return [ids[i] for i in range(n.value) if not _cg.CGDisplayIsBuiltin(ids[i])]


def dim_apply(level):
    level = max(DIM_FLOOR, min(1.0, float(level)))
    ok = False
    for d in _external_displays():
        if _cg.CGSetDisplayTransferByFormula(
                d, 0.0, level, 1.0, 0.0, level, 1.0, 0.0, level, 1.0) == 0:
            ok = True
    if ok:
        DIM["level"] = level
    return ok, level


# ---------------------------------------------------------------------------
# The Mac itself — what the MTMR Touch Bar shows and does
#
# Readouts (battery, volume, built-in brightness, Wi-Fi, AirPlay) and the
# Touch Bar's actions, for the phone. Every action is a fixed script from
# ~/bin with validated arguments, never a command line built from the request
# and never whatever happens to be in MTMR's items.json: these routes are
# reachable through the tunnel.
#
# The Touch Bar's own menu scripts (wifi_menu.sh, airplay_menu.sh list) are
# not reused: they rewrite items.json and restart MTMR. The pieces under them
# are -- wifihelper scan, airplay_devices.sh, wifi_join.sh -- and
# airplay_pick.sh / airplay_off.sh skip their MTMR reload when MONCTL_NO_MENU
# is set.
# ---------------------------------------------------------------------------

BIN = os.path.expanduser("~/bin")
WIFIHELPER = "/Applications/WifiHelper.app/Contents/MacOS/wifihelper"
AIRPLAY_SUP = os.path.expanduser("~/Library/Application Support/AirPlayPad")
AIRPLAY_STATUS = "/tmp/airplay_status.txt"
WIFI_IF = "en0"
MAC_ACTION = threading.Lock()    # one Wi-Fi/AirPlay action at a time

_ds = None
try:
    _ds = ctypes.CDLL("/System/Library/PrivateFrameworks/DisplayServices.framework/DisplayServices")
    _ds.DisplayServicesGetBrightness.argtypes = [ctypes.c_uint32, ctypes.POINTER(ctypes.c_float)]
    _ds.DisplayServicesSetBrightness.argtypes = [ctypes.c_uint32, ctypes.c_float]
except OSError:
    pass


def _run(args, timeout=10, env=None):
    """stdout of a fixed command, or None. Never raises."""
    try:
        e = None
        if env:
            e = dict(os.environ, **env)
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=e)
        return r.stdout if r.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def _builtin_display():
    ids = (ctypes.c_uint32 * 16)()
    n = ctypes.c_uint32(0)
    if _cg.CGGetOnlineDisplayList(16, ids, ctypes.byref(n)) != 0:
        return None
    for i in range(n.value):
        if _cg.CGDisplayIsBuiltin(ids[i]):
            return ids[i]
    return None      # lid closed: the panel is offline


def battery_state():
    out = _run(["/usr/bin/pmset", "-g", "batt"], timeout=5) or ""
    m = re.search(r"(\d+)%;\s*([^;]+)", out)
    if not m:
        return None
    status = m.group(2).strip()
    return {"pct": int(m.group(1)),
            "ac": "AC Power" in out,
            "charging": status == "charging",
            "status": status}


def mac_volume():
    out = _run(["/usr/bin/osascript", "-e", "get volume settings"], timeout=5) or ""
    vol = re.search(r"output volume:(\d+)", out)
    muted = re.search(r"output muted:(true|false)", out)
    # An output macOS can't set (HDMI/DP audio) reports "missing value".
    return {"volume": int(vol.group(1)) if vol else None,
            "muted": (muted.group(1) == "true") if muted else None}


def mac_volume_set(value=None, muted=None):
    if value is not None:
        cmd = f"set volume output volume {int(value)}"
    else:
        cmd = f"set volume output muted {'true' if muted else 'false'}"
    return _run(["/usr/bin/osascript", "-e", cmd], timeout=5) is not None


def mac_brightness():
    d = _builtin_display()
    if _ds is None or d is None:
        return None
    f = ctypes.c_float(-1)
    if _ds.DisplayServicesGetBrightness(d, ctypes.byref(f)) != 0:
        return None
    return round(f.value * 100)


def mac_brightness_set(pct):
    d = _builtin_display()
    if _ds is None or d is None:
        return False
    return _ds.DisplayServicesSetBrightness(d, max(0.0, min(1.0, pct / 100.0))) == 0


def _remembered_networks():
    out = _run(["/usr/sbin/networksetup", "-listpreferredwirelessnetworks", WIFI_IF], timeout=5) or ""
    return [l.strip() for l in out.splitlines()[1:] if l.strip()]


def wifi_state():
    power = _run(["/usr/sbin/networksetup", "-getairportpower", WIFI_IF], timeout=5) or ""
    on = power.strip().endswith("On")
    ssid = None
    if on and os.access(WIFIHELPER, os.X_OK):
        # WifiHelper holds the Location grant that un-redacts the SSID.
        ssid = ((_run([WIFIHELPER, "current"], timeout=5) or "").strip().splitlines() or [None])[0]
    route = _run(["/sbin/route", "-n", "get", "default"], timeout=5) or ""
    m = re.search(r"interface:\s*(\S+)", route)
    iface = m.group(1) if m else None
    return {"power": on, "ssid": ssid or None,
            "wired": bool(iface and iface != WIFI_IF and not iface.startswith("utun")),
            "online": iface is not None}


def wifi_on():
    return _run(["/bin/bash", os.path.join(BIN, "wifi_on.sh")], timeout=15) is not None


def wifi_scan():
    if not os.access(WIFIHELPER, os.X_OK):
        return None
    out = _run([WIFIHELPER, "scan"], timeout=20)
    if out is None:
        return None
    remembered = set(_remembered_networks())
    current = wifi_state()["ssid"]
    seen, nets = set(), []
    for name in (l.strip() for l in out.splitlines()):   # strongest first
        if name and name not in seen:
            seen.add(name)
            nets.append({"ssid": name, "remembered": name in remembered,
                         "current": name == current})
    return nets


def wifi_join(ssid, password=None):
    """wifi_join.sh pulls a remembered network's password from the keychain.
    Returns (ok, error)."""
    args = ["/bin/bash", os.path.join(BIN, "wifi_join.sh"), ssid]
    if password:
        args.append(password)
    with MAC_ACTION:
        before = os.stat(AIRPLAY_STATUS).st_mtime if os.path.exists(AIRPLAY_STATUS) else 0
        try:
            subprocess.run(args, capture_output=True, timeout=45)
        except subprocess.TimeoutExpired:
            return False, "join timed out (a keychain prompt on the Mac?)"
        except OSError as e:
            return False, str(e)
    # wifi_join.sh exits 0 either way; on failure it writes this line to the
    # Touch Bar status file. Checked first, since rejoining the current
    # network would otherwise look like success.
    try:
        if os.stat(AIRPLAY_STATUS).st_mtime > before and "join failed" in (_read1(AIRPLAY_STATUS) or ""):
            return False, "join failed (wrong password, or out of range)"
    except OSError:
        pass
    for _ in range(10):                    # association lags the helper's exit
        if wifi_state()["ssid"] == ssid:
            return True, None
        time.sleep(0.5)
    return False, f"not on {ssid} after joining"


def _read1(path):
    try:
        with open(path) as f:
            return f.readline().strip() or None
    except OSError:
        return None


def airplay_state():
    paused = os.path.exists(WATCH_FLAG)
    return {"status": "⏸ paused" if paused else _read1(AIRPLAY_STATUS),
            "target": _read1(os.path.join(AIRPLAY_SUP, "target_name.txt")),
            "target_mac": _read1(os.path.join(AIRPLAY_SUP, "target_mac.txt")),
            "paused": paused}


MAC_ADDR = re.compile(r"[0-9a-f]{2}(:[0-9a-f]{2}){5}")


def airplay_scan():
    with MAC_ACTION:
        out = _run(["/bin/bash", os.path.join(BIN, "airplay_devices.sh")], timeout=60)
    if out is None:
        return None
    cur = airplay_state()["target_mac"]
    devs = []
    for line in out.splitlines():
        mac, _, name = line.partition("\t")
        if MAC_ADDR.fullmatch(mac) and name:
            devs.append({"mac": mac, "name": name, "current": mac == cur})
    return devs


def airplay_connect(mac, name):
    import base64
    b64 = lambda s: base64.b64encode(s.encode()).decode()
    with MAC_ACTION:
        return _run(["/bin/bash", os.path.join(BIN, "airplay_pick.sh"), b64(mac), b64(name)],
                    timeout=30, env={"MONCTL_NO_MENU": "1"}) is not None


def airplay_disconnect():
    with MAC_ACTION:
        return _run(["/bin/bash", os.path.join(BIN, "airplay_off.sh")],
                    timeout=30, env={"MONCTL_NO_MENU": "1"}) is not None


def mac_snapshot():
    # Each readout is its own short subprocess; serially they add up to ~2 s.
    from concurrent.futures import ThreadPoolExecutor
    parts = {"battery": battery_state, "volume": mac_volume, "brightness": mac_brightness,
             "lid": lid_state, "wifi": wifi_state, "airplay": airplay_state,
             "watcher": watcher_state}
    with ThreadPoolExecutor(len(parts)) as ex:
        futs = {k: ex.submit(f) for k, f in parts.items()}
        got = {k: f.result() for k, f in futs.items()}
    vol = got.pop("volume")
    return {**got, "volume": vol["volume"], "muted": vol["muted"]}


# ---------------------------------------------------------------------------
# Screen preview
#
# A JPEG of one display, for the phone to poll about once a second. Viewing is
# a background read in the sleeping-bus sense: an asleep display is reported,
# never woken. Through the tunnel it is refused unless SCREEN_REMOTE_FLAG
# exists -- the screen is the most private thing this server can hand out.
#
# Capture is by global rectangle (screencapture -R), not -D <n>: the -D index
# reshuffles whenever the AirPlay display comes and goes. Mirrored displays
# (the Pad mirrors the built-in) are folded into the display they mirror.
# Needs Screen Recording permission for the process running this server.
# ---------------------------------------------------------------------------

SCREEN_REMOTE_FLAG = os.path.expanduser("~/.config/monctl/screen_remote")
SCREEN = threading.Lock()
SCREEN_CACHE = {"key": None, "t": 0.0, "jpg": None}
SCREEN_ASKED = {"done": False}


class _CGPoint(ctypes.Structure):
    _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]


class _CGSize(ctypes.Structure):
    _fields_ = [("w", ctypes.c_double), ("h", ctypes.c_double)]


class _CGRect(ctypes.Structure):
    _fields_ = [("origin", _CGPoint), ("size", _CGSize)]


_cg.CGGetActiveDisplayList.argtypes = _cg.CGGetOnlineDisplayList.argtypes
_cg.CGDisplayBounds.argtypes = [ctypes.c_uint32]
_cg.CGDisplayBounds.restype = _CGRect
_cg.CGDisplayMirrorsDisplay.argtypes = [ctypes.c_uint32]
_cg.CGDisplayMirrorsDisplay.restype = ctypes.c_uint32
_cg.CGDisplayIsAsleep.argtypes = [ctypes.c_uint32]
_cg.CGDisplayVendorNumber.argtypes = [ctypes.c_uint32]
_cg.CGDisplayVendorNumber.restype = ctypes.c_uint32
_cg.CGDisplayIsMain.argtypes = [ctypes.c_uint32]
_cg.CGPreflightScreenCaptureAccess.restype = ctypes.c_bool
_cg.CGRequestScreenCaptureAccess.restype = ctypes.c_bool

VENDORS = {0x0472: "Acer", 0x0610: "Apple", 0x6161706C: "AirPlay"}


def screen_displays():
    ids = (ctypes.c_uint32 * 16)()
    n = ctypes.c_uint32(0)
    if _cg.CGGetActiveDisplayList(16, ids, ctypes.byref(n)) != 0:
        return []
    all_ids = [ids[i] for i in range(n.value)]
    # A display in a hardware mirror set is online but not active, so look for
    # mirrors among the online ones.
    on = (ctypes.c_uint32 * 16)()
    m_n = ctypes.c_uint32(0)
    online = [on[i] for i in range(m_n.value)] \
        if _cg.CGGetOnlineDisplayList(16, on, ctypes.byref(m_n)) == 0 else all_ids
    mirrors = {}
    for d in online:
        m = _cg.CGDisplayMirrorsDisplay(d)
        if m:
            mirrors.setdefault(m, []).append(d)
    out = []
    for d in all_ids:
        if _cg.CGDisplayMirrorsDisplay(d):
            continue
        b = _cg.CGDisplayBounds(d)
        # When the built-in mirrors an AirPlay display, the AirPlay one is the
        # master of the set -- but what it shows is the built-in's picture.
        group = [d] + mirrors.get(d, [])
        builtin = any(_cg.CGDisplayIsBuiltin(x) for x in group)
        name = "Built-in" if builtin else VENDORS.get(_cg.CGDisplayVendorNumber(d), "Display")
        out.append({"id": d, "name": name, "builtin": builtin,
                    "main": bool(_cg.CGDisplayIsMain(d)),
                    "mirrored_on": len(mirrors.get(d, [])),
                    "asleep": bool(_cg.CGDisplayIsAsleep(d)),
                    "x": int(b.origin.x), "y": int(b.origin.y),
                    "w": int(b.size.w), "h": int(b.size.h)})
    return out


def screen_capture(display_id, width):
    """(status, jpeg bytes or error dict)."""
    if not _cg.CGPreflightScreenCaptureAccess():
        if not SCREEN_ASKED["done"]:
            SCREEN_ASKED["done"] = True     # macOS shows its prompt once per process
            _cg.CGRequestScreenCaptureAccess()
        return 403, {"error": "Screen Recording permission is off for the monctl server "
                              "(System Settings > Privacy & Security > Screen & System Audio "
                              "Recording: allow python3, then restart the server)",
                     "permission": False}
    disp = next((d for d in screen_displays() if d["id"] == display_id), None)
    if disp is None:
        return 404, {"error": "no such display"}
    st = display_power_state()
    if disp["asleep"] or (st is not None and st < 4):
        return 409, {"error": "display asleep", "asleep": True}
    key = (display_id, width)
    with SCREEN:
        if SCREEN_CACHE["key"] == key and time.monotonic() - SCREEN_CACHE["t"] < 0.5:
            return 200, SCREEN_CACHE["jpg"]
        tmp = f"/tmp/monctl-screen-{os.getpid()}"
        rect = f"{disp['x']},{disp['y']},{disp['w']},{disp['h']}"
        try:
            if _run(["/usr/sbin/screencapture", "-x", "-R", rect, "-t", "jpg", tmp + "-full.jpg"],
                    timeout=10) is None:
                return 502, {"error": "capture failed"}
            if _run(["/usr/bin/sips", "-Z", str(width), "-s", "formatOptions", "60",
                     tmp + "-full.jpg", "--out", tmp + ".jpg"], timeout=10) is None:
                return 502, {"error": "resize failed"}
            with open(tmp + ".jpg", "rb") as f:
                jpg = f.read()
        except OSError as e:
            return 502, {"error": str(e)}
        finally:
            for p in (tmp + "-full.jpg", tmp + ".jpg"):
                try:
                    os.unlink(p)
                except OSError:
                    pass
        SCREEN_CACHE.update(key=key, t=time.monotonic(), jpg=jpg)
        return 200, jpg


# ---------------------------------------------------------------------------
# Start at login
#
# Uses `launchctl enable/disable` rather than load/unload on purpose: disable
# persists across reboots but does NOT terminate a running job, so switching
# autostart off from this UI cannot kill the server answering the request.
# ---------------------------------------------------------------------------

AGENT_LABEL = "com.dell.monctl.web"
AGENT_SRC = os.path.normpath(os.path.join(HERE, "..", "com.dell.monctl.web.plist"))
AGENT_DST = os.path.expanduser(f"~/Library/LaunchAgents/{AGENT_LABEL}.plist")
SERVER_PATH = os.path.join(HERE, "server.py")


def _uid():
    return os.getuid()


def _lc(*args, timeout=10):
    try:
        return subprocess.run(["launchctl", *args], capture_output=True,
                              text=True, timeout=timeout)
    except (subprocess.TimeoutExpired, OSError):
        return None


def agent_installed():
    return os.path.exists(AGENT_DST)


def agent_disabled():
    r = _lc("print-disabled", f"gui/{_uid()}")
    if not r:
        return False
    # launchctl prints:  "com.dell.monctl.web" => disabled
    for line in r.stdout.splitlines():
        if AGENT_LABEL in line and "=>" in line:
            return line.split("=>")[-1].strip().rstrip(",").lower() == "disabled"
    return False


def autostart_state():
    return {"installed": agent_installed(),
            "enabled": agent_installed() and not agent_disabled(),
            "label": AGENT_LABEL,
            "plist": AGENT_DST}


def autostart_set(on):
    """Install/enable or disable the login agent. Never stops a running server."""
    if on:
        try:
            with open(AGENT_SRC) as f:
                plist = f.read().replace("__SERVER__", SERVER_PATH)
            os.makedirs(os.path.dirname(AGENT_DST), exist_ok=True)
            with open(AGENT_DST, "w") as f:
                f.write(plist)
        except OSError as e:
            return False, str(e)
        _lc("enable", f"gui/{_uid()}/{AGENT_LABEL}")
        # bootstrap so it is live now too; harmless if already loaded, and the
        # duplicate exits 0 because the port is taken.
        _lc("bootstrap", f"gui/{_uid()}", AGENT_DST)
        return True, None

    _lc("disable", f"gui/{_uid()}/{AGENT_LABEL}")
    return True, None


# ---------------------------------------------------------------------------
# Auto-brightness
#
# The daemon is one shared external process, not a child of whoever started it,
# so the menu bar app and this server both see the same state instead of each
# tracking its own copy. pgrep is the source of truth.
# ---------------------------------------------------------------------------

AUTOBRIGHT = os.path.normpath(os.path.join(HERE, "..", "autobright.py"))
ALSREAD = os.environ.get("ALSREAD") or os.path.expanduser("~/bin/alsread")
AUTO_PATTERN = "autobright.py"


_auto_cache = {"t": 0.0, "v": False}


def auto_running():
    """pgrep costs ~95ms and every open tab polls this every 4s. Cache briefly."""
    now = time.monotonic()
    if now - _auto_cache["t"] < 2.0:
        return _auto_cache["v"]
    try:
        r = subprocess.run(["pgrep", "-f", AUTO_PATTERN],
                           capture_output=True, text=True, timeout=5)
        val = bool(r.stdout.strip())
    except (subprocess.TimeoutExpired, OSError):
        val = False
    _auto_cache.update(t=now, v=val)
    return val


def auto_start(opts):
    if auto_running():
        return False
    cmd = ["python3", AUTOBRIGHT]
    for flag in ("min", "max", "gain", "interval", "closed"):
        v = opts.get(flag)
        if isinstance(v, (int, float)):
            cmd += [f"--{flag}", str(v)]
    try:
        log = open("/tmp/monctl-auto.log", "a")
        subprocess.Popen(cmd, stdout=log, stderr=log, start_new_session=True)
        return True
    except OSError:
        return False


def auto_stop():
    try:
        subprocess.run(["pkill", "-f", AUTO_PATTERN], capture_output=True, timeout=5)
    except (subprocess.TimeoutExpired, OSError):
        pass
    return True


_lux_cache = {"t": 0.0, "v": None}


def read_lux():
    now = time.monotonic()
    if now - _lux_cache["t"] < 2.0:
        return _lux_cache["v"]
    try:
        out = subprocess.run([ALSREAD], capture_output=True, text=True, timeout=5).stdout
        val = float(out.strip())
    except (ValueError, subprocess.TimeoutExpired, OSError):
        val = None
    _lux_cache.update(t=now, v=val)
    return val


def lid_state():
    try:
        out = subprocess.run(["ioreg", "-r", "-k", "AppleClamshellState", "-d", "4"],
                             capture_output=True, text=True, timeout=5).stdout
        m = re.search(r'"AppleClamshellState"\s*=\s*(\w+)', out)
        return "closed" if (m and m.group(1) == "Yes") else "open"
    except (subprocess.TimeoutExpired, OSError):
        return "unknown"


# ---------------------------------------------------------------------------
# Timed input cycler
#
# The monitor's own auto-source dwell is firmware behaviour — MCCS defines no
# VCP for it, so it cannot be slowed down over DDC. This drives VCP 0x60 from
# the host instead, holding each input for `dwell` seconds. Useful for probing
# which inputs are live; it is NOT a replacement for the monitor's internal
# auto-source, because it only runs while this Mac still owns the panel.
# ---------------------------------------------------------------------------

CYCLE = {
    "thread": None,
    "stop": threading.Event(),
    "running": False,
    "dwell": 20,
    "current": None,
    "inputs": [15, 17, 18],
}


def cycle_worker(dwell, inputs):
    try:
        while not CYCLE["stop"].is_set():
            for src in inputs:
                if CYCLE["stop"].is_set():
                    break
                CYCLE["current"] = src
                write_one("60", src)
                print(f"[cycle] input -> {src}, holding {dwell}s", flush=True)
                if CYCLE["stop"].wait(dwell):
                    break
    finally:
        CYCLE["running"] = False
        CYCLE["current"] = None
        print("[cycle] stopped", flush=True)


def cycle_start(dwell, inputs):
    if CYCLE["running"]:
        return False
    CYCLE["stop"].clear()
    CYCLE["dwell"] = dwell
    CYCLE["inputs"] = inputs
    CYCLE["running"] = True
    t = threading.Thread(target=cycle_worker, args=(dwell, inputs), daemon=True)
    CYCLE["thread"] = t
    t.start()
    return True


def cycle_stop():
    CYCLE["stop"].set()
    return True


def capabilities():
    return probe(["caps"], timeout=120).strip()


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


# Failed-token throttle, keyed by the real client (Cf-Connecting-IP through the
# tunnel). A 43-character token is not guessable anyway; this keeps a scanner
# that found the hostname from spinning on it and filling the log.
FAIL_LIMIT, FAIL_WINDOW, FAIL_LOCKOUT = 10, 600, 600
FAILS = {}            # peer -> [timestamps of recent failures]
LOCKED = {}           # peer -> unlock time
FAIL_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # Set from main(). Empty string = no auth (the historic localhost-only mode).
    token = ""

    def _via_tunnel(self):
        """cloudflared connects from 127.0.0.1, so the TCP peer alone would make
        every internet request look local. Cloudflare stamps these headers on
        everything it forwards. Trusting them is safe in one direction only: a
        client that adds them makes its own check stricter, never looser."""
        return bool(self.headers.get("Cf-Connecting-IP") or self.headers.get("Cf-Ray"))

    def _peer(self):
        return self.headers.get("Cf-Connecting-IP") or self.client_address[0]

    def _locked_out(self):
        with FAIL_LOCK:
            until = LOCKED.get(self._peer())
            if until and until > time.time():
                return True
            LOCKED.pop(self._peer(), None)
            return False

    def _deny(self):
        """401, counting the failure toward a lockout."""
        now = time.time()
        peer = self._peer()
        with FAIL_LOCK:
            recent = [t for t in FAILS.get(peer, []) if now - t < FAIL_WINDOW] + [now]
            FAILS[peer] = recent
            if len(recent) >= FAIL_LIMIT:
                LOCKED[peer] = now + FAIL_LOCKOUT
                FAILS.pop(peer, None)
                print(f"[auth] {peer} locked out for {FAIL_LOCKOUT}s", flush=True)
        return self._send(401, {"error": "token required"})

    def _gate(self):
        """Run before any route. Returns True if the request was already answered."""
        if self._locked_out():
            self._send(429, {"error": "too many bad tokens; try later"})
            return True
        if not self._authed():
            self._deny()
            return True
        return False

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def _authed(self):
        """Token may arrive as ?k=, as X-Monctl-Token, or as the monctl_k cookie.

        The COOKIE is what makes this usable in a browser. Only the initial
        navigation carries ?k=; the <script> and <link> tags the page then loads
        are plain "/assets/..." with no query string, so a query-only check 401s
        them and the user gets a blank white page with the HTML having loaded
        fine. Accepting a cookie -- set on the first authorised hit -- means
        every subsequent request carries auth without touching any markup.

        Compared with compare_digest so a wrong guess cannot be narrowed down by
        timing. Loopback is exempt: the token guards the LAN and the tunnel, and
        requiring it locally would break the menu bar app's one-click open. A
        tunnelled request also arrives from loopback, so it is not exempt.
        """
        if not self.token:
            return True
        if self.client_address[0] in ("127.0.0.1", "::1") and not self._via_tunnel():
            return True
        supplied = (parse_qs(urlparse(self.path).query).get("k", [""])[0]
                    or self.headers.get("X-Monctl-Token", "")
                    or self._cookie_token())
        return secrets.compare_digest(supplied, self.token)

    def _cookie_token(self):
        raw = self.headers.get("Cookie", "")
        for part in raw.split(";"):
            name, _, value = part.strip().partition("=")
            if name == "monctl_k":
                return value
        return ""

    def _query_token_ok(self):
        """True when THIS request carried a valid token in its query string,
        i.e. it is the navigation we should hand a cookie to."""
        if not self.token:
            return False
        k = parse_qs(urlparse(self.path).query).get("k", [""])[0]
        return bool(k) and secrets.compare_digest(k, self.token)

    def log_message(self, fmt, *a):
        pass  # quiet

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # Hand out the cookie on any request that proved itself via ?k=, so the
        # assets and API calls that follow need no query string.
        if self._query_token_ok():
            self.send_header(
                "Set-Cookie",
                f"monctl_k={self.token}; Path=/; Max-Age=31536000; SameSite=Lax")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)

        # Unauthenticated identity check. The Android app calls this on the LAN
        # address before sending its token in cleartext: another Wi-Fi network
        # can have a different device at the same private IP.
        if u.path == "/api/hello":
            return self._send(200, {"monctl": 1})

        if self._gate():
            return

        if u.path == "/api/remote":
            return self._send(200, remote_snapshot())

        if u.path == "/api/app/version":
            v = app_version()
            return self._send(200, v) if v else self._send(404, {"error": "no APK published"})

        if u.path == "/api/app/apk":
            try:
                with open(os.path.join(APK_CHANNEL, "MonCtl.apk"), "rb") as f:
                    return self._send(200, f.read(), "application/vnd.android.package-archive")
            except OSError:
                return self._send(404, {"error": "no APK published"})

        if u.path == "/api/watcher":
            return self._send(200, watcher_state())

        if u.path == "/api/mac":
            return self._send(200, mac_snapshot())

        if u.path.startswith("/api/screen"):
            if self._via_tunnel() and not os.path.exists(SCREEN_REMOTE_FLAG):
                return self._send(403, {"error": "screen preview is LAN-only", "lan_only": True})
            if u.path == "/api/screen/displays":
                return self._send(200, {"displays": screen_displays(),
                                        "permission": bool(_cg.CGPreflightScreenCaptureAccess())})
            if u.path == "/api/screen":
                q = parse_qs(u.query)
                try:
                    d = int(q.get("d", ["0"])[0])
                    w = max(320, min(3840, int(q.get("w", ["1280"])[0])))
                except ValueError:
                    return self._send(400, {"error": "d and w must be integers"})
                status, body = screen_capture(d, w)
                if status == 200:
                    return self._send(200, body, "image/jpeg")
                return self._send(status, body)

        if u.path == "/api/display":
            st = display_power_state()
            return self._send(200, {"state": st, "asleep": (st is not None and st < 4)})

        if u.path in ("/", "/index.html"):
            built = os.path.join(DIST, "index.html")
            # The simple single-file panel is the UI. The old React build in
            # app/dist is no longer served (source left in app/).
            path = os.path.join(HERE, "index.html")
            try:
                with open(path, "rb") as f:
                    return self._send(200, f.read(), "text/html; charset=utf-8")
            except OSError:
                return self._send(500, {"error": "no index.html"})

        # Static assets from the Vite build. Paths are resolved and confined to
        # DIST so a crafted URL cannot escape the directory.
        if (u.path.startswith("/assets/") or u.path.startswith("/fonts/")
                or u.path in ("/vite.svg", "/favicon.ico")):
            target = os.path.normpath(os.path.join(DIST, u.path.lstrip("/")))
            if not target.startswith(DIST + os.sep):
                return self._send(403, {"error": "forbidden"})
            ctypes = {".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml",
                      ".woff2": "font/woff2", ".map": "application/json",
                      ".png": "image/png", ".ico": "image/x-icon"}
            ext = os.path.splitext(target)[1]
            try:
                with open(target, "rb") as f:
                    return self._send(200, f.read(),
                                      ctypes.get(ext, "application/octet-stream"))
            except OSError:
                return self._send(404, {"error": "not found"})

        if u.path == "/api/catalog":
            return self._send(200, {"groups": CATALOG})

        if u.path == "/api/state":
            q = parse_qs(u.query)
            codes = (q.get("codes", [""])[0]).split(",")
            codes = [c.strip().upper() for c in codes if c.strip()]
            if not codes:
                codes = list(BY_CODE)
            return self._send(200, {"values": read_many(codes)})

        if u.path == "/api/stream":
            return self.stream(parse_qs(u.query))

        if u.path == "/api/cycle":
            return self._send(200, {"running": CYCLE["running"], "dwell": CYCLE["dwell"],
                                    "current": CYCLE["current"]})

        if u.path == "/api/auto":
            return self._send(200, {"running": auto_running(), "lux": read_lux(),
                                    "lid": lid_state()})

        if u.path == "/api/autostart":
            return self._send(200, autostart_state())

        if u.path == "/api/dim":
            return self._send(200, {"level": DIM["level"], "floor": DIM_FLOOR})

        if u.path == "/api/caps":
            return self._send(200, {"caps": capabilities()})

        return self._send(404, {"error": "not found"})

    def stream(self, q):
        """Server-Sent Events: emit each VCP value the moment it is read.

        DDC is serial, so the values arrive ~200 ms apart no matter what. This
        does not make the read faster — it removes the stall, so the UI fills in
        continuously instead of jumping a whole section at a time.
        """
        codes = [c.strip().upper() for c in (q.get("codes", [""])[0]).split(",") if c.strip()]
        codes = [c for c in codes if re.fullmatch(r"[0-9A-F]{2}", c)]
        if not codes:
            codes = list(BY_CODE)

        print(f"[stream] client connected, {len(codes)} codes", flush=True)
        sent = 0

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()

        def emit(event, payload):
            self.wfile.write(f"event: {event}\ndata: {json.dumps(payload)}\n\n".encode())
            self.wfile.flush()

        with BUS:
            try:
                proc = subprocess.Popen([DDCPROBE, "many"] + codes,
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                        text=True, bufsize=1)
            except OSError as e:
                return emit("failed", {"message": str(e)})

            try:
                for line in proc.stdout:            # line-buffered, one code per line
                    parts = line.split()
                    if len(parts) != 3:
                        continue
                    code = parts[0].upper()
                    sent += 1
                    if parts[1] == "-":
                        emit("value", {"code": code, "ok": False})
                    else:
                        try:
                            emit("value", {"code": code, "ok": True,
                                           "cur": int(parts[1]), "max": int(parts[2])})
                        except ValueError:
                            emit("value", {"code": code, "ok": False})
                proc.wait(timeout=10)
                emit("done", {"count": len(codes)})
                print(f"[stream] done, {sent}/{len(codes)} values sent", flush=True)
            except (BrokenPipeError, ConnectionResetError):
                proc.kill()                          # client navigated away
            except (subprocess.TimeoutExpired, OSError):
                proc.kill()
                try:
                    emit("failed", {"message": "probe timed out"})
                except OSError:
                    pass

    def _mac_post(self, path, body):
        """The Touch Bar's actions. Arguments are type-checked here and handed
        to fixed scripts as argv, never through a shell string."""
        def level(key):
            v = body.get(key)
            return v if isinstance(v, int) and not isinstance(v, bool) and 0 <= v <= 100 else None

        if path == "/api/mac/volume":
            if level("value") is not None:
                ok = mac_volume_set(value=body["value"])
            elif isinstance(body.get("muted"), bool):
                ok = mac_volume_set(muted=body["muted"])
            else:
                return self._send(400, {"error": "value 0..100 or muted bool"})
            return self._send(200 if ok else 502, {"ok": ok, **mac_volume()})

        if path == "/api/mac/brightness":
            if level("value") is None:
                return self._send(400, {"error": "value must be 0..100"})
            ok = mac_brightness_set(body["value"])
            return self._send(200 if ok else 502,
                              {"ok": ok, "brightness": mac_brightness(),
                               "error": None if ok else "built-in display unavailable (lid closed?)"})

        if path == "/api/wifi/on":
            ok = wifi_on()
            return self._send(200 if ok else 502, {"ok": ok, "wifi": wifi_state()})

        if path == "/api/wifi/scan":
            nets = wifi_scan()
            if nets is None:
                return self._send(502, {"error": "scan failed (is Wi-Fi on?)"})
            return self._send(200, {"networks": nets})

        if path == "/api/wifi/join":
            ssid, pw = body.get("ssid"), body.get("password")
            if not isinstance(ssid, str) or not 0 < len(ssid.encode()) <= 32 \
                    or any(ch in ssid for ch in "\0\n\r"):
                return self._send(400, {"error": "bad ssid"})
            if pw is not None and (not isinstance(pw, str) or not 8 <= len(pw) <= 63
                                   or any(ch in pw for ch in "\0\n\r")):
                return self._send(400, {"error": "password must be 8-63 characters"})
            ok, err = wifi_join(ssid, pw or None)
            return self._send(200 if ok else 502, {"ok": ok, "error": err, "wifi": wifi_state()})

        if path == "/api/airplay/scan":
            devs = airplay_scan()
            if devs is None:
                return self._send(502, {"error": "scan failed"})
            return self._send(200, {"devices": devs})

        if path == "/api/airplay/connect":
            mac, name = str(body.get("mac", "")).lower(), body.get("name")
            if not MAC_ADDR.fullmatch(mac) or not isinstance(name, str) or not 0 < len(name) <= 80:
                return self._send(400, {"error": "mac and name required"})
            ok = airplay_connect(mac, name)
            return self._send(200 if ok else 502, {"ok": ok, "airplay": airplay_state()})

        if path == "/api/airplay/disconnect":
            ok = airplay_disconnect()
            return self._send(200 if ok else 502, {"ok": ok, "airplay": airplay_state()})

        return self._send(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)

        # Every POST changes something, so every one is gated -- including the
        # cycler/auto/dim/autostart toggles that used to sit above this check.
        if self._gate():
            return

        if u.path == "/api/nudge":
            try:
                body = self._body()
                code = str(body.get("code", "")).upper()
                delta = body.get("delta")
            except (ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "bad json"})
            if code not in NUDGEABLE:
                return self._send(400, {"error": "code must be 10 or 62"})
            if not isinstance(delta, int) or isinstance(delta, bool) or abs(delta) > 100:
                return self._send(400, {"error": "delta must be an integer in -100..100"})
            v = nudge(code, delta)
            if v is None:
                return self._send(502, {"error": "monitor did not answer"})
            return self._send(200, {"ok": True, "code": code, **v})

        if u.path.startswith(("/api/mac/", "/api/wifi/", "/api/airplay/")):
            try:
                body = self._body()
            except (ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "bad json"})
            return self._mac_post(u.path, body)

        # The browser reports its own errors here so client-side failures are
        # visible in the terminal instead of only in devtools.
        if u.path == "/api/log":
            try:
                n = int(self.headers.get("Content-Length") or 0)
                msg = json.loads(self.rfile.read(n) or b"{}")
                print(f"[client] {msg.get('where','?')}: {msg.get('message','')}")
                if msg.get("stack"):
                    print("         " + str(msg["stack"])[:500].replace("\n", "\n         "))
            except (ValueError, json.JSONDecodeError):
                pass
            return self._send(200, {"ok": True})

        if u.path == "/api/dim":
            try:
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}")
                level = float(body.get("level"))
            except (ValueError, TypeError, json.JSONDecodeError):
                return self._send(400, {"error": "level must be a number"})
            ok, applied = dim_apply(level)
            return self._send(200 if ok else 502,
                              {"level": applied, "floor": DIM_FLOOR,
                               "error": None if ok else "no external display"})

        if u.path == "/api/autostart":
            try:
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}")
            except (ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "bad json"})
            if not isinstance(body.get("enabled"), bool):
                return self._send(400, {"error": "enabled must be a boolean"})
            ok, err = autostart_set(body["enabled"])
            out = autostart_state()
            out["error"] = err
            return self._send(200 if ok else 500, out)

        if u.path == "/api/auto":
            try:
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}")
            except (ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "bad json"})
            if body.get("action") == "stop":
                auto_stop()
                return self._send(200, {"running": False})
            ok = auto_start(body)
            return self._send(200, {"running": auto_running(),
                                    "error": None if ok else "already running"})

        if u.path == "/api/cycle":
            try:
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}")
            except (ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "bad json"})

            if body.get("action") == "stop":
                cycle_stop()
                return self._send(200, {"running": False})

            try:
                dwell = int(body.get("dwell", 20))
            except (TypeError, ValueError):
                return self._send(400, {"error": "dwell must be an integer"})
            dwell = max(1, min(300, dwell))

            inputs = body.get("inputs") or [15, 17, 18]
            if (not isinstance(inputs, list) or not inputs
                    or not all(isinstance(i, int) and 0 <= i <= 255 for i in inputs)):
                return self._send(400, {"error": "bad inputs list"})

            ok = cycle_start(dwell, inputs)
            return self._send(200 if ok else 409,
                              {"running": CYCLE["running"], "dwell": dwell,
                               "error": None if ok else "already running"})

        if u.path == "/api/display":
            try:
                n = int(self.headers.get("Content-Length") or 0)
                action = json.loads(self.rfile.read(n) or b"{}").get("action")
            except (ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "bad json"})
            if action not in ("sleep", "wake", "off", "on", "hardoff"):
                return self._send(
                    400, {"error": "action must be sleep|wake|off|on|hardoff"})
            if not display_action(action):
                return self._send(502, {"error": f"{action} failed"})
            # Waking re-enumerates the I2C bus; give it a beat before reporting.
            time.sleep(1.2 if action in ("wake", "sleep") else 0.4)
            st = display_power_state()
            return self._send(200, {"ok": True, "action": action, "state": st,
                                    "asleep": (st is not None and st < 4)})

        if u.path == "/api/watcher":
            # {"action": "start"|"stop"} is idempotent (the Android app). No
            # body keeps the original blind toggle for the Touch Bar-style use.
            try:
                action = self._body().get("action")
            except (ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "bad json"})
            if action not in (None, "start", "stop"):
                return self._send(400, {"error": "action must be start|stop"})
            if action is None:
                ok = watcher_toggle()
                # The script writes the flag then plays a sound; re-read after
                # a beat or the response reports the state we just left.
                time.sleep(0.35)
            else:
                ok = watcher_set(action == "start")
            if not ok:
                return self._send(503, {"error": "airplay_toggle.sh not installed"})
            return self._send(200, watcher_state())

        if u.path != "/api/set":
            return self._send(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(n) or b"{}")
        except (ValueError, json.JSONDecodeError):
            return self._send(400, {"error": "bad json"})

        code = str(payload.get("code", "")).upper()
        value = payload.get("value")
        if code not in BY_CODE:
            return self._send(400, {"error": f"unknown control {code}"})
        if not isinstance(value, int):
            return self._send(400, {"error": "value must be an integer"})
        if not write_one(code, value):
            return self._send(502, {"error": "write failed"})
        return self._send(200, {"ok": True, "code": code, "value": value})


def lan_ip():
    """This host's LAN address, for printing a URL other machines can use."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.1.1", 80))     # no packet sent; just picks the route
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


TOKEN_FILE = os.path.expanduser("~/.config/monctl/token")


def file_token():
    """The persistent token, created on first use.

    Kept in a 0600 file rather than on the command line, where it showed up in
    `ps` and in the LaunchAgent plist. The LaunchAgent, the menu bar app's
    fallback launch and `monctl web` all pick up the same one.
    """
    try:
        with open(TOKEN_FILE) as f:
            t = f.read().strip()
        if t:
            return t
    except FileNotFoundError:
        pass
    t = secrets.token_urlsafe(32)
    os.makedirs(os.path.dirname(TOKEN_FILE), mode=0o700, exist_ok=True)
    fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(t + "\n")
    return t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8770)
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--host", default="127.0.0.1",
                    help="bind address; 0.0.0.0 exposes it on the LAN")
    ap.add_argument("--lan", action="store_true",
                    help="shorthand for --host 0.0.0.0")
    ap.add_argument("--token", default=os.environ.get("MONCTL_TOKEN", ""),
                    help="require ?k=TOKEN; 'auto' generates a one-off one. "
                         f"Default: the persistent token in {TOKEN_FILE}.")
    ap.add_argument("--no-token", action="store_true",
                    help="no auth at all (only sensible bound to 127.0.0.1 "
                         "with no tunnel in front)")
    args = ap.parse_args()

    host = "0.0.0.0" if args.lan else args.host
    token = args.token
    from_file = False
    if args.no_token:
        token = ""
    elif token == "auto":
        token = secrets.token_urlsafe(9)
    elif not token:
        token, from_file = file_token(), True
    Handler.token = token

    if not os.access(DDCPROBE, os.X_OK):
        raise SystemExit(f"ddcprobe not found or not executable at {DDCPROBE}\n"
                         f"Build it with: cd ~/monctl && ./build.sh")

    # Exit 0 rather than crash if an instance is already up. The LaunchAgent
    # uses KeepAlive{SuccessfulExit:false}, so a clean exit here means a
    # duplicate launch at login cannot become a respawn loop.
    try:
        srv = ThreadingHTTPServer((host, args.port), Handler)
    except OSError as e:
        if e.errno in (48, 98):          # EADDRINUSE
            print(f"monctl-web: port {args.port} already serving; nothing to do")
            return
        raise

    shown = lan_ip() if host == "0.0.0.0" else host
    # The persistent token is not echoed: this goes to a /tmp log, and that
    # token also opens the tunnel. `cat ~/.config/monctl/token` when needed.
    suffix = f"?k={token}" if token and not from_file else ""
    url = f"http://{shown}:{args.port}/{suffix}"
    print(f"monctl-web  →  {url}")
    if from_file:
        print(f"            token: {TOKEN_FILE} (append ?k=<token> from another device)")
    if host == "0.0.0.0":
        print(f"            also http://127.0.0.1:{args.port}/{suffix}")
        if not token:
            # This panel can factory-reset the monitor (VCP 0x04). Unauthenticated
            # on a LAN shared with IoT devices, that is a real exposure, so say so
            # plainly rather than binding wide open in silence.
            print("  WARNING: reachable by anything on the LAN with no auth, and this "
                  "panel can factory-reset the display. Restart with --token auto.")
    if not args.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(
            f"http://127.0.0.1:{args.port}/{suffix}")).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()
