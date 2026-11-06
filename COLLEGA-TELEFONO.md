# Collega il telefono Android: copia, incolla, fatto

> **Per l'utente — il prompt pronto è in `PROMPT-AGENTE.md`:**
> aprilo, sostituisci `INCOLLA_QUI_LA_KEY` con i 64 caratteri che trovi nel
> telefono in **app MCP Android > schermata token > Copia negli appunti**,
> copia tutto e incollalo al tuo agente. Al resto pensa lui.
>
> Alternative: genera il messaggio al volo con un doppio clic su
> **`stampa-prompt.cmd`** (oppure, con la key in variabile d'ambiente per non
> lasciarla nella shell history,
> `ANDROID_MCP_TOKEN=<64 hex> node scripts/collega-telefono.mjs --print-prompt`):
> stampa lo stesso testo, portabile su qualsiasi macchina.
>
> (Se l'agente ha accesso a questa repo, può anche caricare la procedura completa
> da `skills/android-connect/SKILL.md`: sotto c'è già tutto il necessario.)

---

## 🤖 Messaggio generato (forma; il testo copia-incollabile sta in `PROMPT-AGENTE.md`)

`--print-prompt` stampa il testo esatto e aggiornato. La forma è questa:

```
Collegati al mio telefono Android via MCP (server "android").

KEY DEL TELEFONO (segreta: non diffonderla oltre questa chat): INCOLLA_QUI_LA_KEY

Contesto: hai una copia della repo mcp-android. Il bridge è il file server.js dentro la cartella bridge/.
Trovalo in quest'ordine e usa il primo che esiste, senza inventare path:
1. <tua-copia-della-repo>/bridge/server.js (checkout sviluppatore);
2. il percorso del bridge se te l'ho dato io in questo messaggio.
Esegui tutto dalla root di quella repo, sulla TUA macchina (questo messaggio non contiene percorsi assoluti).

SE TELEFONO E PC SONO SULLA STESSA WI-FI (il caso normale: niente IP da configurare):
1. Controlla node >= 22 (node --version); se manca node_modules nella repo, lancia `npm ci --ignore-scripts`.
2. Esegui dalla root della repo, con la key in variabile d'ambiente (mai in riga di comando):
     ANDROID_MCP_TOKEN=INCOLLA_QUI_LA_KEY node scripts/collega-telefono.mjs --write-host auto
   Lo script trova il telefono da solo (discovery LAN + handshake), lo verifica
   con una `status` reale, scrive mcp-config.android.json e fonde la entry nel
   config del tuo host (backup .bak-timestamp, merge senza cancellare gli altri server).
   Se --write-host auto fallisce (zero o più config trovati), specifica il tuo:
   claude-desktop, cursor, vscode oppure openclaw.
3. Verifica: ricontrolla il file scritto e chiama android_status (deve rispondere il telefono).
4. Riferisci in chiaro: file toccato, backup, esito (via lan|tailscale), SU QUALE MACCHINA hai operato.
   Poi ricordami di riavviare/ricaricare il client MCP. Se qualcosa fallisce, incolla l'errore esatto e fermati.

SE IL TELEFONO NON È SULLA STESSA WI-FI: fermati e chiedimi l'IP Tailscale mostrato
dal telefono (100.x.y.z), poi rilancia aggiungendo --tailscale-url http://<ip>:8765.
Non improvvisare mai URL o porte diverse da 8765.

Regole: non stampare MAI la key per intero (solo primi4…ultimi4), non committare MAI
file che la contengono, non cambiare impostazioni del telefono. Se la key smette di
funzionare è stata ruotata: chiedimi di ricopiarla dal telefono.
```

---

## Note per l'utente (non servono all'agente)

- **Prerequisiti sul telefono:** app installata, servizio Accessibilità attivo,
  **Avvia controllo remoto** premuto. Stessa Wi-Fi = zero configurazione.
- **Se cambi key** (pulsante Ruota nell'app): rigenera il messaggio e ripeti.
- **Dettagli tecnici** (formati config di ogni host, policy segreti, limiti):
  `skills/android-connect/SKILL.md`.
