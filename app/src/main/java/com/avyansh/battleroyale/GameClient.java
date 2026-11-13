package com.avyansh.battleroyale;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Connection to the dedicated server (TCP, newline-delimited JSON, see server/PROTOCOL.md).
 * <p>Threading: one reader thread + one writer thread. Incoming messages are queued and handed to the caller
 * on <b>its</b> thread through {@link #pump(Handler)}, so the GL/UI threads never block on the network and never
 * touch game state from a socket thread. Reconnects automatically (exponential backoff) with the saved token,
 * which makes the server resume a running match.</p>
 * Pure Java: no Android dependency.
 */
public final class GameClient {
	public interface Handler { void onMessage(Map<String, Object> m); }
	public interface Store { String get(String key); void put(String key, String value); }
	public interface StateListener { void onState(int state, String info); }

	public static final int DISCONNECTED = 0, CONNECTING = 1, CONNECTED = 2;
	private static final Charset UTF8 = Charset.forName("UTF-8");

	private final String uid;
	private volatile String name;
	private final Store store;
	private volatile String host;
	private volatile int port;
	private volatile boolean websocket, secure;
	private volatile String wsPath = "/ws";
	private volatile boolean wantOnline;
	private volatile Socket socket;
	private volatile int state = DISCONNECTED;
	private volatile StateListener stateListener;
	private final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<String>(512);
	private final ConcurrentLinkedQueue<Map<String, Object>> in = new ConcurrentLinkedQueue<Map<String, Object>>();
	private Thread connThread;
	private volatile long serverOffsetMs;      // serverTime - localTime (NTP-style, smoothed)
	private volatile int rttMs;
	private volatile boolean welcomed;

	public GameClient(String uid, String name, Store store) { this.uid = uid; this.name = name; this.store = store; }

	public void setStateListener(StateListener l) { stateListener = l; }
	public int state() { return state; }
	public boolean isConnected() { return state == CONNECTED && welcomed; }
	public int rttMs() { return rttMs; }
	public String uid() { return uid; }
	public void setName(String n) { name = n; }
	/** Estimated current server time in ms (use for the `ct` field of fire messages). */
	public long serverNow() { return System.currentTimeMillis() + serverOffsetMs; }

	/** Connect using tcp host:port, ws://host:port/ws, or wss://domain/ws. */
	public synchronized void connectAddress(String address) {
		if (address == null) throw new IllegalArgumentException("server address is empty");
		String a = address.trim();
		if (a.startsWith("ws://") || a.startsWith("wss://")) {
			try {
				URI u = new URI(a);
				String scheme = u.getScheme();
				secure = "wss".equalsIgnoreCase(scheme);
				websocket = true;
				host = u.getHost();
				if (host == null || host.length() == 0) throw new IllegalArgumentException("invalid WebSocket host");
				port = u.getPort() > 0 ? u.getPort() : (secure ? 443 : 80);
				wsPath = u.getRawPath();
				if (wsPath == null || wsPath.length() == 0) wsPath = "/ws";
				if (u.getRawQuery() != null) wsPath += "?" + u.getRawQuery();
			} catch (Exception e) { throw new IllegalArgumentException("Invalid WebSocket URL: " + e.getMessage()); }
		} else {
			websocket = false; secure = false; wsPath = "/ws";
			int i = a.lastIndexOf(':');
			host = i > 0 ? a.substring(0, i) : a;
			port = 7777;
			if (i > 0) { try { port = Integer.parseInt(a.substring(i + 1).trim()); } catch (NumberFormatException e) { port = 7777; } }
		}
		wantOnline = true;
		if (connThread != null && connThread.isAlive()) return;
		connThread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "br-net");
		connThread.setDaemon(true); connThread.start();
	}

	public synchronized void connect(String host, int port) {
		this.host = host; this.port = port; this.websocket = false; this.secure = false; wantOnline = true;
		if (connThread != null && connThread.isAlive()) return;
		connThread = new Thread(new Runnable() { @Override public void run() { loop(); } }, "br-net");
		connThread.setDaemon(true);
		connThread.start();
	}

	public synchronized void disconnect() {
		wantOnline = false;
		closeSocket();
		out.clear();
		if (connThread != null) connThread.interrupt();
	}

	/** Queue a message. Returns false if the send queue is full (message dropped; input/fire are best-effort). */
	public boolean send(Map<String, Object> msg) {
		if (!welcomed && !"hello".equals(msg.get("t"))) return false;
		return out.offer(Json.write(msg));
	}

	/** Deliver queued server messages to {@code h}; call from the game loop or UI thread. Max 256 per call. */
	public int pump(Handler h) {
		int n = 0;
		Map<String, Object> m;
		while (n < 256 && (m = in.poll()) != null) { n++; h.onMessage(m); }
		return n;
	}

	private void setState(int s, String info) {
		state = s;
		StateListener l = stateListener;
		if (l != null) { try { l.onState(s, info); } catch (RuntimeException e) { /* listener bug must not kill networking */ } }
	}

	private void closeSocket() {
		Socket s = socket;
		socket = null;
		welcomed = false;
		if (s != null) { try { s.close(); } catch (IOException e) { /* ignore */ } }
	}

	private void loop() {
		long backoff = 500;
		while (wantOnline) {
			try {
				setState(CONNECTING, host + ":" + port);
				Socket s = secure ? SSLSocketFactory.getDefault().createSocket() : new Socket();
				s.setTcpNoDelay(true);
				s.setKeepAlive(true);
				s.connect(new InetSocketAddress(host, port), 8000);
				if (secure && s instanceof javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocket) s).startHandshake();
				socket = s;
				backoff = 500;
				if (websocket) websocketSession(s); else session(s);
			} catch (IOException e) {
				setState(DISCONNECTED, String.valueOf(e.getMessage()));
			} catch (RuntimeException e) {
				setState(DISCONNECTED, String.valueOf(e));
			}
			closeSocket();
			if (!wantOnline) break;
			setState(DISCONNECTED, "reconnecting");
			try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
			backoff = Math.min(8000, backoff * 2);
		}
		setState(DISCONNECTED, "closed");
	}


	private static void writeClientFrame(OutputStream os, int opcode, byte[] payload) throws IOException {
		int n = payload.length;
		byte[] mask = new byte[4]; new SecureRandom().nextBytes(mask);
		ByteArrayOutputStream b = new ByteArrayOutputStream(n + 14);
		b.write(0x80 | (opcode & 15));
		if (n < 126) b.write(0x80 | n);
		else if (n <= 65535) { b.write(0x80 | 126); b.write((n >>> 8) & 255); b.write(n & 255); }
		else {
			b.write(0x80 | 127);
			for (int i = 7; i >= 0; i--) b.write((int) (((long)n >>> (8 * i)) & 255));
		}
		b.write(mask);
		for (int i = 0; i < n; i++) b.write(payload[i] ^ mask[i & 3]);
		os.write(b.toByteArray()); os.flush();
	}

	private void websocketSession(final Socket s) throws IOException {
		final OutputStream os = s.getOutputStream();
		final BufferedInputStream bis = new BufferedInputStream(s.getInputStream());
		byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce);
		final String key = Base64.getEncoder().encodeToString(nonce);
		String request = "GET " + wsPath + " HTTP/1.1\r\nHost: " + host + ":" + port
			+ "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key
			+ "\r\nSec-WebSocket-Version: 13\r\nOrigin: https://avyansh-game.local\r\n\r\n";
		os.write(request.getBytes(UTF8)); os.flush();
		ByteArrayOutputStream headers = new ByteArrayOutputStream();
		int state = 0, ch;
		while ((ch = bis.read()) != -1) {
			headers.write(ch);
			if ((state == 0 && ch == 13) || (state == 2 && ch == 13)) state++;
			else if ((state == 1 && ch == 10) || (state == 3 && ch == 10)) state++;
			else state = ch == 13 ? 1 : 0;
			if (state == 4) break;
			if (headers.size() > 8192) throw new IOException("WebSocket handshake headers too large");
		}
		String response = new String(headers.toByteArray(), UTF8);
		if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101"))
			throw new IOException("WebSocket upgrade rejected: " + response.split("\\r?\\n", 2)[0]);
		String expected;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF8));
			expected = Base64.getEncoder().encodeToString(digest);
		} catch (Exception e) { throw new IOException(e); }
		if (!response.toLowerCase(java.util.Locale.US).contains("sec-websocket-accept: " + expected.toLowerCase(java.util.Locale.US)))
			throw new IOException("Invalid WebSocket accept header");

		out.clear();
		String token = store.get("token");
		Map<String, Object> hello = Json.obj("t", "hello", "uid", uid, "name", name, "v", 1);
		if (token != null && token.length() > 0) hello.put("token", token);
		writeClientFrame(os, 1, Json.write(hello).getBytes(UTF8));
		Thread writer = new Thread(new Runnable() {
			@Override public void run() {
				try {
					long lastPing = 0;
					while (socket == s) {
						String line = out.poll(250, TimeUnit.MILLISECONDS);
						long now = System.currentTimeMillis();
						if (line != null) writeClientFrame(os, 1, line.getBytes(UTF8));
						if (welcomed && now - lastPing > 2000) {
							lastPing = now;
							writeClientFrame(os, 1, Json.write(Json.obj("t", "ping", "ts", now)).getBytes(UTF8));
						}
					}
				} catch (IOException e) { try { s.close(); } catch (IOException x) { } }
				catch (InterruptedException e) { }
			}
		}, "br-ws-w");
		writer.setDaemon(true); writer.start();
		DataInputStream inStream = new DataInputStream(bis);
		while (true) {
			int b0 = inStream.read();
			if (b0 < 0) break;
			int b1 = inStream.read(); if (b1 < 0) break;
			int opcode = b0 & 15; long len = b1 & 127;
			if (len == 126) len = inStream.readUnsignedShort();
			else if (len == 127) {
				len = inStream.readLong();
				if (len < 0 || len > 1048576) throw new IOException("WebSocket frame too large");
			}
			boolean masked = (b1 & 128) != 0;
			byte[] mask = masked ? new byte[4] : null;
			if (masked) inStream.readFully(mask);
			byte[] payload = new byte[(int) len]; inStream.readFully(payload);
			if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
			if (opcode == 8) break;
			if (opcode == 9) { writeClientFrame(os, 10, payload); continue; }
			if (opcode == 10) continue;
			if (opcode != 1) continue;
			String line = new String(payload, UTF8);
			Map<String, Object> m;
			try { m = Json.parseObject(line); } catch (RuntimeException e) { continue; }
			String t = Json.str(m, "t", "");
			if ("welcome".equals(t)) {
				String tk = Json.str(m, "token", null);
				if (tk != null) store.put("token", tk);
				welcomed = true; setState(CONNECTED, "online");
			} else if ("pong".equals(t)) {
				long now = System.currentTimeMillis(), sent = (long) Json.num(m, "ts", now);
				int rtt = (int) Math.max(0, now - sent);
				rttMs = rttMs == 0 ? rtt : (rttMs * 3 + rtt) / 4;
				long off = (long) Json.num(m, "serverTime", now) + rtt / 2 - now;
				serverOffsetMs = serverOffsetMs == 0 ? off : (serverOffsetMs * 7 + off) / 8;
				continue;
			} else if ("error".equals(t) && ("bad_token".equals(Json.str(m, "code", "")) || "session_expired".equals(Json.str(m, "code", "")))) {
                store.put("token", "");
                setState(DISCONNECTED, "auth_required");
            }
			if (in.size() < 4096) in.add(m);
		}
		throw new IOException("WebSocket closed");
	}

	private void session(final Socket s) throws IOException {
		final OutputStream os = s.getOutputStream();
		out.clear();
		String token = store.get("token");
		Map<String, Object> hello = Json.obj("t", "hello", "uid", uid, "name", name, "v", 1);
		if (token != null && token.length() > 0) hello.put("token", token);
		os.write((Json.write(hello) + "\n").getBytes(UTF8));
		os.flush();
		Thread writer = new Thread(new Runnable() {
			@Override public void run() {
				try {
					long lastPing = 0;
					while (socket == s) {
						String line = out.poll(250, TimeUnit.MILLISECONDS);
						long now = System.currentTimeMillis();
						if (line != null) { os.write((line + "\n").getBytes(UTF8)); os.flush(); }
						if (welcomed && now - lastPing > 2000) {
							lastPing = now;
							os.write((Json.write(Json.obj("t", "ping", "ts", now)) + "\n").getBytes(UTF8));
							os.flush();
						}
					}
				} catch (IOException e) { try { s.close(); } catch (IOException x) { /* ignore */ } }
				catch (InterruptedException e) { /* stop */ }
			}
		}, "br-net-w");
		writer.setDaemon(true);
		writer.start();
		BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8), 16384);
		String line;
		while ((line = r.readLine()) != null) {
			if (line.length() == 0) continue;
			Map<String, Object> m;
			try { m = Json.parseObject(line); } catch (RuntimeException e) { continue; }
			String t = Json.str(m, "t", "");
			if ("welcome".equals(t)) {
				String tk = Json.str(m, "token", null);
				if (tk != null) store.put("token", tk);
				welcomed = true;
				setState(CONNECTED, "online");
			} else if ("pong".equals(t)) {
				long now = System.currentTimeMillis();
				long sent = (long) Json.num(m, "ts", now);
				int rtt = (int) Math.max(0, now - sent);
				rttMs = rttMs == 0 ? rtt : (rttMs * 3 + rtt) / 4;
				long off = (long) Json.num(m, "serverTime", now) + rtt / 2 - now;
				serverOffsetMs = serverOffsetMs == 0 ? off : (serverOffsetMs * 7 + off) / 8;
				continue;
			} else if ("error".equals(t) && "bad_token".equals(Json.str(m, "code", ""))) {
				store.put("token", "");
			}
			if (in.size() < 4096) in.add(m);     // bounded: a stalled game loop cannot exhaust memory
		}
		throw new IOException("server closed connection");
	}
}
