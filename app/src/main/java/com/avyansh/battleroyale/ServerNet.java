package com.avyansh.battleroyale;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android-side glue between the UI / game engine and the dedicated server ({@link GameClient}, protocol in server/PROTOCOL.md).
 *
 * <ul>
 *   <li>Lobby side (UI thread): rooms, friends, invites, matchmaking. Screens call {@link #pump()} from a 100 ms timer.</li>
 *   <li>Match side (GL thread): {@link #tick(float, GameEngine)} pumps snapshots, sends inputs/fire and applies server events.</li>
 * </ul>
 * The server is authoritative for health, damage, loot, zone, kills and results; this class only mirrors that state into the
 * existing engine objects (Net.remotes, ZoneController, SquadModel ...). Bots arrive flagged (isBot) and are never shown as players.
 */
public final class ServerNet {
	private ServerNet() {}

	public static final String PREFS = "net";
	public static final String DEFAULT_ADDR = ServerConfig.AUTH_WS_URL;

	// ------------------------------------------------------------------ public state (read by screens)
	public static final class RoomMember { public String uid = "", name = ""; public int team; public boolean ready, host, isMe; }
	public static final class RoomInfo {
		public String id = "", mode = "br", map = "island", host = "";
		public int teamSize = 4, maxPlayers = 50;
		public boolean priv, hasPassword;
		public final ArrayList<RoomMember> members = new ArrayList<RoomMember>();
	}
	public static final class Friend { public String uid = "", name = ""; public boolean online, inMatch; public String room; }
	public static final class Invite { public String from = "", name = "", room = ""; }
	public static final class RoomRow { public String id = "", mode = "", map = ""; public int teamSize, players, maxPlayers; public boolean hasPassword; }
	public static final class Result {
		public String id = "", name = ""; public int team, placement, kills, damage, surviveSec; public boolean isBot;
	}
	public static final class EconomyState {
		public int coins, diamonds, missionProgress, missionTarget = 1, missionRewardCoins = 150;
		public boolean dailyAvailable, missionClaimed;
		public final ArrayList<String> transactions = new ArrayList<String>();
	}
	public static volatile EconomyState economy = new EconomyState();
	public static final class MatchEnd {
		public int winnerTeam = -1, durationSec; public String mode = ""; public boolean iWon; public int myPlacement;
		public final ArrayList<Result> results = new ArrayList<Result>();
	}

	public interface Listener {
		void onChanged();                       // room / friends / matchmaking state changed
		void onToast(String msg);
		void onMatchStart();                    // launch GameActivity
	}

	public static volatile RoomInfo room;       // null = not in a room
	public static final ArrayList<Friend> friends = new ArrayList<Friend>();
	public static final ArrayList<Friend> pending = new ArrayList<Friend>();
	public static final ArrayList<Friend> searchResults = new ArrayList<Friend>();
	public static final ArrayList<Invite> invites = new ArrayList<Invite>();
	public static final ArrayList<RoomRow> roomList = new ArrayList<RoomRow>();
	public static volatile boolean queued;
	public static volatile String queueMode = "";
	public static volatile int queueWaiting, queueNeeded, queueEta;
	public static volatile String lastError = "";
	public static volatile MatchEnd lastResult;
	public static Listener listener;
    public interface AuthStateListener {
        void onAuthenticationExpired();
    }
    private static volatile AuthStateListener authStateListener;
    public static void setAuthStateListener(AuthStateListener l) {
        authStateListener = l;
    }

	// ------------------------------------------------------------------ internals
	private static GameClient client;
	private static SharedPreferences sp;
	private static String uid = "";
	private static final Object lock = new Object();

	// match state
	private static volatile boolean inMatch;
	private static String myId = "";
	private static int myTeam = -1;
	private static int mySeq;
	private static float sendAcc, pingAcc;
	private static Map<String, Object> pendingStart;
	private static Map<String, Object> lastZone;
	private static final HashMap<String, NetPlayer> byId = new HashMap<String, NetPlayer>();
	public static final SquadModel squad = new SquadModel();
	public static final InputReconciler reconciler = new InputReconciler();
	public static final GunfireIndicators gunfire = new GunfireIndicators();
	public static int aliveTotal, aliveTeams, realPlayers, botCount;
	public static String serverPhase = "plane";
	public static float lastHitFlash;
	public static boolean pendingCorrection;
	/** true while the server says the local player is knocked down (crawl only, cannot shoot). */
	public static volatile boolean iAmKnocked;
	public static final float[] correctionPos = new float[3];
	private static boolean correctionIsJump;
	// stats fed to the HUD
	public static int serverAmmo = -1, serverReserve;
	public static float healT;

	// ------------------------------------------------------------------ setup
	public static void init(Context c) {
		if (client != null) return;
		sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		Net.init(c);                      // creates the persistent uid + name shared with the legacy mode
		uid = Net.uid;
		client = new GameClient(uid, Net.name, new GameClient.Store() {
			@Override public String get(String key) { return "token".equals(key) ? SessionManager.token(c) : sp.getString("srv_" + key, ""); }
			@Override public void put(String key, String value) {
                if ("token".equals(key)) { if (value == null || value.length() == 0) SessionManager.clear(c); }
                else sp.edit().putString("srv_" + key, value).apply();
            }
		});
        client.setStateListener((state, info) -> {
            if ("auth_required".equals(info)) {
                AuthStateListener l = authStateListener;
                if (l != null) { try { l.onAuthenticationExpired(); } catch (RuntimeException ignored) {} }
            }
        });
	}

	/** True only when the player typed their own server address in Settings; otherwise the original Firebase online mode is used. */
	public static boolean isConfigured() {
		if (sp == null) return false;
		String a = sp.getString("server_addr", "");
		return a != null && (a.startsWith("wss://") || a.startsWith("ws://") || a.matches("^[^/ :]+:[0-9]+$"));
	}

	public static String serverAddress() {
		String a = sp == null ? DEFAULT_ADDR : sp.getString("server_addr", DEFAULT_ADDR);
		return a == null || a.trim().length() == 0 ? DEFAULT_ADDR : a.trim();
	}

	public static void setServerAddress(String addr) {
		if (sp != null) sp.edit().putString("server_addr", addr == null ? "" : addr.trim()).apply();
		if (client != null) { client.disconnect(); connect(); }
	}

	public static void setName(String n) { if (client != null && n != null) client.setName(n); }

	public static void connect() {
		if (client == null || !isConfigured()) return;
		String a = serverAddress();
		client.setName(Net.name);
		try { client.connectAddress(a); }
		catch (IllegalArgumentException e) { toast("Invalid server address: " + e.getMessage()); }
	}

	public static void disconnect() { if (client != null) client.disconnect(); }
	public static synchronized void resetForLogout() {
		if (client != null) client.disconnect();
		client = null; sp = null; uid = ""; room = null; queued = false; authStateListener = null;
		economy = new EconomyState();
		friends.clear(); pending.clear(); searchResults.clear(); invites.clear(); roomList.clear();
	}
	public static boolean isOnline() { return client != null && client.isConnected(); }
	public static int stateCode() { return client == null ? GameClient.DISCONNECTED : client.state(); }
	public static int rtt() { return client == null ? 0 : client.rttMs(); }
	public static String uid() { return uid; }
	public static boolean inServerMatch() { return inMatch; }
	public static String myId() { return myId; }
	public static int myTeam() { return myTeam; }
	/** Number of real players on my team in the running server match (bots are never counted as teammates). */
	public static int myTeamSize() {
		if (pendingStart == null) return 1;
		int n = 0;
		for (Object o : lst(pendingStart, "players")) {
			Map<String, Object> r = asObj(o);
			if (r != null && (int) Json.num(r, "team", -1) == myTeam && !Json.bool(r, "isBot", false)) n++;
		}
		return Math.max(1, n);
	}
	public static double renderTs() { return client == null ? 0 : client.serverNow() - 100.0; }
	public static long serverNow() { return client == null ? System.currentTimeMillis() : client.serverNow(); }

	// ------------------------------------------------------------------ requests
	private static boolean send(Map<String, Object> m) { return client != null && client.send(m); }
	private static Map<String, Object> msg(String t, Object... kv) {
		Map<String, Object> m = Json.obj("t", t);
		for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
		return m;
	}

	public static void createRoom(String mode, int teamSize, int maxPlayers, boolean priv, String password, String map) {
		Map<String, Object> m = msg("room_create", "mode", mode, "teamSize", teamSize, "maxPlayers", maxPlayers, "priv", priv, "map", map);
		if (password != null && password.length() > 0) m.put("password", password);
		send(m);
	}
	public static void joinRoom(String id, String password) {
		Map<String, Object> m = msg("room_join", "room", id == null ? "" : id.trim().toUpperCase());
		if (password != null && password.length() > 0) m.put("password", password);
		send(m);
	}
	public static void leaveRoom() { send(msg("room_leave")); room = null; fire(); }
	public static void setReady(boolean r) { send(msg("room_ready", "ready", r)); }
	public static void setTeam(int t) { send(msg("room_team", "team", t)); }
	public static void configRoom(String mode, int teamSize, String map, boolean priv, String password) {
		Map<String, Object> m = msg("room_config");
		if (mode != null) m.put("mode", mode);
		if (teamSize > 0) m.put("teamSize", teamSize);
		if (map != null) m.put("map", map);
		m.put("priv", priv);
		if (password != null) m.put("password", password);
		send(m);
	}
	public static void kick(String id) { send(msg("room_kick", "uid", id)); }
	public static void startRoomMatch() { send(msg("room_start")); }
	public static void refreshRooms() { send(msg("room_list")); }
	public static void joinQueue(String mode, int teamSize) { send(msg("mm_join", "mode", mode, "teamSize", teamSize)); }
	public static void cancelQueue() { send(msg("mm_cancel")); }
	public static void refreshFriends() { send(msg("friends_list")); }
	public static void searchFriends(String query) { send(msg("friend_search", "query", query == null ? "" : query.trim())); }
	public static void refreshEconomy() { send(msg("economy_get")); }
	public static void claimDailyReward() { send(msg("economy_daily_claim")); }
	public static void claimMissionReward() { send(msg("economy_mission_claim")); }
	public static void addFriend(String id) { send(msg("friend_add", "uid", id)); }
	public static void acceptFriend(String id) { send(msg("friend_accept", "uid", id)); }
	public static void declineFriend(String id) { send(msg("friend_decline", "uid", id)); }
	public static void removeFriend(String id) { send(msg("friend_remove", "uid", id)); }
	public static void invite(String id) { send(msg("invite", "uid", id)); }
	public static void leaveMatch() { send(msg("leave_match")); }
	public static void ping(float x, float z, int kind) { send(msg("marker", "x", x, "z", z, "kind", kind)); }
	public static void sendVoice(int seq, String base64) { send(msg("voice", "seq", seq, "data", base64)); }
	public static void removeInvite(Invite i) { synchronized (lock) { invites.remove(i); } }

	private static void fire() { Listener l = listener; if (l != null) l.onChanged(); }
	private static void toast(String s) { Listener l = listener; if (l != null) l.onToast(s); }

	// ------------------------------------------------------------------ lobby pump (UI thread)
	private static final GameClient.Handler LOBBY = new GameClient.Handler() {
		@Override public void onMessage(Map<String, Object> m) { handle(m, null); }
	};

	/** Call from a UI timer while a lobby screen is visible. */
	public static void pump() {
		if (client == null || inMatch) return;
		synchronized (lock) { client.pump(LOBBY); }
	}

	// ------------------------------------------------------------------ message handling
	private static void handle(Map<String, Object> m, GameEngine eng) {
		String t = Json.str(m, "t", "");
		if (t.equals("welcome")) { refreshEconomy(); return; }
		if (t.equals("economy_state")) {
			EconomyState e = new EconomyState();
			e.coins = (int) Json.num(m, "coins", 0); e.diamonds = (int) Json.num(m, "diamonds", 0);
			e.dailyAvailable = Json.bool(m, "dailyAvailable", false);
			Map<String, Object> mission = asObj(m.get("mission"));
			if (mission != null) {
				e.missionProgress = (int) Json.num(mission, "progress", 0);
				e.missionTarget = (int) Json.num(mission, "target", 1);
				e.missionClaimed = Json.bool(mission, "claimed", false);
				e.missionRewardCoins = (int) Json.num(mission, "rewardCoins", 150);
			}
			for (Object item : lst(m, "transactions")) {
				Map<String, Object> tx = asObj(item); if (tx == null) continue;
				e.transactions.add(Json.str(tx, "reason", "transaction") + "  " +
					(Json.num(tx, "amount", 0) >= 0 ? "+" : "") + (int) Json.num(tx, "amount", 0) +
					" " + Json.str(tx, "currency", "coins"));
			}
			economy = e; fire(); return;
		}
		if (t.equals("economy_error")) { toast(Json.str(m, "message", Json.str(m, "code", "Economy request failed"))); return; }
		if (t.equals("error")) { lastError = Json.str(m, "msg", Json.str(m, "code", "error")); toast(lastError); return; }
		if (t.equals("room_state")) { room = parseRoom(m); fire(); return; }
		if (t.equals("room_closed")) { room = null; queued = false; toast("Room closed: " + Json.str(m, "reason", "")); fire(); return; }
		if (t.equals("rooms")) {
			roomList.clear();
			for (Object o : lst(m, "rooms")) {
				Map<String, Object> r = asObj(o); if (r == null) continue;
				RoomRow x = new RoomRow();
				x.id = Json.str(r, "room", ""); x.mode = Json.str(r, "mode", ""); x.map = Json.str(r, "map", "");
				x.teamSize = (int) Json.num(r, "teamSize", 4); x.players = (int) Json.num(r, "players", 0);
				x.maxPlayers = (int) Json.num(r, "maxPlayers", 0); x.hasPassword = Json.bool(r, "hasPassword", false);
				roomList.add(x);
			}
			fire(); return;
		}
		if (t.equals("friend_search_results")) {
			searchResults.clear();
			for (Object o : lst(m, "results")) {
				Map<String, Object> r = asObj(o); if (r == null) continue;
				Friend f = new Friend(); f.uid = Json.str(r, "uid", ""); f.name = Json.str(r, "name", f.uid);
				searchResults.add(f);
			}
			fire(); return;
		}
		if (t.equals("friends")) {
			friends.clear(); pending.clear();
			for (Object o : lst(m, "friends")) { Map<String, Object> r = asObj(o); if (r == null) continue;
				Friend f = new Friend(); f.uid = Json.str(r, "uid", ""); f.name = Json.str(r, "name", f.uid);
				f.online = Json.bool(r, "online", false); f.inMatch = Json.bool(r, "inMatch", false); f.room = Json.str(r, "room", null);
				friends.add(f); }
			for (Object o : lst(m, "pending")) { Map<String, Object> r = asObj(o); if (r == null) continue;
				Friend f = new Friend(); f.uid = Json.str(r, "uid", ""); f.name = Json.str(r, "name", f.uid); pending.add(f); }
			fire(); return;
		}
		if (t.equals("friend_request")) { toast(Json.str(m, "name", "Someone") + " sent you a friend request"); refreshFriends(); return; }
		if (t.equals("invite")) {
			Invite i = new Invite(); i.from = Json.str(m, "from", ""); i.name = Json.str(m, "name", i.from); i.room = Json.str(m, "room", "");
			synchronized (lock) { invites.add(i); }
			toast(i.name + " invited you to room " + i.room); fire(); return;
		}
		if (t.equals("mm_status")) {
			queued = Json.bool(m, "queued", false); queueMode = Json.str(m, "mode", ""); queueWaiting = (int) Json.num(m, "waiting", 0);
			queueNeeded = (int) Json.num(m, "needed", 0); queueEta = (int) Json.num(m, "eta", 0); fire(); return;
		}
		if (t.equals("match_start")) {
			pendingStart = m; queued = false;
			if (eng == null && !inMatch) { beginMatch(m); Listener l = listener; if (l != null) l.onMatchStart(); }
			return;
		}
		if (t.equals("match_end")) { parseEnd(m); if (eng != null) applyEnd(eng); return; }
		if (t.equals("zone")) { lastZone = m; if (eng != null) applyZone(eng, m); return; }
		if (!inMatch) return;
		if (eng == null) return;      // in-match events before the engine is attached are dropped (state is re-sent by snapshots)
		matchEvent(t, m, eng);
	}

	private static RoomInfo parseRoom(Map<String, Object> m) {
		RoomInfo r = new RoomInfo();
		r.id = Json.str(m, "room", ""); r.mode = Json.str(m, "mode", "br"); r.map = Json.str(m, "map", "island");
		r.teamSize = (int) Json.num(m, "teamSize", 4); r.maxPlayers = (int) Json.num(m, "maxPlayers", 50);
		r.priv = Json.bool(m, "priv", false); r.hasPassword = Json.bool(m, "hasPassword", false); r.host = Json.str(m, "host", "");
		for (Object o : lst(m, "members")) {
			Map<String, Object> x = asObj(o); if (x == null) continue;
			RoomMember rm = new RoomMember();
			rm.uid = Json.str(x, "uid", ""); rm.name = Json.str(x, "name", rm.uid); rm.team = (int) Json.num(x, "team", 0);
			rm.ready = Json.bool(x, "ready", false); rm.host = Json.bool(x, "host", false); rm.isMe = rm.uid.equals(uid);
			r.members.add(rm);
		}
		return r;
	}

	public static boolean amHost() { RoomInfo r = room; return r != null && uid.equals(r.host); }

	// ------------------------------------------------------------------ match lifecycle
	private static void beginMatch(Map<String, Object> m) {
		inMatch = true; iAmKnocked = false; lastResult = null; sendAcc = 0f; mySeq = 0; correctionIsJump = false; pendingCorrection = false;
		Map<String, Object> you = asObj(m.get("you"));
		myId = you == null ? uid : Json.str(you, "id", uid);
		myTeam = you == null ? 0 : (int) Json.num(you, "team", 0);
		synchronized (Net.remotes) {
			Net.remotes.clear(); byId.clear();
			squad.reset(myId, myTeam);
			realPlayers = 0; botCount = 0;
			for (Object o : lst(m, "players")) {
				Map<String, Object> p = asObj(o); if (p == null) continue;
				String id = Json.str(p, "id", "");
				boolean bot = Json.bool(p, "isBot", false);
				if (bot) botCount++; else realPlayers++;
				int team = (int) Json.num(p, "team", -1);
				if (team == myTeam) squad.add(id, Json.str(p, "name", id), (int) Json.num(p, "slot", 0)).isMe = id.equals(myId);
				if (id.equals(myId)) continue;
				NetPlayer n = new NetPlayer();
				n.id = id; n.name = Json.str(p, "name", id); n.team = team; n.isBot = bot; n.interp = new SnapshotInterpolator();
				n.lastSeen = System.currentTimeMillis();
				Net.remotes.add(n); byId.put(id, n);
			}
		}
		Net.myTeam = myTeam; Net.active = true; Net.inMatch = true; Net.matchId = Json.str(m, "matchId", "");
		Net.room = room == null ? "" : room.id; Net.uid = uid;
		aliveTotal = realPlayers + botCount; aliveTeams = 0;
		Map<String, Object> z = asObj(m.get("zone")); if (z != null) lastZone = withT(z);
		serverAmmo = -1;
	}

	private static Map<String, Object> withT(Map<String, Object> z) { z.put("t", "zone"); return z; }

	/** The seed/ids of the match the server told us about (GameActivity reads it to start the engine). */
	public static Map<String, Object> startMessage() { return pendingStart; }
	public static int serverRealPlayers() { return realPlayers; }
	public static int serverBots() { return botCount; }

	/** Loot rows of the running match (server generated). */
	public static List<Object> startLoot() { return pendingStart == null ? null : lst(pendingStart, "loot"); }

	/** Makes the cosmetic airplane fly the server's route so the jump point matches. */
	public static void alignPlane(Airplane a) {
		Map<String, Object> pl = pendingStart == null ? null : asObj(pendingStart.get("plane"));
		if (pl == null) return;
		a.x = (float) Json.num(pl, "sx", a.x); a.z = (float) Json.num(pl, "sz", a.z);
		a.dirX = (float) Json.num(pl, "dx", a.dirX); a.dirZ = (float) Json.num(pl, "dz", a.dirZ);
		a.y = (float) Json.num(pl, "alt", a.y);
	}

	/** Called by GameActivity once the engine exists: puts the player in the server-chosen start. */
	public static void attach(GameEngine eng) {
		eng.matchSeed = pendingStart == null ? 0L : (long) Json.num(pendingStart, "seed", 0);
		if (lastZone != null) applyZone(eng, lastZone);
	}

	// ------------------------------------------------------------------ per frame (GL thread)
	private static GameEngine curEng;
	private static final GameClient.Handler MATCH = new GameClient.Handler() {
		@Override public void onMessage(Map<String, Object> m) { handle(m, curEng); }
	};

	public static void tick(float dt, GameEngine eng) {
		if (!inMatch || client == null) return;
		curEng = eng;
		synchronized (lock) { client.pump(MATCH); }
		reconciler.smooth(dt);
		gunfire.update(dt);
		squad.update(dt);
		if (healT > 0f) healT -= dt;
		if (lastHitFlash > 0f) lastHitFlash -= dt;
		Player p = eng.player;
		squad.updateDistances(p.x, p.z);
		// keep honest alive counts for the HUD
		synchronized (Net.remotes) {
			for (int i = 0; i < Net.remotes.size(); i++) Net.remotes.get(i).step(dt);
		}
		if (pendingCorrection) {
			pendingCorrection = false;
			p.x = correctionPos[0]; p.y = correctionPos[1]; p.z = correctionPos[2];
			if (correctionIsJump) { correctionIsJump = false; }
		}
		sendAcc += dt;
		MatchPhase ph = eng.match.phase;
		if (sendAcc >= 0.05f && p.alive && (ph == MatchPhase.PLAYING || ph == MatchPhase.FREEFALL || ph == MatchPhase.PARACHUTE)) {
			sendAcc = 0f;
			int seq = reconciler.nextSeq();
			int st = p.swimming ? 3 : (p.crouching ? 1 : 0);
			if (ph == MatchPhase.FREEFALL) st = 4; else if (ph == MatchPhase.PARACHUTE) st = 5;
			int fl = (eng.runOn ? 1 : 0) | (eng.aiming ? 2 : 0) | (eng.fireHeld ? 4 : 0) | (p.reloading ? 8 : 0) | (p.onGround ? 16 : 0);
			Weapon w = eng.weapons.current();
			Map<String, Object> in = msg("input", "seq", seq, "x", r2(p.x), "y", r2(p.y), "z", r2(p.z),
				"yaw", r1((float) Math.toDegrees(p.yaw)), "pitch", r1((float) Math.toDegrees(p.aimPitchRad)),
				"vx", r1(p.horizontalVX), "vz", r1(p.horizontalVZ), "st", st, "fl", fl, "wp", w == null ? -1 : w.type);
			if (send(in)) reconciler.record(seq, p.x, p.y, p.z);
		}
	}

	private static double r1(float v) { return Math.round(v * 10f) / 10.0; }
	private static double r2(float v) { return Math.round(v * 100f) / 100.0; }

	/** Local fire request: the server validates and applies damage; the reply drives hit markers. */
	public static void sendFire(GameEngine eng, float ox, float oy, float oz, float dx, float dy, float dz, int wp) {
		if (!inMatch) return;
		Map<String, Object> f = msg("fire", "seq", ++mySeq, "ox", r2(ox), "oy", r2(oy), "oz", r2(oz),
			"dx", Math.round(dx * 10000f) / 10000.0, "dy", Math.round(dy * 10000f) / 10000.0, "dz", Math.round(dz * 10000f) / 10000.0,
			"wp", wp, "ct", client.serverNow() - 100L);   // the player sees others ~100 ms in the past (interpolation delay)
		send(f);
	}
	public static void sendReload() { if (inMatch) send(msg("reload")); }
	public static void sendSwitch(int wp) { if (inMatch) send(msg("switch", "wp", wp)); }
	public static void sendPickup(int lootId) { if (inMatch) send(msg("pickup", "lootId", lootId)); }
	public static void sendDrop() { if (inMatch) send(msg("drop")); }
	public static void sendHeal() { if (inMatch) send(msg("heal")); }
	public static void sendJump() { if (inMatch) send(msg("jump")); }
	public static void sendMelee(float dx, float dz) { if (inMatch) send(msg("melee", "dx", dx, "dz", dz)); }
	public static void sendRevive(String id, boolean start) { if (inMatch) send(msg("revive", "uid", id, "start", start)); }

	// ------------------------------------------------------------------ in-match events
	private static void matchEvent(String t, Map<String, Object> m, GameEngine eng) {
		Player p = eng.player;
		if (t.equals("snap")) { applySnap(m, eng); return; }
		if (t.equals("correct")) {
			float[] out = new float[3];
			reconciler.onCorrect((float) Json.num(m, "x", p.x), (float) Json.num(m, "y", p.y), (float) Json.num(m, "z", p.z), out);
			correctionPos[0] = out[0]; correctionPos[1] = out[1]; correctionPos[2] = out[2]; pendingCorrection = true;
			return;
		}
		if (t.equals("hit")) {
			boolean head = Json.bool(m, "head", false);
			GameRenderer r = eng.getRenderer(); if (r != null) r.flashHitMarker(head);
			eng.sound.play(Json.bool(m, "kill", false) ? "kill" : "hit");
			if (Json.bool(m, "kill", false)) p.kills++;
			return;
		}
		if (t.equals("damaged")) {
			float dmg = (float) Json.num(m, "dmg", 0);
			p.health = (float) Json.num(m, "hp", p.health);
			eng.healing.onDamaged(dmg);
			eng.cameraShake.hit(Math.min(1.5f, dmg / 20f));
			GameRenderer r = eng.getRenderer(); if (r != null) r.flashDamage();
			eng.sound.play("hurt");
			lastHitFlash = 0.4f;
			Net.pendingDamageDir = (int) Json.num(m, "dir", 0);
			return;
		}
		if (t.equals("shot")) {
			String id = Json.str(m, "id", "");
			if (id.equals(myId)) return;
			float sx = (float) Json.num(m, "x", 0), sy = (float) Json.num(m, "y", 0), sz = (float) Json.num(m, "z", 0);
			int wp = (int) Json.num(m, "wp", 0);
			// the audio system (distance, stereo pan, occlusion) plays it; indicators only appear within 200 m
			eng.sound.playAt(wp, sx, sy, sz, eng);
			gunfire.onShot(id, p.x, p.y, p.z, sx, sy, sz);
			return;
		}
		if (t.equals("kill")) {
			String killer = Json.str(m, "killerName", ""), victim = Json.str(m, "victimName", "");
			String line = killer.length() == 0 ? victim + " was eliminated" : killer + " eliminated " + victim
				+ (Json.bool(m, "head", false) ? " (headshot)" : "");
			Net.announce(line);
			String vid = Json.str(m, "victim", "");
			NetPlayer n = byId.get(vid); if (n != null) { n.alive = false; n.hp = 0; }
			squad.onKill(vid);
			if (vid.equals(myId)) { p.alive = false; p.health = 0f; }
			return;
		}
		if (t.equals("knock")) {
			String v = Json.str(m, "victim", ""); squad.onKnock(v);
			NetPlayer n = byId.get(v); if (n != null) n.knocked = true;
			return;
		}
		if (t.equals("revived")) {
			String v = Json.str(m, "uid", ""); squad.onRevive(v);
			NetPlayer n = byId.get(v); if (n != null) { n.knocked = false; n.alive = true; }
			return;
		}
		if (t.equals("marker")) { squad.addMarker(Json.str(m, "by", ""), (float) Json.num(m, "x", 0), (float) Json.num(m, "z", 0), (int) Json.num(m, "kind", 0)); return; }
		if (t.equals("ammo")) { serverAmmo = (int) Json.num(m, "mag", 0); serverReserve = (int) Json.num(m, "reserve", 0); eng.weapons.applyServerAmmo(serverAmmo, serverReserve); return; }
		if (t.equals("healed")) { p.health = (float) Json.num(m, "hp", p.health); return; }
		if (t.equals("healing")) { healT = (float) Json.num(m, "t4", 0); return; }
		if (t.equals("loot_taken")) { eng.loot.removeServerLoot((int) Json.num(m, "lootId", -1)); return; }
		if (t.equals("loot_add")) { Map<String, Object> l = asObj(m.get("loot")); if (l != null) eng.loot.addServerLoot(l); return; }
		if (t.equals("inventory")) { eng.loot.applyServerInventory(eng, asObj(m.get("inv"))); return; }
		if (t.equals("voice")) { VoiceChat.onFrame(Json.str(m, "from", ""), (int) Json.num(m, "seq", 0), Json.str(m, "data", "")); return; }
	}

	private static void applySnap(Map<String, Object> m, GameEngine eng) {
		double ts = Json.num(m, "ts", serverNow());
		aliveTotal = (int) Json.num(m, "alive", aliveTotal); aliveTeams = (int) Json.num(m, "aliveTeams", aliveTeams);
		serverPhase = Json.str(m, "phase", serverPhase);
		Player p = eng.player;
		float hp = (float) Json.num(m, "hp", p.health);
		if (p.alive) p.health = hp;
		p.armor = (float) Json.num(m, "armor", p.armor);
		eng.zone.applyServerCircle((float) Json.num(m, "zcx", 0), (float) Json.num(m, "zcz", 0), (float) Json.num(m, "zr", 1400));
		synchronized (Net.remotes) {
			for (Object o : lst(m, "players")) {
				List<Object> row = aList(o);
				if (row == null || row.size() < 10) continue;
				String id = String.valueOf(row.get(0));
				float x = num(row.get(1)), y = num(row.get(2)), z = num(row.get(3));
				float yaw = num(row.get(4)), pitch = num(row.get(5));
				int rhp = (int) num(row.get(6)), st = (int) num(row.get(7)), fl = (int) num(row.get(8)), wp = (int) num(row.get(9));
				if (id.equals(myId)) { squad.onSnapshotRow(id, x, y, z, rhp, st); iAmKnocked = st == 6; continue; }
				NetPlayer n = byId.get(id);
				if (n == null) continue;
				n.interp.push(ts, x, y, z, yaw);
				n.tx = x; n.ty = y; n.tz = z; n.tyaw = yaw; n.pitch = (float) Math.toRadians(pitch);
				n.hp = rhp; n.alive = st != 8; n.knocked = st == 6; n.crouch = st == 1 || st == 2; n.swim = st == 3;
				n.onGround = (fl & 16) != 0; n.aiming = (fl & 2) != 0; n.firing = (fl & 4) != 0; n.weapon = wp < 0 ? Weapon.M416 : wp;
				n.lastSeen = System.currentTimeMillis();
				if (n.team == myTeam) squad.onSnapshotRow(id, x, y, z, rhp, st);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Object> lst(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof List ? (List<Object>) v : new ArrayList<Object>(); }
	@SuppressWarnings("unchecked")
	private static Map<String, Object> asObj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
	@SuppressWarnings("unchecked")
	private static List<Object> aList(Object o) { return o instanceof List ? (List<Object>) o : null; }
	private static float num(Object o) { return o instanceof Number ? ((Number) o).floatValue() : 0f; }

	private static void applyZone(GameEngine eng, Map<String, Object> z) {
		eng.zone.applyServerZone((float) Json.num(z, "cx", 0), (float) Json.num(z, "cz", 0), (float) Json.num(z, "r", 1400),
			(float) Json.num(z, "nx", 0), (float) Json.num(z, "nz", 0), (float) Json.num(z, "nr", 1400),
			(int) Json.num(z, "phase", 0), (float) Json.num(z, "tShrinkStart", 0), (float) Json.num(z, "tShrinkEnd", 0),
			(float) Json.num(z, "dps", 0), (float) Json.num(z, "now", 0));
	}

	private static void parseEnd(Map<String, Object> m) {
		MatchEnd e = new MatchEnd();
		e.winnerTeam = (int) Json.num(m, "winnerTeam", -1); e.durationSec = (int) Json.num(m, "durationSec", 0); e.mode = Json.str(m, "mode", "");
		for (Object o : lst(m, "results")) {
			Map<String, Object> r = asObj(o); if (r == null) continue;
			Result x = new Result(); x.id = Json.str(r, "id", ""); x.name = Json.str(r, "name", ""); x.team = (int) Json.num(r, "team", 0);
			x.placement = (int) Json.num(r, "placement", 0); x.kills = (int) Json.num(r, "kills", 0); x.damage = (int) Json.num(r, "damage", 0);
			x.surviveSec = (int) Json.num(r, "surviveSec", 0); x.isBot = Json.bool(r, "isBot", false);
			if (x.id.equals(myId)) e.myPlacement = x.placement;
			e.results.add(x);
		}
		e.iWon = e.winnerTeam >= 0 && e.winnerTeam == myTeam;
		lastResult = e;
	}

	private static void applyEnd(GameEngine eng) {
		MatchEnd e = lastResult;
		if (e == null) return;
		if (e.iWon && eng.player.alive) eng.match.phase = MatchPhase.WIN;
		else if (!eng.player.alive || !e.iWon) eng.match.phase = MatchPhase.DEAD;
	}

	/** Leaves server-match mode (back in the lobby). */
	public static void endMatchLocal() {
		if (inMatch) leaveMatchIfAlive();
		inMatch = false; curEng = null;
		Net.inMatch = false; Net.active = false;
		synchronized (Net.remotes) { Net.remotes.clear(); byId.clear(); }
		pendingStart = null;
	}

	private static void leaveMatchIfAlive() { if (lastResult == null) leaveMatch(); }

	public static boolean matchFinished() { return lastResult != null; }
}
