package com.avyansh.battleroyale;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import android.os.Build;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.net.URLEncoder;

/** App entry point. No Lobby navigation occurs until account auth AND the game WebSocket welcome succeed. */
public class MainActivity extends Activity {
	
	private TextView status, error;
	private EditText identity, password;
	private Button login, register, retry, googleButton;
	private ProgressBar progress;
	private ImageButton togglePassword;
	
	private final Handler handler = new Handler(Looper.getMainLooper());
	private boolean connectingGame;
	private Runnable poll;
	private boolean passwordVisible = false;
	
	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		showAuthUi();
		if (!handleGoogleRedirect(getIntent()) && SessionManager.hasSession(this)) {
			authenticate("auth_resume", SessionManager.token(this), "");
		}
        
        goFullscreen();
	}
	
    private void goFullscreen() {
		try {
			Window window = getWindow();
			window.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				window.setDecorFitsSystemWindows(false);
				WindowInsetsController c = window.getInsetsController();
				if (c != null) {
					c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
					c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
				}
			} else {
				window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
				window.getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
				| View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				WindowManager.LayoutParams lp = window.getAttributes();
				lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
				window.setAttributes(lp);
			}
		} catch (RuntimeException ignored) { }
	}
    
	private void showAuthUi() {
		setContentView(R.layout.main);
		
		status        = findViewById(R.id.statusText);
		error         = findViewById(R.id.errorText);
		identity      = findViewById(R.id.identityInput);
		password      = findViewById(R.id.passwordInput);
		login         = findViewById(R.id.loginButton);
		register      = findViewById(R.id.registerButton);
		retry         = findViewById(R.id.retryButton);
		googleButton  = findViewById(R.id.googleButton);
		progress      = findViewById(R.id.progressBar);
		togglePassword = findViewById(R.id.togglePassword);
		
		login.setOnClickListener(v -> submit("auth_login"));
		register.setOnClickListener(v -> submit("auth_register"));
		googleButton.setOnClickListener(v -> startGoogleSignIn());
		
		retry.setOnClickListener(v -> {
			if (SessionManager.hasSession(this)) {
				authenticate("auth_resume", SessionManager.token(this), "");
			} else {
				submit("auth_login");
			}
		});
		
		togglePassword.setOnClickListener(v -> togglePasswordVisibility());
	}
	
	private void togglePasswordVisibility() {
		passwordVisible = !passwordVisible;
		if (passwordVisible) {
			password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
		} else {
			password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		}
		password.setSelection(password.getText().length());
	}
	
	private void submit(String action) {
		String id = identity.getText().toString().trim();
		String pw = password.getText().toString();
		
		if (id.isEmpty() || pw.isEmpty()) {
			showError("Enter your username/email and password.");
			return;
		}
		if (pw.length() < 10) {
			showError("Password must contain at least 10 characters.");
			return;
		}
		hideError();
		authenticate(action, id, pw);
	}
	
	private void showError(String message) {
		error.setText(message);
		error.setVisibility(View.VISIBLE);
	}
	
	private void hideError() {
		error.setText("");
		error.setVisibility(View.GONE);
	}
	
	private void setBusy(boolean busy, String message) {
		status.setText(message);
		login.setEnabled(!busy);
		register.setEnabled(!busy);
		identity.setEnabled(!busy);
		password.setEnabled(!busy);
		togglePassword.setEnabled(!busy);
		googleButton.setEnabled(!busy);
		progress.setVisibility(busy ? View.VISIBLE : View.GONE);
		retry.setVisibility(View.GONE);
		
		float alpha = busy ? 0.6f : 1f;
		login.setAlpha(alpha);
		register.setAlpha(alpha);
	}
	
	private void authenticate(String action, String id, String pw) {
		setBusy(true, "Connecting to authentication server…");
		hideError();
		AuthManager.get().authenticate(this, action, id, pw,
			(ok, msg, uid, name, token) -> completeAuthentication(ok, msg, uid, name, token));
	}

	private void completeAuthentication(boolean ok, String msg, String uid, String name, String token) {
		if (!ok) {
			setBusy(false, "Authentication unavailable");
			showError(msg);
			retry.setVisibility(View.VISIBLE);
			return;
		}
		try {
			SessionManager.save(this, uid, name, token);
			getSharedPreferences("net", MODE_PRIVATE).edit()
				.putString("uid", uid)
				.putString("name", name)
				.putString("server_addr", ServerConfig.AUTH_WS_URL)
				.apply();
			Net.init(this);
			Net.uid = uid;
			Net.name = name;
			ServerNet.init(this);
			ServerNet.connect();
			connectingGame = true;
			setBusy(true, "Account verified. Connecting to game server…");
			waitForGameSocket();
		} catch (Exception e) {
			setBusy(false, "Secure session error");
			showError("Could not store the secure session.");
		}
	}

	/** Starts server-mediated Google OAuth. Google credentials are exchanged only by the backend. */
	private void startGoogleSignIn() {
		setBusy(true, "Preparing secure Google sign-in…");
		hideError();
		Thread worker = new Thread(() -> {
			HttpURLConnection c = null;
			try {
				URI ws = new URI(ServerConfig.AUTH_WS_URL);
				if (!"wss".equalsIgnoreCase(ws.getScheme())) throw new Exception("Google sign-in requires a WSS server URL");
				byte[] random = new byte[32];
				new SecureRandom().nextBytes(random);
				String verifier = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
				String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8")),
					Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
				getSharedPreferences("google_oauth", MODE_PRIVATE).edit().putString("verifier", verifier).apply();
				URL start = new URL("https://" + ws.getRawAuthority() + "/auth/google/start?code_challenge=" + URLEncoder.encode(challenge, "UTF-8"));
				c = (HttpURLConnection) start.openConnection();
				c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestMethod("GET");
				int code = c.getResponseCode();
				BufferedReader r = new BufferedReader(new InputStreamReader(code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
				StringBuilder body = new StringBuilder(); String line;
				while ((line = r.readLine()) != null) body.append(line);
				r.close();
				org.json.JSONObject json = new org.json.JSONObject(body.toString());
				if (code >= 400) throw new Exception(json.optString("error", "Google OAuth is not configured on the server"));
				final String authUrl = json.optString("authUrl", "");
				if (authUrl.length() == 0) throw new Exception("Server did not return a Google authorization URL");
				handler.post(() -> {
					setBusy(false, "Choose your Google account to continue");
					try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))); }
					catch (Exception e) { showError("No browser is available for Google sign-in."); }
				});
			} catch (Exception e) {
				final String message = e.getMessage() == null ? "Google sign-in could not start" : e.getMessage();
				handler.post(() -> { setBusy(false, "Google sign-in unavailable"); showError(message); });
			} finally { if (c != null) c.disconnect(); }
		}, "Avyansh-Google-OAuth");
		worker.setDaemon(true); worker.start();
	}

	private boolean handleGoogleRedirect(Intent intent) {
		if (intent == null || intent.getData() == null) return false;
		Uri data = intent.getData();
		if (!"avyansh".equals(data.getScheme()) || !"google-auth".equals(data.getHost())) return false;
		String errorCode = data.getQueryParameter("error");
		if (errorCode != null) {
			showError("Google sign-in cancelled or failed: " + errorCode);
			return true;
		}
		String code = data.getQueryParameter("code");
		if (code == null || code.length() < 20) {
			showError("Google sign-in returned an invalid login code.");
			return true;
		}
		String verifier = getSharedPreferences("google_oauth", MODE_PRIVATE).getString("verifier", "");
		getSharedPreferences("google_oauth", MODE_PRIVATE).edit().remove("verifier").apply();
		if (verifier.length() < 40) {
			showError("Google sign-in session expired. Please try again.");
			return true;
		}
		setBusy(true, "Verifying Google account with game server…");
		AuthManager.get().exchangeGoogleLoginCode(this, code, verifier,
			(ok, msg, uid, name, token) -> completeAuthentication(ok, msg, uid, name, token));
		return true;
	}

	@Override protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		handleGoogleRedirect(intent);
	}

	private void waitForGameSocket() {
		if (poll != null) handler.removeCallbacks(poll);
		final long started = android.os.SystemClock.elapsedRealtime();
		
		poll = new Runnable() {
			public void run() {
				if (isFinishing()) return;
				
				if (ServerNet.isOnline()) {
					status.setText("Connected • Authentication successful");
					connectingGame = false;
					startActivity(new Intent(MainActivity.this, LobbyActivity.class));
					finish();
					return;
				}
				
				if (android.os.SystemClock.elapsedRealtime() - started > 20000) {
					setBusy(false, "Game server unavailable");
					showError("Your account is valid, but the multiplayer server did not accept the connection. Retry when the server is available.");
					retry.setVisibility(View.VISIBLE);
					connectingGame = false;
					return;
				}
				
				status.setText(ServerNet.stateCode() == GameClient.CONNECTING ? "Connecting to game server…" : "Reconnecting to game server…");
				handler.postDelayed(this, 250);
			}
		};
		handler.post(poll);
	}
	
	@Override
	protected void onDestroy() {
		if (poll != null) handler.removeCallbacks(poll);
		super.onDestroy();
	}
}
