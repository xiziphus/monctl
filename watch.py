#!/usr/bin/env python3
"""
monctl watch — report changes made from the monitor's own OSD buttons.

DDC/CI has no interrupt and no per-button reporting: the host always polls.
What it does give us is MCCS VCP 0x02, "New Control Value" — a latch the panel
raises whenever a setting is saved. So:

    poll 0x02  ->  if raised, clear it, re-read the controls, diff, report

That tells you *that* someone touched the OSD and *what* setting landed where.
It cannot tell you which physical button was pressed, or report a press that
doesn't change a value (menu navigation, entering/leaving the menu).

    monctl watch                 print changes to the terminal
    monctl watch --notify        also post macOS notifications
    monctl watch --exec 'CMD'    run CMD on each change ($MONCTL_CHANGE is set)
"""

import argparse
import os
import re
import shlex
import subprocess
import sys
import time

DDCPROBE = os.environ.get("DDCPROBE") or os.path.expanduser("~/bin/ddcprobe")

FLAG = "02"          # New Control Value latch
CLEARED, RAISED = 1, 2

WATCH = {
    "10": "Brightness",      "12": "Contrast",
    "14": "Colour preset",   "60": "Input source",
    "62": "Volume",          "8D": "Mute",
    "16": "Gain red",        "18": "Gain green",       "1A": "Gain blue",
    "6C": "Black red",       "6E": "Black green",      "70": "Black blue",
    "59": "Saturation red",  "5A": "Saturation yellow", "5B": "Saturation green",
    "5C": "Saturation cyan", "5D": "Saturation blue",  "5E": "Saturation magenta",
    "9B": "Hue red",         "9C": "Hue yellow",       "9D": "Hue green",
    "9E": "Hue cyan",        "9F": "Hue blue",         "A0": "Hue magenta",
    "D6": "Power state",     "CA": "OSD lock",
}

LABELS = {
    "14": {5: "6500K", 6: "7500K", 8: "9300K", 11: "User"},
    "60": {15: "DisplayPort", 17: "HDMI 1", 18: "HDMI 2"},
    "8D": {1: "muted", 2: "unmuted"},
    "D6": {1: "on", 4: "standby", 5: "off"},
    "CA": {1: "locked", 2: "unlocked"},
}


def probe(args, timeout=90):
    try:
        return subprocess.run([DDCPROBE] + args, capture_output=True,
                              text=True, timeout=timeout).stdout
    except (subprocess.TimeoutExpired, OSError):
        return ""


def read_many(codes):
    out = {}
    for line in probe(["many"] + list(codes)).splitlines():
        p = line.split()
        if len(p) == 3 and p[1] != "-":
            try:
                out[p[0].upper()] = int(p[1])
            except ValueError:
                pass
    return out


def read_one(code):
    m = re.search(r"current=(\d+)", probe(["get", code], timeout=20))
    return int(m.group(1)) if m else None


def pretty(code, value):
    lut = LABELS.get(code)
    return lut.get(value, str(value)) if lut else str(value)


def notify(title, body):
    subprocess.run(["osascript", "-e",
                    f'display notification {json_str(body)} with title {json_str(title)}'],
                   capture_output=True)


def json_str(s):
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--interval", type=float, default=1.5,
                    help="seconds between polls of the latch (default 1.5)")
    ap.add_argument("--notify", action="store_true", help="post macOS notifications")
    ap.add_argument("--exec", dest="cmd", help="shell command to run on each change")
    args = ap.parse_args()

    if not os.access(DDCPROBE, os.X_OK):
        sys.exit(f"ddcprobe not found at {DDCPROBE}")

    print("reading baseline…", flush=True)
    state = read_many(WATCH)
    probe(["set", FLAG, str(CLEARED)])
    print(f"watching {len(state)} controls — press the monitor's OSD buttons "
          f"(ctrl-C to stop)\n", flush=True)

    while True:
        try:
            if read_one(FLAG) == RAISED:
                probe(["set", FLAG, str(CLEARED)])
                fresh = read_many(WATCH)
                changes = [(c, state.get(c), v) for c, v in fresh.items()
                           if state.get(c) is not None and state[c] != v]
                state = fresh

                stamp = time.strftime("%H:%M:%S")
                if not changes:
                    # The latch fires on any save, including one that lands on
                    # the same value, and on our own writes.
                    print(f"{stamp}  OSD activity (no watched value changed)", flush=True)
                    continue

                for code, old, new in changes:
                    line = (f"{stamp}  {WATCH.get(code, code):<18} "
                            f"{pretty(code, old)} -> {pretty(code, new)}")
                    print(line, flush=True)
                    if args.notify:
                        notify(f"{WATCH.get(code, code)} changed",
                               f"{pretty(code, old)} → {pretty(code, new)}")
                    if args.cmd:
                        env = dict(os.environ,
                                   MONCTL_CHANGE=f"{code}={new}",
                                   MONCTL_CONTROL=WATCH.get(code, code),
                                   MONCTL_OLD=str(old), MONCTL_NEW=str(new))
                        subprocess.run(args.cmd, shell=True, env=env)

            time.sleep(args.interval)
        except KeyboardInterrupt:
            print("\nstopped")
            return


if __name__ == "__main__":
    main()
