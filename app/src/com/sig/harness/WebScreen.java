package com.sig.harness;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/** Optional full web UI of the DSH, authenticated by the URL returned by the bridge. */
public final class WebScreen {
    public interface Host {
        void onRetry();
        void onOpenChrome(String url);
        void onOpenSettings();
    }

    private final Context c;
    private final Host host;
    private final LinearLayout root;
    private final WebView web;
    private final ProgressBar progress;
    private final FrameLayout frame;
    private final LinearLayout waiting;
    private final TextView waitingText;
    private final LinearLayout errorCard;
    private final TextView errorText;
    private String url;
    private boolean destroyed;
    private int loadGeneration;
    private final WebFilePicker files;
    private String downloadHook;
    private String voiceHook;
    private final VoiceBridge voice;
    /** Reloads automáticos quando o host troca de porta/token logo após o primeiro load. */
    private int autoRetries;
    public static final int MIC_REQUEST = 4402;

    public WebScreen(Context context, Host host, WebFilePicker files) {
        this.c = context;
        this.host = host;
        this.files = files;
        this.voice = ((context instanceof Activity)
                ? new VoiceBridge((Activity) context, new Prefs(context), new VoiceBridge.Host() {
                    @Override public void voiceState(String state, String detail) { dispatchVoiceState(state, detail); }
                    @Override public void voicePartial(String text) { dispatchVoicePartial(text); }
                    @Override public void voiceFinal(String text) { dispatchVoiceFinal(text); }
                    @Override public void voiceError(String message) { dispatchVoiceError(message); }
                    @Override public void voiceToast(final String message) {
                        ((Activity) context).runOnUiThread(new Runnable() {
                            @Override public void run() {
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override public void voiceNeedsSettings(String reason) {
                        Toast.makeText(c, reason, Toast.LENGTH_LONG).show();
                        host.onOpenSettings();
                    }
                    @Override public void openSettings() { host.onOpenSettings(); }
                    @Override public void requestMicPermission() {
                        ((Activity) c).requestPermissions(new String[] { android.Manifest.permission.RECORD_AUDIO }, MIC_REQUEST);
                    }
                })
                : null);
        root = Ui.column(c);
        root.setBackgroundColor(Ui.BG);

        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.SURFACE));
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 3)));

