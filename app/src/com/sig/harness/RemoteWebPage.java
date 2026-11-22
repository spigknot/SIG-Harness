package com.sig.harness;

import android.content.Context;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.URI;
import java.util.function.Consumer;

/** Browser page for a saved DSH address, without the local native bridges. */
final class RemoteWebPage {
    private final FrameLayout root;
    private final WebView web;
    private final WebFilePicker files;
    private final LinearLayout error;
    private final TextView detail;

    RemoteWebPage(Context context, URI address, WebFilePicker files, Consumer<String> openBrowser) {
        this.files = files;
        String url = address.toASCIIString();
        root = new FrameLayout(context);
        root.setBackgroundColor(Ui.BG);
        web = new WebView(context);
        web.setBackgroundColor(Ui.BG);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        root.addView(progress, new FrameLayout.LayoutParams(-1, Ui.dp(context, 3), Gravity.TOP));

        error = Ui.column(context);
        error.setGravity(Gravity.CENTER);
        error.setBackgroundColor(Ui.BG);
        int padding = Ui.dp(context, 22);
        error.setPadding(padding, padding, padding, padding);
        error.addView(Ui.title(context, "Não foi possível abrir esta aba", 18f));
        detail = Ui.text(context, "", 14f, Ui.TEXT_SOFT, Ui.REGULAR);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 18));
        error.addView(detail);
        error.addView(Ui.ghostButton(context, "Tentar novamente", v -> {
            error.setVisibility(View.GONE);
            web.loadUrl(url);
        }));
        error.setVisibility(View.GONE);
        root.addView(error, new FrameLayout.LayoutParams(-1, -1));

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                String scheme = target.getScheme();
                if (!("http".equals(scheme) || "https".equals(scheme))) return true;
                if (!request.isForMainFrame()) return false;
                int port = target.getPort();
                if (port == -1) port = "https".equals(scheme) ? 443 : 80;
                if (scheme.equals(address.getScheme()) && address.getHost().equalsIgnoreCase(target.getHost())
                        && port == address.getPort()) return false;
                if (request.hasGesture() && root.isShown()) {
                    openBrowser.accept(target.toString());
                } else {
                    showError(address.getAuthority(), "A página redirecionou para outro endereço.");
                }
                return true;
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
                if (request.isForMainFrame()) showError(address.getAuthority(), failure.getDescription());
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse failure) {
                if (request.isForMainFrame()) showError(address.getAuthority(), "HTTP " + failure.getStatusCode());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value < 100 ? View.VISIBLE : View.GONE);
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return files.open(view, callback, params);
            }
        });
        web.loadUrl(url);
    }

    private void showError(String host, CharSequence reason) {
        detail.setText(host + "\n" + reason);
        error.setVisibility(View.VISIBLE);
    }

    View view() { return root; }

    boolean goBack() {
        if (!web.canGoBack()) return false;
        error.setVisibility(View.GONE);
        web.goBack();
        return true;
    }

    void destroy() {
        files.cancel(web);
        root.removeView(web);
        web.destroy();
    }
}
