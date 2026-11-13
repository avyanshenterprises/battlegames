import com.avyansh.battleroyale.*;
import java.util.*;

/** Off-device tests for the pure-Java client logic and the real GameClient talking to the real Node server. */
public class ClientTests {
	static int pass, fail;
	static void check(boolean ok, String name) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + name); } }
	static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }

	public static void main(String[] args) throws Exception {
		json(); interpolator(); reconciler(); aim(); camera(); audio(); indicators(); minimap(); squad();
		if (args.length > 0) live(Integer.parseInt(args[0]));
		System.out.println("client tests: pass=" + pass + " fail=" + fail);
		System.exit(fail == 0 ? 0 : 1);
	}

	static void json() {
		Map<String, Object> m = Json.parseObject("{\"t\":\"snap\",\"a\":[1,2.5,[true,null]],\"s\":\"x\\\"y\\n\\u00e9\",\"n\":-3e2}");
		check("snap".equals(Json.str(m, "t", "")), "json str");
		check(Json.list(m, "a").size() == 3 && Json.dbl(Json.list(m, "a").get(1)) == 2.5, "json list");
		check("x\"y\né".equals(Json.str(m, "s", "")), "json escapes");
		check(Json.num(m, "n", 0) == -300, "json exp");
		String w = Json.write(Json.obj("t", "input", "x", 1.5f, "i", 3, "b", true, "z", null, "q", "a\"b"));
		Map<String, Object> back = Json.parseObject(w);
		check(Json.f(back, "x", 0) == 1.5f && Json.i(back, "i", 0) == 3 && Json.bool(back, "b", false) && "a\"b".equals(Json.str(back, "q", "")), "json roundtrip " + w);
		boolean threw = false; try { Json.parse("{bad"); } catch (RuntimeException e) { threw = true; }
		check(threw, "json rejects garbage");
		check(Json.write(Json.obj("v", Double.NaN)).equals("{\"v\":0}"), "json NaN -> 0");
	}

	static void interpolator() {
		SnapshotInterpolator s = new SnapshotInterpolator(); float[] o = new float[4];
		check(!s.sample(0, o), "empty");
		s.push(1000, 0, 0, 0, 350); s.push(1100, 10, 0, 0, 10);
		check(s.sample(1050, o) && near(o[0], 5, 1e-4), "midpoint x");
		check(near(o[3], 0, 1e-3) || near(o[3], 360, 1e-3), "yaw wraps the short way: " + o[3]);
		s.sample(1200, o); check(near(o[0], 20, 1e-3), "extrapolates 100ms at 0.1m/ms: " + o[0]);
		s.sample(5000, o); check(near(o[0], 25, 1e-3), "extrapolation capped at 150ms: " + o[0]);
		s.push(1050, 99, 0, 0, 0); check(s.size() == 2, "out-of-order dropped");
		s.push(1200, 500, 0, 0, 0); s.sample(1150, o); check(o[0] == 10 || o[0] == 500, "teleport doesn't slide: " + o[0]);
		for (int i = 0; i < 100; i++) s.push(2000 + i * 50, i, 0, 0, 0);
		check(s.size() == 24, "ring buffer bounded");
	}

	static void reconciler() {
		InputReconciler r = new InputReconciler(); float[] o = new float[3];
		for (int i = 1; i <= 10; i++) r.record(r.nextSeq(), i, 0, 0);
		check(!r.onServerState(5, 5.2f, 0, 0, o), "small error ignored");
		check(r.errX < 0 || near(r.errX, -0.2, 1e-3), "residual recorded");
		check(r.onServerState(5, 2f, 0, 0, o) && near(o[0], 7, 1e-3), "big error snaps, replays newest: " + o[0]);
		r.onCorrect(1, 2, 3, o); check(o[1] == 2, "correct resets");
		check(!r.onServerState(9, 0, 0, 0, o), "history cleared after correct");
	}

	static void aim() {
		AimAssist a = new AimAssist(); float[] out = new float[3];
		float[] t = { 0, 0, -30 };  int[] ids = { 1 };
		// looking 4 deg right of target
		int k = a.update(0.016f, 0, 0, 0, 4f, 0f, 1, t, ids, false, 0f, out);
		check(k == 0 && out[0] < 0 && Math.abs(out[0]) < 1f, "pulls toward target slowly: " + out[0]);
		check(out[2] < 1f && out[2] >= a.frictionMin, "friction applies: " + out[2]);
		// outside cone
		check(a.update(0.016f, 0, 0, 0, 40f, 0f, 1, t, ids, false, 0f, out) == -1 && out[0] == 0 && out[2] == 1f, "outside cone: no assist");
		// never locks: simulate 5 seconds, error never below residual
		float yaw = 5f; float minErr = 99;
		for (int i = 0; i < 300; i++) { a.update(0.016f, 0, 0, 0, yaw, 0f, 1, t, ids, true, 0f, out); yaw += out[0]; minErr = Math.min(minErr, Math.abs(yaw)); }
		check(minErr >= a.residualDeg - 1e-3, "never perfect: min err " + minErr);
		check(Math.abs(yaw) < 1.0f, "converges near target: " + yaw);
		// pull speed cap
		a.strength = 1f; a.update(1f, 0, 0, 0, 6f, 0f, 1, t, ids, false, 0f, out);
		check(Math.abs(out[0]) <= a.maxPullDegPerSec + 1e-3, "pull capped");
		// player override reduces pull
		a.strength = 0.5f; a.update(0.016f, 0, 0, 0, 4f, 0f, 1, t, ids, false, 0f, out); float free = Math.abs(out[0]);
		a.update(0.016f, 0, 0, 0, 4f, 0f, 1, t, ids, false, 5f, out); check(Math.abs(out[0]) < free * 0.5f, "user input overrides");
		a.enabled = false; check(a.update(0.016f, 0, 0, 0, 4f, 0, 1, t, ids, false, 0, out) == -1, "disabled");
		a.enabled = true; check(a.update(0.016f, 0, 0, 0, 0f, 0, 1, new float[]{0,0,-200}, ids, false, 0, out) == -1, "out of range");
	}

	static final CameraRig.World FLAT = new CameraRig.World() {
		public float groundAt(float x, float z) { return 0f; }
		public boolean segmentBlocked(float x0, float y0, float z0, float x1, float y1, float z1) { return false; }
	};
	static void camera() {
		CameraRig c = new CameraRig(); float[] p = new float[3];
		check(c.sensitivity(1f, false) > c.sensitivity(1f, true), "ADS slower than hip");
		check(c.sensitivity(2f, true) > c.sensitivity(4f, true) && c.sensitivity(4f, true) > c.sensitivity(6f, true), "higher zoom => lower sensitivity");
		c.orbit(FLAT, 0.016f, 0, 2f, 0, 0f, 0f, 3.4f, 0f, p);
		check(near(p[2], 3.4, 1e-3) && near(p[1], 2f, 1e-3), "camera behind pivot: z=" + p[2]);
		// wall 1.5m behind (z>1.5 blocked)
		CameraRig.World wall = new CameraRig.World() {
			public float groundAt(float x, float z) { return 0; }
			public boolean segmentBlocked(float x0, float y0, float z0, float x1, float y1, float z1) { return z1 > 1.5f; }
		};
		CameraRig c2 = new CameraRig();
		for (int i = 0; i < 30; i++) c2.orbit(wall, 0.016f, 0, 2f, 0, 0f, 0f, 3.4f, 0f, p);
		check(p[2] < 1.5f, "camera stays in front of wall: " + p[2]);
		// looking steeply up puts camera low: ground clamp
		CameraRig c3 = new CameraRig(); c3.orbit(FLAT, 0.016f, 0, 1f, 0, 0f, 60f, 3.4f, 0f, p);
		check(p[1] >= c3.groundClearance - 1e-3, "never below ground: " + p[1]);
		// eases out slowly after obstacle disappears
		c2.orbit(FLAT, 0.016f, 0, 2f, 0, 0f, 0f, 3.4f, 0f, p); check(p[2] < 3.4f, "eases back out slowly");
	}

	static void audio() {
		PositionalAudio.Result r = new PositionalAudio.Result();
		int ar = Weapon.M416;
		PositionalAudio.compute(0, 0, 0, 0, 0, 0, -50, ar, null, r);        // 50 m straight ahead
		check(r.audible && near(r.distance, 50, 1e-3) && near(r.pan, 0, 1e-3), "ahead: centred");
		float g50 = r.gain;
		PositionalAudio.compute(0, 0, 0, 0, 100, 0, 0, ar, null, r);         // 100 m to the right (east)
		check(r.pan > 0.95f && r.right > r.left, "right source: right louder pan=" + r.pan);
		check(r.gain < g50, "farther is quieter");
		PositionalAudio.compute(0, 0, 0, 0, -100, 0, 0, ar, null, r);
		check(r.pan < -0.95f && r.left > r.right, "left source");
		PositionalAudio.compute(0, 0, 0, 90, 0, 0, -50, ar, null, r);        // listener turned right: source now on the left
		check(r.pan < -0.95f, "listener heading matters: " + r.pan);
		PositionalAudio.compute(0, 0, 0, 0, 0, 0, 100, ar, null, r);          // behind
		check(r.lowpass < 0.7f, "behind is duller");
		// 3D distance uses height
		PositionalAudio.compute(0, 0, 0, 0, 0, 40, -30, ar, null, r); check(near(r.distance, 50, 1e-3), "3D distance");
		// range: pistol quieter/shorter than sniper
		PositionalAudio.compute(0, 0, 0, 0, 0, 0, -300, Weapon.P92, null, r); check(!r.audible, "pistol inaudible at 300m");
        PositionalAudio.compute(0, 0, 0, 0, 0, 0, -150, Weapon.AWM, null, r); check(r.audible, "sniper audible within 200m rolloff");
        PositionalAudio.compute(0, 0, 0, 0, 0, 0, -201, Weapon.AWM, null, r); check(!r.audible, "all gunfire capped beyond 200m");
		// monotonic attenuation
		float prev = 2; boolean mono = true;
		for (int d = 1; d < 200; d += 5) { PositionalAudio.compute(0, 0, 0, 0, 0, 0, -d, ar, null, r); if (r.gain > prev + 1e-6) mono = false; prev = r.gain; }
		check(mono, "gain monotonic with distance");
		// occlusion
		PositionalAudio.Occluder wall = (sx, sy, sz, lx, ly, lz) -> 0f;
		PositionalAudio.compute(0, 0, 0, 0, 0, 0, -50, ar, null, r); float clear = r.gain;
		PositionalAudio.compute(0, 0, 0, 0, 0, 0, -50, ar, wall, r); check(r.gain < clear * 0.4f && r.audible, "occlusion lowers gain: " + clear + " -> " + r.gain);
		// terrain occluder: hill between
		CameraRig.World hill = new CameraRig.World() {
			public float groundAt(float x, float z) { return Math.abs(z + 25) < 8 ? 30f : 0f; }
			public boolean segmentBlocked(float a, float b, float c, float d, float e, float f) { return false; }
		};
		PositionalAudio.TerrainOccluder to = new PositionalAudio.TerrainOccluder(hill);
		check(to.clearness(0, 1, -50, 0, 1, 0) < 0.9f && to.clearness(50, 1, 0, 0, 1, 0) == 1f, "terrain occluder");
	}

	static void indicators() {
		GunfireIndicators g = new GunfireIndicators();
        check(!g.onShot("e", 0, 0, 0, 0, 0, -201), "201 m: no indicator");
        check(g.onShot("e", 0, 0, 0, 0, 0, -199), "199 m: indicator");
		check(near(g.relative(0, 0), 0, 1e-3), "ahead = 0");
		g.onShot("e2", 0, 0, 0, 100, 0, 0); check(near(g.relative(1, 0), 90, 1e-3), "right = +90");
		check(near(g.relative(1, 90), 0, 1e-3), "after turning right it is ahead");
		g.onShot("e", 0, 0, 0, -100, 0, 0); check(g.active(0) && near(g.relative(0, 0), -90, 1e-3), "same shooter refreshes slot");
		for (int i = 0; i < 20; i++) g.onShot("x" + i, 0, 0, 0, 10, 0, 10);
		int act = 0; for (int i = 0; i < GunfireIndicators.MAX; i++) if (g.active(i)) act++;
		check(act == GunfireIndicators.MAX, "pool bounded");
		g.update(3f); act = 0; for (int i = 0; i < GunfireIndicators.MAX; i++) if (g.active(i)) act++;
		check(act == 0, "indicators expire");
	}

	static void minimap() {
		MinimapMath m = new MinimapMath(); m.sizePx = 100; m.viewRadius = 100; float[] o = new float[3];
		m.centerX = 10; m.centerZ = 10; m.rotate = false;
		check(m.toMap(10, 10, o) && o[0] == 0 && o[1] == 0, "centre");
		check(m.toMap(60, 10, o) && near(o[0], 50, 1e-3), "50 m east = 50 px right at 100m/100px");
		m.setZoom(2); m.toMap(60, 10, o); check(near(o[0], 100, 1e-3), "zoom doubles");
		m.setZoom(1); m.rotate = true; m.yawDeg = 0; m.toMap(10, -40, o); check(near(o[1], -50, 1e-3) && near(o[0], 0, 1e-3), "north (-z) is up when facing yaw 0");
		m.yawDeg = 90; m.toMap(60, 10, o); check(near(o[1], -50, 1e-3) && near(o[0], 0, 1e-3), "facing east, east is up");
		float[] w = new float[2]; m.toMap(33, -17, o); m.toWorld(o[0], o[1], w); check(near(w[0], 33, 1e-2) && near(w[1], -17, 1e-2), "toWorld inverse");
		float[] p = { 300, 400, 0 }; m.clampToEdge(p, 5); check(near(Math.hypot(p[0], p[1]), 95, 1e-3), "clamp to edge");
		m.rotate = false; m.zoneCircle(10, 10, 50, o); check(near(o[2], 50, 1e-3), "zone radius px");
	}

	static void squad() {
		SquadModel s = new SquadModel(); s.reset("me", 2);
		s.add("me", "Me", 1); s.add("b", "Bee", 2); s.add("c", "Cee", 3);
		s.onSnapshotRow("b", 30, 0, 40, 80, 0); s.updateDistances(0, 0);
		check(near(s.find("b").distance, 50, 1e-3) && s.find("me").isMe, "distance + me flag");
		s.onKnock("b"); check(s.find("b").state == SquadModel.KNOCKED && !s.eliminated(), "knocked");
		s.onKill("c"); s.onKill("me"); check(!s.eliminated() || s.find("b").state != SquadModel.ALIVE, "knocked-only squad is eliminated: " + s.eliminated());
		s.onRevive("b"); check(s.find("b").state == SquadModel.ALIVE && !s.eliminated(), "revive");
		s.addMarker("b", 1, 2, 0); s.addMarker("b", 5, 6, 0); check(s.markers.size() == 1 && s.markers.get(0).x == 5, "one marker per kind");
		s.update(25f); check(s.markers.isEmpty(), "markers expire");
		check("999m".equals(SquadModel.distanceLabel(999.4f)), "label m");
	}

	// ---------------------------------------------------------------- live server
	static void live(int port) throws Exception {
        final Map<String, String> store = new HashMap<String, String>();
        Map<String, Object> account = registerAccount(port);
        final String uid = Json.str(account, "uid", "");
        store.put("token", Json.str(account, "token", ""));
        GameClient.Store st = new GameClient.Store() { public String get(String k) { return store.get(k); } public void put(String k, String v) { store.put(k, v); } };
        GameClient c = new GameClient(uid, Json.str(account, "username", "JavaTester"), st);
		c.connect("127.0.0.1", port);
		final List<Map<String, Object>> got = new ArrayList<Map<String, Object>>();
		GameClient.Handler h = new GameClient.Handler() { public void onMessage(Map<String, Object> m) { got.add(m); } };
		check(waitFor(c, got, h, "welcome", 3000) != null, "live: welcome");
		check(store.get("token") != null && store.get("token").length() >= 16, "live: token stored");
		check(c.isConnected(), "live: connected");
		c.send(Json.obj("t", "mm_join", "mode", "br", "teamSize", 1));
		Map<String, Object> ms = waitFor(c, got, h, "match_start", 6000);
		check(ms != null, "live: match_start");
		if (ms != null) {
			List<Object> players = Json.list(ms, "players");
			int bots = 0; for (Object o : players) { @SuppressWarnings("unchecked") Map<String, Object> pm = (Map<String, Object>) o; if (Json.bool(pm, "isBot", false)) bots++; }
			check(players.size() == 50 && bots == 49, "live: 50 players, 49 flagged bots (" + players.size() + "," + bots + ")");
			check(Json.i(Json.map(ms, "counts"), "realPlayers", 0) == 1, "live: honest counts");
		}
		Thread.sleep(2600);   // ping/pong
		check(c.rttMs() >= 0 && Math.abs(c.serverNow() - System.currentTimeMillis()) < 2000, "live: clock sync");
		check(waitFor(c, got, h, "snap", 3000) != null, "live: snapshots");
		// reconnect: kill the socket, expect resume
		c.disconnect(); Thread.sleep(200);
		got.clear();
        GameClient c2 = new GameClient(uid, Json.str(account, "username", "JavaTester"), st);
		c2.connect("127.0.0.1", port);
		Map<String, Object> w2 = waitFor(c2, got, h, "welcome", 3000);
		check(w2 != null && Json.map(w2, "resume") != null, "live: server offers resume");
		Map<String, Object> ms2 = waitFor(c2, got, h, "match_start", 3000);
		check(ms2 != null && Json.bool(ms2, "resume", false), "live: resumed match");
		c2.disconnect();
	}
    static Map<String, Object> registerAccount(int port) throws Exception {
        java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), "UTF-8"));
        java.io.OutputStream out = socket.getOutputStream();
        String id = "jclient_" + Long.toString(System.nanoTime(), 36);
        out.write(Json.write(Json.obj("t", "auth_register", "identity", id, "password", "JavaTesterStrongPass123!")).concat("\n").getBytes("UTF-8"));
        out.flush();
        String line = reader.readLine();
        socket.close();
        Map<String, Object> response = Json.parseObject(line);
        if (!"auth_ok".equals(Json.str(response, "t", ""))) throw new RuntimeException("test account registration failed: " + line);
        return response;
    }

	static Map<String, Object> waitFor(GameClient c, final List<Map<String, Object>> got, GameClient.Handler h, String t, int ms) throws Exception {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			c.pump(h);
			for (Iterator<Map<String, Object>> it = got.iterator(); it.hasNext();) { Map<String, Object> m = it.next(); if (t.equals(Json.str(m, "t", ""))) { it.remove(); return m; } }
			Thread.sleep(10);
		}
		return null;
	}
}
