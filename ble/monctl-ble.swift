// monctl-ble — Bluetooth LE route to webui/server.py, for when the phone can't
// reach the Mac over the LAN or the tunnel (the Mac off Wi-Fi, no internet).
//
// The Mac is the BLE peripheral. The phone writes an encrypted request, this
// forwards it to server.py on loopback, and notifies the encrypted reply back.
// All hardware logic stays in server.py; this is only a transport.
//
// Security does not rely on Bluetooth pairing (unreliable with the Mac as the
// peripheral). Every message is AES-GCM sealed with a key derived from the
// monctl token, so without the token nothing can be read, forged or altered.
// Replays are stopped by a per-connection random session nonce plus a counter
// that must increase, both bound into the GCM additional data. Only an
// allow-list of routes is forwarded: no screen frames, no APK.
//
// Wire format (all integers big-endian):
//   NONCE  read    16 random bytes; reading it starts a new session
//   REQ    write   chunks: [flags][bytes...], flags bit0 = last chunk
//   RESP   notify  chunks, same framing
//   message      = counter(4) || nonce(12) || ciphertext || tag(16)
//   request  AAD = session || "Q" || counter;  plaintext = {"m":"GET","p":"/api/remote","b":{...}}
//   response AAD = session || "R" || counter;  plaintext = status(2) || server body
//   auth failure = counter 0xFFFFFFFF, plaintext "auth" (not sealed: the
//                  phone's key is the thing that's wrong)
//   key          = HKDF-SHA256(token, salt "monctl-ble-v1", info "aes-gcm", 32 bytes)
//
// Build: see build.sh (an app bundle, so macOS can grant it Bluetooth access).

import CoreBluetooth
import CryptoKit
import Foundation

let SERVICE = CBUUID(string: "6D6F6E63-746C-4000-8000-000000000001")
let NONCE_C = CBUUID(string: "6D6F6E63-746C-4000-8000-000000000002")
let REQ_C = CBUUID(string: "6D6F6E63-746C-4000-8000-000000000003")
let RESP_C = CBUUID(string: "6D6F6E63-746C-4000-8000-000000000004")

let env = ProcessInfo.processInfo.environment
let SERVER = env["MONCTL_URL"] ?? "http://127.0.0.1:8770"
let TOKEN_FILE = (env["HOME"] ?? NSHomeDirectory()) + "/.config/monctl/token"
let MAX_MESSAGE = 64 * 1024
let AUTH_FAIL: UInt32 = 0xFFFF_FFFF

/// Small requests only. Screen frames are far too big for BLE, and the APK is
/// never sent this way.
let ALLOWED: Set<String> = [
    "GET /api/hello", "GET /api/remote", "GET /api/mac", "GET /api/display",
    "GET /api/watcher", "GET /api/watcher/config", "GET /api/app/version",
    "POST /api/set", "POST /api/nudge", "POST /api/display", "POST /api/watcher",
    "POST /api/watcher/config", "POST /api/watcher/test",
    "POST /api/mac/volume", "POST /api/mac/brightness",
    "POST /api/wifi/on", "POST /api/wifi/scan", "POST /api/wifi/join",
    "POST /api/airplay/scan", "POST /api/airplay/connect", "POST /api/airplay/disconnect",
]

func log(_ s: String) {
    let t = ISO8601DateFormatter().string(from: Date())
    FileHandle.standardError.write("\(t) \(s)\n".data(using: .utf8)!)
}

func be32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.bigEndian) { Data($0) } }

func readBE32(_ d: Data) -> UInt32 { d.prefix(4).reduce(0) { ($0 << 8) | UInt32($1) } }

func loadKey() -> SymmetricKey? {
    guard let raw = try? String(contentsOfFile: TOKEN_FILE, encoding: .utf8) else { return nil }
    let token = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if token.isEmpty { return nil }
    return HKDF<SHA256>.deriveKey(
        inputKeyMaterial: SymmetricKey(data: Data(token.utf8)),
        salt: Data("monctl-ble-v1".utf8),
        info: Data("aes-gcm".utf8),
        outputByteCount: 32)
}

final class Session {
    let nonce: Data
    let key: SymmetricKey
    var last: UInt32 = 0
    var inbox = Data()
    var busy = false

    init(key: SymmetricKey) {
        var b = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        nonce = Data(b)
        self.key = key
    }

    func aad(_ dir: String, _ counter: UInt32) -> Data { nonce + Data(dir.utf8) + be32(counter) }
}

