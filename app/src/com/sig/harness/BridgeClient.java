package com.sig.harness;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Line-delimited JSON client for the SIG bridge running inside Termux.
 * One TCP connection per app process; the bridge keeps the runtime alive.
 */
public final class BridgeClient {
    public interface Listener {
        void onWelcomed(JSONObject welcome);
        void onEvent(String event, JSONObject data);
        void onDisconnected(String reason);
    }

    public interface Callback {
        void onResult(JSONObject result);
        void onError(String code, String message);
    }

    private static final long DEFAULT_TIMEOUT_MS = 45000L;

    private final Socket socket;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Long, Callback> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final CountDownLatch welcomeLatch = new CountDownLatch(1);
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<String>();
    private final Thread writerThread;
    private volatile boolean closed;
    private volatile boolean listenerNotified;
    private JSONObject welcome;
    private String authError;
    private Listener listener;

    private BridgeClient(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "sig-bridge-reader");
        thread.setDaemon(true);
        thread.start();
        writerThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (!closed) {
                        String line = outbox.take();
                        writeLine(line);
                    }
                } catch (InterruptedException ignored) {
                } catch (IOException e) {
                    closeQuietlyInternal("A conexão com o bridge foi encerrada.", true);
                }
            }
        }, "sig-bridge-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /** Blocking connect + handshake. Throws when the port is not the bridge or auth fails. */
    public static BridgeClient connect(String host, int port, String token, int timeoutMs) throws IOException, InterruptedException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            BridgeClient client = new BridgeClient(socket);
            client.writeHello(token);
            if (!client.welcomeLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                client.closeQuietly();
                throw new IOException("Timeout ao autenticar com o bridge");
            }
            if (client.welcome == null) {
                String error = client.authError == null ? "Autenticação recusada" : client.authError;
                client.closeQuietly();
                throw new IOException(error);
            }
            socket.setSoTimeout(0);
            return client;
        } catch (IOException | InterruptedException e) {
            try { socket.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    public void setListener(Listener value) {
        this.listener = value;
        JSONObject w = this.welcome;
        if (w != null && value != null) {
            main.post(new Runnable() {
                @Override public void run() { if (!closed) value.onWelcomed(w); }
            });
        }
    }

    public JSONObject welcome() {
        return welcome;
    }

    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    private void writeHello(String token) throws IOException {
        JSONObject hello = new JSONObject();
        try {
            hello.put("type", "hello");
            hello.put("token", token);
            hello.put("client", "sig-harness-android");
            hello.put("version", "1");
        } catch (Exception ignored) { }
        writeLine(hello.toString());
    }

    private synchronized void writeLine(String line) throws IOException {
        writer.write(line);
        writer.write("\n");
        writer.flush();
    }

    private void readLoop() {
        String reason = null;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject object;
                try { object = new JSONObject(line); } catch (Exception e) { continue; }
                String type = object.optString("type", "");
                if ("welcome".equals(type)) {
                    welcome = object;
                    try { socket.setSoTimeout(0); } catch (IOException ignored) { }
                    welcomeLatch.countDown();
                    final JSONObject w = object;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (!closed && listener != null) listener.onWelcomed(w);
                        }
                    });
                } else if ("response".equals(type)) {
                    long id = object.optLong("id", -1);
                    Callback callback = pending.remove(id);
                    if (callback == null) continue;
                    final JSONObject response = object;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (response.optBoolean("ok", false)) {
                                JSONObject result = response.optJSONObject("result");
                                callback.onResult(result == null ? new JSONObject() : result);
                            } else {
                                JSONObject error = response.optJSONObject("error");
                                String code = error == null ? "error" : error.optString("code", "error");
                                String message = error == null ? "Erro desconhecido" : error.optString("message", "Erro desconhecido");
                                callback.onError(code, message);
                            }
                        }
                    });
                } else if ("event".equals(type)) {
                    final String event = object.optString("event", "");
                    final JSONObject data = object.optJSONObject("data");
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (!closed && listener != null) listener.onEvent(event, data == null ? new JSONObject() : data);
                        }
                    });
                } else if ("error".equals(type)) {
                    authError = object.optString("message", "Autenticação recusada");
                    welcomeLatch.countDown();
                }
            }
            reason = "A conexão com o bridge foi encerrada.";
        } catch (IOException e) {
            reason = e.getMessage() == null ? "Conexão perdida." : e.getMessage();
        } finally {
            boolean notify;
            synchronized (this) { notify = !closed; }
            closeQuietlyInternal(reason, notify);
        }
    }

    private void closeQuietly() {
        closeQuietlyInternal("Conexão encerrada.", false);
    }

    private void closeQuietlyInternal(String reason, boolean notify) {
        closed = true;
        outbox.clear();
        if (writerThread != null) writerThread.interrupt();
        try { socket.close(); } catch (IOException ignored) { }
        for (Map.Entry<Long, Callback> entry : pending.entrySet()) {
            Callback callback = entry.getValue();
            if (callback != null) callback.onError("disconnected", reason == null ? "Conexão encerrada." : reason);
        }
        pending.clear();
        if (notify && !listenerNotified) {
            listenerNotified = true;
            main.post(new Runnable() {
                @Override public void run() {
                    if (listener != null) listener.onDisconnected(reason == null ? "Conexão encerrada." : reason);
                }
            });
        }
    }

    public void close() {
        closeQuietlyInternal("Conexão encerrada pelo app.", false);
    }

    private void enqueue(String line) {
        if (closed) throw new IllegalStateException("Bridge fechado");
        if (!outbox.offer(line)) throw new IllegalStateException("Fila de envio cheia");
    }

    public void request(String method, JSONObject params, final Callback callback) {
        if (closed) {
            main.post(new Runnable() {
                @Override public void run() { callback.onError("disconnected", "O bridge não está conectado."); }
            });
            return;
        }
        final long id = nextId.getAndIncrement();
        pending.put(id, callback);
        JSONObject object = new JSONObject();
        try {
            object.put("type", "request");
            object.put("id", id);
            object.put("method", method);
            object.put("params", params == null ? new JSONObject() : params);
            enqueue(object.toString());
        } catch (Exception e) {
            pending.remove(id);
            main.post(new Runnable() {
                @Override public void run() { callback.onError("io", e.getMessage() == null ? "Falha ao enviar" : e.getMessage()); }
            });
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                Callback still = pending.remove(id);
                if (still != null) still.onError("timeout", "O Termux demorou demais para responder.");
            }
        }, DEFAULT_TIMEOUT_MS);
    }

    /** Request with a caller-chosen timeout (used for short probes). */
    public void request(String method, JSONObject params, final Callback callback, long timeoutMs) {
        if (closed) {
            main.post(new Runnable() {
                @Override public void run() { callback.onError("disconnected", "O bridge não está conectado."); }
            });
            return;
        }
        final long id = nextId.getAndIncrement();
        pending.put(id, callback);
        JSONObject object = new JSONObject();
        try {
            object.put("type", "request");
            object.put("id", id);
            object.put("method", method);
            object.put("params", params == null ? new JSONObject() : params);
            enqueue(object.toString());
        } catch (Exception e) {
            pending.remove(id);
            main.post(new Runnable() {
                @Override public void run() { callback.onError("io", e.getMessage() == null ? "Falha ao enviar" : e.getMessage()); }
            });
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                Callback still = pending.remove(id);
                if (still != null) still.onError("timeout", "O Termux demorou demais para responder.");
            }
        }, timeoutMs);
    }
}
