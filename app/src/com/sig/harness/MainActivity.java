package com.sig.harness;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.util.Base64;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Single-activity shell: start screen, conversations and native chat. */
public final class MainActivity extends Activity implements BridgeClient.Listener, AppBridge.Host {
    private static final int REQ_TERMUX = 41;
    private static final int REQ_ATTACH = 42;
    private static final int REQ_MIC = 43;
    private static final int MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
    private static final int SCREEN_NONE = 0;
    private static final int SCREEN_START = 1;
    private static final int SCREEN_SESSIONS = 2;
    private static final int SCREEN_CHAT = 3;
    private static final int SCREEN_SETTINGS = 4;
    private static final int SCREEN_WEB = 5;
    private static final String BRIDGE_VERSION = "1.2.1";

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout contentHost;
    private FrameLayout overlayHost;
    private int pendingUploads;
    private boolean pendingMic;
    private SttSession stt;
    private TextView connectionBanner;

    private StartScreen startScreen;
    private SessionsScreen sessionsScreen;
    private ChatScreen chatScreen;
    private SettingsScreen settingsScreen;

    private BridgeClient bridge;
    private int bridgePort;
    private int bridgePid;
    private String runtimeState = "stopped";
    private int screen = SCREEN_NONE;

    private boolean startFlow;
    private boolean polling;
    private boolean scanning;
    private boolean pendingStartPermission;
    private boolean finishing;
    private long pollDeadline;
    private boolean refreshScheduled;
    private boolean runtimeWasReady;
    private String bridgeBase64;
    private String patchBase64;
    private String payloadBase64;
    private boolean restartingWeb;

    private View permissionOverlay;
    private JSONObject permissionData;
    private WebScreen webScreen;
    private boolean webStarting;
    private View modelSheet;
    private final List<JSONObject> permissionQueue = new ArrayList<>();

    /** Revalida o Termux ao voltar do navegador: instalar por fora não deve exigir reabrir o app. */
    @Override protected void onResume() {
        super.onResume();
        if (startScreen != null) startScreen.setTermuxInstalled(TermuxRunner.isTermuxInstalled(this));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        prefs = new Prefs(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        contentHost = new FrameLayout(this);
        root.addView(contentHost, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        connectionBanner = buildBanner();
        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bannerLp.gravity = Gravity.TOP;
        root.addView(connectionBanner, bannerLp);
        overlayHost = new FrameLayout(this);
        root.addView(overlayHost, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        showStart();
        ui.postDelayed(new Runnable() {
            @Override public void run() { checkExistingBridge(false); }
        }, 250);
    }

    private TextView buildBanner() {
        TextView v = Ui.text(this, "Sem conexão · toque para reconectar", 12.5f, Color.WHITE, Ui.MEDIUM);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Ui.RED);
        v.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        v.setVisibility(View.GONE);
        v.setClickable(true);
        v.setElevation(Ui.dp(this, 8));
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { reconnect(); }
        });
        return v;
    }

    private void updateBanner() {
        boolean show = bridge == null && (screen == SCREEN_SESSIONS || screen == SCREEN_SETTINGS);
        connectionBanner.setVisibility(show ? View.VISIBLE : View.GONE);
        contentHost.setPadding(0, show ? Ui.dp(this, 42) : 0, 0, 0);
    }

    private void setScreen(View view) {
        contentHost.removeAllViews();
        contentHost.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        updateBanner();
    }

    // ---------------------------------------------------------------- screens

    private void showStart() {
        if (startScreen == null) startScreen = new StartScreen(this, startHost);
        startScreen.setTermuxInstalled(TermuxRunner.isTermuxInstalled(this));
        screen = SCREEN_START;
        setScreen(startScreen.view());
        if (bridge == null && !startFlow) {
            startScreen.resetSteps();
            startScreen.setBusy(false);
            startScreen.setStatus("Harness parado", Ui.TEXT_SOFT);
        }
    }

