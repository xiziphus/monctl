// MonCtl — menu bar sliders for external-monitor brightness & volume over DDC/CI.
//
// Drives ddcprobe (not ddcctl) so every transaction goes through the same
// system-wide flock as the CLI, the watcher, and the web UI. DDC has no
// transaction isolation — concurrent probes read each other's replies and
// surface plausible-but-wrong values rather than an error.
//
// Build:  ./build.sh      Run: open ~/Applications/MonCtl.app

import Cocoa
import Darwin

let DDCPROBE = ProcessInfo.processInfo.environment["DDCPROBE"]
    ?? (NSString(string: "~/bin/ddcprobe").expandingTildeInPath)
let WEB_PORT: UInt16 = 8770
let WEB_SERVER = NSString(string: "~/monctl/webui/server.py").expandingTildeInPath
let WEB_AGENT  = "com.dell.monctl.web"
let WEB_AGENT_PLIST = NSString(string: "~/Library/LaunchAgents/\(WEB_AGENT).plist")
    .expandingTildeInPath

// VCP codes
enum VCP {
    static let brightness = "10"
    static let volume     = "62"
    static let mute       = "8D"
    static let power      = "D6"      // DPMS power mode
    static let hours      = "C0"      // lifetime power-on hours
    static let source     = "60"      // 0x0F DisplayPort, 0x11 HDMI 1, 0x12 HDMI 2
}

let DP    = 15
let HDMI1 = 17
let HDMI2 = 18

// MARK: - DDC plumbing

enum DDC {
    static let queue = DispatchQueue(label: "monctl.ddc")
    private static var pending: [String: Int] = [:]
    private static var scheduled = false

    private static func log(_ m: String) {
        FileHandle.standardError.write(Data("MonCtl: \(m)\n".utf8))
    }

    /// nil means ddcprobe itself failed -- no DDC-capable bus, or a write the
    /// panel refused. An empty string is a legitimate empty answer. The exit
    /// status used to be dropped on the floor and stderr sent to nullDevice,
    /// so "the bus is down" and "this control does not exist" were the same
    /// value here, and neither left a trace anywhere.
    /// The panel takes its DDC bus down whenever the display sleeps, so every
    /// control here fails while the screen is off -- and reads and writes need
    /// opposite answers to that. A write is someone pressing something and may
    /// wake the panel to be delivered (`revive: true`); a poll must never, or
    /// the menu bar refreshing itself would keep a dark monitor lit forever.
    @discardableResult
    static func run(_ args: [String], revive: Bool = false) -> String? {
        if let out = attempt(args) { return out }
        guard revive else { return nil }
        // -u declares user activity; -t 2 keeps the assertion brief.
        let w = Process()
        w.executableURL = URL(fileURLWithPath: "/usr/bin/caffeinate")
        w.arguments = ["-u", "-t", "2"]
        do { try w.run(); w.waitUntilExit() } catch { return nil }
        Thread.sleep(forTimeInterval: 2.0)
        log("DDC bus was asleep -- woke the display and retried")
        return attempt(args)
    }

    @discardableResult
    private static func attempt(_ args: [String]) -> String? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: DDCPROBE)
        p.arguments = args
        let out = Pipe(), err = Pipe()
        p.standardOutput = out
        p.standardError  = err
        do { try p.run() } catch {
            log("could not run \(DDCPROBE): \(error.localizedDescription)")
            return nil
        }
        // ddcprobe writes at most a line or two to stderr, so draining stdout
        // first cannot fill the stderr pipe and deadlock.
        let data  = out.fileHandleForReading.readDataToEndOfFile()
        let edata = err.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        guard p.terminationStatus == 0 else {
            let why = String(data: edata, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            log("ddcprobe \(args.joined(separator: " ")) failed\(why.isEmpty ? "" : " -- " + why)")
            return nil
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Blocking read of a VCP control. Returns (current, max).
    static func query(_ code: String) -> (cur: Int, max: Int)? {
        guard let out = run(["get", code]) else { return nil }
        guard let r = out.range(of: #"current=\d+ max=\d+"#, options: .regularExpression)
        else { return nil }
        let nums = String(out[r])
            .components(separatedBy: CharacterSet.decimalDigits.inverted)
            .filter { !$0.isEmpty }
            .compactMap(Int.init)
        guard nums.count >= 2 else { return nil }
        return (nums[0], nums[1])
    }

    /// Coalesced write. Dragging a slider fires continuously; a DDC round-trip
    /// costs ~100-200 ms, so collapse everything inside a 100 ms window down to
    /// the last value per control.
    static func set(_ code: String, _ value: Int) {
        queue.async {
            pending[code] = value
            guard !scheduled else { return }
            scheduled = true
            queue.asyncAfter(deadline: .now() + 0.10) {
                let batch = pending
                pending.removeAll()
                scheduled = false
                // A refused write is not silently dropped any more: run()
                // logs the reason, and the next menuWillOpen refresh() pulls
                // the row back to what the panel actually holds.
                for (c, v) in batch { _ = run(["set", c, "\(v)"], revive: true) }
            }
        }
    }

    static func displayName() -> String? {
        guard let n = run(["name"])?
            .trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
        return n.isEmpty ? nil : n
    }
}

// MARK: - Web UI

enum WebUI {
    static let url = URL(string: "http://127.0.0.1:\(WEB_PORT)/")!
    private(set) static var ownedProcess: Process?

    /// Cheap TCP probe — the server may have been started from a terminal, so
    /// tracking our own child process is not enough to know if one is up.
    static func isRunning() -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = WEB_PORT.bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        var tv = timeval(tv_sec: 0, tv_usec: 300_000)
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        return withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0
            }
        }
    }

    /// Prefer the LaunchAgent. Its plist is the single source of truth for how
    /// this server has to run -- /usr/bin/python3 (Homebrew python is not in
    /// this Mac's firewall allow-list, so a LAN bind under it is dropped with
    /// no error anywhere), --lan, and the shared token the tablet's saved
    /// cookie carries.
    ///
    /// This used to always spawn its own copy via `env python3` with neither
    /// --lan nor --token, so a panel started from this menu came up
    /// localhost-only and untokened -- unreachable from the tablet. And it only
    /// ever ran when the agent was already down, which is exactly the moment
    /// that matters.
    static func launch() -> Bool {
        if FileManager.default.isReadableFile(atPath: WEB_AGENT_PLIST) {
            let lc = Process()
            lc.executableURL = URL(fileURLWithPath: "/bin/launchctl")
            lc.arguments = ["kickstart", "-k", "gui/\(getuid())/\(WEB_AGENT)"]
            lc.standardOutput = FileHandle.nullDevice
            lc.standardError  = FileHandle.nullDevice
            if (try? lc.run()) != nil {
                lc.waitUntilExit()
                if lc.terminationStatus == 0 {
                    // launchd owns it now; quitting MonCtl must not take it down.
                    ownedProcess = nil
                    return true
                }
            }
        }
        // No agent installed or it refused to start. Run it ourselves -- still
        // not via `env python3` -- and stay on 127.0.0.1, since without the
        // agent we have no token with which to protect a LAN bind.
        guard FileManager.default.isReadableFile(atPath: WEB_SERVER) else { return false }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        p.arguments = [WEB_SERVER, "--no-browser", "--port", "\(WEB_PORT)"]
        p.standardOutput = FileHandle.nullDevice
        p.standardError  = FileHandle.nullDevice
        do { try p.run() } catch { return false }
        ownedProcess = p
        return true
    }

    static func stopIfOwned() {
        if let p = ownedProcess, p.isRunning { p.terminate() }
        ownedProcess = nil
    }
}

