package com.sig.harness;

import android.webkit.JavascriptInterface;

/** Ganchos da interface web para o app, expostos como window.SigApp no WebView. */
public final class AppBridge {
    public interface Host {
        /** Encerra o dsh web atual e sobe um processo novo, recarregando a interface. */
        void restartHarness();
    }

    private final Host host;

    public AppBridge(Host host) {
        this.host = host;
    }

    @JavascriptInterface
    public void restartHarness() {
        if (host != null) host.restartHarness();
    }
}
