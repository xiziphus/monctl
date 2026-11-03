# MonCtl — brightness & volume for the Acer KG281K

Manual control of the external monitor over **DDC/CI**, which rides the AUX channel
of your USB-C → DisplayPort cable. macOS gives you no brightness slider for external
displays; this fills that gap.

Two front ends over the same engine:

| | What |
| --- | --- |
| `monctl` | CLI in `~/bin` — scriptable, exact values, relative moves |
| `MonCtl.app` | Menu bar sliders in `~/Applications` — the manual knob |
| `monctl web` | Browser control panel replicating the full OSD menu |
| `monctl watch` | Reports changes you make with the monitor's own buttons |

All of them go through `ddcprobe`, the raw-VCP tool built here, which holds a
system-wide lock so they can run simultaneously. `ddcctl` is no longer used.

---

## Install / rebuild

```bash
~/monctl/build.sh
```

Builds the app into `~/Applications/MonCtl.app` and installs the CLI to `~/bin/monctl`.

To keep the menu bar app around permanently:
**System Settings → General → Login Items → Open at Login → +** → `~/Applications/MonCtl.app`

---

## CLI

```bash
monctl                 # status of every control, with meters
monctl b               # print brightness
monctl b 50            # set brightness
monctl b +10           # raise by 10   (clamps at max)
monctl b -10           # lower by 10   (clamps at 0)
monctl v 30            # volume
monctl c 60            # contrast
monctl mute | unmute | toggle
monctl rgb 50 50 50    # red/green/blue gain
monctl input           # current input source
```

Sample:

```text
Acer KG281K  (ddcctl display 1)
  Brightness   ████████████████········   70/100
  Contrast     ████████████············   50/100
  Volume       ████████████████████████  100/100
  Mute         unmuted
```

### Env overrides

- `MONCTL_DISPLAY` — ddcctl display index, default `1`
- `DDCCTL` — path to the binary, default `/usr/local/bin/ddcctl`

---

## Menu bar app

