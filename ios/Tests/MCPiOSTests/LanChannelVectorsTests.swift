import XCTest
@testable import MCPiOSCore

final class LanChannelVectorsTests: XCTestCase {
    // Vettore indipendente dalla piattaforma: stessa derivazione di bridge/ios-channel.js.
    // token = 64 'a' equivalenti? Usiamo token fisso e verifichiamo round-trip + binding.
    func testHelloRoundTrip() throws {
        let token = String(repeating: "ab", count: 32)
        let session = String(repeating: "cd", count: 16)
        let nonce = "hello-nonce-1"
        let proof = try LanChannel.helloProof(token: token, nonce: nonce, session: session)
        let payload = try LanChannel.helloResponse(token: token, nonce: nonce, session: session)
        XCTAssertEqual(payload["proof"] as? String, proof)
        XCTAssertEqual(LanChannel.verifyHello(token: token, nonce: nonce, payload: payload), session)
        XCTAssertNil(LanChannel.verifyHello(token: token, nonce: "wrong", payload: payload))
    }

    func testEncryptDecryptRoundTrip() throws {
        let token = String(repeating: "01", count: 32)
        let session = String(repeating: "02", count: 16)
        let body: [String: Any] = ["method": "status", "params": [:]]
        let env = try LanChannel.encrypt(token: token, session: session, direction: "request", payload: body)
        let back = try LanChannel.decrypt(token: token, session: session, direction: "request", envelope: env)
        XCTAssertEqual(back["method"] as? String, "status")
        // Direzione sbagliata deve fallire (chiavi separate request/response).
        XCTAssertThrowsError(try LanChannel.decrypt(token: token, session: session, direction: "response", envelope: env))
    }

    func testAddressPolicy() {
        XCTAssertTrue(HttpServer.isRfc1918("192.168.1.10"))
        XCTAssertTrue(HttpServer.isRfc1918("10.0.0.5"))
        XCTAssertFalse(HttpServer.isRfc1918("8.8.8.8"))
        XCTAssertTrue(HttpServer.isTailscale("100.64.0.1"))
        XCTAssertFalse(HttpServer.isTailscale("192.168.1.1"))
    }

    func testDiscoveryVectors() throws {
        let token = String(repeating: "ff", count: 32)
        let data = try LanDiscovery.encodeResponse(nonce: "n1", address: "192.168.1.20", token: token)
        XCTAssertLessThanOrEqual(data.count, 512)
        XCTAssertEqual(LanDiscovery.parseRequest(Data(#"{"protocol":"mcp-ios-discovery","version":1,"nonce":"n1"}"#.utf8)), "n1")
        XCTAssertNil(LanDiscovery.parseRequest(Data(#"{"protocol":"mcp-android-discovery","version":1,"nonce":"n1"}"#.utf8)))
    }

    func testPathSafety() {
        XCTAssertNil(FileStore.safeRelative("../secret"))
        XCTAssertNil(FileStore.safeRelative("/etc"))
        XCTAssertNil(FileStore.safeRelative("a\\b"))
        XCTAssertNotNil(FileStore.safeRelative("fatture/settembre.pdf"))
        XCTAssertNotNil(FileStore.safeRelative(""))
    }

    func testUpdaterAssets() {
        XCTAssertTrue(Updater.isValidTag("v0.8.20"))
        XCTAssertFalse(Updater.isValidTag("0.8.20"))
        XCTAssertTrue(Updater.isValidAsset(name: "mcp-ios-0.8.20.ipa", version: "0.8.20"))
        XCTAssertFalse(Updater.isValidAsset(name: "mcp-android-0.8.20-debug.apk", version: "0.8.20"))
    }
}
