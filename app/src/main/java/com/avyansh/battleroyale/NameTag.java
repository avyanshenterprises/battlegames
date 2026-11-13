package com.avyansh.battleroyale;

/** Screen-space name tag of a character, produced by the renderer and drawn by GameActivity. */
public final class NameTag {
	public final String name;
	/** Position as a fraction of the screen (0..1). */
	public final float x, y;
	public final boolean friend;
	public final boolean bot;

	public NameTag(String name, float x, float y, boolean friend, boolean bot) {
		this.name = name; this.x = x; this.y = y; this.friend = friend; this.bot = bot;
	}
}
