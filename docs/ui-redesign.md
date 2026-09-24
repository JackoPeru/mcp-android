> **Documento storico (redesign v0.8.2/v0.8.3):** conservato come riferimento; lo stato verificato corrente è in `docs/acceptance.md` (v0.8.19).

# Nuova interfaccia MCP Android

Redesign nativo sviluppato su v0.8.2 e integrato nel progetto v0.8.3 insieme al flusso di aggiornamento modificato dall'altro agente. Nessuna nuova dipendenza UI.

## Dove trovare i comandi

- **Panoramica**: avvio e interruzione della sessione, stato separato di Wi-Fi e Tailscale, dettagli delle connessioni e indicazioni per configurare gli accessi.
- **Accessi**: Accessibilità, notifiche e media, cartelle autorizzate. Termux e Shizuku sono nella sezione espandibile Strumenti avanzati.
- **Impostazioni**: token di associazione e revoca, aggiornamenti, impostazioni app e batteria.

Tema scuro con accenti verde acqua, schede arrotondate, icone vettoriali disegnate con API native, pulsanti con feedback al tocco. Contenuti scorrevoli, larghezza adattiva, navigazione persistente e testi che rispettano la dimensione del carattere di sistema.

Gli stati visualizzati derivano dal servizio e dai permessi Android: una sessione aperta non viene presentata come prova che un agente sia collegato. Mostrare o rigenerare il token ferma il servizio; schermata e dialogo conservano FLAG_SECURE.

> **Errata:** dalla v0.8.4 la schermata token ha il bottone **Copia negli appunti** — la frase precedente che lo escludeva non è più valida.

## Installazione e verifica

APK personale firmato della versione combinata: `dist/mcp-android-0.8.3-ui-preview.apk`.
Checksum SHA-256: `f125c702268c4d529eff3d3d286254514d7070740dbbdbc3f2571fcd0572d175`.

- `assembleDebug` e `lintDebug`: completati sia sul redesign isolato sia sulla versione combinata v0.8.3. Zero errori lint; restano avvisi su target SDK e risorse testuali precedenti non più usate.
- `npm run check`: 28 test passati sullo snapshot del redesign v0.8.2, prima delle ulteriori modifiche dell'altro agente all'updater.
- Firma APK: verifica v2 riuscita.
- Revisione indipendente dei file del redesign: **ship**, nessuna regressione materiale rilevata. Non estesa alle successive modifiche dell'altro agente all'updater.
- Nessuna verifica visiva su emulatore o dispositivo, secondo la richiesta dell'utente. Font ingranditi, scorrimento e insets sono gestiti dal codice; il comportamento reale sul telefono resta da verificare.

Le modifiche UI riguardano `MainActivity.java`, il nuovo `UiKit.java` e le nuove risorse `ui_theme.xml` e `ui_ids.xml`. Backend, trasporti, versioni e workflow di release non sono stati modificati da questa sessione. L'altro agente ha integrato la UI nel commit `f2c5a26` e modificato l'updater nel commit `37c959f`: entrambe le integrazioni sono state conservate. Nessun commit o push effettuato da questa sessione.
