# iOS — parità onesta con Android (stock, senza jailbreak)

La parità 1:1 con Android è **impossibile su iOS stock**: Apple vieta
AccessibilityService cross-app, Notification Listener cross-app, shell,
controllo touch di sistema e server in background permanenti. Questa versione
iOS riusa **lo stesso disegno di sicurezza** (token 256-bit, discovery HMAC,
`/hello` + AES-256-GCM con chiavi separate, bearer su Tailscale) con
**dominio separato** (`mcp-ios-*`), ma espone solo ciò che iOS consente.

## Stesso protocollo, dominio separato

| | Android | iOS |
|---|---|---|
| Porte | TCP 8765 + UDP 8766 | TCP 8765 + UDP 8766 |
| LAN | HMAC discovery + `/hello` + AES-256-GCM | identico, dominio `mcp-ios-*` |
| Tailscale | bearer su tunnel WireGuard | identico |
| Token | 64 hex, Keystore cifrato | 64 hex, Keychain |
| Bridge | `ANDROID_MCP_*` + `android_*` (67+batch) | `IOS_MCP_*` + `ios_*` (28+batch) |

I token non sono intercambiabili tra le due app.

## Matrice metodi

| Area Android | iOS | Nota |
|---|---|---|
| status/device_info/capabilities/diagnostics | ✅ `ios_*` | stesse semantiche, valori iOS |
| screenshot/screen_context | ⚠️ solo in-app foreground | altre app vietate |
| ui_tree/find/click/set_text/wait | ⚠️ solo `ios_ui_tree` in-app | automazione vera via `ios_shortcut_run` |
| tap/swipe/drag/pinch/scroll/press_key/input/global/flow/act_and_observe | ❌ | vietato dal sandbox |
| unlock_device | ❌ | nessuna API |
| clipboard | ✅ foreground | limiti iOS 16+ |
| apps/launch/open_settings | ⚠️ scheme noti + `shortcuts://` | niente query pacchetti |
| open_uri/share_text | ✅ (+ `shortcuts:`) | intent sicuri |
| notifications open/dismiss/reply | ⚠️ solo proprie | listener cross-app vietato |
| media/volume | ⚠️ get solo | niente set volume sistema |
| events | ✅ journal in-app | niente eventi UI sistema |
| shell Termux/Shizuku/logcat/force-stop | ❌ | nessun equivalente stock |
| file_* SAF/all-files | ⚠️ sandbox + bookmark | niente radice globale, niente `Android/data` equivalente |
| batch | ✅ `ios_batch` | senza screenshot/file-mutazioni |
| updater Controlla→Scarica→Installa | ⚠️ check + download verificato | install via TestFlight/App Store, mai silenziosa |

## Setup iPhone

1. Apri `ios/` con Xcode 16+ su Mac (oppure `xcodegen generate` da `project.yml`), firma con il tuo Team, installa su iPhone (iOS 17+).
2. Apri MCP iOS → **Ruota** → **Copia token** → **Avvia controllo remoto**. Tieni l'app in primo piano: in background iOS sospende il server.
3. Facoltativo: importa cartelle con UIDocumentPicker (restano via bookmark) e crea Scorciatoie richiamabili con `ios_shortcut_run`.
4. Sul PC: `npm ci --ignore-scripts`, poi configura `IOS_MCP_TOKEN` + `IOS_MCP_TAILSCALE_URL` / `IOS_MCP_LAN_URL` oppure discovery (`IOS_MCP_DISCOVERY=true`), vedi `mcp-config.example.json`.
5. Test: `npm test` (include `tests/ios.test.js`), poi `ios_status` → `ios_device_info` → `ios_file_roots` → `ios_shortcut_run`.

## Build

```powershell
npm ci --ignore-scripts
npm test
# su Mac con Xcode:
# cd ios; xcodegen generate; open MCPiOS.xcodeproj
# swift test (logica core: LanChannel, discovery, policy, updater)
```

`scripts/version-check.mjs` impone `MARKETING_VERSION == package.json`
e `IOS_MCP_VERSION == package.json`, come già fa per Gradle/bridge Android.
