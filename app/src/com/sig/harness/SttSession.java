package com.sig.harness;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Live speech-to-text over WebSocket. Meta Muse Voice sends raw PCM16LE
 * binary frames; Gemini 3.5 Transcribe sends base64 PCM inside JSON.
 * The microphone only starts after the provider acknowledges the session.
 */
public final class SttSession {
    public interface Callback {
        void onState(String state, String detail);
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
    }

    private static final String MUSE_URL = "wss://api.meta.ai/v1/asr/realtime";
    private static final String GEMINI_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_BYTES = 3200; // 100 ms of PCM16 mono

    private final Context context;
    private final String provider;
    private final String apiKey;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();

    private volatile boolean started;
    private volatile boolean cancelled;
    private volatile boolean stopRequested;
    private volatile boolean finished;
    private volatile boolean ready;
    private volatile boolean finalReceived;
    private volatile long finalDeadline;
    private volatile long lastFinalAt;
    private volatile String finalText = "";
    private volatile String partialText = "";

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private AudioRecord audio;
    private Thread audioThread;
    private Thread connectionThread;

    public SttSession(Context context, String provider, String apiKey, Callback callback) {
        this.context = context;
        this.provider = provider == null ? "muse" : provider;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.callback = callback;
    }

    public boolean isActive() {
        return started && !finished;
    }

    public void start() {
        if (started) return;
        started = true;
        connectionThread = new Thread(new Runnable() {
            @Override public void run() { runConnection(); }
        }, "sig-stt-connection");
        connectionThread.start();
    }

    public void stop() {
        if (!started || finished || stopRequested) return;
        stopRequested = true;
        finalDeadline = System.currentTimeMillis() + 8000L;
        postState("finishing", "Finalizando transcrição…");
        new Thread(new Runnable() {
            @Override public void run() {
                stopAudio();
                if (!ready || finished) return;
                try {
                    sendEnd();
                } catch (Throwable t) {
                    fail("Não foi possível encerrar o áudio.");
                }
            }
        }, "sig-stt-end").start();
    }

    public void cancel() {
        if (finished) return;
        cancelled = true;
        stopRequested = true;
        finished = true;
        stopAudio();
        closeQuietly();
        postState("idle", "");
    }

    private void runConnection() {
        try {
            connect();
            sendHandshake();
            long readyDeadline = System.currentTimeMillis() + 20000L;
            ByteArrayOutputStream fragment = new ByteArrayOutputStream();
            int fragmentOpcode = -1;
            while (!cancelled && !finished) {
                Frame frame = null;
                boolean timedOut = false;
                try {
                    frame = readFrame();
                } catch (SocketTimeoutException e) {
                    timedOut = true;
                }
                if (frame == null) {
                    if (timedOut) {
                        // Sem dados neste ciclo (SoTimeout). Não é EOF: o Gemini
                        // fica em silêncio entre setupComplete e o primeiro áudio.
                    } else {
                        if (stopRequested) { finish(); return; }
                        fail("O provedor encerrou a conexão de voz.");
                        return;
                    }
                }
                if (frame != null) {
                    if (frame.opcode == 8) break;
                    if (frame.opcode == 9) {
                        writeFrame(10, frame.payload);
                    } else if (frame.opcode == 1 || frame.opcode == 2) {
                        if (frame.fin) {
                            handleMessage(frame.opcode, frame.payload);
                        } else {
                            fragment.reset();
                            fragment.write(frame.payload);
                            fragmentOpcode = frame.opcode;
                        }
                    } else if (frame.opcode == 0 && fragmentOpcode >= 0) {
                        fragment.write(frame.payload);
                        if (frame.fin) {
                            handleMessage(fragmentOpcode, fragment.toByteArray());
                            fragmentOpcode = -1;
                        }
                    }
                }
                long now = System.currentTimeMillis();
                if (!ready && now > readyDeadline) {
                    fail("O provedor de voz não respondeu a tempo.");
                    return;
                }
                if (stopRequested) {
                    if (!ready) { finish(); return; }
                    if (finalReceived && now - lastFinalAt > 700L) { finish(); return; }
                    if (now > finalDeadline) { finish(); return; }
                }
            }
            if (!finished && !cancelled) finish();
        } catch (Throwable t) {
            if (!cancelled && !finished) fail(friendly(t));
        } finally {
            closeQuietly();
        }
    }

