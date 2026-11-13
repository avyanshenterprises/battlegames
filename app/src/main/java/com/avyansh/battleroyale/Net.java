package com.avyansh.battleroyale;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;

public final class Net {
	private Net() {}
	
	public interface Listener { void onRoomChanged(); void onStatus(String msg); void onMatchStart(); }
	
	/** One person in the room (lobby view). */
	public static final class Member {
		public String uid = "", name = "Player";
		public long joined;
		public boolean isHost, isMe;
	}
	
	public static final int MAX_PLAYERS = 12;
	
	public static FirebaseDb db;
	public static String uid, name = "Player", room = "";
	public static volatile String matchId = "";
	public static boolean host, active, inMatch;
	public static volatile String status = "";
	public static int peakOthers;
	public static volatile int teamSize = 4;
	public static volatile int myTeam = -1;
	public static Listener listener;
	public static final ArrayList<NetPlayer> remotes = new ArrayList<NetPlayer>();
	private static final ArrayList<Member> members = new ArrayList<Member>();
	public static String hostId = "";
	
	private static String apiKey = "";
	private static float sendT, pollT, matchT;
	private static volatile boolean polling, sending, ready, selfSeen, starting;
	private static volatile long lastAuthFail;
	private static String lastMatchId = "";
	public static String killFeed = "";
	public static float killFeedTime;
	public static int pendingDamageDir;
	
	// match bookkeeping (guarded by synchronized(remotes))
	private static final HashMap<String, Integer> teamMap = new HashMap<String, Integer>();
	private static final HashMap<String, Boolean> others = new HashMap<String, Boolean>();
	private static final HashSet<String> everSeen = new HashSet<String>();
	private static final HashMap<String, Long> lastT = new HashMap<String, Long>();
	private static final HashMap<String, Long> lastChange = new HashMap<String, Long>();
	
	// Net.java - Add room state tracking
	public static final int ROOM_STATE_IDLE = 0;
	public static final int ROOM_STATE_WAITING = 1;
	public static final int ROOM_STATE_PLAYING = 2;
	
	private static volatile int roomState = ROOM_STATE_IDLE;
	
	public static int getRoomState() { return roomState; }
	
	public static int getMatchBotCount() {
		return matchBotCount;
	}
	
	/** Get total expected participants (real + bots). */
	public static int getTotalParticipants() {
		return otherCount() + 1 + matchBotCount;
	}
	// ------------------------------------------------------------------ setup
	public static void init(Context c) {
		SharedPreferences p = c.getSharedPreferences("net", Context.MODE_PRIVATE);
		uid = p.getString("uid", null);
		if (uid == null) {
			uid = Long.toString(Math.abs(new Random().nextLong()), 36);
			if (uid.length() > 9) uid = uid.substring(0, 9);
			p.edit().putString("uid", uid).apply();
		}
		name = p.getString("name", "Player" + (100 + new Random().nextInt(900)));
		String url = res(c, "firebase_db_url"), key = res(c, "firebase_api_key");
		apiKey = key;
		if (db == null || !db.configured()) db = new FirebaseDb(url);
	}
	
	public static void setName(Context c, String n) {
		if (n == null || n.trim().length() == 0) return;
		name = n.trim().length() > 14 ? n.trim().substring(0, 14) : n.trim();
		c.getSharedPreferences("net", Context.MODE_PRIVATE).edit().putString("name", name).apply();
		if (active && db != null) db.put("rooms/" + room + "/players/" + uid + "/name", JSONObject.quote(name), null);
	}
	
	private static String res(Context c, String n) {
		int id = c.getResources().getIdentifier(n, "string", c.getPackageName());
		return id == 0 ? "" : c.getString(id);
	}
	
	public static boolean configured() { return db != null && db.configured(); }
	
	private static void say(String m) { status = m; Listener l = listener; if (l != null) l.onStatus(m); }
	
	private static void withAuth(final Runnable r) {
		if (ready && db.authAgeMs() < 45L * 60L * 1000L) { r.run(); return; }
		if (System.currentTimeMillis() - lastAuthFail < 60000L) { r.run(); return; }
		db.signInAnonymously(apiKey, new FirebaseDb.Callback() {
			@Override public void done(boolean ok, String body) {
				if (!ok) { lastAuthFail = System.currentTimeMillis(); say("Sign-in failed (continuing without auth): " + body); }
				else ready = true;
				r.run();
			}
		});
	}
	
