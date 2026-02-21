package com.openscad.standalone;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class LibraryManager {

    private final Context appContext;
    private final File librariesDir;
    private final File librarySourcesDir;

    LibraryManager(Context context, OpenScadRuntime runtime) {
        this.appContext = context.getApplicationContext();
        this.librariesDir = runtime.getUserLibrariesDir();
        this.librarySourcesDir = runtime.getUserLibrarySourcesDir();

        if (!librariesDir.exists()) {
            librariesDir.mkdirs();
        }
        if (!librarySourcesDir.exists()) {
            librarySourcesDir.mkdirs();
        }
    }

    File getLibrariesDir() {
        return librariesDir;
    }

    File getLibrarySourcesDir() {
        return librarySourcesDir;
    }

    List<String> listLibraries() {
        List<String> libs = new ArrayList<String>();
        collectLibraryScadFiles(librariesDir, librariesDir, libs);
        Collections.sort(libs, String.CASE_INSENSITIVE_ORDER);
        return libs;
    }

    String readLibrarySource(String libraryPath) throws IOException {
        if (libraryPath == null || libraryPath.trim().isEmpty()) {
            throw new IOException("Library path is empty");
        }
        File file = new File(librariesDir, libraryPath);
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Library not found: " + libraryPath);
        }
        return readTextFile(file);
    }

    String importFromUri(Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("Missing uri");
        }

        String displayName = queryDisplayName(uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "library.scad";
        }

        String safeName = makeSafeLibraryName(displayName);
        String lower = safeName.toLowerCase(Locale.US);
        File sourceCopy = resolveUniqueFile(new File(librarySourcesDir, safeName));
        copyUriToFile(uri, sourceCopy);

        if (lower.endsWith(".zip")) {
            int count = unzipLibraryArchive(sourceCopy, safeName);
            return safeName + " copied + extracted (" + count + " files)";
        }

        if (!lower.endsWith(".scad")) {
            safeName = safeName + ".scad";
        }
        File target = resolveUniqueFile(new File(librariesDir, safeName));
        copyFile(sourceCopy, target);
        return target.getName() + " copied";
    }

    private void collectLibraryScadFiles(File root, File dir, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File file : files) {
            if (file.isDirectory()) {
                collectLibraryScadFiles(root, file, out);
            } else if (file.getName().toLowerCase(Locale.US).endsWith(".scad")) {
                String path = root.toURI().relativize(file.toURI()).getPath();
                if (path != null && !path.trim().isEmpty()) {
                    out.add(path);
                }
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = appContext.getContentResolver().query(
                uri,
                new String[] { OpenableColumns.DISPLAY_NAME },
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int unzipLibraryArchive(File archiveFile, String archiveName) throws IOException {
        String baseDirName = makeSafeLibraryName(stripExtension(archiveName));
        if (baseDirName.isEmpty()) {
            baseDirName = "library";
        }
        File importRoot = resolveUniqueDirectory(new File(librariesDir, baseDirName));
        if (!importRoot.exists() && !importRoot.mkdirs()) {
            throw new IOException("Could not create " + importRoot.getAbsolutePath());
        }

        try (InputStream baseIn = new FileInputStream(archiveFile);
                ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(baseIn))) {

            byte[] buffer = new byte[8192];
            int writtenFiles = 0;
            String rootPath = importRoot.getCanonicalPath() + File.separator;

            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) {
                    zipIn.closeEntry();
                    continue;
                }
                name = name.replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (name.contains("..")) {
                    zipIn.closeEntry();
                    continue;
                }

                File outFile = new File(importRoot, name);
                String outPath = outFile.getCanonicalPath();
                if (!(outPath.equals(importRoot.getCanonicalPath()) || outPath.startsWith(rootPath))) {
                    zipIn.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    zipIn.closeEntry();
                    continue;
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Could not create " + parent.getAbsolutePath());
                }

                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    int read;
                    while ((read = zipIn.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                writtenFiles++;
                zipIn.closeEntry();
            }

            if (writtenFiles <= 0) {
                throw new IOException("Archive was empty");
            }
            return writtenFiles;
        }
    }

    private void copyUriToFile(Uri uri, File target) throws IOException {
        try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Could not open selected file");
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent.getAbsolutePath());
            }

            try (FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        }
    }

    private static File resolveUniqueFile(File file) {
        if (!file.exists()) {
            return file;
        }
        String name = file.getName();
        String stem = stripExtension(name);
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            ext = name.substring(dot);
        }
        int idx = 2;
        while (true) {
            File next = new File(file.getParentFile(), stem + "_" + idx + ext);
            if (!next.exists()) {
                return next;
            }
            idx++;
        }
    }

    private static File resolveUniqueDirectory(File dir) {
        if (!dir.exists()) {
            return dir;
        }
        String name = dir.getName();
        int idx = 2;
        while (true) {
            File next = new File(dir.getParentFile(), name + "_" + idx);
            if (!next.exists()) {
                return next;
            }
            idx++;
        }
    }

    private static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name;
        }
        return name.substring(0, dot);
    }

    private static String makeSafeLibraryName(String raw) {
        if (raw == null) {
            return "library";
        }
        String name = raw.trim().replace('\\', '_').replace('/', '_');
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            return "library";
        }
        return name;
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getAbsolutePath());
        }

        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static String readTextFile(File source) throws IOException {
        byte[] data = new byte[(int) source.length()];
        int total = 0;
        try (FileInputStream in = new FileInputStream(source)) {
            while (total < data.length) {
                int read = in.read(data, total, data.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        }
        return new String(data, 0, total, StandardCharsets.UTF_8);
    }
}