// MARK: - Auto brightness

/// The daemon is one shared external process rather than a child of whoever
/// started it, so this app and the web UI observe the same state instead of
/// each tracking its own copy. pgrep is the source of truth.
enum Auto {
    static let script = NSString(string: "~/monctl/autobright.py").expandingTildeInPath
    static let alsread = NSString(string: "~/bin/alsread").expandingTildeInPath
    private static let pattern = "autobright.py"

    private static func shell(_ launch: String, _ args: [String]) -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: launch)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError  = FileHandle.nullDevice
        do { try p.run() } catch { return "" }
        let d = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(data: d, encoding: .utf8) ?? ""
    }

    static func isRunning() -> Bool {
        !shell("/usr/bin/pgrep", ["-f", pattern]).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func start() {
        guard !isRunning(), FileManager.default.isReadableFile(atPath: script) else { return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        p.arguments = ["python3", script]
        if let log = FileHandle(forWritingAtPath: "/tmp/monctl-auto.log") {
            log.seekToEndOfFile()
            p.standardOutput = log
            p.standardError  = log
        }
        try? p.run()          // detached on purpose; pgrep owns lifecycle
    }

    static func stop() { _ = shell("/usr/bin/pkill", ["-f", pattern]) }

    static func lux() -> Double? {
        Double(shell(alsread, []).trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

// MARK: - Display power (DPMS)

/// Puts the MONITOR to sleep, not the Mac. macOS keeps running, keeps driving
/// the video signal, and has no idea the panel went dark -- this is purely the
/// display's own DPMS state over DDC.
///
/// Values match the `monctl standby` / `monctl wake` CLI verbs (VCP 0xD6:
/// 1 = on, 4 = off). This panel reports the code back on read (current=1
/// max=4), so the menu shows real state instead of tracking a guess that
/// desynchronises the moment anything else touches the monitor.
///
/// CAVEAT worth knowing: because macOS still thinks the display is awake, a
/// mouse move will NOT bring it back. Recovery is `monctl wake` from a shell,
/// or this menu item from the built-in display.
enum DisplayPower {
    private static func shell(_ launch: String, _ args: [String]) -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: launch)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return "" }
        let d = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(data: d, encoding: .utf8) ?? ""
    }

    /// IODisplayWrangler's power state: 4 = on, lower = progressively asleep.
    /// Read from macOS rather than from VCP 0xD6, because the DDC register goes
    /// unreadable exactly when the panel is dark -- the moment we most need it.
    static func isAsleep() -> Bool {
        let out = shell("/usr/sbin/ioreg", ["-n", "IODisplayWrangler", "-r", "-d", "1"])
        guard let r = out.range(of: #""CurrentPowerState"=\d+"#, options: .regularExpression),
              let v = Int(out[r].split(separator: "=")[1]) else { return false }
        return v < 4
    }

    /// macOS drops the video signal, exactly as it does on idle timeout. The
    /// panel sleeps gracefully and keeps its DDC controller alive.
    static func sleep() { _ = shell("/usr/bin/pmset", ["displaysleepnow"]) }

    /// -u declares user activity; -t 1 stops it holding the assertion open.
    static func wake() { _ = shell("/usr/bin/caffeinate", ["-u", "-t", "1"]) }

    /// The DDC power-down, kept because it is genuinely different: the panel
    /// powers ITSELF off rather than losing signal.
    ///
    /// VCP 0xD6 = 5 is defined by MCCS 2.1 as "turn off power, as if the user
    /// pressed the power button", and this panel advertises it -- the caps
    /// string reads D6(01 04 05). It does not appear in a D6 read (current=1
    /// max=4) because 5 is write-only, which is why it looked unsupported.
    ///
    /// This used to send 4. That was DPMS off: the screen went dark but the
    /// power LED stayed lit, so it was not the button. Verified on this Acer
    /// 2026-09-11: sending 5 kills the LED outright, the DDC bus disappears
    /// with the panel, and macOS never notices -- IODisplayWrangler still
    /// reports CurrentPowerState=4 throughout.
    ///
    /// ONE-WAY. The bus needed to carry a wake command dies with the panel:
    /// `set D6 1` reported success five times against a 4 and nothing
    /// happened, and 5 is strictly harder. Recovery is the physical button.
    /// Every caller must confirm before firing this.
    static func hardOff() { DDC.queue.async { _ = DDC.run(["set", VCP.power, "5"]) } }
}

// MARK: - AirPlay watcher

/// Pause/resume for the AirPlay-display watcher (~/bin/airplay_watch.sh), the
/// same control MTMR exposes on the Touch Bar.
///
/// The watcher polls a flag file, so pausing it is just creating that file --
/// but this shells out to the SAME toggle script the Touch Bar button runs
/// rather than touching the flag directly. Two reasons: the script also plays
/// distinct pause/resume sounds and speaks the new state (the whole point of it
/// is being usable with the panel dead), and a second implementation of one
/// toggle would inevitably drift from the first.
enum Watcher {
    static let flag = "/tmp/airplay_watch.pause"
    static let toggleScript =
        NSString(string: "~/bin/airplay_toggle.sh").expandingTildeInPath

    /// Hide the menu item entirely on a machine without the failsafe installed,
    /// rather than offering a control that silently does nothing.
    static var isInstalled: Bool {
        FileManager.default.isExecutableFile(atPath: toggleScript)
    }

    static var isPaused: Bool {
        FileManager.default.fileExists(atPath: flag)
    }

    /// Whether the watcher process is actually up. Paused-but-dead and
    /// paused-but-running are different situations and the menu should not
    /// present them identically.
    static func isRunning() -> Bool {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pgrep")
        p.arguments = ["-f", "airplay_watch.sh"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return false }
        let d = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return !String(data: d, encoding: .utf8)!
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func toggle() {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/bash")
        p.arguments = ["-lc", toggleScript]
        try? p.run()
    }
}

// MARK: - Window placement

/// "Show windows in screen": moves another app's windows to a chosen screen
/// through the Accessibility API, for when an app opens on the laptop panel
/// and that panel can't be seen. It was asked for as a Dock right-click item,
/// but the Dock's menu belongs to the Dock process and can only be extended
/// by injecting code into it, which needs SIP off -- so it lives here.
enum Screens {
    static func id(_ s: NSScreen) -> CGDirectDisplayID {
        (s.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value ?? 0
    }

    /// Stable across replugs, unlike NSScreen identity or the display ID.
    static func key(_ s: NSScreen) -> String {
        let d = id(s)
        return "\(CGDisplayVendorNumber(d)):\(CGDisplayModelNumber(d)):\(CGDisplaySerialNumber(d))"
    }

    static func screen(forKey k: String) -> NSScreen? { NSScreen.screens.first { key($0) == k } }

    /// A mirror set is one NSScreen, named after its master. Say when the
    /// built-in panel is hiding inside one, or "the laptop screen" goes missing
    /// from the list for no visible reason.
    static func label(_ s: NSScreen) -> String {
        let d = id(s)
        if CGDisplayIsBuiltin(d) != 0 { return "\(s.localizedName) (built-in)" }
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var n: UInt32 = 0
        CGGetOnlineDisplayList(16, &ids, &n)
        let hidesBuiltin = ids.prefix(Int(n)).contains {
            CGDisplayMirrorsDisplay($0) == d && CGDisplayIsBuiltin($0) != 0
        }
        return hidesBuiltin ? "\(s.localizedName) + built-in (mirrored)" : s.localizedName
    }

    /// AX speaks top-left-origin global coordinates, AppKit bottom-left; both
    /// are anchored to the primary screen.
    static func axRect(_ r: NSRect) -> CGRect {
        let h = NSScreen.screens.first?.frame.height ?? 0
        return CGRect(x: r.minX, y: h - r.maxY, width: r.width, height: r.height)
    }

    /// The screen holding most of `r` (AX coordinates), nil if none does.
    static func holding(_ r: CGRect) -> NSScreen? {
        var best: (screen: NSScreen, area: CGFloat)?
        for s in NSScreen.screens {
            let i = axRect(s.frame).intersection(r)
            let a = i.isNull ? 0 : i.width * i.height
            if a > (best?.area ?? 0) { best = (s, a) }
        }
        return best?.screen
    }
}

enum Windows {
    static var trusted: Bool { AXIsProcessTrusted() }

    /// The prompt call is also what puts MonCtl into the Accessibility list.
    static func askForTrust() {
        let opt = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([opt: true] as CFDictionary)
        NSWorkspace.shared.open(URL(string:
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }

    /// A hung app otherwise stalls the menu for AX's default 6 s per call.
    static func limitTimeouts() { AXUIElementSetMessagingTimeout(AXUIElementCreateSystemWide(), 1.0) }

    private static func attr(_ e: AXUIElement, _ name: String) -> CFTypeRef? {
        var v: CFTypeRef?
        return AXUIElementCopyAttributeValue(e, name as CFString, &v) == .success ? v : nil
    }

    @discardableResult
    private static func set(_ e: AXUIElement, _ name: String, _ v: CFTypeRef) -> Bool {
        AXUIElementSetAttributeValue(e, name as CFString, v) == .success
    }

    /// Only windows on the current Space(s): that is all AX reports for most apps.
    static func windows(_ pid: pid_t) -> [AXUIElement] {
        (attr(AXUIElementCreateApplication(pid), kAXWindowsAttribute) as? [AXUIElement] ?? [])
            .filter(movable)
    }

    /// Standard windows and dialogs that can actually be moved: not
    /// minimised, not a fullscreen Space of their own.
    static func movable(_ w: AXUIElement) -> Bool {
        let sub = attr(w, kAXSubroleAttribute) as? String
        guard sub == kAXStandardWindowSubrole || sub == kAXDialogSubrole else { return false }
        if attr(w, kAXMinimizedAttribute) as? Bool == true { return false }
        if attr(w, "AXFullScreen") as? Bool == true { return false }
        return true
    }

    static func frame(_ w: AXUIElement) -> CGRect? {
        guard let p = attr(w, kAXPositionAttribute), let s = attr(w, kAXSizeAttribute) else { return nil }
        var pt = CGPoint.zero, sz = CGSize.zero
        guard AXValueGetValue(p as! AXValue, .cgPoint, &pt),
              AXValueGetValue(s as! AXValue, .cgSize, &sz) else { return nil }
        return CGRect(origin: pt, size: sz)
    }

    static func screensUsed(_ pid: pid_t) -> Set<String> {
        Set(windows(pid).compactMap { frame($0).flatMap(Screens.holding).map(Screens.key) })
    }

    /// Keeps the window's relative place on screen, shrinking it only if the
    /// target is smaller. A window that is on no screen at all is centred.
    static func move(_ w: AXUIElement, to target: NSScreen) {
        guard let f = frame(w) else { return }
        var pid: pid_t = 0
        AXUIElementGetPid(w, &pid)
        // Chrome and Electron apps animate AX moves and drop resizes while
        // this is on; Rectangle and friends switch it off for the move too.
        let owner = AXUIElementCreateApplication(pid)
        let enhanced = attr(owner, "AXEnhancedUserInterface") as? Bool == true
        if enhanced { set(owner, "AXEnhancedUserInterface", kCFBooleanFalse) }
        defer { if enhanced { set(owner, "AXEnhancedUserInterface", kCFBooleanTrue) } }

        let dst = Screens.axRect(target.visibleFrame)
        var size = CGSize(width: min(f.width, dst.width), height: min(f.height, dst.height))
        var rx: CGFloat = 0.5, ry: CGFloat = 0.5
        if let from = Screens.holding(f) {
            let src = Screens.axRect(from.visibleFrame)
            func frac(_ off: CGFloat, _ room: CGFloat) -> CGFloat { room > 0 ? max(0, min(1, off / room)) : 0.5 }
            rx = frac(f.minX - src.minX, src.width - f.width)
            ry = frac(f.minY - src.minY, src.height - f.height)
        }
        var origin = CGPoint(x: dst.minX + (dst.width - size.width) * rx,
                             y: dst.minY + (dst.height - size.height) * ry)
        // Position, size, position: some apps clamp a resize against the
        // screen they are still on, so the size only sticks after the move.
        set(w, kAXPositionAttribute, AXValueCreate(.cgPoint, &origin)!)
        if size != f.size { set(w, kAXSizeAttribute, AXValueCreate(.cgSize, &size)!) }
        set(w, kAXPositionAttribute, AXValueCreate(.cgPoint, &origin)!)
    }

    @discardableResult
    static func moveAll(_ pid: pid_t, to target: NSScreen) -> Int {
        let ws = windows(pid)
        ws.forEach { move($0, to: target) }
        return ws.count
    }
}

private func windowCreated(_ obs: AXObserver, _ w: AXUIElement, _ note: CFString,
                           _ refcon: UnsafeMutableRawPointer?) {
    guard let refcon else { return }
    Unmanaged<WindowPlacer>.fromOpaque(refcon).takeUnretainedValue().placeSoon(w)
}

/// The default screen: windows that open anywhere else are moved onto it, so
/// an app that comes up on the dead laptop panel doesn't stay there. Only NEW
/// windows -- one dragged elsewhere afterwards is left where it was put.
final class WindowPlacer {
    static let shared = WindowPlacer()
    private let keyPref = "defaultScreen", namePref = "defaultScreenName"
    private var observers: [pid_t: AXObserver] = [:]

    var defaultKey: String? { UserDefaults.standard.string(forKey: keyPref) }
    var defaultName: String? { UserDefaults.standard.string(forKey: namePref) }

    func setDefault(_ s: NSScreen?) {
        UserDefaults.standard.set(s.map(Screens.key), forKey: keyPref)
        UserDefaults.standard.set(s.map(Screens.label), forKey: namePref)
        sync()
    }

    func start() {
        let nc = NSWorkspace.shared.notificationCenter
        nc.addObserver(forName: NSWorkspace.didLaunchApplicationNotification, object: nil,
                       queue: .main) { [weak self] n in
            guard let self,
                  let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            else { return }
            self.attach(app)
            // Windows that opened before the observer was in place, and a
            // second go at attaching to an app that wasn't answering AX yet.
            for t in [0.8, 2.5] {
                DispatchQueue.main.asyncAfter(deadline: .now() + t) { self.sweep(app) }
            }
        }
        nc.addObserver(forName: NSWorkspace.didTerminateApplicationNotification, object: nil,
                       queue: .main) { [weak self] n in
            guard let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
                  let obs = self?.observers.removeValue(forKey: app.processIdentifier) else { return }
            CFRunLoopRemoveSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(obs), .commonModes)
        }
        sync()
    }

    /// Attached to every app while a default is set and access is granted,
    /// to none otherwise. Cheap enough to run on every menu open, which is how
    /// an Accessibility grant made after launch gets picked up.
    func sync() {
        guard defaultKey != nil, Windows.trusted else {
            for obs in observers.values {
                CFRunLoopRemoveSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(obs), .commonModes)
            }
            observers.removeAll()
            return
        }
        NSWorkspace.shared.runningApplications.forEach(attach)
    }

    private func attach(_ app: NSRunningApplication) {
        let pid = app.processIdentifier
        guard defaultKey != nil, Windows.trusted, observers[pid] == nil,
              app.activationPolicy == .regular, pid != getpid() else { return }
        var made: AXObserver?
        guard AXObserverCreate(pid, windowCreated, &made) == .success, let obs = made,
              AXObserverAddNotification(obs, AXUIElementCreateApplication(pid),
                                        kAXWindowCreatedNotification as CFString,
                                        Unmanaged.passUnretained(self).toOpaque()) == .success
        else { return }
        CFRunLoopAddSource(CFRunLoopGetMain(), AXObserverGetRunLoopSource(obs), .commonModes)
        observers[pid] = obs
    }

    private func sweep(_ app: NSRunningApplication) {
        attach(app)
        Windows.windows(app.processIdentifier).forEach(place)
    }

    /// Apps often create a window first and position it a moment later.
    func placeSoon(_ w: AXUIElement) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { self.place(w) }
    }

    private func place(_ w: AXUIElement) {
        guard let k = defaultKey, let target = Screens.screen(forKey: k),
              Windows.movable(w), let f = Windows.frame(w),
              Screens.holding(f).map(Screens.key) != k else { return }
        Windows.move(w, to: target)
    }
}

/// Show windows in screen ▸ <app> ▸ <screen>, with the screens the app has
/// windows on ticked, and Default screen ▸ at the bottom. Rebuilt on open.
final class WindowMenu: NSObject, NSMenuDelegate {
    let item = NSMenuItem(title: "Show windows in screen", action: nil, keyEquivalent: "")
    private let root = NSMenu()

    private final class AppMenu: NSMenu { var pid: pid_t = 0 }

    override init() {
        super.init()
        root.delegate = self
        item.submenu = root
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        if let m = menu as? AppMenu { fillApp(m) } else if menu === root { fillRoot() }
    }

    private func fillRoot() {
        root.removeAllItems()
        WindowPlacer.shared.sync()
        if !Windows.trusted {
            let ask = NSMenuItem(title: "Allow Accessibility access…",
                                 action: #selector(askTrust), keyEquivalent: "")
            ask.target = self
            ask.toolTip = "Moving another app's windows needs MonCtl switched on under "
                + "System Settings › Privacy & Security › Accessibility."
            root.addItem(ask)
            root.addItem(.separator())
        }
        let apps = NSWorkspace.shared.runningApplications
            .filter { $0.activationPolicy == .regular && $0.processIdentifier != getpid() }
            .sorted { ($0.localizedName ?? "").localizedCaseInsensitiveCompare($1.localizedName ?? "")
                == .orderedAscending }
        // The app that just opened somewhere unseen is nearly always the
        // frontmost one, so it goes first.
        if let front = NSWorkspace.shared.frontmostApplication, apps.contains(front) {
            root.addItem(appItem(front))
            root.addItem(.separator())
            apps.filter { $0 != front }.forEach { root.addItem(appItem($0)) }
        } else {
            apps.forEach { root.addItem(appItem($0)) }
        }
        root.addItem(.separator())
        root.addItem(defaultItem())
    }

    private func appItem(_ app: NSRunningApplication) -> NSMenuItem {
        let it = NSMenuItem(title: app.localizedName ?? "pid \(app.processIdentifier)",
                            action: nil, keyEquivalent: "")
        if let icon = app.icon?.copy() as? NSImage {
            icon.size = NSSize(width: 16, height: 16)
            it.image = icon
        }
        let sub = AppMenu()
        sub.pid = app.processIdentifier
        sub.delegate = self
        it.submenu = sub
        return it
    }

    private func fillApp(_ m: AppMenu) {
        m.removeAllItems()
        let used = Windows.trusted ? Windows.screensUsed(m.pid) : []
        if Windows.trusted && used.isEmpty {
            m.addItem(NSMenuItem(title: "No windows on this desktop", action: nil, keyEquivalent: ""))
            m.addItem(.separator())
        }
        for s in NSScreen.screens {
            let it = NSMenuItem(title: Screens.label(s), action: #selector(show(_:)), keyEquivalent: "")
            it.target = self
            it.representedObject = (m.pid, Screens.key(s))
            it.state = used.contains(Screens.key(s)) ? .on : .off
            m.addItem(it)
        }
    }

    private func defaultItem() -> NSMenuItem {
        let placer = WindowPlacer.shared
        let current = placer.defaultKey.flatMap(Screens.screen(forKey:))
        let it = NSMenuItem(title: "Default screen: "
                            + (current.map(Screens.label) ?? placer.defaultName ?? "None"),
                            action: nil, keyEquivalent: "")
        it.toolTip = "New windows that open on any other screen are moved here."
        let sub = NSMenu()
        let none = NSMenuItem(title: "None", action: #selector(pickDefault(_:)), keyEquivalent: "")
        none.target = self
        none.representedObject = ""
        none.state = placer.defaultKey == nil ? .on : .off
        sub.addItem(none)
        sub.addItem(.separator())
        if let name = placer.defaultName, current == nil, placer.defaultKey != nil {
            let gone = NSMenuItem(title: "\(name) (not connected)", action: nil, keyEquivalent: "")
            gone.state = .on
            sub.addItem(gone)
        }
        for s in NSScreen.screens {
            let opt = NSMenuItem(title: Screens.label(s), action: #selector(pickDefault(_:)), keyEquivalent: "")
            opt.target = self
            opt.representedObject = Screens.key(s)
            opt.state = Screens.key(s) == placer.defaultKey ? .on : .off
            sub.addItem(opt)
        }
        it.submenu = sub
        return it
    }

    @objc private func askTrust() { Windows.askForTrust() }

    @objc private func show(_ sender: NSMenuItem) {
        guard let (pid, key) = sender.representedObject as? (pid_t, String) else { return }
        guard Windows.trusted else { Windows.askForTrust(); return }
        guard let target = Screens.screen(forKey: key),
              let app = NSRunningApplication(processIdentifier: pid) else { return }
        if app.isHidden { app.unhide() }
        Windows.moveAll(pid, to: target)
        app.activate(options: [.activateAllWindows])
    }

    @objc private func pickDefault(_ sender: NSMenuItem) {
        let key = sender.representedObject as? String ?? ""
        WindowPlacer.shared.setDefault(key.isEmpty ? nil : Screens.screen(forKey: key))
        if !key.isEmpty && !Windows.trusted { Windows.askForTrust() }
    }
}

// MARK: - Slider row

final class SliderRow: NSView {
    let slider = NSSlider()
    private let valueLabel = NSTextField(labelWithString: "--")
    private let onChange: (Int) -> Void

    init(title: String, symbol: String, onChange: @escaping (Int) -> Void) {
        self.onChange = onChange
        super.init(frame: NSRect(x: 0, y: 0, width: 260, height: 40))

        let icon = NSImageView()
        icon.image = NSImage(systemSymbolName: symbol, accessibilityDescription: title)
        icon.contentTintColor = .secondaryLabelColor
        icon.frame = NSRect(x: 14, y: 11, width: 18, height: 18)
        addSubview(icon)

        slider.minValue = 0
        slider.maxValue = 100
        slider.isContinuous = true
        slider.target = self
        slider.action = #selector(changed)
        slider.frame = NSRect(x: 40, y: 10, width: 168, height: 20)
        addSubview(slider)

        valueLabel.frame = NSRect(x: 214, y: 12, width: 36, height: 16)
        valueLabel.alignment = .right
        valueLabel.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        valueLabel.textColor = .secondaryLabelColor
        addSubview(valueLabel)
    }

    required init?(coder: NSCoder) { fatalError() }

    /// Until the first device read lands, the slider sits at its default 0. A
    /// click on the menu in that window would write 0 to the monitor — which is
    /// how brightness kept ending up at zero. Ignore input until synced.
    private var hasSynced = false

    @objc private func changed() {
        guard hasSynced else { return }
        let v = Int(slider.doubleValue.rounded())
        valueLabel.stringValue = "\(v)"
        onChange(v)
    }

    /// Update from a device read without firing onChange.
    func sync(cur: Int, max: Int) {
        slider.maxValue = Double(max)
        slider.doubleValue = Double(cur)
        // Revive a row that an earlier failed read disabled. Without this the
        // grey-out below was a one-way door: one unanswered read -- a sleeping
        // panel, a busy bus -- killed the slider for the life of the app, and
        // every later successful read updated the number next to a control
        // that still could not be dragged.
        slider.isEnabled = true
        valueLabel.stringValue = "\(cur)"
        hasSynced = true
    }

    /// The control did not answer. That is a sleeping panel or a busy bus about
    /// as often as it is a genuinely absent control, so this is reversible --
    /// see sync() above.
    func markUnavailable() {
        slider.isEnabled = false
        valueLabel.stringValue = hasSynced ? "--" : "n/a"
    }
}

// MARK: - Input row

/// Two big buttons side by side, HDMI 1 and DisplayPort. HDMI 2 lives in a
/// submenu below them.
final class InputRow: NSView {
    private let inputs: [(title: String, code: Int)] = [("HDMI 1", HDMI1), ("DisplayPort", DP)]
    private var buttons: [NSButton] = []
    private let onPress: (Int) -> Void

    init(onPress: @escaping (Int) -> Void) {
        self.onPress = onPress
        super.init(frame: NSRect(x: 0, y: 0, width: 260, height: 58))
        let gap: CGFloat = 8
        let w = (232 - gap) / CGFloat(inputs.count)
        for (i, input) in inputs.enumerated() {
            let button = NSButton()
            button.bezelStyle = .rounded
            button.controlSize = .large
            button.font = .systemFont(ofSize: 15, weight: .semibold)
            button.tag = input.code
            button.target = self
            button.action = #selector(pressed(_:))
            button.frame = NSRect(x: 14 + CGFloat(i) * (w + gap), y: 7, width: w, height: 44)
            addSubview(button)
            buttons.append(button)
        }
        setActive(nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    @objc private func pressed(_ sender: NSButton) { onPress(sender.tag) }

    /// The button for `code` is accent-filled; the rest are plain. nil = unknown.
    func setActive(_ code: Int?) {
        for (button, input) in zip(buttons, inputs) {
            let on = input.code == code
            button.bezelColor = on ? .controlAccentColor : nil
            button.contentTintColor = on ? .white : nil
            button.title = on ? "\(input.title) ✓" : input.title
        }
    }
}

// MARK: - App

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()
    private var brightnessRow: SliderRow!
    private var volumeRow: SliderRow!
    private let titleItem = NSMenuItem(title: "Reading display…", action: nil, keyEquivalent: "")
    private let muteItem  = NSMenuItem(title: "Mute", action: #selector(toggleMute), keyEquivalent: "")
    private let webItem   = NSMenuItem(title: "Open Web UI", action: #selector(openWebUI), keyEquivalent: "w")
    private let sourceItem = NSMenuItem(title: "Other input", action: nil, keyEquivalent: "")
    private let hoursItem = NSMenuItem(title: "Powered on …", action: nil, keyEquivalent: "")
    private var inputRow: InputRow!
    private let autoItem  = NSMenuItem(title: "Auto brightness", action: #selector(toggleAuto), keyEquivalent: "a")
    private let luxItem   = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let watcherItem = NSMenuItem(title: "Pause AirPlay watcher",
                                         action: #selector(toggleWatcher), keyEquivalent: "p")
    private let sleepItem = NSMenuItem(title: "Sleep display",
                                       action: #selector(toggleDisplayPower), keyEquivalent: "")
    private let powerOffItem = NSMenuItem(title: "Power off monitor…",
                                          action: #selector(hardOffDisplay), keyEquivalent: "")
    private var currentSource = -1
    private let windowMenu = WindowMenu()

    func applicationDidFinishLaunching(_ n: Notification) {
        guard FileManager.default.isExecutableFile(atPath: DDCPROBE) else {
            let a = NSAlert()
            a.messageText = "ddcprobe not found"
            a.informativeText = "MonCtl needs ddcprobe at \(DDCPROBE).\n\nBuild it with:  cd ~/monctl && ./build.sh"
            a.alertStyle = .critical
            a.runModal()
            NSApp.terminate(nil)
            return
        }

        statusItem.button?.image = NSImage(systemSymbolName: "sun.max",
                                           accessibilityDescription: "Monitor controls")
        statusItem.button?.image?.isTemplate = true

        titleItem.isEnabled = false
        menu.addItem(titleItem)
        hoursItem.isEnabled = false
        menu.addItem(hoursItem)
        menu.addItem(.separator())

        // Input: the buttons that matter, up top.
        inputRow = InputRow { [weak self] in self?.pickSource($0) }
        let inputItem = NSMenuItem()
        inputItem.view = inputRow
        menu.addItem(inputItem)
        let sources = NSMenu()
        let h2 = NSMenuItem(title: "HDMI 2", action: #selector(source(_:)), keyEquivalent: "")
        h2.target = self
        h2.tag = HDMI2
        sources.addItem(h2)
        sourceItem.submenu = sources
        menu.addItem(sourceItem)
        menu.addItem(.separator())

        brightnessRow = SliderRow(title: "Brightness", symbol: "sun.max.fill") {
            DDC.set(VCP.brightness, $0)
        }
        volumeRow = SliderRow(title: "Volume", symbol: "speaker.wave.2.fill") {
            DDC.set(VCP.volume, $0)
        }
        for row in [brightnessRow!, volumeRow!] {
            let item = NSMenuItem()
            item.view = row
            menu.addItem(item)
        }

        menu.addItem(.separator())
        muteItem.target = self
        menu.addItem(muteItem)

        menu.addItem(.separator())
        autoItem.target = self
        menu.addItem(autoItem)
        luxItem.isEnabled = false
        menu.addItem(luxItem)

        menu.addItem(.separator())
        webItem.target = self
        menu.addItem(webItem)

        let refreshItem = NSMenuItem(title: "Refresh from display", action: #selector(refresh), keyEquivalent: "r")
        refreshItem.target = self
        menu.addItem(refreshItem)

        menu.addItem(.separator())
        menu.addItem(windowMenu.item)
        Windows.limitTimeouts()
        WindowPlacer.shared.start()

        // Kept below a separator, away from the sliders: a misclick on either of
        // these has a visible consequence, unlike nudging brightness.
        menu.addItem(.separator())
        watcherItem.target = self
        menu.addItem(watcherItem)
        sleepItem.target = self
        menu.addItem(sleepItem)
        // Sits under Sleep because the two read as neighbours but are not:
        // sleep comes back on a keypress, this comes back on a walk to the
        // desk. The ellipsis and the confirm sheet are the whole guard rail.
        powerOffItem.target = self
        powerOffItem.toolTip = "Powers the panel down as if you pressed its power button. "
            + "The LED goes out and the DDC bus goes with it -- only the monitor's "
            + "own button brings it back."
        menu.addItem(powerOffItem)

        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit MonCtl",
                                action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))

        // AppKit re-enables every item with a valid target/action on open, so
        // the isEnabled bookkeeping in refresh() (mute, input source) was
        // being overwritten before the menu ever drew. Own it explicitly.
        menu.autoenablesItems = false
        menu.delegate = self
        statusItem.menu = menu
        refreshAutoState()
        refresh()
    }

    func applicationWillTerminate(_ n: Notification) {
        WebUI.stopIfOwned()
    }

    /// Re-read the device every time the menu opens, so the sliders reflect
    /// changes made from the monitor's own OSD or from the `monctl` CLI.
    func menuWillOpen(_ menu: NSMenu) {
        webItem.title = WebUI.isRunning() ? "Open Web UI" : "Start & open Web UI"
        refreshAutoState()
        refreshWatcherState()
        refreshDisplayPowerState()
        refresh()
    }

    // MARK: - System controls

    @objc private func toggleDisplayPower() {
        // Dismiss first: with the menu still tracking, the click-through that
        // wakes the panel can land on whatever is underneath.
        menu.cancelTracking()
        let wasAsleep = DisplayPower.isAsleep()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            wasAsleep ? DisplayPower.wake() : DisplayPower.sleep()
        }
    }

    /// Confirms first, and says plainly what the way back is. Anyone reaching
    /// for this from the Pad while the laptop panel is dead needs to know the
    /// recovery is physical BEFORE the screen goes black, not after.
    @objc private func hardOffDisplay() {
        menu.cancelTracking()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            let a = NSAlert()
            a.messageText = "Power off the monitor?"
            a.informativeText = """
                This powers the panel down exactly as its own power button does \
                -- the LED goes out and the DDC bus goes with it.

                Nothing on this Mac can turn it back on. You will have to press \
                the button on the monitor.

                To dim the screen reversibly, use "Sleep display" instead.
                """
            a.alertStyle = .warning
            a.addButton(withTitle: "Power Off")
            a.addButton(withTitle: "Cancel")
            // Escape and Return both land on Cancel; the destructive button
            // has to be aimed at deliberately.
            a.buttons[1].keyEquivalent = "\r"
            a.buttons[0].keyEquivalent = ""
            guard a.runModal() == .alertFirstButtonReturn else { return }
            DisplayPower.hardOff()
        }
    }

    private func refreshDisplayPowerState() {
        let asleep = DisplayPower.isAsleep()
        sleepItem.title = asleep ? "Wake display" : "Sleep display"
        sleepItem.toolTip = asleep
            ? "Displays are asleep. Any keypress wakes them, or click here."
            : "Sleeps the displays via macOS. Wakes on any key, mouse move, or this item."
    }

    @objc private func toggleWatcher() {
        Watcher.toggle()
        // The script writes the flag then plays a sound; give the filesystem a
        // beat before re-reading, or the label lags one click behind.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { self.refreshWatcherState() }
    }

    private func refreshWatcherState() {
        guard Watcher.isInstalled else {
            watcherItem.isHidden = true
            return
        }
        watcherItem.isHidden = false
        let paused = Watcher.isPaused
        watcherItem.title = paused ? "Resume AirPlay watcher" : "Pause AirPlay watcher"
        watcherItem.state = paused ? .on : .off
        // Distinguish "paused" from "not even running": a flag file left behind
        // by a watcher that has since died would otherwise read as a healthy
        // pause, and resuming it would appear to do nothing.
        watcherItem.toolTip = Watcher.isRunning()
            ? (paused ? "Watcher is running but paused" : "Watcher is running")
            : "Watcher process is not running"
    }

    @objc private func toggleAuto() {
        if Auto.isRunning() { Auto.stop() } else { Auto.start() }
        // pgrep needs a beat to see the new process before we relabel.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { self.refreshAutoState() }
    }

    private func refreshAutoState() {
        let on = Auto.isRunning()
        autoItem.state = on ? .on : .off
        autoItem.title = on ? "Auto brightness (on)" : "Auto brightness"
        DDC.queue.async {
            let lx = Auto.lux()
            DispatchQueue.main.async {
                self.luxItem.title = lx.map { String(format: "   ambient %.0f lux", $0) }
                    ?? "   ambient — no sensor"
            }
        }
    }

    @objc private func openWebUI() {
        if WebUI.isRunning() {
            NSWorkspace.shared.open(WebUI.url)
            return
        }
        guard WebUI.launch() else {
            let a = NSAlert()
            a.messageText = "Could not start the web server"
            a.informativeText = "Expected \(WEB_SERVER).\n\nStart it manually with:  monctl web"
            a.runModal()
            return
        }
        // Wait for the port to accept before opening, so the browser does not
        // land on a connection-refused page.
        DispatchQueue.global().async {
            for _ in 0..<50 {
                if WebUI.isRunning() { break }
                usleep(150_000)
            }
            DispatchQueue.main.async { NSWorkspace.shared.open(WebUI.url) }
        }
    }

    @objc private func refresh() {
        DDC.queue.async {
            let name = DDC.displayName()
            let b = DDC.query(VCP.brightness)
            let v = DDC.query(VCP.volume)
            let m = DDC.query(VCP.mute)
            let src = DDC.query(VCP.source)
            let hrs = DDC.query(VCP.hours)
            DispatchQueue.main.async {
                self.titleItem.title = name ?? "External display"
                if let hrs {
                    let f = NumberFormatter()
                    f.numberStyle = .decimal
                    self.hoursItem.title = "Powered on "
                        + (f.string(from: NSNumber(value: hrs.cur)) ?? "\(hrs.cur)") + " h"
                } else {
                    self.hoursItem.title = "Powered on —"
                }
                if let b { self.brightnessRow.sync(cur: b.cur, max: b.max) }
                else { self.brightnessRow.markUnavailable() }
                if let v { self.volumeRow.sync(cur: v.cur, max: v.max) }
                else { self.volumeRow.markUnavailable() }
                if let m {
                    self.muteItem.state = (m.cur == 1) ? .on : .off
                    self.muteItem.isEnabled = true
                } else {
                    self.muteItem.isEnabled = false
                }
                // The source can also be changed from the monitor's own buttons,
                // so trust the panel over our last write.
                if let src {
                    self.currentSource = src.cur
                    self.inputRow.setActive(src.cur)
                    for item in self.sourceItem.submenu?.items ?? [] {
                        item.state = (item.tag == src.cur) ? .on : .off
                    }
                    self.sourceItem.isEnabled = true
                } else {
                    // Writes still work (and revive a sleeping panel), so the
                    // button stays live; we just can't show which is active.
                    self.inputRow.setActive(nil)
                    self.sourceItem.isEnabled = true
                }
            }
        }
    }

    /// No confirmation: the monitor will not settle on an input with nothing
    /// plugged in -- it blanks, looks, finds no signal and returns to the live
    /// source by itself. Verified on this panel 2026-08-01.
    private func pickSource(_ code: Int) {
        // Tick it immediately: confirming by reading back needs the link we may
        // be about to drop, and the bus stays down for the ~30-60 s the panel
        // takes to renegotiate. refresh() reconciles once it returns.
        currentSource = code
        inputRow.setActive(code)
        for item in sourceItem.submenu?.items ?? [] { item.state = (item.tag == code) ? .on : .off }
        menu.cancelTracking()
        DDC.set(VCP.source, code)
    }

    @objc private func source(_ sender: NSMenuItem) { pickSource(sender.tag) }

    @objc private func toggleMute() {
        let muted = muteItem.state == .on
        muteItem.state = muted ? .off : .on
        DDC.set(VCP.mute, muted ? 2 : 1)
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)   // menu bar only, no Dock icon
app.run()