    private String assetBase64(String name) {
        if ("sig-bridge.mjs".equals(name) && bridgeBase64 != null) return bridgeBase64;
        if ("acp.patch.yml".equals(name) && patchBase64 != null) return patchBase64;
        if ("dsh-web.tgz".equals(name) && payloadBase64 != null) return payloadBase64;
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            in.close();
            String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            if ("sig-bridge.mjs".equals(name)) bridgeBase64 = encoded;
            else if ("acp.patch.yml".equals(name)) patchBase64 = encoded;
            else if ("dsh-web.tgz".equals(name)) payloadBase64 = encoded;
            return encoded;
        } catch (Exception e) {
            throw new IllegalStateException("Bridge embutido não encontrado: " + name, e);
        }
    }

    /** Reads an asset that may be absent in older builds, without breaking the launch. */
    private String assetBase64OrEmpty(String name) {
        try {
            return assetBase64(name);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void showSessions() {
        stopStt();
        if (sessionsScreen == null) sessionsScreen = new SessionsScreen(this, sessionsHost);
        sessionsScreen.setOnline(bridge != null, bridge != null ? "Harness online" : "Offline");
        screen = SCREEN_SESSIONS;
        setScreen(sessionsScreen.view());
        if (bridge != null) fetchSessions();
    }

    private void openChat(String id, String title, String cwd) {
        stopStt();
        if (chatScreen != null) chatScreen.release();
        chatScreen = new ChatScreen(this, chatHost, id, title, cwd);
        chatScreen.setConnection(bridge != null, "Conexão perdida com o bridge.");
        chatScreen.setModel(null);
        screen = SCREEN_CHAT;
        setScreen(chatScreen.view());
        prefs.setLastSessionId(id);
        if (bridge != null) {
            JSONObject params = new JSONObject();
            try { params.put("sessionId", id); } catch (Exception ignored) { }
            final String openId = id;
            bridge.request("session.open", params, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) {
                    JSONObject session = result == null ? null : result.optJSONObject("session");
                    if (session != null && chatScreen != null && chatScreen.sessionId().equals(openId)) {
                        chatScreen.setConfigOptions(session.optJSONArray("configOptions"));
                    }
                }
                @Override public void onError(String code, String message) {
                    if (chatScreen != null && chatScreen.sessionId().equals(openId)) chatScreen.showError(friendlyError(message));
                }
            });
            loadHistory(id);
        }
    }

    private void showSettings() {
        stopStt();
        if (settingsScreen == null) settingsScreen = new SettingsScreen(this, settingsHost);
        settingsScreen.setCwd(prefs.cwd());
        settingsScreen.setPermission(TermuxRunner.hasPermission(this));
        settingsScreen.setConnection(runtimeState, runtimeDetail(), bridgePort, bridgePid);
        settingsScreen.setBoot(prefs.boot(), TermuxRunner.isBootInstalled(this));
        settingsScreen.setStt(prefs.sttProvider(), prefs.museKey(), prefs.geminiKey());
        screen = SCREEN_SETTINGS;
        setScreen(settingsScreen.view());
    }

    private String runtimeDetail() {
        if (bridge == null) return "Offline";
        if ("ready".equals(runtimeState)) return "Runtime pronto";
        if ("starting".equals(runtimeState)) return "Iniciando runtime…";
        if ("error".equals(runtimeState)) return "Runtime com erro";
        return "Harness parado";
    }

    // ---------------------------------------------------------------- start screen host

    private final StartScreen.Host startHost = new StartScreen.Host() {
        @Override public void onStart() { startHarnessFlow(); }
        @Override public void onConnectExisting() { checkExistingBridge(true); }
        @Override public void onOpenTermux() { TermuxRunner.openTermux(MainActivity.this); }
        @Override public void onInstallTermux() { openInstallPage(); }
        @Override public void onOpenSettings() { showVoiceSettings(); }
        @Override public void onCopyBootstrap() { copyBootstrapCommand(); }
        @Override public void onCopyTermuxSetup() { copyTermuxSetup(); }
    };

    private void openInstallPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")));
        } catch (Throwable t) {
            toast("Instale o Termux pelo F-Droid ou GitHub.");
        }
    }

    // ---------------------------------------------------------------- sessions screen host

    private final SessionsScreen.Host sessionsHost = new SessionsScreen.Host() {
        @Override public void onOpenSession(String id, String title, String cwd, boolean webOnly) {
            if (webOnly) showWebOnlyDialog(title); else openChat(id, title, cwd);
        }
        @Override public void onNewChat() { newChat(); }
        @Override public void onOpenSettings() { showSettings(); }
        @Override public void onRefresh() { fetchSessions(); }
        @Override public void onRenameSession(String id, String title) { renameSession(id, title); }
        @Override public void onArchiveSession(String id, String title) { confirmArchive(id, title); }
        @Override public void onDeleteSession(String id, String title) { confirmDelete(id, title); }
        @Override public void onUnarchiveSession(String id, String title) {
            prefs.setArchived(id, false);
            fetchSessions();
            toast("Conversa desarquivada.");
        }
    };

    // ---------------------------------------------------------------- chat host

    private final ChatScreen.Host chatHost = new ChatScreen.Host() {
        @Override public void onBack() { showSessions(); }
        @Override public void onNewChat() { newChat(); }
        @Override public void onOpenSessions() { showSessions(); }
        @Override public void onOpenSettings() { showSettings(); }
        @Override public void onStopHarness() { confirmStopHarness(); }
        @Override public void onSend(final String sessionId, final String text, final JSONArray attachments) {
            if (bridge == null) {
                if (chatScreen != null) chatScreen.showError("O harness não está conectado. Volte ao início e inicie novamente.");
                return;
            }
            keepScreenOn(true);
            JSONObject params = new JSONObject();
            try {
                params.put("sessionId", sessionId);
                params.put("text", text);
                if (attachments != null && attachments.length() > 0) params.put("attachments", attachments);
            } catch (Exception ignored) { }
            bridge.request("session.prompt", params, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) {
                    if (chatScreen != null) chatScreen.setGenerating(true);
                }
                @Override public void onError(String code, String message) {
                    keepScreenOn(false);
                    if (chatScreen != null) chatScreen.showError(friendlyError(message));
                }
            });
        }
        @Override public void onQueue(final String sessionId, final String text, final JSONArray attachments) {
            if (bridge == null) { toast("O harness não está conectado."); return; }
            JSONObject params = new JSONObject();
            try {
                params.put("sessionId", sessionId);
                params.put("text", text);
                params.put("queue", true);
                if (attachments != null && attachments.length() > 0) params.put("attachments", attachments);
            } catch (Exception ignored) { }
            bridge.request("session.prompt", params, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) {
                    if (chatScreen == null || !sessionId.equals(chatScreen.sessionId())) return;
                    if (result != null && result.optBoolean("queued", false)) {
                        chatScreen.addQueuedBubble(result.optString("queueId", ""), text, attachments, result.optInt("position", 1));
                    } else {
                        chatScreen.addSentUserMessage(text, attachments);
                        chatScreen.setGenerating(true);
                    }
                }
                @Override public void onError(String code, String message) {
                    toast(friendlyError(message));
                }
            });
        }
        @Override public void onMic() {
            if (stt != null) { stt.stop(); return; }
            if (chatScreen == null) return;
            String provider = prefs.sttProvider();
            String key = "gemini".equals(provider) ? prefs.geminiKey() : prefs.museKey();
            if (key.isEmpty()) {
                toast("Configure a API key em Ajustes → Transcrição de voz.");
                showSettings();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingMic = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
            startStt(provider, key);
        }
        @Override public void onPickAttachments() { pickAttachments(); }
        @Override public void onClearQueue(final String sessionId) {
            if (bridge == null) return;
            JSONObject params = new JSONObject();
            try { params.put("sessionId", sessionId); } catch (Exception ignored) { }
            bridge.request("session.queueClear", params, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) { }
                @Override public void onError(String code, String message) { toast(friendlyError(message)); }
            });
        }
        @Override public void onPromoteQueued(final String sessionId, final String queueId) {
            if (bridge == null) { toast("O harness não está conectado."); return; }
            JSONObject params = new JSONObject();
            try { params.put("sessionId", sessionId); params.put("queueId", queueId); } catch (Exception ignored) { }
            bridge.request("session.queuePromote", params, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) { toast("Enviando agora…"); }
                @Override public void onError(String code, String message) { toast(friendlyError(message)); }
            });
        }
        @Override public void onCancel(String sessionId) {
            if (bridge == null) return;
            JSONObject params = new JSONObject();
            try { params.put("sessionId", sessionId); } catch (Exception ignored) { }
            bridge.request("session.cancel", params, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) { toast("Interrompendo…"); }
                @Override public void onError(String code, String message) { toast(message); }
            });
        }
        @Override public void onModelSelected(String sessionId, String configId, String value) { }
        @Override public void onReconnect() { reconnect(); }
        @Override public void onPickModel(String sessionId, JSONArray configOptions) { showModelSheet(sessionId, configOptions); }
        @Override public void onRestartRuntime() { restartRuntime(); }
        @Override public void onRename(String sessionId, String currentTitle) { renameSession(sessionId, currentTitle); }
    };

    // ---------------------------------------------------------------- settings host

    private final SettingsScreen.Host settingsHost = new SettingsScreen.Host() {
        @Override public void onBack() { backFromSettings(); }
        @Override public void onSaveCwd(String value) {
            String cwd = value == null ? "" : value.trim();
            if (!cwd.startsWith("/")) { toast("Use um caminho absoluto (começa com /)."); return; }
            prefs.setCwd(cwd);
            if (settingsScreen != null) settingsScreen.setCwdSaved();
            configureBoot();
            toast("Pasta salva.");
        }
        @Override public void onStopHarness() { confirmStopHarness(); }
        @Override public void onRestartRuntime() { restartRuntime(); }
        @Override public void onRequestTermuxPermission() {
            if (TermuxRunner.hasPermission(MainActivity.this)) { openAppSettings(); return; }
            requestPermissions(new String[]{TermuxRunner.PERMISSION}, REQ_TERMUX);
        }
        @Override public void onOpenAppSettings() { openAppSettings(); }
        @Override public void onOpenTermux() { TermuxRunner.openTermux(MainActivity.this); }
        @Override public void onToggleBoot(boolean enabled) {
            prefs.setBoot(enabled);
            if (settingsScreen != null) settingsScreen.setBoot(enabled, TermuxRunner.isBootInstalled(MainActivity.this));
            configureBoot();
            if (enabled && !TermuxRunner.isBootInstalled(MainActivity.this)) showBootInstallDialog();
        }
        @Override public void onInstallBoot() { openTermuxBootInstall(); }
        @Override public void onSaveStt(String provider, String museKey, String geminiKey) {
            prefs.setSttProvider(provider);
            prefs.setMuseKey(museKey);
            prefs.setGeminiKey(geminiKey);
            if (settingsScreen != null) settingsScreen.setSttSaved();
            toast("Configuração de transcrição salva.");
        }
        @Override public void onOpenWeb() { startWebMode(); }
    };

    private void backFromSettings() {
        if (webScreen != null) {
            screen = SCREEN_WEB;
            setScreen(webScreen.view());
        } else {
            showStart();
        }
    }

    private void showVoiceSettings() {
        if (prefs == null) prefs = new Prefs(this);
        VoiceSettings.show(this, prefs);
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            toast("Abra Ajustes do Android > Apps > SIG Harness.");
        }
    }

    // ---------------------------------------------------------------- connection

    private BridgeClient probeBridge() {
        int base = prefs.basePort();
        List<Integer> ports = new ArrayList<Integer>();
        int last = prefs.lastPort();
        if (last >= base && last < base + 16) ports.add(last);
        for (int i = 0; i < 16; i++) {
            int p = base + i;
            if (!ports.contains(p)) ports.add(p);
        }
        for (int i = 0; i < ports.size(); i++) {
            if (finishing) return null;
            try {
                return BridgeClient.connect("127.0.0.1", ports.get(i), prefs.token(), 700);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void checkExistingBridge(final boolean userInitiated) {
        if (bridge != null) {
            if (userInitiated) doStartWeb();
            return;
        }
        if (userInitiated && startScreen != null) {
            startScreen.setStatus("Procurando o harness…", Ui.TEXT_SOFT);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final BridgeClient found = probeBridge();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (found != null) {
                            adoptBridge(found, false);
                            return;
                        }
                        if (startScreen != null && !startFlow) startScreen.setStatus("Harness parado", Ui.TEXT_SOFT);
                        if (userInitiated) {
                            toast("O harness não está rodando.");
                            showStart();
                        } else {
                            startHarnessFlow();
                        }
                    }
                });
            }
        }, "sig-probe").start();
    }

    /** Liberação do Termux, para colar quando o RUN_COMMAND é recusado. */
    private void copyTermuxSetup() {
        String command = "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings";
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard == null) { toast("Comando: " + command); return; }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SIG Harness", command));
        toast("Copiado — cole no Termux (fora do su) e depois recarregue o Termux.");
    }

    /** Alternativa quando o Termux não aceita RUN_COMMAND: comando para colar no Termux. */
    private void copyBootstrapCommand() {
        String command = "pkg install -y nodejs && npm i -g @deepseek-ai/dsh@"
                + TermuxRunner.DSH_BOOTSTRAP_VERSION
                + " && echo 'pronto: volte ao SIG Harness e toque em Iniciar Harness'";
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard == null) { toast("Comando: " + command); return; }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SIG Harness", command));
        toast("Comando copiado — cole no Termux e execute.");
    }

    private void startHarnessFlow() {
        if (startFlow) return;
        // APK novo = pacote dsh-web novo: provisiona e reinicia o host antes de tudo,
        // senão a interface continua servindo os módulos da versão anterior.
        String payload = assetBase64OrEmpty("dsh-web.tgz");
        String hash = payloadHash(payload);
        if (!hash.isEmpty() && !hash.equals(prefs.appliedPayload()) && TermuxRunner.isTermuxInstalled(this) && TermuxRunner.hasPermission(this)) {
            prefs.setAppliedPayload(hash);
            startFlow = true;
            doRestartHarness();
            startFlow = false;
            return;
        }
        if (bridge != null) {
            doStartWeb();
            return;
        }
        if (!TermuxRunner.isTermuxInstalled(this)) {
            showStart();
            startScreen.showError("Termux não encontrado", "O SIG Harness usa o Termux para executar o DSH. Instale o Termux (F-Droid ou GitHub) e toque em Iniciar Harness de novo.");
            return;
        }
        if (!TermuxRunner.hasPermission(this)) {
            pendingStartPermission = true;
            showStart();
            startScreen.setBusy(true);
            startScreen.resetSteps();
            startScreen.setStatus("Permissão do Termux necessária", Ui.AMBER);
            startScreen.setStep(0, 1, "pedindo permissão");
            requestPermissions(new String[]{TermuxRunner.PERMISSION}, REQ_TERMUX);
            return;
        }
        showStart();
        beginLaunch();
    }

    private void beginLaunch() {
        startFlow = true;
        pendingStartPermission = false;
        startScreen.resetSteps();
        startScreen.setBusy(true);
        startScreen.hideError();
        startScreen.setStatus("Iniciando o harness…", Ui.TEXT_SOFT);
        startScreen.setStep(0, 1, "enviando comando ao Termux");
        try {
            TermuxRunner.startBridge(this, prefs.token(), prefs.cwd(), prefs.basePort(), assetBase64("sig-bridge.mjs"), assetBase64("acp.patch.yml"), assetBase64OrEmpty("dsh-web.tgz"));
        } catch (SecurityException se) {
            failStart("Permissão do Termux negada", "Conceda a permissão \"Executar comandos no Termux\" nos Ajustes do app e tente de novo.");
            return;
        } catch (Throwable t) {
            failStart("Não foi possível falar com o Termux", String.valueOf(t));
            return;
        }
        startScreen.setStep(0, 2, "comando enviado");
        startScreen.setStep(1, 1, "instalando o que faltar");
        startScreen.setStep(2, 1, "aguardando o bridge");
        toast("Iniciando o SIG Harness…");
        polling = true;
        pollDeadline = System.currentTimeMillis() + 90000L;
        ui.post(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!polling || finishing) return;
            if (bridge != null) { polling = false; return; }
            if (System.currentTimeMillis() > pollDeadline) {
                failStart("O harness não respondeu", "O Termux pode estar iniciando. Verifique se o Termux está instalado, com o bridge em ~/.sig-harness/sig-bridge.mjs, e tente novamente.");
                return;
            }
            if (!scanning) {
                scanning = true;
                new Thread(new Runnable() {
                    @Override public void run() {
                        final BridgeClient found = probeBridge();
                        ui.post(new Runnable() {
                            @Override public void run() {
                                scanning = false;
                                if (found != null && !finishing) adoptBridge(found, true);
                            }
                        });
                    }
                }, "sig-poll").start();
            }
            ui.postDelayed(this, 420);
        }
    };

    private void adoptBridge(final BridgeClient client, boolean fromStart) {
        if (finishing) { client.close(); return; }
        if (bridge != null && bridge != client) bridge.close();
        JSONObject welcome = client.welcome();
        String version = welcome == null ? "" : welcome.optString("version", "");
        if (!BRIDGE_VERSION.equals(version)) {
            bridge = null;
            startFlow = false;
            JSONObject shutdown = new JSONObject();
            client.request("bridge.shutdown", shutdown, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) { }
                @Override public void onError(String code, String message) { }
            });
            toast("Atualizando o bridge do SIG Harness…");
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    client.close();
                    startHarnessFlow();
                }
            }, 900);
            return;
        }
        bridge = client;
        client.setListener(this);
        if (welcome != null) {
            bridgePort = welcome.optInt("port", prefs.basePort());
            bridgePid = welcome.optInt("pid", 0);
            runtimeState = welcome.optString("state", "stopped");
        } else {
            bridgePort = prefs.basePort();
        }
        prefs.setLastPort(bridgePort);
        configureBoot();
        if (startScreen != null) {
            startScreen.setStep(1, 2, "pronto");
        startScreen.setStep(2, 2, "conectado");
            startScreen.setStep(3, 2, "token aceito");
            startScreen.setStatus("Conectado · autenticando", Ui.TEXT_SOFT);
        }
        updateBanner();
        if (sessionsScreen != null) sessionsScreen.setOnline(true, "Harness online");
        if (chatScreen != null) chatScreen.setConnection(true, null);
        if (startScreen != null) {
            startScreen.setBusy(true);
            startScreen.setStep(3, 2, "token aceito");
            startScreen.setStep(4, 1, "abrindo interface web");
            startScreen.setStatus("Abrindo a interface web…", Ui.TEXT_SOFT);
        }
        doStartWeb();
    }

    private void failStart(String title, String detail) {
        startFlow = false;
        polling = false;
        scanning = false;
        showStart();
        if (startScreen != null) {
            startScreen.setBusy(false);
            startScreen.showError(title, detail);
            startScreen.setErrorRetryLabel("Tentar novamente");
        }
    }

    private void onRuntimeReady() {
        runtimeState = "ready";
        boolean first = !runtimeWasReady;
        runtimeWasReady = true;
        polling = false;
        startFlow = false;
        if (startScreen != null) {
            startScreen.setStep(4, 2, "pronto");
            startScreen.setStatus("Harness pronto", Ui.GREEN);
            startScreen.setBusy(false);
        }
        updateBanner();
        if (sessionsScreen != null) sessionsScreen.setOnline(true, "Harness online");
        if (chatScreen != null) chatScreen.setConnection(true, null);
        if (restartingWeb) {
            restartingWeb = false;
            doStartWeb();
            return;
        }
        if (screen != SCREEN_WEB) doStartWeb();
        if (first) toast("Harness online.");
    }

    @Override public void restartHarness() {
        ui.post(new Runnable() {
            @Override public void run() { doRestartHarness(); }
        });
    }

    /** Mata o dsh web em execução, reenvia o comando ao Termux e recarrega a interface. */
    /** SHA-256 do base64 do pacote embutido — muda a cada build que altere dsh-web/. */
    private String payloadHash(String payload) {
        if (payload == null || payload.isEmpty()) return "";
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    private void doRestartHarness() {
        restartingWeb = true;
        polling = false;
        scanning = false;
        if (bridge != null) { try { bridge.close(); } catch (Throwable ignored) { } }
        bridge = null;
        runtimeState = "stopped";
        runtimeWasReady = false;
        if (webScreen != null) webScreen.setWaiting(true, "Reiniciando o harness…");
        try {
            TermuxRunner.restartBridge(this, prefs.token(), prefs.cwd(), prefs.basePort(),
                    assetBase64("sig-bridge.mjs"), assetBase64("acp.patch.yml"), assetBase64OrEmpty("dsh-web.tgz"));
        } catch (Throwable error) {
            restartingWeb = false;
            toast("Não foi possível reiniciar: " + error);
            return;
        }
        polling = true;
        pollDeadline = System.currentTimeMillis() + 90000L;
        // O processo antigo precisa sair antes da primeira varredura, senão a ponte
        // morta ainda responde e devolve a URL expirada.
        ui.postDelayed(pollRunnable, 2500L);
    }

    private void reconnect() {
        if (bridge != null) return;
        checkExistingBridge(true);
    }

    private void onBridgeLost(String reason) {
        bridge = null;
        runtimeState = "stopped";
        runtimeWasReady = false;
        keepScreenOn(false);
        updateBanner();
        if (sessionsScreen != null) sessionsScreen.setOnline(false, "Offline");
        if (chatScreen != null) chatScreen.setConnection(false, "Sem conexão · toque para reconectar");
        if (screen == SCREEN_START && startScreen != null) {
            startScreen.setBusy(false);
            startScreen.setStatus("Harness parado", Ui.TEXT_SOFT);
        }
    }

    // ---------------------------------------------------------------- data

    private void fetchSessions() {
        if (bridge == null) {
            if (sessionsScreen != null) sessionsScreen.setError("O harness não está conectado.");
            return;
        }
        if (sessionsScreen != null && sessionsScreen.view().getParent() != null) sessionsScreen.setLoading(true);
        JSONObject params = new JSONObject();
        bridge.request("sessions.list", params, new BridgeClient.Callback() {
            @Override public void onResult(JSONObject result) {
                JSONArray list = result == null ? null : result.optJSONArray("sessions");
                JSONArray active = new JSONArray();
                JSONArray archived = new JSONArray();
                JSONArray visible = new JSONArray();
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.optJSONObject(i);
                        if (item == null) continue;
                        String id = item.optString("id", "");
                        if (id.isEmpty() || prefs.isDeleted(id)) continue;
                        visible.put(item);
                        if (prefs.isArchived(id)) archived.put(item); else active.put(item);
                    }
                }
                if (sessionsScreen != null) {
                    sessionsScreen.setLoading(false);
                    sessionsScreen.setSessions(active, archived);
                    sessionsScreen.setOnline(true, "Harness online");
                }
                refreshChatTitle(visible);
            }
            @Override public void onError(String code, String message) {
                if (sessionsScreen != null) {
                    sessionsScreen.setLoading(false);
                    sessionsScreen.setError(friendlyError(message));
                }
            }
        });
    }

    private void refreshChatTitle(JSONArray list) {
        if (chatScreen == null || list == null) return;
        String id = chatScreen.sessionId();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            if (id.equals(item.optString("id"))) {
                String title = item.optString("title", "");
                if (!title.isEmpty() && !"Nova conversa".equals(title)) chatScreen.setTitle(title);
                return;
            }
        }
    }

    private void scheduleSessionsRefresh() {
        if (refreshScheduled) return;
        refreshScheduled = true;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                refreshScheduled = false;
                if (bridge == null) return;
                if (screen == SCREEN_SESSIONS) fetchSessions();
                else if (screen == SCREEN_CHAT) {
                    JSONObject params = new JSONObject();
                    bridge.request("sessions.list", params, new BridgeClient.Callback() {
                        @Override public void onResult(JSONObject result) {
                            refreshChatTitle(result == null ? null : result.optJSONArray("sessions"));
                        }
                        @Override public void onError(String code, String message) { }
                    });
                }
            }
        }, 700);
    }

    private void loadHistory(String id) {
        JSONObject params = new JSONObject();
        try { params.put("sessionId", id); } catch (Exception ignored) { }
        final ChatScreen target = chatScreen;
        bridge.request("session.history", params, new BridgeClient.Callback() {
            @Override public void onResult(JSONObject result) {
                if (target != null && target == chatScreen) {
                    target.setHistory(result);
                    if ("ready".equals(runtimeState)) target.setConnection(true, null);
                }
            }
            @Override public void onError(String code, String message) {
                if (target != null && target == chatScreen) target.showError(friendlyError(message));
            }
        });
    }

    private void newChat() {
        if (bridge == null) { toast("O harness não está conectado."); return; }
        if (sessionsScreen != null) sessionsScreen.setNewChatBusy(true);
        JSONObject params = new JSONObject();
        try {
            params.put("cwd", prefs.cwd());
            if (!prefs.modelValue().isEmpty()) params.put("model", prefs.modelValue());
            params.put("reasoningEffort", prefs.reasoning());
        } catch (Exception ignored) { }
        bridge.request("sessions.new", params, new BridgeClient.Callback() {
            @Override public void onResult(JSONObject result) {
                if (sessionsScreen != null) sessionsScreen.setNewChatBusy(false);
                JSONObject session = result == null ? null : result.optJSONObject("session");
                if (session == null) { toast("Não foi possível criar a conversa."); return; }
                openChat(session.optString("id", ""), "Nova conversa", session.optString("cwd", prefs.cwd()));
                scheduleSessionsRefresh();
            }
            @Override public void onError(String code, String message) {
                if (sessionsScreen != null) sessionsScreen.setNewChatBusy(false);
                toast(friendlyError(message));
            }
        });
    }

    // ---------------------------------------------------------------- events

    @Override public void onWelcomed(JSONObject welcome) {
        if (welcome == null) return;
        runtimeState = welcome.optString("state", runtimeState);
        bridgePort = welcome.optInt("port", bridgePort);
        bridgePid = welcome.optInt("pid", bridgePid);
        if (startScreen != null) {
            startScreen.setStep(2, 2, "conectado");
            startScreen.setStep(3, 2, "token aceito");
        }
        if ("ready".equals(runtimeState)) onRuntimeReady();
    }

    @Override public void onEvent(String event, JSONObject data) {
        if (event == null) return;
        if ("harness.status".equals(event)) {
            String state = data.optString("state", "");
            String detail = data.optString("detail", "");
            runtimeState = state;
            if (startScreen != null) {
                if ("starting".equals(state)) {
                    startScreen.setStep(4, 1, detail);
                    startScreen.setStatus("Iniciando o runtime…", Ui.TEXT_SOFT);
                    startScreen.setBusy(true);
                } else if ("error".equals(state)) {
                    startScreen.setStep(4, 3, detail);
                    startScreen.setStatus("O runtime falhou", Ui.RED);
                    startScreen.setLog(detail);
                }
            }
            if ("ready".equals(state)) onRuntimeReady();
        } else if ("harness.log".equals(event)) {
            if (startScreen != null) startScreen.setLog(data.optString("line", ""));
        } else if ("sessions.changed".equals(event)) {
            scheduleSessionsRefresh();
        } else if ("session.update".equals(event)) {
            String sid = data.optString("sessionId", "");
            JSONObject update = data.optJSONObject("update");
            if (chatScreen != null && sid.equals(chatScreen.sessionId())) chatScreen.onUpdate(update);
        } else if ("session.promptStarted".equals(event)) {
            String sid = data.optString("sessionId", "");
            if (chatScreen != null && sid.equals(chatScreen.sessionId())) {
                chatScreen.setGenerating(true);
                String queueId = data.optString("queueId", "");
                if (!queueId.isEmpty()) chatScreen.onQueuedStarted(queueId);
            }
            keepScreenOn(true);
        } else if ("session.queueChanged".equals(event)) {
            String sid = data.optString("sessionId", "");
            if (chatScreen != null && sid.equals(chatScreen.sessionId())) {
                chatScreen.onQueueChanged(data.optJSONArray("items"), data.optString("reason", ""), data.optString("queueId", ""));
            }
        } else if ("session.promptDone".equals(event)) {
            String sid = data.optString("sessionId", "");
            keepScreenOn(false);
            if (chatScreen != null && sid.equals(chatScreen.sessionId())) {
                chatScreen.onPromptDone(withFriendlyError(data));
            }
            scheduleSessionsRefresh();
            ui.postDelayed(new Runnable() {
                @Override public void run() { scheduleSessionsRefresh(); }
            }, 2500);
        } else if ("session.promptStalled".equals(event)) {
            String sid = data.optString("sessionId", "");
            if (chatScreen != null && sid.equals(chatScreen.sessionId())) chatScreen.showStalled(data.optLong("idleMs", 0));
        } else if ("session.permission".equals(event)) {
            enqueuePermission(data);
        } else if ("session.permissionResolved".equals(event)) {
            dismissPermissionById(data.optString("requestId", ""));
        }
    }

    @Override public void onDisconnected(String reason) {
        if (finishing) return;
        onBridgeLost(reason);
    }

    private JSONObject withFriendlyError(JSONObject data) {
        if (data == null) return new JSONObject();
        try {
            JSONObject copy = new JSONObject(data.toString());
            String error = copy.optString("error", "");
            if (!error.isEmpty()) copy.put("error", friendlyError(error));
            return copy;
        } catch (Exception e) {
            return data;
        }
    }

    private String friendlyError(String message) {
        if (message == null || message.isEmpty()) return "Erro desconhecido.";
        String lower = message.toLowerCase();
        if (lower.contains("defer_loading") || lower.contains("all tools cannot be deferred")) {
            return "Esta conversa foi criada na interface web e não pode rodar no modo nativo. Abra o modo web ou crie uma nova conversa.";
        }
        if (lower.contains("link de autenticacao") || lower.contains("link de autenticação")) {
            return "O dsh web está rodando, mas o link de autenticação está desatualizado. Reinicie o dsh web no Termux e tente de novo.";
        }
        if (lower.contains("429") || lower.contains("overloaded") || lower.contains("sobrecarreg")) {
            return "O provedor do modelo está sobrecarregado agora. Tente novamente em alguns instantes.";
        }
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("api key") || lower.contains("credential")) {
            return "Credenciais do modelo inválidas. Verifique a chave do provedor no DSH do Termux.";
        }
        if (lower.contains("sandbox")) {
            return "O sandbox do Termux não está disponível. O SIG usa acesso total para funcionar no Android.";
        }
        if (lower.contains("timeout") || lower.contains("tempo esgotado")) {
            return "O Termux demorou demais para responder. Verifique se o harness ainda está rodando.";
        }
        return message;
    }

    // ---------------------------------------------------------------- permissions overlay

    private void enqueuePermission(JSONObject data) {
        if (data == null) return;
        if (permissionOverlay != null) { permissionQueue.add(data); return; }
        showPermission(data);
    }

    private void showPermission(final JSONObject data) {
        permissionData = data;
        final String requestId = data.optString("requestId", "");
        JSONObject details = data.optJSONObject("toolCallDetails");
        JSONObject toolCall = data.optJSONObject("toolCall");
        String toolTitle = "Ferramenta";
        String command = "";
        if (details != null) {
            toolTitle = details.optString("title", toolTitle);
            JSONObject raw = details.optJSONObject("rawInput");
            if (raw != null) {
                command = raw.optString("command", "");
                if (command.isEmpty()) command = raw.optString("description", "");
                if (command.isEmpty()) command = raw.optString("path", "");
            }
        } else if (toolCall != null) {
            toolTitle = toolCall.optString("title", toolTitle);
        }

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(210, 4, 6, 10));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        FrameLayout holder = new FrameLayout(this);
        FrameLayout.LayoutParams holderLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        holderLp.gravity = Gravity.BOTTOM;
        overlay.addView(holder, holderLp);

        LinearLayout card = Ui.column(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.BG_ELEVATED);
        float r = Ui.dp(this, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(bg);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 24));

        LinearLayout titleRow = Ui.row(this);
        TextView glyph = Ui.text(this, "⚠", 18f, Ui.AMBER, Ui.REGULAR);
        titleRow.addView(glyph);
        TextView title = Ui.text(this, "Permissão necessária", 20f, Ui.TEXT, Ui.BOLD);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        titleRow.addView(title, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(titleRow);

        TextView subtitle = Ui.text(this, "O agente quer executar uma ação no Termux. Revise antes de permitir.", 12.5f, Ui.MUTED, Ui.REGULAR);
        subtitle.setLineSpacing(Ui.dp(this, 3), 1.0f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 8);
        card.addView(subtitle, subLp);

        TextView toolName = Ui.text(this, toolTitle, 13f, Ui.ACCENT, Ui.MONO);
        toolName.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        card.addView(toolName);

        if (!command.isEmpty()) {
            TextView code = Ui.text(this, command, 11.5f, Ui.TEXT_SOFT, Ui.MONO);
            code.setBackground(Ui.round(Ui.CODE_BG, 10f, this));
            code.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            code.setMaxLines(6);
            code.setTextIsSelectable(true);
            card.addView(code, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        JSONArray options = data.optJSONArray("options");
        List<JSONObject> allows = new ArrayList<JSONObject>();
        List<JSONObject> rejects = new ArrayList<JSONObject>();
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                if (option == null) continue;
                String kind = option.optString("kind", "");
                if (kind.startsWith("allow")) allows.add(option); else rejects.add(option);
            }
        }
        for (int i = 0; i < allows.size(); i++) {
            JSONObject option = allows.get(i);
            final String optionId = option.optString("optionId", "");
            String kind = option.optString("kind", "");
            String label = "allow_always".equals(kind) ? "Permitir sempre" : "Permitir";
            TextView button = Ui.button(this, label, Ui.ACCENT, Color.WHITE, 15f, 15f);
            button.setTypeface(Ui.MEDIUM);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { answerPermission(requestId, optionId); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
            lp.topMargin = Ui.dp(this, 14);
            card.addView(button, lp);
        }
        for (int i = 0; i < rejects.size(); i++) {
            JSONObject option = rejects.get(i);
            final String optionId = option.optString("optionId", "");
            String kind = option.optString("kind", "");
            String label = "reject_always".equals(kind) ? "Negar sempre" : "Negar";
            TextView button = Ui.button(this, label, Color.TRANSPARENT, Ui.RED, 15f, 15f);
            button.setBackground(Ui.rippleStroke(Color.TRANSPARENT, Color.rgb(110, 44, 44), 1, 15f, this));
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { answerPermission(requestId, optionId); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
            lp.topMargin = Ui.dp(this, 10);
            card.addView(button, lp);
        }
        if (allows.isEmpty() && rejects.isEmpty()) {
            TextView deny = Ui.button(this, "Negar", Color.TRANSPARENT, Ui.RED, 15f, 15f);
            deny.setBackground(Ui.rippleStroke(Color.TRANSPARENT, Color.rgb(110, 44, 44), 1, 15f, this));
            deny.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { answerPermission(requestId, null); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
            lp.topMargin = Ui.dp(this, 14);
            card.addView(deny, lp);
        }

        holder.addView(card, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        overlayHost.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        permissionOverlay = overlay;
    }

    private void answerPermission(String requestId, String optionId) {
        if (bridge != null && requestId != null && !requestId.isEmpty()) {
            JSONObject params = new JSONObject();
            try {
                params.put("requestId", requestId);
                if (optionId != null) params.put("optionId", optionId);
            } catch (Exception ignored) { }
            bridge.request("permission.answer", params, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) { }
                @Override public void onError(String code, String message) { toast("Permissão expirada."); }
            });
        }
        closePermission();
    }

    private void closePermission() {
        if (permissionOverlay != null) {
            overlayHost.removeView(permissionOverlay);
            permissionOverlay = null;
        }
        permissionData = null;
        if (!permissionQueue.isEmpty()) {
            JSONObject next = permissionQueue.remove(0);
            showPermission(next);
        }
    }

    private void dismissPermissionById(String requestId) {
        if (permissionData != null && requestId != null && requestId.equals(permissionData.optString("requestId", ""))) {
            if (permissionOverlay != null) {
                overlayHost.removeView(permissionOverlay);
                permissionOverlay = null;
            }
            permissionData = null;
            if (!permissionQueue.isEmpty()) showPermission(permissionQueue.remove(0));
        } else {
            for (int i = 0; i < permissionQueue.size(); i++) {
                if (requestId != null && requestId.equals(permissionQueue.get(i).optString("requestId", ""))) {
                    permissionQueue.remove(i);
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- harness control

    private void confirmStopHarness() {
        new AlertDialog.Builder(this)
                .setTitle("Parar o harness?")
                .setMessage("O runtime do DSH será encerrado no Termux. As conversas continuam salvas.")
                .setPositiveButton("Parar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { stopHarness(); }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void stopHarness() {
        stopStt();
        final BridgeClient old = bridge;
        if (old != null) {
            JSONObject params = new JSONObject();
            old.request("bridge.shutdown", params, new BridgeClient.Callback() {
                @Override public void onResult(JSONObject result) { }
                @Override public void onError(String code, String message) { }
            });
        }
        bridge = null;
        runtimeState = "stopped";
        runtimeWasReady = false;
        polling = false;
        startFlow = false;
        keepScreenOn(false);
        chatScreen = null;
        sessionsScreen = null;
        settingsScreen = null;
        if (old != null) {
            ui.postDelayed(new Runnable() {
                @Override public void run() { old.close(); }
            }, 300);
        }
        showStart();
        toast("Harness parado.");
    }

    private void restartRuntime() {
        doStartWeb();
    }

    private void keepScreenOn(boolean value) {
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- lifecycle

    // ---------------------------------------------------------------- boot / web / model

    private void configureBoot() {
        if (bridge == null) return;
        JSONObject params = new JSONObject();
        try {
            params.put("enabled", prefs.boot());
            params.put("cwd", prefs.cwd());
        } catch (Exception ignored) { }
        bridge.request("boot.configure", params, new BridgeClient.Callback() {
            @Override public void onResult(JSONObject result) { }
            @Override public void onError(String code, String message) { }
        });
    }

    private void openTermuxBootInstall() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux.boot/")));
        } catch (Throwable t) {
            toast("Instale o Termux:Boot pelo F-Droid.");
        }
    }

    private void showBootInstallDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Instale o Termux:Boot")
                .setMessage("O script de boot foi criado, mas o app Termux:Boot é necessário para executá-lo quando o Android liga. Instale-o e abra uma vez para ativar.")
                .setPositiveButton("Abrir F-Droid", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { openTermuxBootInstall(); }
                })
                .setNegativeButton("Agora não", null)
                .show();
    }

    private void showWebOnlyDialog(final String title) {
        String message = "Esta conversa foi criada na interface web (preset PTC). O chat nativo não consegue continuá-la. Abra no modo web ou comece uma conversa nova em Projetos.";
        new AlertDialog.Builder(this)
                .setTitle("Conversa da interface web")
                .setMessage((title == null || title.isEmpty()) ? message : title + "\n\n" + message)
                .setPositiveButton("Abrir modo web", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { startWebMode(); }
                })
                .setNeutralButton("Nova conversa", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { newChat(); }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void startWebMode() {
        doStartWeb();
    }

    private void doStartWeb() {
        if (bridge == null) { toast("O harness não está conectado."); return; }
        if (webScreen == null) webScreen = new WebScreen(this, webHost);
        webScreen.setWaiting(true, "Iniciando a interface web…");
        screen = SCREEN_WEB;
        setScreen(webScreen.view());
        webStarting = true;
        requestWebStart();
    }

    private void requestWebStart() {
        if (bridge == null) { webStarting = false; return; }
        JSONObject params = new JSONObject();
        bridge.request("web.start", params, new BridgeClient.Callback() {
            @Override public void onResult(JSONObject result) {
                webStarting = false;
                String url = result == null ? "" : result.optString("url", "");
                if (webScreen == null) {
                    JSONObject stop = new JSONObject();
                    if (bridge != null) bridge.request("web.stop", stop, emptyCallback());
                    return;
                }
                if (url.isEmpty()) {
                    webScreen.showError("O bridge não retornou o link de autenticação da interface web.");
                    return;
                }
                webScreen.load(url);
            }
            @Override public void onError(String code, String message) {
                webStarting = false;
                if (webScreen != null) webScreen.showError(friendlyError(message));
            }
        }, 90000L);
    }

    private void openChrome(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("Não foi possível abrir o Chrome.");
        }
    }

    private final WebScreen.Host webHost = new WebScreen.Host() {
        @Override public void onBackToApp() { moveTaskToBack(true); }
        @Override public void onRetry() { doStartWeb(); }
        @Override public void onOpenChrome(String url) { openChrome(url); }
        @Override public void onOpenSettings() { showVoiceSettings(); }
    };

    private BridgeClient.Callback emptyCallback() {
        return new BridgeClient.Callback() {
            @Override public void onResult(JSONObject result) { }
            @Override public void onError(String code, String message) { }
        };
    }

    private void confirmArchive(final String id, String title) {
        String message = (title == null || title.isEmpty() ? "Esta conversa" : title)
                + "\n\nEla vai para Arquivadas e pode ser desarquivada depois.";
        new AlertDialog.Builder(this)
                .setTitle("Arquivar conversa?")
                .setMessage(message)
                .setPositiveButton("Arquivar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.setArchived(id, true);
                        fetchSessions();
                        toast("Conversa arquivada.");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmDelete(final String id, String title) {
        String message = (title == null || title.isEmpty() ? "Esta conversa" : title)
                + "\n\nEla será removida da lista do SIG Harness. O histórico no DSH/Termux não será apagado.";
        new AlertDialog.Builder(this)
                .setTitle("Apagar conversa?")
                .setMessage(message)
                .setPositiveButton("Apagar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.deleteSession(id);
                        if (id.equals(prefs.lastSessionId())) prefs.setLastSessionId(null);
                        fetchSessions();
                        toast("Conversa removida da lista.");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void renameSession(final String id, String currentTitle) {
        if (bridge == null) { toast("O harness não está conectado."); return; }
        final EditText input = new EditText(this);
        input.setText(currentTitle == null ? "" : currentTitle);
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        input.setHint("Nome da conversa");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setTextSize(16f);
        input.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        FrameLayout holder = new FrameLayout(this);
        int pad = Ui.dp(this, 20);
        holder.setPadding(pad, 0, pad, 0);
        holder.addView(input, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Renomear conversa")
                .setView(holder)
                .setPositiveButton("Salvar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        final String title = input.getText().toString().replaceAll("\\s+", " ").trim();
                        if (title.isEmpty()) { toast("Digite um nome para a conversa."); return; }
                        JSONObject params = new JSONObject();
                        try { params.put("sessionId", id); params.put("title", title); } catch (Exception ignored) { }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        bridge.request("session.rename", params, new BridgeClient.Callback() {
                            @Override public void onResult(JSONObject result) {
                                dialog.dismiss();
                                if (chatScreen != null && chatScreen.sessionId().equals(id)) chatScreen.setTitle(title);
                                fetchSessions();
                                toast("Conversa renomeada.");
                            }
                            @Override public void onError(String code, String message) {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                toast(friendlyError(message));
                            }
                        });
                    }
                });
                input.requestFocus();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                }
            }
        });
        dialog.show();
    }

    private void showModelSheet(final String sessionId, final JSONArray configOptions) {
        if (configOptions == null || configOptions.length() == 0) {
            toast("As opções de modelo ainda não foram carregadas.");
            return;
        }
        dismissModelSheet();
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(215, 4, 6, 10));
        overlay.setClickable(true);
        FrameLayout holder = new FrameLayout(this);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.BOTTOM;
        overlay.addView(holder, hlp);

        LinearLayout card = Ui.column(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.BG_ELEVATED);
        float r = Ui.dp(this, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(bg);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 18));

        LinearLayout head = Ui.row(this);
        TextView title = Ui.text(this, "Modelo e raciocínio", 19f, Ui.TEXT, Ui.BOLD);
        head.addView(title, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.iconButton(this, "✕", 18f, new View.OnClickListener() {
            @Override public void onClick(View v) { dismissModelSheet(); }
        });
        head.addView(close);
        card.addView(head);

        TextView hint = Ui.text(this, "Vale para esta conversa e para as próximas.", 11.5f, Ui.MUTED, Ui.REGULAR);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        card.addView(hint);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = Ui.column(this);
        scroll.addView(list, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < configOptions.length(); i++) {
            JSONObject option = configOptions.optJSONObject(i);
            if (option == null || !"select".equals(option.optString("type"))) continue;
            final String configId = option.optString("id", "");
            final String current = option.optString("currentValue", "");
            if (configId.isEmpty()) continue;
            TextView label = Ui.text(this, option.optString("name", configId), 11f, Ui.FAINT, Ui.MEDIUM);
            label.setAllCaps(true);
            label.setLetterSpacing(0.1f);
            label.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
            list.addView(label);
            JSONArray options = option.optJSONArray("options");
            if (options == null) continue;
            for (int j = 0; j < options.length(); j++) {
                JSONObject entry = options.optJSONObject(j);
                if (entry == null) continue;
                JSONArray groupOptions = entry.optJSONArray("options");
                if (groupOptions != null) {
                    TextView group = Ui.text(this, entry.optString("name", entry.optString("group", "")), 11.5f, Ui.MUTED, Ui.MEDIUM);
                    group.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, Ui.dp(this, 2));
                    list.addView(group);
                    for (int k = 0; k < groupOptions.length(); k++) {
                        JSONObject item = groupOptions.optJSONObject(k);
                        if (item != null) list.addView(modelRow(sessionId, configId, item, current));
                    }
                } else {
                    list.addView(modelRow(sessionId, configId, entry, current));
                }
            }
        }
        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        card.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
        holder.addView(card, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        overlayHost.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        modelSheet = overlay;
    }

    private View modelRow(final String sessionId, final String configId, final JSONObject item, final String current) {
        final String value = item.optString("value", "");
        final String name = item.optString("name", value);
        final String description = item.optString("description", "");
        boolean selected = value.equals(current);
        LinearLayout row = Ui.column(this);
        row.setBackground(Ui.ripple(selected ? Ui.SURFACE_2 : Color.TRANSPARENT, 12f, this));
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        row.setClickable(true);
        LinearLayout top = Ui.row(this);
        TextView nameView = Ui.text(this, name, 15f, Ui.TEXT, selected ? Ui.MEDIUM : Ui.REGULAR);
        top.addView(nameView, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) {
            TextView check = Ui.text(this, "✓", 15f, Ui.ACCENT, Ui.MEDIUM);
            top.addView(check);
        }
        row.addView(top);
        if (!description.isEmpty()) {
            TextView desc = Ui.text(this, description, 11.5f, Ui.MUTED, Ui.REGULAR);
            desc.setLineSpacing(Ui.dp(this, 2), 1.0f);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Ui.dp(this, 4);
            row.addView(desc, dlp);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyConfigOption(sessionId, configId, value, name); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 4);
        row.setLayoutParams(lp);
        return row;
    }

    private void applyConfigOption(final String sessionId, final String configId, final String value, final String name) {
        dismissModelSheet();
        if (bridge == null) { toast("O harness não está conectado."); return; }
        JSONObject params = new JSONObject();
        try {
            params.put("sessionId", sessionId);
            params.put("configId", configId);
            params.put("value", value);
        } catch (Exception ignored) { }
        bridge.request("session.setConfigOption", params, new BridgeClient.Callback() {
            @Override public void onResult(JSONObject result) {
                JSONArray options = result == null ? null : result.optJSONArray("configOptions");
                if (chatScreen != null && chatScreen.sessionId().equals(sessionId)) chatScreen.setConfigOptions(options);
                if ("model".equals(configId)) prefs.setModel(value, name);
                else if ("reasoning_effort".equals(configId)) prefs.setReasoning(value, name);
                toast("Configuração atualizada.");
            }
            @Override public void onError(String code, String message) { toast(friendlyError(message)); }
        });
    }

    private void dismissModelSheet() {
        if (modelSheet != null) {
            overlayHost.removeView(modelSheet);
            modelSheet = null;
        }
    }

    private void pickAttachments() {
        if (chatScreen == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_ATTACH);
        } catch (Throwable t) {
            toast("Não foi possível abrir o seletor de arquivos.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (webScreen != null && webScreen.handleFileChooserResult(requestCode, resultCode, data)) return;
        if (requestCode != REQ_ATTACH || resultCode != RESULT_OK || data == null) return;
        final ChatScreen target = chatScreen;
        if (target == null) return;
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
        if (uris.isEmpty()) return;
        pendingUploads += uris.size();
        target.setAttaching(true);
        for (Uri uri : uris) uploadAttachment(uri, target);
    }

    private void uploadAttachment(final Uri uri, final ChatScreen target) {
        new Thread(new Runnable() {
            @Override public void run() {
                String name = "arquivo";
                long size = -1;
                String mime = null;
                try { mime = getContentResolver().getType(uri); } catch (Throwable ignored) { }
                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (cursor != null) cursor.close();
                }
                final String fileName = name;
                final String fileMime = mime == null ? "application/octet-stream" : mime;
                final long knownSize = size;
                if (knownSize > MAX_ATTACHMENT_BYTES) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            finishUpload(target, "O arquivo " + fileName + " passa de 20 MB.");
                        }
                    });
                    return;
                }
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("stream ausente");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    long total = 0;
                    try {
                        while ((read = in.read(buffer)) > 0) {
                            total += read;
                            if (total > MAX_ATTACHMENT_BYTES) throw new IOException("Arquivo maior que 20 MB.");
                            out.write(buffer, 0, read);
                        }
                    } finally {
                        try { in.close(); } catch (Exception ignored) { }
                    }
                    final String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (bridge == null) {
                                finishUpload(target, "O harness não está conectado.");
                                return;
                            }
                            JSONObject params = new JSONObject();
                            try {
                                params.put("name", fileName);
                                params.put("mimeType", fileMime);
                                params.put("data", encoded);
                            } catch (Exception ignored) { }
                            bridge.request("file.upload", params, new BridgeClient.Callback() {
                                @Override public void onResult(JSONObject result) {
                                    if (result != null && chatScreen == target) {
                                        target.addAttachment(result.optString("name", fileName), result.optString("path", ""),
                                                result.optString("mimeType", fileMime), result.optLong("size", knownSize));
                                    }
                                    finishUpload(target, null);
                                }
                                @Override public void onError(String code, String message) {
                                    finishUpload(target, friendlyError(message));
                                }
                            });
                        }
                    });
                } catch (Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            finishUpload(target, "Não foi possível ler " + fileName + ".");
                        }
                    });
                }
            }
        }, "sig-attach").start();
    }

    private void finishUpload(ChatScreen target, String error) {
        pendingUploads = Math.max(0, pendingUploads - 1);
        if (chatScreen == target) {
            target.setAttaching(pendingUploads > 0);
            if (error != null) target.attachmentError(error);
        }
    }

    private void startStt(String provider, String key) {
        if (chatScreen == null) return;
        final ChatScreen target = chatScreen;
        final String label = "gemini".equals(provider) ? "Gemini 3.5 Transcribe" : "Meta Muse Voice";
        target.setDictationState("connecting", "🎤 Conectando ao " + label + "…");
        stt = new SttSession(this, provider, key, new SttSession.Callback() {
            @Override public void onState(String state, String detail) {
                if (chatScreen == target) target.setDictationState(state, detail);
            }
            @Override public void onPartial(String text) {
                if (chatScreen == target) target.setDictationPartial(text);
            }
            @Override public void onFinal(String text) {
                if (chatScreen == target) {
                    target.insertDictation(text);
                    target.setDictationState("idle", "");
                }
                stt = null;
            }
            @Override public void onError(String message) {
                if (chatScreen == target) target.setDictationError(message);
                stt = null;
            }
        });
        stt.start();
    }

    private void stopStt() {
        if (stt != null) {
            stt.cancel();
            stt = null;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (webScreen != null && webScreen.onPermissionResult(requestCode, grantResults)) return;
        if (requestCode == REQ_MIC) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingMic) {
                pendingMic = false;
                startStt(prefs.sttProvider(), "gemini".equals(prefs.sttProvider()) ? prefs.geminiKey() : prefs.museKey());
            } else {
                pendingMic = false;
                toast("Permissão de microfone necessária para ditar.");
            }
            return;
        }
        if (requestCode != REQ_TERMUX) return;
        boolean granted = TermuxRunner.hasPermission(this);
        if (granted) {
            if (settingsScreen != null) settingsScreen.setPermission(true);
            if (pendingStartPermission) {
                pendingStartPermission = false;
                beginLaunch();
            }
        } else {
            pendingStartPermission = false;
            if (startScreen != null) {
                startScreen.setBusy(false);
                startScreen.showError("Permissão do Termux necessária", "O Android precisa autorizar o SIG a executar comandos no Termux. Abra Ajustes do app e conceda \"Executar comandos no Termux\".");
            }
            if (settingsScreen != null) settingsScreen.setPermission(false);
        }
    }

    @Override public void onBackPressed() {
        if (permissionOverlay != null) {
            if (permissionData != null) answerPermission(permissionData.optString("requestId", ""), null);
            else closePermission();
            return;
        }
        if (modelSheet != null) { dismissModelSheet(); return; }
        if (screen == SCREEN_WEB) {
            if (webScreen != null && webScreen.canGoBack()) { webScreen.goBack(); return; }
            moveTaskToBack(true);
            return;
        }
        if (screen == SCREEN_CHAT) { showSessions(); return; }
        if (screen == SCREEN_SETTINGS) { backFromSettings(); return; }
        if (screen == SCREEN_SESSIONS) {
            if (bridge != null) { super.onBackPressed(); return; }
            showStart();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        stopStt();
        finishing = true;
        polling = false;
        scanning = false;
        ui.removeCallbacksAndMessages(null);
        if (webScreen != null) { webScreen.destroy(); webScreen = null; }
        if (isFinishing() && bridge != null) {
            bridge.close();
            bridge = null;
        }
        super.onDestroy();
    }
}
