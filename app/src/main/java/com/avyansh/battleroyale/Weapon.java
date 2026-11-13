package com.avyansh.battleroyale;

/**
 * PUBG-style arsenal. Every weapon is one row of {@link #DEFS}; the weapon "type" used everywhere
 * (network, meshes, loot) is simply the row index.
 */
public class Weapon {
	// categories
	public static final int CAT_AR = 0, CAT_SMG = 1, CAT_SHOTGUN = 2, CAT_SNIPER = 3, CAT_DMR = 4, CAT_LMG = 5, CAT_PISTOL = 6;
	public static final String[] CAT_NAMES = { "ASSAULT RIFLE", "SMG", "SHOTGUN", "SNIPER RIFLE", "DMR", "LMG", "PISTOL" };
	// ammo types
	public static final int AMMO_556 = 0, AMMO_762 = 1, AMMO_9 = 2, AMMO_12 = 3, AMMO_45 = 4, AMMO_300 = 5;
	public static final String[] AMMO_NAMES = { "5.56mm", "7.62mm", "9mm", "12 Gauge", ".45 ACP", ".300 Magnum" };
	/** rounds in one ammo pickup */
	public static final int[] AMMO_BOX = { 30, 30, 30, 10, 30, 10 };

	// weapon ids (first four keep the old numbering: AR, PISTOL, SHOTGUN, SNIPER)
	public static final int M416 = 0, P92 = 1, S12K = 2, AWM = 3, AKM = 4, SCAR = 5, GROZA = 6, M762 = 7,
		UZI = 8, UMP = 9, VECTOR = 10, TOMMY = 11, S686 = 12, S1897 = 13, KAR98 = 14, M24 = 15,
		SKS = 16, MINI14 = 17, SLR = 18, MK14 = 19, M249 = 20, DP28 = 21,
		P1911 = 22, R1895 = 23, R45 = 24, DEAGLE = 25;
	public static final int AR = M416, PISTOL = P92, SHOTGUN = S12K, SNIPER = AWM;
	public static final int COUNT = 26;

	public static final class Def {
		public final String name; public final int cat, ammo;
		public final float damage, interval, reload, spread, recoil, range;
		public final int pellets, mag, reserve; public final boolean auto;
		public final float zoom, pitch; public final String sound; public final int weight;
		Def(String name, int cat, int ammo, float damage, float interval, int mag, float reload, float spread,
			float recoil, float range, int pellets, boolean auto, float zoom, String sound, float pitch, int weight) {
			this.name = name; this.cat = cat; this.ammo = ammo; this.damage = damage; this.interval = interval;
			this.mag = mag; this.reload = reload; this.spread = spread; this.recoil = recoil; this.range = range;
			this.pellets = pellets; this.auto = auto; this.zoom = zoom; this.sound = sound; this.pitch = pitch;
			this.weight = weight; this.reserve = mag * 2;
		}
	}

