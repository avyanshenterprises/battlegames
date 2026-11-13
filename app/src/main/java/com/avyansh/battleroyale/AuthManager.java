package com.avyansh.battleroyale;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

/** Account authentication over the platform TLS/WebSocket stack; no external JARs required. */
public final class AuthManager {
    public interface Callback {
        void onResult(boolean ok, String message, String uid, String username, String token);
    }

    private static AuthManager instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AuthManager() {}

    public static synchronized AuthManager get() {
        if (instance == null) instance = new AuthManager();
        return instance;
    }

    public void authenticate(Context context, final String action, final String identity,
                             final String password, final Callback callback) {
        final String requestAction = action;
        final String requestIdentity = identity == null ? "" : identity.trim();
        final String requestPassword = password == null ? "" : password;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                NativeWebSocket ws = null;
                try {
                    ws = NativeWebSocket.connect(ServerConfig.AUTH_WS_URL, 8000);
                    JSONObject request = new JSONObject();
                    request.put("t", requestAction);
                    if ("auth_resume".equals(requestAction)) {
                        request.put("token", requestIdentity);
                    } else if ("auth_google".equals(requestAction)) {
                        request.put("idToken", requestIdentity);
                    } else if ("auth_google_exchange".equals(requestAction)) {
                        request.put("code", requestIdentity);
                        request.put("codeVerifier", requestPassword);
                    } else {
                        request.put("identity", requestIdentity);
                        request.put("password", requestPassword);
                    }
                    ws.sendText(request.toString() + "\n");
                    String response = ws.readText().trim();
                    JSONObject result = new JSONObject(response);
                    if ("auth_ok".equals(result.optString("t"))) {
                        deliver(callback, true, "Authenticated", result.optString("uid"),
                                result.optString("username"), result.optString("token"));
                    } else if ("auth_error".equals(result.optString("t"))) {
                        deliver(callback, false, result.optString("message", "Authentication failed"), "", "", "");
                    } else {
                        deliver(callback, false, "Unexpected authentication response", "", "", "");
                    }
                } catch (Exception e) {
                    String message = e.getMessage();
                    if (message == null || message.length() == 0) message = "connection failed";
                    deliver(callback, false, "Server unavailable: " + message, "", "", "");
                } finally {
                    if (ws != null) ws.close();
                }
            }
        }, "Avyansh-Auth");
        worker.setDaemon(true);
        worker.start();
    }

    /** Exchanges a short-lived, one-use code returned by the server's Google OAuth callback. */
    public void exchangeGoogleLoginCode(Context context, String code, String verifier, Callback callback) {
        authenticate(context, "auth_google_exchange", code, verifier, callback);
    }

    public void logout(final String uid, final String token, final Runnable done) {
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                NativeWebSocket ws = null;
                try {
                    ws = NativeWebSocket.connect(ServerConfig.AUTH_WS_URL, 8000);
                    JSONObject request = new JSONObject();
                    request.put("t", "auth_logout");
                    request.put("uid", uid == null ? "" : uid);
                    request.put("token", token == null ? "" : token);
                    ws.sendText(request.toString() + "\n");
                    ws.readText();
                } catch (Exception ignored) {
                    // Local logout still completes even if the server cannot be reached.
                } finally {
                    if (ws != null) ws.close();
                    if (done != null) mainHandler.post(done);
                }
            }
        }, "Avyansh-Logout");
        worker.setDaemon(true);
        worker.start();
    }

    private void deliver(final Callback callback, final boolean ok, final String message,
                         final String uid, final String username, final String token) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                callback.onResult(ok, message, uid, username, token);
            }
        });
    }
}
