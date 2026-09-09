package com.example.androidmcp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public final class MainActivity extends Activity {
    private FileRootStore roots;
    private TextView status;
    private TextView updateStatus;
    private LinearLayout rootList;
    private final UpdateManager.Callback updateCallback = new UpdateManager.Callback() {
        @Override public void onState(String message) {
            if (updateStatus != null) updateStatus.setText(message);
        }
        @Override public void onNoUpdate(String currentVersion) {
            if (updateStatus != null) {
                updateStatus.setText(getString(R.string.update_current, currentVersion));
            }
        }
        @Override public void onUpdateAvailable(UpdateManager.Release release) {
            if (updateStatus != null) {
                updateStatus.setText(getString(R.string.update_available, release.version));
            }
            showUpdateDialog(release);
        }
        @Override public void onError(String message) {
            if (updateStatus != null) updateStatus.setText(getString(R.string.update_error, message));
        }
    };
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            JSONObject transport = McpForegroundService.transportStatus();
            JSONObject endpoints = transport.optJSONObject("endpoints");
            JSONObject lan = endpoints == null ? null : endpoints.optJSONObject("lan");
            JSONObject tailscale = endpoints == null ? null : endpoints.optJSONObject("tailscale");
            String lanStatus = lan != null && lan.optBoolean("available", false)
                    ? lan.optString("address", "") + ":" + lan.optInt("port", McpHttpServer.PORT)
                    : getString(R.string.endpoint_unavailable);
            String tailscaleStatus = tailscale != null && tailscale.optBoolean("available", false)
                    ? tailscale.optString("address", "") + ":" + tailscale.optInt("port", McpHttpServer.PORT)
                    : getString(R.string.endpoint_unavailable);
            String preferred = transport.optString("preferredTransport", "none");
            if ("lan".equals(preferred)) preferred = getString(R.string.preferred_lan);
            else if ("tailscale".equals(preferred)) preferred = getString(R.string.preferred_tailscale);
            else preferred = getString(R.string.preferred_none);
            String remote = getString(McpForegroundService.sessionEnabled()
                    ? R.string.remote_control_active : R.string.remote_control_waiting);
            String accessibility = getString(R.string.accessibility_state,
                    getString(McpAccessibilityService.active() == null
                            ? R.string.accessibility_off : R.string.accessibility_on));
            String error = McpForegroundService.error();
            String errorSuffix = error.isEmpty() ? "" : getString(R.string.transport_status_error, error);
            status.setText(getString(R.string.transport_status,
                    remote, lanStatus, tailscaleStatus, preferred, accessibility, errorSuffix));
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        roots = new FileRootStore(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scroll.addView(layout); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            layout.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        text(layout, "MCP Android", 28);
        text(layout, "Controllo del tuo telefono via Wi-Fi locale o Tailscale. In casa l'agente preferisce la LAN; fuori casa può usare Tailscale. Il token resta obbligatorio su entrambi i trasporti. Avvia soltanto quando vuoi consentire l'accesso.", 16);
        text(layout, "Versione installata: " + installedVersion(), 15);
        status = text(layout, "", 16);
        updateStatus = text(layout, "Aggiornamenti: controllo automatico giornaliero.", 14);
        button(layout, "Controlla aggiornamenti", () -> UpdateManager.check(this, true, updateCallback));
        button(layout, "1. Abilita Accessibilità", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(layout, "Accesso notifiche (opzionale)", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button(layout, "Abilita shell Termux (opzionale)", () -> {
            String permission = "com.termux.permission.RUN_COMMAND";
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permesso Termux già concesso.", Toast.LENGTH_SHORT).show();
            } else {
                requestPermissions(new String[]{permission}, 40);
            }
        });
        button(layout, "Abilita Shizuku (opzionale)", () -> {
            try {
                boolean alreadyGranted = ShizukuBridge.requestPermission(this);
                Toast.makeText(this,
                        alreadyGranted ? "Permesso Shizuku già concesso." : "Richiesta inviata a Shizuku.",
                        Toast.LENGTH_LONG).show();
            } catch (ApiException e) {
                Toast.makeText(this, e.code, Toast.LENGTH_LONG).show();
            }
        });
        button(layout, "2. Autorizza una cartella", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), 20));
        button(layout, "3. Mostra token per configurare l'agente", this::showToken);
        button(layout, "4. Avvia controllo remoto", () -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 30);
                return;
            }
            startForegroundService(new Intent(this, McpForegroundService.class).setAction("START"));
        });
        button(layout, "STOP controllo remoto", McpForegroundService::stopNow);
        button(layout, "Revoca token e genera nuovo", () -> {
            McpForegroundService.stopNow(); SecretStore.rotate(this);
            Toast.makeText(this, "Servizio fermato. Configura il nuovo token nell'agente.", Toast.LENGTH_LONG).show();
        });
        text(layout, "Cartelle autorizzate", 20);
        rootList = new LinearLayout(this); rootList.setOrientation(LinearLayout.VERTICAL); layout.addView(rootList);
        text(layout, "File via API: nessuna navigazione UI dopo la scelta iniziale. Android limita radice memoria, Download intera, Android/data e dati privati delle altre app. Nessun bypass del blocco schermo. Dopo un riavvio avvia nuovamente il servizio.", 15);
        text(layout, "Shell opzionali: Termux usa il proprio ambiente utente; Shizuku usa UID shell o root solo se lo hai avviato esplicitamente così. I due backend hanno permessi separati e non vengono mai scelti automaticamente uno al posto dell'altro.", 15);
        button(layout, "Impostazioni app / batteria", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    }

    private void showUpdateDialog(UpdateManager.Release release) {
        if (isFinishing() || isDestroyed()) return;
        StringBuilder message = new StringBuilder()
                .append("Versione installata: ").append(installedVersion())
                .append("\nNuova versione: ").append(release.version)
                .append("\n\nL'APK verrà scaricato da GitHub Releases, verificato con SHA-256 e poi passato all'installer Android.");
        if (release.notes != null && !release.notes.trim().isEmpty()) {
            String notes = release.notes.trim();
            if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
            message.append("\n\nNote:\n").append(notes);
        }
        new AlertDialog.Builder(this)
                .setTitle("Aggiornamento v" + release.version)
                .setMessage(message.toString())
                .setPositiveButton("Aggiorna", (dialog, which) ->
                        UpdateManager.install(this, release, updateCallback))
                .setNegativeButton("Più tardi", null)
                .show();
    }

    private void showToken() {
        McpForegroundService.stopNow();
        TextView view = new TextView(this);
        view.setPadding(24, 24, 24, 24);
        view.setText(SecretStore.current(this));
        view.setTextIsSelectable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Token privato — servizio fermato")
            .setView(view).setPositiveButton("Chiudi", null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private String installedVersion() {
        try {
            String value = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return value == null || value.isEmpty() ? "sconosciuta" : value;
        } catch (PackageManager.NameNotFoundException e) {
            return "sconosciuta";
        }
    }

    private void refreshRoots() {
        rootList.removeAllViews();
        for (FileRootStore.Root root : roots.list()) {
            text(rootList, root.uri.getLastPathSegment() + "\n" + root.id, 14);
            button(rootList, "Revoca cartella", () -> {
                McpForegroundService.stopNow();
                try { roots.revoke(root.id); refreshRoots(); }
                catch (ApiException e) { Toast.makeText(this, e.code, Toast.LENGTH_LONG).show(); }
            });
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20 && result == RESULT_OK && data != null) {
            try { roots.add(data.getData()); refreshRoots(); }
            catch (ApiException e) { Toast.makeText(this, e.code, Toast.LENGTH_LONG).show(); }
        }
    }
    @Override protected void onResume() {
        super.onResume();
        refreshRoots();
        handler.post(refreshStatus);
        UpdateManager.resumePending(this, updateCallback);
        UpdateManager.check(this, false, updateCallback);
    }
    @Override protected void onPause() { handler.removeCallbacks(refreshStatus); super.onPause(); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0, 12, 0, 12); parent.addView(view); return view;
    }
    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setOnClickListener(view -> { try { action.run(); } catch (RuntimeException e) { Toast.makeText(this, "Operazione non disponibile.", Toast.LENGTH_LONG).show(); } });
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
}
