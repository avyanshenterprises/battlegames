package com.avyansh.battleroyale;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;

/**
* Launcher screen: Free-Fire style lobby (res/layout/lobby.xml) on top of the live 3D hero.
* The match itself runs in {@link GameActivity}; every START opens it with a fresh GAME ID.
*/
public class LobbyActivity extends Activity implements Net.Listener, ServerNet.Listener {
	
	// ─── views ───
	private FrameLayout heroContainer;
	private Game3DView gameView;
	private View lobbyPanel;
	private TextView nameView, uidView, startText, roomInfo, roomPlayers, roomCodeText, badge, coinsText, diamondsText;
	private TextView modeBr, modeOnline, teamSolo, teamDuo, teamSquad;
	private View onlineBox, onlineIdle, onlineRoom, btnLeave, btnStart;
	private EditText roomCode;
	private LinearLayout squadSlots, friendsList;
	private View friendsDrawer;
	private FrameLayout nameTags;
	private MapView lobbyMap;
	private final ArrayList<TextView> tagViews = new ArrayList<>();
	
	// ─── state ───
	private SharedPreferences prefs;
	private boolean onlineMode, resumed, launching, pendingOnlineLaunch;
	private float sensitivity = 1.0f;
	private int tickCount;
	
	// ---- squad invitations (pop-up with avatar + name) ----
	private static final int INBOX_POLL_EVERY_TICKS = 2;       // 2 x 1.5 s
	private static final int INVITE_AVATAR_DP = 72;
	private final HashSet<String> seenInvites = new HashSet<>();
	private final ArrayDeque<String[]> inviteQueue = new ArrayDeque<>();   // {fromUid, name, room}
	private boolean inviteShowing;
	private String lastSquadKey = "";
	private final Handler ui = new Handler(Looper.getMainLooper());
	
	private final Runnable lobbyTick = new Runnable() {
		@Override public void run() {
			if (!resumed) return;
			if (ServerNet.isConfigured()) ServerNet.pump();
			if (Net.active && !Net.inMatch) Net.pollLobby();
			refreshOnline();
			if (tickCount++ % INBOX_POLL_EVERY_TICKS == 1) refreshBadge();
			ui.postDelayed(this, 1500);
		}
	};
	private final Runnable serverPump = new Runnable() {
		@Override public void run() {
			if (!resumed || isFinishing()) return;
			ServerNet.pump();
			refreshEconomyLabels();
			ui.postDelayed(this, 100);
		}
	};
	
