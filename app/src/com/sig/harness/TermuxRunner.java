package com.sig.harness;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends the installer to Termux and receives its progress and final result. */
public final class TermuxRunner {
    private static final String TERMUX_PACKAGE = "com.termux";
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String EXTRA = "com.termux.RUN_COMMAND_";
    private static final Pattern READY = Pattern.compile("(?m)^SIG_READY ([0-9]{1,5}) ([0-9]{1,10})$");

    interface Listener {
        void onProgress(String message);
        void onReady(int port, int pid);
        void onFailure(String message);
    }

    private TermuxRunner() { }

    public static boolean isTermuxInstalled(Context context) { return installed(context, TERMUX_PACKAGE); }
    public static boolean isBootInstalled(Context context) { return installed(context, "com.termux.boot"); }

    private static boolean installed(Context context, String name) {
        try {
            context.getPackageManager().getPackageInfo(name, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public static void openTermux(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launch != null) context.startActivity(launch);
    }

    static final class Installation {
        private final Activity app;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final Listener listener;
        private final String id = UUID.randomUUID().toString();
        private final String action;
        private File statusFile;
        private PendingIntent resultIntent;
        private boolean registered;
        private boolean closed;
        private String lastStep = "";
        private final Runnable noProgress = this::showWaitingForTermux;
        private final Runnable timedOut = () -> {
            String detail = label(lastStep);
            fail(detail == null ? "O Termux não devolveu o resultado da verificação."
                    : "Tempo limite em «" + detail + "». Consulte ~/.sig-harness/bootstrap.log no Termux.");
        };

        Installation(Activity app, Listener listener) {
            this.app = app;
            this.listener = listener;
            action = app.getPackageName() + ".INSTALL_RESULT." + id;
        }

        private void showWaitingForTermux() {
            if (lastStep.isEmpty()) listener.onProgress("Aguardando resposta do Termux");
        }

        void start(String mode, String token, String cwd, int port) {
            new Thread(() -> {
                try {
                    File[] dirs = app.getExternalMediaDirs();
                    if (dirs.length == 0 || dirs[0] == null || (!dirs[0].isDirectory() && !dirs[0].mkdirs())) {
                        throw new IOException("Armazenamento compartilhado indisponível");
                    }
                    String script = new String(readAsset("bootstrap.sh"), StandardCharsets.UTF_8);
                    byte[] payload = readAsset("dsh-web.tgz");
                    String sha = hex(MessageDigest.getInstance("SHA-256").digest(payload));
                    File folder = dirs[0];
                    File file = new File(folder, "harness-" + sha + ".tgz");
                    // Sempre regrava a partir do APK. O Termux faz outra verificação após copiar.
                    File tmp = new File(folder, id + ".tmp");
                    try {
                        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(payload); }
                        if (!tmp.renameTo(file)) throw new IOException("Não foi possível compartilhar o pacote");
                    } finally {
                        tmp.delete();
                    }
                    File progress = new File(folder, id + ".status");
                    if (!progress.createNewFile()) throw new IOException("Arquivo de progresso indisponível");
                    ui.post(() -> send(script, file, sha, progress, mode, token, cwd, port, 0));
                } catch (Exception e) {
                    ui.post(() -> fail("Não foi possível preparar os arquivos de instalação. Confira o armazenamento do aparelho."));
                }
            }, "sig-install-assets").start();
        }

        private void send(String script, File file, String sha, File status, String mode,
                          String token, String cwd, int port, int attempt) {
            if (closed) return;
            if (!app.hasWindowFocus()) {
                if (attempt >= 30) { fail("Volte ao SIG Harness e tente novamente para autorizar o comando."); return; }
                ui.postDelayed(() -> send(script, file, sha, status, mode, token, cwd, port, attempt + 1), 300);
                return;
            }
            statusFile = status;
            try {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
                else app.registerReceiver(receiver, new IntentFilter(action));
                registered = true;
                int flags = PendingIntent.FLAG_ONE_SHOT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                resultIntent = PendingIntent.getBroadcast(app, 0, new Intent(action).setPackage(app.getPackageName()), flags);

                Intent cmd = new Intent("com.termux.RUN_COMMAND");
                cmd.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
                cmd.putExtra(EXTRA + "PATH", "/data/data/com.termux/files/usr/bin/bash");
                cmd.putExtra(EXTRA + "ARGUMENTS", new String[]{"-c", script, "sig-bootstrap",
                        file.getAbsolutePath(), sha, status.getAbsolutePath(), mode, String.valueOf(port), token, cwd});
                cmd.putExtra(EXTRA + "WORKDIR", "/data/data/com.termux/files/home");
                cmd.putExtra(EXTRA + "BACKGROUND", true);
                cmd.putExtra(EXTRA + "COMMAND_LABEL", "SIG Harness");
                cmd.putExtra(EXTRA + "PENDING_INTENT", resultIntent);
                if (app.startService(cmd) == null) throw new IllegalStateException("Serviço do Termux ausente");
                ui.post(poll);
                ui.postDelayed(noProgress, 45000L);
                ui.postDelayed(timedOut, 50L * 60L * 1000L);
            } catch (RuntimeException error) {
                fail("O Android não conseguiu enviar o comando ao Termux. Abra o Termux uma vez e confira a permissão do SIG Harness.");
            }
        }

        private byte[] readAsset(String name) throws IOException {
            try (InputStream in = app.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int count;
                while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
                return out.toByteArray();
            }
        }

        private static String hex(byte[] hash) {
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        }

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (closed) return;
                readProgress();
                ui.postDelayed(this, 450);
            }
        };