	public static String newGameId() {
		String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
		StringBuilder sb = new StringBuilder("G");
		String t = Long.toString(System.currentTimeMillis() / 1000L, 36).toUpperCase();
		sb.append(t.length() > 5 ? t.substring(t.length() - 5) : t);
		Random r = new Random();
		for (int i = 0; i < 3; i++) sb.append(a.charAt(r.nextInt(a.length())));
		return sb.toString();
	}
	
	private static void resetRoomState() {
		active = false;
		inMatch = false;
		host = false;
		room = "";
		matchId = "";
		hostId = "";
		myTeam = -1;
		selfSeen = false;
		polling = false;
		sending = false;
		starting = false;
		roomState = ROOM_STATE_IDLE;
		matchBotCount = 0;
		synchronized (remotes) {
			remotes.clear();
			teamMap.clear();
			others.clear();
			everSeen.clear();
			lastT.clear();
			lastChange.clear();
		}
		synchronized (members) { members.clear(); }
		synchronized (pending) { pending.clear(); pendingFrom.clear(); }
	}
	
	// ------------------------------------------------------------------ rooms
	private static String newCode() {
		String a = "1234567890"; StringBuilder sb = new StringBuilder();
		Random r = new Random();
		for (int i = 0; i < 5; i++) sb.append(a.charAt(r.nextInt(a.length())));
		return sb.toString();
	}
	
	public static void createRoom(final boolean publicQuick) {
		withAuth(new Runnable() { @Override public void run() {
				final String code = newCode();
				String meta = "{\"host\":\"" + uid + "\",\"status\":\"waiting\",\"teamSize\":" + teamSize + ",\"t\":" + System.currentTimeMillis() + "}";
				db.put("rooms/" + code + "/meta", meta, new FirebaseDb.Callback() {
					@Override public void done(boolean ok, String b) {
						if (!ok) { say("Create failed: " + b); return; }
						if (active) leave();
						resetRoomState();
						room = code; host = true; active = true; hostId = uid; peakOthers = 0;
						writeSelf();
						if (publicQuick) db.put("quick/waiting", "{\"code\":\"" + code + "\",\"t\":" + System.currentTimeMillis() + "}", null);
						say("Room " + code + " created. Share the code!");
						fire();
					}
				});
			}});
	}
	
	public static void joinRoom(String raw) { joinInternal(raw, false); }
	
	private static void joinInternal(String raw, final boolean fromQuick) {
		final String code = raw == null ? "" : raw.trim().toUpperCase();
		if (code.length() < 3) { say("Enter a room code"); return; }
		if (!configured()) { say("Firebase URL not set (res/values/firebase_config.xml)"); return; }
		withAuth(new Runnable() { @Override public void run() {
				db.get("rooms/" + code, new FirebaseDb.Callback() {
					@Override public void done(boolean ok, String b) {
						if (!ok) { say("Join failed: " + b); return; }
						if (b == null || b.equals("null")) { if (fromQuick) { createRoom(true); return; } say("Room " + code + " not found"); return; }
						try {
							JSONObject o = new JSONObject(b);
							JSONObject m = o.optJSONObject("meta");
							if (m == null) { if (fromQuick) { createRoom(true); return; } say("Room " + code + " not found"); return; }
							if (!"waiting".equals(m.optString("status"))) { if (fromQuick) { createRoom(true); return; } say("Match already started"); return; }
							JSONObject pl = o.optJSONObject("players");
							if (pl != null && pl.length() >= MAX_PLAYERS) { if (fromQuick) { createRoom(true); return; } say("Room is full"); return; }
							if (active) leave();
							resetRoomState();
							room = code; host = false; active = true; peakOthers = 0;
							hostId = m.optString("host");
							teamSize = m.optInt("teamSize", teamSize);
							writeSelf();
							say("Joined room " + code);
							fire();
						} catch (Throwable t) { say("Bad room data"); }
					}
				});
			}});
	}
	
