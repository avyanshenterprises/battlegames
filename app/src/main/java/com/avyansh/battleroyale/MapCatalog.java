package com.avyansh.battleroyale;

/**
 * Maps selectable in rooms. The server only needs the id; the terrain of a map is generated from the shared world seed,
 * so adding a map means adding it here AND giving it its own seed in server/src/match.js (see README).
 */
public final class MapCatalog {
	private MapCatalog() {}
	private static final String[] IDS = { "island" };
	private static final String[] NAMES = { "Island" };
	public static String[] ids() { return IDS; }
	public static String name(String id) {
		for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
		return id;
	}
}
