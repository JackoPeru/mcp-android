# Funzioni v0.9: acquisizione, telefono, salute e relay

Questa release aggiunge 35 strumenti MCP (102 totali) mantenendo intatti LAN,
Tailscale, discovery, cifratura e tutti i tool esistenti.

## Fotocamera (`android_photo_capture`, `android_torch_set/status`)

- CameraX con anteprima **visibile**: nessuna cattura nascosta, come richiesto
  dalle regole Android in primo piano.
- Parametri: `lens` (`back|front`), `flash` (`auto|on|off`), `maxWidth`
  (0 = nativa, altrimenti ridimensiona in JPEG), `quality` 40..100,
  `timeoutMs` 1..20 s.
- L'immagine torna **direttamente come contenuto immagine** (JPEG/PNG validato
  per magic bytes); per file oltre il limite inline il tool indica di usare
  `android_media_read`.
- `android_torch_set` usa `CameraManager.setTorchMode` sul retro con flash:
  nessuna fotocamera aperta, nessun permesso aggiuntivo oltre CAMERA.
- Errori espliciti: `CAMERA_PERMISSION_DENIED`, `CAMERA_UNAVAILABLE`,
  `CAMERA_TIMEOUT`, `CAMERA_CAPTURE_FAILED`, `BUSY`.

## Audio (`android_audio_record_*`)

- `MediaRecorder` in un **foreground service di tipo `microphone`** con
  notifica persistente e azione Stop; la registrazione parte da una schermata
  visibile (`AudioCaptureActivity`) con timer e pulsante di stop.
- Formati: `m4a`/`aac` (MPEG-4 AAC) oppure `ogg`/`opus`.
- Limiti: `maxDurationSec` 1..3600 (auto-stop), `bitrateKbps` 16..320.
- Le registrazioni interrotte vengono finalizzate e marcate `interrupted`;
  `audio_record_status` riporta stato, file, durata e ultimo errore.
- Trasferimento: `android_media_list` → `android_media_read` a blocchi da
  massimo 256 KiB fino a `eof:true` (base64).

## Schermo (`android_screen_record_*`)

- `MediaProjection` con **consenso di sistema obbligatorio** richiesto da una
  activity visibile, service di tipo `mediaProjection` avviato prima di
  ottenere la proiezione (requisito Android 14).
- Parametri: `maxDurationSec` 1..1800, `fps` 10..60, `bitrateKbps` 500..20000,
  `maxLongEdge` 0..2160 (dimensioni pari, H.264 in MP4).
- Stop manuale, auto-stop a scadenza, rilascio completo di VirtualDisplay,
  recorder e proiezione.
- File grandi: stesso schema a blocchi di `media_read`.

## Catture (`android_media_*`)

- Storage privato dell'app (`files/captures`), mai memoria condivisa: i tool
  `file_*` non possono raggiungerlo.
- Nomi generati dal telefono e validati con regex; rifiuto di traversal.
- Retention automatica: 24 ore e massimo 40 file per tipo, pulizia a ogni
  cattura/status.

## SMS, chiamate, contatti

- **Conferma esplicita monouso**: `sms_prepare`/`call_prepare` aprono una
  schermata con destinatario e testo; solo dopo "Conferma" il relativo
  `confirmationId` (16 hex, scade in 2 minuti) viene accettato **una sola
  volta** da `sms_send`/`call`, vincolato a destinatario e corpo esatti.
- Nessun retry automatico: `sms_send` consegna una sola volta a `SmsManager`
  (multipart), le chiamate usano `ACTION_CALL` solo con CALL_PHONE.
- `android_call_dial` e `android_sms_open_composer` usano intent di sistema e
  non richiedono permessi pericolosi.
- `android_sms_list` è opzionale e richiede READ_SMS (Android può limitarlo);
  i testi sono dati non attendibili.
- `android_contacts_search` richiede READ_CONTACTS e restituisce nome/numero.

## Health Connect (`android_health_*`) — sola lettura

- Tipi: `steps`, `heart_rate`, `resting_heart_rate`, `sleep`, `exercise`,
  `distance`, `total_calories`, `active_calories`, `oxygen_saturation`,
  `weight`, `height`, `vo2max`.
- Permessi **per categoria**; `health_status` elenca quelle concesse,
  `health_request_permissions` apre la schermata ufficiale.
- `health_read` accetta istanti ISO-8601, massimo 366 giorni e 1000 record;
  `health_aggregate` calcola total/avg/min/max on-device sullo stesso set.
