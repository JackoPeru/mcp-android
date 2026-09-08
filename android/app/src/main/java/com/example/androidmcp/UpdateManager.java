package com.example.androidmcp;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Secure updater for public GitHub Releases. Android still requires user confirmation to install. */
public final class UpdateManager {
    private static final String RELEASE_API =
            "https://api.github.com/repos/JackoPeru/mcp-android/releases/latest";
    private static final String PREFS = "updates";
    private static final String LAST_CHECK = "lastCheck";
    private static final String PENDING = "pending";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_HASH_BYTES = 4096;
    private static final int MAX_REDIRECTS = 5;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> DOWNLOAD_HOSTS = new HashSet<>();
    private static final Set<String> API_HOSTS = new HashSet<>();

    static {
        API_HOSTS.add("api.github.com");
        DOWNLOAD_HOSTS.add("github.com");
        DOWNLOAD_HOSTS.add("release-assets.githubusercontent.com");
        DOWNLOAD_HOSTS.add("objects.githubusercontent.com");
    }

    private UpdateManager() { }

    public interface Callback {
        void onState(String message);
        void onNoUpdate(String currentVersion);
        void onUpdateAvailable(Release release);
        void onError(String message);
    }

    public static final class Release {
        public final String version;
        public final String tag;
        public final String apkUrl;
        public final String hashUrl;
        public final String apkName;
        public final String notes;

        Release(String version, String tag, String apkUrl, String hashUrl,
                String apkName, String notes) {
            this.version = version;
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.hashUrl = hashUrl;
            this.apkName = apkName;
            this.notes = notes;
        }

        JSONObject json() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("version", version);
            object.put("tag", tag);
            object.put("apkUrl", apkUrl);
            object.put("hashUrl", hashUrl);
            object.put("apkName", apkName);
            object.put("notes", notes);
            return object;
        }