	public static final Def[] DEFS = new Def[COUNT];
	static {
		//                         name        category     ammo       dmg   rate  mag  reload spread  recoil  range pel auto   zoom sound     pitch weight
		DEFS[M416]   = new Def("M416",        CAT_AR,      AMMO_556, 22f, 0.088f, 30, 2.1f, 0.012f, 0.0040f, 140f, 1, true,  2.4f, "ar",      1.00f, 10);
		DEFS[AKM]    = new Def("AKM",         CAT_AR,      AMMO_762, 30f, 0.105f, 30, 2.4f, 0.015f, 0.0058f, 140f, 1, true,  2.2f, "ar",      0.88f, 8);
		DEFS[SCAR]   = new Def("SCAR-L",      CAT_AR,      AMMO_556, 24f, 0.095f, 30, 2.2f, 0.011f, 0.0038f, 145f, 1, true,  2.4f, "ar",      1.05f, 7);
		DEFS[GROZA]  = new Def("Groza",       CAT_AR,      AMMO_762, 28f, 0.080f, 30, 2.3f, 0.011f, 0.0045f, 145f, 1, true,  2.4f, "ar",      0.95f, 2);
		DEFS[M762]   = new Def("Beryl M762",  CAT_AR,      AMMO_762, 29f, 0.088f, 30, 2.5f, 0.016f, 0.0066f, 140f, 1, true,  2.2f, "ar",      0.90f, 5);
		DEFS[UZI]    = new Def("Micro UZI",   CAT_SMG,     AMMO_9,   13f, 0.050f, 25, 1.9f, 0.020f, 0.0026f,  70f, 1, true,  1.5f, "pistol",  1.18f, 7);
		DEFS[UMP]    = new Def("UMP45",       CAT_SMG,     AMMO_45,  19f, 0.090f, 25, 2.0f, 0.014f, 0.0030f,  85f, 1, true,  1.6f, "pistol",  0.92f, 7);
		DEFS[VECTOR] = new Def("Vector",      CAT_SMG,     AMMO_45,  16f, 0.055f, 19, 1.8f, 0.016f, 0.0028f,  80f, 1, true,  1.6f, "pistol",  1.08f, 4);
		DEFS[TOMMY]  = new Def("Tommy Gun",   CAT_SMG,     AMMO_45,  18f, 0.085f, 30, 2.6f, 0.018f, 0.0034f,  80f, 1, true,  1.5f, "pistol",  0.85f, 4);
		DEFS[S686]   = new Def("S686",        CAT_SHOTGUN, AMMO_12,  11f, 0.280f,  2, 2.4f, 0.052f, 0.0280f,  40f, 9, false, 1.4f, "shotgun", 1.00f, 5);
		DEFS[S1897]  = new Def("S1897",       CAT_SHOTGUN, AMMO_12,  10f, 0.900f,  5, 2.8f, 0.055f, 0.0300f,  42f, 9, false, 1.4f, "shotgun", 0.92f, 4);
		DEFS[S12K]   = new Def("S12K",        CAT_SHOTGUN, AMMO_12,   8f, 0.350f,  5, 2.4f, 0.060f, 0.0240f,  40f, 8, false, 1.4f, "shotgun", 1.08f, 3);
		DEFS[KAR98]  = new Def("Kar98k",      CAT_SNIPER,  AMMO_762, 82f, 1.700f,  5, 3.0f, 0.0015f, 0.0420f, 320f, 1, false, 4.0f, "sniper", 0.95f, 5);
		DEFS[M24]    = new Def("M24",         CAT_SNIPER,  AMMO_762, 88f, 1.800f,  5, 3.2f, 0.0012f, 0.0440f, 330f, 1, false, 5.0f, "sniper", 1.00f, 3);
		DEFS[AWM]    = new Def("AWM",         CAT_SNIPER,  AMMO_300,110f, 2.000f,  5, 3.4f, 0.0010f, 0.0480f, 360f, 1, false, 6.0f, "sniper", 0.85f, 1);
		DEFS[SKS]    = new Def("SKS",         CAT_DMR,     AMMO_762, 38f, 0.120f, 10, 2.8f, 0.006f, 0.0120f, 220f, 1, false, 3.0f, "sniper", 1.30f, 4);
		DEFS[MINI14] = new Def("Mini 14",     CAT_DMR,     AMMO_556, 34f, 0.110f, 20, 2.6f, 0.006f, 0.0100f, 220f, 1, false, 3.0f, "ar",     1.20f, 4);
		DEFS[SLR]    = new Def("SLR",         CAT_DMR,     AMMO_762, 44f, 0.140f, 10, 2.9f, 0.006f, 0.0130f, 230f, 1, false, 3.0f, "sniper", 1.25f, 3);
		DEFS[MK14]   = new Def("Mk14 EBR",    CAT_DMR,     AMMO_762, 40f, 0.095f, 10, 3.0f, 0.007f, 0.0100f, 230f, 1, true,  3.0f, "sniper", 1.20f, 1);
		DEFS[M249]   = new Def("M249",        CAT_LMG,     AMMO_556, 20f, 0.072f,100, 5.0f, 0.020f, 0.0034f, 150f, 1, true,  2.2f, "ar",      0.80f, 1);
		DEFS[DP28]   = new Def("DP-28",       CAT_LMG,     AMMO_762, 24f, 0.100f, 47, 4.2f, 0.018f, 0.0044f, 150f, 1, true,  2.2f, "ar",      0.78f, 2);
		DEFS[P92]    = new Def("P92",         CAT_PISTOL,  AMMO_9,   20f, 0.170f, 15, 1.4f, 0.010f, 0.0060f,  80f, 1, false, 1.4f, "pistol",  1.00f, 6);
		DEFS[P1911]  = new Def("P1911",       CAT_PISTOL,  AMMO_45,  24f, 0.200f,  7, 1.5f, 0.010f, 0.0075f,  80f, 1, false, 1.4f, "pistol",  0.88f, 5);
		DEFS[R1895]  = new Def("R1895",       CAT_PISTOL,  AMMO_762, 46f, 0.850f,  7, 2.6f, 0.007f, 0.0200f,  95f, 1, false, 1.4f, "pistol",  0.70f, 3);
		DEFS[R45]    = new Def("R45",         CAT_PISTOL,  AMMO_45,  40f, 0.600f,  6, 2.4f, 0.008f, 0.0170f,  90f, 1, false, 1.4f, "pistol",  0.76f, 2);
		DEFS[DEAGLE] = new Def("Desert Eagle",CAT_PISTOL,  AMMO_45,  50f, 0.500f,  7, 2.0f, 0.009f, 0.0230f,  95f, 1, false, 1.4f, "pistol",  0.66f, 1);
	}

