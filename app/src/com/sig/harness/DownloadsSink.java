package com.sig.harness;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * One file written flat into the public Downloads folder: MediaStore on
 * Android 10+, a plain file on Android 9 and older. Names are sanitized and
 * de-duplicated with the usual " (1)", " (2)" suffixes.
 */
public final class DownloadsSink {
    private final Context context;
    private final String name;
    private final Uri uri;
    private final File file;
    private final OutputStream out;
    /** No Android 10+ o conteúdo vai para um arquivo temporário e só entra em Downloads no fim. */
    private File temp;
    private final String mime;
    private boolean finished;

    private DownloadsSink(Context context, String name, Uri uri, File file, OutputStream out) {
        this.context = context;
        this.name = name;
        this.uri = uri;
        this.file = file;
        this.out = out;
        this.mime = null;
    }

    private DownloadsSink(Context context, String name, File temp, String mime, OutputStream out) {
        this.context = context;
        this.name = name;
        this.uri = null;
        this.file = null;
        this.temp = temp;
        this.out = out;
        this.mime = mime;
    }

    public static DownloadsSink open(Context context, String requestedName, String mime) throws IOException {
        String name = sanitize(requestedName == null || requestedName.isEmpty() ? "download" : requestedName);
        if (name.indexOf('.') < 0 && mime != null && mime.toLowerCase(Locale.US).contains("zip")) name = name + ".zip";
        String type = mimeOf(name, mime);
        if (Build.VERSION.SDK_INT >= 29) return Q.open(context, name, type);
        return Legacy.open(context, name);
    }

    private String publishedName;

    public String name() {
        return publishedName == null ? name : publishedName;
    }

    public void write(byte[] buffer, int offset, int length) throws IOException {
        out.write(buffer, offset, length);
    }

    public void finish() throws IOException {
        if (finished) return;
        finished = true;
        out.flush();
        out.close();
        if (temp != null) {
            try {
                String published = Q.commit(context, temp, name, mimeOf(name, mime));
                this.publishedName = published;
            } finally {
                if (temp.exists()) temp.delete();
            }
            return;
        }
        if (uri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try { context.getContentResolver().update(uri, values, null, null); } catch (Exception ignored) {}
        } else if (file != null) {
            try {
                MediaScannerConnection.scanFile(context, new String[] { file.getAbsolutePath() }, null, null);
            } catch (Exception ignored) {}
        }
    }

    public void abort() {
        if (finished) return;
        finished = true;
        try { out.close(); } catch (Exception ignored) {}
        if (temp != null) { try { temp.delete(); } catch (Exception ignored) {} }
        try {
            if (uri != null) context.getContentResolver().delete(uri, null, null);
            else if (file != null && file.exists()) file.delete();
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- naming

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        cleaned = cleaned.replaceAll("^[. ]+", "");
        if (cleaned.isEmpty()) cleaned = "download";
        if (cleaned.length() > 120) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 && cleaned.length() - dot <= 12 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, 120 - ext.length()) + ext;
        }
        return cleaned;
    }

    private static String mimeOf(String name, String mime) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (mime != null && !mime.isEmpty() && !mime.equalsIgnoreCase("application/octet-stream")) return mime;
        return "application/octet-stream";
    }

    private static String uniqueFor(ContentResolver resolver, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String candidate = name;
        for (int i = 1; existsInDownloads(resolver, candidate) && i < 1000; i++) {
            candidate = base + " (" + i + ")" + ext;
        }
        return candidate;
    }

    private static boolean existsInDownloads(ContentResolver resolver, String name) {
        String[] projection = { MediaStore.Downloads._ID };
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?";
        String[] args = { name, Environment.DIRECTORY_DOWNLOADS + "/" };
        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null);
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // ---------------------------------------------------------------- Android 10+

    private static final class Q {
        /**
         * Grava primeiro no cache do app e só publica em Downloads ao final.
         * Gravar direto numa linha `IS_PENDING=1` deixava o arquivo eternamente
         * pendente quando a atualização final falhava — o nome ficava `.pending-…`.
         */
        static DownloadsSink open(Context context, String name, String mime) throws IOException {
            File dir = new File(context.getCacheDir(), "downloads");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("cache indisponível");
            File temp = new File(dir, "sig-" + System.currentTimeMillis() + ".part");
            return new DownloadsSink(context, name, temp, mime, new FileOutputStream(temp));
        }

        /** Publica o temporário em Downloads, já finalizado, sem estado pendente. */
        static String commit(Context context, File temp, String name, String mime) throws IOException {
            ContentResolver resolver = context.getContentResolver();
            String unique = uniqueFor(resolver, name);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, unique);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore recusou o arquivo");
            OutputStream target = null;
            try {
                target = resolver.openOutputStream(uri);
                if (target == null) throw new IOException("não foi possível abrir o arquivo em Downloads");
                java.io.FileInputStream in = new java.io.FileInputStream(temp);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) target.write(buffer, 0, read);
                    target.flush();
                } finally {
                    try { in.close(); } catch (Exception ignored) {}
                }
            } catch (IOException error) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
                throw error;
            } finally {
                if (target != null) { try { target.close(); } catch (Exception ignored) {} }
            }
            return unique;
        }
    }

    // ---------------------------------------------------------------- Android 9-

    private static final class Legacy {
        static DownloadsSink open(Context context, String name) throws IOException {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("pasta Downloads indisponível");
            File target = unique(dir, name);
            return new DownloadsSink(context, target.getName(), null, target, new FileOutputStream(target));
        }

        private static File unique(File dir, String name) {
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            File candidate = new File(dir, name);
            for (int i = 1; candidate.exists() && i < 1000; i++) {
                candidate = new File(dir, base + " (" + i + ")" + ext);
            }
            return candidate;
        }
    }
}
