package com.avyansh.battleroyale;

/**
 * Healing state machine for all PUBG healing items (bandage, first aid kit, medkit, painkiller, energy drink).
 * Engine calls update(dt); HUD calls tryStart() which automatically picks the most sensible item.
 */
public final class PlayerHealing {
	public static final int IDLE = 0;
	public static final int HEALING = 1;

	/** indexed by Loot.BANDAGE .. Loot.ENERGY_DRINK */
	public static final String[] NAMES = { "Bandage", "First Aid Kit", "Medkit", "Painkiller", "Energy Drink" };
	public static final float[] AMOUNT = { 15f, 55f, 100f, 30f, 25f };
	/** health the item cannot heal past (bandage / first aid stop at 75) */
	public static final float[] CAP = { 75f, 75f, 100f, 100f, 100f };
	public static final float[] TIME = { 2.5f, 4.0f, 6.0f, 4.0f, 3.5f };

	private int state = IDLE;
	private float remaining = 0f, duration = 1f;
	private float startHealth = 0f, gain = 0f, applied = 0f;
	private int item = -1;

	/** @return true if healing was started */
	public boolean tryStart(Player p, GameEngine eng) {
		if (state != IDLE || !p.alive) return false;
		int best = -1; float bestScore = -1e9f;
		for (int i = 0; i < NAMES.length; i++) {
			if (p.heal[i] <= 0 || p.health >= CAP[i] - 0.5f) continue;
			float g = Math.min(AMOUNT[i], CAP[i] - p.health);
			float score = g - 0.35f * (AMOUNT[i] - g);   // prefer the item that wastes the least
			if (score > bestScore) { bestScore = score; best = i; }
		}
		if (best < 0) {
			if (eng.sound != null) eng.sound.playPitched("hit", p.healCount() == 0 ? 0.6f : 0.5f);
			return false;
		}
		item = best;
		state = HEALING;
		duration = TIME[best];
		remaining = duration;
		startHealth = p.health;
		gain = Math.min(AMOUNT[best], CAP[best] - p.health);
		applied = 0f;
		if (eng.sound != null) eng.sound.play("medkit");
		return true;
	}

	public void cancel() {
		state = IDLE;
		remaining = 0f;
		item = -1;
	}

	public boolean isHealing() { return state == HEALING; }
	public String usingName() { return item >= 0 ? NAMES[item] : ""; }

	public float progress() {
		if (state != HEALING) return 0f;
		return 1f - remaining / Math.max(0.01f, duration);
	}

	/** Called by GameEngine when damage is applied to the player. */
	public void onDamaged(float amount) {
		if (state == HEALING && amount >= GameSettings.HEAL_INTERRUPT_DAMAGE) cancel();
	}

	/** Called by GameEngine when the player fires. */
	public void onFired() {
		if (state == HEALING) cancel();
	}

	public void update(float dt, Player p) {
		if (state != HEALING) return;
		if (!p.alive) { cancel(); return; }

		remaining -= dt;
		float frac = Math.min(1f, (duration - remaining) / duration);
		float target = gain * frac;
		float step = Math.max(0f, target - applied);
		applied += step;
		p.health = Math.min(p.maxHealth, p.health + step);

		if (remaining <= 0f) {
			p.health = Math.min(p.maxHealth, Math.max(p.health, startHealth + gain));
			if (item >= 0) p.heal[item] = Math.max(0, p.heal[item] - 1);
			state = IDLE;
			item = -1;
		}
	}
}