        private void readProgress() {
            if (statusFile == null) return;
            try (FileInputStream in = new FileInputStream(statusFile)) {
                byte[] buf = new byte[64];
                int count = in.read(buf);
                if (count <= 0) return;
                String step = new String(buf, 0, count, StandardCharsets.US_ASCII).trim();
                String text = label(step);
                if (text != null && !step.equals(lastStep)) {
                    lastStep = step;
                    listener.onProgress(text);
                }
            } catch (IOException ignored) { }
        }

        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (closed || !action.equals(intent.getAction())) return;
                // O resultado só vale após a conclusão do comando no Termux.
                // Para Android anteriores ao 13, o identificador imprevisível também isola a operação.
                Bundle result = intent.getBundleExtra("result");
                if (result == null) { fail("O Termux não devolveu o resultado da verificação."); return; }
                readProgress();
                String out = result.getString("stdout", "");
                Matcher ready = READY.matcher(out);
                if (result.getInt("err", Activity.RESULT_OK) == Activity.RESULT_OK
                        && result.getInt("exitCode", -1) == 0 && ready.find()) {
                    try {
                        int port = Integer.parseInt(ready.group(1));
                        int pid = Integer.parseInt(ready.group(2));
                        if (statusFile != null) statusFile.delete();
                        close();
                        listener.onReady(port, pid);
                        return;
                    } catch (NumberFormatException ignored) { }
                }
                Matcher failed = Pattern.compile("(?m)^SIG_FAILED ([a-z_]+)$").matcher(out);
                if (failed.find() && label(failed.group(1)) != null) lastStep = failed.group(1);
                String detail = label(lastStep);
                if (detail == null) {
                    fail("O Termux recusou o comando. Libere comandos externos no Termux e tente novamente.");
                } else if ("environment".equals(lastStep)) {
                    fail("Execute pelo Termux no Android ARM64 com a sessão comum do Termux.");
                } else if ("lock".equals(lastStep)) {
                    fail("Já existe uma verificação ou reparo em andamento. Aguarde e tente novamente.");
                } else if ("assets".equals(lastStep)) {
                    fail("O Termux não conseguiu ler ou validar o pacote do app. Confira o acesso aos arquivos do aparelho no Termux.");
                } else {
                    fail("Falha em «" + detail + "». Consulte ~/.sig-harness/bootstrap.log no Termux.");
                }
            }
        };

        private void fail(String message) {
            if (closed) return;
            if (statusFile != null) statusFile.delete();
            close();
            listener.onFailure(message);
        }

        void close() {
            closed = true;
            ui.removeCallbacksAndMessages(null);
            if (registered) {
                app.unregisterReceiver(receiver);
                registered = false;
            }
            if (resultIntent != null) resultIntent.cancel();
        }

        private static String label(String step) {
            switch (step) {
                case "environment": return "Verificando o ambiente do Termux";
                case "lock": return "Iniciando a verificação";
                case "tools": return "Verificando ferramentas do Termux";
                case "assets": return "Validando os arquivos do app";
                case "node": return "Verificando Node.js e npm";
                case "node_install": return "Corrigindo Node.js e npm";
                case "pnpm": return "Preparando o instalador de plugins";
                case "dsh": return "Verificando o DeepSeek Harness";
                case "dsh_install": return "Instalando o DeepSeek Harness";
                case "native": return "Restaurando patches e binários nativos";
                case "profile": return "Verificando o perfil e os plugins";
                case "profile_initialize": return "Criando o perfil Web no DeepSeek Harness";
                case "profile_repair": return "Reparando o perfil e os plugins";
                case "bridge": return "Preparando a conexão local";
                default: return null;
            }
        }
    }
}
