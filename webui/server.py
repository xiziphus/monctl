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
LAST_INPUT = {"code": None, "at": None}


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
