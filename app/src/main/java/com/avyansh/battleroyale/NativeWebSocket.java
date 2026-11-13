package com.avyansh.battleroyale;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Small RFC 6455 client using only Android/Java platform APIs (no OkHttp/Okio). */
final class NativeWebSocket {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();

    private NativeWebSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    static NativeWebSocket connect(String address, int timeoutMs) throws Exception {
        URI uri = new URI(address);
        String scheme = uri.getScheme();
        if (!"wss".equalsIgnoreCase(scheme)) {
            throw new IOException("A secure wss:// server URL is required");
        }
        String host = uri.getHost();
        if (host == null || host.length() == 0) throw new IOException("Invalid WebSocket host");
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        String path = uri.getRawPath();
        if (path == null || path.length() == 0) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        SSLSocket ssl = null;
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String key = java.util.Base64.getEncoder().encodeToString(nonce);
            String hostHeader = host + (port == 443 ? "" : ":" + port);
            String request = "GET " + path + " HTTP/1.1\r\n" +
                    "Host: " + hostHeader + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + key + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: AvyanshBattleRoyale/1.0\r\n\r\n";
            OutputStream output = ssl.getOutputStream();
            output.write(request.getBytes(UTF8));
            output.flush();
            String headers = readHttpHeaders(ssl.getInputStream());
            String[] lines = headers.split("\r\n");
            if (lines.length == 0 || !lines[0].contains(" 101 ")) {
                throw new IOException("WebSocket upgrade rejected: " + (lines.length > 0 ? lines[0] : "empty response") + " (check WSS host, /ws path, HTTPS proxy upgrade, and server port)");
            }
            String expected = java.util.Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(UTF8)));
            boolean accepted = false;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && "sec-websocket-accept".equalsIgnoreCase(lines[i].substring(0, colon).trim())) {
                    accepted = expected.equals(lines[i].substring(colon + 1).trim());
                    break;
                }
            }
            if (!accepted) throw new IOException("Invalid WebSocket handshake response");
            ssl.setSoTimeout(timeoutMs);
            return new NativeWebSocket(ssl);
        } catch (Exception e) {
            try { if (ssl != null) ssl.close(); else raw.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private static String readHttpHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < 16384) {
            int b = input.read();
            if (b < 0) throw new EOFException("Server closed during WebSocket handshake");
            bytes.write(b);
            if ((state == 0 && b == '\r') || (state == 2 && b == '\r')) state++;
            else if ((state == 1 && b == '\n') || (state == 3 && b == '\n')) state++;
            else state = (b == '\r') ? 1 : 0;
            if (state == 4) return new String(bytes.toByteArray(), UTF8);
        }
        throw new IOException("WebSocket handshake headers too large");
    }

    synchronized void sendText(String text) throws IOException {
        writeFrame(0x1, text.getBytes(UTF8));
    }

    synchronized void close() {
        try { writeFrame(0x8, new byte[] { 0x03, (byte) 0xE8 }); } catch (Exception ignored) {}
        try { socket.close(); } catch (Exception ignored) {}
    }

    String readText() throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean started = false;
        while (true) {
            int first = readByte();
            int second = readByte();
            int opcode = first & 0x0f;
            boolean fin = (first & 0x80) != 0;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) length = readUnsigned(2);
            else if (length == 127) length = readUnsigned(8);
            if (length > 1024 * 1024) throw new IOException("WebSocket message too large");
            byte[] mask = masked ? readBytes(4) : null;
            byte[] payload = readBytes((int) length);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i & 3]);

            if (opcode == 0x8) throw new EOFException("WebSocket closed by server");
            if (opcode == 0x9) { synchronized (this) { writeFrame(0xA, payload); } continue; }
            if (opcode == 0xA) continue;
            if (opcode == 0x1) started = true;
            if (opcode == 0x1 || opcode == 0x0) {
                if (!started && opcode == 0x0) throw new IOException("Unexpected continuation frame");
                message.write(payload);
                if (fin) return new String(message.toByteArray(), UTF8);
            } else if (opcode != 0x2) {
                throw new IOException("Unsupported WebSocket frame");
            } else {
                throw new IOException("Unexpected binary WebSocket response");
            }
        }
    }

    private int readByte() throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException("WebSocket connection closed");
        return value;
    }

    private long readUnsigned(int count) throws IOException {
        long value = 0;
        for (int i = 0; i < count; i++) value = (value << 8) | readByte();
        return value;
    }

    private byte[] readBytes(int count) throws IOException {
        byte[] result = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = in.read(result, offset, count - offset);
            if (n < 0) throw new EOFException("Incomplete WebSocket frame");
            offset += n;
        }
        return result;
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        if (socket.isClosed()) throw new IOException("WebSocket is closed");
        out.write(0x80 | (opcode & 0x0f));
        int length = payload.length;
        if (length < 126) out.write(0x80 | length);
        else if (length <= 65535) {
            out.write(0x80 | 126); out.write((length >>> 8) & 255); out.write(length & 255);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) out.write((int) (((long) length >>> (8 * i)) & 255));
        }
        byte[] mask = new byte[4]; random.nextBytes(mask); out.write(mask);
        byte[] encoded = Arrays.copyOf(payload, payload.length);
        for (int i = 0; i < encoded.length; i++) encoded[i] = (byte) (encoded[i] ^ mask[i & 3]);
        out.write(encoded); out.flush();
    }
}
