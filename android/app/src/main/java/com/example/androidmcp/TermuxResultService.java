package com.example.androidmcp;

import android.app.IntentService;
import android.content.Intent;

/** Receives one-shot Termux RUN_COMMAND PendingIntent results in the app process. */
@SuppressWarnings("deprecation")
public final class TermuxResultService extends IntentService {
    public TermuxResultService() { super("McpTermuxResult"); }
    @Override protected void onHandleIntent(Intent intent) {
        TermuxBridge.acceptResult(intent);
    }
}
