# MCP iOS (versione pari — per quanto iOS consente)

App SwiftUI + bridge MCP `ios_*` con lo stesso disegno di sicurezza di Android
(token 256-bit, discovery HMAC su UDP 8766, `/hello` + AES-256-GCM su TCP 8765,
bearer su Tailscale), dominio separato `mcp-ios-*`.

**La parità totale è impossibile su iOS stock** (vedi `../docs/ios-parity.md`):
niente controllo UI di altre app, niente shell, niente notification listener,
screenshot solo in-app foreground, file solo sandbox + bookmark, server sospeso
in background. L'automazione nativa passa dalle **Scorciatoie**
(`ios_shortcut_run`), non da tap/swipe di sistema.

## Struttura

- `Sources/MCPiOSCore/` — logica condivisa e testabile: `LanChannel`,
  `LanDiscovery`, `HttpServer`, `RpcDispatcher`, `FileStore`, `TokenStore`, `Updater`.
- `MCPiOS/` — app SwiftUI (`MCPiOSApp`, `ContentView`, `InterfaceAddrs`) + `Info.plist` + entitlements.
- `Tests/MCPiOSTests/` — vettori crypto/policy/updater (mirror di `tests/ios.test.js`).
- `project.yml` — per `xcodegen generate` su Mac con Xcode 16+ (iOS 17+).

## Porte e policy rete (come Android)

- Mai bind su `0.0.0.0`/`::`: solo IPv4 numerici RFC1918 (LAN) e 100.64/10 (Tailscale), porta 8765; discovery UDP 8766.
- LAN: token mai sul filo, solo prove HMAC; RPC cifrate AES-256-GCM con chiavi request/response separate e binding `requestNonce`.
- Tailscale: bearer compatibile, protetto dal tunnel WireGuard.

## Avvio rapido

Vedi `../docs/ios-parity.md` + `../mcp-config.example.json` (sezione `ios`).
Bridge: `node bridge/ios-server.js` con `IOS_MCP_TOKEN`, `IOS_MCP_TRANSPORT`,
`IOS_MCP_DISCOVERY`, `IOS_MCP_LAN_URL`, `IOS_MCP_TAILSCALE_URL`.
