package com.example.androidmcp;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

import java.io.File;

/** UI only: every control delegates to the existing permission and runtime APIs. */
public final class MainActivity extends Activity {
    private UiKit ui;
    private FileRootStore roots;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private final ScrollView[] pages = new ScrollView[3];
    private final LinearLayout[] tabs = new LinearLayout[3];
    private final TextView[] tabLabels = new TextView[3];
    private final FrameLayout[] tabIcons = new FrameLayout[3];
    private final String[] tabNames = {"Panoramica", "Accessi", "Impostazioni"};
    private final String[] tabSymbols = {"home", "shield", "settings"};
    private int selectedTab;
    private boolean advancedExpanded, startAfterPermission, accessibilityAllowed, notificationsAllowed;
    private int rootCount;
    private TextView heroBadge, heroTitle, heroDescription, errorMessage, lanValue, tailscaleValue;
    private TextView preferredLabel, accessibilityBadge, notificationBadge, folderBadge, updateStatus;
    private TextView updateLatest, updateNotes, updateProgressLabel;
    private TextView setupDescription, advancedLabel;
    private Button sessionButton, setupButton, updateDownloadButton, updateInstallButton;
    private ProgressBar updateProgress;
    private LinearLayout rootList, advancedBody;
    private String transportDetails = "";
    private UpdateManager.Release updateRelease;
    private File updateApk;