	public static void quickMatch() {
		if (!configured()) { say("Firebase URL not set (res/values/firebase_config.xml)"); return; }
		say("Searching a match...");
		withAuth(new Runnable() { @Override public void run() {
				db.get("quick/waiting", new FirebaseDb.Callback() {
					@Override public void done(boolean ok, String b) {
						try {
							if (ok && b != null && !b.equals("null")) {
								JSONObject o = new JSONObject(b);
								if (System.currentTimeMillis() - o.optLong("t") < 120000L) { joinInternal(o.optString("code"), true); return; }
							}
						} catch (Throwable ignored) {}
						createRoom(true);
					}
				});
			}});
	}
	
	/** Leave the room (host closes it for everybody). */
	public static void leave() {
		if (!active || db == null) { resetRoomState(); return; }
		final String r = room; boolean h = host;
		if (h) {
			db.delete("rooms/" + r, null);
			db.get("quick/waiting", new FirebaseDb.Callback() {
				@Override public void done(boolean ok, String b) { if (b != null && b.contains(r)) db.delete("quick/waiting", null); } });
		} else {
			db.delete("rooms/" + r + "/players/" + uid, null);
		}
		resetRoomState();
	}
	
	/** Host only: remove a player from the room. */
	public static void kick(String targetUid) {
		if (!active || !host || targetUid == null || targetUid.equals(uid)) return;
		db.delete("rooms/" + room + "/players/" + targetUid, null);
		synchronized (members) {
			for (int i = members.size() - 1; i >= 0; i--) if (members.get(i).uid.equals(targetUid)) members.remove(i);
		}
		fire();
	}
	
	/** Host only: solo / duo / squad. */
	public static void setTeamSize(int n) {
		if (n != 1 && n != 2 && n != 4) return;
		teamSize = n;
		if (active && host && db != null) db.patch("rooms/" + room + "/meta", "{\"teamSize\":" + n + "}", null);
		fire();
	}
	
	private static final Comparator<Member> BY_JOIN = new Comparator<Member>() {
		@Override public int compare(Member a, Member b) {
			if (a.joined != b.joined) return a.joined < b.joined ? -1 : 1;
			return a.uid.compareTo(b.uid);
		}
	};
	
	/** Snapshot of the room, in join order (safe to call from the UI thread). */
	public static ArrayList<Member> members() {
		synchronized (members) { return new ArrayList<Member>(members); }
	}
	
	/** The people who would share a team with me when the match starts (lobby preview). */
	public static ArrayList<Member> myGroup() {
		ArrayList<Member> all = members();
		int me = -1;
		for (int i = 0; i < all.size(); i++) if (all.get(i).isMe) me = i;
		ArrayList<Member> g = new ArrayList<Member>();
		if (me < 0) return g;
		int ts = Math.max(1, teamSize);
		int from = (me / ts) * ts;
		for (int i = from; i < Math.min(all.size(), from + ts); i++) g.add(all.get(i));
		return g;
	}
	
