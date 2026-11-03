#!/usr/bin/env python3
"""
monctl auto — drive the Acer's backlight from the Mac's ambient light sensor.

macOS auto-brightness only ever applies to the built-in panel; external displays
are left out. This reads the same sensor (via `alsread`, HID ambient-light
events) and writes VCP 0x10 over DDC.

Behaviour follows the established adaptive-brightness model rather than a naive
"sample and set", which flickers badly in practice:

  * Time-weighted average over a sample window, not a bare EMA — recent samples
    weigh more, but a single spike cannot move the result on its own.
  * Asymmetric debounce. A change must persist before it is acted on, and
    darkening waits ~4x longer than brightening: a light turning on should be
    followed quickly, but a passing shadow must not dim the screen.
  * Hysteresis proportional to the current ambient level, not a fixed step.
    10 lux of drift matters at 20 lux and is noise at 400.
  * Ramped output. The backlight walks to the target instead of jumping, which
    is the difference between "adaptive" and "distracting".
  * User offset learning. Override the brightness while this is running and it
    treats that as your preference at that light level, and carries the offset
    forward at partial confidence.

    monctl als                       print lux and exit
    monctl lid                       lid open/closed
    monctl auto                      run it
    monctl auto --dry-run            decide but never write
    monctl auto --no-learn --min 30
"""

import argparse
import math
import os
import re
import subprocess
import sys
import time
from collections import deque

ALSREAD = os.environ.get("ALSREAD") or os.path.expanduser("~/bin/alsread")
DDCPROBE = os.environ.get("DDCPROBE") or os.path.expanduser("~/bin/ddcprobe")
BRIGHTNESS = "10"


# --------------------------------------------------------------------------- io

def lux():
    try:
        out = subprocess.run([ALSREAD], capture_output=True, text=True, timeout=5).stdout
        return float(out.strip())
    except (ValueError, subprocess.TimeoutExpired, OSError):
        return None


def get_brightness():
    try:
        out = subprocess.run([DDCPROBE, "get", BRIGHTNESS],
                             capture_output=True, text=True, timeout=20).stdout
        m = re.search(r"current=(\d+)", out)
        return int(m.group(1)) if m else None
    except (subprocess.TimeoutExpired, OSError):
        return None


def set_brightness(v):
    try:
        subprocess.run([DDCPROBE, "set", BRIGHTNESS, str(v)],
                       capture_output=True, text=True, timeout=20)
        return True
    except (subprocess.TimeoutExpired, OSError):
        return False


def lid_closed():
    """True / False / None.

    The lid angle sensor (HID 0x20/0x8A) exists on this Mac but does not answer
    over IOHIDEventSystemClient. AppleClamshellState answers the only question
    that matters, with public API.
    """
    try:
        out = subprocess.run(["ioreg", "-r", "-k", "AppleClamshellState", "-d", "4"],
                             capture_output=True, text=True, timeout=5).stdout
        m = re.search(r'"AppleClamshellState"\s*=\s*(\w+)', out)
        return m.group(1) == "Yes" if m else None
    except (subprocess.TimeoutExpired, OSError):
        return None


# ------------------------------------------------------------------- algorithm

class Ambient:
    """Time-weighted ambient estimate over a sliding window.

    AOSP weights recent samples more heavily via an integral over the window;
    this is the same idea — linear ramp weighting — which keeps the estimate
    responsive without letting one spike decide anything.
    """

    def __init__(self, window=10.0):
        self.window = window
        self.samples = deque()          # (t, lux)

    def add(self, t, lx):
        self.samples.append((t, lx))
        cutoff = t - self.window
        while self.samples and self.samples[0][0] < cutoff:
            self.samples.popleft()

    def value(self, now):
        if not self.samples:
            return None
        num = den = 0.0
        for t, lx in self.samples:
            age = now - t
            w = max(0.0, 1.0 - age / self.window)   # newest ~1, oldest ~0
            num += w * lx
            den += w
        return (num / den) if den else self.samples[-1][1]