	public static final int[] CAT_COLORS = { 0xFF7CC4FF, 0xFFFFC857, 0xFFFF8A5B, 0xFFE57BFF, 0xFF7DFFB0, 0xFFFF6B6B, 0xFFCFD8DC };

	public final String name;
	public final int type, category, ammoType;
	public final float damage, interval, reloadTime, spread, recoil, range, zoom, firePitch;
	public final int pellets, magSize, startReserve;
	public final boolean auto;
	public final String fireSound;
	public int iconColor;

	public int ammo;
	/** Legacy field: ammo now lives in {@link WeaponManager#reserveOf(Weapon)} (shared per ammo type). */
	public int reserve;

	private Weapon(int type, Def d) {
		this.type = type;
		this.name = d.name; this.category = d.cat; this.ammoType = d.ammo;
		this.damage = d.damage; this.interval = d.interval; this.reloadTime = d.reload;
		this.spread = d.spread; this.recoil = d.recoil; this.range = d.range; this.zoom = d.zoom;
		this.firePitch = d.pitch; this.fireSound = d.sound;
		this.pellets = d.pellets; this.magSize = d.mag; this.startReserve = d.reserve; this.auto = d.auto;
		this.iconColor = CAT_COLORS[d.cat];
		refill();
	}

	public void refill() {
		ammo = magSize;
		reserve = startReserve;
	}

	public static Weapon create(int type) {
		if (type < 0 || type >= COUNT) type = M416;
		return new Weapon(type, DEFS[type]);
	}

	public static String nameOf(int type) { return type >= 0 && type < COUNT ? DEFS[type].name : "Weapon"; }
	public static int categoryOf(int type) { return type >= 0 && type < COUNT ? DEFS[type].cat : CAT_AR; }
	public static boolean isPistol(int type) { return categoryOf(type) == CAT_PISTOL; }

	/** Random weapon type, weighted by rarity. */
	public static int randomType(java.util.Random r) {
		int total = 0;
		for (Def d : DEFS) total += d.weight;
		int roll = r.nextInt(total);
		for (int i = 0; i < COUNT; i++) { roll -= DEFS[i].weight; if (roll < 0) return i; }
		return M416;
	}
}
