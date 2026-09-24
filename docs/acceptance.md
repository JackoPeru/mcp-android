# Acceptance v0.8.20

## Delta v0.8.20 (24/09/2026)

- Audit completo: dedupliche bridge (isValidPng, mapTransportError, single-flight LAN, base64 strict), util Java condivisi (Hex, PrefsFlags), hardening sicurezza (check workdir Shizuku, replay pre-decrypt, requestId SecureRandom, TOCTOU write, rename APK atomico, clipboard auto-clear 45s, opt-in sblocco da agente con toggle in-app), perf (snapshot no-copy, backoff wait, sort cache, SessionVeil 200ms), docs/CI (limits.md, doctor.mjs, validation.test.js, matrix Node 22/24, job Windows, gradle cache).
- `unlock_device` ora richiede opt-in esplicito in-app (checkbox "Permetti sblocco da agente"), default spento; verify-timeout lento non causa più lockout (UNLOCK_TIMEOUT neutro).

## Delta v0.8.19 (17/09/2026)

- Tutti i messaggi utente sono popup chiudibili con testo selezionabile e bottone Copia (niente più banner a scomparsa).

## Delta v0.8.18 (17/09/2026)

- Errori UI parlanti: `safely()` mostra classe e messaggio dell'eccezione (e logga) invece del solo banner generico.

## Delta v0.8.17 (17/09/2026)

- Guida collegamento agente in 3 passi: copia token + copia configurazione JSON già compilata (IP Tailscale reale quando noto).

## Delta v0.8.16 (17/09/2026)

- Toggle schermo-bloccato con etichetta dinamica ("Limita a sole notifiche" / "Attiva accesso completo") più riga di stato esplicita.

## Delta v0.8.15 (17/09/2026)

- Flag "Schermo bloccato: accesso completo" (default uguale a oggi) oppure solo lettura notifiche + `unlock_device`/`ui_done`. Gate centrale in `dispatch()` prima di lock e velo, con test dedicato. Verifica live alla prima occasione utile.
- Chiusura residui audit: `kind` nel batch, `TIMEOUT` probe→`unreachable`, refine coordinate complete, cap su `deleteRecursive`, Actions pinnate a SHA, WakeLock con timeout, doc 67/55/106.

## Delta v0.8.14 (OnePlus 7, Android 12, 14/09/2026)

- Sblocco verificato dal vivo: wake + swipe + 4 cifre toccate da coordinate (i tasti PIN OxygenOS non sono nodi cliccabili), esito `unlocked:true method:pin`. Percorso keyguard dedicato (`gestureOnKeyguard`, `tapKeyguardDigit`) dopo che `checkUi`/`onMain` bloccavano anche il flusso di sblocco. Pill ■ Stop premuta dal vivo con arresto immediato.

## Delta v0.8.13 (14/09/2026)

- Fix salvataggio PIN: `InvalidAlgorithmParameterException: called-provider iv not permitted` — con randomizzazione richiesta, il Keystore vieta IV forniti dal chiamante. Ora l'IV lo genera il Keystore (`getIV()`), in encrypt e sonda. Da verificare sul telefono al rientro.

## Delta v0.8.12 (OnePlus 7, Android 12, 14/09/2026)

- Il dialogo di errore salvataggio PIN mostra anche lo stato della sonda Keystore, così la diagnosi è su un'unica schermata.

## Delta v0.8.11 (OnePlus 7, Android 12, 13/09/2026)

- Sblocco esplicito `android_unlock_device`: PIN 4-16 cifre salvato cifrato (Keystore) solo in-app, mai trasmesso né leggibile; wake + digitazione su keyguard di sistema o swipe se non sicura; un tentativo verificato per chiamata, lockout dopo 5 errori. Trade-off dichiarato in-app e README: chi ha il token può chiedere lo sblocco. Sonda Keystore con autotest visibile nella card di sblocco (mostra la diagnosi prima del salvataggio).

## Delta v0.8.10 (OnePlus 7, Android 12, 13/09/2026)

