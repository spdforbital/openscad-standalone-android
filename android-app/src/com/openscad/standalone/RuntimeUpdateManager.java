package com.openscad.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class RuntimeUpdateManager {

    interface ProgressListener {
        void onProgress(String message);
    }

    static class RuntimeStatus {
        final String repository;
        final String deviceAbi;
        final boolean abiSupported;
        final boolean downloaded;
        final String installedVersion;
        final String installedAssetName;
        final long installedAtMs;
        final String latestVersion;
        final String latestAssetName;
        final boolean updateAvailable;
        final long checkedAtMs;
        final String message;

        RuntimeStatus(
            String repository,
            String deviceAbi,
            boolean abiSupported,
            boolean downloaded,
            String installedVersion,
            String installedAssetName,
            long installedAtMs,
            String latestVersion,
            String latestAssetName,
            boolean updateAvailable,
            long checkedAtMs,
            String message
        ) {
            this.repository = repository;
            this.deviceAbi = deviceAbi;
            this.abiSupported = abiSupported;
            this.downloaded = downloaded;
            this.installedVersion = installedVersion;
            this.installedAssetName = installedAssetName;
            this.installedAtMs = installedAtMs;
            this.latestVersion = latestVersion;
            this.latestAssetName = latestAssetName;
            this.updateAvailable = updateAvailable;
            this.checkedAtMs = checkedAtMs;
            this.message = message;
        }
    }

    private static class ReleaseInfo {
        final String tag;
        final AssetInfo asset;

        ReleaseInfo(String tag, AssetInfo asset) {
            this.tag = tag;
            this.asset = asset;
        }
    }

    private static class AssetInfo {
        final String name;
        final String url;

        AssetInfo(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final String REPO_OWNER = "spdforbital";
    private static final String REPO_NAME = "openscad-standalone-android";
    private static final String RELEASES_LATEST_URL =
        "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";

    private static final String PREFS_NAME = "runtime_update_prefs";
    private static final String KEY_LATEST_VERSION = "latest_version";
    private static final String KEY_LATEST_ASSET = "latest_asset";
    private static final String KEY_LAST_CHECKED = "last_checked";

    private static final String META_FILE_NAME = ".downloaded_runtime_meta.json";
    private static final String READY_MARKER_NAME = ".ready";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 25000;

    private static final String ABI_ARM64 = "arm64-v8a";
    private static final String ABI_ARMV7 = "armeabi-v7a";

    private final Context appContext;
    private final OpenScadRuntime runtime;
    private final SharedPreferences prefs;

    RuntimeUpdateManager(Context context, OpenScadRuntime runtime) {
        this.appContext = context.getApplicationContext();
        this.runtime = runtime;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    RuntimeStatus getCachedStatus() {
        String abi = detectArmAbi();
        boolean abiSupported = !TextUtils.isEmpty(abi);

        MetaInfo meta = readMetaInfo();
        boolean downloaded = meta != null && !TextUtils.isEmpty(meta.tag);

        String installedVersion = downloaded ? meta.tag : "Bundled";
        String installedAsset = downloaded ? meta.assetName : "(bundled assets)";

        String latestVersion = prefs.getString(KEY_LATEST_VERSION, "");
        String latestAsset = prefs.getString(KEY_LATEST_ASSET, "");
        long checkedAt = prefs.getLong(KEY_LAST_CHECKED, 0L);

        boolean updateAvailable = false;
        if (abiSupported && !TextUtils.isEmpty(latestVersion)) {
            if (!downloaded) {
                updateAvailable = true;
            } else {
                updateAvailable = !latestVersion.equals(installedVersion);
            }
        }

        String message;
        if (!abiSupported) {
            message = "Unsupported ABI: only ARM devices are handled";
        } else if (TextUtils.isEmpty(latestVersion)) {
            message = "No online version check yet";
        } else if (updateAvailable) {
            message = "New runtime version available";
        } else {
            message = "Runtime is up to date";
        }

        return new RuntimeStatus(
            REPO_OWNER + "/" + REPO_NAME,
            abi,
            abiSupported,
            downloaded,
            installedVersion,
            installedAsset,
            meta == null ? 0L : meta.installedAtMs,
            latestVersion,
            latestAsset,
            updateAvailable,
            checkedAt,
            message
        );
    }

    RuntimeStatus checkForUpdates() throws IOException {
        String abi = detectArmAbi();
        boolean abiSupported = !TextUtils.isEmpty(abi);
        MetaInfo meta = readMetaInfo();

        if (!abiSupported) {
            return new RuntimeStatus(
                REPO_OWNER + "/" + REPO_NAME,
                "",
                false,
                meta != null,
                meta == null ? "Bundled" : meta.tag,
                meta == null ? "(bundled assets)" : meta.assetName,
                meta == null ? 0L : meta.installedAtMs,
                "",
                "",
                false,
                System.currentTimeMillis(),
                "Unsupported ABI"
            );
        }

        ReleaseInfo release = fetchLatestRelease(abi);
        long now = System.currentTimeMillis();

        String latestVersion = release == null ? "" : safe(release.tag);
        String latestAsset = release == null || release.asset == null ? "" : safe(release.asset.name);
        prefs.edit()
            .putString(KEY_LATEST_VERSION, latestVersion)
            .putString(KEY_LATEST_ASSET, latestAsset)
            .putLong(KEY_LAST_CHECKED, now)
            .apply();

        boolean downloaded = meta != null && !TextUtils.isEmpty(meta.tag);
        String installedVersion = downloaded ? meta.tag : "Bundled";
        String installedAsset = downloaded ? meta.assetName : "(bundled assets)";

        boolean updateAvailable = false;
        if (!TextUtils.isEmpty(latestVersion)) {
            if (!downloaded) {
                updateAvailable = true;
            } else {
                updateAvailable = !latestVersion.equals(installedVersion);
            }
        }

        String message;
        if (release == null || release.asset == null) {
            message = "No runtime asset found in latest release for ABI " + abi;
        } else if (updateAvailable) {
            message = "Update available: " + latestVersion;
        } else {
            message = "Runtime is up to date";
        }

        return new RuntimeStatus(
            REPO_OWNER + "/" + REPO_NAME,
            abi,
            true,
            downloaded,
            installedVersion,
            installedAsset,
            meta == null ? 0L : meta.installedAtMs,
            latestVersion,
            latestAsset,
            updateAvailable,
            now,
            message
        );
    }

    RuntimeStatus downloadAndInstallLatest(ProgressListener listener) throws IOException {
        String abi = detectArmAbi();
        if (TextUtils.isEmpty(abi)) {
            throw new IOException("Unsupported ABI");
        }

        notifyProgress(listener, "Checking latest runtime release...");
        ReleaseInfo release = fetchLatestRelease(abi);
        if (release == null || release.asset == null || TextUtils.isEmpty(release.asset.url)) {
            throw new IOException("No downloadable runtime asset found for ABI " + abi);
        }

        notifyProgress(listener, "Preparing runtime base...");
        runtime.prepareRuntime();
        runtime.markRuntimeUnprepared();

        File downloadFile = new File(appContext.getCacheDir(), "openscad_runtime_download.tmp");
        if (downloadFile.exists()) {
            downloadFile.delete();
        }

        File runtimeRoot = runtime.getRuntimeRoot();
        try {
            notifyProgress(listener, "Downloading " + release.asset.name + "...");
            downloadToFile(release.asset.url, downloadFile);

            if (release.asset.name.toLowerCase(Locale.US).endsWith(".zip")) {
                notifyProgress(listener, "Installing runtime archive...");
                installFromZip(downloadFile, runtimeRoot);
            } else {
                notifyProgress(listener, "Installing runtime binary...");
                installBinary(downloadFile, runtimeRoot);
            }

            File openscad = new File(runtime.getRuntimeBinDir(), "openscad");
            if (!openscad.exists()) {
                throw new IOException("Runtime install failed: openscad binary missing");
            }
            if (!openscad.setExecutable(true, true)) {
                throw new IOException("Could not mark openscad as executable");
            }

            writeReadyMarker(runtimeRoot);
            writeMetaInfo(new MetaInfo(release.tag, release.asset.name, System.currentTimeMillis()));
        } catch (Exception installError) {
            notifyProgress(listener, "Install failed, restoring bundled runtime...");
            try {
                runtime.resetRuntimeToBundled();
            } catch (Exception restoreError) {
                throw new IOException(
                    "Runtime install failed and restore failed: " + installError.getMessage() +
                        "; restore error: " + restoreError.getMessage(),
                    installError
                );
            }
            throw new IOException("Runtime install failed, restored bundled runtime: " + installError.getMessage(), installError);
        }

        notifyProgress(listener, "Runtime installed: " + release.tag);

        long now = System.currentTimeMillis();
        prefs.edit()
            .putString(KEY_LATEST_VERSION, safe(release.tag))
            .putString(KEY_LATEST_ASSET, safe(release.asset.name))
            .putLong(KEY_LAST_CHECKED, now)
            .apply();

        return getCachedStatus();
    }

    private ReleaseInfo fetchLatestRelease(String abi) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASES_LATEST_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "OpenSCAD-Standalone-Android");

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IOException("GitHub API error " + code + ": " + trimForError(response));
            }

            JSONObject root = new JSONObject(response);
            String tag = root.optString("tag_name", "");
            JSONArray assets = root.optJSONArray("assets");
            AssetInfo bestAsset = pickBestAsset(assets, abi);
            return new ReleaseInfo(tag, bestAsset);
        } catch (JSONException e) {
            throw new IOException("Invalid GitHub API response", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private AssetInfo pickBestAsset(JSONArray assets, String abi) throws JSONException {
        if (assets == null || assets.length() == 0) {
            return null;
        }

        int bestScore = Integer.MIN_VALUE;
        AssetInfo best = null;

        for (int i = 0; i < assets.length(); i++) {
            JSONObject item = assets.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("name", "");
            String url = item.optString("browser_download_url", "");
            int score = scoreAsset(name, abi);
            if (score <= bestScore || score <= 0 || TextUtils.isEmpty(url)) {
                continue;
            }

            bestScore = score;
            best = new AssetInfo(name, url);
        }

        return best;
    }

    private int scoreAsset(String name, String abi) {
        if (TextUtils.isEmpty(name)) {
            return Integer.MIN_VALUE;
        }
        String lower = name.toLowerCase(Locale.US);

        if (lower.endsWith(".apk") || lower.endsWith(".idsig")
            || lower.endsWith(".sha256") || lower.endsWith(".sha512")
            || lower.endsWith(".asc") || lower.endsWith(".txt")
            || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
            || lower.endsWith(".tar.xz") || lower.endsWith(".xz")
            || lower.endsWith(".appimage") || lower.endsWith(".deb")
            || lower.endsWith(".rpm") || lower.endsWith(".dmg")
            || lower.endsWith(".exe") || lower.endsWith(".msi")) {
            return Integer.MIN_VALUE;
        }

        boolean isZip = lower.endsWith(".zip");
        boolean looksRuntimePayload = lower.contains("runtime")
            || lower.contains("openscad-bin")
            || lower.contains("openscad_binary")
            || lower.endsWith("openscad")
            || lower.endsWith("openscad.bin");
        if (!looksRuntimePayload) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        if (lower.contains("openscad")) {
            score += 20;
        }
        if (lower.contains("runtime")) {
            score += 25;
        }
        if (lower.contains("binary") || lower.contains("bin")) {
            score += 10;
        }

        int abiScore = scoreAbiMatch(lower, abi);
        if (abiScore <= 0) {
            return Integer.MIN_VALUE;
        }
        score += abiScore;

        if (isZip) {
            score += 15;
        } else {
            score += 8;
        }

        if (lower.contains("standalone")) {
            score -= 20;
        }

        return score;
    }

    private int scoreAbiMatch(String nameLower, String abi) {
        if (ABI_ARM64.equals(abi)) {
            if (nameLower.contains("arm64-v8a")) {
                return 100;
            }
            if (nameLower.contains("aarch64")) {
                return 95;
            }
            if (nameLower.contains("arm64")) {
                return 80;
            }
            return 0;
        }

        if (ABI_ARMV7.equals(abi)) {
            if (nameLower.contains("armeabi-v7a")) {
                return 100;
            }
            if (nameLower.contains("armv7a") || nameLower.contains("armv7")) {
                return 90;
            }
            if (nameLower.contains("arm32") || nameLower.contains("armhf")) {
                return 80;
            }
            return 0;
        }

        return 0;
    }

    private String detectArmAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) {
            return "";
        }

        for (String abi : abis) {
            if (ABI_ARM64.equals(abi)) {
                return ABI_ARM64;
            }
        }
        for (String abi : abis) {
            if (ABI_ARMV7.equals(abi)) {
                return ABI_ARMV7;
            }
        }
        for (String abi : abis) {
            if (abi == null) {
                continue;
            }
            String lower = abi.toLowerCase(Locale.US);
            if (lower.contains("arm64") || lower.contains("aarch64")) {
                return ABI_ARM64;
            }
            if (lower.contains("armv7") || lower.contains("armeabi") || lower.equals("arm")) {
                return ABI_ARMV7;
            }
        }
        return "";
    }

    private void downloadToFile(String url, File target) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        BufferedOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "OpenSCAD-Standalone-Android");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readAll(conn.getErrorStream());
                throw new IOException("Download failed " + code + ": " + trimForError(body));
            }

            in = new BufferedInputStream(conn.getInputStream());
            out = new BufferedOutputStream(new FileOutputStream(target, false));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void installBinary(File sourceBinary, File runtimeRoot) throws IOException {
        File binDir = new File(runtimeRoot, "bin");
        if (!binDir.exists() && !binDir.mkdirs()) {
            throw new IOException("Could not create runtime/bin");
        }
        File target = new File(binDir, "openscad");
        copyFile(sourceBinary, target);
    }

    private void installFromZip(File zipFile, File runtimeRoot) throws IOException {
        File unpackRoot = new File(appContext.getCacheDir(), "openscad_runtime_unpack");
        if (unpackRoot.exists()) {
            deleteRecursively(unpackRoot);
        }
        if (!unpackRoot.mkdirs()) {
            throw new IOException("Could not create unzip directory");
        }

        unzip(zipFile, unpackRoot);

        File payloadRoot = findRuntimePayload(unpackRoot);
        if (payloadRoot == null) {
            throw new IOException("Archive does not contain runtime/bin/openscad or bin/openscad");
        }

        String[] parts = new String[] {"bin", "lib", "share", "etc", "home"};
        for (String part : parts) {
            File source = new File(payloadRoot, part);
            if (!source.exists()) {
                continue;
            }
            File target = new File(runtimeRoot, part);
            if (target.exists()) {
                deleteRecursively(target);
            }
            copyRecursively(source, target);
        }
    }

    private File findRuntimePayload(File unpackRoot) {
        File directRuntime = new File(unpackRoot, "runtime");
        if (new File(directRuntime, "bin/openscad").exists()) {
            return directRuntime;
        }

        if (new File(unpackRoot, "bin/openscad").exists()) {
            return unpackRoot;
        }

        File[] children = unpackRoot.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            if (new File(child, "runtime/bin/openscad").exists()) {
                return new File(child, "runtime");
            }
            if (new File(child, "bin/openscad").exists()) {
                return child;
            }
        }
        return null;
    }

    private void unzip(File zipFile, File outDir) throws IOException {
        String rootPath = outDir.getCanonicalPath() + File.separator;
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
        byte[] buffer = new byte[8192];
        try {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (TextUtils.isEmpty(name)) {
                    zin.closeEntry();
                    continue;
                }
                name = name.replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (name.contains("..")) {
                    zin.closeEntry();
                    continue;
                }

                File outFile = new File(outDir, name);
                String outPath = outFile.getCanonicalPath();
                if (!(outPath.equals(outDir.getCanonicalPath()) || outPath.startsWith(rootPath))) {
                    zin.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    zin.closeEntry();
                    continue;
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Could not create " + parent.getAbsolutePath());
                }

                BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile));
                int read;
                while ((read = zin.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                out.close();
                zin.closeEntry();
            }
        } finally {
            zin.close();
        }
    }

    private void writeReadyMarker(File runtimeRoot) throws IOException {
        File marker = new File(runtimeRoot, READY_MARKER_NAME);
        FileOutputStream fos = new FileOutputStream(marker, false);
        fos.write(Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        fos.close();
    }

    private MetaInfo readMetaInfo() {
        File metaFile = new File(runtime.getRuntimeRoot(), META_FILE_NAME);
        if (!metaFile.exists()) {
            return null;
        }
        try {
            String content = readAll(new FileInputStream(metaFile));
            JSONObject json = new JSONObject(content);
            String tag = json.optString("tag", "");
            String asset = json.optString("asset_name", "");
            long installedAt = json.optLong("installed_at", 0L);
            if (TextUtils.isEmpty(tag)) {
                return null;
            }
            return new MetaInfo(tag, asset, installedAt);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeMetaInfo(MetaInfo meta) throws IOException {
        File metaFile = new File(runtime.getRuntimeRoot(), META_FILE_NAME);
        JSONObject json = new JSONObject();
        try {
            json.put("repo", REPO_OWNER + "/" + REPO_NAME);
            json.put("tag", safe(meta.tag));
            json.put("asset_name", safe(meta.assetName));
            json.put("installed_at", meta.installedAtMs);
        } catch (JSONException ignored) {
        }

        FileOutputStream fos = new FileOutputStream(metaFile, false);
        fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
        fos.close();
    }

    private static class MetaInfo {
        final String tag;
        final String assetName;
        final long installedAtMs;

        MetaInfo(String tag, String assetName, long installedAtMs) {
            this.tag = tag;
            this.assetName = assetName;
            this.installedAtMs = installedAtMs;
        }
    }

    private static void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("Could not create " + target.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }
        copyFile(source, target);
    }

    private static void copyFile(File source, File target) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(source));
        FileOutputStream out = new FileOutputStream(target, false);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
        out.close();
        in.close();
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed deleting " + file.getAbsolutePath());
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String trimForError(String text) {
        if (text == null) {
            return "";
        }
        text = text.trim();
        if (text.length() > 220) {
            return text.substring(0, 220) + "...";
        }
        return text;
    }

    private static void notifyProgress(ProgressListener listener, String msg) {
        if (listener != null) {
            listener.onProgress(msg);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
