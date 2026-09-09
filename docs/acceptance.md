# Acceptance v0.8.0

## Scope verificato

- Bridge MCP stdio + HTTP autenticato verso il telefono.
- **65 tool MCP** invariati rispetto alla v0.7.
- Dual transport:
  - LAN Wi-Fi RFC1918 su TCP 8765;
  - Tailscale 100.64.0.0/10 su TCP 8765;
  - listener indipendenti, entrambi legati a IPv4 numerici concreti;
  - nessun bind RPC su `0.0.0.0` o `::`;
  - LAN limitata anche ai client della stessa subnet Wi-Fi.
- `TransportManager` event-driven:
  - callback Android Wi-Fi;
  - callback Android VPN;
  - riconciliazione debounced;
  - watchdog di sicurezza ogni 15 minuti mentre START;
  - teardown completo mentre STOP.
- Discovery LAN:
  - UDP 8766;
  - massimo 512 byte;
  - protocollo/versione/nonce strettamente validati;
  - risposta unicast;
  - nessun token o dato del dispositivo;
  - sorgente obbligatoriamente RFC1918 e nella stessa subnet;
  - rate limit 1 risposta/s per IP;
  - massimo 64 sorgenti conservate in RAM.
  - un errore di bind della discovery UDP non spegne il listener RPC LAN TCP.
- Bridge LAN-first:
  - endpoint LAN configurato opzionale;
  - discovery LAN one-shot;
  - candidato discovery autenticato tramite RPC `status`;
  - Tailscale fallback;
  - cache LAN validata con TTL breve per evitare una RPC `status` prima di ogni operazione;
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
- La cache LAN validata riduce le RPC `status` di probe durante sequenze rapide.

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

Nessun telefono fisico è stato collegato durante l'implementazione v0.8.0. Build, test JVM e fixture Node non dimostrano ancora:

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
- consumo batteria LAN-only, Tailscale-only o dual;
- gesture/screenshot/flow sulle app reali;
- Notification Listener, Termux, Shizuku, SAF e updater sul dispositivo.

Questi punti richiedono collaudo end-to-end sul telefono.

## Verifiche automatiche eseguite il 2026-09-08

- Node: v24.19.0.
- `npm.cmd run check`:
  - coerenza versione: OK;
  - **15 test Node, tutti passati**.
- Test Node v0.8 includono:
  - config Tailscale legacy;
  - config dual transport;
  - validazione URL LAN/Tailscale;
  - discovery UDP one-shot;
  - assenza del token nel datagramma discovery;
  - validazione nonce;
  - timeout discovery bounded;
  - LAN-first;
  - fallback Tailscale;
  - auth failure non trasformato in fallback;
  - metodo mutante inviato una sola volta;
  - cache endpoint LAN validata;
  - UI dual transport;
  - discovery dei 65 tool MCP tramite stdio.
- `build-android.ps1`: assembleDebug + testDebugUnitTest + lintDebug completati.
- Android/JVM: **68 test, 0 failure, 0 error, 0 skipped**:
  - ActionRegistryTest: 2
  - ApkIdentityValidationTest: 4
  - CapabilityRouterTest: 3
  - CoordinateResolverTest: 3
  - FlowTraceTest: 1
  - FlowValidationTest: 4
  - HttpBoundaryTest: 4
  - IdleExecutorPolicyTest: 2
  - LanDiscoveryProtocolTest: 2
  - LanDiscoveryRateLimitTest: 2
  - LowPowerSessionPolicyTest: 3
  - NetworkAddressPolicyTest: 4
  - NetworkRecoveryPolicyTest: 3
  - RequestScopeTest: 3
  - RpcEndpointServerPolicyTest: 3
  - RpcPolicyTest: 2
  - ScreenSnapshotStoreTest: 3
  - SecurityValidatorsTest: 5
  - TraceJournalTest: 2
  - TransportDiagnosticsTest: 3
  - TransportReconciliationTest: 4
  - UiLoopPolicyTest: 2
  - UpdateValidationTest: 2
  - VersioningTest: 2
- Lint: **0 errori, 1 warning**:
  - targetSdk 35 non è l'ultimo SDK disponibile nell'ambiente.

## APK

- Package: `com.example.androidmcp`.
- versionCode: **11**.
- versionName: **0.8.0**.
- minSdk: **30**.
- targetSdk: **35**.
- APK: `dist/mcp-android-0.8.0-debug.apk`.
- Dimensione: **2,984,239 byte**.
- SHA-256: `39876ca0699f09e7fd71713d5a6eef15f13a0ba701bc1639e3ad8c7a1be68a77`.
- APK Signature Scheme v2: **valida**.
- Signer: **1**.
- Chiave: RSA 2048, Android Debug.
- Certificato signer SHA-256:
  `7be7c380f31c81c050a86ea8cefd4ec3bd41972ddd864a8edb97b1e20c84823f`.
- Il signer coincide con le release precedenti compatibili con l'updater.

## Sicurezza / trust boundary

- RPC TCP richiede sempre il bearer token da 256 bit.
- Token rotation invalida entrambe le reti.
- Discovery UDP non accede a `SecretStore`.
- Discovery non trasmette token, authorization header o dati privati.
- Una discovery valida non autentica il telefono: il bridge esegue successivamente una RPC autenticata.
- LAN RPC:
  - bind su IPv4 RFC1918 concreto;
  - client obbligatoriamente nella stessa subnet.
- Tailscale RPC:
  - bind su IPv4 100.64.0.0/10 concreto;
  - client ammessi solo in 100.64.0.0/10.
- Nessun listener RPC pubblico.
- Nessun `0.0.0.0` / `::` per TCP RPC.
- Nessun mDNS, multicast lock, port forwarding, UPnP o Funnel.
- Errori 401/auth non vengono reinterpretati come problemi di rete.
- Dopo il dispatch di una RPC mutante, timeout/disconnessione restituiscono esito incerto e non causano replay automatico sull'altro trasporto.
- PIN, biometria, keyguard e finestre protette non vengono aggirati.
- Le altre boundary v0.7 su SAF, shell, Shizuku, flow, trace e updater restano invariate.

## Collaudo fisico richiesto

### LAN-only

1. Spegnere Tailscale sul telefono.
2. Collegare telefono e agente alla stessa Wi-Fi.
3. Premere START su MCP Android.
4. Verificare che l'app mostri un endpoint LAN.
5. Avviare il bridge con `ANDROID_MCP_TRANSPORT=auto` e discovery attiva.
6. Verificare discovery + RPC `status`.
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

Implementazione software/build **completa per v0.8.0 dual transport**. La release è pronta per il collaudo fisico, ma LAN broadcast, routing reale e consumo batteria non vengono dichiarati verificati finché non vengono provati sul telefono.
