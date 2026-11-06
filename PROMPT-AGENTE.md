# PROMPT DA DARE ALL'AGENTE — copia tutto da sotto la riga

Sostituisci INCOLLA_QUI_LA_KEY con i 64 caratteri (app MCP Android > schermata token > Copia), copia tutto il blocco qui sotto e incollalo al tuo agente.

--------------------------------------------------------------------------------

Collegati al mio telefono Android via MCP (server "android").

KEY DEL TELEFONO (segreta: non diffonderla oltre questa chat): INCOLLA_QUI_LA_KEY

Contesto: hai una copia della repo mcp-android. Il bridge è il file server.js dentro la cartella bridge/.
Trovalo in quest'ordine e usa il primo che esiste, senza inventare path:
1. <tua-copia-della-repo>/bridge/server.js (checkout sviluppatore);
2. il percorso del bridge se te l'ho dato io in questo messaggio.
Esegui tutto dalla root di quella repo, sulla TUA macchina (questo messaggio non contiene percorsi assoluti).

SE TELEFONO E PC SONO SULLA STESSA WI-FI (il caso normale: niente IP da configurare):
1. Controlla node >= 22 (node --version); se manca node_modules nella repo, lancia `npm ci --ignore-scripts`.
2. Esegui dalla root della repo, con la key in variabile d'ambiente (mai in riga di comando, per non lasciarla nelle shell history):
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