	public static void startMatch() {
		if (!active || !host || inMatch || starting) return;
		starting = true;
		say("Starting match...");
		
		db.get("rooms/" + room + "/players", new FirebaseDb.Callback() {
			@Override public void done(boolean ok, String b) {
				if (!ok) { starting = false; say("Start failed: " + b); return; }
				try {
					ArrayList<Member> list = new ArrayList<Member>();
					JSONObject pl = (b == null || b.equals("null")) ? new JSONObject() : new JSONObject(b);
					for (Iterator<String> it = pl.keys(); it.hasNext();) {
						String k = it.next();
						JSONObject pj = pl.optJSONObject(k);
						Member m = new Member();
						m.uid = k;
						m.name = pj != null ? pj.optString("name", k) : k;
						m.joined = pj != null ? pj.optLong("joined", 0) : 0;
						list.add(m);
					}
					if (!containsUid(list, uid)) {
						Member m = new Member();
						m.uid = uid;
						m.name = name;
						m.joined = 0;
						list.add(m);
					}
					Collections.sort(list, BY_JOIN);
					
					final String mid = newGameId();
					final JSONObject teams = new JSONObject();
					int ts = Math.max(1, teamSize);
					
					// Assign teams to real players
					for (int i = 0; i < list.size(); i++) {
						teams.put(list.get(i).uid, i / ts);
					}
					
					// Calculate how many bots to add
					int realPlayerCount = list.size();
					int totalSlots = BotManager.MATCH_SIZE;
					int botCount = Math.max(0, totalSlots - realPlayerCount);
					
					// Create bot assignments (they go to teams after real players)
					int nextTeamSlot = list.size();
					for (int i = 0; i < botCount; i++) {
						teams.put("bot_" + i, nextTeamSlot / ts);
						nextTeamSlot++;
					}
					
					String meta = "{\"status\":\"playing\",\"matchId\":\"" + mid + "\",\"startAt\":" + System.currentTimeMillis()
					+ ",\"teamSize\":" + ts + ",\"teams\":" + teams.toString() 
					+ ",\"botCount\":" + botCount + ",\"realPlayers\":" + realPlayerCount + "}";
					
					db.patch("rooms/" + room + "/meta", meta, new FirebaseDb.Callback() {
						@Override public void done(boolean ok2, String b2) {
							if (!ok2) { starting = false; say("Start failed: " + b2); return; }
							db.delete("quick/waiting", null);
							starting = false;
							beginMatchLocal(mid, teams);
						}
					});
				} catch (Throwable t) {
					starting = false;
					say("Start failed: " + t);
				}
			}
		});
	}
	
	// Update beginMatchLocal to store bot count
	private static int matchBotCount = 0;
	
	private static void beginMatchLocal(String mid, JSONObject teams) {
		synchronized (remotes) {
			remotes.clear();
			teamMap.clear();
			others.clear();
			everSeen.clear();
			lastT.clear();
			lastChange.clear();
			if (teams != null) {
				for (Iterator<String> it = teams.keys(); it.hasNext();) {
					String k = it.next();
					teamMap.put(k, teams.optInt(k, -1));
					if (!k.equals(uid) && !k.startsWith("bot_")) {
						others.put(k, Boolean.TRUE);
					}
				}
			}
			Integer mt = teamMap.get(uid);
			myTeam = mt == null ? 0 : mt;
		}
		synchronized (pending) { pending.clear(); pendingFrom.clear(); }
		matchId = mid;
		lastMatchId = mid;
		peakOthers = 0;
		matchT = 0f;
		sendT = 0f;
		pollT = 0f;
		inMatch = true;
		Listener l = listener;
		if (l != null) l.onMatchStart();
	}
	
	private static boolean containsUid(ArrayList<Member> l, String id) {
		for (int i = 0; i < l.size(); i++) if (l.get(i).uid.equals(id)) return true;
		return false;
	}
	
	/** Called by GameActivity when it closes: the room goes back to the lobby (host re-opens it for the next game). */
	public static void endMatch() {
		boolean wasIn = inMatch;
		inMatch = false;
		synchronized (remotes) { remotes.clear(); teamMap.clear(); others.clear(); everSeen.clear(); lastT.clear(); lastChange.clear(); }
		synchronized (pending) { pending.clear(); pendingFrom.clear(); }
		matchId = ""; myTeam = -1;
		if (wasIn && active && db != null) {
			db.delete("rooms/" + room + "/players/" + uid + "/s", null);
			db.delete("rooms/" + room + "/ev/" + uid, null);
			if (host) db.patch("rooms/" + room + "/meta", "{\"status\":\"waiting\"}", null);
		}
		fire();
	}
	
	private static void fire() { Listener l = listener; if (l != null) l.onRoomChanged(); }
	
