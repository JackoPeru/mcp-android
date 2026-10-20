import AVFoundation
import Foundation
import UIKit
import UniformTypeIdentifiers

/// Dispatcher RPC iOS. Solo metodi realizzabili su iOS stock.
/// Differenze intenzionali rispetto ad Android (documentate in docs/ios-parity.md):
///  - NIENTE controllo UI di altre app (niente AccessibilityService): solo ios_ui_tree
///    in-app + ios_shortcut_run (Shortcuts) come automazione nativa.
///  - NIENTE shell Termux/Shizuku, logcat, force-stop, global_action, tap/swipe
///    di sistema, unlock, notification listener cross-app.
///  - File limitati al sandbox + bookmark UIDocumentPicker (niente SAF globale).
public final class RpcDispatcher {
    public static let service = "ios-private-mcp"
    public static let version = "0.8.20"

    private let files: FileStore
    private let now: () -> Date
    private var snapshots: [Int: [String: Any]] = [:]
    private var nextSnapshot = 1
    private var events: [[String: Any]] = []
    private var nextEvent = 1

    public init(files: FileStore = FileStore(), now: @escaping () -> Date = Date.init) {
        self.files = files; self.now = now
    }

    // MARK: - Entry

    public func dispatch(method: String, params: [String: Any]) throws -> [String: Any] {
        switch method {
        case "status": return status()
        case "device_info": return deviceInfo()
        case "capabilities": return ["capabilities": capabilities(), "service": Self.service]
        case "diagnostics": return diagnostics(params)
        case "screenshot": return try screenshot()
        case "screen_context": return screenContext(params)
        case "clipboard_get": return clipboardGet()
        case "clipboard_set": try clipboardSet(params); return ["ok": true]
        case "open_uri": try openURI(params); return ["ok": true]
        case "share_text": queueShare(params); return ["ok": true, "queued": true]
        case "apps": return apps(params)
        case "launch_app": try launchApp(params); return ["ok": true]
        case "notifications": return ownNotifications()
        case "volume_get": return volumeGet()
        case "events", "events_wait": return eventsAfter(params)
        case "shortcut_run": try shortcutRun(params); return ["ok": true, "queued": true]
        case "ui_tree": return ownUITree()
        case "file_roots": return ["roots": files.roots()]
        case "file_list": return try fileList(params)
        case "file_stat": return try fileStat(params)
        case "file_read": return try fileRead(params)
        case "file_search": return try fileSearch(params)
        case "file_write": return try fileWrite(params)
        case "file_mkdir": return try fileMkdir(params)
        case "file_rename": return try fileRename(params)
        case "file_delete": return try fileDelete(params)
        case "updater_check": return Updater.checkLocal()
        default: throw RpcError.invalidArgument("Unknown RPC method")
        }
    }

    // MARK: - Status / device

    func status() -> [String: Any] {
        ["service": Self.service, "version": Self.version,
         "running": true, "transport": "lan+tailscale",
         "fileRoots": files.roots().count,
         "note": "iOS subset: no cross-app UI control, no shell, no notification listener"]
    }

    func deviceInfo() -> [String: Any] {
        let d = UIDevice.current
        return ["model": d.model, "name": "iPhone",
                "system": "\(d.systemName) \(d.systemVersion)",
                "batteryLevel": batteryLevel(),
                "orientation": UIDevice.current.orientation.rawValue]
    }

    func batteryLevel() -> Double {
        UIDevice.current.isBatteryMonitoringEnabled = true
        return Double(UIDevice.current.batteryLevel)
    }

    func capabilities() -> [String: Any] {
        ["ui_automation": "in-app-only",
         "shell": "unsupported-on-ios",
         "notifications": "own-app-only",
         "files": "sandbox-plus-bookmarks",
         "shortcuts": "supported",
         "screenshot": "foreground-in-app-only"]
    }

    func diagnostics(_ params: [String: Any]) -> [String: Any] {
        ["service": Self.service, "version": Self.version,
         "recentEvents": Array(events.suffix(20)), "snapshots": snapshots.count]
    }

    // MARK: - Screenshot (solo foreground in-app: UIWindow snapshot)

    func screenshot() throws -> [String: Any] {
        guard let png = MainActor.assumeIsolated({ inAppPNG() }, file: #file, line: #line) else {
            throw RpcError.unavailable("Screenshot requires foreground app")
        }
        return ["mimeType": "image/png", "data": png.base64EncodedString(),
                "width": 1170, "height": 2532]
    }

