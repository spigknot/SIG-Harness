package com.sig.harness;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Starts the SIG bridge inside Termux using Termux's RUN_COMMAND intent. */
public final class TermuxRunner {
    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";

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

    public static Intent bridgeIntent(String token, String cwd, int port, String bridgeBase64, String patchBase64) {
        String script = "export PATH=/data/data/com.termux/files/usr/bin:$PATH; "
                + "mkdir -p \"$HOME/.sig-harness\"; "
                + "printf '%s' '" + bridgeBase64 + "' | base64 -d > \"$HOME/.sig-harness/sig-bridge.mjs\"; "
                + "if [ ! -f \"$HOME/.sig-harness/acp.patch.yml\" ]; then printf '%s' '" + patchBase64 + "' | base64 -d > \"$HOME/.sig-harness/acp.patch.yml\"; fi; "
                + "chmod 700 \"$HOME/.sig-harness/sig-bridge.mjs\"; "
                + "exec /data/data/com.termux/files/usr/bin/node \"$HOME/.sig-harness/sig-bridge.mjs\""
                + " --daemon --port " + port
                + " --token " + quote(token)
                + " --cwd " + quote(cwd)
                + " --permission-mode danger-full-access"
                + " --log \"$HOME/.sig-harness/bridge.log\"";
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "SIG Harness");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Iniciar o SIG Harness");
        return intent;
    }

    public static void startBridge(Context context, String token, String cwd, int port, String bridgeBase64, String patchBase64) {
        context.startService(bridgeIntent(token, cwd, port, bridgeBase64, patchBase64));
    }

    public static void openTermux(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }

    private static String quote(String value) {
        if (value == null) value = "";
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
