package com.sig.harness;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/** Starts the SIG bridge inside Termux using Termux's RUN_COMMAND intent. */
public final class TermuxRunner {
    /** Versão do DSH instalada no primeiro uso (espelha dsh-web/pin.env). */
    static final String DSH_BOOTSTRAP_VERSION = "0.2.1-rc.5";

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";

    /**
     * O kernel limita cada argumento do execve a MAX_ARG_STRLEN (128 KB). O script já
     * carrega o bridge em base64 (~77 KB), então o pacote dsh-web viaja em argumentos
     * separados e é remontado no shell com \"$1$2...\".
     */
    private static final int PAYLOAD_CHUNK = 60000;

    private TermuxRunner() { }

    public static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean isBootInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.termux.boot", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public static Intent bridgeIntent(String token, String cwd, int port, String bridgeBase64, String patchBase64, String payloadBase64) {
        return bridgeIntent(token, cwd, port, bridgeBase64, patchBase64, payloadBase64, false);
    }

    /**
     * Reinício completo: derruba o dsh web em execução antes de subir a bridge de novo,
     * para que os módulos editados em disco (patches, plugins) sejam carregados do zero.
     */
    public static void restartBridge(Context context, String token, String cwd, int port, String bridgeBase64, String patchBase64, String payloadBase64) {
        context.startService(bridgeIntent(token, cwd, port, bridgeBase64, patchBase64, payloadBase64, true));
    }

    public static Intent bridgeIntent(String token, String cwd, int port, String bridgeBase64, String patchBase64, String payloadBase64, boolean restart) {
        List<String> chunks = splitPayload(payloadBase64);
        // Camada dsh-web embutida no APK: despeja o pacote, extrai e reaplica as
        // customizações do DSH (patches, plugin da aba Voz e addon nativo) antes do bridge.
        String provision = "";
        if (!chunks.isEmpty()) {
            provision = "printf '%s' " + chunkRefs(chunks.size()) + " | base64 -d > \"$HOME/.sig-harness/dsh-web.tgz\"; "
                    + "rm -rf \"$HOME/.sig-harness/dsh-web\"; "
                    + "tar xzf \"$HOME/.sig-harness/dsh-web.tgz\" -C \"$HOME/.sig-harness\" 2>/dev/null && "
                    + "bash \"$HOME/.sig-harness/dsh-web/apply.sh\" --auto >> \"$HOME/.sig-harness/provision.log\" 2>&1; ";
        }
        // "pkill -f 'bin.js web'" casaria com este próprio shell (o texto do comando contém
        // o padrão) e mataria o wrapper antes do exec. O caminho correto é encerrar a bridge,
        // que é dona do processo web e o derruba no shutdown; o pid está em bridge.json.
        String prefix = restart
                ? "PID=$(grep -o '" + "\"pid\":[0-9]*" + "' \"$HOME/.sig-harness/bridge.json\" 2>/dev/null | grep -o '[0-9]*'); "
                + "[ -n \"$PID\" ] && kill \"$PID\" 2>/dev/null; "
                + "STRAY=$(pgrep -f 'expose-internals.*bin[.]js web' 2>/dev/null); [ -n \"$STRAY\" ] && kill $STRAY 2>/dev/null; "
                + "sleep 2; "
                : "";
        String script = prefix + "export PATH=/data/data/com.termux/files/usr/bin:$PATH; "
                + "mkdir -p \"$HOME/.sig-harness\"; "
                // Primeiro uso num aparelho limpo: instala o que falta antes de seguir.
                // Idempotente — em aparelho pronto os dois comandos só conferem o PATH.
                + "BOOT=\"$HOME/.sig-harness/bootstrap.log\"; "
                + "command -v node >/dev/null 2>&1 || { echo \"[bootstrap] node\" >> \"$BOOT\"; "
                // upgrade antes: node novo sobre libc++ antigo falha com "cannot locate symbol"
                + "yes | pkg update -y >> \"$BOOT\" 2>&1; yes | pkg upgrade -y >> \"$BOOT\" 2>&1; "
                + "yes | pkg install -y nodejs npm >> \"$BOOT\" 2>&1; }; "
                + "command -v dsh >/dev/null 2>&1 || { echo \"[bootstrap] dsh " + DSH_BOOTSTRAP_VERSION + "\" >> \"$BOOT\"; "
                + "npm i -g @deepseek-ai/dsh@" + DSH_BOOTSTRAP_VERSION + " >> \"$BOOT\" 2>&1; }; "
                + "printf '%s' '" + bridgeBase64 + "' | base64 -d > \"$HOME/.sig-harness/sig-bridge.mjs\"; "
                + "if [ ! -f \"$HOME/.sig-harness/acp.patch.yml\" ]; then printf '%s' '" + patchBase64 + "' | base64 -d > \"$HOME/.sig-harness/acp.patch.yml\"; fi; "
                + "chmod 700 \"$HOME/.sig-harness/sig-bridge.mjs\"; "
                + provision
                + "exec /data/data/com.termux/files/usr/bin/node \"$HOME/.sig-harness/sig-bridge.mjs\""
                + " --daemon --port " + port
                + " --token " + quote(token)
                + " --cwd " + quote(cwd)
                + " --permission-mode danger-full-access"
                + " --log \"$HOME/.sig-harness/bridge.log\"";
        List<String> args = new ArrayList<String>();
        args.add("-lc");
        args.add(script);
        if (!chunks.isEmpty()) {
            args.add("sig-payload");
            args.addAll(chunks);
        }
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args.toArray(new String[0]));
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "SIG Harness");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Iniciar o SIG Harness");
        return intent;
    }

    public static void startBridge(Context context, String token, String cwd, int port, String bridgeBase64, String patchBase64, String payloadBase64) {
        context.startService(bridgeIntent(token, cwd, port, bridgeBase64, patchBase64, payloadBase64));
    }

    public static void openTermux(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }

    private static List<String> splitPayload(String payload) {
        List<String> out = new ArrayList<String>();
        if (payload == null || payload.isEmpty()) return out;
        for (int i = 0; i < payload.length(); i += PAYLOAD_CHUNK) {
            out.add(payload.substring(i, Math.min(payload.length(), i + PAYLOAD_CHUNK)));
        }
        return out;
    }

    /** Devolve \"$1$2$3\" — o shell concatena os blocos antes do base64 -d. */
    private static String chunkRefs(int count) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 1; i <= count; i++) sb.append('$').append(i);
        sb.append('"');
        return sb.toString();
    }

    private static String quote(String value) {
        if (value == null) value = "";
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
