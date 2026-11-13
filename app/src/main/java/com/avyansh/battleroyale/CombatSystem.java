package com.avyansh.battleroyale;

public final class CombatSystem {
	private CombatSystem() {}

	/**
	 * Online: the server is authoritative. We send ONE fire message (origin at the player's eye, direction toward what the
	 * crosshair is over, so third-person parallax is handled) and the server validates rate/ammo/position, rewinds targets
	 * to what this client saw (lag compensation), applies spread/damage and answers with `hit` / `damaged` messages.
	 */
	private static void fireOnline(GameEngine eng, Weapon w) {
		GameCamera cam = eng.camera;
		Player p = eng.player;
		float ox = p.x, oy = p.y + 1.6f, oz = p.z;
		float range = Math.min(w.range, 200f);
		float t = CollisionSystem.rayTerrain(eng.world.generator, cam.pos[0], cam.pos[1], cam.pos[2], cam.fx, cam.fy, cam.fz, range, 1.5f);
		float d = t >= 0f ? t : range;
		float tx = cam.pos[0] + cam.fx * d, ty = cam.pos[1] + cam.fy * d, tz = cam.pos[2] + cam.fz * d;
		float dx = tx - ox, dy = ty - oy, dz = tz - oz;
		float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (l < 0.5f) { dx = cam.fx; dy = cam.fy; dz = cam.fz; } else { dx /= l; dy /= l; dz /= l; }
		ServerNet.sendFire(eng, ox, oy, oz, dx, dy, dz, w.type);
	}


	/** Hand-to-hand attack: short reach in front of the player, 35 damage, 0.7 s cooldown. */
	public static void melee(GameEngine eng) {
		Player pl = eng.player;
		if (!pl.alive || pl.swimming || pl.meleeCd > 0f || eng.match.phase != MatchPhase.PLAYING) return;
		pl.meleeT = 0.45f; pl.meleeCd = 0.7f;
		GameCamera cam = eng.camera;
		float fl = (float) Math.sqrt(cam.fx * cam.fx + cam.fz * cam.fz);
		if (fl < 1e-4f) return;
		float fx = cam.fx / fl, fz = cam.fz / fl;
		if (ServerNet.inServerMatch()) { ServerNet.sendMelee(fx, fz); return; }
		final float reach = 2.3f, dmg = 35f;
		Bot bestBot = null; NetPlayer bestNet = null; float best = reach;
		for (int i = 0; i < eng.bots.bots.size(); i++) {
			Bot b = eng.bots.bots.get(i);
			if (!b.alive) continue;
			float dx = b.x - pl.x, dz = b.z - pl.z, d = (float) Math.sqrt(dx * dx + dz * dz);
			if (d < best && d > 0.01f && (dx * fx + dz * fz) / d > 0.45f && Math.abs(b.y - pl.y) < 2f) { best = d; bestBot = b; bestNet = null; }
		}
		if (Net.active && Net.inMatch) {
			synchronized (Net.remotes) {
				for (int i = 0; i < Net.remotes.size(); i++) {
					NetPlayer n = Net.remotes.get(i);
					if (!n.alive || Net.isFriend(n)) continue;
					float dx = n.x - pl.x, dz = n.z - pl.z, d = (float) Math.sqrt(dx * dx + dz * dz);
					if (d < best && d > 0.01f && (dx * fx + dz * fz) / d > 0.45f && Math.abs(n.y - pl.y) < 2f) { best = d; bestNet = n; bestBot = null; }
				}
			}
		}
		if (bestBot != null) {
			bestBot.damage(dmg);
			if (eng.effects != null) eng.effects.blood(bestBot.x, bestBot.y + 1.2f, bestBot.z, fx, 0f, fz, 10, eng.world.ground(bestBot.x, bestBot.z));
			if (!bestBot.alive) { pl.kills++; eng.sound.play("kill"); Net.announce("You eliminated " + bestBot.name); } else eng.sound.play("hit");
		} else if (bestNet != null) {
			Net.sendDamage(bestNet.id, dmg, false);
			bestNet.hp -= (int) dmg;
			if (eng.effects != null) eng.effects.blood(bestNet.x, bestNet.y + 1.2f, bestNet.z, fx, 0f, fz, 10, eng.world.ground(bestNet.x, bestNet.z));
			if (bestNet.hp <= 0 && bestNet.alive) { bestNet.alive = false; pl.kills++; eng.sound.play("kill"); Net.announce("You eliminated " + bestNet.name); } else eng.sound.play("hit");
		}
	}

