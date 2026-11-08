package com.sig.harness;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.provider.MediaStore;
import android.os.Build;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
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
import java.util.List;

/** Optional full web UI of the DSH, authenticated by the URL returned by the bridge. */
public final class WebScreen {
    public interface Host {
        void onBackToApp();
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
    private ValueCallback<Uri[]> fileCallback;
    private String downloadHook;
    private String voiceHook;
    private String shellHook;
    private final VoiceBridge voice;
    public static final int MIC_REQUEST = 4402;
    private static final int FILE_PICK = 4401;

    public WebScreen(Context context, Host host) {
        this.c = context;
        this.host = host;
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
                String target = request.getUrl().toString();
                if (target.startsWith("http://127.0.0.1") || target.startsWith("http://localhost")) return false;
                host.onOpenChrome(target);
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
                installShellHook();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    showError("Não foi possível carregar a interface web. " + (error == null ? "" : error.getDescription()));
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
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                if (!(c instanceof Activity)) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                List<String> mimes = new ArrayList<String>();
                String[] accept = params == null ? null : params.getAcceptTypes();
                if (accept != null) {
                    for (String type : accept) {
                        if (type != null && !type.trim().isEmpty()) mimes.add(type.trim());
                    }
                }
                boolean multiple = params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;

                // Imagem: vai para a galeria (Photo Picker), que inclui Google Fotos e mostra
                // primeiro o que foi tirado agora. Arquivo genérico continua no navegador
                // de documentos.
                boolean imagesOnly = !mimes.isEmpty();
                for (String mime : mimes) {
                    if (!mime.startsWith("image/")) { imagesOnly = false; break; }
                }
                Intent intent = imagesOnly ? photoPicker(multiple) : null;
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    if (mimes.isEmpty()) {
                        intent.setType("*/*");
                    } else if (mimes.size() == 1) {
                        intent.setType(mimes.get(0));
                    } else {
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                    }
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
                }
                try {
                    ((Activity) c).startActivityForResult(intent, FILE_PICK);
                } catch (Throwable error) {
                    if (imagesOnly) {
                        // Photo Picker indisponível neste aparelho: cai no navegador de documentos.
                        try {
                            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            fallback.addCategory(Intent.CATEGORY_OPENABLE);
                            fallback.setType(mimes.isEmpty() ? "image/*" : mimes.get(0));
                            fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
                            ((Activity) c).startActivityForResult(fallback, FILE_PICK);
                            return true;
                        } catch (Throwable ignored) { }
                    }
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
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
            @Override public void onClick(View v) { host.onRetry(); }
        });
        errorCard.addView(retry, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 56)));
        TextView chrome2 = Ui.ghostButton(c, "Abrir no Chrome", new View.OnClickListener() {
            @Override public void onClick(View v) { if (url != null) host.onOpenChrome(url); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        clp.topMargin = Ui.dp(c, 10);
        errorCard.addView(chrome2, clp);
        errorCard.setVisibility(View.GONE);
        frame.addView(errorCard, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public View view() { return root; }

    public void load(String value) {
        this.url = value;
        hideError();
        showWaiting(false);
        web.loadUrl(value);
    }

    public void setWaiting(boolean visible, String text) {
        waitingText.setText(text == null ? "Iniciando a interface web…" : text);
        showWaiting(visible);
        if (visible) errorCard.setVisibility(View.GONE);
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

    /**
     * Deliver the system file-picker result to the WebView. Called by the host
     * activity before its own attachment handling.
     */
    /** Photo Picker do sistema (Android 13+) — galeria com Google Fotos; nulo se indisponível. */
    private Intent photoPicker(boolean multiple) {
        if (Build.VERSION.SDK_INT < 33) return null;
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.setType("image/*");
        if (multiple) intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
        if (intent.resolveActivity(c.getPackageManager()) == null) return null;
        return intent;
    }

    public boolean handleFileChooserResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_PICK) return false;
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (callback == null) return true;
        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null);
            return true;
        }
        List<Uri> uris = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        callback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        return true;
    }

    /** Install the in-page reload control next to the agent-preset label. */
    private void installShellHook() {
        try {
            if (shellHook == null) {
                InputStream in = c.getAssets().open("reload-button.js");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
                in.close();
                shellHook = buffer.toString("UTF-8");
            }
            web.evaluateJavascript(shellHook, null);
        } catch (Throwable ignored) {
            // The page remains usable without the reload shortcut.
        }
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
