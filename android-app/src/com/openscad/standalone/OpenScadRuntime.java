package com.openscad.standalone;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class OpenScadRuntime {

    static class RenderResult {
        final boolean success;
        final File stlFile;
        final File pngFile;
        final String log;
        final String error;
        final long durationMs;

        RenderResult(boolean success, File stlFile, File pngFile, String log, String error, long durationMs) {
            this.success = success;
            this.stlFile = stlFile;
            this.pngFile = pngFile;
            this.log = log;
            this.error = error;
            this.durationMs = durationMs;
        }
    }

    private static class ExecResult {
        final int exitCode;
        final String output;
        final boolean timedOut;

        ExecResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }
    }

    private static class StreamDrainer implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream output;

        StreamDrainer(InputStream input, ByteArrayOutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            int read;
            try {
                while ((read = input.read(buf)) != -1) {
                    output.write(buf, 0, read);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private final Context appContext;
    private final File runtimeRoot;
    private final File runtimeBin;
    private final File runtimeLib;
    private final File runtimeHome;
    private final File runtimeFontConfig;
    private final File runtimeOpenScadPath;
    private final File userLibrariesDir;
    private final File userLibrarySourcesDir;
    private final File projectsDir;
    private final File rendersDir;

    private volatile boolean prepared;

    OpenScadRuntime(Context context) {
        this.appContext = context.getApplicationContext();
        this.runtimeRoot = new File(appContext.getFilesDir(), "runtime");
        this.runtimeBin = new File(runtimeRoot, "bin");
        this.runtimeLib = new File(runtimeRoot, "lib");
        this.runtimeHome = new File(runtimeRoot, "home");
        this.runtimeFontConfig = new File(runtimeRoot, "etc/fonts");
        this.runtimeOpenScadPath = new File(runtimeRoot, "share/openscad/libraries");
        this.userLibrariesDir = new File(appContext.getFilesDir(), "libraries");
        this.userLibrarySourcesDir = new File(appContext.getFilesDir(), "library_sources");
        this.projectsDir = new File(appContext.getFilesDir(), "projects");
        this.rendersDir = new File(appContext.getFilesDir(), "renders");

        this.userLibrariesDir.mkdirs();
        this.userLibrarySourcesDir.mkdirs();
        this.projectsDir.mkdirs();
        this.rendersDir.mkdirs();
    }

    File getProjectsDir() {
        return projectsDir;
    }

    File getRendersDir() {
        return rendersDir;
    }

    File getUserLibrariesDir() {
        return userLibrariesDir;
    }

    File getUserLibrarySourcesDir() {
        return userLibrarySourcesDir;
    }

    synchronized void prepareRuntime() throws IOException {
        if (prepared) {
            return;
        }

        File marker = new File(runtimeRoot, ".ready");
        File openscad = new File(runtimeBin, "openscad");
        if (marker.exists() && openscad.exists() && openscad.canExecute()) {
            prepared = true;
            return;
        }

        deleteRecursively(runtimeRoot);
        if (!runtimeRoot.mkdirs()) {
            throw new IOException("Could not create runtime directory: " + runtimeRoot);
        }

        AssetManager assets = appContext.getAssets();
        copyAssetTree(assets, "runtime", runtimeRoot);

        if (!runtimeHome.exists()) {
            runtimeHome.mkdirs();
        }

        if (!openscad.exists()) {
            throw new IOException("Bundled openscad binary missing after extraction");
        }
        if (!openscad.setExecutable(true, true)) {
            throw new IOException("Could not mark openscad binary as executable");
        }

        FileOutputStream fos = new FileOutputStream(marker);
        fos.write(Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        fos.close();

        prepared = true;
    }

    RenderResult render(String code, String baseName) {
        long startMs = System.currentTimeMillis();
        try {
            prepareRuntime();

            String safeBase = sanitizeName(baseName);
            if (safeBase.isEmpty()) {
                safeBase = "model";
            }

            String hash = md5Short(code);
            File scadFile = new File(rendersDir, safeBase + "_" + hash + ".scad");
            File stlFile = new File(rendersDir, safeBase + "_" + hash + ".stl");

            writeText(scadFile, code);

            List<String> stlArgs = new ArrayList<String>();
            stlArgs.add("-q");
            stlArgs.add("--export-format=binstl");
            stlArgs.add("-o");
            stlArgs.add(stlFile.getAbsolutePath());
            stlArgs.add(scadFile.getAbsolutePath());

            ExecResult stl = runOpenScad(stlArgs, 180);
            if (stl.timedOut) {
                return new RenderResult(false, null, null, stl.output, "Render timed out", System.currentTimeMillis() - startMs);
            }
            if (stl.exitCode != 0 || !stlFile.exists()) {
                String err = stl.output == null || stl.output.trim().isEmpty() ? "OpenSCAD failed to produce STL" : stl.output;
                return new RenderResult(false, null, null, stl.output, err, System.currentTimeMillis() - startMs);
            }

            StringBuilder log = new StringBuilder();
            if (stl.output != null && !stl.output.trim().isEmpty()) {
                log.append(stl.output.trim());
                log.append('\n');
            }
            log.append("STL generated for native viewer.");

            return new RenderResult(true, stlFile, null, log.toString(), null, System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            return new RenderResult(false, null, null, "", e.getMessage(), System.currentTimeMillis() - startMs);
        }
    }

    private ExecResult runOpenScad(List<String> args, long timeoutSeconds) throws IOException, InterruptedException {
        File openscad = new File(runtimeBin, "openscad");

        List<String> directCmd = new ArrayList<String>();
        directCmd.add(openscad.getAbsolutePath());
        directCmd.addAll(args);

        try {
            return runCommand(directCmd, timeoutSeconds);
        } catch (IOException directError) {
            if (!isPermissionDenied(directError)) {
                throw directError;
            }

            File linker64 = new File("/system/bin/linker64");
            if (!linker64.exists()) {
                throw directError;
            }

            List<String> linkerCmd = new ArrayList<String>();
            linkerCmd.add(linker64.getAbsolutePath());
            linkerCmd.add(openscad.getAbsolutePath());
            linkerCmd.addAll(args);

            try {
                return runCommand(linkerCmd, timeoutSeconds);
            } catch (IOException linkerError) {
                throw new IOException(
                    "OpenSCAD launch failed. Direct exec denied and linker fallback failed: " + linkerError.getMessage(),
                    linkerError
                );
            }
        }
    }

    private ExecResult runCommand(List<String> cmd, long timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(runtimeRoot);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", runtimeLib.getAbsolutePath() + ":" + appContext.getApplicationInfo().nativeLibraryDir);
        env.put("HOME", runtimeHome.getAbsolutePath());
        String openScadPath = runtimeOpenScadPath.getAbsolutePath();
        if (userLibrariesDir.exists()) {
            openScadPath = userLibrariesDir.getAbsolutePath() + ":" + openScadPath;
        }
        env.put("OPENSCADPATH", openScadPath);
        env.put("TMPDIR", appContext.getCacheDir().getAbsolutePath());
        env.put("FONTCONFIG_PATH", runtimeFontConfig.getAbsolutePath());
        env.put("FONTCONFIG_FILE", new File(runtimeFontConfig, "fonts.conf").getAbsolutePath());
        env.put("LANG", "C.UTF-8");

        Process process = pb.start();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drainer = new Thread(new StreamDrainer(process.getInputStream(), output), "openscad-output-drainer");
        drainer.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            drainer.join(1000);
            return new ExecResult(-1, output.toString(StandardCharsets.UTF_8.name()), true);
        }

        drainer.join(1000);
        return new ExecResult(process.exitValue(), output.toString(StandardCharsets.UTF_8.name()), false);
    }

    private static boolean isPermissionDenied(IOException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("permission denied") || lower.contains("error=13");
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(file, false);
        fos.write(text.getBytes(StandardCharsets.UTF_8));
        fos.close();
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String stem = name;
        if (stem.endsWith(".scad")) {
            stem = stem.substring(0, stem.length() - 5);
        }
        return stem.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String md5Short(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }

    private static void copyAssetTree(AssetManager assetManager, String assetPath, File outPath) throws IOException {
        String[] children = assetManager.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assetManager, assetPath, outPath);
            return;
        }

        if (!outPath.exists() && !outPath.mkdirs()) {
            throw new IOException("Could not create directory " + outPath);
        }

        for (String child : children) {
            String childAssetPath = assetPath + "/" + child;
            File childOut = new File(outPath, child);

            String[] grandChildren = assetManager.list(childAssetPath);
            if (grandChildren != null && grandChildren.length > 0) {
                copyAssetTree(assetManager, childAssetPath, childOut);
            } else {
                copyAssetFile(assetManager, childAssetPath, childOut);
            }
        }
    }

    private static void copyAssetFile(AssetManager assetManager, String assetPath, File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create parent dir for " + outFile);
        }

        InputStream in = assetManager.open(assetPath);
        OutputStream out = new FileOutputStream(outFile);
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
            throw new IOException("Failed deleting " + file);
        }
    }

    String readProject(String fileName) throws IOException {
        File file = new File(projectsDir, fileName);
        FileInputStream fis = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        int total = 0;
        while (total < data.length) {
            int read = fis.read(data, total, data.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        fis.close();
        return new String(data, 0, total, StandardCharsets.UTF_8);
    }

    void writeProject(String fileName, String content) throws IOException {
        File file = new File(projectsDir, fileName);
        writeText(file, content);
    }

    boolean deleteProject(String fileName) {
        File file = new File(projectsDir, fileName);
        return !file.exists() || file.delete();
    }
}
