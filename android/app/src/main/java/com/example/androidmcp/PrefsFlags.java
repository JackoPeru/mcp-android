package com.example.androidmcp;

import android.content.Context;
import android.content.SharedPreferences;

/** Single SharedPreferences accessor for the "android_private_mcp" file. */
public final class PrefsFlags {
    private PrefsFlags() { }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("android_private_mcp", Context.MODE_PRIVATE);
    }
}