Click the ☀ icon. A **Powered on N h** line (the panel's lifetime hours, VCP `0xC0`),
two big input buttons side by side, **HDMI 1** and **DisplayPort** (the active one
is lit; **Other input ▸ HDMI 2** below them), brightness and
volume sliders, a mute toggle, and **Open Web UI** (⌘W).

The web item is self-managing: it TCP-probes port 8770 each time the menu opens
and relabels itself. If a server is already up — started from a terminal or by a
previous click — it just opens the browser. Otherwise it reads **Start & open
Web UI**, launches `webui/server.py`, waits for the port to accept, then opens
the browser (so you never land on connection-refused). A server the app started
is terminated when the app quits; one you started yourself is left alone.

It re-reads the display **every time the menu opens**, so it stays correct even if
you changed something from the monitor's own OSD buttons or from the CLI.

The app drives `ddcprobe`, not `ddcctl`, so it shares the system-wide bus lock
with the CLI, the watcher, and the web server.

---

## Keyboard shortcuts

DDC isn't wired to the media keys, so bind them yourself. Easiest route without
installing anything: **Shortcuts.app → new Shortcut → "Run Shell Script"**

```bash
/Users/dell/bin/monctl b +10
```

Then assign a key in the shortcut's settings. One shortcut each for `+10` / `-10`.

---

## Web UI

```bash
monctl web            # http://127.0.0.1:8770, opens your browser
monctl web 9000       # different port
```

A browser panel replicating the monitor's OSD menu: Picture, Colour, Six-axis,
Audio, System, Restore, Information, plus a capabilities viewer. 38 controls.

- **Token-guarded beyond loopback.** It binds 127.0.0.1 unless started with
  `--lan` (the LaunchAgent does). Every request other than direct loopback
  needs the token from `~/.config/monctl/token` (created on first start), as
  `?k=` once in a browser (a cookie carries it after that) or as the
  `X-Monctl-Token` header. Requests arriving through the Cloudflare tunnel come
  from 127.0.0.1 too; they carry `Cf-*` headers, and those are never treated as
  local. Ten bad tokens from one client lock it out for 10 minutes.
- **Readings stream in one at a time.** `GET /api/stream` is Server-Sent Events:
  the server runs `ddcprobe many` under `Popen` and emits a `value` event per
  line as the probe yields it, rather than waiting for the whole batch. Each
  value lands in its row the moment it arrives, ~90 ms apart.

  DDC is a single serial bus, so this is **not** faster — the values were always
  going to arrive one at a time. It removes the stall: previously a section
  showed nothing for seconds and then snapped in all at once. Same total time,
  much better perceived latency. `GET /api/state?codes=…` still exists for the
  batched, one-shot form.
- **Slider drags are coalesced** into one write per 130 ms, with a single
  request in flight — the same reason the menu bar app does it.
- **Destructive controls are gated** behind a confirm dialog: input switching,
  power state, and the three factory restores.
- Writes are validated server-side against the catalogue; unknown codes and
  non-integer values are rejected rather than passed to the hardware.

### Current panel

The panel is now a single file, `webui/index.html` (no build step): a big HDMI 1
button, brightness/volume/mute, sleep/wake/power off, auto brightness, the
power-on hours, and a collapsed Advanced section rendered from `/api/catalog`.
`server.py` no longer serves `app/dist`; the React app below is retained but unused.

### React app (superseded)

The panel is a Vite + React + Tailwind app in `app/`, using five Aceternity
components installed through the shadcn CLI:

| Component | Used for |
| --- | --- |
| `glowing-effect` | pointer-tracked border glow on every module |
| `stateful-button` | restore actions, cycler start/stop, caps fetch — idle → loading → success |
| `sparkles` | masthead particle field |
| `container-text-flip` | masthead stat rotation |
| `use-outside-click` | expandable Capabilities card |

```bash
cd ~/monctl/app
npm run build      # -> app/dist, served by server.py
npm run dev        # localhost:5173, proxies /api to the Python server
```

`server.py` serves `app/dist` when it exists and falls back to the original
`webui/index.html` otherwise, so the panel still works without a Node toolchain.
Static asset paths are confined to `dist/` — a literal `../` returns 403.

Two upstream fixes were needed after install:

- `stateful-button` hardcodes `layoutId="button"`, so every instance shared one
  shared-layout animation and they swapped places when clicked. Now takes a
  `layoutKey` prop.
- `sparkles` targets `@tsparticles/react@3`; v4 dropped `initParticlesEngine`.
  Pinned to v3. `container-text-flip` also imported `@/utils/cn` rather than
  `@/lib/utils`.

Files: `webui/server.py` (stdlib only) and `app/` (the React build).

## Android companion

`android/` is a Kotlin/Compose app with a home-screen widget:
on/off, brightness, volume, mute, input (DisplayPort, HDMI 1, HDMI 2), and start/stop
for the AirPlay-display watcher. It also has the Mac's own controls (the MTMR
Touch Bar set) and a screen preview. It talks to
`server.py` on the LAN when the phone is on home Wi-Fi and through a
Cloudflare tunnel otherwise.

```text
phone ──LAN (http, :8770)──────────────────────────────┐
     └─https://<tunnel host>──Cloudflare──cloudflared──┴─> server.py ─> ddcprobe ─> monitor
```

**Mac side, once:**

1. The web server's LaunchAgent (`monctl autostart on`) runs it with `--lan` on
   port 8770 and creates `~/.config/monctl/token`.
2. Tunnel: `cloudflared tunnel create monctl`, `cloudflared tunnel route dns
   monctl <host>`, and a `~/.cloudflared/monctl.yml` sending `<host>` to
   `http://127.0.0.1:8770`, run by its own LaunchAgent. None of this is in the repo.
3. `monctl pair` prints a `monctl://pair?...` link (and a QR code if
   `qrencode` is installed) holding the LAN URL, tunnel URL and token. Open it
   on the phone, or copy it and use **Settings → Paste pairing link**.

