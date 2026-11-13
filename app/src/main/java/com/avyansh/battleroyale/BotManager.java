package com.avyansh.battleroyale;

import java.util.ArrayList;

public class BotManager {
	/** A match has {@link #MATCH_SIZE} participants; bots fill every seat that no real player takes. */
	public static final int MATCH_SIZE = 50;
	public static final int DEFAULT_BOT_COUNT = MATCH_SIZE - 1;
	/** Bots further away than this are simulated at a lower rate. */
	private static final float FAR_DISTANCE = 140f;
	private static final int FAR_STRIDE = 4;
	
	/** Set by GameActivity before the match starts. */
	public int botCount = DEFAULT_BOT_COUNT;
	private int frame;
	public final ArrayList<Bot> bots = new ArrayList<>();
	private final TerrainGenerator tg;
	// BotManager.java - Add method
	public void setBotCount(int count) {
		this.botCount = Math.max(0, Math.min(count, MATCH_SIZE - 1));
	}
	
	public int getBotCount() { return botCount; }
	public BotManager() {
		this.tg = new TerrainGenerator(GameSettings.WORLD_SEED);
	}
	
	public void spawnAll(GameWorld world) { spawnAll(world, GameSettings.WORLD_SEED); }
	
	/** Spawns {@link #botCount} named bots; the same seed gives the same names and start positions on every device. */
	public void spawnAll(GameWorld world, long seed) {
		bots.clear();
		java.util.List<String> names = BotNames.pick(botCount, seed);
		java.util.Random r = new java.util.Random(seed ^ 0xBEEF);
		TerrainGenerator g = world.generator;
		for (int i = 0; i < botCount; i++) {
			float sx = 0f, sz = 0f;
			// The world is centred on (0,0). Pick dry, gentle ground that is not inside a wall.
			for (int tries = 0; tries < 80; tries++) {
				sx = (r.nextFloat() - 0.5f) * 1800f;
				sz = (r.nextFloat() - 0.5f) * 1800f;
				float h = g.heightAt(sx, sz);
				if (h > 3f && h < 40f && g.normalY(sx, sz) > 0.85f && !world.blocked(sx, sz, 1.2f)) break;
			}
			Bot b = new Bot();
			b.name = names.get(i);
			b.spawn(sx, sz, world);
			bots.add(b);
		}
	}
	
	public void update(float dt, GameEngine eng) {
		frame++;
		Player p = eng.player;
		for (int i = 0; i < bots.size(); i++) {
			Bot b = bots.get(i);
			float dx = b.x - p.x, dz = b.z - p.z;
			if (dx * dx + dz * dz < FAR_DISTANCE * FAR_DISTANCE) b.update(dt, eng);
			else if ((frame + i) % FAR_STRIDE == 0) b.update(dt * FAR_STRIDE, eng);   // distant bots: cheaper, coarser steps
		}
	}
	
	public int aliveCount() {
		int n = 0;
		for (int i = 0; i < bots.size(); i++) if (bots.get(i).alive) n++;
		return n;
	}
	
	/** Returns nearest living bot to a world point, or null. */
	public Bot nearest(float x, float z, float maxDist) {
		Bot best = null;
		float bestD2 = maxDist * maxDist;
		for (int i = 0; i < bots.size(); i++) {
			Bot b = bots.get(i);
			if (!b.alive) continue;
			float dx = b.x - x, dz = b.z - z;
			float d2 = dx*dx + dz*dz;
			if (d2 < bestD2) { bestD2 = d2; best = b; }
		}
		return best;
	}
}
