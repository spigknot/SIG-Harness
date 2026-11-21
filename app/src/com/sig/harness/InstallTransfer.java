package com.sig.harness;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Temporary loopback transfer from the APK to the Termux bootstrap. */
final class InstallTransfer implements AutoCloseable {
    private final ServerSocket listener;
    private final byte[] payload;
    private final String route = "/" + UUID.randomUUID();
    private volatile String step = "";
    private volatile Socket activePeer;

    InstallTransfer(byte[] payload) throws IOException {
        this.payload = payload;
        listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        } catch (IOException error) {
            close();
            throw error;
        }
        Thread thread = new Thread(this::serve, "sig-install-transfer");
        thread.start();
    }

    String endpoint() { return listener.getLocalPort() + route; }
    String step() { return step; }

    private void serve() {
        while (!listener.isClosed()) {
            try (Socket peer = listener.accept()) {
                activePeer = peer;
                if (listener.isClosed()) return;
                peer.setSoTimeout(2000);
                BufferedInputStream input = new BufferedInputStream(peer.getInputStream());
                String request = readLine(input);
                if (!readLine(input).isEmpty()) continue; // The bootstrap sends no headers.
                String prefix = "GET " + route;
                if (request.equals(prefix + "/payload HTTP/1.0")) {
                    reply(peer.getOutputStream(), payload);
                } else if (request.startsWith(prefix + "/step/") && request.endsWith(" HTTP/1.0")) {
                    String value = request.substring(prefix.length() + 6, request.length() - 9);
                    if (value.matches("[a-z_]{1,40}")) {
                        step = value;
                        reply(peer.getOutputStream(), new byte[0]);
                    }
                }
            } catch (IOException ignored) { }
            finally { activePeer = null; }
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < 256) {
            int next = input.read();
            if (next == '\n') return line.toString("US-ASCII").trim();
            if (next < 0) break;
            line.write(next);
        }
        throw new IOException("Incomplete request line");
    }

    private static void reply(OutputStream output, byte[] body) throws IOException {
        String header = "HTTP/1.0 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    @Override public void close() {
        try { listener.close(); } catch (IOException ignored) { }
        Socket peer = activePeer;
        if (peer != null) {
            try { peer.close(); } catch (IOException ignored) { }
        }
    }
}