	private static void writeSelf() {
		db.put("rooms/" + room + "/players/" + uid, "{\"name\":" + JSONObject.quote(name) + ",\"joined\":" + System.currentTimeMillis() + "}", null);
	}
	
	
	// ---------------- friends and party invitations (Realtime Database REST)
	public interface SocialInboxCallback { void done(boolean ok, String json); }
	/** Fetch pending friend requests, party invitations and accepted friends for the signed-in player. */
	public static void loadSocialInbox(final SocialInboxCallback callback) {
		if (!configured() || uid == null) { if (callback != null) callback.done(false, "Firebase is not configured"); return; }
		withAuth(new Runnable() { public void run() {
				final JSONObject result = new JSONObject();
				final String[] paths = {"friendRequests/" + safe(uid), "teamInvites/" + safe(uid), "friends/" + safe(uid)};
				final String[] keys = {"requests", "invites", "friends"};
				final int[] remaining = {paths.length};
				final boolean[] success = {true};
				for (int i=0;i<paths.length;i++) {
					final int index=i;
					db.get(paths[i], new FirebaseDb.Callback() { public void done(boolean ok, String body) {
							synchronized (result) {
								try { result.put(keys[index], ok && body != null && !"null".equals(body) ? new JSONObject(body) : new JSONObject()); }
								catch (Throwable ignored) { try { result.put(keys[index], new JSONObject()); } catch (Throwable ignored2) {} }
								if (!ok) success[0]=false;
								remaining[0]--;
								if (remaining[0]==0 && callback!=null) callback.done(success[0], result.toString());
							}
						}});
				}
			}});
	}
	
	// ---------------- friends and party invitations (Realtime Database REST)
	private static String safe(String v) { return v==null?"":v.replace("\\","\\\\").replace("\"","\\\"").replace("/","_"); }
	public static void sendFriendRequest(final String targetUid) {
		if(!configured() || targetUid==null || targetUid.trim().length()<3 || targetUid.equals(uid)) { say("Invalid friend ID"); return; }
		withAuth(new Runnable(){ public void run(){
				String body="{\"from\":\""+safe(uid)+"\",\"name\":\""+safe(name)+"\",\"createdAt\":"+System.currentTimeMillis()+"}";
				db.put("friendRequests/"+safe(targetUid)+"/"+safe(uid),body,new FirebaseDb.Callback(){public void done(boolean ok,String b){say(ok?"Friend request sent":"Friend request failed: "+b);}});
			}});
	}
	public static void acceptFriendRequest(final String fromUid) {
		if(!configured() || fromUid==null || fromUid.length()<3)return;
		withAuth(new Runnable(){public void run(){
				final String stamp="{\"since\":"+System.currentTimeMillis()+",\"name\":\""+safe(name)+"\"}";
				db.put("friends/"+safe(uid)+"/"+safe(fromUid),stamp,new FirebaseDb.Callback(){public void done(boolean ok,String b){
						if(!ok){say("Accept failed: "+b);return;}
						db.put("friends/"+safe(fromUid)+"/"+safe(uid),"{\"since\":"+System.currentTimeMillis()+",\"name\":\""+safe(name)+"\"}",null);
						db.delete("friendRequests/"+safe(uid)+"/"+safe(fromUid),null); say("Friend request accepted");
					}});
			}});
	}
	public static void declineFriendRequest(final String fromUid) { if(configured()&&fromUid!=null) withAuth(new Runnable(){public void run(){db.delete("friendRequests/"+safe(uid)+"/"+safe(fromUid),null);}}); }
	public static void inviteToTeam(final String targetUid) {
		if(!active || targetUid==null || targetUid.equals(uid)){say("Create or join a party first");return;}
		withAuth(new Runnable(){public void run(){String body="{\"from\":\""+safe(uid)+"\",\"name\":\""+safe(name)+"\",\"room\":\""+safe(room)+"\",\"createdAt\":"+System.currentTimeMillis()+"}";
				db.put("teamInvites/"+safe(targetUid)+"/"+safe(uid),body,new FirebaseDb.Callback(){public void done(boolean ok,String b){say(ok?"Team invitation sent":"Invite failed: "+b);}});
			}});
	}
	public static void acceptTeamInvite(final String fromUid, final String inviteRoom) { if(inviteRoom==null||inviteRoom.length()<3)return; if (active) leave(); joinRoom(inviteRoom); if(configured()&&fromUid!=null) db.delete("teamInvites/"+safe(uid)+"/"+safe(fromUid),null); }
	public static void declineTeamInvite(final String fromUid) { if(configured()&&fromUid!=null) withAuth(new Runnable(){public void run(){db.delete("teamInvites/"+safe(uid)+"/"+safe(fromUid),null);}}); }
	