def curve(lx, lo, hi, gain):
    """Perceptual (log10) lux -> brightness. 0 lux -> lo, ~1000 lux -> hi."""
    if lx is None or lx < 0:
        return None
    return int(round(max(lo, min(hi, lo + gain * math.log10(lx + 1.0)))))


def ramp_to(current, target, step_size, dry_run):
    """Walk to target instead of jumping. Returns the new current."""
    if current is None:
        if not dry_run:
            set_brightness(target)
        return target
    step = step_size if target > current else -step_size
    v = current
    while (step > 0 and v < target) or (step < 0 and v > target):
        v = min(v + step, target) if step > 0 else max(v + step, target)
        if not dry_run:
            set_brightness(v)
    return target


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min", type=int, default=20, dest="lo")
    ap.add_argument("--max", type=int, default=100, dest="hi")
    ap.add_argument("--gain", type=float, default=27.0,
                    help="brightness units per decade of lux (default 27)")
    ap.add_argument("--interval", type=float, default=2.0,
                    help="sensor sample period (default 2s)")
    ap.add_argument("--check-every", type=int, default=4,
                    help="run the expensive DDC/ioreg checks every Nth cycle "
                         "(default 4). The lux read is cheap and runs every cycle.")
    ap.add_argument("--window", type=float, default=10.0,
                    help="time-weighted average window (default 10s)")
    ap.add_argument("--brighten-delay", type=float, default=2.0,
                    help="seconds a brighter reading must persist (default 2)")
    ap.add_argument("--darken-delay", type=float, default=8.0,
                    help="seconds a darker reading must persist (default 8)")
    ap.add_argument("--brighten-hyst", type=float, default=0.15,
                    help="fractional lux rise needed to react (default 0.15)")
    ap.add_argument("--darken-hyst", type=float, default=0.30,
                    help="fractional lux fall needed to react (default 0.30)")
    ap.add_argument("--ramp-step", type=int, default=3,
                    help="brightness units per ramp write (default 3)")
    ap.add_argument("--no-learn", action="store_true",
                    help="ignore manual overrides instead of learning from them")
    ap.add_argument("--confidence", type=float, default=0.5,
                    help="how much of a manual override to adopt, 0-1 (default 0.5)")
    ap.add_argument("--closed", type=int, default=None, metavar="N",
                    help="pin this brightness while the lid is shut")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    for path, what in ((ALSREAD, "alsread"), (DDCPROBE, "ddcprobe")):
        if not os.access(path, os.X_OK):
            sys.exit(f"{what} not found at {path} — run ~/monctl/build.sh")
    if args.lo >= args.hi:
        sys.exit("--min must be below --max")

    amb = Ambient(args.window)
    anchor = None          # ambient level the current brightness corresponds to
    pending_since = None   # when the current out-of-band reading started
    pending_dir = None     # 'up' | 'down'
    offset = 0             # learned user preference, brightness units
    current = get_brightness()
    was_closed = False
    converged = False
    tick = 0
    lid_cache = False

    print(f"adaptive brightness | floor {args.lo} ceiling {args.hi} gain {args.gain} | "
          f"brighten {args.brighten_delay}s/{args.brighten_hyst:.0%} "
          f"darken {args.darken_delay}s/{args.darken_hyst:.0%}"
          f"{' | DRY RUN' if args.dry_run else ''}", flush=True)
    print(f"monitor at {current}", flush=True)

    while True:
        try:
            now = time.time()
            stamp = time.strftime("%H:%M:%S")

            # --- lid guard -------------------------------------------------
            # ioreg costs ~46ms and a ddcprobe read ~157ms; neither needs to run
            # every cycle. A lid does not open and shut between samples, and the
            # user does not grab the slider every 2s. The lux read is cheap
            # (~25ms) and stays at full rate so response time is unaffected.
            tick += 1
            slow = (tick % args.check_every == 1)
            if slow:
                lid_cache = bool(lid_closed())
            if lid_cache:
                if not was_closed:
                    was_closed = True
                    if args.closed is not None:
                        print(f"{stamp}  lid closed -> pinning {args.closed}", flush=True)
                        if not args.dry_run:
                            set_brightness(args.closed)
                        current = args.closed
                    else:
                        print(f"{stamp}  lid closed -> sensor blind, holding {current}",
                              flush=True)
                time.sleep(args.interval)
                continue
            elif was_closed:
                was_closed = False
                amb = Ambient(args.window)     # window holds only pre-close light
                anchor = pending_since = pending_dir = None
                converged = False
                print(f"{stamp}  lid open -> resuming", flush=True)

            lx = lux()
            if lx is None:
                print(f"{stamp}  sensor read failed", flush=True)
                time.sleep(args.interval)
                continue

            amb.add(now, lx)
            level = amb.value(now)

            # --- converge on start ------------------------------------------
            # Anchoring to whatever brightness happened to be set means an
            # already-wrong value is never corrected: the daemon reports
            # "stable" forever and only moves if the ambient level changes.
            # Enabling adaptive brightness should take over immediately.
            if not converged:
                t = curve(level, args.lo, args.hi, args.gain)
                if t is not None:
                    t = max(args.lo, min(args.hi, t + offset))
                    if current is None or current != t:
                        print(f"{stamp}  {lx:7.1f} lux  avg {level:6.1f}  "
                              f"{'would converge' if args.dry_run else 'converge'} "
                              f"{current} -> {t}", flush=True)
                        current = ramp_to(current, t, args.ramp_step, args.dry_run)
                    converged = True
                    anchor = level
                    time.sleep(args.interval)
                    continue

            # --- learn from a manual override ------------------------------
            if slow and not args.no_learn and current is not None:
                actual = get_brightness()
                if actual is not None and abs(actual - current) >= 3:
                    # Someone moved it. Treat the delta as preference at this
                    # light level and adopt it at partial confidence, the way
                    # adaptive (as opposed to merely automatic) systems do.
                    base = curve(level, args.lo, args.hi, args.gain)
                    if base is not None:
                        delta = actual - base
                        offset = int(round(offset + args.confidence * (delta - offset)))
                        offset = max(-40, min(40, offset))
                        print(f"{stamp}  manual override -> {actual}; learned offset "
                              f"{offset:+d}", flush=True)
                    current = actual
                    anchor = level

            if anchor is None:
                anchor = level

            # --- hysteresis + asymmetric debounce --------------------------
            up_gate = anchor * (1 + args.brighten_hyst) + 1
            dn_gate = anchor * (1 - args.darken_hyst)
            direction = "up" if level > up_gate else ("down" if level < dn_gate else None)

            if direction is None:
                pending_since = pending_dir = None
                print(f"{stamp}  {lx:7.1f} lux  avg {level:6.1f}  stable at {current}",
                      flush=True)
                time.sleep(args.interval)
                continue

            if pending_dir != direction:
                pending_dir, pending_since = direction, now

            need = args.brighten_delay if direction == "up" else args.darken_delay
            held = now - pending_since
            if held < need:
                print(f"{stamp}  {lx:7.1f} lux  avg {level:6.1f}  {direction} "
                      f"{held:.1f}/{need:.0f}s", flush=True)
                time.sleep(args.interval)
                continue

            # --- act: ramp, do not jump ------------------------------------
            target = curve(level, args.lo, args.hi, args.gain)
            if target is None:
                time.sleep(args.interval)
                continue
            target = max(args.lo, min(args.hi, target + offset))

            if current is None or target == current:
                current = target
                anchor = level
                pending_since = pending_dir = None
                time.sleep(args.interval)
                continue

            print(f"{stamp}  {lx:7.1f} lux  avg {level:6.1f}  "
                  f"{'would ramp' if args.dry_run else 'ramp'} {current} -> {target}"
                  f"{f' (offset {offset:+d})' if offset else ''}", flush=True)

            current = ramp_to(current, target, args.ramp_step, args.dry_run)
            anchor = level
            pending_since = pending_dir = None

            time.sleep(args.interval)
        except KeyboardInterrupt:
            print("\nstopped")
            return


if __name__ == "__main__":
    main()
