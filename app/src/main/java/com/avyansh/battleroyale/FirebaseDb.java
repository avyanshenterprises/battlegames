package com.avyansh.battleroyale;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal Firebase Realtime Database client over the REST API (no SDK / google-services.json needed). */
public final class FirebaseDb {
	public interface Callback { void done(boolean ok, String body); }

	private final String base;
	private volatile String auth;
	private volatile long authTime;
	// 3 threads were not enough once position updates (9/s) + polling (8/s) ran together, requests queued up and lagged.
	private final ExecutorService pool = Executors.newFixedThreadPool(6);

	public FirebaseDb(String dbUrl) {
		String u = dbUrl == null ? "" : dbUrl.trim();
		while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
		base = u;
	}

	public boolean configured() { return base.startsWith("http") && !base.contains("YOUR-PROJECT"); }
	public void setAuth(String idToken) { auth = idToken; authTime = System.currentTimeMillis(); }
	/** Anonymous ID tokens live 1 hour; callers re-sign-in after this many ms. */
	public long authAgeMs() { return auth == null ? Long.MAX_VALUE : System.currentTimeMillis() - authTime; }

	public void get(String path, Callback cb) { run("GET", path, null, cb); }
	public void put(String path, String json, Callback cb) { run("PUT", path, json, cb); }
	public void patch(String path, String json, Callback cb) { run("PATCH", path, json, cb); }
	public void post(String path, String json, Callback cb) { run("POST", path, json, cb); }
	public void delete(String path, Callback cb) { run("DELETE", path, null, cb); }

	/** Anonymous sign-in (only needed when your database rules require auth != null). */
	public void signInAnonymously(final String apiKey, final Callback cb) {
		if (apiKey == null || apiKey.length() < 10 || apiKey.contains("YOUR")) { if (cb != null) cb.done(true, ""); return; }
		pool.execute(new Runnable() {
			@Override public void run() {
				try {
					URL url = new URL("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + apiKey);
					String res = request(url, "POST", "{\"returnSecureToken\":true}", false);
					org.json.JSONObject o = new org.json.JSONObject(res);
					String tok = o.optString("idToken", "");
					if (tok.length() > 0) setAuth(tok);
					if (cb != null) cb.done(tok.length() > 0, res);
				} catch (Throwable t) {
					if (cb != null) cb.done(false, String.valueOf(t));
				}
			}
		});
	}

	private void run(final String method, final String path, final String json, final Callback cb) {
		pool.execute(new Runnable() {
			@Override public void run() {
				try {
					StringBuilder u = new StringBuilder(base).append('/').append(path).append(".json");
					boolean q = false;
					if (path.contains("?")) { u.setLength(0); u.append(base).append('/').append(path.replace("?", ".json?")); q = true; }
					if (auth != null) u.append(q ? '&' : '?').append("auth=").append(URLEncoder.encode(auth, "UTF-8"));
					String m = method;
					boolean override = false;
					if (method.equals("PATCH")) { u.append(u.indexOf("?") >= 0 ? '&' : '?').append("x-http-method-override=PATCH"); m = "POST"; override = true; }
					String res = request(new URL(u.toString()), m, json, override);
					if (cb != null) cb.done(true, res);
				} catch (Throwable t) {
					if (cb != null) cb.done(false, String.valueOf(t));
				}
			}
		});
	}

	private static String request(URL url, String method, String body, boolean patchOverride) throws Exception {
		HttpURLConnection c = (HttpURLConnection) url.openConnection();
		try {
			c.setConnectTimeout(6000);
			c.setReadTimeout(8000);
			c.setRequestMethod(method);
			c.setRequestProperty("Content-Type", "application/json");
			if (patchOverride) c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
			if (body != null) {
				c.setDoOutput(true);
				byte[] b = body.getBytes("UTF-8");
				OutputStream os = c.getOutputStream();
				os.write(b); os.flush(); os.close();
			}
			int code = c.getResponseCode();
			InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
			StringBuilder sb = new StringBuilder();
			if (is != null) {
				BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
				try {
					String line;
					while ((line = r.readLine()) != null) sb.append(line);
				} finally { r.close(); }
			}
			if (code >= 400) throw new Exception("HTTP " + code + " " + sb);
			return sb.toString();
		} finally {
			c.disconnect();
		}
	}
}