	// ------------------------------------------------------------------ per frame (GL thread)
	public static void tick(float dt, GameEngine eng) {
		if (ServerNet.inServerMatch()) { ServerNet.tick(dt, eng); if (killFeedTime > 0f) killFeedTime -= dt; return; }
		if (!active) return;
		pollT += dt; sendT += dt; matchT += dt;
		if (inMatch) {
			if (sendT >= 0.11f && !sending) { sendT = 0f; sendState(eng); }
			if (pollT >= 0.12f && !polling) { pollT = 0f; poll(eng); }
			// players that never showed up (crashed while loading) do not keep the match alive forever
			if (matchT > 90f) synchronized (remotes) {
				for (String k : new ArrayList<String>(others.keySet())) if (!everSeen.contains(k)) others.put(k, Boolean.FALSE);
			}
		}
		synchronized (remotes) {
			for (int i = 0; i < remotes.size(); i++) remotes.get(i).step(dt);
		}
		if (killFeedTime > 0f) killFeedTime -= dt;
	}
	
	/** Lobby polling (called from the lobby UI timer). */
	public static void pollLobby() {
		if (!active || polling || inMatch) return;
		polling = true;
		final String r = room;
		db.get("rooms/" + r, new FirebaseDb.Callback() {
			@Override public void done(boolean ok, String b) {
				polling = false;
				if (!ok || !active || !r.equals(room)) return;
				try {
					if (b == null || b.equals("null")) { say("Room closed by the host"); resetRoomState(); fire(); return; }
					JSONObject o = new JSONObject(b);
					JSONObject meta = o.optJSONObject("meta");
					JSONObject pl = o.optJSONObject("players");
					if (meta == null && !host) { say("Room closed by the host"); resetRoomState(); fire(); return; }
					
					// removed (kicked) from the room? only meaningful once we were seen in it
					boolean inList = pl != null && pl.has(uid);
					if (inList) selfSeen = true;
					else if (selfSeen) { say("You were removed from the room"); resetRoomState(); fire(); return; }
					
					ArrayList<Member> list = new ArrayList<Member>();
					if (pl != null) for (Iterator<String> it = pl.keys(); it.hasNext();) {
						String k = it.next(); JSONObject pj = pl.optJSONObject(k);
						Member m = new Member(); m.uid = k;
						m.name = pj != null ? pj.optString("name", k) : k;
						m.joined = pj != null ? pj.optLong("joined", 0) : 0;
						m.isMe = k.equals(uid);
						m.isHost = meta != null && k.equals(meta.optString("host"));
						list.add(m);
					}
					Collections.sort(list, BY_JOIN);
					synchronized (members) { members.clear(); members.addAll(list); }
					if (meta != null) {
						hostId = meta.optString("host");
						if (!host) teamSize = meta.optInt("teamSize", teamSize);
					}
					fire();
					
					if (meta != null && "playing".equals(meta.optString("status")) && !inMatch) {
						String mid = meta.optString("matchId", "");
						if (mid.length() > 0 && !mid.equals(lastMatchId)) {
							JSONObject teams = meta.optJSONObject("teams");
							if (teams == null || !teams.has(uid)) return; // joined too late for this match
							beginMatchLocal(mid, teams);
						}
					}
				} catch (Throwable ignored) { polling = false; }
			}
		});
	}
	
	private static void sendState(GameEngine eng) {
		Player p = eng.player;
		try {
			JSONObject s = new JSONObject();
			s.put("m", matchId);
			s.put("x", r1(p.x)); s.put("y", r1(p.y)); s.put("z", r1(p.z)); s.put("r", r2(p.yaw));
			s.put("v", r1(p.speedNow)); s.put("p", r2(p.aimPitchRad));
			int f = (p.alive ? 1 : 0) | (p.onGround ? 2 : 0) | (p.crouching ? 4 : 0) | (p.swimming ? 8 : 0)
			| (eng.aiming ? 16 : 0) | (eng.fireHeld ? 32 : 0);
			s.put("f", f); s.put("w", eng.weapons.current()==null ? -1 : eng.weapons.current().type); s.put("h", (int) p.health); s.put("t", System.currentTimeMillis());
			sending = true;
			db.put("rooms/" + room + "/players/" + uid + "/s", s.toString(), new FirebaseDb.Callback() {
				@Override public void done(boolean ok, String b) { sending = false; }
			});
		} catch (Throwable ignored) { sending = false; }
	}
	
