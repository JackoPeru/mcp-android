package com.example.androidmcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** Handles PackageInstaller's explicit user-confirmation handoff and final result. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.example.androidmcp.UPDATE_INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = confirmationIntent(intent);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            } else {
                UpdateManager.installFinished();
                Toast.makeText(context, "Conferma installazione non disponibile.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        UpdateManager.installFinished();
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Aggiornamento installato.", Toast.LENGTH_LONG).show();
            return;
        }
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Toast.makeText(context,
                "Installazione aggiornamento fallita"
                        + (detail == null || detail.isEmpty() ? "." : ": " + detail),
                Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmationIntent(Intent source) {
        if (Build.VERSION.SDK_INT >= 33) {
            return source.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return source.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
