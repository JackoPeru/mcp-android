package com.example.androidmcp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.health.connect.client.PermissionController;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Granular Health Connect permission screen (also the platform rationale
 * target). Read-only: only READ permissions are ever requested.
 */
public final class HealthConnectActivity extends Activity {
    private static final int REQUEST = 71;
    private ActivityResultContract<Set<String>, Set<String>> contract;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("MCP Android · Health Connect\n\nRichiesta permessi di sola lettura…");
        text.setTextSize(16);
        text.setPadding(48, 64, 48, 48);
        setContentView(text);
        contract = PermissionController.createRequestPermissionResultContract();
        String[] requested = getIntent().getStringArrayExtra("permissions");
        Set<String> permissions = new HashSet<>(Arrays.asList(
                requested == null ? new String[0] : requested));
        if (permissions.isEmpty()) { finish(); return; }
        try {
            startActivityForResult(contract.createIntent(this, permissions), REQUEST);
        } catch (RuntimeException e) {
            finish();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        finish();
    }
}