    // ---------------------------------------------------------------- websocket

    private void connect() throws IOException {
        URI uri;
        try {
            uri = new URI("muse".equals(provider) ? MUSE_URL : GEMINI_URL);
        } catch (Exception e) {
            throw new IOException("Endpoint de voz inválido.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IOException("Endpoint de voz inválido.");
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) path += "?" + uri.getRawQuery();

        Socket plain = new Socket();
        plain.setTcpNoDelay(true);
        plain.connect(new InetSocketAddress(host, port), 15000);
        plain.setSoTimeout(15000);
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        socket = ssl;
        input = ssl.getInputStream();
        output = ssl.getOutputStream();

        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(port == 443 ? "" : (":" + port)).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("User-Agent: SIG-Harness-Android/1.1.6\r\n");
        if ("gemini".equals(provider)) {
            req.append("x-goog-api-key: ").append(safeHeader(apiKey)).append("\r\n");
        } else {
            req.append("Authorization: Bearer ").append(safeHeader(apiKey)).append("\r\n");
        }
        req.append("\r\n");
        output.write(req.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();

        String headers = readHeaders();
        String first = headers.split("\r\n", 2)[0];
        if (first == null || first.indexOf(" 101") < 0) {
            String status = first == null ? "sem status" : first.replace("HTTP/1.1 ", "").replace("HTTP/1.0 ", "");
            throw new IOException("Conexão recusada pelo provedor (" + status + ").");
        }
        socket.setSoTimeout(500);
    }

    private String readHeaders() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (buffer.size() < 16384) {
            int b = input.read();
            if (b < 0) throw new EOFException("Conexão encerrada no handshake.");
            buffer.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
        }
        return buffer.toString("ISO-8859-1");
    }

    private void sendHandshake() throws IOException {
        JSONObject json = new JSONObject();
        try {
            if ("gemini".equals(provider)) {
                JSONObject setup = new JSONObject();
                setup.put("model", "models/gemini-3.5-transcribe-live");
                JSONObject generation = new JSONObject();
                generation.put("responseModalities", new JSONArray().put("TEXT"));
                setup.put("generationConfig", generation);
                JSONObject transcription = new JSONObject();
                transcription.put("mode", "VERBATIM");
                transcription.put("languageCodes", new JSONArray().put("pt-BR"));
                setup.put("inputAudioTranscription", transcription);
                json.put("setup", setup);
            } else {
                JSONObject auth = new JSONObject();
                auth.put("accessToken", apiKey);
                json.put("authorization", auth);
                json.put("audioEncoding", "PCM_16KHZ");
                json.put("model", "muse-voice-transcribe-1.0");
                json.put("mode", "ENDPOINTING");
                json.put("partialMode", "CUMULATIVE");
                json.put("emitAudioProgress", false);
                json.put("languageBias", new JSONArray().put("Portuguese"));
            }
        } catch (Exception e) {
            throw new IOException("Falha ao montar o handshake.");
        }
        sendText(json.toString());
    }

    private Frame readFrame() throws IOException {
        int b0 = input.read();
        if (b0 < 0) return null;
        int b1 = readByte();
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7f;
        if (length == 126) {
            length = ((long) readByte() << 8) | readByte();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | readByte();
        }
        if (length < 0 || length > 2_000_000L) throw new IOException("Frame WebSocket inválido.");
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }
        byte[] payload = new byte[(int) length];
        readFully(payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return new Frame(fin, opcode, payload);
    }

    private int readByte() throws IOException {
        int b = input.read();
        if (b < 0) throw new EOFException("Conexão encerrada.");
        return b;
    }

    private void readFully(byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) throw new EOFException("Conexão encerrada.");
            offset += read;
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        synchronized (writeLock) {
            if (output == null || socket == null || socket.isClosed()) throw new IOException("Sem conexão.");
            ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
            frame.write(0x80 | (opcode & 0x0f));
            int length = payload.length;
            if (length < 126) {
                frame.write(0x80 | length);
            } else if (length <= 65535) {
                frame.write(0x80 | 126);
                frame.write((length >> 8) & 0xff);
                frame.write(length & 0xff);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) frame.write((int) (((long) length >> (8 * i)) & 0xff));
            }
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            for (int i = 0; i < length; i++) frame.write(payload[i] ^ mask[i & 3]);
            output.write(frame.toByteArray());
            output.flush();
        }
    }

    private void sendText(String text) throws IOException {
        writeFrame(1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendBinary(byte[] data) throws IOException {
        writeFrame(2, data);
    }

    private void sendGeminiAudio(byte[] pcm) throws IOException {
        JSONObject audio = new JSONObject();
        JSONObject input = new JSONObject();
        JSONObject realtime = new JSONObject();
        try {
            audio.put("data", Base64.encodeToString(pcm, Base64.NO_WRAP));
            audio.put("mimeType", "audio/pcm;rate=16000");
            input.put("audio", audio);
            realtime.put("realtimeInput", input);
        } catch (Exception e) {
            throw new IOException("Falha ao montar o áudio.");
        }
        sendText(realtime.toString());
    }

    private void sendEnd() throws IOException {
        if ("gemini".equals(provider)) {
            byte[] silence = new byte[CHUNK_BYTES];
            for (int i = 0; i < 20; i++) sendGeminiAudio(silence);
            JSONObject input = new JSONObject();
            JSONObject realtime = new JSONObject();
            try {
                input.put("audioStreamEnd", true);
                realtime.put("realtimeInput", input);
            } catch (Exception e) {
                throw new IOException("Falha ao encerrar o áudio.");
            }
            sendText(realtime.toString());
        } else {
            sendText("{\"type\":\"endStream\"}");
        }
    }

    // ---------------------------------------------------------------- provider events

    private void handleMessage(int opcode, byte[] payload) {
        // Gemini envia setupComplete/serverContent em frames BINÁRIOS (opcode 2);
        // Muse usa texto. O conteúdo dos dois é JSON UTF-8.
        if ((opcode != 1 && opcode != 2) || payload == null) return;
        String text = new String(payload, StandardCharsets.UTF_8);
        JSONObject json;
        try { json = new JSONObject(text); } catch (Exception e) { return; }
        if ("gemini".equals(provider)) handleGemini(json);
        else handleMuse(json);
    }

    private void handleMuse(JSONObject json) {
        if (json.has("sessionId") && !ready) {
            markReady();
        }
        String type = json.optString("type", "");
        if ("transcript".equals(type)) {
            String transcript = json.optString("transcript", "");
            boolean isFinal = json.optBoolean("final", false);
            if (isFinal) addFinal(transcript);
            else setPartial(transcript);
        } else if ("speechComplete".equals(type)) {
            addFinal(json.optString("transcript", ""));
        } else if ("error".equals(type)) {
            fail("O Muse Voice recusou a sessão.");
        }
    }

    private void handleGemini(JSONObject json) {
        if (json.has("setupComplete") && !ready) {
            markReady();
        }
        if (json.has("error")) {
            fail("O Gemini recusou a sessão.");
            return;
        }
        if (json.optBoolean("generationComplete", false)) {
            finalReceived = true;
            lastFinalAt = System.currentTimeMillis();
        }
        JSONObject content = json.optJSONObject("serverContent");
        if (content != null) {
            JSONObject interim = content.optJSONObject("interimInputTranscription");
            if (interim != null) setPartial(interim.optString("text", ""));
            JSONObject finalTranscription = content.optJSONObject("inputTranscription");
            if (finalTranscription != null) addFinal(finalTranscription.optString("text", ""));
            if (content.optBoolean("turnComplete", false) || content.optBoolean("generationComplete", false)) {
                finalReceived = true;
                lastFinalAt = System.currentTimeMillis();
            }
        }
    }

    private void markReady() {
        if (ready || stopRequested || cancelled) return;
        ready = true;
        postState("listening", "Ouvindo…");
        startAudio();
    }

    private void setPartial(String value) {
        String text = normalize(value);
        partialText = text;
        String display = finalText.isEmpty() ? text : (finalText + (text.isEmpty() ? "" : " " + text)).trim();
        postPartial(display);
    }

    private void addFinal(String value) {
        String text = normalize(value);
        if (text.isEmpty()) {
            partialText = "";
            return;
        }
        if (finalText.isEmpty()) finalText = text;
        else if (text.startsWith(finalText)) finalText = text;
        else if (finalText.endsWith(text)) { }
        else if (text.contains(finalText)) finalText = text;
        else finalText = (finalText + " " + text).trim();
        partialText = "";
        finalReceived = true;
        lastFinalAt = System.currentTimeMillis();
        postPartial(finalText);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- audio

    private void startAudio() {
        if (stopRequested || cancelled || finished) return;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = CHUNK_BYTES * 4;
            int bufferSize = Math.max(min, CHUNK_BYTES * 4);
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                fail("Não foi possível abrir o microfone.");
                return;
            }
            record.startRecording();
            audio = record;
            audioThread = new Thread(new Runnable() {
                @Override public void run() { readAudio(); }
            }, "sig-stt-audio");
            audioThread.start();
        } catch (Throwable t) {
            fail("Não foi possível abrir o microfone.");
        }
    }

    private void readAudio() {
        AudioRecord record = audio;
        if (record == null) return;
        byte[] buffer = new byte[CHUNK_BYTES];
        try {
            while (!stopRequested && !cancelled && !finished && record == audio) {
                int read = record.read(buffer, 0, buffer.length);
                if (read < 0) break;
                if (read == 0) continue;
                byte[] chunk = read == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, read);
                if ("gemini".equals(provider)) sendGeminiAudio(chunk);
                else sendBinary(chunk);
            }
        } catch (Throwable t) {
            if (!cancelled && !finished) fail("Falha ao enviar o áudio.");
        }
    }

    private void stopAudio() {
        AudioRecord record = audio;
        audio = null;
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) { }
            try { record.release(); } catch (Throwable ignored) { }
        }
        Thread thread = audioThread;
        audioThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
        }
    }

    // ---------------------------------------------------------------- completion

    private void finish() {
        if (finished) return;
        finished = true;
        stopAudio();
        closeQuietly();
        final String text = finalText.trim();
        main.post(new Runnable() {
            @Override public void run() {
                callback.onState("idle", "");
                callback.onFinal(text);
            }
        });
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        stopAudio();
        closeQuietly();
        final String text = finalText.trim();
        final String reason = message == null ? "Falha na transcrição." : message;
        main.post(new Runnable() {
            @Override public void run() {
                if (!text.isEmpty()) callback.onFinal(text);
                else callback.onState("idle", "");
                callback.onError(reason);
            }
        });
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        socket = null;
        input = null;
        output = null;
    }

    private void postState(final String state, final String detail) {
        main.post(new Runnable() {
            @Override public void run() { callback.onState(state, detail); }
        });
    }

    private void postPartial(final String text) {
        main.post(new Runnable() {
            @Override public void run() { callback.onPartial(text); }
        });
    }

    private static String safeHeader(String value) {
        if (value == null) return "";
        return value.replace("\r", "").replace("\n", "").trim();
    }

    private static String friendly(Throwable t) {
        if (t == null) return "Falha na transcrição.";
        if (t instanceof java.net.UnknownHostException) return "Sem conexão com o provedor de voz.";
        if (t instanceof javax.net.ssl.SSLException) return "Falha TLS ao conectar ao provedor de voz.";
        String message = t.getMessage();
        if (message == null || message.isEmpty()) return "Falha na transcrição.";
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;
        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }
}
