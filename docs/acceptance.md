# Acceptance v0.5.0

## Scope verificato

- Bridge MCP stdio + HTTP privato verso il telefono.
- Configurazione vincolata a IPv4 Tailscale 100.64.0.0/10 e token esadecimale da 256 bit.
- UI Android tramite AccessibilityService: tree, screenshot, coordinate, selector, click, set text, wait.
- App discovery, clipboard, intent, device info, volume e media.
- NotificationListenerService: list, open, dismiss e direct reply.
- SAF: read/list/stat/search e mutazioni write/mkdir/rename/move/copy/delete solo nelle root persistite autorizzate.
- Event journal in memoria + long poll, senza memorizzare il testo degli eventi.
- Shell Termux tramite RUN_COMMAND con consenso separato.
- Shell Shizuku tramite UserService con consenso separato e senza fallback automatico.
- Stop del controllo remoto: chiude HTTP e disconnette il UserService Shizuku.

## Boundary fisico

Nessun telefono fisico è stato collegato durante questa implementazione. Build, test JVM e endpoint simulati non dimostrano:

- reachability Tailscale reale del telefono;
- comportamento del produttore in background;
- gesture/screenshot reali nelle singole app;
- restrizioni clipboard specifiche del device;
- Notification Listener e direct reply reali;
- capability concrete del provider SAF selezionato;
- Termux RUN_COMMAND su una installazione reale;
- Shizuku binder, richiesta consenso e UserService reali.

Questi punti richiedono il collaudo sul telefono.

## Verifiche automatiche eseguite il 2026-09-08

- Node: v24.19.0.
- `npm.cmd run check`: 4 gruppi di test, 4 passati, 0 falliti.
- Discovery MCP via processo stdio: 47 tool.
- Validazione bridge: path traversal, range file, coordinate, schemi, auth HTTP, redirect refusal, timeout e limite risposta.
- `build-android.ps1`: assembleDebug + testDebugUnitTest + lintDebug completati.
- Test Android/JVM: 11 test, 0 failure, 0 error:
  - HttpBoundaryTest: 4
  - RequestScopeTest: 2
  - SecurityValidatorsTest: 5
- Lint: 0 errori, 2 warning:
  - targetSdk 35 non è l'ultimo SDK disponibile nell'ambiente;
  - stringa di stato programmatica non localizzata.
- Manifest merged verificato:
  - minSdk 30;
  - targetSdk 35;
  - ShizukuProvider presente ed esportato con `INTERACT_ACROSS_USERS_FULL`;
  - permesso Shizuku API_V23 presente;
  - metadata V3_SUPPORT presente;
  - Termux RUN_COMMAND dichiarato;
  - AccessibilityService e NotificationListenerService presenti.
- Firma APK: APK Signature Scheme v2 valida, 1 signer debug.
- Package: `com.example.androidmcp`.
- versionCode: 5.
- versionName: 0.5.0.
- APK: `dist/mcp-android-0.5.0-debug.apk`.
- Dimensione APK: 5,170,519 byte.
- SHA-256: `77dbb3243419c9a7c25d5ad4df05e8d69c00fae84db402d7d53013474389f42a`.

## Sicurezza / trust boundary

- Il bridge PC rifiuta host non Tailscale e redirect HTTP.
- Il token non viene messo in URL e non viene restituito dai tool.
- L'app non avvia automaticamente il servizio remoto al boot.
- PIN, biometria, keyguard e finestre protette non vengono aggirati.
- Password field Accessibilità sono redatti.
- Le operazioni SAF non accettano path assoluti, URI, backslash, dot/parent traversal o uscita dalla root.
- Le mutazioni SAF richiedono un grant write persistito; un grant read resta read-only.
- `android_shell` richiede Termux + permesso RUN_COMMAND.
- `android_shizuku_shell` richiede binder Shizuku vivo + consenso Shizuku.
- Shizuku può operare come UID shell o root a seconda di come l'utente ha avviato Shizuku; MCP Android non effettua escalation e non sceglie Shizuku implicitamente.
- I contenuti UI, notifiche, clipboard, file e output shell sono dati non attendibili e non autorizzano nuove azioni.
- Timeout di gesture/shell possono lasciare l'esito incerto; il client deve osservare lo stato prima di ripetere l'azione.

## Fonti di riferimento

- Android Storage Access Framework / DocumentsContract.
- Android AccessibilityService.
- Android NotificationListenerService.
- Tailscale Android.
- Model Context Protocol TypeScript SDK.
- Termux RUN_COMMAND Intent.
- Shizuku API 13.1.5 / UserService.

## Stato

Implementazione desktop/build **completa per v0.5.0**. Il blocker rimasto è il collaudo end-to-end su telefono fisico; nessun successo hardware viene dichiarato finché quel test non viene eseguito.