    @MainActor func inAppPNG() -> Data? {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).flatMap({ $0.windows }).first(where: { $0.isKeyWindow }),
              let img = window.snapshot() else { return nil }
        return img.pngData()
    }

    func screenContext(_ params: [String: Any]) -> [String: Any] {
        let id = nextSnapshot; nextSnapshot += 1
        let ctx: [String: Any] = ["snapshotId": id, "service": Self.service,
                                 "note": "iOS semantic context covers the MCP app itself, never other apps"]
        snapshots[id] = ctx
        if snapshots.count > 8 { snapshots.removeValue(forKey: snapshots.keys.min()!) }
        var out = ctx
        if (params["screenshot"] as? Bool) == true, let png = try? screenshotPNGData() {
            out["screenshot"] = ["mimeType": "image/png", "data": png.base64EncodedString()]
        }
        return out
    }

    func screenshotPNGData() throws -> Data {
        var data: Data?
        var err: Error?
        DispatchQueue.main.sync {
            guard let window = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene }).flatMap({ $0.windows }).first(where: { $0.isKeyWindow }),
                  let img = window.snapshot() else { err = RpcError.unavailable("No foreground window"); return }
            data = img.pngData()
        }
        if let e = err { throw e }
        guard let d = data else { throw RpcError.unavailable("Screenshot failed") }
        return d
    }

    // MARK: - Clipboard (iOS limita lettura in background dal 16+)

    func clipboardGet() -> [String: Any] {
        var text = ""
        DispatchQueue.main.sync { text = UIPasteboard.general.string ?? "" }
        return ["text": text]
    }

    func clipboardSet(_ params: [String: Any]) throws {
        guard let text = params["text"] as? String, text.count <= 4096 else {
            throw RpcError.invalidArgument("Invalid text")
        }
        DispatchQueue.main.sync { UIPasteboard.general.string = text }
        appendEvent(kind: "clipboard")
    }

    // MARK: - URI / share / apps / shortcuts

    func openURI(_ params: [String: Any]) throws {
        guard let raw = params["uri"] as? String, raw.count <= 2048,
              let url = URL(string: raw),
              let scheme = url.scheme?.lowercased(),
              ["https", "http", "geo", "tel", "sms", "smsto", "mailto", "shortcuts"].contains(scheme)
        else { throw RpcError.invalidArgument("Use an http(s), geo, tel, sms/smsto, mailto or shortcuts URI") }
        DispatchQueue.main.async { UIApplication.shared.open(url) }
    }

    func queueShare(_ params: [String: Any]) {
        appendEvent(kind: "share")
    }

    func apps(_ params: [String: Any]) -> [String: Any] {
        // Su iOS non esiste query di tutte le app: si espongono solo gli
        // scheme dichiarati + le Scorciatoie note. Mai un elenco sistema.
        let q = (params["query"] as? String ?? "").lowercased()
        let known = [["label": "Shortcuts", "scheme": "shortcuts://"],
                     ["label": "Safari", "scheme": "https://"],
                     ["label": "Mail", "scheme": "mailto:"],
                     ["label": "Maps", "scheme": "geo:"]]
        let list = q.isEmpty ? known : known.filter { ($0["label"] ?? "").lowercased().contains(q) }
        return ["apps": list]
    }

    func launchApp(_ params: [String: Any]) throws {
        // Solo URL scheme espliciti: niente apertura per package come Android.
        guard let scheme = params["urlScheme"] as? String,
              let url = URL(string: scheme),
              ["https", "http", "shortcuts"].contains(url.scheme?.lowercased() ?? "")
        else { throw RpcError.invalidArgument("Provide an explicit urlScheme (https or shortcuts)") }
        DispatchQueue.main.async { UIApplication.shared.open(url) }
    }

    func shortcutRun(_ params: [String: Any]) throws {
        guard let name = params["name"] as? String, !name.isEmpty, name.count <= 200 else {
            throw RpcError.invalidArgument("Provide a Shortcut name")
        }
        // Scorciatoie via URL: richiede che esista una scorciatoia con quel nome.
        let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? name
        guard let url = URL(string: "shortcuts://run-shortcut?name=\(encoded)") else {
            throw RpcError.invalidArgument("Invalid shortcut name")
        }
        DispatchQueue.main.async { UIApplication.shared.open(url) }
        appendEvent(kind: "shortcut")
    }

    func ownNotifications() -> [String: Any] {
        // Solo notifica: niente lettura notifiche di altre app (vietato su iOS).
        ["notifications": [], "note": "iOS exposes only own-app notifications; cross-app listener is unavailable"]
    }

    func volumeGet() -> [String: Any] {
        let session = AVAudioSession.sharedInstance()
        return ["outputVolume": session.outputVolume, "note": "iOS has no settable system volume API for third-party apps"]
    }

    func ownUITree() -> [String: Any] {
        ["note": "iOS UI tree covers the MCP app itself, never other apps",
         "nodes": [["label": "MCP iOS root", "role": "app"]]]
    }

    // MARK: - Events (journal in-memory: file/clipboard/shortcut/lifecycle)

    func appendEvent(kind: String) {
        let id = nextEvent; nextEvent += 1
        events.append(["id": id, "kind": kind, "at": now().timeIntervalSince1970])
        if events.count > 128 { events.removeFirst(events.count - 128) }
    }

    func eventsAfter(_ params: [String: Any]) -> [String: Any] {
        let after = params["afterId"] as? Int ?? 0
        let limit = min(max(params["limit"] as? Int ?? 100, 1), 200)
        let list = events.filter { ($0["id"] as? Int ?? 0) > after }.prefix(limit)
        return ["events": Array(list), "latestId": nextEvent - 1]
    }

    // MARK: - Files passthrough

    func fileList(_ p: [String: Any]) throws -> [String: Any] {
        guard let rootId = p["rootId"] as? String else { throw RpcError.invalidArgument("Invalid root") }
        let path = p["path"] as? String ?? ""
        let offset = p["offset"] as? Int ?? 0, limit = p["limit"] as? Int ?? 100
        return ["entries": try files.list(rootId: rootId, path: path, offset: offset, limit: limit)]
    }
    func fileStat(_ p: [String: Any]) throws -> [String: Any] {
        guard let rootId = p["rootId"] as? String else { throw RpcError.invalidArgument("Invalid root") }
        return try files.stat(rootId: rootId, path: p["path"] as? String ?? "")
    }
    func fileRead(_ p: [String: Any]) throws -> [String: Any] {
        guard let rootId = p["rootId"] as? String else { throw RpcError.invalidArgument("Invalid root") }
        return try files.read(rootId: rootId, path: p["path"] as? String ?? "",
                              offset: p["offset"] as? Int ?? 0, length: p["length"] as? Int ?? 65536)
    }
    func fileSearch(_ p: [String: Any]) throws -> [String: Any] {
        guard let rootId = p["rootId"] as? String else { throw RpcError.invalidArgument("Invalid root") }
        let base = try files.resolve(rootId: rootId, path: p["path"] as? String ?? "")
        let query = (p["query"] as? String ?? "").lowercased()
        let maxDepth = min(max(p["maxDepth"] as? Int ?? 8, 0), 16)
        let limit = min(max(p["limit"] as? Int ?? 100, 1), 500)
        var out: [String] = []
        let fm = FileManager.default
        func walk(_ url: URL, depth: Int) {
            guard depth <= maxDepth, out.count < limit,
                  let names = try? fm.contentsOfDirectory(atPath: url.path) else { return }
            for n in names {
                if out.count >= limit { return }
                if qMatch(n, query) { out.append(url.appendingPathComponent(n).path) }
                var isD: ObjCBool = false
                _ = fm.fileExists(atPath: url.appendingPathComponent(n).path, isDirectory: &isD)
                if isD.boolValue { walk(url.appendingPathComponent(n), depth: depth + 1) }
            }
        }
        func qMatch(_ n: String, _ q: String) -> Bool { q.isEmpty || n.lowercased().contains(q) }
        walk(base, depth: 0)
        return ["matches": out]
    }
    func fileWrite(_ p: [String: Any]) throws -> [String: Any] {
        guard let rootId = p["rootId"] as? String, let path = p["path"] as? String,
              let data = p["data"] as? String else { throw RpcError.invalidArgument("Invalid write") }
        return try files.write(rootId: rootId, path: path, dataB64: data,
                               offset: p["offset"] as? Int ?? 0, truncate: p["truncate"] as? Bool ?? true)
    }
    func fileMkdir(_ p: [String: Any]) throws -> [String: Any] {
        guard let rootId = p["rootId"] as? String, let path = p["path"] as? String else {
            throw RpcError.invalidArgument("Invalid mkdir")
        }
        let url = try files.resolve(rootId: rootId, path: path)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return ["ok": true]
    }
    func fileRename(_ p: [String: Any]) throws -> [String: Any] {
        guard let rootId = p["rootId"] as? String, let path = p["path"] as? String,
              let name = p["newName"] as? String, !name.contains("/"), name != ".", name != ".."
        else { throw RpcError.invalidArgument("Invalid rename") }
        let src = try files.resolve(rootId: rootId, path: path)
        let dst = src.deletingLastPathComponent().appendingPathComponent(name)
        try FileManager.default.moveItem(at: src, to: dst)
        return ["ok": true]
    }
    func fileDelete(_ p: [String: Any]) throws -> [String: Any] {
        guard let rootId = p["rootId"] as? String, let path = p["path"] as? String, !path.isEmpty else {
            throw RpcError.invalidArgument("Cannot delete a root")
        }
        try FileManager.default.removeItem(at: files.resolve(rootId: rootId, path: path))
        return ["ok": true]
    }
}

extension UIView {
    func snapshot() -> UIImage? {
        UIGraphicsBeginImageContextWithOptions(bounds.size, false, 0)
        defer { UIGraphicsEndImageContext() }
        guard let ctx = UIGraphicsGetCurrentContext() else { return nil }
        layer.render(in: ctx)
        return UIGraphicsGetImageFromCurrentImageContext()
    }
}