- La patina tiene acceso lo schermo (`FLAG_KEEP_SCREEN_ON` sulla sua finestra, nessun permesso nuovo): verificato `Awake` oltre il timeout di 120 s senza tocchi e `Dozing` regolare dopo lo spegnimento.

## Delta v0.8.9 (OnePlus 7, Android 12, 13/09/2026)

- Self-driving setup screens: `includeOwnApp` (default off) on `screen_context`, `ui_tree`, `ui_find`, `ui_click` exposes MCP Android windows so an agent can complete grants/Avvia alone. The token view carries a stable `token_secret` id and stays hidden even then; password nodes stay redacted. Bridge/JVM verdi; verifica live (albero proprio popolato, token assente, tocco innocuo) in attesa di telefono ricollegato.

## Delta v0.8.8 (OnePlus 7, Android 12, 13/09/2026)

- Patina animata stile onda + pill STOP istantanea (vedi voce v0.8.7).
- Patina guidata dall'attività: `pulse()` su ogni RPC visiva (`RpcPolicy.showsVeil`), spegnimento dopo 90 s di inattività UI (copre le pause di ragionamento tra micro-passi), mai all'avvio sessione; shell/file/status restano al buio. Chiusura immediata con il nuovo tool `android_ui_done`, che ogni agente chiama a fine task.

## Delta v0.8.7 (OnePlus 7, Android 12, 13/09/2026)

- Accesso completo opzionale dietro flag: `MANAGE_EXTERNAL_STORAGE` + interruttore in-app espone la radice `all-files` (tutta la memoria condivisa, senza più cartelle una a una). `Android/data` e `Android/obb` restano bloccati con `OPERATION_UNSUPPORTED` (limite di sistema). Stesso contratto JSON/codici del SAF, test JVM dedicati, verificato live (`roots`, `list`, `read`, `write`+`delete` di pulizia, traversal e `data` rifiutati).
- Patina di sessione: onde animate fullscreen non interagibili più pill STOP toccabile in basso al centro (`SYSTEM_ALERT_WINDOW`, solo con consenso). Il velo non intercetta i tocchi perché mangerebbe anche i gesti iniettati dell'agente (stesso canale di input); la pill è una finestra separata e l'arresto è istantaneo via `stopNow`. Per gli screenshot l'agente non vede mai né onde né pill: `screenshot()` sospende tutto per un frame e ripristina in `finally` (se la sessione cade nel mezzo, resta giustamente spento).

## Delta v0.8.6 (OnePlus 7, Android 12, 13/09/2026)