	// ═══════════════════════════════════════════════════════════════════
	//  onCreate
	// ═══════════════════════════════════════════════════════════════════
	@Override protected void onCreate(Bundle b) {
		super.onCreate(b);
        if (!SessionManager.hasSession(this) || !ServerNet.isOnline()) {
            Intent gate = new Intent(this, MainActivity.class);
            gate.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(gate); finish(); return;
        }
        ServerNet.setAuthStateListener(() -> runOnUiThread(() -> {
            ServerNet.resetForLogout();
            Intent gate = new Intent(LobbyActivity.this, MainActivity.class);
            gate.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(gate); finish();
        }));
		goFullscreen();
		setContentView(R.layout.lobby);
		Net.init(this);
		Net.listener = this;
		
		prefs = getSharedPreferences("avyansh_game_settings", MODE_PRIVATE);
		sensitivity = prefs.getFloat("sensitivity", 1.0f);
		
		heroContainer = findViewById(R.id.game_container);
		lobbyPanel    = findViewById(R.id.lobby_panel);
		nameView      = findViewById(R.id.lobby_name);
		uidView       = findViewById(R.id.lobby_uid);
		onlineBox     = findViewById(R.id.online_box);
		onlineIdle    = findViewById(R.id.online_idle);
		onlineRoom    = findViewById(R.id.online_room);
		modeBr        = findViewById(R.id.mode_br);
		modeOnline    = findViewById(R.id.mode_online);
		teamSolo      = findViewById(R.id.team_solo);
		teamDuo       = findViewById(R.id.team_duo);
		teamSquad     = findViewById(R.id.team_squad);
		roomInfo      = findViewById(R.id.room_info);
		roomPlayers   = findViewById(R.id.room_players);
		roomCodeText  = findViewById(R.id.room_code_text);
		btnLeave      = findViewById(R.id.btn_leave);
		btnStart      = findViewById(R.id.btn_start);
		startText     = findViewById(R.id.btn_start_text);
		roomCode      = findViewById(R.id.room_code);
		squadSlots    = findViewById(R.id.squad_slots);
		friendsDrawer = findViewById(R.id.friends_drawer);
		friendsList   = findViewById(R.id.friends_list);
		badge         = findViewById(R.id.friends_badge);
		nameTags      = findViewById(R.id.name_tags);
		coinsText     = findViewById(R.id.text_coins);
		diamondsText  = findViewById(R.id.text_diamonds);
		findViewById(R.id.chip_coins).setOnClickListener(v -> openEconomy());
		findViewById(R.id.chip_diamonds).setOnClickListener(v -> openEconomy());
		findViewById(R.id.lobby_shop).setOnClickListener(v -> openEconomy());
		View friendsCard = findViewById(R.id.map_card);
		if (friendsCard != null) friendsCard.setOnClickListener(v -> startActivity(new Intent(this, FriendsActivity.class)));
		View friendsOpen = findViewById(R.id.friends_preview_open);
		if (friendsOpen != null) friendsOpen.setOnClickListener(v -> startActivity(new Intent(this, FriendsActivity.class)));
		// Resolve this optional custom-view ID dynamically because some Codeware
		// resource importers omit custom-view IDs from the generated R class.
		int lobbyMapId = getResources().getIdentifier("lobby_map", "id", getPackageName());
		lobbyMap = lobbyMapId != 0 ? findViewById(lobbyMapId) : null;
		if (lobbyMap != null) {
			lobbyMap.setOnTouchListener((v, ev) -> {
				if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
				return false;
			});
		}
		
		// Profile
		nameView.setText(Net.name);
		uidView.setText("UID  " + Net.uid + "  \u2022  COPY");
		nameView.setOnClickListener(v -> editName());
		findViewById(R.id.lobby_edit).setOnClickListener(v -> editName());
		uidView.setOnClickListener(v -> copy("UID", Net.uid));
		findViewById(R.id.lobby_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
		
		// Mode switch
		modeBr.setOnClickListener(v -> setMode(false));
		modeOnline.setOnClickListener(v -> setMode(true));
		
		// Room controls
		findViewById(R.id.btn_quick).setOnClickListener(v -> { ServerNet.init(this); if (ServerNet.isConfigured()) modeChooser(); else Net.quickMatch(); });
		findViewById(R.id.btn_create).setOnClickListener(v -> { ServerNet.init(this); if (ServerNet.isConfigured()) startActivity(new Intent(this, RoomCreateActivity.class)); else Net.createRoom(false); });
		findViewById(R.id.btn_join).setOnClickListener(v -> { ServerNet.init(this); if (ServerNet.isConfigured()) { ServerNet.connect(); ServerNet.joinRoom(roomCode.getText().toString(), ""); startActivity(new Intent(this, RoomLobbyActivity.class)); } else Net.joinRoom(roomCode.getText().toString()); });
		btnLeave.setOnClickListener(v -> { Net.leave(); refreshOnline(); });
		findViewById(R.id.btn_copy_room).setOnClickListener(v -> {
			if (Net.active) copy("Room code", Net.room);
		});
		roomPlayers.setOnClickListener(v -> showRoomList());
		teamSolo.setOnClickListener(v -> pickTeamSize(1));
		teamDuo.setOnClickListener(v -> pickTeamSize(2));
		teamSquad.setOnClickListener(v -> pickTeamSize(4));
		
		// Start
		btnStart.setOnClickListener(v -> onStartPressed());
		
		// Friends drawer
		findViewById(R.id.lobby_friends).setOnClickListener(v -> { ServerNet.init(this); if (ServerNet.isConfigured()) startActivity(new Intent(this, FriendsActivity.class)); else toggleFriendsDrawer(true); });
		findViewById(R.id.friends_close).setOnClickListener(v -> toggleFriendsDrawer(false));
		findViewById(R.id.friends_add).setOnClickListener(v -> addFriendDialog());
		
		// Coming soon nav
		findViewById(R.id.nav_weapons).setOnClickListener(v -> startActivity(new Intent(this, WeaponInventoryActivity.class)));
		findViewById(R.id.nav_collection).setOnClickListener(v -> startActivity(new Intent(this, CharacterInventoryActivity.class)));
		int[] soon = { R.id.nav_vault, R.id.nav_pet,
			R.id.lobby_mail };
		for (int id : soon) findViewById(id).setOnClickListener(v -> toast("Coming soon"));
		
		// Restore online mode
		onlineMode = Net.active;
		setMode(onlineMode);
		createHero();
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  3D hero (released while a match is running so two GL worlds never exist at once)
	// ═══════════════════════════════════════════════════════════════════
	private void openEconomy() {
		startActivity(new Intent(this, EconomyActivity.class));
	}

	private void refreshEconomyLabels() {
		ServerNet.EconomyState e = ServerNet.economy;
		if (e == null) return;
		if (coinsText != null) coinsText.setText(String.valueOf(e.coins));
		if (diamondsText != null) diamondsText.setText(String.valueOf(e.diamonds));
	}

	private void createHero() {
		if (gameView != null) return;
		applyGraphicsPreset(prefs.getInt("quality", 1));
		gameView = new Game3DView(this);
		gameView.setSensitivity(sensitivity);
		gameView.setGyroAimEnabled(false);
		heroContainer.addView(gameView, new FrameLayout.LayoutParams(
		FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		if (lobbyMap != null) lobbyMap.setEngine(gameView.getEngine());
	}
	
	private void releaseHero() {
		if (gameView == null) return;
		heroContainer.removeAllViews();   // detaches the GLSurfaceView -> its GL thread and textures are freed
		gameView = null;
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Fullscreen
	// ═══════════════════════════════════════════════════════════════════
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
	
	// ═══════════════════════════════════════════════════════════════════
	//  Graphics preset
	// ═══════════════════════════════════════════════════════════════════
	private void applyGraphicsPreset(int q) {
		if (q == 0) {
			GameSettings.VIEW_RADIUS_CHUNKS = 3;
			GameSettings.MAX_TREE_TRIS = 18000;
			GameSettings.MAX_BUILDING_TRIS = 18000;
			GameSettings.TREE_HI_DIST = 75f;
		} else if (q == 2) {
			GameSettings.VIEW_RADIUS_CHUNKS = 5;
			GameSettings.MAX_TREE_TRIS = 50000;
			GameSettings.MAX_BUILDING_TRIS = 50000;
			GameSettings.TREE_HI_DIST = 140f;
		} else {
			GameSettings.VIEW_RADIUS_CHUNKS = 4;
			GameSettings.MAX_TREE_TRIS = 35000;
			GameSettings.MAX_BUILDING_TRIS = 35000;
			GameSettings.TREE_HI_DIST = 110f;
		}
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Mode switch / team size
	// ═══════════════════════════════════════════════════════════════════
	private void styleSeg(TextView v, boolean active) {
		if (active) {
			v.setBackgroundResource(R.drawable.bg_mode_selected);
			v.setTextColor(0xFF17212B);
		} else {
			v.setBackgroundResource(0);
			v.setTextColor(0xFFC6D8E6);
		}
	}
	
	private void setMode(boolean online) {
		if (!online && Net.active) Net.leave();   // a hidden room would still be able to start a match under you
		onlineMode = online;
		styleSeg(modeBr, !online);
		styleSeg(modeOnline, online);
		onlineBox.setVisibility(online ? View.VISIBLE : View.GONE);
		lastSquadKey = "";
		refreshOnline();
	}
	
	private void pickTeamSize(int n) {
		if (!Net.active) { toast("Create or join a room first"); return; }
		if (!Net.host) { toast("Only the host can change the team size"); return; }
		Net.setTeamSize(n);
		refreshOnline();
	}
	
	private static String teamName(int size) {
		return size <= 1 ? "SOLO" : (size == 2 ? "DUO" : "SQUAD");
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Online status refresh
	// ═══════════════════════════════════════════════════════════════════
	private void refreshOnline() {
		runOnUiThread(() -> {
			if (isFinishing() || isDestroyed()) return;
			rebuildSquadSlots();
			
			if (!onlineMode) {
				startText.setText("START");
				btnStart.setAlpha(1f);
				return;
			}
			
			boolean inRoom = Net.active;
			onlineIdle.setVisibility(inRoom ? View.GONE : View.VISIBLE);
			onlineRoom.setVisibility(inRoom ? View.VISIBLE : View.GONE);
			
			if (!Net.configured()) {
				roomInfo.setText("Firebase not set: edit res/values/firebase_config.xml");
			} else if (Net.status.length() > 0) {
				roomInfo.setText(Net.status);
			} else {
				roomInfo.setText("Quick Match, Create or Join a room");
			}
			
			if (inRoom) {
				roomCodeText.setText("ROOM  " + Net.room);
				int n = Net.members().size();
				roomPlayers.setText(Math.max(1, n) + " / " + Net.MAX_PLAYERS + " players  \u2022  tap for list"
				+ (Net.host ? "  \u2022  HOST" : ""));
				styleSeg(teamSolo, Net.teamSize == 1);
				styleSeg(teamDuo, Net.teamSize == 2);
				styleSeg(teamSquad, Net.teamSize >= 4);
				float a = Net.host ? 1f : 0.55f;
				teamSolo.setAlpha(a); teamDuo.setAlpha(a); teamSquad.setAlpha(a);
			}
			
			if (!inRoom) {
				startText.setText("JOIN A ROOM");
				btnStart.setAlpha(0.5f);
			} else if (Net.host) {
				startText.setText("START MATCH");
				btnStart.setAlpha(1f);
			} else {
				startText.setText("WAITING FOR HOST");
				btnStart.setAlpha(0.5f);
			}
		});
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  SQUAD TILES (bottom centre — Free Fire style, one tile per team slot)
	// ═══════════════════════════════════════════════════════════════════
	private void rebuildSquadSlots() {
		if (squadSlots == null) return;
		
		ArrayList<Net.Member> group = new ArrayList<>();
		int slots = 1;
		if (onlineMode) {
			slots = Math.max(1, Net.teamSize);
			if (Net.active) group = Net.myGroup();
		}
		boolean hasMe = false;
		for (Net.Member m : group) if (m.isMe) hasMe = true;
		if (!hasMe) {
			Net.Member me = new Net.Member();
			me.uid = Net.uid; me.name = Net.name; me.isMe = true;
			me.isHost = !onlineMode || (Net.active && Net.host);
			group.add(0, me);
		}
		if (group.size() > slots) slots = group.size();
		
		// teammates are shown as 3D characters next to the hero (name tags are placed by tagTick)
		ArrayList<String> mateNames = new ArrayList<>();
		for (Net.Member m : group) if (!m.isMe) mateNames.add(m.name);
		if (gameView != null) gameView.getEngine().lobbyMates = mateNames.toArray(new String[0]);
		
		StringBuilder key = new StringBuilder().append(onlineMode).append('|').append(Net.active).append('|').append(slots);
		for (Net.Member m : group) key.append('|').append(m.uid).append(':').append(m.isMe ? Net.name : m.name).append(m.isHost);
		String k = key.toString();
		if (k.equals(lastSquadKey)) return;
		lastSquadKey = k;
		buildTags(mateNames);
		
		squadSlots.removeAllViews();
		for (int i = 0; i < slots; i++) {
			final Net.Member m = i < group.size() ? group.get(i) : null;
			squadSlots.addView(buildTile(m), tileLp());
		}
	}
	
	private void buildTags(ArrayList<String> mateNames) {
		nameTags.removeAllViews();
		tagViews.clear();
		for (int i = 0; i <= Math.min(3, mateNames.size()); i++) {
			TextView t = new TextView(this);
			boolean me = i == 0;
			t.setText(me ? Net.name + (onlineMode && Net.host ? "  \u2605" : "") : mateNames.get(i - 1));
			t.setTextColor(me ? 0xFFFFD45A : 0xFFFFFFFF);
			t.setTextSize(11);
			t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
			t.setBackground(PremiumDialog.shape(me ? 0xCC3A2A05 : 0xCC0B1622, me ? 0xFFFFD45A : 0xFF5C7389, 10, this));
			t.setPadding(dp(10), dp(3), dp(10), dp(3));
			t.setSingleLine(true);
			t.setVisibility(View.INVISIBLE);
			nameTags.addView(t, new FrameLayout.LayoutParams(-2, -2));
			tagViews.add(t);
		}
	}
	
	/** Moves the name tags to follow the 3D characters (screen positions come from the renderer). */
	private final Runnable tagTick = new Runnable() {
		@Override public void run() {
			if (!resumed) return;
			if (gameView != null && nameTags.getWidth() > 0) {
				GameEngine e = gameView.getEngine();
				for (int i = 0; i < tagViews.size() && i < 4; i++) {
					TextView t = tagViews.get(i);
					float fx = e.lobbyTagX[i], fy = e.lobbyTagY[i];
					if (fx < 0f || fx > 1f || fy < 0f || fy > 1f) { t.setVisibility(View.INVISIBLE); continue; }
					t.measure(0, 0);
					t.setTranslationX(fx * nameTags.getWidth() - t.getMeasuredWidth() / 2f);
					t.setTranslationY(fy * nameTags.getHeight() - t.getMeasuredHeight());
					t.setVisibility(View.VISIBLE);
				}
			}
			ui.postDelayed(this, 50);
		}
	};
	
	private LinearLayout.LayoutParams tileLp() {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(78));
		lp.setMargins(dp(3), 0, dp(3), 0);
		return lp;
	}
	
	private View buildTile(final Net.Member m) {
		LinearLayout tile = new LinearLayout(this);
		tile.setOrientation(LinearLayout.VERTICAL);
		tile.setGravity(Gravity.CENTER_HORIZONTAL);
		tile.setPadding(dp(4), dp(7), dp(4), dp(4));
		tile.setBackgroundResource(m == null ? R.drawable.bg_squad_empty : (m.isMe ? R.drawable.bg_tile_me : R.drawable.bg_squad_slot));
		
		FrameLayout av = new FrameLayout(this);
		av.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
		av.setBackgroundResource(R.drawable.bg_avatar_ring);
		if (m == null) {
			TextView plus = new TextView(this);
			plus.setText("+");
			plus.setTextColor(0xFFFFD45A);
			plus.setTextSize(20);
			plus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
			plus.setGravity(Gravity.CENTER);
			av.addView(plus, new FrameLayout.LayoutParams(-1, -1));
		} else {
			ImageView ic = new ImageView(this);
			ic.setImageResource(R.drawable.ic_person);
			ic.setScaleType(ImageView.ScaleType.CENTER);
			av.addView(ic, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
		}
		tile.addView(av);
		
		TextView name = new TextView(this);
		name.setText(m == null ? "EMPTY" : (m.isMe ? Net.name : m.name));
		name.setTextColor(m == null ? 0xFF8FA2B5 : 0xFFFFFFFF);
		name.setTextSize(10);
		name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		name.setSingleLine(true);
		name.setEllipsize(TextUtils.TruncateAt.END);
		name.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
		nlp.topMargin = dp(4);
		tile.addView(name, nlp);
		
		TextView sub = new TextView(this);
		sub.setTextSize(8);
		sub.setGravity(Gravity.CENTER);
		if (m == null) { sub.setText("INVITE"); sub.setTextColor(0xFFFFD45A); }
		else if (m.isHost) { sub.setText(m.isMe ? "YOU \u2022 HOST" : "HOST"); sub.setTextColor(0xFFFFD45A); }
		else { sub.setText(m.isMe ? "YOU" : "TEAMMATE"); sub.setTextColor(0xFF9CB3C4); }
		tile.addView(sub, new LinearLayout.LayoutParams(-1, -2));
		
		tile.setOnClickListener(v -> {
			if (m == null) { inviteDialog(); return; }
			if (m.isMe) { editName(); return; }
			if (Net.host) confirmKick(m);
		});
		return tile;
	}
	
	private void confirmKick(final Net.Member m) {
		PremiumDialog.show(this, "REMOVE PLAYER", m.name,
		PremiumDialog.text(this, "Remove " + m.name + " from this room?", 13, 0xFFEAF1F7, false),
		"REMOVE", () -> Net.kick(m.uid), "CANCEL", null, GameDialog.Tone.DANGER);
	}
	
	private void showRoomList() {
		if (!Net.active) return;
		LinearLayout col = PremiumDialog.column(this);
		ArrayList<Net.Member> all = Net.members();
		int ts = Math.max(1, Net.teamSize);
		if (all.isEmpty()) col.addView(PremiumDialog.text(this, "Loading\u2026", 13, 0xFFB9C9D5, false));
		for (int i = 0; i < all.size(); i++) {
			Net.Member m = all.get(i);
			if (i % ts == 0) PremiumDialog.section(this, col, "TEAM " + (i / ts + 1));
			col.addView(PremiumDialog.text(this, m.name + (m.isHost ? "  \u2605" : "") + (m.isMe ? "  (you)" : ""), 13, 0xFFFFFFFF, false));
		}
		PremiumDialog.show(this, "ROOM " + Net.room, teamName(ts) + "  \u2022  teams follow the join order",
		col, "CLOSE", null, null, null);
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Start match
	// ═══════════════════════════════════════════════════════════════════
	private void onStartPressed() {
		if (!onlineMode) {
			Net.inMatch = false;
			launchGame(false);
			return;
		}
		if (!Net.active) { toast("Create or join a room first"); return; }
		if (!Net.host) { toast("Only the host can start the match"); return; }
		if (Net.members().size() < 2) {
			PremiumDialog.show(this, "START ALONE?", "Room " + Net.room,
			PremiumDialog.text(this, "Nobody else has joined yet. The match will start with bots as opponents.", 13, 0xFFEAF1F7, false),
			"START", () -> Net.startMatch(), "WAIT", null, GameDialog.Tone.SUCCESS);
			return;
		}
		Net.startMatch();   // generates the GAME ID, assigns teams and calls onMatchStart() for everyone
	}
	
	/** Opens the match screen with a brand new GAME ID. */
	private void launchGame(boolean online) {
		if (launching || isFinishing() || isDestroyed()) return;
		if (!resumed) { pendingOnlineLaunch = online; return; }   // activities cannot be started from the background
		launching = true;
		String gameId = online && Net.matchId.length() > 0 ? Net.matchId : Net.newGameId();
		Intent i = new Intent(this, GameActivity.class);
		i.putExtra(GameActivity.EXTRA_GAME_ID, gameId);
		i.putExtra(GameActivity.EXTRA_ONLINE, online);
		i.putExtra(GameActivity.EXTRA_ROOM, online ? Net.room : "");
		i.putExtra(GameActivity.EXTRA_TEAM, online ? Net.myTeam : -1);
		i.putExtra(GameActivity.EXTRA_TEAM_SIZE, online ? Net.teamSize : 1);
		releaseHero();
		startActivity(i);
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Edit name / clipboard / toast
	// ═══════════════════════════════════════════════════════════════════
	private void editName() {
		final EditText et = new EditText(this);
		et.setSingleLine(true);
		et.setText(Net.name);
		et.setTextColor(0xFFFFFFFF);
		et.setHintTextColor(0xFF8FA2B5);
		et.setHint("Enter player name");
		et.setPadding(dp(12), dp(12), dp(12), dp(12));
		et.setBackground(PremiumDialog.shape(0xFF101C2A, 0xFF52677A, 9, this));
		
		PremiumDialog.show(this, "PLAYER IDENTITY", "Your name is visible to squad members", et,
		"SAVE", () -> {
			String value = et.getText().toString().trim();
			if (value.length() < 3) { toast("Name must contain at least 3 characters"); return; }
			Net.setName(this, value);
			nameView.setText(Net.name);
			lastSquadKey = "";
			refreshOnline();
		}, "CANCEL", null);
	}
	
	private void copy(String label, String text) {
		try {
			ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
			if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
			toast(label + " copied: " + text);
		} catch (RuntimeException e) { toast(text); }
	}
	
	private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
	
	// ═══════════════════════════════════════════════════════════════════
	//  FRIENDS DRAWER
	// ═══════════════════════════════════════════════════════════════════
	private void toggleFriendsDrawer(boolean open) {
		if (friendsDrawer == null) return;
		if (open) {
			friendsDrawer.setVisibility(View.VISIBLE);
			friendsDrawer.setTranslationX(0f);
			loadFriendsIntoDrawer();
		} else {
			friendsDrawer.setTranslationX(dp(300));
			friendsDrawer.setVisibility(View.GONE);
		}
	}
	
	private void refreshBadge() {
		if (!Net.configured()) return;
		Net.loadSocialInbox((ok, json) -> runOnUiThread(() -> {
			if (isFinishing() || isDestroyed()) return;
			try {
				JSONObject root = new JSONObject(json);
				JSONObject rq = root.optJSONObject("requests"), iv = root.optJSONObject("invites");
				int inv = iv == null ? 0 : iv.length();
				int total = (rq == null ? 0 : rq.length()) + inv;
				badge.setText(String.valueOf(total));
				badge.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
				collectNewInvites(iv);
			} catch (Throwable ignored) { }
		}));
	}
	
	/** Queues every invitation that has not been shown yet and opens the pop-up. */
	private void collectNewInvites(JSONObject invites) {
		if (invites == null) return;
		for (Iterator<String> it = invites.keys(); it.hasNext(); ) {
			String from = it.next();
			JSONObject o = invites.optJSONObject(from);
			if (o == null) continue;
			String room = o.optString("room", "");
			String key = from + ":" + o.optLong("createdAt", 0L);
			if (room.length() == 0 || !seenInvites.add(key)) continue;
			inviteQueue.add(new String[] { from, o.optString("name", "A friend"), room });
		}
		showNextInvite();
	}
	
	private void showNextInvite() {
		if (inviteShowing || inviteQueue.isEmpty() || Net.inMatch || isFinishing() || isDestroyed() || !resumed) return;
		final String[] inv = inviteQueue.poll();
		final String fromUid = inv[0], fromName = inv[1], room = inv[2];
		inviteShowing = true;
		android.app.Dialog d = GameDialog.with(this)
		.tone(GameDialog.Tone.SUCCESS)
		.glyph("\u2709")
		.title("SQUAD INVITATION")
		.subtitle("Room " + room)
		.content(buildInviteContent(fromName))
		.cancelable(false)
		.primary("ACCEPT", () -> {
			setMode(true);
			Net.acceptTeamInvite(fromUid, room);
		})
		.secondary("DECLINE", () -> Net.declineTeamInvite(fromUid))
		.show();
		d.setOnDismissListener(x -> { inviteShowing = false; showNextInvite(); });
	}
	
	/** Avatar + player name + short message. */
	private View buildInviteContent(String fromName) {
		LinearLayout col = new LinearLayout(this);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setGravity(Gravity.CENTER_HORIZONTAL);
		
		FrameLayout avatar = new FrameLayout(this);
		avatar.setBackgroundResource(R.drawable.bg_avatar_ring);
		ImageView icon = new ImageView(this);
		icon.setImageResource(R.drawable.ic_person);
		icon.setScaleType(ImageView.ScaleType.CENTER);
		avatar.addView(icon, new FrameLayout.LayoutParams(dp(INVITE_AVATAR_DP / 2), dp(INVITE_AVATAR_DP / 2), Gravity.CENTER));
		col.addView(avatar, new LinearLayout.LayoutParams(dp(INVITE_AVATAR_DP), dp(INVITE_AVATAR_DP)));
		
		TextView name = new TextView(this);
		name.setText(fromName);
		name.setTextColor(0xFFFFFFFF);
		name.setTextSize(20);
		name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		name.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
		nlp.topMargin = dp(10);
		col.addView(name, nlp);
		
		TextView msg = new TextView(this);
		msg.setText("invited you to join their squad");
		msg.setTextColor(0xFFA0B2C2);
		msg.setTextSize(13);
		msg.setGravity(Gravity.CENTER);
		col.addView(msg, new LinearLayout.LayoutParams(-2, -2));
		return col;
	}
	
	private void loadFriendsIntoDrawer() {
		if (friendsList == null) return;
		friendsList.removeAllViews();
		TextView loading = new TextView(this);
		loading.setText("Loading\u2026");
		loading.setTextColor(0xFFB9C9D5);
		loading.setTextSize(11);
		friendsList.addView(loading);
		
		Net.loadSocialInbox((ok, json) -> runOnUiThread(() -> {
			if (isFinishing() || isDestroyed() || friendsList == null) return;
			friendsList.removeAllViews();
			try {
				JSONObject root = new JSONObject(json);
				addDrawerSection("PENDING REQUESTS", root.optJSONObject("requests"), 0);
				addDrawerSection("TEAM INVITES",     root.optJSONObject("invites"),  1);
				addDrawerSection("FRIENDS",          root.optJSONObject("friends"),  2);
				if (!ok) addDrawerEmpty("Some data could not load \u2014 check Firebase rules.");
			} catch (Throwable e) {
				addDrawerEmpty("Unable to read social inbox: " + e.getMessage());
			}
		}));
	}
	
	private void addDrawerSection(String title, JSONObject data, int kind) {
		TextView h = new TextView(this);
		h.setText(title);
		h.setTextColor(0xFFFFD45A);
		h.setTextSize(10);
		h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		h.setPadding(dp(2), dp(12), 0, dp(4));
		friendsList.addView(h);
		
		int count = 0;
		if (data != null) {
			for (Iterator<String> it = data.keys(); it.hasNext(); ) {
				final String id = it.next();
				final JSONObject item = data.optJSONObject(id);
				final String display = item == null ? id : item.optString("name", id);
				friendsList.addView(buildFriendRow(id, display, kind, item));
				count++;
			}
		}
		if (count == 0) addDrawerEmpty("None");
	}
	
	private View buildFriendRow(final String id, String display, final int kind, final JSONObject item) {
		LinearLayout row = new LinearLayout(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(6), dp(6), dp(6), dp(6));
		row.setBackgroundResource(R.drawable.bg_panel_ff);
		LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
		rlp.bottomMargin = dp(5);
		
		FrameLayout av = new FrameLayout(this);
		av.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
		av.setBackgroundResource(R.drawable.bg_avatar_ring);
		ImageView ic = new ImageView(this);
		ic.setImageResource(R.drawable.ic_person);
		av.addView(ic, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
		row.addView(av);
		
		LinearLayout col = new LinearLayout(this);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setPadding(dp(8), 0, 0, 0);
		col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
		
		TextView name = new TextView(this);
		name.setText(display);
		name.setTextColor(0xFFFFFFFF);
		name.setTextSize(12);
		
		TextView sub = new TextView(this);
		sub.setText(id);
		sub.setTextColor(0xFF8FA2B5);
		sub.setTextSize(9);
		
		col.addView(name);
		col.addView(sub);
		row.addView(col);
		
		TextView action = new TextView(this);
		action.setText(kind == 0 ? "ACCEPT" : kind == 1 ? "JOIN" : "INVITE");
		action.setTextColor(0xFFFFD45A);
		action.setTextSize(10);
		action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		action.setPadding(dp(8), dp(6), dp(8), dp(6));
		action.setOnClickListener(v -> {
			if (kind == 0) {
				Net.acceptFriendRequest(id);
				toggleFriendsDrawer(false);
			} else if (kind == 1) {
				String room = item == null ? "" : item.optString("room", "");
				if (room.length() > 0) {
					setMode(true);
					Net.acceptTeamInvite(id, room);
					toggleFriendsDrawer(false);
				} else {
					toast("Invite has no room code");
				}
			} else {
				if (!Net.active) toast("Create or join a room first");
				else Net.inviteToTeam(id);
			}
		});
		row.addView(action);
		
		if (kind == 0 || kind == 1) {
			TextView decline = new TextView(this);
			decline.setText("DECLINE");
			decline.setTextColor(0xFFFF7777);
			decline.setTextSize(9);
			decline.setPadding(dp(6), dp(6), dp(2), dp(6));
			decline.setOnClickListener(v -> {
				if (kind == 0) Net.declineFriendRequest(id);
				else Net.declineTeamInvite(id);
				loadFriendsIntoDrawer();
			});
			row.addView(decline);
		}
		
		row.setLayoutParams(rlp);
		return row;
	}
	
	private void addDrawerEmpty(String s) {
		TextView t = new TextView(this);
		t.setText(s);
		t.setTextColor(0xFFB9C9D5);
		t.setTextSize(11);
		t.setPadding(dp(4), dp(4), 0, dp(8));
		friendsList.addView(t);
	}
	
	private void addFriendDialog() {
		final EditText id = new EditText(this);
		id.setSingleLine(true);
		id.setHint("Player UID");
		id.setTextColor(0xFFFFFFFF);
		id.setHintTextColor(0xFF8FA2B5);
		id.setBackground(PremiumDialog.shape(0xFF101C2A, 0xFF52677A, 9, this));
		
		PremiumDialog.show(this, "ADD FRIEND", "Enter the exact player UID (your own UID is shown under your name)", id,
		"SEND REQUEST", () -> {
			String uid = id.getText().toString().trim();
			if (uid.length() > 0) Net.sendFriendRequest(uid);
			else toast("Enter a player UID");
		}, "CANCEL", null);
	}
	
	private void inviteDialog() {
		if (!Net.active) { toast("Create or join a room first"); return; }
		final EditText id = new EditText(this);
		id.setSingleLine(true);
		id.setHint("Friend player UID");
		id.setTextColor(0xFFFFFFFF);
		id.setHintTextColor(0xFF8FA2B5);
		id.setBackground(PremiumDialog.shape(0xFF101C2A, 0xFF52677A, 9, this));
		
		PremiumDialog.show(this, "SQUAD INVITATION", "Invite a friend to room " + Net.room + "  (or just share the room code)", id,
		"SEND INVITE", () -> {
			String uid = id.getText().toString().trim();
			if (uid.length() > 0) Net.inviteToTeam(uid);
			else toast("Enter a player UID");
		}, "CANCEL", null);
	}
	
	private void modeChooser() {
		LinearLayout col = PremiumDialog.column(this);
		col.addView(PremiumDialog.text(this, "Choose a game mode. Matchmaking runs on the dedicated game server.", 13, 0xFFEAF1F7, false));
		PremiumDialog.show(this, "PLAY ONLINE", "Real players first, bots only fill empty seats", col,
			"BATTLE ROYALE", () -> startActivity(new Intent(this, BrMatchmakingActivity.class)),
			"CLASH SQUAD", () -> startActivity(new Intent(this, CsMatchmakingActivity.class)));
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Settings
	// ═══════════════════════════════════════════════════════════════════
	private void settings() {
		LinearLayout panel = PremiumDialog.column(this);
		
		PremiumDialog.section(this, panel, "CONTROLS");
		final float startSens = prefs.getFloat("sensitivity", 1.0f);
		sensitivity = startSens;
		final TextView sensTitle = PremiumDialog.text(this,
		"Look sensitivity  \u00B7  " + String.format(Locale.US, "%.1f", sensitivity), 13, 0xFFFFFFFF, true);
		panel.addView(sensTitle);
		
		final SeekBar sb = new SeekBar(this);
		sb.setMax(25);
		sb.setProgress(Math.max(0, Math.min(25, (int) (sensitivity * 10f) - 5)));
		sb.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFCD4B));
		sb.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFCD4B));
		sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onProgressChanged(SeekBar v, int p, boolean u) {
				sensitivity = (p + 5) / 10f;
				sensTitle.setText("Look sensitivity  \u00B7  " + String.format(Locale.US, "%.1f", sensitivity));
			}
			public void onStartTrackingTouch(SeekBar v) { }
			public void onStopTrackingTouch(SeekBar v) { }
		});
		panel.addView(sb);
		
		final CheckBox gyro = new CheckBox(this);
		gyro.setText("Gyroscope aim");
		gyro.setTextColor(0xFFEAF1F7);
		gyro.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFFFCD4B));
		gyro.setChecked(prefs.getBoolean("gyro", false));
		panel.addView(gyro);
		
		PremiumDialog.section(this, panel, "GRAPHICS QUALITY");
		final RadioGroup quality = new RadioGroup(this);
		quality.setOrientation(RadioGroup.VERTICAL);
		String[] names = {
			"Performance  \u00B7  smoother on low-end phones",
			"Balanced  \u00B7  recommended",
			"Ultra  \u00B7  higher visual quality"
		};
		int selected = prefs.getInt("quality", 1);
		for (int i = 0; i < names.length; i++) {
			RadioButton rb = new RadioButton(this);
			rb.setText(names[i]);
			rb.setTextSize(12);
			rb.setTextColor(0xFFEAF1F7);
			rb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFFFCD4B));
			rb.setId(100 + i);
			quality.addView(rb);
			if (i == selected) rb.setChecked(true);
		}
		panel.addView(quality);
		
		PremiumDialog.section(this, panel, "DISPLAY");
		panel.addView(PremiumDialog.text(this,
		"Changes are saved on this device and used by the next match.", 11, 0xFFA4B5C5, false));
		
		PremiumDialog.show(this, "GAME SETTINGS", "Fine-tune controls and visual quality", panel,
		"APPLY", () -> {
			int q = quality.getCheckedRadioButtonId() - 100;
			if (q < 0 || q > 2) q = 1;
			prefs.edit()
			.putFloat("sensitivity", sensitivity)
			.putBoolean("gyro", gyro.isChecked())
			.putInt("quality", q)
			.apply();
			applyGraphicsPreset(q);
			toast("Settings saved");
		}, "CLOSE", () -> sensitivity = startSens);
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Helpers
	// ═══════════════════════════════════════════════════════════════════
	private int dp(int v) {
		return Math.round(getResources().getDisplayMetrics().density * v);
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Net.Listener (may be called from a background thread)
	// ═══════════════════════════════════════════════════════════════════
	@Override public void onRoomChanged() { refreshOnline(); }
	@Override public void onStatus(String msg) { refreshOnline(); }
	@Override public void onChanged() { refreshOnline(); refreshEconomyLabels(); }
	@Override public void onToast(String msg) { runOnUiThread(() -> toast(msg)); }
	@Override public void onMatchStart() {
		runOnUiThread(() -> launchGame(true));
	}
	
	// ═══════════════════════════════════════════════════════════════════
	//  Lifecycle
	// ═══════════════════════════════════════════════════════════════════
	// LobbyActivity.java - Update onResume
	@Override protected void onResume() {
		super.onResume();
		resumed = true;
		launching = false;
		Net.listener = this;
		ServerNet.init(this);
		ServerNet.listener = this;
		ServerNet.connect();
		ServerNet.refreshEconomy();
		ServerNet.refreshFriends();
		refreshEconomyLabels();
		goFullscreen();
		createHero();
		if (gameView != null) gameView.onResume();
		lastSquadKey = "";
		refreshOnline();
		ui.removeCallbacks(lobbyTick);
		ui.post(lobbyTick);
		ui.removeCallbacks(serverPump);
		ui.post(serverPump);
		ui.removeCallbacks(tagTick);
		ui.post(tagTick);
		
		// Room should still be active after returning from a match
		// The host can start another match, players can see room again
		
		if (pendingOnlineLaunch && Net.active && Net.inMatch) {
			pendingOnlineLaunch = false;
			launchGame(true);
		}
	}
	
	@Override protected void onPause() {
		super.onPause();
		resumed = false;
		if (ServerNet.listener == this) ServerNet.listener = null;
		if (gameView != null) gameView.onPause();
		ui.removeCallbacks(lobbyTick);
		ui.removeCallbacks(serverPump);
		ui.removeCallbacks(tagTick);
	}
	
	@Override public void onBackPressed() {
		if (friendsDrawer != null && friendsDrawer.getVisibility() == View.VISIBLE) {
			toggleFriendsDrawer(false);
			return;
		}
		if (onlineMode && Net.active) {
			PremiumDialog.show(this, "LEAVE ROOM?", "Room " + Net.room,
			PremiumDialog.text(this, Net.host ? "You are the host: the room will be closed for everybody." : "You will leave this squad.",
			13, 0xFFEAF1F7, false),
			"LEAVE", () -> { Net.leave(); refreshOnline(); }, "STAY", null, GameDialog.Tone.DANGER);
			return;
		}
		super.onBackPressed();
	}
	
	@Override protected void onDestroy() {
		super.onDestroy();
		ui.removeCallbacks(lobbyTick);
		if (Net.listener == this) Net.listener = null;
		// only close the room when the player really leaves the app, not when a match is running
		if (isFinishing() && !Net.inMatch && !launching) Net.leave();
	}
}
