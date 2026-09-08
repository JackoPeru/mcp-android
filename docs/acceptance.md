# Acceptance v0.6.1

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
- Updater GitHub Releases con controllo versione, verifica checksum e PackageInstaller.
- Verifica preventiva dell'APK candidato: package, versionName, versionCode e signer SHA-256.
- Controllo di coerenza versione tra Node, Gradle, bridge MCP, script APK e tag release.
- CI GitHub per test/build e workflow separata per release firmate e immutabili.
- RPC separato in domini di concorrenza UI, FILE e SHELL; event wait senza lock UI.
- Recovery automatica del socket MCP dopo perdita/ritorno di Tailscale.

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
- download e installazione dell'updater su un telefono reale.

Questi punti richiedono il collaudo sul telefono.

## Verifiche automatiche eseguite il 2026-09-08

- Node: v24.19.0.
- `npm.cmd run check`: controllo versione + 5 test Node, tutti passati.
- Discovery MCP via processo stdio: 47 tool.
- Validazione bridge: path traversal, range file, coordinate, schemi, auth HTTP, redirect refusal, timeout e limite risposta.
- `build-android.ps1`: assembleDebug + testDebugUnitTest + lintDebug completati.
- Test Android/JVM: 25 test, 0 failure, 0 error:
  - ApkIdentityValidationTest: 4
  - HttpBoundaryTest: 4
  - NetworkRecoveryPolicyTest: 3
  - RequestScopeTest: 3
  - RpcPolicyTest: 2
  - SecurityValidatorsTest: 5
  - VersioningTest: 2
  - UpdateValidationTest: 2
- Lint: 0 errori, 1 warning:
  - targetSdk 35 non è l'ultimo SDK disponibile nell'ambiente.
- Manifest merged verificato:
  - minSdk 30;
  - targetSdk 35;
  - ShizukuProvider presente ed esportato con `INTERACT_ACROSS_USERS_FULL`;
  - permesso Shizuku API_V23 presente;
  - metadata V3_SUPPORT presente;
  - Termux RUN_COMMAND dichiarato;
  - AccessibilityService e NotificationListenerService presenti;
  - REQUEST_INSTALL_PACKAGES e UpdateInstallReceiver presenti.
- Firma APK: APK Signature Scheme v2 valida, 1 signer debug.
- Package: `com.example.androidmcp`.
- versionCode: 7.
- versionName: 0.6.1.
- APK: `dist/mcp-android-0.6.1-debug.apk`.
- Dimensione APK: 2,826,535 byte.
- SHA-256: `fddd08491dc661b51c63184674589d2ced128e5ecbe8ee2ba8b1b66214bd4824`.
- Certificato signer SHA-256: `7be7c380f31c81c050a86ea8cefd4ec3bd41972ddd864a8edb97b1e20c84823f`.

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
- L'updater accetta metadata solo dall'endpoint GitHub configurato e limita i redirect agli host release consentiti.
- L'updater rifiuta tag/versioni ambigui, asset con nome inatteso, checksum non valido e APK oltre 100 MiB.
- Prima di PackageInstaller, l'updater rifiuta un APK con package/versionName inattesi, versionCode non crescente o signer diverso dall'app installata.
- Le release esistenti non possono essere sovrascritte dagli script di progetto; la pubblicazione locale richiede `main` pulito e sincronizzato con `origin/main`.
- `events_wait` non occupa il lock UI; filesystem, UI e shell sono serializzati solo nei rispettivi domini.
- Timeout: 8 s per I/O socket, 20 s deadline richiesta sul telefono, 25 s timeout bridge PC; le operazioni lunghe restano limitate a 12 s.
- Una perdita temporanea di Tailscale non termina più il foreground service: il socket viene chiuso e riaperto automaticamente quando la VPN torna.

## Fonti di riferimento

- Android Storage Access Framework / DocumentsContract.
- Android AccessibilityService.
- Android NotificationListenerService.
- Tailscale Android.
- Model Context Protocol TypeScript SDK.
- Termux RUN_COMMAND Intent.
- Shizuku API 13.1.5 / UserService.

## Stato

Implementazione desktop/build **completa per v0.6.1**. Il blocker rimasto è il collaudo end-to-end su telefono fisico, incluso il nuovo updater; nessun successo hardware viene dichiarato finché quel test non viene eseguito.
