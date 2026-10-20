import Foundation

#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

/// Server HTTP/1.1 minimale per MCP iOS: due listener indipendenti come Android
/// (LAN su IPv4 RFC1918 numerico + Tailscale su 100.64/10), mai 0.0.0.0.
/// - LAN: POST /hello (HMAC) + POST /rpc con envelope AES-256-GCM.
/// - Tailscale: POST /rpc con bearer `Authorization: Bearer <token>` (tunnel WireGuard).
/// Deadline 25 s, I/O 8 s, risposte bounded. Da avviare solo in foreground;
/// iOS può sospendere l'app in background (documentato in README iOS).
public final class HttpServer {
    public static let port: UInt16 = 8765
    public static let requestDeadline: TimeInterval = 25
    public static let ioTimeout: TimeInterval = 8

    private let dispatcher: RpcDispatcher
    private let token: () -> String?
    private var sockets: [Int32] = []
    private var running = false
    private var session: String?
    private let lock = NSLock()

    public init(dispatcher: RpcDispatcher, token: @escaping () -> String?) {
        self.dispatcher = dispatcher; self.token = token
    }

    public func start(lanAddress: String?, tailscaleAddress: String?) throws {
        stop()
        var addrs: [String] = []
        if let lan = lanAddress, Self.isRfc1918(lan) { addrs.append(lan) }
        if let ts = tailscaleAddress, Self.isTailscale(ts) { addrs.append(ts) }
        guard !addrs.isEmpty else { throw RpcError.unavailable("No bindable IPv4 address") }
        lock.lock(); session = LanChannel.newSessionId(); lock.unlock()
        for addr in addrs {
            let fd = try bind(address: addr, port: Self.port)
            sockets.append(fd)
            Thread.detachNewThread { [weak self] in self?.acceptLoop(fd: fd, secureLan: Self.isRfc1918(addr)) }
        }
        running = true
    }

    public func stop() {
        running = false
        for fd in sockets { close(fd) }
        sockets.removeAll()
    }

    public func rotateSession() { lock.lock(); session = LanChannel.newSessionId(); lock.unlock() }
    func currentSession() -> String { lock.lock(); defer { lock.unlock() }; return session ?? LanChannel.newSessionId() }

    // MARK: - Socket

    private func bind(address: String, port: UInt16) throws -> Int32 {
        let fd = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        guard fd >= 0 else { throw RpcError.failed("socket failed") }
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = address.withCString { inet_addr($0) }
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Foundation.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard r == 0, listen(fd, 32) == 0 else { close(fd); throw RpcError.failed("bind \(address) failed") }
        return fd
    }

    private func acceptLoop(fd: Int32, secureLan: Bool) {
        while running {
            var addr = sockaddr(); var len = socklen_t(MemoryLayout<sockaddr>.size)
            let client = accept(fd, &addr, &len)
            if client < 0 { break }
            Thread.detachNewThread { [weak self] in self?.handle(client: client, secureLan: secureLan) }
        }
    }

