import CryptoKit
import Foundation

/// Discovery UDP 8766 — stessa semantica di Android, dominio iOS.
/// Richiesta: {"protocol":"mcp-ios-discovery","version":1,"nonce":"..."}
/// Risposta: {"protocol","version","nonce","address","port":8765,"transport":"lan","proof"}
/// proof = HMAC-SHA256(token, "mcp-ios-discovery\n1\n<nonce>\n<address>\n8765\nlan")
public enum LanDiscovery {
    public static let proto = "mcp-ios-discovery"
    public static let version = 1
    public static let rpcPort = 8765
    public static let discoveryPort = 8766
    public static let maxPacketBytes = 512

    public static func proof(token: String, nonce: String, address: String) throws -> String {
        guard LanChannel.isValidToken(token.lowercased()) else { throw LanError.invalidToken }
        let material = "\(proto)\n\(version)\n\(nonce)\n\(address)\n\(rpcPort)\nlan"
        let key = SymmetricKey(data: Data(hexString: token.lowercased())!)
        let mac = HMAC<SHA256>.authenticationCode(for: Data(material.utf8), using: key)
        return Data(mac).hexString
    }

    public static func parseRequest(_ data: Data) -> String? {
        guard !data.isEmpty, data.count <= maxPacketBytes,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              obj.count == 3,
              (obj["protocol"] as? String) == proto,
              (obj["version"] as? Int) == version,
              let nonce = obj["nonce"] as? String,
              LanChannel.isValidHelloNonce(nonce)
        else { return nil }
        return nonce
    }

    public static func encodeResponse(nonce: String, address: String, token: String) throws -> Data {
        let proof = try proof(token: token, nonce: nonce, address: address)
        let obj: [String: Any] = ["protocol": proto, "version": version, "nonce": nonce,
                                  "address": address, "port": rpcPort, "transport": "lan", "proof": proof]
        let data = try JSONSerialization.data(withJSONObject: obj)
        guard data.count <= maxPacketBytes else { throw LanError.invalidPlaintext }
        return data
    }
}
