package com.avyansh.battleroyale;

import java.util.ArrayList;

/**
* Loot spawning + pickup for the full PUBG item set: 26 weapons, 6 ammo types, 5 healing items,
* vests / helmets / backpacks level 1-3. Pickups need a tap on the loot pop-up.
*/
public class LootManager {
	public final ArrayList<Loot> all = new ArrayList<>();

	private static final String[] ITEM_NAMES = {
		"Bandage", "First Aid Kit", "Medkit", "Painkiller", "Energy Drink",
		"Vest (Level.1)", "Vest (Level.2)", "Vest (Level.3)",
		"Helmet (Level.1)", "Helmet (Level.2)", "Helmet (Level.3)",
		"Backpack (Level.1)", "Backpack (Level.2)", "Backpack (Level.3)"
	};
	private static final float[] HELMET_DURABILITY = { 0f, 45f, 60f, 80f };

	public static String nameOf(int type) {
		if (type >= Loot.WEAPON_BASE) return Weapon.nameOf(type - Loot.WEAPON_BASE);
		if (type >= Loot.AMMO_BASE && type < Loot.AMMO_BASE + 6) return Weapon.AMMO_NAMES[type - Loot.AMMO_BASE] + " Ammo";
		if (type >= 0 && type < ITEM_NAMES.length) return ITEM_NAMES[type];
		return "Supply";
	}

	public static String subOf(int type) {
		switch (type) {
			case Loot.BANDAGE: return "Heals 15 HP (up to 75) \u00B7 2.5s";
			case Loot.FIRST_AID: return "Heals 55 HP (up to 75) \u00B7 4s";
			case Loot.MEDKIT: return "Heals fully \u00B7 6s";
			case Loot.PAINKILLER: return "Heals 30 HP \u00B7 4s";
			case Loot.ENERGY_DRINK: return "Heals 25 HP \u00B7 3.5s";
			case Loot.VEST_1: return "Absorbs 30% of damage";
			case Loot.VEST_2: return "Absorbs 40% of damage";
			case Loot.VEST_3: return "Absorbs 55% of damage";
			case Loot.HELMET_1: return "Head protection \u00B7 8% reduction";
			case Loot.HELMET_2: return "Head protection \u00B7 14% reduction";
			case Loot.HELMET_3: return "Head protection \u00B7 20% reduction";
			case Loot.BACKPACK_1: return "Carry capacity \u00B7 7 heal items";
			case Loot.BACKPACK_2: return "Carry capacity \u00B7 10 heal items";
			case Loot.BACKPACK_3: return "Carry capacity \u00B7 13 heal items";
			default:
				if (type >= Loot.AMMO_BASE && type < Loot.AMMO_BASE + 6) return "+" + Weapon.AMMO_BOX[type - Loot.AMMO_BASE] + " rounds";
				if (type >= Loot.WEAPON_BASE) {
					Weapon.Def d = Weapon.DEFS[type - Loot.WEAPON_BASE];
					return Weapon.CAT_NAMES[d.cat] + " \u00B7 " + Weapon.AMMO_NAMES[d.ammo] + " \u00B7 DMG " + (int) d.damage + (d.pellets > 1 ? "x" + d.pellets : "");
				}
				return "Supply";
		}
	}

	/** Rarity colour (white / blue / gold like PUBG) for gear, category colour for guns. */
	public static int colorOf(int type) {
		if (type >= Loot.WEAPON_BASE) return Weapon.CAT_COLORS[Weapon.categoryOf(type - Loot.WEAPON_BASE)];
		switch (type) {
			case Loot.BANDAGE: return 0xFFE8EEF2;
			case Loot.FIRST_AID: return 0xFFFF7A7A;
			case Loot.MEDKIT: return 0xFFFF4D4D;
			case Loot.PAINKILLER: return 0xFFFFA64D;
			case Loot.ENERGY_DRINK: return 0xFF4DC3FF;
			case Loot.VEST_1: case Loot.HELMET_1: case Loot.BACKPACK_1: return 0xFFD6DDE3;
			case Loot.VEST_2: case Loot.HELMET_2: case Loot.BACKPACK_2: return 0xFF5AA9FF;
			case Loot.VEST_3: case Loot.HELMET_3: case Loot.BACKPACK_3: return 0xFFFFC93C;
			default: return 0xFFFFD54F;
		}
	}

	/** Drawable used in the loot pop-up. */
	public static int iconOf(int type) {
		if (type >= Loot.WEAPON_BASE || (type >= Loot.AMMO_BASE && type < Loot.AMMO_BASE + 6)) return R.drawable.ic_gun;
		if (type >= Loot.BANDAGE && type <= Loot.ENERGY_DRINK) return R.drawable.ic_medkit;
		if (type >= Loot.BACKPACK_1 && type <= Loot.BACKPACK_3) return R.drawable.ic_bag;
		return R.drawable.ic_shield;
	}