    private final UpdateManager.Callback updateCallback = new UpdateManager.Callback() {
        @Override public void onState(String message) { setUpdateStatus(message); }
        @Override public void onNoUpdate(String version) {
            updateRelease = null; updateApk = null;
            hideUpdateProgress();
            setUpdateStatus("Sei aggiornato alla versione " + version + ".");
            renderUpdateControls();
        }
        @Override public void onUpdateAvailable(UpdateManager.Release release, File downloadedApk) {
            updateRelease = release; updateApk = downloadedApk;
            if (downloadedApk == null) hideUpdateProgress();
            setUpdateStatus(downloadedApk == null
                    ? "Aggiornamento disponibile. Scarica l'APK dentro l'app."
                    : "Aggiornamento già scaricato e verificato. Puoi installarlo.");
            renderUpdateControls();
        }
        @Override public void onDownloadProgress(UpdateManager.Release release, long downloadedBytes, long totalBytes) {
            if (updateRelease == null || !updateRelease.version.equals(release.version)) updateRelease = release;
            renderDownloadProgress(downloadedBytes, totalBytes);
        }
        @Override public void onDownloadReady(UpdateManager.Release release, File apk) {
            updateRelease = release; updateApk = apk;
            setUpdateStatus("Download completato. APK verificato e pronto per l'installazione.");
            renderUpdateControls();
        }
        @Override public void onError(String message) {
            setUpdateStatus(getString(R.string.update_error, message));
            hideUpdateProgress();
            renderUpdateControls();
        }
    };
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            renderStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        setTheme(R.style.ui_app_theme);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ui = new UiKit(this);
        roots = new FileRootStore(this);
        selectedTab = state == null ? 0 : Math.max(0, Math.min(2, state.getInt("ui.tab", 0)));
        advancedExpanded = state != null && state.getBoolean("ui.advanced", false);
        startAfterPermission = state != null && state.getBoolean("ui.pendingStart", false);
        LinearLayout shell = ui.column(); shell.setBackgroundColor(UiKit.BG);
        setContentView(shell);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            shell.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        shell.addView(topBar(), new LinearLayout.LayoutParams(-1, -2));
        FrameLayout content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout overview = page(content, 0, R.id.ui_overview_scroll);
        LinearLayout access = page(content, 1, R.id.ui_access_scroll);
        LinearLayout settings = page(content, 2, R.id.ui_settings_scroll);
        buildOverview(overview); buildAccess(access); buildSettings(settings);
        shell.addView(bottomBar(), new LinearLayout.LayoutParams(-1, -2));
        showTab(selectedTab);
    }

    private View topBar() {
        LinearLayout bar = ui.row(); bar.setPadding(ui.dp(22), ui.dp(14), ui.dp(22), ui.dp(12));
        FrameLayout mark = new FrameLayout(this); mark.setBackground(ui.shape(UiKit.SOFT, 0, 14));
        mark.addView(ui.icon("phone", UiKit.ACCENT, 24), new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        bar.addView(mark, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout title = ui.column();
        title.addView(ui.text("MCP Android", 18, UiKit.TEXT, true));
        TextView caption = ui.text("IL TUO CONTROLLO, OVUNQUE", 10, UiKit.MUTED, true); caption.setLetterSpacing(.06f);
        ui.add(title, caption, 5);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1); titleParams.leftMargin = ui.dp(12);
        bar.addView(title, titleParams);
        TextView version = ui.badge("v" + installedVersion()); version.setTextColor(UiKit.MUTED); version.setBackground(ui.shape(UiKit.CARD, UiKit.BORDER, 10));
        bar.addView(version, new LinearLayout.LayoutParams(-2, -2));
        return bar;
    }

    private LinearLayout page(FrameLayout parent, int index, int id) {
        ScrollView scroll = new ScrollView(this); scroll.setId(id);
        scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        FrameLayout centering = new FrameLayout(this);
        LinearLayout body = ui.column(); body.setPadding(0, ui.dp(10), 0, ui.dp(24));
        centering.addView(body, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        centering.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.max(1, Math.min(ui.dp(640), right - left - ui.dp(40)));
            if (body.getLayoutParams().width != width) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) body.getLayoutParams();
                params.width = width; body.setLayoutParams(params);
            }
        });
        scroll.addView(centering, new ScrollView.LayoutParams(-1, -2));
        parent.addView(scroll, new FrameLayout.LayoutParams(-1, -1)); pages[index] = scroll;
        return body;
    }

    private void buildOverview(LinearLayout body) {
        LinearLayout hero = ui.card(body);
        hero.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xff19372f, UiKit.CARD}));
        ((android.graphics.drawable.GradientDrawable)hero.getBackground()).setCornerRadius(ui.dp(28));
        ((android.graphics.drawable.GradientDrawable)hero.getBackground()).setStroke(ui.dp(1), 0xff315248);
        LinearLayout top = ui.row();
        heroBadge = ui.badge("SESSIONE SPENTA");
        top.addView(heroBadge, new LinearLayout.LayoutParams(-2, -2));
        View space = new View(this); top.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(ui.icon("shield", UiKit.ACCENT, 28), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        hero.addView(top);
        heroTitle = ui.text("Pronto quando\nlo sei tu.", 34, UiKit.TEXT, true);
        heroTitle.setLetterSpacing(-.025f); ui.add(hero, heroTitle, 23);
        heroDescription = ui.text("Scegli cosa condividere, poi apri una sessione per il tuo agente.", 15, 0xffc0d4cf, false);
        ui.add(hero, heroDescription, 14);
        sessionButton = ui.button("Avvia controllo remoto", true, () -> safely(() -> {
            if (McpForegroundService.sessionEnabled()) { McpForegroundService.stopNow(); renderStatus(); }
            else startRemoteControl();
        }));
        ui.add(hero, sessionButton, 22);
        TextView note = ui.text("Decidi tu quando iniziare e quando fermarti.", 12, UiKit.MUTED, false);
        note.setGravity(Gravity.CENTER); ui.add(hero, note, 12);
        errorMessage = ui.text("", 14, UiKit.DANGER, false);
        errorMessage.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        errorMessage.setBackground(ui.shape(UiKit.DANGER_BG, 0, 16)); errorMessage.setVisibility(View.GONE);
        ui.add(body, errorMessage, 14);
        section(body, "Le tue connessioni", "Due modi per raggiungere il telefono.");
        LinearLayout lan = ui.card(body);
        ui.heading(lan, "wifi", "Wi-Fi locale", "Per il tuo agente nella stessa rete.");
        lanValue = ui.text("Non disponibile", 14, UiKit.MUTED, true); ui.add(lan, lanValue, 15);
        LinearLayout remote = ui.card(body);
        ui.heading(remote, "remote", "Tailscale", "Per accedere anche quando sei fuori casa.");
        tailscaleValue = ui.text("Non disponibile", 14, UiKit.MUTED, true); ui.add(remote, tailscaleValue, 15);
        preferredLabel = ui.text("Nessuna connessione disponibile", 13, UiKit.MUTED, false); ui.add(body, preferredLabel, 14);
        ui.add(body, ui.button("Dettagli connessione", false, () -> dialog("Stato connessioni", transportDetails)), 12);
        LinearLayout setup = ui.card(body);
        ui.heading(setup, "shield", "Fallo tuo", "Abilita solo gli accessi che ti servono.");
        setupDescription = ui.text("", 14, UiKit.MUTED, false); ui.add(setup, setupDescription, 14);
        setupButton = ui.button("Configura gli accessi", false, () -> showTab(1)); ui.add(setup, setupButton, 14);
    }

    private void buildAccess(LinearLayout body) {
        pageHeading(body, "I tuoi accessi", "Il telefono resta tuo.\nScegli cosa può usare l'agente.");
        LinearLayout screen = ui.card(body);
        ui.heading(screen, "phone", "Schermo e gesti", "Consenti all'agente di leggere lo schermo, toccare, scorrere e scrivere.");
        accessibilityBadge = ui.badge("Da autorizzare"); ui.add(screen, accessibilityBadge, 14);
        ui.add(screen, ui.button("Gestisci Accessibilità", false, () -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)), 14);
        LinearLayout notifications = ui.card(body);
        ui.heading(notifications, "bell", "Notifiche e media", "Accesso opzionale alle notifiche e ai controlli di riproduzione.");
        notificationBadge = ui.badge("Opzionale"); ui.add(notifications, notificationBadge, 14);
        ui.add(notifications, ui.button("Gestisci notifiche", false, () -> openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)), 14);
        section(body, "File condivisi", "Accesso diretto ai file, senza aprire un file manager.");
        folderBadge = ui.text("Nessuna cartella autorizzata", 13, UiKit.MUTED, false); ui.add(body, folderBadge, 12);
        rootList = ui.column(); body.addView(rootList);
        ui.add(body, ui.button("Aggiungi una cartella", true, this::chooseFolder), 14);
        ui.add(body, ui.text("Android ti farà scegliere una cartella. L'agente potrà usare solo gli accessi concessi dal sistema.", 13, UiKit.MUTED, false), 12);
        LinearLayout advanced = ui.card(body);
        LinearLayout header = ui.row();
        header.addView(ui.icon("terminal", UiKit.MUTED, 24), new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        advancedLabel = ui.text("Strumenti avanzati  +", 16, UiKit.TEXT, true);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1); textParams.leftMargin = ui.dp(12); header.addView(advancedLabel, textParams);
        header.setMinimumHeight(ui.dp(48)); header.setFocusable(true); header.setBackground(ui.ripple(UiKit.CARD, 0, 12));
        header.setOnClickListener(v -> { advancedExpanded = !advancedExpanded; renderAdvanced(); });
        buttonSemantics(header); advanced.addView(header);
        advancedBody = ui.column(); advanced.addView(advancedBody);
        ui.add(advancedBody, ui.text("Shell opzionali, con permessi separati. Non sono necessarie per schermo e file.", 14, UiKit.MUTED, false), 14);
        ui.add(advancedBody, ui.button("Autorizza Termux", false, () -> safely(() -> {
            String permission = "com.termux.permission.RUN_COMMAND";
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) toast("Permesso Termux già concesso.");
            else requestPermissions(new String[]{permission}, 40);
        })), 14);
        ui.add(advancedBody, ui.text("Usa l'ambiente utente di Termux, che deve essere installato.", 13, UiKit.MUTED, false), 8);
        ui.add(advancedBody, ui.button("Autorizza Shizuku", false, () -> {
            try { toast(ShizukuBridge.requestPermission(this) ? "Permesso Shizuku già concesso." : "Richiesta inviata a Shizuku."); }
            catch (ApiException e) { dialog("Shizuku non disponibile", "Avvia Shizuku e verifica i suoi permessi.\n\nDettaglio: " + e.code); }
        }), 14);
        ui.add(advancedBody, ui.text("Usa la modalità shell o root scelta in Shizuku. Nessun passaggio automatico tra backend.", 13, UiKit.MUTED, false), 8);
        renderAdvanced();
    }

    private void buildSettings(LinearLayout body) {
        pageHeading(body, "Tutto al suo posto", "Collega il tuo agente e mantieni\nl'app pronta per quando serve.");
        LinearLayout pairing = ui.card(body);
        ui.heading(pairing, "key", "Collega il tuo agente", "Il token è la chiave di accesso al telefono. Inseriscilo soltanto nella configurazione privata del tuo agente.");
        ui.add(pairing, ui.button("Mostra token di accesso", true, () -> safely(this::showToken)), 18);
        ui.add(pairing, ui.text("Mostrare il token interrompe la sessione attiva.", 12, UiKit.MUTED, false), 10);
        Button rotate = ui.button("Revoca e genera nuovo token", false, () -> new AlertDialog.Builder(this)
                .setTitle("Sostituire il token?")
                .setMessage("Il controllo remoto si fermerà. Gli agenti configurati con il vecchio token non potranno più accedere.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Genera nuovo", (dialog, which) -> safely(() -> {
                    McpForegroundService.stopNow(); SecretStore.rotate(this); renderStatus();
                    toast("Token sostituito. Aggiorna la configurazione del tuo agente.");
                })).show());
        ui.paintButton(rotate, UiKit.RAISED, UiKit.DANGER); ui.add(pairing, rotate, 12);
        LinearLayout updates = ui.card(body);
        ui.heading(updates, "update", "Sempre aggiornato", "Versione installata " + installedVersion());
        updateStatus = ui.text("Controllo automatico una volta al giorno.", 14, UiKit.MUTED, false); ui.add(updates, updateStatus, 14);
        updateLatest = ui.text("", 12, UiKit.MUTED, true); updateLatest.setVisibility(View.GONE); ui.add(updates, updateLatest, 10);
        updateNotes = ui.text("", 13, UiKit.TEXT, false); updateNotes.setVisibility(View.GONE); ui.add(updates, updateNotes, 10);
        updateProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateProgress.setMax(100); updateProgress.setProgress(0); updateProgress.setVisibility(View.GONE); ui.add(updates, updateProgress, 14);
        updateProgressLabel = ui.text("", 12, UiKit.MUTED, false); updateProgressLabel.setVisibility(View.GONE); ui.add(updates, updateProgressLabel, 8);
        ui.add(updates, ui.button("Controlla aggiornamenti", false, () -> UpdateManager.check(this, true, updateCallback)), 16);
        updateDownloadButton = ui.button("Scarica aggiornamento", true, () -> {
            if (updateRelease != null) UpdateManager.download(this, updateRelease, updateCallback);
        });
        updateDownloadButton.setVisibility(View.GONE); ui.add(updates, updateDownloadButton, 10);
        updateInstallButton = ui.button("Installa aggiornamento", true, () -> {
            if (updateRelease != null && updateApk != null) {
                UpdateManager.installDownloaded(this, updateRelease, updateApk, updateCallback);
            }
        });
        updateInstallButton.setVisibility(View.GONE); ui.add(updates, updateInstallButton, 10);
        LinearLayout battery = ui.card(body);
        ui.heading(battery, "settings", "Continuità in background", "Se Android sospende l'app, controlla le impostazioni della batteria per mantenerla disponibile durante una sessione.");
        ui.add(battery, ui.button("Apri impostazioni app", false, () -> safely(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))), 16);
        LinearLayout boundaries = ui.card(body);
        ui.heading(boundaries, "shield", "I limiti ti proteggono", "Nessun aggiramento di PIN, biometria o schermate protette. Android mantiene privati i dati delle altre app e limita le cartelle selezionabili.");
        TextView footer = ui.text("MCP ANDROID  /  CONTROLLO PERSONALE", 10, UiKit.MUTED, true);
        footer.setLetterSpacing(.08f); footer.setGravity(Gravity.CENTER); ui.add(body, footer, 24);
    }

    private View bottomBar() {
        LinearLayout nav = ui.row(); nav.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(10)); nav.setBackgroundColor(UiKit.BG);
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout item = ui.column(); item.setGravity(Gravity.CENTER); item.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)); item.setMinimumHeight(ui.dp(68));
            item.setFocusable(true); item.setContentDescription(tabNames[i]); buttonSemantics(item);
            tabIcons[i] = new FrameLayout(this); item.addView(tabIcons[i], new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
            tabLabels[i] = ui.text(tabNames[i], 12, UiKit.MUTED, true); tabLabels[i].setGravity(Gravity.CENTER); tabLabels[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ui.add(item, tabLabels[i], 6); item.setOnClickListener(v -> showTab(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(ui.dp(3), 0, ui.dp(3), 0);
            nav.addView(item, params); tabs[i] = item;
        }
        return nav;
    }

    private void showTab(int index) {
        selectedTab = index;
        for (int i = 0; i < 3; i++) {
            boolean selected = i == index;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(selected); tabs[i].setBackground(ui.ripple(selected ? UiKit.SOFT : UiKit.BG, 0, 18));
            tabLabels[i].setTextColor(selected ? UiKit.ACCENT : UiKit.MUTED);
            tabIcons[i].removeAllViews(); tabIcons[i].addView(ui.icon(tabSymbols[i], selected ? UiKit.ACCENT : UiKit.MUTED, 24));
        }
    }

    private void renderStatus() {
        JSONObject transport = McpForegroundService.transportStatus();
        JSONObject endpoints = transport.optJSONObject("endpoints");
        JSONObject lan = endpoints == null ? null : endpoints.optJSONObject("lan");
        JSONObject tailscale = endpoints == null ? null : endpoints.optJSONObject("tailscale");
        boolean started = McpForegroundService.sessionEnabled();
        boolean lanReady = lan != null && lan.optBoolean("available", false);
        boolean tailscaleReady = tailscale != null && tailscale.optBoolean("available", false);
        boolean ready = started && (lanReady || tailscaleReady);
        replace(heroBadge, !started ? "SESSIONE SPENTA" : ready ? "SESSIONE ATTIVA" : "IN ATTESA DI RETE");
        heroBadge.setTextColor(!started ? UiKit.MUTED : ready ? UiKit.ACCENT : UiKit.WARNING);
        replace(heroTitle, !started ? "Pronto quando\nlo sei tu." : ready ? "Il controllo\nè nelle tue mani." : "Aspettiamo\nuna connessione.");
        replace(heroDescription, !started ? "Scegli cosa condividere, poi apri una sessione per il tuo agente." : ready
                ? "Il telefono è raggiungibile. Il tuo agente potrà accedere con il token autorizzato."
                : "La sessione è aperta. Collega il Wi-Fi o attiva Tailscale per rendere disponibile il telefono.");
        String action = started ? "Interrompi sessione" : "Avvia controllo remoto";
        if (!sessionButton.getText().toString().equals(action)) {
            sessionButton.setText(action); ui.paintButton(sessionButton, started ? UiKit.DANGER_BG : UiKit.ACCENT, started ? UiKit.DANGER : UiKit.BG);
        }
        replace(lanValue, endpointLabel(lan, started)); lanValue.setTextColor(lanReady ? UiKit.ACCENT : UiKit.MUTED);
        replace(tailscaleValue, endpointLabel(tailscale, started)); tailscaleValue.setTextColor(tailscaleReady ? UiKit.ACCENT : UiKit.MUTED);
        String preferred = transport.optString("preferredTransport", "none");
        String selected = "lan".equals(preferred) ? "Wi-Fi locale" : "tailscale".equals(preferred) ? "Tailscale" : "Nessuna";
        replace(preferredLabel, "Connessione preferita: " + selected);
        String error = McpForegroundService.error();
        replace(errorMessage, error); errorMessage.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);
        transportDetails = getString(R.string.transport_status,
                started ? "Sessione aperta" : "Sessione spenta", endpointLabel(lan, started), endpointLabel(tailscale, started),
                selected, accessibilityAllowed ? "Accessibilità autorizzata" : "Accessibilità non autorizzata", error.isEmpty() ? "" : "\n" + error);
    }

    private String endpointLabel(JSONObject endpoint, boolean started) {
        if (endpoint != null && endpoint.optBoolean("available", false))
            return "Disponibile  ·  " + endpoint.optString("address", "") + ":" + endpoint.optInt("port", McpHttpServer.PORT);
        return started ? "Non disponibile al momento" : "Disponibile dopo l'avvio, se la rete è presente";
    }

    private void refreshPermissions() {
        accessibilityAllowed = false;
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager != null) for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) accessibilityAllowed = true;
        }
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationsAllowed = notificationManager != null && notificationManager.isNotificationListenerAccessGranted(new ComponentName(this, McpNotificationService.class));
        replace(accessibilityBadge, accessibilityAllowed ? "Autorizzata" : "Da autorizzare");
        accessibilityBadge.setTextColor(accessibilityAllowed ? UiKit.ACCENT : UiKit.WARNING);
        replace(notificationBadge, notificationsAllowed ? "Autorizzate" : "Opzionali · non autorizzate");
        notificationBadge.setTextColor(notificationsAllowed ? UiKit.ACCENT : UiKit.MUTED);
        replace(setupDescription, !accessibilityAllowed ? "Vuoi controllare lo schermo? Autorizza Accessibilità. Per usare soltanto i file non è necessaria."
                : rootCount == 0 ? "Il controllo schermo è configurato. Puoi aggiungere una cartella oppure collegare subito il tuo agente."
                : "Schermo e cartelle sono configurati. Trovi il token per il tuo agente in Impostazioni.");
    }

    private void refreshRoots() {
        rootList.removeAllViews();
        java.util.List<FileRootStore.Root> current = roots.list(); rootCount = current.size();
        replace(folderBadge, rootCount == 0 ? "Nessuna cartella autorizzata" : rootCount + (rootCount == 1 ? " cartella autorizzata" : " cartelle autorizzate"));
        if (current.isEmpty()) {
            LinearLayout empty = ui.card(rootList);
            ui.heading(empty, "folder", "Un posto per i tuoi file", "Scegli una cartella da condividere. Potrai revocare l'accesso quando vuoi.");
        }
        for (FileRootStore.Root root : current) {
            LinearLayout card = ui.card(rootList);
            String name = root.uri.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "Cartella condivisa";
            int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
            if (separator >= 0 && separator < name.length() - 1) name = name.substring(separator + 1);
            ui.heading(card, "folder", name, "Accesso tramite i permessi concessi da Android.");
            ui.add(card, ui.button("Revoca accesso", false, () -> {
                McpForegroundService.stopNow();
                try { roots.revoke(root.id); refreshRoots(); refreshPermissions(); renderStatus(); toast("Accesso revocato. Sessione interrotta."); }
                catch (ApiException e) { dialog("Impossibile revocare", e.code); }
            }), 14);
        }
    }

    private void renderAdvanced() {
        if (advancedBody == null) return;
        advancedBody.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
        advancedLabel.setText(advancedExpanded ? "Strumenti avanzati  −" : "Strumenti avanzati  +");
        ((View)advancedLabel.getParent()).setContentDescription("Strumenti avanzati, " + (advancedExpanded ? "espansi" : "compressi"));
    }
    private void startRemoteControl() {
        safely(() -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                startAfterPermission = true; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 30); return;
            }
            startAfterPermission = false;
            startForegroundService(new Intent(this, McpForegroundService.class).setAction("START"));
            handler.post(this::renderStatus);
        });
    }
    private void chooseFolder() {
        safely(() -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), 20));
    }
    private void showToken() {
        McpForegroundService.stopNow(); renderStatus();
        LinearLayout content = ui.column(); content.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12));
        content.addView(ui.text("Sessione interrotta per proteggere la tua chiave. Inseriscila nella configurazione privata dell'agente.", 14, UiKit.MUTED, false));
        TextView token = ui.text(SecretStore.current(this), 16, UiKit.ACCENT, false);
        token.setTextDirection(View.TEXT_DIRECTION_LTR);
        token.setTypeface(Typeface.MONOSPACE); token.setTextIsSelectable(false);
        token.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); token.setBackground(ui.shape(UiKit.BG, UiKit.BORDER, 12));
        token.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        token.setSaveEnabled(false); ui.add(content, token, 16);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Il tuo token di accesso").setView(content).setPositiveButton("Chiudi", null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.show();
    }
    private void renderUpdateControls() {
        if (isDestroyed()) return;
        boolean hasRelease = updateRelease != null;
        boolean ready = hasRelease && updateApk != null && updateApk.isFile();
        if (updateLatest != null) {
            if (hasRelease) {
                replace(updateLatest, "Latest: " + updateRelease.version + "  •  " + updateRelease.apkName);
                updateLatest.setVisibility(View.VISIBLE);
            } else updateLatest.setVisibility(View.GONE);
        }
        if (updateNotes != null) {
            String notes = hasRelease && updateRelease.notes != null ? updateRelease.notes.trim() : "";
            if (!notes.isEmpty()) {
                if (notes.length() > 1600) notes = notes.substring(0, 1600) + "…";
                replace(updateNotes, "Novità:\n" + notes);
                updateNotes.setVisibility(View.VISIBLE);
            } else updateNotes.setVisibility(View.GONE);
        }
        if (updateDownloadButton != null) updateDownloadButton.setVisibility(hasRelease && !ready ? View.VISIBLE : View.GONE);
        if (updateInstallButton != null) updateInstallButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (ready && updateProgress != null && updateProgressLabel != null) {
            updateProgress.setIndeterminate(false); updateProgress.setProgress(100); updateProgress.setVisibility(View.VISIBLE);
            replace(updateProgressLabel, "100%  •  " + readableBytes(updateApk.length())); updateProgressLabel.setVisibility(View.VISIBLE);
        } else if (!hasRelease) {
            if (updateProgress != null) updateProgress.setVisibility(View.GONE);
            if (updateProgressLabel != null) updateProgressLabel.setVisibility(View.GONE);
        }
    }
    private void renderDownloadProgress(long downloadedBytes, long totalBytes) {
        if (updateProgress == null || updateProgressLabel == null) return;
        updateProgress.setVisibility(View.VISIBLE); updateProgressLabel.setVisibility(View.VISIBLE);
        if (totalBytes > 0) {
            int percent = (int)Math.min(100L, downloadedBytes * 100L / totalBytes);
            updateProgress.setIndeterminate(false); updateProgress.setProgress(percent);
            replace(updateProgressLabel, percent + "%  •  " + readableBytes(downloadedBytes) + " / " + readableBytes(totalBytes));
            setUpdateStatus("Scaricamento APK in corso… " + percent + "%");
        } else {
            updateProgress.setIndeterminate(true);
            replace(updateProgressLabel, readableBytes(downloadedBytes));
            setUpdateStatus("Scaricamento APK in corso…");
        }
        if (updateDownloadButton != null) updateDownloadButton.setVisibility(View.GONE);
        if (updateInstallButton != null) updateInstallButton.setVisibility(View.GONE);
    }
    private void hideUpdateProgress() {
        if (updateProgress != null) updateProgress.setVisibility(View.GONE);
        if (updateProgressLabel != null) updateProgressLabel.setVisibility(View.GONE);
    }
    private static String readableBytes(long value) {
        if (value <= 0) return "0 B";
        double size = value;
        String[] units = {"B", "KB", "MB", "GB"};
        int index = 0;
        while (size >= 1024 && index < units.length - 1) { size /= 1024.0; index++; }
        return index == 0 ? ((long)size) + " " + units[index]
                : String.format(java.util.Locale.US, "%.1f %s", size, units[index]);
    }
    private void setUpdateStatus(String message) { if (!isDestroyed() && updateStatus != null) replace(updateStatus, message); }
    private String installedVersion() {
        try { String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; return version == null ? "—" : version; }
        catch (PackageManager.NameNotFoundException e) { return "—"; }
    }
    private void pageHeading(LinearLayout body, String title, String subtitle) {
        TextView heading = ui.text(title, 30, UiKit.TEXT, true); heading.setLetterSpacing(-.02f); ui.add(body, heading, 14);
        ui.add(body, ui.text(subtitle, 15, UiKit.MUTED, false), 12);
    }
    private void section(LinearLayout body, String title, String description) {
        ui.add(body, ui.text(title, 20, UiKit.TEXT, true), 27);
        ui.add(body, ui.text(description, 14, UiKit.MUTED, false), 8);
    }
    private void openSettings(String action) { safely(() -> startActivity(new Intent(action))); }
    private void dialog(String title, String message) { if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Chiudi", null).show(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void safely(Runnable action) { try { action.run(); } catch (RuntimeException e) { toast("Operazione non disponibile. Riprova dalle impostazioni dell'app."); } }
    private static void replace(TextView view, String value) { if (view != null && !view.getText().toString().equals(value)) view.setText(value); }
    private void buttonSemantics(View view) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); info.setSelected(host.isSelected());
            }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20 && result == RESULT_OK && data != null) {
            try { roots.add(data.getData()); refreshRoots(); refreshPermissions(); toast("Cartella aggiunta."); }
            catch (ApiException e) { dialog("Cartella non autorizzata", e.code); }
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 30 && startAfterPermission) {
            startAfterPermission = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRemoteControl();
            else toast("Consenti le notifiche per avviare una sessione visibile e controllabile.");
        }
        if (request == 40) toast(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED ? "Termux autorizzato." : "Permesso Termux non concesso.");
        refreshPermissions();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("ui.tab", selectedTab); state.putBoolean("ui.advanced", advancedExpanded); state.putBoolean("ui.pendingStart", startAfterPermission);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume(); refreshRoots(); refreshPermissions(); handler.removeCallbacks(refreshStatus); handler.post(refreshStatus);
        UpdateManager.check(this, false, updateCallback);
    }
    @Override protected void onPause() { handler.removeCallbacks(refreshStatus); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public void onBackPressed() { if (selectedTab != 0) showTab(0); else super.onBackPressed(); }
}
