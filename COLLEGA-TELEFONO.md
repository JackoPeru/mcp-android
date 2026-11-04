# Collega il telefono Android: prompt pronto per l'agente

> **Per l'utente — ti basta questo:** copia tutto il blocco qui sotto, incollalo
> al tuo agente, sostituisci `INCOLLA_QUI_LA_KEY` con i 64 caratteri che trovi
> nel telefono in **app MCP Android > schermata token > Copia negli appunti**,
> e invia. Al resto pensa l'agente: non devi configurare IP, file o variabili.

---

## 🤖 Prompt da incollare all'agente (con la key)

```
Collega il mio telefono Android a te come strumento MCP.

KEY DEL TELEFONO: INCOLLA_QUI_LA_KEY

Contesto: sei nella repo mcp-android (cartella col bridge in bridge/server.js).
Obiettivo: alla fine devi potermi controllare il telefono via tool android_*.

Esegui in autonomia, senza chiedermi niente salvo i casi sotto. Procedi così:

1. ESTRAI la key dal messaggio (64 caratteri esadecimali). Se manca o è
   malformata, dimmelo e fermati: è l'unica cosa che devo darti io.

2. PREPARA l'ambiente: se manca node_modules, lancia `npm ci --ignore-scripts`.

3. AUTO-CONFIGURA lanciando dalla root della repo:
     node scripts/collega-telefono.mjs --key <la-key>
   Lo script prova da solo la discovery LAN (stessa Wi-Fi, nessun IP da
   configurare), verifica il telefono con una chiamata `status` reale e scrive
   mcp-config.android.json già pronto all'uso.

4. SE LO SCRIPT FALLISCE per telefono irraggiungibile, fai UNA sola domanda:
   "Telefono e PC sono sulla stessa Wi-Fi con Avvia controllo remoto attivo?
   Altrimenti dammi l'IP Tailscale mostrato dal telefono (100.x.y.z)."
   Poi rilancia con --tailscale-url http://<ip-dato>:8765. Non improvvisare
   URL o porte diverse da 8765.

5. COLLEGA IL CLIENT MCP che stai usando (opencode, Claude Desktop, ecc.):
   registra il server "android" usando mcp-config.android.json generato
   (command node, args bridge/server.js, env incluso). Se non puoi scrivere
   tu la configurazione del client, dimmi esattamente dove incollare il file.

6. VERIFICA chiamando android_status: deve rispondere il telefono.
   Poi dammi conferma breve: "Telefono collegato via <lan|tailscale>".

REGOLE DI SICUREZZA (tassative):
- Non mostrare MAI la key per intero: solo mascherata (primi 4…ultimi 4).
- Non committare MAI file con la key (mcp-config.android.json è gitignored).
- Non inviare MAI la key in chat, log o tool di terze parti.
```

---

## Note per l'utente (non servono all'agente)

- **Prerequisiti sul telefono:** app installata, servizio Accessibilità attivo,
  **Avvia controllo remoto** premuto. Stessa Wi-Fi di telefono e PC = zero
  configurazione oltre la key.
- **Fuori casa:** serve anche l'IP Tailscale del telefono (lo mostra l'app);
  l'agente te lo chiede solo in quel caso.
- **Se cambi key** (pulsante Ruota nell'app): ripeti il prompt con la nuova key,
  oppure lancia `node scripts/collega-telefono.mjs --check` dopo aver
  aggiornato la key: riverifica senza riscrivere.
