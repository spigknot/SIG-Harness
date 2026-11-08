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
        /** Aviso curto na tela (troca de provedor, por exemplo). */
        void voiceToast(String message);
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

    /** Provedor de transcrição em uso: 'muse' ou 'gemini'. */
    @JavascriptInterface public String sttProvider() {
        return prefs.sttProvider();
    }

    /** Troca o provedor pelo menu de toque longo no microfone. */
    @JavascriptInterface public void setSttProvider(final String id) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                String provider = "gemini".equals(id) ? "gemini" : "muse";
                String label = "gemini".equals(provider) ? "Gemini 3.5 Transcribe" : "Meta Muse Voice";
                prefs.setSttProvider(provider);
                String key = "gemini".equals(provider) ? prefs.geminiKey() : prefs.museKey();
                host.voiceToast("Transcrição: " + label);
                if (key == null || key.trim().isEmpty()) {
                    host.voiceNeedsSettings("Configure a chave de " + label + " para ditar.");
                }
            }
        });
    }

    /** Estado das chaves (apenas booleanos) e provedor ativo, para a aba de modelos. */
    @JavascriptInterface public String keysState() {
        boolean muse = prefs.museKey() != null && !prefs.museKey().trim().isEmpty();
        boolean gemini = prefs.geminiKey() != null && !prefs.geminiKey().trim().isEmpty();
        return "{\"muse\":" + muse + ",\"gemini\":" + gemini
                + ",\"provider\":\"" + prefs.sttProvider() + "\"}";
    }

    /** Grava chaves; texto vazio mantém o valor atual. */
    @JavascriptInterface public void setKeys(final String museKey, final String geminiKey) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (museKey != null && !museKey.trim().isEmpty()) prefs.setMuseKey(museKey.trim());
                if (geminiKey != null && !geminiKey.trim().isEmpty()) prefs.setGeminiKey(geminiKey.trim());
                host.voiceToast("Chaves de transcrição atualizadas");
            }
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