**Build and install** (needs the Android SDK, platform 35, and JDK 17):

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Updates.** `android/publish.sh` builds the app and copies it to
`~/.local/share/monctl/android/` with a `version.json` (versionCode, name,
SHA-256). `server.py` serves that as `GET /api/app/version` and
`GET /api/app/apk`, behind the token, over whichever route the phone uses.
The app checks on every open and shows **Update**: it downloads, checks the
SHA-256, and installs through `PackageInstaller`. The first time, Android asks
to allow MonCtl to install apps, and confirms the update. After that, Android 12+
installs later updates without asking. The versionCode is minutes since the
epoch, so every build outranks the last. Builds are signed with this Mac's
`~/.android/debug.keystore`; lose it and the installed app can't be updated
(uninstall and reinstall instead).

**How it picks a route.** Before sending the token in cleartext to the LAN
address, the app asks it `GET /api/hello` (unauthenticated) with a 700 ms
budget, because another Wi-Fi network can have a different device at the same
private IP. If that fails it uses the tunnel. The choice is cached for a minute
and dropped on any network change. Only reads fall back to the other route
after a failure: a timed-out write may still have reached the Mac.

**Endpoints it uses:** `GET /api/remote` (power state plus brightness, volume,
mute and input in one call; never wakes the display), `POST /api/nudge
{code, delta}` (a relative step computed against the live value, which is what
the widget's −/+ buttons send since its cached state can be 15 minutes old),
`POST /api/set`, `POST /api/display`, and `POST /api/watcher {action:
"start"|"stop"}`. Stop sets the watcher's pause flag (its LaunchAgent is
`KeepAlive`, so killing it would only respawn it); start clears it and
kickstarts `com.dell.airplaywatch` if the process has died. Both are
idempotent, unlike the body-less toggle the other front ends use.

**The Mac (the Touch Bar set).** What the MTMR Touch Bar shows and does, for
the phone: battery, Mac volume and mute, built-in display brightness, Wi-Fi
(On, Scan, Join) and AirPlay (Scan, Connect, Disconnect, plus the watcher).
`GET /api/mac` returns all the readouts in one call (about 1 s; `osascript`
for the volume is most of it). The actions are `POST /api/mac/volume
{value|muted}`, `/api/mac/brightness {value}`, `/api/wifi/on`,
`/api/wifi/scan`, `/api/wifi/join {ssid, password?}`, `/api/airplay/scan`,
`/api/airplay/connect {mac, name}` and `/api/airplay/disconnect`. Each one runs
a fixed `~/bin` script with validated arguments, never a command built from the
request or read from `items.json`. The Touch Bar's own menu scripts aren't
used, because they rewrite `items.json` and restart MTMR. `airplay_pick.sh` and
`airplay_off.sh` skip that restart when `MONCTL_NO_MENU` is set.

- Joining a remembered network uses `wifi_join.sh`, which reads the password
  from the System keychain. A new network takes a password typed on the phone.
  That password crosses the LAN in cleartext, or the tunnel over HTTPS.
- Switching the Mac's Wi-Fi cuts the connection the phone may be using. The app
  warns, then looks for the Mac again on whichever route answers. There is
  deliberately no Wi-Fi Off, as on the Touch Bar.
- The widget's large size adds a `MAC 80% ⚡ · <SSID>` line.
- `/api/mac` also reports the current sound output (`output`, from Homebrew
  `SwitchAudioSource`) and whether an AirPlay display is online
  (`airplay.connected`). The watcher's status line can't say that while it is
  paused.

