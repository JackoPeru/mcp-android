package com.example.androidmcp;

import android.app.IntentService;
import android.content.Intent;

/** Receives one-shot Termux RUN_COMMAND PendingIntent results in the app process.
 * Kept as a standalone service: AndroidManifest declares it explicitly and folding it
 * into TermuxBridge would require a manifest change owned by another agent. */
@SuppressWarnings("deprecation")
public final class TermuxResultService extends IntentService {
    public TermuxResultService() { super("McpTermuxResult"); }
    @Override protected void onHandleIntent(Intent intent) {
        TermuxBridge.acceptResult(intent);
    }
}
