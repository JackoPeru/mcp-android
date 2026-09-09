package com.example.androidmcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded in-memory store for sanitized semantic screen snapshots. */
public final class ScreenSnapshotStore {
    private static final int MAX_SNAPSHOTS = 8;

    private final ArrayDeque<Snapshot> snapshots = new ArrayDeque<>();
    private long nextId = 1;

    public synchronized Snapshot capture(JSONObject context) throws ApiException {
        if (context == null) throw new ApiException("INVALID_ARGUMENT", "Missing screen context");
        JSONObject copy;
        try {
            copy = new JSONObject(context.toString());
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to copy screen context");
        }
        long id = nextId++;
        String hash = hash(copy);
        Snapshot snapshot = new Snapshot(id, hash, copy);
        snapshots.addLast(snapshot);
        while (snapshots.size() > MAX_SNAPSHOTS) snapshots.removeFirst();
        return snapshot;
    }

    public synchronized long latestId() {
        Snapshot latest = snapshots.peekLast();
        return latest == null ? 0 : latest.id;
    }

    public synchronized int size() {
        return snapshots.size();
    }

    public synchronized Snapshot get(long id) throws ApiException {
        for (Snapshot snapshot : snapshots) {
            if (snapshot.id == id) return snapshot;
        }
        throw new ApiException("UNKNOWN_SNAPSHOT", "Screen snapshot is no longer available");
    }

    public synchronized JSONObject diff(long fromId, long toId) throws ApiException {
        Snapshot from = get(fromId);
        Snapshot to = get(toId);
        return diff(from, to);
    }

    public synchronized JSONObject diff(Snapshot from, Snapshot to) throws ApiException {
        if (from == null || to == null) throw new ApiException("INVALID_ARGUMENT", "Missing screen snapshot");
        JSONObject result = new JSONObject();
        try {
            boolean packageChanged = !from.context.optString("packageName", "")
                    .equals(to.context.optString("packageName", ""));
            boolean windowChanged = !from.context.optString("windowClass", "")
                    .equals(to.context.optString("windowClass", ""));

            JSONObject fromInput = from.context.optJSONObject("input");
            JSONObject toInput = to.context.optJSONObject("input");
            boolean fromFocus = fromInput != null && fromInput.optBoolean("focusedEditable", false);
            boolean toFocus = toInput != null && toInput.optBoolean("focusedEditable", false);
            boolean fromKeyboard = fromInput != null && fromInput.optBoolean("keyboardVisible", false);
            boolean toKeyboard = toInput != null && toInput.optBoolean("keyboardVisible", false);

            Map<String, JSONObject> oldNodes = nodesByPath(from.context.optJSONArray("nodes"));
            Map<String, JSONObject> newNodes = nodesByPath(to.context.optJSONArray("nodes"));
            JSONArray added = new JSONArray();
            JSONArray removed = new JSONArray();
            JSONArray changed = new JSONArray();

            for (Map.Entry<String, JSONObject> entry : newNodes.entrySet()) {
                JSONObject before = oldNodes.get(entry.getKey());
                if (before == null) {
                    added.put(copy(entry.getValue()));
                } else if (!canonical(before).equals(canonical(entry.getValue()))) {
                    changed.put(new JSONObject()
                            .put("path", entry.getKey())
                            .put("before", copy(before))
                            .put("after", copy(entry.getValue())));
                }
            }
            for (Map.Entry<String, JSONObject> entry : oldNodes.entrySet()) {
                if (!newNodes.containsKey(entry.getKey())) removed.put(copy(entry.getValue()));
            }

            boolean changedAny = !from.uiHash.equals(to.uiHash);
            result.put("fromSnapshotId", from.id);
            result.put("toSnapshotId", to.id);
            result.put("fromUiHash", from.uiHash);
            result.put("toUiHash", to.uiHash);
            result.put("changed", changedAny);
            result.put("packageChanged", packageChanged);
            result.put("windowChanged", windowChanged);
            result.put("focusChanged", fromFocus != toFocus);
            result.put("keyboardChanged", fromKeyboard != toKeyboard);
            result.put("addedNodes", added);
            result.put("removedNodes", removed);
            result.put("changedNodes", changed);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode screen diff");
        }
    }

    public static String hash(JSONObject context) throws ApiException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical(context).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new ApiException("INTERNAL", "SHA-256 unavailable");
        }
    }

    private static Map<String, JSONObject> nodesByPath(JSONArray nodes) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        if (nodes == null) return result;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            String path = node.optString("path", "");
            if (!path.isEmpty()) result.put(path, node);
        }
        return result;
    }

    private static JSONObject copy(JSONObject source) throws JSONException {
        return new JSONObject(source.toString());
    }

    private static String canonical(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (String key : keys) {
                if ("snapshotId".equals(key) || "uiHash".equals(key) || "screenshot".equals(key)) continue;
                if (!first) out.append(',');
                first = false;
                out.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(canonical(array.opt(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return JSONObject.quote(String.valueOf(value));
    }

    public static final class Snapshot {
        public final long id;
        public final String uiHash;
        public final JSONObject context;

        Snapshot(long id, String uiHash, JSONObject context) {
            this.id = id;
            this.uiHash = uiHash;
            this.context = context;
        }

        public JSONObject responseCopy() throws ApiException {
            try {
                return new JSONObject(context.toString())
                        .put("snapshotId", id)
                        .put("uiHash", uiHash);
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode screen snapshot");
            }
        }
    }
}