        static Release from(JSONObject object) throws JSONException {
            return new Release(
                    object.getString("version"),
                    object.getString("tag"),
                    object.getString("apkUrl"),
                    object.getString("hashUrl"),
                    object.getString("apkName"),
                    object.optString("notes", ""));
        }
    }

    public static void check(Activity activity, boolean force, Callback callback) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong(LAST_CHECK, 0) < AUTO_CHECK_INTERVAL_MS) return;
        state(callback, "Controllo aggiornamenti…");
        EXECUTOR.execute(() -> {
            try {
                Release release = fetchLatest();
                prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
                String current = currentVersion(activity);
                if (Versioning.compare(release.version, current) > 0) {
                    MAIN.post(() -> callback.onUpdateAvailable(release));
                } else {
                    MAIN.post(() -> callback.onNoUpdate(current));
                }
            } catch (Exception e) {
                error(callback, "Controllo aggiornamenti non riuscito.");
            }
        });
    }

    public static void install(Activity activity, Release release, Callback callback) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(PENDING, release.json().toString()).apply();
            } catch (JSONException e) {
                error(callback, "Impossibile preparare l'aggiornamento.");
                return;
            }
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            state(callback, "Abilita «Installa app sconosciute», poi torna in MCP Android.");
            return;
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING).apply();
        state(callback, "Scaricamento aggiornamento…");
        EXECUTOR.execute(() -> {
            try {
                File apk = downloadAndVerify(activity, release, callback);
                commitInstall(activity, apk);
                state(callback, "Download verificato. Conferma l'installazione Android.");
            } catch (Exception e) {
                error(callback, "Aggiornamento non riuscito: " + safeMessage(e));
            }
        });
    }

    public static void resumePending(Activity activity, Callback callback) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) return;
        String raw = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            Release release = Release.from(new JSONObject(raw));
            install(activity, release, callback);
        } catch (JSONException e) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING).apply();
        }
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection connection = open(new URL(RELEASE_API), API_HOSTS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        byte[] body = readBounded(connection, MAX_JSON_BYTES);
        JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        if (json.optBoolean("draft", true) || json.optBoolean("prerelease", true)) {
            throw new IOException("Latest release is not stable");
        }
        String tag = json.getString("tag_name");
        String version = Versioning.normalizeTag(tag);
        String apkName = "mcp-android-" + version + "-debug.apk";
        String hashName = apkName + ".sha256";
        JSONArray assets = json.getJSONArray("assets");
        String apkUrl = null;
        String hashUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (apkName.equals(name)) apkUrl = requireDownloadUrl(url);
            else if (hashName.equals(name)) hashUrl = requireDownloadUrl(url);
        }
        if (apkUrl == null || hashUrl == null) throw new IOException("Release assets missing");
        String notes = json.optString("body", "");
        if (notes.length() > 4000) notes = notes.substring(0, 4000);
        return new Release(version, tag, apkUrl, hashUrl, apkName, notes);
    }

    private static File downloadAndVerify(Activity activity, Release release, Callback callback)
            throws Exception {
        String expected = parseHash(new String(
                readBounded(open(new URL(release.hashUrl), DOWNLOAD_HOSTS), MAX_HASH_BYTES),
                StandardCharsets.US_ASCII), release.apkName);

        File directory = new File(activity.getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Update cache unavailable");
        File partial = new File(directory, release.apkName + ".part");
        File ready = new File(directory, release.apkName);
        if (partial.exists() && !partial.delete()) throw new IOException("Old partial update locked");
        if (ready.exists() && !ready.delete()) throw new IOException("Old update locked");

        HttpURLConnection connection = open(new URL(release.apkUrl), DOWNLOAD_HOSTS);
        long declared = connection.getContentLengthLong();
        if (declared > MAX_APK_BYTES) throw new IOException("APK too large");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            long nextProgress = 5L * 1024L * 1024L;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_APK_BYTES) throw new IOException("APK too large");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                if (total >= nextProgress) {
                    final long current = total;
                    state(callback, String.format(Locale.ROOT,
                            "Scaricati %.1f MB…", current / 1048576.0));
                    nextProgress += 5L * 1024L * 1024L;
                }
            }
        } finally {
            connection.disconnect();
        }
        String actual = hex(digest.digest());
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) {
            partial.delete();
            throw new IOException("SHA-256 non valido");
        }
        if (!partial.renameTo(ready)) {
            partial.delete();
            throw new IOException("Impossibile finalizzare APK");
        }
        return ready;
    }

    private static void commitInstall(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        try (InputStream input = new FileInputStream(apk);
             OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            session.fsync(output);
            Intent status = new Intent(context, UpdateInstallReceiver.class)
                    .setAction(UpdateInstallReceiver.ACTION)
                    .setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, status, flags);
            session.commit(pending.getIntentSender());
            committed = true;
        } finally {
            if (!committed) session.abandon();
            session.close();
        }
    }

    private static String parseHash(String sidecar, String apkName) throws IOException {
        String line = sidecar.trim();
        if (line.length() > 512) throw new IOException("Invalid checksum file");
        String[] parts = line.split("\\s+", 2);
        if (parts.length != 2 || !parts[0].matches("^[a-fA-F0-9]{64}$")) {
            throw new IOException("Invalid checksum file");
        }
        String named = parts[1].trim();
        if (named.startsWith("*")) named = named.substring(1);
        if (!apkName.equals(named)) throw new IOException("Checksum filename mismatch");
        return parts[0].toLowerCase(Locale.ROOT);
    }

    private static String requireDownloadUrl(String raw) throws Exception {
        URI uri = new URI(raw);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Invalid release URL");
        }
        return uri.toString();
    }

    private static HttpURLConnection open(URL initial, Set<String> hosts) throws Exception {
        URL current = initial;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())
                    || !hosts.contains(current.getHost().toLowerCase(Locale.ROOT))) {
                throw new IOException("Disallowed update host");
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "MCP-Android-Updater/0.6.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status <= 399) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) throw new IOException("Redirect without location");
                current = new URL(current, location);
                continue;
            }
            if (status != 200) {
                connection.disconnect();
                throw new IOException("Update server HTTP " + status);
            }
            return connection;
        }
        throw new IOException("Too many update redirects");
    }

    private static byte[] readBounded(HttpURLConnection connection, int max) throws IOException {
        long length = connection.getContentLengthLong();
        if (length > max) {
            connection.disconnect();
            throw new IOException("Update response too large");
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (output.size() + read > max) throw new IOException("Update response too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static String currentVersion(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return Versioning.normalizeTag(info.versionName);
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return builder.toString();
    }

    private static String safeMessage(Exception e) {
        String value = e.getMessage();
        if (value == null || value.isEmpty()) return "errore sconosciuto";
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static void state(Callback callback, String value) {
        MAIN.post(() -> callback.onState(value));
    }

    private static void error(Callback callback, String value) {
        MAIN.post(() -> callback.onError(value));
    }
}
