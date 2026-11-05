package com.example.androidmcp;

import java.util.List;

/** Chat message + link verification for pairing an agent, Desktop-AI console style.
 *  The generated message is portable by design: it never contains absolute
 *  file paths of any PC (the agent runs on another machine and resolves the
 *  bridge checkout itself). Only network URLs (LAN/Tailscale) and the secret
 *  token are real values. Pure logic; no Android dependencies. */
public final class AgentLink {
    public static final String SERVER_KEY = "android";
    public static final String REPO_URL = "https://github.com/JackoPeru/mcp-android";
    /** A recent RPC proves the agent got through even if the dialog was reopened. */
    public static final long RECENT_RPC_WINDOW_MS = 5 * 60 * 1000L;

    private AgentLink() { }

    /** Full copy-paste chat message. Empty URL lists mean the session never exposed endpoints. */
    public static String buildMessage(String serverKey, String token,
                                      List<String> lanUrls, List<String> tailscaleUrls) {
        if (token == null || token.isEmpty()
                || ((lanUrls == null || lanUrls.isEmpty()) && (tailscaleUrls == null || tailscaleUrls.isEmpty()))) {
            return buildOfflineMessage(serverKey);
        }
        StringBuilder urls = new StringBuilder();
        if (lanUrls != null) for (String url : lanUrls) urls.append("   - ").append(url).append('\n');
        if (tailscaleUrls != null) for (String url : tailscaleUrls) urls.append("   - ").append(url).append('\n');
        return "Collegati al mio telefono Android via MCP (server \"" + serverKey + "\").\n"
                + "\n"
                + "KEY DEL TELEFONO (segreta: non diffonderla oltre questa chat): " + token + "\n"
                + "\n"
                + "SE GIRI SULLA STESSA WI-FI DEL TELEFONO (il caso normale):\n"
                + "1. Procurati il bridge (una sola volta): git clone " + REPO_URL
                + " — serve node >= 22, poi `npm ci --ignore-scripts` dentro la repo.\n"
                + "2. Prova questi URL nell'ordine, usa il primo che risponde (LAN prima, Tailscale poi):\n"
                + urls
                + "3. Esegui dalla root della repo:\n"
                + "     node scripts/collega-telefono.mjs --key " + token + " --write-host auto\n"
                + "   Lo script trova il telefono da solo (discovery LAN + handshake), lo verifica\n"
                + "   con una `status` reale, scrive mcp-config.android.json e fonde la entry nel\n"
                + "   config del tuo host (backup .bak-timestamp, merge senza cancellare gli altri server).\n"
                + "   Se --write-host auto fallisce (zero o più config trovati), specifica il tuo:\n"
                + "   claude-desktop, cursor, vscode oppure openclaw.\n"
                + "4. Verifica: ricontrolla il file scritto e chiama android_status (deve rispondere il telefono).\n"
                + "   Se non risponde, dimmi di premere «Avvia controllo remoto» sul telefono e riprova.\n"
                + "5. Riferisci in chiaro: file toccato, backup, esito (via lan|tailscale), SU QUALE MACCHINA hai operato.\n"
                + "   Poi ricordami di riavviare/ricaricare il client MCP. Se qualcosa fallisce, incolla l'errore esatto e fermati.\n"
                + "\n"
                + "SE IL TELEFONO NON È SULLA STESSA WI-FI: gli URL Tailscale qui sopra valgono anche da fuori casa —\n"
                + "rilancia il punto 3 aggiungendo l'URL Tailscale con --tailscale-url <url>.\n"
                + "Non improvvisare mai URL o porte diverse da 8765.\n"
                + "\n"
                + "Regole: non stampare MAI la key per intero (solo primi4…ultimi4), non committare MAI\n"
                + "file che la contengono, non cambiare impostazioni del telefono. Se la key smette di\n"
                + "funzionare è stata ruotata: chiedimi di ricopiarla dal telefono.";
    }

    public static String buildOfflineMessage(String serverKey) {
        return "Collegamento al telefono Android via MCP (server \"" + serverKey + "\") non ancora attivo.\n"
                + "\n"
                + "Chiedi all'operatore di aprire MCP Android e premere «Avvia controllo remoto»,\n"
                + "poi fatti ridare il messaggio aggiornato: conterrà token e indirizzi.";
    }

    public static final class VerifyResult {
        public final boolean ok;
        public final String text;
        VerifyResult(boolean ok, String text) { this.ok = ok; this.text = text; }
    }

    /** recentlyTalked covers a reopened dialog: the baseline resets, the last RPC does not. */
    public static VerifyResult verify(boolean sessionRunning, long rpcsAtOpen, long rpcsNow,
                                      long lastRpcAgeMs, String preferredTransport) {
        if (!sessionRunning) {
            return new VerifyResult(false,
                    "Sessione non attiva: premi «Avvia controllo remoto» qui in app, poi ripremi verifica.");
        }
        boolean fresh = rpcsNow > rpcsAtOpen
                || (lastRpcAgeMs >= 0 && lastRpcAgeMs <= RECENT_RPC_WINDOW_MS);
        if (fresh) {
            return new VerifyResult(true,
                    "Agente collegato ✓ (ultima richiesta " + ageText(lastRpcAgeMs)
                            + ", via " + networkLabel(preferredTransport) + ").");
        }
        return new VerifyResult(false,
                "Nessuna richiesta ancora: incolla il messaggio al tuo agente e premi verifica dopo la sua prima risposta (android_status).");
    }

    static String networkLabel(String preferredTransport) {
        if ("lan".equals(preferredTransport)) return "Wi-Fi locale";
        if ("tailscale".equals(preferredTransport)) return "Tailscale";
        return "rete disponibile";
    }

    static String ageText(long ageMs) {
        if (ageMs < 0) return "poco fa";
        if (ageMs < 5000) return "pochi secondi fa";
        if (ageMs < 60000) return (ageMs / 1000) + "s fa";
        return (ageMs / 60000) + "m fa";
    }
}
