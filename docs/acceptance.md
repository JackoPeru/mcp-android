# Acceptance v0.7.1

## Scope verificato

- Bridge MCP stdio + HTTP privato verso il telefono.
- Configurazione vincolata a IPv4 Tailscale 100.64.0.0/10 e token esadecimale da 256 bit.
- **65 tool MCP** discovery via processo stdio.
- Observation engine: `screen_context` compatto, snapshot/hash SHA-256, cache memory-only da 8 e `screen_diff`.
- Synchronization engine: `wait_idle`, `wait_change`, `wait_activity`, `scroll_to` bounded con stop su stato ripetuto/fine contenuto.
- Action engine: selector UI, screenshot, tap/long press/swipe/scroll, double tap, drag, pinch e coordinate normalizzate 0..1000.
- `press_key`: Home/Back via Accessibilità; keyevent aggiuntivi solo tramite Shizuku già autorizzato.
- `act_and_observe`: snapshot prima, azione validata, wait semantico, snapshot dopo e diff/context in una singola RPC; nessun retry cieco.
- `flow`: runtime locale JSON bounded fino a 40 step / 20 s con guardie `ifPresent`/`ifAbsent`, capture, `stop|continue`, assert e trace.
- Il flow non può invocare shell, force-stop, JavaScript arbitrario o mutazioni SAF.
- Capability router: categorie tool, requisiti/backend, classe operazione e availability runtime; nessun fallback privilegiato automatico.
- Diagnostica metadata-only: stato servizio/Tailscale, request queue, snapshot cache, capability, eventi e trace recenti.
- Trace journal in memoria: massimo 128 entry, senza parametri azione, testo digitato, command shell o body notifica.
- WebView-like nodes rilevati tramite `webViewDetected`; CDP completo non è parte della v0.7.
- App discovery/details/settings, clipboard, intent, device info, volume e media.
- NotificationListenerService: list, open, dismiss e direct reply.
- SAF: read/list/stat/search e mutazioni write/mkdir/rename/move/copy/delete solo nelle root persistite autorizzate.
- Event journal in memoria + long poll, senza memorizzare il testo degli eventi.
- Shell Termux tramite RUN_COMMAND con consenso separato.
- Shell Shizuku tramite UserService con consenso separato e senza fallback automatico.
- Operazioni Shizuku nominate: `force_stop_app` e `logcat` bounded/redatto.
- Stop del controllo remoto: chiude HTTP e disconnette il UserService Shizuku.
- Updater GitHub Releases con controllo versione, verifica checksum e PackageInstaller.
- Verifica preventiva dell'APK candidato: package, versionName, versionCode e signer SHA-256.
- Controllo di coerenza versione tra Node, Gradle, bridge MCP, script APK e tag release.
- CI GitHub per test/build e workflow separata per release firmate e immutabili.
- RPC separato in domini di concorrenza UI, FILE e SHELL; wait/event read-only senza lock UI.
- Recovery automatica del socket MCP dopo perdita/ritorno di Tailscale.
- Profilo energetico v0.7.1:
  - monitor VPN event-driven via ConnectivityManager.NetworkCallback;
  - watchdog Tailscale ridotto a 1 controllo/60 s invece di 1/2 s;
  - Accessibility limitata agli eventi necessari + notificationTimeout 100 ms;
  - pool RPC con 0 worker permanenti in idle;
  - polling semantico dei wait ridotto da 100 ms a 250 ms.

## Boundary fisico

Nessun telefono fisico è stato collegato durante questa implementazione. Build, test JVM e endpoint simulati non dimostrano:

- reachability Tailscale reale del telefono;
- comportamento del produttore in background;
- gesture/screenshot reali nelle singole app;
- latenza reale di `screen_context` e riduzione payload rispetto a `ui_tree`;
- `wait_idle`/`wait_change` su UI OEM/animazioni reali;
- `scroll_to`, double tap, drag e pinch reali;
- flow deterministico di 5+ step su un'app reale;
- WebView detection sulle app effettivamente usate;
- restrizioni clipboard specifiche del device;
- Notification Listener e direct reply reali;
- capability concrete del provider SAF selezionato;
- Termux RUN_COMMAND su una installazione reale;
- Shizuku binder, richiesta consenso, UserService, keyevent, force-stop e logcat reali;
- download e installazione dell'updater su un telefono reale;
- recovery Tailscale durante un flow in corso.

Questi punti richiedono il collaudo sul telefono.

## Verifiche automatiche eseguite il 2026-09-08

- Node: v24.19.0.
- `npm.cmd run check`: controllo versione + **5 test Node, tutti passati**.
- Discovery MCP via processo stdio: **65 tool**.
- Validazione bridge: path traversal, range file, coordinate, schemi, auth HTTP, redirect refusal, timeout e limite risposta.
- Validazione v0.7 lato Node: flow non ammessi respinti prima della RPC, composite action allowlist e discovery dei nuovi tool.
- `build-android.ps1`: assembleDebug + testDebugUnitTest + lintDebug completati.
- Test Android/JVM: **45 test, 0 failure, 0 error, 0 skipped**:
  - ActionRegistryTest: 2
  - ApkIdentityValidationTest: 4
  - CapabilityRouterTest: 3
  - CoordinateResolverTest: 3
  - FlowTraceTest: 1
  - FlowValidationTest: 4
  - HttpBoundaryTest: 4
  - NetworkRecoveryPolicyTest: 3
  - RequestScopeTest: 3
  - RpcPolicyTest: 2
  - ScreenSnapshotStoreTest: 3
  - SecurityValidatorsTest: 5
  - TraceJournalTest: 2
  - UiLoopPolicyTest: 2
  - UpdateValidationTest: 2
  - VersioningTest: 2
