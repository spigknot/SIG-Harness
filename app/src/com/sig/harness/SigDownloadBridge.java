package com.sig.harness;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

/**
 * JavaScript bridge that receives blob downloads the WebView cannot hand to a
 * DownloadListener (the mobile plugin's share fallback builds a blob URL and
 * clicks an anchor). Protocol: begin(name, mime) -> chunk(base64)* -> end(),
 * with fail(message) for aborts. Bytes land flat in Downloads.
 */
public final class SigDownloadBridge {
    private final Context context;
    private DownloadsSink sink;
    private String failure;

    public SigDownloadBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface public synchronized void begin(String name, String mime) {
        abortQuietly();
        failure = null;
        try {
            sink = DownloadsSink.open(context, name, mime);
        } catch (Exception e) {
            sink = null;
            failure = messageOf(e);
        }
    }

    @JavascriptInterface public synchronized void chunk(String base64) {
        if (sink == null || base64 == null) return;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            sink.write(bytes, 0, bytes.length);
        } catch (Exception e) {
            failure = messageOf(e);
            abortQuietly();
        }
    }

    @JavascriptInterface public synchronized void end() {
        if (sink == null) {
            toast("Falha ao baixar: " + (failure == null ? "o arquivo não pôde ser criado" : failure));
            return;
        }
        String name = sink.name();
        try {
            sink.finish();
            toast("Salvo em Downloads/" + name);
        } catch (Exception e) {
            sink.abort();
            toast("Falha ao salvar: " + messageOf(e));
        } finally {
            sink = null;
        }
    }

    @JavascriptInterface public synchronized void fail(String message) {
        abortQuietly();
        toast("Falha ao baixar: " + (message == null || message.isEmpty() ? "erro desconhecido" : message));
    }

    private void abortQuietly() {
        if (sink != null) {
            sink.abort();
            sink = null;
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void toast(final String text) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            }
        });
    }
}