- Nuovo logo: adaptive icon + mipmap da `logo mcp android.png` (firma `ic_launcher`, round, sfondo `#0B1217` in tema con l'app).
- Guida `allow-external-apps` integrata nei dialoghi Termux e nel README: senza la proprietà in `~/.termux/termux.properties` Termux risponde `termuxErrorCode 2`.
- Shell Termux verificata live (`id`, `pwd`, stdin, exit 0) dopo fallback + proprietà.

## Delta v0.8.5 (OnePlus 7, Android 12, 13/09/2026)

- Fix avvio comandi Termux bloccato dal sistema (`Background start not allowed ... startFg?=false` in logcat anche con foreground service attivo): fallback a `startForegroundService`, sicuro perché Termux (targetSdk 28) è esente dal timeout foreground. Verificato live con `id`/`pwd`/stdin.
- Nota batteria: su OxygenOS aggressivo, togliere MCP Android dall'ottimizzazione batteria se i comandi falliscono all'avvio.

## Delta v0.8.4 (verificato su OnePlus 7, Android 12, il 13/09/2026)

- Tasto **Copia negli appunti** nella schermata token (`MainActivity.showToken`): niente più trascrizione a mano.
- Guida Termux azionabile: pre-check installazione, dialogo con passi F-Droid/apertura/riprova invece del solo toast.
- Binding `requestNonce` risposta↔richiesta attivo su entrambi i lati (bridge + app): verificato live che il server vecchio senza binding viene rifiutato fail-closed.
- Hardening audit: allowlist `open_uri`, `outcome_unknown` uniforme sul bearer, validazione `workdir`, pin porta 8765, TTL sessioni LAN 10 min, redazione logcat estesa, `SEEK_UNSUPPORTED` in scrittura, supporto Ethernet, backoff accept 1s→5s.
- Verifiche live: discovery HMAC, handshake `/hello`, RPC AES (`status`, `diagnostics`, `screen_context`, `screen_diff`, backend status), teardown STOP senza socket residue, manifest live senza permessi pericolosi.
- Limiti noti invariati: SAF non concede intero volume/Download/`Android/data`; Termux richiede consenso; Shizuku assente sul device di test; Doze può differire il watchdog.

## Scope verificato

- Bridge MCP stdio + trasporto TCP autenticato verso il telefono.
- **67 tool MCP** (65 della v0.7 più `ui_done` per lo spegnimento esplicito della patina e `unlock_device` per lo sblocco esplicito).
- Dual transport:
  - LAN Wi-Fi RFC1918 su TCP 8765;
  - Tailscale 100.64.0.0/10 su TCP 8765;
  - listener indipendenti, entrambi legati a IPv4 numerici concreti;
  - nessun bind RPC su `0.0.0.0` o `::`;
  - LAN limitata anche ai client della stessa subnet Wi-Fi.
- `TransportManager` event-driven:
  - callback Android Wi-Fi;
  - callback Android VPN con `NET_CAPABILITY_NOT_VPN` esplicitamente rimossa dalla `NetworkRequest`;
  - riconciliazione debounced;
  - watchdog di sicurezza ogni 15 minuti mentre START;
  - teardown completo mentre STOP.
- Discovery LAN:
  - UDP 8766;
  - socket di ricezione wildcard IPv4 vincolato alla `Network` Wi-Fi selezionata; nessun wildcard TCP RPC;
  - massimo 512 byte;
  - protocollo/versione/nonce strettamente validati;
  - risposta unicast;
  - nessun token o dato del dispositivo;
  - risposta firmata HMAC-SHA256 usando il token come chiave, senza trasmettere il token;
  - sorgente obbligatoriamente RFC1918 e nella stessa subnet;
  - IP dichiarato nel payload obbligatoriamente uguale all'IP sorgente UDP;
  - rate limit 1 risposta/s per IP;
  - massimo 64 sorgenti conservate in RAM.
  - un errore di bind della discovery UDP non spegne il listener RPC LAN TCP.
  - se la discovery UDP è down mentre il TCP LAN resta attivo, viene ritentato solo il responder discovery alle riconciliazioni successive.
- Bridge LAN-first:
  - endpoint LAN configurato opzionale;
  - discovery LAN one-shot;
  - candidato discovery accettato solo dopo HMAC valido + source-IP/subnet validation;
  - handshake `/hello` senza bearer con nonce casuale, sessione casuale e prova HMAC del server;
  - RPC LAN cifrate/autenticate AES-256-GCM con chiavi distinte request/response derivate da token + sessione;
  - bearer, metodo, params, risultati ed errori RPC non vengono trasmessi in chiaro sulla LAN;
  - replay guard bounded non-evicting su nonce di richieste già autenticate; a capacità esaurita la sessione viene ruotata al successivo `/hello`;
  - Tailscale fallback;
  - cache LAN validata con TTL breve per evitare una RPC `status` prima di ogni operazione;
  - errore di trasporto LAN invalida immediatamente il TTL della cache, così la chiamata successiva rivalida/fallbacka;
  - nessun replay automatico del metodo richiesto dopo un errore di trasporto con esito incerto.
- Compatibilità legacy:
  - `ANDROID_MCP_URL=http://100.x.y.z:8765` + token continua a funzionare in modalità Tailscale-only.
- Nuova configurazione:
  - `ANDROID_MCP_LAN_URL`;
  - `ANDROID_MCP_TAILSCALE_URL`;
  - `ANDROID_MCP_TRANSPORT=auto|lan|tailscale`;
  - `ANDROID_MCP_DISCOVERY=true|false`.
- Stato/diagnostica:
  - endpoint LAN;
  - endpoint Tailscale;
  - trasporto preferito;
  - monitor rete `event_driven_dual`;
  - nessun token, SSID, BSSID o MAC esposto.
- UI Android:
  - stato sessione START/STOP;
  - LAN disponibile/non disponibile;
  - Tailscale disponibile/non disponibile;
  - trasporto preferito.
- Tutte le funzionalità v0.7 restano presenti:
  - snapshot/diff semantico;
  - `act_and_observe`;
  - flow locali bounded;
  - gesture;
  - app/intenti;
  - notifiche/media;
  - SAF;
  - Termux;
  - Shizuku;
  - logcat/force-stop;
  - capability router;
  - diagnostics/trace.

## Modello energetico verificato dal codice

In modalità START:

- Wi-Fi e VPN sono seguiti tramite callback Android, non polling rapido.
- Il watchdog effettua al massimo 4 riconciliazioni/ora in assenza di eventi.
- Ogni listener TCP resta bloccato su `accept()` quando inattivo.
- Il responder UDP LAN resta bloccato su `receive()` quando inattivo.
- Il pool RPC ha 0 worker core permanenti.
- Il timeout scheduler può terminare il proprio worker dopo 30 s di idle.
- Il worker updater non resta residente permanentemente.
- Screenshot e scansioni UI vengono eseguiti solo su richiesta.
- La cache LAN validata riduce handshake/probe ripetuti durante sequenze rapide.
- HMAC e AES-GCM vengono eseguiti solo in risposta a discovery o traffico RPC; non aggiungono polling idle.

In casa è possibile lasciare Tailscale completamente spento e mantenere MCP raggiungibile tramite LAN.

In modalità STOP:

- listener TCP LAN chiuso;
- listener TCP Tailscale chiuso;
- discovery UDP chiusa;
- callback Wi-Fi/VPN unregisterate;
- watchdog rimosso;
- Accessibility `eventTypes=0`;
- Notification Listener unbound;
- Shizuku disconnesso/lazy;
- nessun polling MCP periodico.

Questa verifica riguarda architettura, lifecycle e test software. **Non viene dichiarata una percentuale di batteria/ora senza misura su telefono fisico.**

## Boundary fisico

Nessun telefono fisico è stato collegato durante l'implementazione v0.8.3. Build, test JVM e fixture Node non dimostrano ancora:

- ricezione reale del broadcast UDP 8766 su una specifica ROM Android;
- bind simultaneo reale dei listener LAN e Tailscale;
- comportamento del routing Android con Tailscale attivo e Wi-Fi locale;
- discovery LAN tra il PC agente e il telefono su router/AP reali;
- cambio DHCP dell'IP Wi-Fi;
- passaggio reale LAN → Tailscale quando si lascia la rete domestica;
- passaggio Tailscale → LAN al rientro;
- comportamento con AP/client isolation;
- firewall del PC/router;
- recovery reale dopo cambio Wi-Fi/VPN;
- comportamento reale del secure channel su una ROM/rete Wi-Fi fisica e sotto packet capture/MITM reali;
- consumo batteria LAN-only, Tailscale-only o dual;
- gesture/screenshot/flow sulle app reali;
- Notification Listener, Termux, Shizuku, SAF e updater sul dispositivo.

Questi punti richiedono collaudo end-to-end sul telefono.

## Verifiche automatiche eseguite il 2026-09-09

- Node: v24.19.0.
- `npm.cmd run check`:
  - coerenza versione: OK;
  - **55 test Node, tutti passati**.
- `npm audit --omit=dev`: **0 vulnerabilità**.
- Test Node v0.8.3 includono:
  - config Tailscale legacy;
  - config dual transport;
  - validazione URL LAN/Tailscale;
  - discovery UDP one-shot;
  - assenza del token nel datagramma discovery;
  - HMAC discovery con vettore condiviso Android/Node;
  - validazione nonce;
  - rifiuto discovery se payload IP e sorgente UDP non coincidono;
  - rifiuto discovery se la sorgente non appartiene a una subnet interrogata;
  - timeout discovery bounded;
  - `/hello` senza bearer e prova HMAC del server;
  - AES-256-GCM request/response con vettore cross-language;
  - chiavi AES distinte per request e response;
  - rifiuto risposta cifrata manomessa/sessione errata;
  - verifica che bearer, metodo e params non compaiano nel wire LAN;
  - risposta LAN non autenticabile classificata `outcome_unknown` e sessione invalidata;
  - LAN-first;
  - fallback Tailscale;
  - auth failure non trasformato in fallback;
  - metodo mutante inviato una sola volta;
  - cache endpoint LAN validata;
  - invalidazione cache LAN dopo errore di trasporto e fallback della chiamata successiva;
  - UI dual transport;
  - discovery dei 67 tool MCP tramite stdio.
- `build-android.ps1`: assembleDebug + testDebugUnitTest + lintDebug completati.
- Android/JVM: **106 test, 0 failure, 0 error, 0 skipped**:
  - ActionRegistryTest: 2
  - AllFilesStoreTest: 8
  - ApkIdentityValidationTest: 4
  - CapabilityRouterTest: 4
  - CoordinateResolverTest: 3
  - DevicePinStoreTest: 1
  - FlowRuntimeTest: 2
  - FlowTraceTest: 1
  - FlowValidationTest: 4
  - HttpBoundaryTest: 6
  - IdleExecutorPolicyTest: 3
  - JsonArgsTest: 1
  - LanDiscoveryProtocolTest: 3
  - LanDiscoveryRateLimitTest: 3
  - LanSecureChannelTest: 4
  - LockedAccessTest: 2
  - LowPowerSessionPolicyTest: 4
  - NetworkAddressPolicyTest: 4
  - NetworkRecoveryPolicyTest: 3
  - RequestScopeTest: 3
  - RpcEndpointServerPolicyTest: 3
  - RpcPolicyTest: 4
  - ScreenSnapshotStoreTest: 5
  - SecurityValidatorsTest: 6
  - ShizukuShellServiceTest: 2
  - TraceJournalTest: 2
  - TransportDiagnosticsTest: 4
  - TransportReconciliationTest: 5
  - UiLoopPolicyTest: 3
  - UpdateManagerTest: 1
  - UpdateValidationTest: 4
  - VersioningTest: 2
- Lint: **0 errori, 20 warning** (nessuno introdotto dal lotto corrente):
  - targetSdk 35 non è l'ultimo SDK disponibile nell'ambiente;
  - `androidx.core:core 1.15.0` non è l'ultima versione disponibile nell'ambiente;
  - 12 stringhe legacy risultano ora inutilizzate dopo il redesign della UI.

## APK

- Package: `com.example.androidmcp`.
- versionCode: **31**.
- versionName: **0.8.20**.
- minSdk: **30**.
- targetSdk: **35**.
- APK: `mcp-android-0.8.20-debug.apk` (fonte primaria: GitHub Release v0.8.20).
- Dimensione: vedi asset release (verificata via SHA-256 a ogni pubblicazione).
- SHA-256: vedi `mcp-android-0.8.20-debug.apk.sha256` nella GitHub Release v0.8.20.
- APK Signature Scheme v2: **valida**.
- Signer: **1**.
- Chiave: RSA 2048, Android Debug.
- Certificato signer SHA-256:
  `7be7c380f31c81c050a86ea8cefd4ec3bd41972ddd864a8edb97b1e20c84823f`.
- Il signer coincide con le release precedenti compatibili con l'updater.

## Sicurezza / trust boundary

- Il token casuale da 256 bit resta il segreto radice di autenticazione per entrambi i trasporti.
- Token rotation invalida entrambe le reti; l'app ferma il servizio prima di ruotarlo.
- Discovery UDP usa il token soltanto come chiave HMAC e non lo trasmette.
- Discovery non trasmette token, authorization header o dati privati.
- Una risposta discovery è accettata solo se HMAC, nonce, IP payload, IP sorgente e subnet interrogata coincidono.
- LAN RPC:
  - bind su IPv4 RFC1918 concreto;
  - client obbligatoriamente nella stessa subnet;
  - `/hello` non contiene bearer; il server dimostra il possesso del token con HMAC-SHA256 su nonce + sessione;
  - chiavi AES-256-GCM derivate via HMAC-SHA256 da token + sessione + direzione;
  - request e response usano chiavi distinte;
  - nonce GCM casuale a 96 bit; replay guard bounded non-evicting e rotazione sessione/chiavi quando la capacità viene esaurita;
  - HTTP è solo framing: bearer, RPC e contenuti non transitano in plaintext;
  - risposta non autenticabile dopo l'invio è sempre `outcome_unknown`, mai replay automatico.
- Tailscale RPC:
  - bind su IPv4 100.64.0.0/10 concreto;
  - client ammessi solo in 100.64.0.0/10;
  - bearer HTTP legacy mantenuto per compatibilità, dentro il tunnel WireGuard Tailscale.
- Nessun listener RPC pubblico.
- Nessun `0.0.0.0` / `::` per TCP RPC.
- Nessun mDNS, multicast lock, port forwarding, UPnP o Funnel.
- Errori auth verificati prima del dispatch non vengono reinterpretati come problemi di rete; dopo l'invio di una RPC LAN vale invece la regola `outcome_unknown` perché una risposta HTTP non autenticata può essere forgiata on-path.
- Dopo il dispatch di una RPC mutante, timeout/disconnessione restituiscono esito incerto e non causano replay automatico sull'altro trasporto.
- Dopo un errore di trasporto LAN la cache viene invalidata senza replay della RPC fallita; la chiamata successiva può quindi passare a Tailscale.
- PIN, biometria, keyguard e finestre protette non vengono aggirati.
- Le altre boundary v0.7 su SAF, shell, Shizuku, flow, trace e updater restano invariate.

## Collaudo fisico richiesto

### LAN-only

1. Spegnere Tailscale sul telefono.
2. Collegare telefono e agente alla stessa Wi-Fi.
3. Premere START su MCP Android.
4. Verificare che l'app mostri un endpoint LAN.
5. Avviare il bridge con `ANDROID_MCP_TRANSPORT=auto` e discovery attiva.
6. Verificare discovery HMAC + `/hello` autenticato + RPC `status` cifrata.
7. Eseguire `screen_context` e una flow breve.

### Dual

1. Attivare Tailscale mantenendo la Wi-Fi.
2. Verificare che compaiano entrambi gli endpoint.
3. Verificare che `auto` scelga LAN.
4. Disattivare Wi-Fi e verificare che una nuova RPC usi Tailscale.
5. Riattivare Wi-Fi e verificare il ritorno a LAN.

### DHCP / cambio rete

1. Cambiare rete Wi-Fi o rinnovare l'IP.
2. Verificare chiusura listener vecchio.
3. Verificare nuovo listener.
4. Verificare invalidazione del vecchio endpoint/cache e nuova discovery.

### STOP

Verificare che TCP 8765 LAN/Tailscale e UDP 8766 non siano più raggiungibili e che la diagnostica/Android non mostri callback o backend rimasti attivi.

### Batteria

Misurare separatamente per alcune ore:

- START + LAN-only idle;
- START + Tailscale-only idle;
- START + LAN e Tailscale;
- automazione intensa;
- STOP.

## Stato

Implementazione software/build **completa per v0.8.20**, con secure LAN transport e updater in stile HermesHub. La release è pronta per il collaudo fisico, ma broadcast/routing reale, packet capture su rete fisica, updater su dispositivo reale e consumo batteria non vengono dichiarati verificati finché non vengono provati sul telefono.
