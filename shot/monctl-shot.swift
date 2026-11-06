// monctl-shot: capture one display to an image file, nothing else.
//
// It exists so macOS's Screen Recording permission can be granted to this
// one bundle (~/Applications/MonCtlShot.app) instead of to the Python
// interpreter that runs webui/server.py. It is started with `open -n -a`
// so that launchd makes it its own responsible process.
//
//   monctl-shot <displayID> <out path> <max width px, 0 = native> <png|jpg>
//   monctl-shot --check        exit 0 if Screen Recording is allowed
//
// Output: <out> on success, <out>.err (one line) on failure.
// Exit: 0 ok, 2 bad args, 3 no permission, 4 capture/encode failed.
import CoreGraphics
import Foundation
import ImageIO
import ScreenCaptureKit
import UniformTypeIdentifiers

let a = CommandLine.arguments
if a.count == 2 && a[1] == "--check" { exit(CGPreflightScreenCaptureAccess() ? 0 : 3) }
guard a.count == 5, let id = UInt32(a[1]), let maxW = Int(a[3]), a[4] == "png" || a[4] == "jpg" else {
    FileHandle.standardError.write(Data("usage: monctl-shot <displayID> <out> <maxW> <png|jpg>\n".utf8))
    exit(2)
}
// Started through `open`, so there is no exit status or stderr to read:
// failures are reported by writing <out>.err, and success by <out> existing.
func fail(_ code: Int32, _ why: String) -> Never {
    FileHandle.standardError.write(Data((why + "\n").utf8))
    try? why.write(toFile: a[2] + ".err", atomically: true, encoding: .utf8)
    exit(code)
}
if !CGPreflightScreenCaptureAccess() {
    _ = CGRequestScreenCaptureAccess()
    fail(3, "permission")
}
// ScreenCaptureKit is async; this is a one-shot tool, so block on a semaphore.
var captured: CGImage?
let done = DispatchSemaphore(value: 0)
Task {
    defer { done.signal() }
    let content: SCShareableContent
    do { content = try await SCShareableContent.current }
    catch { FileHandle.standardError.write(Data("shareable content: \(error)\n".utf8)); return }
    guard let disp = content.displays.first(where: { $0.displayID == id }) else {
        FileHandle.standardError.write(Data("no display \(id) in \(content.displays.map { $0.displayID })\n".utf8))
        return
    }
    let cfg = SCStreamConfiguration()
    // Native pixel size (Retina backing store), or scaled down to maxW.
    let scale = Int(CGDisplayPixelsWide(id)) > 0 ? Double(CGDisplayPixelsWide(id)) / Double(disp.width) : 1
    var w = Int(Double(disp.width) * scale), h = Int(Double(disp.height) * scale)
    if maxW > 0 && w > maxW { h = h * maxW / w; w = maxW }
    cfg.width = w
    cfg.height = h
    cfg.showsCursor = true
    do {
        captured = try await SCScreenshotManager.captureImage(
            contentFilter: SCContentFilter(display: disp, excludingWindows: []), configuration: cfg)
    } catch { FileHandle.standardError.write(Data("capture: \(error)\n".utf8)) }
}
if done.wait(timeout: .now() + 12) == .timedOut { fail(4, "capture timed out") }
guard let img = captured else { fail(4, "no such display or capture failed") }

let type = (a[4] == "png" ? UTType.png : UTType.jpeg).identifier as CFString
guard let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: a[2] + ".part") as CFURL, type, 1, nil) else { fail(4, "cannot write output") }
CGImageDestinationAddImage(dest, img, [kCGImageDestinationLossyCompressionQuality: 0.6] as CFDictionary)
if !CGImageDestinationFinalize(dest) { fail(4, "encode failed") }
// Rename last, so a poller seeing <out> never sees half an image.
do { try FileManager.default.moveItem(atPath: a[2] + ".part", toPath: a[2]) } catch { fail(4, "cannot write output") }
exit(0)