	public static void firePlayer(GameEngine eng) {
		Weapon w = eng.weapons.current();
        if(w==null) return;
		GameCamera cam = eng.camera;

		if (ServerNet.inServerMatch()) { fireOnline(eng, w); return; }

		boolean hitBot = false;
		boolean headshot = false;

		for (int pellet = 0; pellet < w.pellets; pellet++) {
			float dx = cam.fx, dy = cam.fy, dz = cam.fz;
			if (w.spread > 0f) {
			float spr = w.spread * (eng.scopeBlend > 0.9f ? GameSettings.SCOPE_SPREAD_MUL : 1f);
			float sx = ((float)Math.random() - 0.5f) * spr;
			float sy = ((float)Math.random() - 0.5f) * spr;
				dx += cam.rx * sx;
				dy += sy;
				dz += cam.rz * sx;
				float l = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
				if (l > 1e-5f) { dx/=l; dy/=l; dz/=l; }
			}

			float bestT = w.range;
			Bot target = null;
			boolean head = false;
			NetPlayer netTarget = null;
			for (int i = 0; i < eng.bots.bots.size(); i++) {
				Bot b = eng.bots.bots.get(i);
				if (!b.alive) continue;
				float t = CollisionSystem.rayCharacter(
				cam.pos[0], cam.pos[1], cam.pos[2],
				dx, dy, dz,
				b.x, b.y, b.z);
				if (t >= 0f && t < bestT) {
					bestT = t;
					target = b;
					head = CollisionSystem.lastHitHead;
				}
			}

			if (Net.active && Net.inMatch) {
				synchronized (Net.remotes) {
					for (int i = 0; i < Net.remotes.size(); i++) {
						NetPlayer n = Net.remotes.get(i);
						if (!n.alive || Net.isFriend(n)) continue; // dead players and teammates cannot be hit
						float t = CollisionSystem.rayCharacter(cam.pos[0], cam.pos[1], cam.pos[2], dx, dy, dz, n.x, n.y, n.z);
						if (t >= 0f && t < bestT) { bestT = t; netTarget = n; target = null; head = CollisionSystem.lastHitHead; }
					}
				}
			}
			float terr = CollisionSystem.rayTerrain(
			eng.world.generator,
			cam.pos[0], cam.pos[1], cam.pos[2],
			dx, dy, dz,
			Math.min(bestT, w.range), 1.5f);
			if (terr >= 0f && terr < bestT) { target = null; netTarget = null; }
			if (netTarget != null) {
				float dmgN = w.damage * (head ? 2.5f : 1f);
				Net.sendDamage(netTarget.id, dmgN, head);
				netTarget.hp -= (int) dmgN;
				float hx = cam.pos[0] + dx * bestT, hy = cam.pos[1] + dy * bestT, hz = cam.pos[2] + dz * bestT;
				if (eng.effects != null) eng.effects.blood(hx, hy, hz, dx, dy, dz, 15, eng.world.ground(hx, hz));
				if (netTarget.hp <= 0 && netTarget.alive) { netTarget.alive = false; eng.player.kills++; eng.sound.play("kill"); Net.announce("You eliminated " + netTarget.name); }
				else eng.sound.play("hit");
				hitBot = true; if (head) headshot = true;
			}

			if (target != null) {
				float dmg = w.damage * (head ? 2.5f : 1f);
				target.damage(dmg);

				float hitX = cam.pos[0] + dx * bestT;
				float hitY = cam.pos[1] + dy * bestT;
				float hitZ = cam.pos[2] + dz * bestT;
				float floor = eng.world.ground(hitX, hitZ);

				if (eng.effects != null) {
					eng.effects.blood(hitX, hitY, hitZ, dx, dy, dz, 15, floor);
				}

				if (!target.alive) {
					eng.player.kills++;
					eng.sound.play("kill");
					Net.announce("You eliminated " + target.name);
				} else {
					eng.sound.play("hit");
				}
				hitBot = true;
				if (head) headshot = true;
			}
		}

		GameRenderer r = eng.getRenderer();
		if (hitBot && r != null) {
			r.flashHitMarker(headshot);
		}
	}
}
