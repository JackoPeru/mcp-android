package com.example.androidmcp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Secure updater for public GitHub Releases. Android still requires user confirmation to install. */
public final class UpdateManager {
    private static final String RELEASE_API =
            "https://api.github.com/repos/JackoPeru/mcp-android/releases/latest";
    private static final String PREFS = "updates";
    private static final String LAST_CHECK = "lastCheck";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_HASH_BYTES = 4096;
    private static final int MAX_REDIRECTS = 5;
    private static final ThreadPoolExecutor EXECUTOR =
            LowPowerSessionPolicy.createSingleIdleWorkerExecutor("android-mcp-updater");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);
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
        void onUpdateAvailable(Release release, File downloadedApk);
        void onDownloadProgress(Release release, long downloadedBytes, long totalBytes);
        void onDownloadReady(Release release, File apk);
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
                    File downloaded = findDownloadedUpdate(activity, release);
                    MAIN.post(() -> callback.onUpdateAvailable(release, downloaded));
                } else {
                    MAIN.post(() -> callback.onNoUpdate(current));
                }
            } catch (Exception e) {
                error(callback, "Controllo aggiornamenti non riuscito.");
            }
        });
    }

    public static void download(Activity activity, Release release, Callback callback) {
        if (!DOWNLOADING.compareAndSet(false, true)) {
            state(callback, "Un download è già in corso.");
            return;
        }
        state(callback, "Scaricamento aggiornamento…");
        EXECUTOR.execute(() -> {
            try {
                File apk = downloadAndVerify(activity, release, callback);
                MAIN.post(() -> callback.onDownloadReady(release, apk));
            } catch (Exception e) {
                error(callback, "Download non riuscito: " + safeMessage(e));
            } finally {
                DOWNLOADING.set(false);
            }
        });
    }

    public static void installDownloaded(Activity activity, Release release, File apk, Callback callback) {
        try {
            requireManagedUpdateFile(activity, release, apk);
            verifyApkIdentity(activity, apk, release);
        } catch (Exception e) {
            if (apk != null) apk.delete();
            error(callback, "APK non valido: " + safeMessage(e));
            return;
        }

        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(settings);
                state(callback, "Consenti a MCP Android di installare APK sconosciuti, poi premi di nuovo Installa aggiornamento.");
            } catch (RuntimeException e) {
                error(callback, "Impossibile aprire il permesso di installazione.");
            }
            return;
        }

        try {
            Uri contentUri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent installIntent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(contentUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(installIntent);
            state(callback, "Installer Android aperto. Conferma l'aggiornamento.");
        } catch (RuntimeException e) {
            error(callback, "Impossibile aprire l'installer Android.");
        }
    }

    public static File findDownloadedUpdate(Context context, Release release) {
        File ready = new File(updateDirectory(context), release.apkName);
        if (!ready.isFile() || ready.length() <= 0 || ready.length() > MAX_APK_BYTES) {
            if (ready.exists()) ready.delete();
            return null;
        }
        try {
            verifyApkIdentity(context, ready, release);
            return ready;
        } catch (Exception e) {
            ready.delete();
            return null;
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
            if (apkName.equals(name)) apkUrl = UpdateValidation.requireDownloadUrl(url);
            else if (hashName.equals(name)) hashUrl = UpdateValidation.requireDownloadUrl(url);
        }
        if (apkUrl == null || hashUrl == null) throw new IOException("Release assets missing");
        String notes = json.optString("body", "");
        if (notes.length() > 4000) notes = notes.substring(0, 4000);
        return new Release(version, tag, apkUrl, hashUrl, apkName, notes);
    }

    private static File downloadAndVerify(Activity activity, Release release, Callback callback)
            throws Exception {
        String expected = UpdateValidation.parseHash(new String(
                readBounded(open(new URL(release.hashUrl), DOWNLOAD_HOSTS), MAX_HASH_BYTES),
                StandardCharsets.US_ASCII), release.apkName);

        File directory = updateDirectory(activity);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Update cache unavailable");
        File partial = new File(directory, release.apkName + ".part");
        File ready = new File(directory, release.apkName);
        if (partial.exists() && !partial.delete()) throw new IOException("Old partial update locked");

        boolean completed = false;
        try {
            HttpURLConnection connection = open(new URL(release.apkUrl), DOWNLOAD_HOSTS);
            long declared = connection.getContentLengthLong();
            if (declared > MAX_APK_BYTES) {
                connection.disconnect();
                throw new IOException("APK too large");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                int lastPercent = -2;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    total += read;
                    if (total > MAX_APK_BYTES) throw new IOException("APK too large");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    int percent = declared > 0 ? (int) Math.min(100L, total * 100L / declared) : -1;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final long current = total;
                        final long expectedTotal = declared;
                        MAIN.post(() -> callback.onDownloadProgress(release, current, expectedTotal));
                    }
                }
                output.flush();
                output.getFD().sync();
            } finally {
                connection.disconnect();
            }
            if (declared > 0 && total != declared) throw new IOException("Download incompleto");
            String actual = hex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    expected.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("SHA-256 non valido");
            }
            verifyApkIdentity(activity, partial, release);
            if (ready.exists() && !ready.delete()) throw new IOException("Old update locked");
            if (!partial.renameTo(ready)) throw new IOException("Impossibile finalizzare APK");
            completed = true;
            return ready;
        } finally {
            if (!completed && partial.exists()) partial.delete();
        }
    }

    private static File updateDirectory(Context context) {
        File external = context.getExternalFilesDir(null);
        return new File(external != null ? external : context.getCacheDir(), "updates");
    }

    private static void requireManagedUpdateFile(Context context, Release release, File apk) throws IOException {
        if (apk == null || !apk.isFile()) throw new IOException("APK non trovato");
        File expected = new File(updateDirectory(context), release.apkName).getCanonicalFile();
        if (!apk.getCanonicalFile().equals(expected)) throw new IOException("Percorso APK inatteso");
    }

    private static void verifyApkIdentity(Context context, File apk, Release release) throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo current = manager.getPackageInfo(
                context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo candidate = manager.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null) {
            throw new IOException("APK metadata non valida");
        }
        ApkIdentityValidation.requireValidCandidate(
                context.getPackageName(),
                release.version,
                current.getLongVersionCode(),
                candidate.packageName,
                candidate.versionName,
                candidate.getLongVersionCode(),
                signerDigests(current),
                signerDigests(candidate));
    }

    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Set<String> result = new HashSet<>();
        if (info == null || info.signingInfo == null) return result;
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null) return result;
        for (Signature signer : signers) {
            if (signer == null) continue;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            result.add(hex(digest.digest(signer.toByteArray())));
        }
        return result;
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
            connection.setRequestProperty("User-Agent", "MCP-Android-Updater");
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
