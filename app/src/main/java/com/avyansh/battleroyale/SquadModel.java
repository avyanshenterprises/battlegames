package com.avyansh.battleroyale;

import java.util.ArrayList;

/** Client-side squad state for the PUBG-style HUD (numbers, names, health, knocked/eliminated, distance, markers). */
public final class SquadModel {
	public static final int ALIVE = 0, KNOCKED = 1, DEAD = 2;

	public static final class Member {
		public String id = "", name = "";
		public int slot, state = ALIVE, hp = 100;
		public float x, y, z, distance;
		public boolean isMe, connected = true;
		public float knockedFor;
	}
	public static final class Marker { public String by; public float x, z; public int kind; public float age; }

	public final ArrayList<Member> members = new ArrayList<Member>(4);
	public final ArrayList<Marker> markers = new ArrayList<Marker>(8);
	public String myId = "";
	public int team = -1;
	public static final float MARKER_LIFE = 20f;

	public void reset(String myId, int team) { this.myId = myId; this.team = team; members.clear(); markers.clear(); }

	public Member add(String id, String name, int slot) {
		Member m = find(id);
		if (m == null) { m = new Member(); members.add(m); }
		m.id = id; m.name = name; m.slot = slot; m.isMe = id.equals(myId);
		return m;
	}
	public Member find(String id) { for (int i = 0; i < members.size(); i++) if (members.get(i).id.equals(id)) return members.get(i); return null; }

	/** snapshot row; stCode: 6 = knocked, 8 = dead */
	public void onSnapshotRow(String id, float x, float y, float z, int hp, int stCode) {
		Member m = find(id); if (m == null) return;
		m.x = x; m.y = y; m.z = z; m.hp = hp;
		int s = stCode == 6 ? KNOCKED : stCode == 8 ? DEAD : ALIVE;
		if (s == KNOCKED && m.state != KNOCKED) m.knockedFor = 0f;
		m.state = s;
	}
	public void onKnock(String id) { Member m = find(id); if (m != null) { m.state = KNOCKED; m.knockedFor = 0f; } }
	public void onKill(String id) { Member m = find(id); if (m != null) { m.state = DEAD; m.hp = 0; } }
	public void onRevive(String id) { Member m = find(id); if (m != null) { m.state = ALIVE; m.hp = 30; } }

	public void updateDistances(float myX, float myZ) {
		for (int i = 0; i < members.size(); i++) { Member m = members.get(i); float dx = m.x - myX, dz = m.z - myZ; m.distance = (float) Math.sqrt(dx * dx + dz * dz); }
	}
	public void update(float dt) {
		for (int i = 0; i < members.size(); i++) if (members.get(i).state == KNOCKED) members.get(i).knockedFor += dt;
		for (int i = markers.size() - 1; i >= 0; i--) { markers.get(i).age += dt; if (markers.get(i).age > MARKER_LIFE) markers.remove(i); }
	}
	public void addMarker(String by, float x, float z, int kind) {
		for (int i = markers.size() - 1; i >= 0; i--) if (markers.get(i).by.equals(by) && markers.get(i).kind == kind) markers.remove(i);   // one marker per kind per player
		Marker m = new Marker(); m.by = by; m.x = x; m.z = z; m.kind = kind; markers.add(m);
	}
	public int aliveCount() { int n = 0; for (int i = 0; i < members.size(); i++) if (members.get(i).state != DEAD) n++; return n; }
	/** squad eliminated when nobody is standing */
	public boolean eliminated() { for (int i = 0; i < members.size(); i++) if (members.get(i).state == ALIVE) return false; return !members.isEmpty(); }
	public static String distanceLabel(float d) { return d < 1000f ? ((int) d) + "m" : String.format("%.1fkm", d / 1000f); }
}