**Layout.** The app is a control-centre grid (`ui/Tiles.kt`): a monitor group,
a Mac group and the screen preview. Tiles that switch something (power, mute,
watcher) do it on tap. Their › corner, or a long-press, opens a bottom sheet
(`ui/Sheets.kt`). Tiles that need a choice (input, Wi-Fi, AirPlay, battery)
open their sheet on tap. Sliders are tall tiles. Dragging moves the value by
how far the finger travels, from wherever it was, so there is no thumb to find
and overshooting stops at the end. A tap opens fine control (−10/−1/+1/+10 and
presets). Switching input is confirmed inside its sheet (pick, then "Switch
to …"). Only "Power off completely" and joining a Wi-Fi network get a dialog.
Settings → Edit tiles hides the screen preview, watcher, Mac mute and lid
tiles. Haptic ticks every 10 steps can be turned off.

**Watcher settings.** `GET/POST /api/watcher/config` read and change
`~/.config/monctl/watcher.conf`. The keys are announcement mode
(spoken/sounds/off), voice, the "Wi-Fi still down" and "still locked" repeat
intervals, whether alerts move sound to the Mac's speakers, pausing while the
Acer is plugged in, dropping AirPlay when the lid closes, keeping MTMR alive,
and the reconnect/rediscover/rescan timings. `POST /api/watcher/test` plays one
announcement with the saved settings. The file is `KEY=value` lines.
`~/bin/airplay_cue.sh`, sourced by `airplay_watch.sh` and `airplay_toggle.sh`,
parses it against an allow-list rather than sourcing it, and rereads it every
loop, so a change applies within one 5 s tick. The server validates every
value too: voices must be ones `say -v ?` lists, and numbers have ranges.

**Screen preview.** `GET /api/screen/displays` lists the displays the server
can capture. Mirrored displays (the AirPlay Pad mirroring the built-in) are
folded into the one they mirror. `GET /api/screen?d=<id>&w=<px>` returns a JPEG
of that display's rectangle (`screencapture -R`, then `sips`). It takes about
0.9 s and is 50–200 KB at 1280 px. The app shows a thumbnail and a full-screen
live view: about one frame a second, only while the app is in front, pinch to
zoom. Limits:

- **LAN-only.** Through the tunnel the server answers 403 unless
  `~/.config/monctl/screen_remote` exists.
- **Never wakes the display.** An asleep display answers 409.
- **It shows only what the Mac draws.** An HDMI source on the Acer isn't
  visible.
- **It needs Screen Recording permission** for the Python that runs the server
  (Xcode's `Python.app` under the LaunchAgent). The first request asks for it.
  Grant it, then restart the server. macOS 15 also asks you to re-approve
  screen recording from time to time.

**Bluetooth fallback.** For when neither the LAN nor the tunnel answers, for
example because the Mac is off Wi-Fi. `ble/monctl-ble.swift` runs as its own
background app (`~/Applications/MonCtlBLE.app`, built by `build.sh`). It
advertises a BLE service and forwards each request to `server.py` on loopback.

```text
phone ──BLE──> monctl-ble ──http://127.0.0.1:8770──> server.py
```

- **Setup:** on the Mac run `monctl ble on` once. It installs the
  `com.dell.monctl.ble` LaunchAgent; macOS asks to allow Bluetooth for "MonCtl
  BLE". On the phone: **Settings → Bluetooth → On**, which asks for Bluetooth
  permission. `monctl ble status` shows whether it's running, with the end of
  `/tmp/monctl-ble.log`.
- **Security comes from the token, not Bluetooth pairing.**
  - Every message is sealed with AES-GCM, using a key derived from the token
    (HKDF-SHA256). Without the token nothing can be read, forged or altered.
  - Each connection starts with a random session nonce from the Mac, and each
    request carries a counter that must increase. Both are bound into the seal,
    so a recorded request can't be replayed.
  - Only an allow-list of routes is forwarded: monitor, display, watcher, Mac,
    Wi-Fi and AirPlay controls. Not the screen preview, which is too big for
    BLE, and not the APK.
- **When it's used:** after the LAN and the tunnel fail. Reads always fall
  back. A write falls back only when the failure proves it never reached the
  Mac: tunnel 530 ("origin not connected"), a refused connection, or no DNS. A
  route that worked is kept for a minute, as with the others. The header and
  the widget show `BLUETOOTH` / `BT`.
- **Limits:**
  - Range is about 10 m.
  - The Mac must be awake with Bluetooth on, and `server.py` running.
  - The first request takes 1–3 s to scan and connect. The link then stays
    open for 30 s of quiet.

**Limits:**

- **On/Off is display wake/sleep.** Off is `pmset displaysleepnow`: the Mac
  stops sending a picture and the monitor drops to standby. On is
  `caffeinate -u`. That is the only off that can be undone remotely. The app
  also has **Power off completely** (VCP `D6=5`, LED dark) behind a
  confirmation; it kills the DDC bus, so only the physical button turns the
  monitor back on. If the monitor isn't answering, the app says so, but its
  controls stay visible and each tap still tries.
- **The Mac has to be awake.** On AC this Mac is set to `sleep 0`, so the
  display can sleep while the server stays reachable. On battery, remote
  control stops when the Mac sleeps.
- Input switches are confirmed in the app and in the widget (a one-tap dialog),
  as in every other front end.
- **There is no "current input".** Reading VCP `0x60` on this Acer returns 15
  (DisplayPort, with max=1) whatever input is showing, presumably because the
  query arrives over the DP cable. Switching works from any input. The app
  therefore never highlights an input or disables a button; it shows only the
  input monctl last switched to (`last_input` in `/api/remote`, tracked by
  `write_one` in server.py). Changes made with the OSD buttons are invisible.
  The web panel and menu bar app still light a button from the bogus read.
- The widget refreshes after each tap, when the app is opened, and every
  15 minutes (WorkManager's minimum) while there's a network.

## Detecting OSD button presses

```bash
monctl watch                 # print changes as you press the monitor's buttons
monctl watch --notify        # also post macOS notifications
monctl watch --exec 'CMD'    # run CMD per change ($MONCTL_CONTROL/_OLD/_NEW set)
```

**What is and isn't possible.** DDC/CI is strictly host-polled — there is no
interrupt, no push channel, and no per-button reporting. You cannot learn that
"the down button was pressed."

What you *can* have is MCCS **VCP `0x02`, "New Control Value"** — a latch the
panel raises whenever a setting is saved, and which the host can clear by
writing `1`. Verified on this monitor: reads `2`, write `1`, reads `1`. So:

```text
poll 0x02 -> if raised, clear it, re-read the controls, diff, report
```

That gives you *that* the OSD was used and *what* value landed where — a real
change notification, just polled rather than pushed. Confirmed working against
actual button presses (caught a manual brightness change to 100).

Limits worth knowing:

- Menu navigation that doesn't commit a value shows as `OSD activity (no watched
  value changed)` — the latch fires, but nothing observable moved.
- The latch also fires for *our own* writes, so scripted changes appear too.
- Each detection costs a full re-read of the watch set (~25 controls), so
  reporting lags a press by a few seconds.

## The bus lock

DDC has no transaction isolation — a reply is just the next read on the wire, so
two processes probing at once can receive each other's replies as
plausible-but-wrong values rather than an error. Every `ddcprobe` invocation
therefore takes an exclusive `flock` on `/tmp/monctl-ddc.lock` for its lifetime,
which serialises the CLI, the watcher, and the web server against each other.

All four front ends — CLI, menu bar app, watcher, and web server — go through
`ddcprobe`, so they all take the lock and are safe to run together. `ddcctl`
does its own I2C and does **not** participate; nothing here calls it any more.

## `ddcprobe` — raw VCP access

`ddcctl` only exposes 8 hardcoded controls. `ddcprobe` (built here, Intel
IOFramebuffer I2C path) reaches any VCP code, which is how the table below
was established.

```bash
ddcprobe caps            # DDC/CI capabilities string (VCP 0xF3)
ddcprobe scan            # read all 256 codes, list the ones that answer
ddcprobe get 6C
ddcprobe set 6C 50
```

The capabilities string arrives in fragments framed as
`[6E][80|len][E3][off_hi][off_lo][data…][checksum]` — the payload starts at
index **5**. Reading from index 6 corrupts exactly one byte per fragment and
still looks almost right, which is a nasty way to lose an hour.

## What this monitor actually supports

**61 of 256 VCP codes answer.** That's most of the OSD, not just brightness.
Reported `mccs_ver(2.1)`, `model(KG281K)`.

| OSD feature | VCP | Exposed as |
| --- | --- | --- |
| Brightness | `0x10` | `monctl b` |
| Contrast | `0x12` | `monctl c` |
| Colour temperature preset | `0x14` | `monctl temp` — 6500/7500/9300/User |
| R/G/B gain | `0x16/18/1A` | `monctl rgb` |
| Black level R/G/B ("black boost") | `0x6C/6E/70` | `monctl black` |
| Six-axis **saturation** R Y G C B M | `0x59–0x5E` | `monctl sat` |
| Six-axis **hue** R Y G C B M | `0x9B–0xA0` | `monctl hue` |
| Input source | `0x60` | `monctl source` — DP / HDMI1 / HDMI2 |
| Speaker volume | `0x62` | `monctl v` |
| Mute | `0x8D` | `monctl toggle` |
| Power / DPMS | `0xD6` | `monctl standby` / `wake` |
| Factory restores | `0x04/05/08` | `monctl reset` |
| Lifetime power-on hours | `0xC0` | `monctl hours` |
| Display scaling (wide mode) | `0x86` | `monctl vcp 86` |
| Screen orientation | `0xAA` | `monctl vcp AA` |
| OSD language | `0xCC` | `monctl vcp CC` |
| OSD / button lock | `0xCA` | `monctl vcp CA` |
| H/V position | `0x20/0x30` | `monctl vcp 20` |
| Vertical frequency (read-only) | `0xAE` | reports 5999 = 59.99 Hz |

Two quirks worth knowing:

- **`0x8D` (mute) is missing from the capabilities string but works anyway.**
  Trust the scan over the caps string.
- The reverse also holds: caps advertises `E0` and `E7`, but neither answers.
  Acer's caps string is unreliable in both directions.

### Not yet identified

`0xE1 0xE2 0xE3 0xE4 0xE5 0xE6 0xE8 0xE9 0xF0` answer but are
manufacturer-specific and unlabelled. By value-range these are very likely the
Acer extras — Over Drive, ACM, Blue Light, Super Sharpness, game/picture modes
(`0xE2` has 11 discrete values, `0xE9` has 3). Mapping them means writing
unknown vendor codes and watching the panel, so they're left alone by default;
`monctl vcp E2 <n>` will poke one if you want to find out.

Panel: 27.8″, native **3840×2160 @ 59.997 Hz**, sRGB/Rec.709 gamut on an accurate
D65 white point, gamma 2.2, manufactured week 12 2019.

### Things that are *not* possible, already checked

- **HDR** — the EDID advertises HDR10/ST-2084 (max 329 cd/m², min 0.33), but macOS
  reports EDR headroom `1.0` for this display and offers no toggle. 329 nits on an
  sRGB TN panel doesn't clear Apple's bar. Not a bandwidth limit: 4K60 at 10 bpc is
  16.0 Gbps, under HBR2's 17.28.
- **Refresh above 60 Hz** — macOS offers 24/25/30/60 at 4K, nothing higher. The EDID
  caps vertical at 60 Hz, and the only >60 rate anywhere in the 298 reported modes is
  60.25 Hz at 800×600 (a legacy VESA timing).
- **Overclocking** — would need custom timing injection via a display override plist,
  but SIP is enabled so `/System/Library/Displays/…/Overrides/` is read-only, and
  there's no override for `DisplayProductID-62d`. Theoretical headroom to the declared
  600 MHz pixel-clock ceiling is only ~12.5% over native anyway (~67 Hz), on a 60 Hz
  TN panel that almost certainly won't sync it.

---

## Notes

- DDC round-trips take ~200 ms. The app coalesces slider drags into one write per
  100 ms window; without that the AUX channel backs up and the slider feels laggy.
- The app is ad-hoc signed and built locally, so there's no Gatekeeper prompt.
- If the monitor stops responding to DDC after a sleep/wake cycle, unplugging and
  replugging the USB-C cable re-establishes it.
