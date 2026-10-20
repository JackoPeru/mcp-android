import CryptoKit
import Foundation

/// Canale LAN autenticato/cifrato per MCP iOS.
/// Parità wire con bridge/ios-channel.js:
///  - HMAC-SHA256 per discovery (/hello proof) — il token non viaggia mai sulla LAN
///  - AES-256-GCM con chiavi derivate distinte per request/response
///  - AAD = "<domain>\n<direction>\n<session>"
///  - chiave = HMAC-SHA256(token, "<domain>\n<session>\n<direction>")
public enum LanChannel {
    public static let mediaType = "application/mcp-ios-lan+json"
    public static let proto = "mcp-ios-lan-channel"
    public static let version = 1
    public static let domain = "mcp-ios-lan-channel-v1"

    static let tokenRe = try! NSRegularExpression(pattern: "^[a-f0-9]{64}$")
    static let sessionRe = try! NSRegularExpression(pattern: "^[a-f0-9]{32}$")
    static let nonceRe = try! NSRegularExpression(pattern: "^[a-f0-9]{24}$")

    public static func isValidToken(_ s: String) -> Bool { match(tokenRe, s.lowercased()) }
    public static func isValidSession(_ s: String) -> Bool { match(sessionRe, s.lowercased()) }
    public static func isValidNonce(_ s: String) -> Bool { match(nonceRe, s.lowercased()) }

    private static func match(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    static func tokenBytes(_ token: String) throws -> Data {
        let t = token.lowercased()
        guard isValidToken(t), let d = Data(hexString: t) else { throw LanError.invalidToken }
        return d
    }

    public static func deriveKey(token: String, session: String, direction: String) throws -> SymmetricKey {
        guard direction == "request" || direction == "response" else { throw LanError.invalidDirection }
        guard isValidSession(session.lowercased()) else { throw LanError.invalidSession }
        let keyData = try tokenBytes(token)
        let material = "\(domain)\n\(session.lowercased())\n\(direction)"
        let mac = HMAC<SHA256>.authenticationCode(for: Data(material.utf8), using: SymmetricKey(data: keyData))
        return SymmetricKey(data: Data(mac))
    }

    static func aad(direction: String, session: String) throws -> Data {
        guard direction == "request" || direction == "response" else { throw LanError.invalidDirection }
        return Data("\(domain)\n\(direction)\n\(session.lowercased())".utf8)
    }

    public static func newSessionId() -> String { randomHex(bytes: 16) }
    public static func newNonce() -> String { randomHex(bytes: 12) }
    public static func newHelloNonce() -> String { randomHex(bytes: 16) }

    static func randomHex(bytes: Int) -> String {
        var b = [UInt8](repeating: 0, count: bytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes, &b)
        return b.map { String(format: "%02x", $0) }.joined()
    }

    public static func helloProof(token: String, nonce: String, session: String) throws -> String {
        guard isValidHelloNonce(nonce) else { throw LanError.invalidNonce }
        let keyData = try tokenBytes(token)
        guard isValidSession(session.lowercased()) else { throw LanError.invalidSession }
        let material = "\(domain)\nhello\n\(nonce)\n\(session.lowercased())"
        let mac = HMAC<SHA256>.authenticationCode(for: Data(material.utf8), using: SymmetricKey(data: keyData))
        return Data(mac).hexString
    }

    public static func isValidHelloNonce(_ s: String) -> Bool {
        guard s.count >= 1, s.count <= 64 else { return false }
        return s.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
    }

    public static func helloResponse(token: String, nonce: String, session: String) throws -> [String: Any] {
        let proof = try helloProof(token: token, nonce: nonce, session: session)
        return ["protocol": proto, "version": version, "nonce": nonce, "session": session.lowercased(), "proof": proof]
    }

    public static func verifyHello(token: String, nonce: String, payload: [String: Any]) -> String? {
        guard payload.count == 5,
              (payload["protocol"] as? String) == proto,
              (payload["version"] as? Int) == version,
              (payload["nonce"] as? String) == nonce,
              let session = (payload["session"] as? String)?.lowercased(), isValidSession(session),
              let proof = (payload["proof"] as? String)?.lowercased(), proof.count == 64,
              let expected = try? helloProof(token: token, nonce: nonce, session: session)
        else { return nil }
        guard constantTimeEqual(Data(hexString: proof) ?? Data(), Data(hexString: expected) ?? Data()) else { return nil }
        return session
    }

    public static func encrypt(token: String, session: String, direction: String, payload: [String: Any], nonceHex: String? = nil) throws -> [String: Any] {
        let nonce = (nonceHex ?? newNonce()).lowercased()
        guard isValidNonce(nonce), let nonceData = Data(hexString: nonce) else { throw LanError.invalidNonce }
        let key = try deriveKey(token: token, session: session, direction: direction)
        let aadData = try aad(direction: direction, session: session)
        let plain = try JSONSerialization.data(withJSONObject: payload)
        let sealed = try AES.GCM.seal(plain, using: key, nonce: AES.GCM.Nonce(data: nonceData), authenticating: aadData)
        let wire = (sealed.ciphertext + sealed.tag).base64EncodedString()
        return ["version": version, "session": session.lowercased(), "nonce": nonce, "ciphertext": wire]
    }

    public static func decrypt(token: String, session: String, direction: String, envelope: [String: Any]) throws -> [String: Any] {
        guard envelope.count == 4,
              (envelope["version"] as? Int) == version,
              ((envelope["session"] as? String)?.lowercased() == session.lowercased()),
              let nonce = (envelope["nonce"] as? String)?.lowercased(), isValidNonce(nonce),
              let wire = envelope["ciphertext"] as? String, !wire.isEmpty, wire.count <= 12 * 1024 * 1024,
              let combined = Data(base64Encoded: wire), combined.count >= 17,
              let nonceData = Data(hexString: nonce)
        else { throw LanError.invalidEnvelope }
        let key = try deriveKey(token: token, session: session, direction: direction)
        let aadData = try aad(direction: direction, session: session)
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonceData),
                                        ciphertext: combined.prefix(combined.count - 16),
                                        tag: combined.suffix(16))
        let plain = try AES.GCM.open(box, using: key, authenticating: aadData)
        let obj = try JSONSerialization.jsonObject(with: plain)
        guard let dict = obj as? [String: Any] else { throw LanError.invalidPlaintext }
        return dict
    }

    static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count, !a.isEmpty else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }
}

public enum LanError: Error {
    case invalidToken, invalidSession, invalidNonce, invalidDirection, invalidEnvelope, invalidPlaintext
}

extension Data {
    init?(hexString: String) {
        guard hexString.count % 2 == 0 else { return nil }
        var data = Data(capacity: hexString.count / 2)
        var idx = hexString.startIndex
        for _ in 0..<(hexString.count / 2) {
            let next = hexString.index(idx, offsetBy: 2)
            guard let byte = UInt8(hexString[idx..<next], radix: 16) else { return nil }
            data.append(byte); idx = next
        }
        self = data
    }
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
