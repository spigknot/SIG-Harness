package com.sig.harness;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads triggered by a normal WebView navigation go straight to the public
 * Downloads folder, with no subfolder. The Host export route authenticates with
 * the WebView cookie, so the cookie is copied onto the request made here.
 * Blob downloads (share fallback) go through {@link SigDownloadBridge} instead.
 */
public final class WebDownloader {
    private static final int STORAGE_REQUEST = 4101;
    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*\\s*=\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PLAIN = Pattern.compile("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", Pattern.CASE_INSENSITIVE);

    private WebDownloader() {}

    /** Handle one WebView download callback on a worker thread. */
    public static void start(final Context context, final String url, final String userAgent, final String contentDisposition) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        final Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT < 29 && !hasLegacyStoragePermission(context)) {
            requestLegacyStoragePermission(context);
            return;
        }
        toast(app, "Baixando…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(120000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("Accept", "*/*");
                    if (userAgent != null && !userAgent.isEmpty()) conn.setRequestProperty("User-Agent", userAgent);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                    String mime = conn.getContentType();
                    String name = chooseName(url, contentDisposition, conn.getHeaderField("Content-Disposition"));
                    InputStream in = conn.getInputStream();
                    DownloadsSink sink = DownloadsSink.open(app, name, mime);
                    try {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) sink.write(buffer, 0, read);
                        sink.finish();
                    } catch (IOException error) {
                        sink.abort();
                        throw error;
                    } finally {
                        in.close();
                    }
                    toast(app, "Salvo em Downloads/" + sink.name());
                } catch (Exception e) {
                    String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    toast(app, "Falha ao baixar: " + detail);
                }
            }
        }, "sig-web-download").start();
    }

    // ---------------------------------------------------------------- naming

    private static String chooseName(String url, String hint, String header) {
        String name = parseDisposition(header);
        if (name == null) name = parseDisposition(hint);
        if (name == null) name = nameFromSession(url);
        return name == null ? "dsh-session.zip" : name;
    }

    private static String parseDisposition(String value) {
        if (value == null || value.isEmpty()) return null;
        Matcher star = FILENAME_STAR.matcher(value);
        if (star.find()) {
            String raw = star.group(1).trim();
            int first = raw.indexOf('\'');
            int second = first < 0 ? -1 : raw.indexOf('\'', first + 1);
            if (second > first) raw = raw.substring(second + 1);
            try { return URLDecoder.decode(raw, "UTF-8"); } catch (Exception ignored) { return raw; }
        }
        Matcher plain = FILENAME_PLAIN.matcher(value);
        if (plain.find()) {
            String raw = plain.group(2) != null ? plain.group(2) : plain.group(1);
            return raw == null ? null : raw.trim();
        }
        return null;
    }

    private static String nameFromSession(String url) {
        try {
            String id = Uri.parse(url).getQueryParameter("sessionId");
            if (id == null || id.isEmpty()) return null;
            return "dsh-session-" + id.replaceAll("[^A-Za-z0-9_-]", "_") + ".zip";
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean hasLegacyStoragePermission(Context context) {
        return context.checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestLegacyStoragePermission(Context context) {
        if (context instanceof Activity) {
            ((Activity) context).requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, STORAGE_REQUEST);
            toast(context, "Permita o acesso ao armazenamento e toque em Export de novo.");
        } else {
            toast(context, "Sem permissão para salvar em Downloads.");
        }
    }

    private static void toast(final Context context, final String text) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            }
        });
    }
}
