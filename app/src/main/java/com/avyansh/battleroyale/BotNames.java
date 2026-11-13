package com.avyansh.battleroyale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Pool of display names for AI opponents; every bot of a match gets a distinct one. */
public final class BotNames {
	private BotNames() { }

	private static final String[] POOL = {
		"Rogue Viper", "Shadow Fox", "Iron Wolf", "Neon Ghost", "Blaze X", "Silent Hawk", "Dark Mamba", "Cyber Tiger",
		"Storm Rider", "Night Owl", "Steel Falcon", "Red Panther", "Frost Bite", "Thunder Claw", "Ace Hunter", "Toxic Rain",
		"Ghost Recon", "Lone Wolf", "Savage King", "Venom Strike", "Pixel Ninja", "Crimson Fang", "Atomic Zed", "Rapid Fire",
		"Sniper Elite", "Desert Eagle", "Cobra Kai", "Hyper Nova", "Skull Crusher", "Shadow Reaper", "Blitz Krieg", "Mad Dog",
		"Phantom X", "Quick Silver", "Gold Rush", "Dragon Fly", "Bullet Storm", "Alpha Wolf", "Zero Cool", "Titan Slayer",
		"Raven Claw", "Juggernaut", "Echo Strike", "Hell Raiser", "Jet Black", "Wild Card", "Grim Hunter", "Lucky Seven",
		"Maverick", "Outlaw Jack", "Razor Edge", "Turbo Boss", "Viking Rage", "Panda Pro", "Ninja Cat", "Yeti Hunter",
		"Cold Blood", "Dusty Trail", "Rocket Man", "Sand Viper", "Tango Down", "Urban Legend", "Whisper", "Zombie Slayer"
	};

	/** Returns {@code count} distinct names in a seed-dependent order (same seed = same names on every device). */
	public static List<String> pick(int count, long seed) {
		List<String> all = new ArrayList<>(POOL.length);
		Collections.addAll(all, POOL);
		Collections.shuffle(all, new Random(seed));
		List<String> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			// more bots than pool entries: add a numeric suffix so names stay unique
			String base = all.get(i % all.size());
			out.add(i < all.size() ? base : base + " " + (i / all.size() + 1));
		}
		return out;
	}
}