    private func handle(client: Int32, secureLan: Bool) {
        defer { close(client) }
        var timeout = timeval(tv_sec: Int(Self.ioTimeout), tv_usec: 0)
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        guard let req = readRequest(fd: client) else { writeRaw(fd: client, status: 400, body: #"{"error":{"code":"INVALID_ARGUMENT"}}"#); return }
        let started = Date()
        let response: (Int, String)
        if req.path == "/hello", secureLan {
            response = handleHello(req)
        } else if req.path == "/rpc", req.method == "POST" {
            if Date().timeIntervalSince(started) > Self.requestDeadline {
                response = (504, #"{"error":{"code":"TIMEOUT"}}"#)
            } else if secureLan {
                response = handleLanRpc(req)
            } else {
                response = handleBearerRpc(req)
            }
        } else {
            response = (404, #"{"error":{"code":"INVALID_ARGUMENT"}}"#)
        }
        writeRaw(fd: client, status: response.0, body: response.1)
    }

    struct Request { var method: String; var path: String; var headers: [String: String]; var body: Data }

    private func readRequest(fd: Int32) -> Request? {
        var headerData = Data()
        var buf = [UInt8](repeating: 0, count: 4096)
        while headerData.count < 16 * 1024 {
            let n = recv(fd, &buf, buf.count, 0)
            if n <= 0 { return nil }
            headerData.append(contentsOf: buf.prefix(n))
            if let s = String(data: headerData, encoding: .utf8), s.contains("\r\n\r\n") { break }
        }
        guard let headerStr = String(data: headerData, encoding: .utf8),
              let sep = headerStr.range(of: "\r\n\r\n") else { return nil }
        let head = String(headerStr[..<sep.lowerBound])
        var body = Data(headerData.suffix(from: headerData.index(headerData.startIndex, offsetBy: head.utf8.count + 4)))
        let lines = head.components(separatedBy: "\r\n")
        guard let top = lines.first else { return nil }
        let parts = top.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        var headers: [String: String] = [:]
        for line in lines.dropFirst().prefix(32) {
            let kv = line.split(separator: ":", maxSplits: 1).map(String.init)
            if kv.count == 2 { headers[kv[0].trimmingCharacters(in: .whitespaces).lowercased()] = kv[1].trimmingCharacters(in: .whitespaces) }
        }
        let length = min(max(Int(headers["content-length"] ?? "0") ?? 0, 0), 12 * 1024 * 1024 + 4096)
        while body.count < length {
            let n = recv(fd, &buf, min(buf.count, length - body.count), 0)
            if n <= 0 { break }
            body.append(contentsOf: buf.prefix(n))
        }
        return Request(method: String(parts[0]), path: String(parts[1]).split(separator: "?")[0].description,
                       headers: headers, body: body.prefix(length))
    }

    // MARK: - Handlers

    private func handleHello(_ req: Request) -> (Int, String) {
        guard let token = token(),
              let obj = try? JSONSerialization.jsonObject(with: req.body) as? [String: Any],
              let nonce = obj["nonce"] as? String, LanChannel.isValidHelloNonce(nonce)
        else { return (401, #"{"error":{"code":"INVALID_ARGUMENT"}}"#) }
        let session = currentSession()
        guard let payload = try? LanChannel.helloResponse(token: token, nonce: nonce, session: session),
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let str = String(data: data, encoding: .utf8)
        else { return (500, #"{"error":{"code":"REMOTE_ERROR"}}"#) }
        return (200, str)
    }

    private func handleLanRpc(_ req: Request) -> (Int, String) {
        guard let token = token(),
              req.headers["content-type"] == LanChannel.mediaType,
              let env = try? JSONSerialization.jsonObject(with: req.body) as? [String: Any]
        else { rotateSession(); return (401, #"{"error":{"code":"INVALID_ARGUMENT"}}"#) }
        let session = currentSession()
        guard let plain = try? LanChannel.decrypt(token: token, session: session, direction: "request", envelope: env),
              let method = plain["method"] as? String
        else { rotateSession(); return (401, #"{"error":{"code":"INVALID_ARGUMENT"}}"#) }
        let params = plain["params"] as? [String: Any] ?? [:]
        let requestNonce = env["nonce"] as? String ?? ""
        do {
            let result = try dispatcher.dispatch(method: method, params: params)
            let payload: [String: Any] = ["result": result, "requestNonce": requestNonce]
            let enc = try LanChannel.encrypt(token: token, session: session, direction: "response", payload: payload)
            let str = String(data: try JSONSerialization.data(withJSONObject: enc), encoding: .utf8) ?? "{}"
            return (200, str)
        } catch let e as RpcError {
            return lanError(token: token, session: session, requestNonce: requestNonce, code: e.code)
        } catch {
            return lanError(token: token, session: session, requestNonce: requestNonce, code: "REMOTE_ERROR")
        }
    }

    private func lanError(token: String, session: String, requestNonce: String, code: String) -> (Int, String) {
        let payload: [String: Any] = ["error": ["code": code], "requestNonce": requestNonce]
        if let enc = try? LanChannel.encrypt(token: token, session: session, direction: "response", payload: payload),
           let str = String(data: (try? JSONSerialization.data(withJSONObject: enc)) ?? Data(), encoding: .utf8) {
            return (200, str)
        }
        return (500, "{}")
    }

    private func handleBearerRpc(_ req: Request) -> (Int, String) {
        guard let token = token(),
              req.headers["authorization"] == "Bearer \(token)",
              let obj = try? JSONSerialization.jsonObject(with: req.body) as? [String: Any],
              let method = obj["method"] as? String
        else { return (401, #"{"error":{"code":"INVALID_ARGUMENT"}}"#) }
        let params = obj["params"] as? [String: Any] ?? [:]
        do {
            let result = try dispatcher.dispatch(method: method, params: params)
            let str = String(data: try JSONSerialization.data(withJSONObject: ["result": result]), encoding: .utf8) ?? "{}"
            return (200, str)
        } catch let e as RpcError {
            return (200, #"{"error":{"code":"\#(e.code)"}}"#)
        } catch {
            return (200, #"{"error":{"code":"REMOTE_ERROR"}}"#)
        }
    }

    private func writeRaw(fd: Int32, status: Int, body: String) {
        let data = Data(body.utf8)
        let head = "HTTP/1.1 \(status) OK\r\nContent-Type: application/json\r\nContent-Length: \(data.count)\r\nConnection: close\r\n\r\n"
        _ = Data(head.utf8).withUnsafeBytes { send(fd, $0.baseAddress!, $0.count, 0) }
        _ = data.withUnsafeBytes { send(fd, $0.baseAddress!, $0.count, 0) }
    }

    // MARK: - Address policy (stesse classi di Android)

    public static func isRfc1918(_ ip: String) -> Bool {
        let p = ip.split(separator: ".").compactMap { UInt32($0) }
        guard p.count == 4, p.allSatisfy({ $0 <= 255 }) else { return false }
        let v = (p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]
        return (v & 0xFF00_0000) == 0x0A00_0000 || (v & 0xFFF0_0000) == 0xAC10_0000 || (v & 0xFFFF_0000) == 0xC0A8_0000
    }

    public static func isTailscale(_ ip: String) -> Bool {
        let p = ip.split(separator: ".").compactMap { UInt32($0) }
        guard p.count == 4, p.allSatisfy({ $0 <= 255 }) else { return false }
        return (((p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]) & 0xFFC0_0000) == 0x6440_0000
    }
}
