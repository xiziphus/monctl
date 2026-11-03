# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Local macOS tooling to control one external monitor (Acer KG281K, USB-C → DisplayPort) over DDC/CI from an Intel MacBookPro16,1. README.md is the detailed reference: the VCP code table, hardware quirks, and things already ruled out (HDR, >60 Hz, overclocking). Read it before adding a control or chasing a hardware idea.

This is a **public** git repo (github.com/xiziphus/monctl). Commit before a non-trivial edit instead of making `.bak` copies. Older sibling backups (`*.bak*`) are still on disk but gitignored. Keep secrets and personal endpoints out of tracked files: the server token lives in `~/.config/monctl/token`, the tunnel config in `~/.cloudflared/monctl.yml`, and the Android app takes its URLs and token from its own settings.

## Build / run

```bash
./build.sh            # builds ~/Applications/MonCtl.app, compiles the C helpers into ~/bin, symlinks ~/bin/monctl -> ./monctl
monctl                # CLI status (zsh script; edits are live because of the symlink)
monctl web            # python3 webui/server.py on 127.0.0.1:8770
python3 webui/server.py --no-browser --port 8770   # server directly; --lan/--host for network access, token from ~/.config/monctl/token
monctl watch          # python3 watch.py
monctl auto --dry-run # python3 autobright.py, decides but never writes
monctl pair           # monctl://pair link (token + LAN + tunnel URL) for the Android app
cd android && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Rebuilding one C helper by hand: `clang -O2 -o ddcprobe ddcprobe.c -framework IOKit -framework CoreFoundation && install -m 755 ddcprobe ~/bin/ddcprobe`. The exact flags for each helper are in its header comment and in `build.sh`.

**Install locations matter:** `server.py`, `watch.py`, `autobright.py` and the Swift app call `~/bin/ddcprobe` / `~/bin/alsread` (overridable through the `DDCPROBE` / `ALSREAD` env vars), not the copies in this directory. After changing a `.c` file, reinstall it to `~/bin`. After changing `MonCtl.swift`, rerun `build.sh` and relaunch the app. `server.py` must be restarted, since a LaunchAgent may be running it.

There are no tests. Verification means running against the real monitor (`monctl`, `ddcprobe get <hex>`) and checking that the panel actually changed.

`app/` is a Vite/React/Tailwind panel (`npm run build`, `npm run dev`, `npm run lint` = oxlint). It is **superseded and not served**: `server.py` always serves `webui/index.html`, a single file with no build step. Make UI changes in `webui/index.html` unless asked to revive `app/`.

## Architecture

```
monctl (zsh CLI) ─┐
MonCtl.app (Swift)├──> ddcprobe (C, IOKit I2C) ──> DDC/CI over DP AUX ──> monitor
webui/server.py  ─┤        flock /tmp/monctl-ddc.lock
watch.py         ─┤
autobright.py    ─┘ (+ alsread: ambient light sensor via private IOHIDEventSystem API)
dimmer (C): software gamma-ramp dimming, no DDC

android/ (Kotlin, Compose, Glance widget) ──LAN :8770 or Cloudflare tunnel──> webui/server.py
```

- **`ddcprobe` is the single path to the hardware.** It holds an exclusive `flock` for its whole lifetime, because DDC has no transaction isolation: concurrent readers receive each other's replies as plausible-but-wrong values, not errors. Any new hardware access must go through `ddcprobe`. `server.py` also serializes its own calls with an in-process `BUS` lock. Subcommands: `caps`, `get`, `set`, `many` (batch read, one `code cur max` line per code), `scan`, `name`.
- **Exception:** the CLI's basic verbs (`status`, `b`, `c`, `v`, `mute`, `rgb`, `input`) still go through Homebrew `ddcctl` (`_query`/`_ddcw` in `monctl`), and the script exits early if `ddcctl` is missing. `ddcctl` does not take the bus lock. README claims nothing calls `ddcctl` any more; that is true for the app, server and watcher but not for the CLI.
- **The sleeping-bus rule.** The panel drops its DDC bus whenever the display sleeps, so every call fails. Every front end detects this (`_bus_dead`) and can wake the display once with `caffeinate -u -t 2` and retry. Only **user-initiated writes** may revive. Background polls must not (`revive=False` in server.py/Swift, `MONCTL_NO_WAKE=1` for the CLI), or a poller keeps a dark monitor lit forever. Preserve this distinction in new code.
- **Power states are three different things** (see the comment block in `server.py` around `display_action`): *sleep/wake* = `pmset displaysleepnow` / `caffeinate`, reversible. *off* = VCP `D6=4`, which kills the DDC bus, so only the physical button recovers it. *hardoff* = VCP `D6=5`, which turns the LED off, is one-way, and macOS doesn't notice. Destructive actions (input switch, off, hardoff, factory resets) are confirm-gated in every UI.
- **`webui/server.py`** (stdlib only) holds `CATALOG`, the OSD menu as data. The web UI renders its Advanced section from `/api/catalog`, and `/api/set` validates writes against it, so to expose a new VCP control, add it to `CATALOG`. `/api/stream` is SSE that emits each value as `ddcprobe many` yields it. The server also manages the LaunchAgent (`com.dell.monctl.web.plist`, where `__SERVER__` is substituted at install), the auto-brightness process, input cycling, the `dimmer` level, and an unrelated AirPlay watcher toggle (`~/bin/airplay_toggle.sh`).
- **Auth (`_gate` in server.py).** The token lives in `~/.config/monctl/token` (created on first start, never on a command line). Every GET except `/api/hello` and every POST is gated. Direct loopback is exempt, but a request carrying `Cf-Connecting-IP`/`Cf-Ray` is not: the Cloudflare tunnel (`~/.cloudflared/monctl.yml`, LaunchAgent `com.dell.monctl.tunnel`) connects from 127.0.0.1, so without that rule the internet would get loopback trust. Bad tokens lock a client out for 10 minutes. Keep new routes behind `_gate`.
- **Android companion (`android/`).** It uses `/api/remote` (one-shot snapshot, never revives), `/api/nudge` (server-side relative step, may revive), `/api/set` and `/api/display` (sleep/wake only; it never sends off/hardoff). It checks `/api/hello` on the LAN URL before sending the token in cleartext, and falls back to the tunnel. URLs and token are entered on the phone (`monctl pair`); never hardcode them, since this repo is public.
- **`monctl`** resolves helper binaries by script location first and then a hardcoded PATH. Shortcuts/Siri/launchd give it a bare PATH, and under `err_return` a failed lookup used to abort silently. Keep `_find`, and keep failures visible: ddcprobe stderr is passed through deliberately, and writes report refusal (`_wrote`, `_ddcw`) rather than printing success unconditionally.
- **Writes are coalesced** in the UIs (about 100 ms in the Swift app, 130 ms in the web UI, one request in flight) because a DDC round-trip takes about 200 ms.
- `watch.py` polls VCP `0x02` (the MCCS "new control value" latch), clears it, and diffs a watched set of controls. That is the only way to detect OSD button presses.

## Hardware gotchas worth remembering

- Acer's capabilities string is wrong in both directions (`0x8D` mute works but isn't listed; `E0`/`E7` are listed but don't answer). Trust `ddcprobe scan`.
- Capabilities fragments: the payload starts at index **5**, not 6.
- Input codes: `0x0F` DP, `0x11` (17) HDMI1, `0x12` (18) HDMI2. The menu bar app offers HDMI 1, DisplayPort and HDMI 2; the web UI offers HDMI only.
- `0xE1–0xE9`, `0xF0` are unmapped Acer vendor codes. Don't write them speculatively.