	/** Up to 4 un-taken items close to the player, nearest first (for the loot pop-up). */
	public ArrayList<Loot> nearby(float px, float py, float pz) {
		final ArrayList<Loot> found = new ArrayList<>();
		final ArrayList<Float> dist = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			Loot l = all.get(i);
			if (l.taken) continue;
			float dx = px - l.x, dz = pz - l.z;
			float d2 = dx * dx + dz * dz;
			if (d2 >= 3.2f * 3.2f || Math.abs(py - (l.y - 0.75f)) >= 3.2f) continue;
			int at = found.size();
			while (at > 0 && dist.get(at - 1) > d2) at--;
			found.add(at, l); dist.add(at, d2);
		}
		while (found.size() > 4) found.remove(found.size() - 1);
		return found;
	}

	/** Called when the player taps an item in the loot pop-up. */
	public void pickup(Loot l, GameEngine eng) {
		if (l == null || l.taken) return;
		if (l.serverId >= 0 && ServerNet.inServerMatch()) {   // online: the server validates range and owns the inventory
			ServerNet.sendPickup(l.serverId);
			eng.sound.play("loot");
			return;
		}
		Player p = eng.player;
		int t = l.type;
		if (l.isWeapon()) {
			boolean fresh = l.weaponItem == null;
			Weapon incoming = fresh ? Weapon.create(l.weaponType()) : l.weaponItem;
			boolean added = eng.weapons.pickup(incoming, false);
			if (!added) {
				// All slots occupied: tapping a ground weapon swaps the equipped slot.
				Weapon old = eng.weapons.swapCurrent(incoming);
				if (old != null) all.add(new Loot(Loot.WEAPON_BASE + old.type, l.x + 0.18f, l.y, l.z + 0.18f, old));
			}
			if (fresh) eng.weapons.addAmmo(incoming.ammoType, incoming.startReserve);
		} else if (l.isAmmo()) {
			eng.weapons.addAmmo(l.ammoType(), Weapon.AMMO_BOX[l.ammoType()]);
		} else if (l.isHeal()) {
			if (p.healCount() >= p.healCapacity()) { refuse(eng); return; }
			p.heal[t]++;
		} else if (l.isVest()) {
			int lv = l.level();
			if (lv < p.vestLevel && p.armor >= 25f) { refuse(eng); return; }
			p.vestLevel = lv; p.armor = p.maxArmor;
		} else if (l.isHelmet()) {
			int lv = l.level();
			if (lv < p.helmetLevel) { refuse(eng); return; }
			if (lv == p.helmetLevel && p.helmetDurability >= HELMET_DURABILITY[lv]) { refuse(eng); return; }
			p.helmetLevel = lv; p.helmetDurability = HELMET_DURABILITY[lv];
		} else if (l.isBackpack()) {
			int lv = l.level();
			if (lv <= p.backpackLevel) { refuse(eng); return; }
			p.backpackLevel = lv;
		} else {
			return;
		}
		l.taken = true;
		eng.sound.play("loot");
	}

	private void refuse(GameEngine eng) { if (eng.sound != null) eng.sound.playPitched("hit", 0.5f); }

	/** Drop selected weapon into the world, preserving its remaining magazine. */
	public boolean dropCurrent(GameEngine eng) {
		if (ServerNet.inServerMatch()) { ServerNet.sendDrop(); return true; }
		if (eng == null || eng.weapons.current() == null) return false;
		Weapon w = eng.weapons.dropCurrent();
		if (w == null) return false;
		Player p = eng.player;
		float fx = (float) Math.sin(p.yaw), fz = (float) Math.cos(p.yaw);
		float x = p.x + fx * 1.15f, z = p.z + fz * 1.15f;
		all.add(new Loot(Loot.WEAPON_BASE + w.type, x, p.y + 0.12f, z, w));
		return true;
	}

	/** Weighted item roll for one world spawn. */
	private int rollItem(java.util.Random r) {
		float roll = r.nextFloat();
		if (roll < 0.40f) return Loot.WEAPON_BASE + Weapon.randomType(r);
		if (roll < 0.58f) return Loot.AMMO_BASE + r.nextInt(6);
		if (roll < 0.74f) {                              // healing: common items most often
			float h = r.nextFloat();
			if (h < 0.38f) return Loot.BANDAGE;
			if (h < 0.60f) return Loot.FIRST_AID;
			if (h < 0.72f) return Loot.MEDKIT;
			if (h < 0.88f) return Loot.PAINKILLER;
			return Loot.ENERGY_DRINK;
		}
		int kind = r.nextInt(3);                         // 0 vest, 1 helmet, 2 backpack
		float lv = r.nextFloat();
		int level = lv < 0.60f ? 0 : (lv < 0.90f ? 1 : 2);
		return (kind == 0 ? Loot.VEST_1 : kind == 1 ? Loot.HELMET_1 : Loot.BACKPACK_1) + level;
	}

	public void spawnAll(TerrainGenerator tg) {
		all.clear();
		java.util.Random r = new java.util.Random(GameSettings.WORLD_SEED ^ 0xCAFE);
		for (Building b : tg.buildings) {
			int n = 3 + r.nextInt(4);   // 3-6 items per building
			for (int i = 0; i < n; i++) {
				float ox = (r.nextFloat() - 0.5f) * b.w * 0.6f;
				float oz = (r.nextFloat() - 0.5f) * b.d * 0.6f;
				all.add(new Loot(rollItem(r), b.x + ox, b.baseY + 0.75f, b.z + oz));   // on the interior floor slab
			}
		}
	}

	// ------------------------------------------------------------------ dedicated-server loot
	/** Maps a server loot row {id,type,kind,x,y,z,mag} to a local Loot (kinds: gun, ammo, heal, armor, helmet). */
	private static Loot fromServer(java.util.Map<String, Object> m) {
		int id = (int) Json.num(m, "id", -1), type = (int) Json.num(m, "type", 0);
		String kind = Json.str(m, "kind", "");
		float x = (float) Json.num(m, "x", 0), y = (float) Json.num(m, "y", 0) + 0.75f, z = (float) Json.num(m, "z", 0);
		int lt;
		Weapon item = null;
		if (kind.equals("gun")) {
			lt = WEAPON_TYPE_BASE + type;
			if (m.containsKey("mag")) { item = Weapon.create(type); item.ammo = (int) Json.num(m, "mag", item.magSize); }
		} else if (kind.equals("ammo")) lt = Loot.AMMO_BASE + Math.max(0, Math.min(5, type));
		else if (kind.equals("heal")) lt = Loot.MEDKIT;
		else if (kind.equals("armor")) lt = Loot.VEST_1 + Math.max(0, Math.min(2, type - 1));
		else if (kind.equals("helmet")) lt = Loot.HELMET_1 + Math.max(0, Math.min(2, type - 1));
		else return null;
		Loot l = new Loot(lt, x, y, z, item);
		l.serverId = id;
		return l;
	}
	private static final int WEAPON_TYPE_BASE = Loot.WEAPON_BASE;

	/** Replaces the local loot with the server's list (online matches: all loot comes from the server). */
	public void loadServerLoot(java.util.List<Object> rows) {
		all.clear();
		if (rows == null) return;
		for (Object o : rows) {
			if (!(o instanceof java.util.Map)) continue;
			@SuppressWarnings("unchecked") java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
			Loot l = fromServer(m);
			if (l != null) all.add(l);
		}
	}
	public void addServerLoot(java.util.Map<String, Object> m) { Loot l = fromServer(m); if (l != null) all.add(l); }
	public void removeServerLoot(int id) { for (int i = 0; i < all.size(); i++) if (all.get(i).serverId == id) { all.get(i).taken = true; all.remove(i); return; } }

	/** Mirrors the authoritative inventory ({weapons:[3 ids|-1], cur, mags:{id:rounds}, ammo:[6], heals, armor, helmet}) into the local objects. */
	public void applyServerInventory(GameEngine eng, java.util.Map<String, Object> inv) {
		if (inv == null) return;
		Player p = eng.player;
		java.util.List<?> ws = inv.get("weapons") instanceof java.util.List ? (java.util.List<?>) inv.get("weapons") : null;
		java.util.List<?> am = inv.get("ammo") instanceof java.util.List ? (java.util.List<?>) inv.get("ammo") : null;
		java.util.Map<?, ?> mags = inv.get("mags") instanceof java.util.Map ? (java.util.Map<?, ?>) inv.get("mags") : null;
		int[] w = new int[WeaponManager.SLOTS];
		for (int i = 0; i < w.length; i++) { Object o = ws != null && i < ws.size() ? ws.get(i) : null; w[i] = o instanceof Number ? ((Number) o).intValue() : -1; }
		int[] pool = new int[Weapon.AMMO_NAMES.length];
		for (int i = 0; i < pool.length; i++) { Object o = am != null && i < am.size() ? am.get(i) : null; pool[i] = o instanceof Number ? ((Number) o).intValue() : 0; }
		int[] mg = new int[Weapon.COUNT];
		java.util.Arrays.fill(mg, -1);
		if (mags != null) for (java.util.Map.Entry<?, ?> e : mags.entrySet()) {
			try { int k = Integer.parseInt(String.valueOf(e.getKey())); if (k >= 0 && k < mg.length && e.getValue() instanceof Number) mg[k] = ((Number) e.getValue()).intValue(); } catch (NumberFormatException ignored) { }
		}
		eng.weapons.applyServer(w, (int) Json.num(inv, "cur", -1), mg, pool);
		java.util.Arrays.fill(p.heal, 0);
		p.heal[Loot.MEDKIT] = (int) Json.num(inv, "heals", 0);
		p.vestLevel = (int) Json.num(inv, "armor", 0);
		p.helmetLevel = (int) Json.num(inv, "helmet", 0);
	}

	/** Called every frame when PLAYING. Pickups are explicit taps, nothing happens automatically. */
	public void update(GameEngine eng) { }
}
