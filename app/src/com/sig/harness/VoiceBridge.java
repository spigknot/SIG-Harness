package com.sig.harness;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;

/**
 * JavaScript bridge for dictation inside the WebView: starts one streaming
 * {@link SttSession} with the provider/key configured in the app, forwards
 * partials and the final transcript to the page, and leaves insertion to the
 * page's own composer (see assets/voice-input.js).
 */
public final class VoiceBridge {
    public interface Host {
        void voiceState(String state, String detail);
        void voicePartial(String text);
        void voiceFinal(String text);
        void voiceError(String message);
        void voiceNeedsSettings(String reason);
        void openSettings();
        void requestMicPermission();
    }

    private final Activity activity;
    private final Prefs prefs;
    private final Host host;
    private SttSession session;
    private boolean pendingStart;

    public VoiceBridge(Activity activity, Prefs prefs, Host host) {
        this.activity = activity;
        this.prefs = prefs;
        this.host = host;
    }

    @JavascriptInterface public void start() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { begin(); }
        });
    }

    @JavascriptInterface public void stop() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (session != null) session.stop();
                else host.voiceState("idle", "");
            }
        });
    }

    @JavascriptInterface public void openSettings() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { host.openSettings(); }
        });
    }

    @JavascriptInterface public void cancel() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (session != null) session.cancel();
                session = null;
                host.voiceState("idle", "");
            }
        });
    }

    /** Deliver the microphone permission verdict after the system dialog. */
    public void onPermission(boolean granted) {
        if (!granted) {
            pendingStart = false;
            host.voiceError("Permissão de microfone necessária para ditar.");
            return;
        }
        if (pendingStart) {
            pendingStart = false;
            begin();
        }
    }

    public void release() {
        pendingStart = false;
        if (session != null) {
            session.cancel();
            session = null;
        }
    }

    private void begin() {
        if (session != null && session.isActive()) return;
        String provider = prefs.sttProvider();
        String key = "gemini".equals(provider) ? prefs.geminiKey() : prefs.museKey();
        if (key == null || key.trim().isEmpty()) {
            host.voiceNeedsSettings("Configure a chave de transcrição para ditar.");
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            host.requestMicPermission();
            return;
        }
        final String label = "gemini".equals(provider) ? "Gemini 3.5 Transcribe" : "Meta Muse Voice";
        host.voiceState("connecting", "Conectando ao " + label + "…");
        final SttSession[] holder = new SttSession[1];
        SttSession created = new SttSession(activity, provider, key, new SttSession.Callback() {
            @Override public void onState(String state, String detail) {
                if (session != null && session == holder[0]) host.voiceState(state, detail);
            }
            @Override public void onPartial(String text) {
                if (session != null && session == holder[0]) host.voicePartial(text);
            }
            @Override public void onFinal(String text) {
                if (session != null && session == holder[0]) {
                    session = null;
                    holder[0] = null;
                    host.voiceFinal(text);
                }
            }
            @Override public void onError(String message) {
                if (session != null && session == holder[0]) {
                    session = null;
                    holder[0] = null;
                    host.voiceError(message);
                }
            }
        });
        holder[0] = created;
        session = created;
        created.start();
    }
}
