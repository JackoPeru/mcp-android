import Foundation

/// File scope iOS: sandbox dell'app + bookmark security-scoped importati
/// con UIDocumentPicker (Documents, iCloud Drive). Niente accesso globale
/// tipo SAF/MANAGE_EXTERNAL_STORAGE: su iOS stock è vietato dal sandbox.
/// `app-documents` = Documents/ dell'app. Le root importate restano
/// valide tra riavvii via bookmark persistiti in UserDefaults (App Group).
public final class FileStore {
    public struct Root: Codable { public var rootId: String; public var name: String; public var url: URL? }

    private let fm = FileManager.default
    private let defaults: UserDefaults
    private let bookmarkKey = "mcpios.file.bookmarks.v1"

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public var appDocuments: URL {
        fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    // MARK: - Roots

    public func roots() -> [[String: Any]] {
        var out: [[String: Any]] = [["rootId": "app-documents", "name": "Documenti app"]]
        for (id, _) in bookmarks() { out.append(["rootId": id, "name": id]) }
        return out
    }

    // MARK: - Bookmarks

    func bookmarks() -> [(String, URL)] {
        guard let dict = defaults.dictionary(forKey: bookmarkKey) as? [String: Data] else { return [] }
        var out: [(String, URL)] = []
        for (id, data) in dict {
            var stale = false
            if let url = try? URL(resolvingBookmarkData: data, options: [.withoutUI],
                                  relativeTo: nil, bookmarkDataIsStale: &stale), !stale {
                out.append((id, url))
            }
        }
        return out
    }

    public func addBookmark(rootId: String, url: URL) throws {
        let data = try url.bookmarkData(options: [.withSecurityScope], includingResourceValuesForKeys: nil, relativeTo: nil)
        var dict = (defaults.dictionary(forKey: bookmarkKey) as? [String: Data]) ?? [:]
        dict[rootId] = data
        defaults.set(dict, forKey: bookmarkKey)
    }

    public func removeBookmark(rootId: String) {
        var dict = (defaults.dictionary(forKey: bookmarkKey) as? [String: Data]) ?? [:]
        dict.removeValue(forKey: rootId)
        defaults.set(dict, forKey: bookmarkKey)
    }

    // MARK: - Path safety (stessa severità del bridge: niente assoluti, .., backslash, :)

    public static func safeRelative(_ path: String) -> [String]? {
        if path.isEmpty { return [] }
        if path.hasPrefix("/") || path.contains("\\") || path.contains(":") || path.contains("\0") { return nil }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        for p in parts { if p.isEmpty || p == "." || p == ".." { return nil } }
        if path.utf8.count > 1024 { return nil }
        return parts
    }

    public func resolve(rootId: String, path: String) throws -> URL {
        guard let parts = Self.safeRelative(path) else { throw RpcError.invalidArgument("Invalid path") }
        let base: URL
        if rootId == "app-documents" { base = appDocuments }
        else if let found = bookmarks().first(where: { $0.0 == rootId })?.1 { base = found }
        else { throw RpcError.invalidArgument("Invalid root") }
        var url = base
        for p in parts { url.appendPathComponent(p) }
        return url
    }

    // MARK: - Ops (tutte bounded come Android: list 200, read 256 KiB, write 32 KiB)

    public func list(rootId: String, path: String, offset: Int, limit: Int) throws -> [[String: Any]] {
        let dir = try resolve(rootId: rootId, path: path)
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: dir.path, is: &isDir), isDir.boolValue else {
            throw RpcError.invalidArgument("Not a directory")
        }
        let scoped = startScoped(dir)
        defer { if scoped { dir.stopAccessingSecurityScopedResource() } }
        let names = (try? fm.contentsOfDirectory(atPath: dir.path).sorted()) ?? []
        return names.dropFirst(max(0, offset)).prefix(max(1, min(limit, 200))).map { name in
            var isD: ObjCBool = false
            _ = fm.fileExists(atPath: dir.appendingPathComponent(name).path, is: &isD)
            return ["name": name, "isDirectory": isD.boolValue]
        }
    }

    public func stat(rootId: String, path: String) throws -> [String: Any] {
        let url = try resolve(rootId: rootId, path: path)
        let scoped = startScoped(url)
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let attrs = try fm.attributesOfItem(atPath: url.path)
        return ["size": attrs[.size] as? Int ?? 0,
                "isDirectory": (attrs[.type] as? FileAttributeType) == .typeDirectory,
                "modified": (attrs[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0]
    }

    public func read(rootId: String, path: String, offset: Int, length: Int) throws -> [String: Any] {
        guard length >= 1, length <= 262_144, offset >= 0 else { throw RpcError.invalidArgument("Invalid range") }
        let url = try resolve(rootId: rootId, path: path)
        let scoped = startScoped(url)
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))
        let data = handle.readData(ofLength: length)
        return ["data": data.base64EncodedString(), "bytesRead": data.count, "eof": data.count < length]
    }

    public func write(rootId: String, path: String, dataB64: String, offset: Int, truncate: Bool) throws -> [String: Any] {
        guard let data = Data(base64Encoded: dataB64), data.count <= 32 * 1024 else {
            throw RpcError.invalidArgument("Write chunk exceeds 32 KiB")
        }
        let url = try resolve(rootId: rootId, path: path)
        let scoped = startScoped(url)
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if truncate || !fm.fileExists(atPath: url.path) { try data.write(to: url) }
        else {
            let handle = try FileHandle(forWritingTo: url)
            defer { try? handle.close() }
            try handle.seek(toOffset: UInt64(offset))
            handle.write(data)
        }
        return ["ok": true, "bytesWritten": data.count]
    }

    private func startScoped(_ url: URL) -> Bool {
        if url.path.hasPrefix(appDocuments.path) { return false }
        return url.startAccessingSecurityScopedResource()
    }
}

public enum RpcError: Error {
    case invalidArgument(String), unavailable(String), failed(String)
    public var code: String {
        switch self {
        case .invalidArgument: return "INVALID_ARGUMENT"
        case .unavailable: return "UNAVAILABLE"
        case .failed: return "REMOTE_ERROR"
        }
    }
    public var message: String {
        switch self { case .invalidArgument(let m), .unavailable(let m), .failed(let m): return m }
    }
}