final class Bridge: NSObject, CBPeripheralManagerDelegate {
    var pm: CBPeripheralManager!
    let resp = CBMutableCharacteristic(type: RESP_C, properties: [.notify], value: nil, permissions: [])
    var sessions: [UUID: Session] = [:]
    var outbox: [(CBCentral, Data)] = []
    let http: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 75     // Wi-Fi joins take up to 45 s on the server
        return URLSession(configuration: c)
    }()

    override init() {
        super.init()
        pm = CBPeripheralManager(delegate: self, queue: nil)
    }

    // MARK: lifecycle

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        switch p.state {
        case .poweredOn:
            let svc = CBMutableService(type: SERVICE, primary: true)
            svc.characteristics = [
                CBMutableCharacteristic(type: NONCE_C, properties: [.read], value: nil, permissions: [.readable]),
                CBMutableCharacteristic(type: REQ_C, properties: [.write], value: nil, permissions: [.writeable]),
                resp,
            ]
            p.removeAllServices()
            p.add(svc)
        case .unauthorized:
            log("Bluetooth access denied: System Settings > Privacy & Security > Bluetooth > MonCtl BLE")
        case .poweredOff:
            log("Bluetooth is off")
            sessions.removeAll()
        default:
            log("Bluetooth state \(p.state.rawValue)")
        }
    }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error { return log("add service failed: \(error)") }
        p.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [SERVICE],
                            CBAdvertisementDataLocalNameKey: "MonCtl"])
    }

    func peripheralManagerDidStartAdvertising(_ p: CBPeripheralManager, error: Error?) {
        log(error.map { "advertising failed: \($0)" } ?? "advertising")
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom c: CBCharacteristic) {
        sessions[central.identifier] = nil
        outbox.removeAll { $0.0.identifier == central.identifier }
    }

    // MARK: requests

    func peripheralManager(_ p: CBPeripheralManager, didReceiveRead req: CBATTRequest) {
        guard req.characteristic.uuid == NONCE_C else { return p.respond(to: req, withResult: .attributeNotFound) }
        // A long read arrives as several requests with increasing offsets;
        // only the first one starts a session.
        if req.offset == 0 {
            guard let key = loadKey() else {
                log("no token in \(TOKEN_FILE)")
                return p.respond(to: req, withResult: .unlikelyError)
            }
            sessions[req.central.identifier] = Session(key: key)
        }
        guard let s = sessions[req.central.identifier], req.offset <= s.nonce.count else {
            return p.respond(to: req, withResult: .invalidOffset)
        }
        req.value = s.nonce.subdata(in: req.offset..<s.nonce.count)
        p.respond(to: req, withResult: .success)
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite reqs: [CBATTRequest]) {
        guard let first = reqs.first else { return }
        for r in reqs {
            guard r.characteristic.uuid == REQ_C, let s = sessions[r.central.identifier],
                  let v = r.value, !v.isEmpty else {
                return p.respond(to: first, withResult: .writeNotPermitted)
            }
            if s.busy { return p.respond(to: first, withResult: .unlikelyError) }
            s.inbox.append(v.dropFirst())
            if s.inbox.count > MAX_MESSAGE {
                s.inbox.removeAll()
                return p.respond(to: first, withResult: .invalidAttributeValueLength)
            }
            if v[v.startIndex] & 1 == 1 {
                let msg = s.inbox
                s.inbox = Data()
                handle(msg, s, r.central)
            }
        }
        p.respond(to: first, withResult: .success)
    }

    func handle(_ msg: Data, _ s: Session, _ central: CBCentral) {
        guard msg.count > 4 + 12 + 16 else { return deny(central, "short frame") }
        let counter = readBE32(msg)
        guard counter > s.last, counter != AUTH_FAIL else { return deny(central, "replayed counter \(counter)") }
        guard let box = try? AES.GCM.SealedBox(combined: msg.dropFirst(4)),
              let plain = try? AES.GCM.open(box, using: s.key, authenticating: s.aad("Q", counter)) else {
            return deny(central, "bad seal (wrong token?)")
        }
        s.last = counter
        guard let req = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let method = req["m"] as? String, let path = req["p"] as? String else {
            return reply(central, s, counter, 400, Data(#"{"error":"bad request"}"#.utf8))
        }
        guard ALLOWED.contains("\(method) \(path)") else {
            return reply(central, s, counter, 403, Data(#"{"error":"not available over Bluetooth"}"#.utf8))
        }
        var r = URLRequest(url: URL(string: SERVER + path)!)
        r.httpMethod = method
        if method == "POST" {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = (try? JSONSerialization.data(withJSONObject: req["b"] ?? [String: Any]())) ?? Data("{}".utf8)
        }
        s.busy = true
        log("\(method) \(path)")
        http.dataTask(with: r) { data, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode
                ?? 502
            let body = data ?? Data(#"{"error":"monctl server not answering on the Mac"}"#.utf8)
            if let error { log("forward failed: \(error.localizedDescription)") }
            DispatchQueue.main.async {
                s.busy = false
                self.reply(central, s, counter, status, body)
            }
        }.resume()
    }

    func deny(_ central: CBCentral, _ why: String) {
        log("rejected: \(why)")
        send(central, be32(AUTH_FAIL) + Data("auth".utf8))
    }

    func reply(_ central: CBCentral, _ s: Session, _ counter: UInt32, _ status: Int, _ body: Data) {
        guard sessions[central.identifier] === s else { return }   // phone went away meanwhile
        let plain = Data([UInt8(status >> 8 & 0xFF), UInt8(status & 0xFF)]) + body
        guard let sealed = try? AES.GCM.seal(plain, using: s.key, authenticating: s.aad("R", counter)).combined else {
            return log("seal failed")
        }
        send(central, be32(counter) + sealed)
    }

    // MARK: notify, chunked to the central's MTU

    func send(_ central: CBCentral, _ msg: Data) {
        let size = max(20, central.maximumUpdateValueLength) - 1
        var i = msg.startIndex
        repeat {
            let end = min(i + size, msg.endIndex)
            let flags: UInt8 = end == msg.endIndex ? 1 : 0
            outbox.append((central, Data([flags]) + msg[i..<end]))
            i = end
        } while i < msg.endIndex
        flush()
    }

    func flush() {
        while let (central, chunk) = outbox.first {
            // false = the transmit queue is full; peripheralManagerIsReady resumes.
            guard pm.updateValue(chunk, for: resp, onSubscribedCentrals: [central]) else { return }
            outbox.removeFirst()
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers p: CBPeripheralManager) { flush() }
}

let bridge = Bridge()
log("monctl-ble starting, forwarding to \(SERVER)")
withExtendedLifetime(bridge) { RunLoop.main.run() }