	private static double r1(float v) { return Math.round(v * 10f) / 10.0; }
	private static double r2(float v) { return Math.round(v * 100f) / 100.0; }
	
	private static void poll(final GameEngine eng) {
		polling = true;
		final String mid = matchId;
		db.get("rooms/" + room, new FirebaseDb.Callback() {
			@Override public void done(boolean ok, String b) {
				polling = false;
				if (!ok || b == null || b.equals("null") || !inMatch || !mid.equals(matchId)) return;
				try {
					JSONObject o = new JSONObject(b);
					JSONObject pl = o.optJSONObject("players");
					long now = System.currentTimeMillis();
					synchronized (remotes) {
						ArrayList<String> seen = new ArrayList<String>();
						if (pl != null) for (Iterator<String> it = pl.keys(); it.hasNext();) {
							String k = it.next();
							if (k.equals(uid)) continue;
							JSONObject pj = pl.optJSONObject(k); if (pj == null) continue;
							JSONObject s = pj.optJSONObject("s"); if (s == null) continue;
							if (!mid.equals(s.optString("m"))) continue; // leftover of another game
							long t = s.optLong("t");
							Long prevT = lastT.get(k);
							if (prevT == null || prevT.longValue() != t) { lastT.put(k, t); lastChange.put(k, now); }
							Long ch = lastChange.get(k);
							if (ch != null && now - ch.longValue() > 12000L) continue; // stopped sending -> disconnected
							NetPlayer n = find(k);
							if (n == null) { n = new NetPlayer(); n.id = k; remotes.add(n); }
							n.lastSeen = now;
							seen.add(k);
							Integer tm = teamMap.get(k);
							n.team = tm == null ? -1 : tm;
							n.name = pj.optString("name", "Player");
							n.tx = (float) s.optDouble("x"); n.ty = (float) s.optDouble("y"); n.tz = (float) s.optDouble("z");
							n.tyaw = (float) s.optDouble("r"); n.speed = (float) s.optDouble("v"); n.pitch = (float) s.optDouble("p");
							int f = s.optInt("f", 1);
							n.alive = (f & 1) != 0; n.onGround = (f & 2) != 0; n.crouch = (f & 4) != 0; n.swim = (f & 8) != 0;
							n.aiming = (f & 16) != 0; n.firing = (f & 32) != 0;
							n.weapon = s.optInt("w"); n.hp = s.optInt("h", 100);
							everSeen.add(k);
							others.put(k, n.alive);
						}
						for (int i = remotes.size() - 1; i >= 0; i--) {
							String id = remotes.get(i).id;
							if (!seen.contains(id)) { others.put(id, Boolean.FALSE); remotes.remove(i); }
						}
						if (remotes.size() > peakOthers) peakOthers = remotes.size();
					}
					JSONObject ev = o.optJSONObject("ev");
					JSONObject mine = ev == null ? null : ev.optJSONObject(uid);
					if (mine != null) for (Iterator<String> it = mine.keys(); it.hasNext();) {
						String k = it.next(); JSONObject e = mine.optJSONObject(k); if (e == null) continue;
						db.delete("rooms/" + room + "/ev/" + uid + "/" + k, null);
						if (!mid.equals(e.optString("m"))) continue;
						if (now - e.optLong("t") > 5000L) continue;
						String from = e.optString("from");
						if (isTeammateId(from)) continue; // no friendly fire
						synchronized (pending) { pending.add(new float[] { (float) e.optDouble("d"), e.optBoolean("hs") ? 1f : 0f }); pendingFrom.add(from); }
					}
				} catch (Throwable ignored) {}
			}
		});
	}
	
	private static final ArrayList<float[]> pending = new ArrayList<float[]>();
	private static final ArrayList<String> pendingFrom = new ArrayList<String>();
	
