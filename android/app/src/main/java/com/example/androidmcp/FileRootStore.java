package com.example.androidmcp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persisted, user-selected SAF roots. Root IDs hide provider URIs from callers. */
public final class FileRootStore {
    private static final String PREFS = "android_private_mcp";
    private static final String ROOTS = "saf_roots";
    private final Context appContext;

    public FileRootStore(Context context) {
        appContext = context.getApplicationContext();
    }

    public synchronized List<Root> list() {
        List<Root> roots = new ArrayList<>();
        JSONArray array = readArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            String uri = item.optString("uri", "");
            if (SecurityValidators.isValidRootId(id) && !uri.isEmpty()) {
                roots.add(new Root(id, Uri.parse(uri)));
            }
        }
        return roots;
    }

    public synchronized String add(Uri treeUri) throws ApiException {
        if (treeUri == null || !"content".equals(treeUri.getScheme())
                || !DocumentsContract.isTreeUri(treeUri)) {
            throw new ApiException("INVALID_ARGUMENT", "A SAF tree grant is required");
        }
        try {
            appContext.getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            // Some providers only grant read even when write is requested. Preserve the useful
            // read grant and expose write capability separately.
            try {
                appContext.getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException readFailure) {
                throw new ApiException("ROOT_GRANT_FAILED", "SAF grant was not accepted");
            }
        }
        List<Root> roots = list();
        for (Root root : roots) {
            if (root.uri.equals(treeUri)) {
                return root.id;
            }
        }
        String id = "root_" + UUID.randomUUID().toString().replace("-", "");
        JSONArray array = readArray();
        JSONObject item = new JSONObject();
        try {
            item.put("id", id);
            item.put("uri", treeUri.toString());
            array.put(item);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to persist root");
        }
        saveArray(array);
        return id;
    }

    public synchronized void revoke(String rootId) throws ApiException {
        if (!SecurityValidators.isValidRootId(rootId)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid root");
        }
        JSONArray kept = new JSONArray();
        boolean found = false;
        for (Root root : list()) {
            if (root.id.equals(rootId)) {
                found = true;
                try {
                    appContext.getContentResolver().releasePersistableUriPermission(
                            root.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    try {
                        appContext.getContentResolver().releasePersistableUriPermission(
                                root.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignoredAgain) {
                        // The provider may already have revoked this grant.
                    }
                }
            } else {
                JSONObject item = new JSONObject();
                try {
                    item.put("id", root.id);
                    item.put("uri", root.uri.toString());
                    kept.put(item);
                } catch (JSONException e) {
                    throw new ApiException("INTERNAL", "Unable to persist roots");
                }
            }
        }
        if (!found) {
            throw new ApiException("UNKNOWN_ROOT", "Unknown root");
        }
        saveArray(kept);
    }

    public synchronized void revokeAll() {
        for (Root root : list()) {
            try {
                appContext.getContentResolver().releasePersistableUriPermission(
                        root.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                try {
                    appContext.getContentResolver().releasePersistableUriPermission(
                            root.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignoredAgain) {
                    // Continue clearing local state even if provider already revoked grant.
                }
            }
        }
        saveArray(new JSONArray());
    }

    public synchronized Uri requireUri(String rootId) throws ApiException {
        if (!SecurityValidators.isValidRootId(rootId)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid root");
        }
        for (Root root : list()) {
            if (root.id.equals(rootId)) {
                if (!hasReadGrant(root.uri)) {
                    throw new ApiException("ROOT_REVOKED", "Root permission revoked");
                }
                return root.uri;
            }
        }
        throw new ApiException("UNKNOWN_ROOT", "Unknown root");
    }

    public synchronized Uri requireWriteUri(String rootId) throws ApiException {
        Uri uri = requireUri(rootId);
        if (!hasWriteGrant(uri)) {
            throw new ApiException("ROOT_READ_ONLY", "Root does not have write permission");
        }
        return uri;
    }

    public synchronized boolean canWrite(String rootId) {
        try {
            Uri uri = requireUri(rootId);
            return hasWriteGrant(uri);
        } catch (ApiException e) {
            return false;
        }
    }

    public static final class Root {
        public final String id;
        public final Uri uri;

        Root(String id, Uri uri) {
            this.id = id;
            this.uri = uri;
        }
    }

    private boolean hasReadGrant(Uri uri) {
        for (android.content.UriPermission permission
                : appContext.getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri())
                    && permission.isReadPermission()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasWriteGrant(Uri uri) {
        for (android.content.UriPermission permission
                : appContext.getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri()) && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private JSONArray readArray() {
        String raw = prefs().getString(ROOTS, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void saveArray(JSONArray array) {
        if (!prefs().edit().putString(ROOTS, array.toString()).commit()) {
            throw new IllegalStateException("Unable to persist roots");
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
