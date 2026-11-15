package com.example.androidmcp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Visible, explicit confirmation for outbound SMS and calls. */
public final class ConfirmActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String pendingId = getIntent().getStringExtra("pendingId");
        String kind = String.valueOf(getIntent().getStringExtra("kind"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0b1217);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        TextView title = new TextView(this);
        title.setText(String.valueOf(getIntent().getStringExtra("title")));
        title.setTextColor(0xff64dfc4);
        title.setTextSize(14);
        root.addView(title);
        TextView detail = new TextView(this);
        detail.setText(String.valueOf(getIntent().getStringExtra("detail")));
        detail.setTextColor(0xffedf5f5);
        detail.setTextSize(18);
        detail.setTextIsSelectable(true);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(14);
        root.addView(detail, detailParams);
        Button confirm = new Button(this);
        confirm.setText("sms".equals(kind) ? "Conferma e invia SMS" : "Conferma e chiama");
        confirm.setAllCaps(false);
        confirm.setOnClickListener(v -> {
            PendingActions.confirm(pendingId);
            finish();
        });
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        confirmParams.topMargin = dp(24);
        root.addView(confirm, confirmParams);
        Button cancel = new Button(this);
        cancel.setText("Annulla");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> {
            PendingActions.cancel(pendingId);
            finish();
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        cancelParams.topMargin = dp(12);
        root.addView(cancel, cancelParams);
        TextView note = new TextView(this);
        note.setText("Azione richiesta dall'agente. Conferma solo se riconosci destinatario e contenuto.");
        note.setTextColor(0xffa2b4bd);
        note.setTextSize(13);
        note.setGravity(Gravity.START);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(18);
        root.addView(note, noteParams);
        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