	/** GL thread: apply damage other players dealt to us. */
	public static void applyIncoming(GameEngine eng) {
		if (ServerNet.inServerMatch()) return;   // the server applies damage itself
		synchronized (pending) {
			for (int i = 0; i < pending.size(); i++) {
				float dmg = pending.get(i)[0];
				Player p = eng.player;
				if (!p.alive || eng.match.phase != MatchPhase.PLAYING) continue;
				boolean wasAlive = p.alive;
				p.damage(dmg);
				eng.healing.onDamaged(dmg);
				eng.cameraShake.hit(Math.min(1.5f, dmg / 20f));
				GameRenderer r = eng.getRenderer();
				if (r != null) r.flashDamage();
				eng.sound.play("hurt");
				if (wasAlive && !p.alive) {
					String by = nameOf(pendingFrom.get(i));
					announce(by + " eliminated you");
				}
			}
			pending.clear(); pendingFrom.clear();
		}
	}
	
	public static void sendDamage(String target, float dmg, boolean head) {
		if (ServerNet.inServerMatch()) return;   // never trust clients with damage: the server validates `fire` messages
		if (!active || db == null || !inMatch) return;
		db.post("rooms/" + room + "/ev/" + target, "{\"from\":\"" + uid + "\",\"m\":\"" + matchId + "\",\"d\":" + (int) dmg + ",\"hs\":" + head + ",\"t\":" + System.currentTimeMillis() + "}", null);
	}
	
	public static void announce(String s) { killFeed = s; killFeedTime = 4f; }
	
	public static String nameOf(String id) {
		synchronized (remotes) { NetPlayer n = find(id); return n == null ? "Player" : n.name; }
	}
	
	private static NetPlayer find(String id) {
		for (int i = 0; i < remotes.size(); i++) if (remotes.get(i).id.equals(id)) return remotes.get(i);
		return null;
	}
	
	// ------------------------------------------------------------------ teams
	private static boolean isTeammateId(String id) {
		synchronized (remotes) {
			Integer t = teamMap.get(id);
			return t != null && myTeam >= 0 && t == myTeam;
		}
	}
	
	/** True when this remote player is on my team (cannot be shot, shown green on the map). */
	public static boolean isFriend(NetPlayer n) { return n != null && myTeam >= 0 && n.team == myTeam; }
	
	public static ArrayList<NetPlayer> teammates() {
		ArrayList<NetPlayer> l = new ArrayList<NetPlayer>();
		synchronized (remotes) { for (int i = 0; i < remotes.size(); i++) if (isFriend(remotes.get(i))) l.add(remotes.get(i)); }
		return l;
	}
	
	/** Players (other than me) still in the match, counting ones that have not appeared yet. */
	public static int aliveRemotes() {
		if (ServerNet.inServerMatch()) { int n = 0; synchronized (remotes) { for (int i = 0; i < remotes.size(); i++) if (remotes.get(i).alive) n++; } return n; }
		int c = 0;
		synchronized (remotes) { for (Boolean b : others.values()) if (b) c++; }
		return c;
	}
	
	public static int otherCount() { synchronized (remotes) { return ServerNet.inServerMatch() ? remotes.size() : others.size(); } }
	
	/** Enemy-team players still alive (a team match ends when this reaches 0). */
	public static int aliveEnemies() {
		if (ServerNet.inServerMatch()) { int n = 0; synchronized (remotes) { for (int i = 0; i < remotes.size(); i++) { NetPlayer o = remotes.get(i); if (o.alive && !isFriend(o)) n++; } } return n; }
		int c = 0;
		synchronized (remotes) {
			for (java.util.Map.Entry<String, Boolean> e : others.entrySet()) {
				Integer t = teamMap.get(e.getKey());
				boolean friend = t != null && myTeam >= 0 && t == myTeam;
				if (!friend && e.getValue()) c++;
			}
		}
		return c;
	}
	
	/** How many enemy-team players this match started with. */
	public static int expectedEnemies() {
		if (ServerNet.inServerMatch()) { int n = 0; synchronized (remotes) { for (int i = 0; i < remotes.size(); i++) if (!isFriend(remotes.get(i))) n++; } return n; }
		int c = 0;
		synchronized (remotes) {
			for (String k : others.keySet()) {
				Integer t = teamMap.get(k);
				if (!(t != null && myTeam >= 0 && t == myTeam)) c++;
			}
		}
		return c;
	}
}