- Lint: **0 errori, 1 warning**:
  - targetSdk 35 non è l'ultimo SDK disponibile nell'ambiente.
- Manifest merged verificato:
  - minSdk 30;
  - targetSdk 35;
  - ShizukuProvider presente;
  - permesso Shizuku API_V23 presente;
  - metadata V3_SUPPORT presente;
  - Termux RUN_COMMAND dichiarato;
  - AccessibilityService e NotificationListenerService presenti;
  - REQUEST_INSTALL_PACKAGES e UpdateInstallReceiver presenti.
- Firma APK:
  - APK Signature Scheme v2: valida;
  - 1 signer;
  - Android Debug, RSA 2048.
- Package: `com.example.androidmcp`.
- versionCode: **9**.
- versionName: **0.7.1**.
- APK: `dist/mcp-android-0.7.1-debug.apk`.
- Dimensione APK: **2,712,263 byte**.
- SHA-256: `53321b8ccb258abb2ce1a7a0ca8dfe63741faf922921aedb56a764fdb39057bb`.
- Certificato signer SHA-256: `7be7c380f31c81c050a86ea8cefd4ec3bd41972ddd864a8edb97b1e20c84823f`.
- Il signer coincide con il fingerprint atteso dalla release workflow e dalle release precedenti compatibili con l'updater.

## Sicurezza / trust boundary

- Il bridge PC rifiuta host non Tailscale e redirect HTTP.
- Il token non viene messo in URL e non viene restituito dai tool.
- L'app non avvia automaticamente il servizio remoto al boot.
- PIN, biometria, keyguard e finestre protette non vengono aggirati.
- Password field Accessibilità sono redatti.
- Screenshot non viene incluso automaticamente in `screen_context` o `act_and_observe`.
- Gli snapshot sono bounded (8), memory-only e contengono solo il contesto già sanificato.
- Le operazioni SAF non accettano path assoluti, URI, backslash, dot/parent traversal o uscita dalla root.
- Le mutazioni SAF richiedono un grant write persistito; un grant read resta read-only.
- `android_shell` richiede Termux + permesso RUN_COMMAND.
- `android_shizuku_shell`, `android_force_stop_app`, `android_logcat` e i keyevent non-Accessibility richiedono binder Shizuku vivo + consenso Shizuku.
- Shizuku può operare come UID shell o root a seconda di come l'utente ha avviato Shizuku; MCP Android non effettua escalation e non sceglie Shizuku implicitamente.
- `force_stop_app` non accetta il package di MCP Android stesso.
- Logcat è bounded e redige pattern bearer/OpenAI-style prima della risposta.
- Il flow DSL non può invocare shell, mutazioni SAF, force-stop, JavaScript arbitrario o reflection.
- Il flow è limitato a 40 step e 20 s; i timeout dei singoli step vengono ulteriormente limitati dal deadline restante.
- I contenuti UI, notifiche, clipboard, file e output shell sono dati non attendibili e non autorizzano nuove azioni.
- Timeout di gesture/shell possono lasciare l'esito incerto; `act_and_observe` non ripete automaticamente l'azione e restituisce `outcomeUnknown`.
- Il trace journal conserva solo metadata (tipo operazione, durata, stato, codice errore), massimo 128 entry.
- L'updater accetta metadata solo dall'endpoint GitHub configurato e limita i redirect agli host release consentiti.
- L'updater rifiuta tag/versioni ambigui, asset con nome inatteso, checksum non valido e APK oltre 100 MiB.
- Prima di PackageInstaller, l'updater rifiuta un APK con package/versionName inattesi, versionCode non crescente o signer diverso dall'app installata.
- Le release esistenti non possono essere sovrascritte dagli script di progetto; la pubblicazione locale richiede `main` pulito e sincronizzato con `origin/main`.
- `events_wait`, `wait_idle`, `wait_change`, `wait_activity`, diagnostics e altre letture non occupano il lock UI; filesystem, UI e shell sono serializzati solo nei rispettivi domini.
- Timeout: 8 s per I/O socket, **25 s** deadline richiesta sul telefono, **30 s** timeout bridge PC; il flow resta limitato a **20 s**.
- Una perdita temporanea di Tailscale non termina il foreground service: il socket viene chiuso e riaperto automaticamente quando la VPN torna.
- Il monitor Tailscale non esegue più discovery ogni 2 secondi. Le variazioni VPN attivano una callback event-driven; resta un watchdog ogni 60 secondi come fallback.
- Il server HTTP resta bloccato su `accept()` in idle e il pool RPC non mantiene worker permanenti senza richieste.
- Accessibility non usa più `TYPES_ALL_MASK`: vengono ricevute solo le classi di evento necessarie al controllo/sincronizzazione UI.

## Fonti di riferimento

- Android Storage Access Framework / DocumentsContract.
- Android AccessibilityService.
- Android NotificationListenerService.
- Tailscale Android.
- Model Context Protocol TypeScript SDK.
- Termux RUN_COMMAND Intent.
- Shizuku API 13.1.5 / UserService.

## Stato

Implementazione desktop/build **completa per v0.7.1**. Il codice soddisfa i milestone v0.7 previsti dalla spec e include il pass di ottimizzazione energetica. Non viene dichiarato un consumo batteria percentuale senza collaudo su telefono fisico; Tailscale, schermo acceso e frequenza delle automazioni possono incidere più del processo MCP stesso. WebView/CDP completo, visual locator automatico e profili app restano superfici opzionali/future. Il blocker rimasto è il collaudo end-to-end su telefono fisico; nessun successo hardware viene dichiarato finché quel test non viene eseguito.