        frame = new FrameLayout(c);
        root.addView(frame, Ui.lpWeight(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(c);
        web.setBackgroundColor(Ui.BG);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new SigDownloadBridge(c), "SigDownload");
        if (c instanceof AppBridge.Host) web.addJavascriptInterface(new AppBridge((AppBridge.Host) c), "SigApp");
        if (voice != null) web.addJavascriptInterface(voice, "SigVoice");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                Uri local = url == null ? Uri.EMPTY : Uri.parse(url);
                if ("http".equals(target.getScheme()) && "127.0.0.1".equals(target.getHost())
                        && target.getPort() == local.getPort()) return false;
                if (request.isForMainFrame() && ("https".equals(target.getScheme()) || "http".equals(target.getScheme()))) {
                    host.onOpenChrome(target.toString());
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String pageUrl, android.graphics.Bitmap favicon) {
                showWaiting(false);
                hideError();
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String pageUrl) {
                progress.setVisibility(View.GONE);
                installDownloadHook();
                installVoiceHook();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    if (autoRetries++ < 2) { host.onRetry(); return; }
                    showError("Não foi possível carregar a interface web. " + (error == null ? "" : error.getDescription()));
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    if (autoRetries++ < 2) { host.onRetry(); return; }
                    int status = errorResponse == null ? 0 : errorResponse.getStatusCode();
                    showError("A interface web respondeu HTTP " + status + ". Toque em Tentar novamente.");
                }
            }
        });
        web.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                WebDownloader.start(c, url, userAgent, contentDisposition);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return files.open(view, callback, params);
            }
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                if (newProgress >= 100) progress.setVisibility(View.GONE);
                else progress.setVisibility(View.VISIBLE);
            }
        });
        frame.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        waiting = Ui.column(c);
        waiting.setGravity(Gravity.CENTER);
        waiting.setBackgroundColor(Ui.BG);
        ProgressBar spinner = new ProgressBar(c);
        spinner.setIndeterminate(true);
        waiting.addView(spinner);
        waitingText = Ui.text(c, "Iniciando a interface web…", 13f, Ui.MUTED, Ui.REGULAR);
        waitingText.setGravity(Gravity.CENTER);
        waitingText.setPadding(0, Ui.dp(c, 12), 0, 0);
        waiting.addView(waitingText);
        frame.addView(waiting, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        errorCard = Ui.column(c);
        errorCard.setGravity(Gravity.CENTER);
        errorCard.setBackgroundColor(Ui.BG);
        errorCard.setPadding(Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24));
        TextView errTitle = Ui.text(c, "Não foi possível abrir o modo web", 18f, Ui.TEXT, Ui.MEDIUM);
        errTitle.setGravity(Gravity.CENTER);
        errorCard.addView(errTitle);
        errorText = Ui.text(c, "", 12.5f, Ui.MUTED, Ui.REGULAR);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(Ui.dp(c, 3), 1.0f);
        errorText.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 16));
        errorCard.addView(errorText);
        TextView retry = Ui.primaryButton(c, "Tentar novamente", new View.OnClickListener() {
            @Override public void onClick(View v) { autoRetries = 0; host.onRetry(); }
        });
        errorCard.addView(retry, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 56)));
        errorCard.setVisibility(View.GONE);
        frame.addView(errorCard, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public View view() { return root; }

    void reload() {
        if (waiting.getVisibility() != View.VISIBLE && web.getUrl() != null) web.reload();
    }

    public void load(final String value) {
        final int generation = ++loadGeneration;
        this.url = value;
        hideError();
        // Expire só os cookies do DSH local: a porta muda, mas o WebView
        // compartilha os cookies por host entre todas as abas.
        final CookieManager cookies = CookieManager.getInstance();
        String local = "http://127.0.0.1/";
        String stored = cookies.getCookie(local);
        ArrayList<String> names = new ArrayList<>();
        if (stored != null) {
            for (String entry : stored.split(";")) {
                int delimiter = entry.indexOf('=');
                if (delimiter < 0) continue;
                String name = entry.substring(0, delimiter).trim();
                if (name.matches("dsh-auth-[A-Za-z0-9_-]+")) names.add(name);
            }
        }
        if (names.isEmpty()) {
            web.loadUrl(value);
            return;
        }
        final int[] remaining = {names.size()};
        for (String name : names) {
            cookies.setCookie(local, name + "=; Max-Age=0; Path=/", ignored -> {
                if (--remaining[0] == 0 && !destroyed && generation == loadGeneration) web.loadUrl(value);
            });
        }
    }

    public void setWaiting(boolean visible, String text) {
        waitingText.setText(text == null ? "Iniciando a interface web…" : text);
        showWaiting(visible);
        if (visible) {
            errorCard.setVisibility(View.GONE);
        }
    }

    public void showError(String message) {
        errorText.setText(message == null ? "Erro desconhecido." : message);
        errorCard.setVisibility(View.VISIBLE);
        waiting.setVisibility(View.GONE);
    }

    private void showWaiting(boolean visible) {
        waiting.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void hideError() {
        errorCard.setVisibility(View.GONE);
    }

    public boolean canGoBack() {
        return web.canGoBack();
    }

    public void goBack() {
        web.goBack();
    }

    public void destroy() {
        destroyed = true;
        files.cancel(web);
        if (voice != null) voice.release();
        try { web.destroy(); } catch (Throwable ignored) { }
    }

    /**
     * Deliver the microphone permission verdict to the dictation bridge.
     * @param requestCode the permission request code that just resolved.
     * @param grantResults the system verdict.
     * @returns whether this screen owned the request.
     */
    public boolean onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != MIC_REQUEST) return false;
        boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (voice != null) voice.onPermission(granted);
        return true;
    }

    private void dispatchVoiceState(String state, String detail) {
        evaluate("window.SigVoiceOnState && window.SigVoiceOnState(" + org.json.JSONObject.quote(state == null ? "idle" : state) + "," + org.json.JSONObject.quote(detail == null ? "" : detail) + ")");
    }

    private void dispatchVoicePartial(String text) {
        evaluate("window.SigVoiceOnPartial && window.SigVoiceOnPartial(" + org.json.JSONObject.quote(text == null ? "" : text) + ")");
    }

    private void dispatchVoiceFinal(String text) {
        evaluate("window.SigVoiceOnFinal && window.SigVoiceOnFinal(" + org.json.JSONObject.quote(text == null ? "" : text) + ")");
    }

    private void dispatchVoiceError(String message) {
        evaluate("window.SigVoiceOnError && window.SigVoiceOnError(" + org.json.JSONObject.quote(message == null ? "" : message) + ")");
    }

    private void evaluate(String code) {
        try { web.evaluateJavascript(code, null); } catch (Throwable ignored) { }
    }

    /** Install the dictation button on top of the composer's send button. */
    private void installVoiceHook() {
        if (voice == null) return;
        try {
            if (voiceHook == null) {
                InputStream in = c.getAssets().open("voice-input.js");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
                in.close();
                voiceHook = buffer.toString("UTF-8");
            }
            web.evaluateJavascript(voiceHook, null);
        } catch (Throwable ignored) {
            // Dictation stays unavailable when the asset cannot be read.
        }
    }

    /** Install the blob-download hook so the share fallback lands in Downloads. */
    private void installDownloadHook() {
        try {
            if (downloadHook == null) {
                InputStream in = c.getAssets().open("blob-download.js");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
                in.close();
                downloadHook = buffer.toString("UTF-8");
            }
            web.evaluateJavascript(downloadHook, null);
        } catch (Throwable ignored) {
            // The page still works without blob downloads.
        }
    }
}
