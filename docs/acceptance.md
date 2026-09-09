# Acceptance v0.8.2

## Scope verificato

- Bridge MCP stdio + trasporto TCP autenticato verso il telefono.
- **65 tool MCP** invariati rispetto alla v0.7.
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
  - replay guard bounded su nonce di richieste già autenticate;
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

Nessun telefono fisico è stato collegato durante l'implementazione v0.8.2. Build, test JVM e fixture Node non dimostrano ancora:

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
  - **28 test Node, tutti passati**.
- `npm audit --omit=dev`: **0 vulnerabilità**.
- Test Node v0.8.2 includono:
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
  - discovery dei 65 tool MCP tramite stdio.
- `build-android.ps1`: assembleDebug + testDebugUnitTest + lintDebug completati.
- Android/JVM: **77 test, 0 failure, 0 error, 0 skipped**:
  - ActionRegistryTest: 2
  - ApkIdentityValidationTest: 4
  - CapabilityRouterTest: 3
  - CoordinateResolverTest: 3
  - FlowTraceTest: 1
  - FlowValidationTest: 4
  - HttpBoundaryTest: 5
  - IdleExecutorPolicyTest: 3
  - LanDiscoveryProtocolTest: 3
  - LanDiscoveryRateLimitTest: 3
  - LanSecureChannelTest: 3
  - LowPowerSessionPolicyTest: 3
  - NetworkAddressPolicyTest: 4
  - NetworkRecoveryPolicyTest: 3
  - RequestScopeTest: 3
  - RpcEndpointServerPolicyTest: 3
  - RpcPolicyTest: 2
  - ScreenSnapshotStoreTest: 3
  - SecurityValidatorsTest: 5
  - TraceJournalTest: 2
  - TransportDiagnosticsTest: 4
  - TransportReconciliationTest: 5
  - UiLoopPolicyTest: 2
  - UpdateValidationTest: 2
  - VersioningTest: 2
- Lint: **0 errori, 1 warning**:
  - targetSdk 35 non è l'ultimo SDK disponibile nell'ambiente.

## APK

- Package: `com.example.androidmcp`.
- versionCode: **13**.
- versionName: **0.8.2**.
- minSdk: **30**.
- targetSdk: **35**.
- APK: `dist/mcp-android-0.8.2-debug.apk`.
- Dimensione: **2,742,975 byte**.
- SHA-256: `77d8aa9c6b92b8d978cfc91bbc1959575b4f7dcb84fc64d1eedb2149e701b089`.
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
  - nonce GCM casuale a 96 bit e replay guard bounded su richieste già autenticate;
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

Implementazione software/build **completa per v0.8.2 secure LAN transport**. La release è pronta per il collaudo fisico, ma broadcast/routing reale, packet capture su rete fisica e consumo batteria non vengono dichiarati verificati finché non vengono provati sul telefono.
