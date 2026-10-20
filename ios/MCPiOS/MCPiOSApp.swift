import SwiftUI
import MCPiOSCore

@main
struct MCPiOSApp: App {
    @StateObject private var model = ServerModel()
    var body: some Scene {
        WindowGroup { ContentView().environmentObject(model) }
    }
}

@MainActor
final class ServerModel: ObservableObject {
    @Published var token: String = ""
    @Published var maskedToken: String = ""
    @Published var running = false
    @Published var lanAddress: String = ""
    @Published var tailscaleAddress: String = ""
    @Published var lastError: String = ""
    @Published var useTailscaleFallback = true

    private let dispatcher = RpcDispatcher()
    private lazy var server = HttpServer(dispatcher: dispatcher, token: { TokenStore.load() })

    init() { refreshTokenLabel() }

    func refreshTokenLabel() {
        if let t = TokenStore.load() {
            token = t
            maskedToken = String(t.prefix(6)) + "…" + String(t.suffix(4)) + " (tocca Copia, mai condividerlo)"
        } else {
            token = ""; maskedToken = "nessun token: premi Ruota"
        }
    }

    func rotate() { token = TokenStore.rotate(); refreshTokenLabel() }
    func copy() { UIPasteboard.general.string = token }

    func start() {
        guard TokenStore.load() != nil else { lastError = "Ruota prima il token"; return }
        do {
            try server.start(lanAddress: lanOrNil(), tailscaleAddress: tsOrNil())
            running = true; lastError = ""
        } catch { lastError = error.localizedDescription }
    }

    func stop() { server.stop(); running = false }

    private func lanOrNil() -> String? {
        let v = lanAddress.trimmingCharacters(in: .whitespaces)
        return v.isEmpty ? Self.currentLanIPv4() : v
    }
    private func tsOrNil() -> String? {
        let v = tailscaleAddress.trimmingCharacters(in: .whitespaces)
        if !v.isEmpty { return v }
        return useTailscaleFallback ? Self.currentTailscaleIPv4() : nil
    }

    static func currentLanIPv4() -> String? { InterfaceAddrs.firstRFC1918() }
    static func currentTailscaleIPv4() -> String? { InterfaceAddrs.firstTailscale() }
}