- Nessuna scrittura o cancellazione, nessun dato nei log.

## Sensori e sistema

- `android_location_get`: posizione una tantum (`getCurrentLocation`), nessun
  tracking in background, nessun geocoding.
- `android_speak/speak_stop/tts_status`: TTS con stato reale dell'engine.
- `android_alarm_set`/`android_timer_set`: intent `AlarmClock` ufficiali.
- `android_current_app`: package in primo piano via Accessibilità.
- `android_network_status`: trasporti, validazione, metering, banda, IPv4, VPN.

## Relay self-hosted (opzionale, additivo)

Obiettivo: raggiungere il telefono dietro NAT senza Tailscale e senza port
forwarding, con il relay **incapace di leggere i comandi**.

- Il telefono apre una connessione **in uscita** TLS verso il relay e si
  autentica con un pairing secret separato (64 hex) e una prova HMAC; il relay
  risponde con la propria prova (autenticazione mutua).
- Room + nonce per sessione: `session = HMAC(MCP_TOKEN, ...)` calcolata **solo
  dai due peer**. Il relay non riceve mai il token e vede solo envelope
  AES-256-GCM in base64 (dominio `mcp-android-relay-v1`, distinto da LAN).
- Binding `requestNonce` su ogni risposta, anti-replay lato telefono, TLS
  obbligatorio (`tls://`), riconnessione con backoff esponenziale 1→60 s.
- Semantica invariata per le azioni mutanti: un timeout o una caduta di rete
  produce `outcome_unknown`, mai un replay automatico.
- LAN e Tailscale restano invariati e preferiti: il relay si usa solo se
  configurato e gli altri non rispondono (`auto`), oppure con
  `ANDROID_MCP_TRANSPORT=relay`.

### Setup relay

```bash
# 1) Segreto di pairing (una volta)
openssl rand -hex 32

# 2) Certificato TLS (esempio self-signed; usa il tuo CA in produzione)
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout relay-key.pem -out relay-cert.pem -subj "/CN=relay.example.com"

# 3) Avvio relay
RELAY_SECRET=<64 hex> \
RELAY_TLS_CERT=relay-cert.pem RELAY_TLS_KEY=relay-key.pem \
RELAY_HOST=0.0.0.0 RELAY_PORT=9443 \
node relay/relay-server.mjs
```

Nel telefono (tab **Funzioni → Relay self-hosted**): URL `tls://relay.example.com:9443`,
room e pairing secret. Sul PC:

```text
ANDROID_MCP_RELAY_URL=tls://relay.example.com:9443
ANDROID_MCP_RELAY_ROOM=<stessa room>
ANDROID_MCP_RELAY_SECRET=<64 hex>
ANDROID_MCP_TRANSPORT=auto        # oppure relay
ANDROID_MCP_RELAY_CA=/path/relay-cert.pem   # solo se self-signed
```

## Permessi Android richiesti

| Funzione | Permesso / consenso |
|---|---|
| Foto, torcia | `CAMERA` |
| Audio | `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` |
| Schermo | consenso MediaProjection per sessione + `FOREGROUND_SERVICE_MEDIA_PROJECTION` |
| SMS invio | `SEND_SMS` + conferma in-app |
| SMS lettura | `READ_SMS` (opzionale) |
| Chiamate | `CALL_PHONE` + conferma in-app |
| Contatti | `READ_CONTACTS` |
| Posizione | `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` |
| Salute | permessi `health.READ_*` per categoria |
| Sveglie/timer | `SET_ALARM` (normale) |
| Relay | TLS + pairing secret; nessun permesso aggiuntivo |

## Limiti dichiarati (non verificati su hardware)

- Acquisizioni, SMS/chiamate, Health Connect, GPS, torcia e TTS sono compilati
  e coperti da test di logica, ma **non** sono stati provati su un telefono
  fisico in questa sessione: niente dati reali, niente foto reali.
- MediaProjection può comportarsi diversamente su alcuni OEM; il consenso
  resta obbligatorio.
- Health Connect 1.0.0-alpha11 è l'ultima versione compatibile con
  `compileSdk 35`; passare a 1.1.0 richiede SDK 36 + AGP 8.9.1.
- Il relay è testato end-to-end in modalità loopback plaintext (solo test) e
  con TLS nei deployment reali; non è stato provato attraverso NAT reale.
