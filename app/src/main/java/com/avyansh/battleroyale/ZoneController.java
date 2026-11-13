package com.avyansh.battleroyale;

import java.util.Random;

public final class ZoneController {
	
	/** One stage of the match. */
	private static final class Stage {
		final float waitSec;
		final float shrinkSec;
		final float radius;
		final float damagePerSec;
		
		Stage(float waitSec, float shrinkSec, float radius, float damagePerSec) {
			this.waitSec = waitSec;
			this.shrinkSec = shrinkSec;
			this.radius = radius;
			this.damagePerSec = damagePerSec;
		}
	}
	
	private static final Stage[] STAGES = {
		new Stage(100f, 60f, 900f, 1.0f),
		new Stage( 60f, 45f, 560f, 2.0f),
		new Stage( 50f, 40f, 330f, 3.5f),
		new Stage( 40f, 35f, 180f, 5.5f),
		new Stage( 30f, 30f,  80f, 8.0f),
		new Stage( 20f, 25f,  18f, 12f),
		new Stage( 15f, 20f,   6f, 16f)
	};
	
	private static final float SAFE_MARGIN = 6f;
	private static final float BOT_DAMAGE_SCALE = 0.8f;
	private static final float BOT_REACT_SECONDS = 25f;
	
	private static final float MAX_NEXT_OFFSET_RATIO = 0.72f;
	private static final float MIN_NEXT_OFFSET_RATIO = 0.15f;
	
	private float cx, cz, radius;
	private float nextX, nextZ, nextRadius;
	
	private float fromX, fromZ, fromRadius;
	private float shrinkStartX, shrinkStartZ, shrinkStartRadius;
	
	private int stage;
	private float stageTime;
	private float matchTime;
	
	private boolean shrinking;
	private boolean finished;
	private boolean active;
	
	private boolean playerOutside;
	private boolean playerInDanger;
	private float flashTimer;
	private float warningPulse;
	
	private Random rng = new Random();
	
	// ------------------------------------------------------------------ lifecycle
	
	public void reset(long seed, float startRadius) {
		rng = new Random(seed ^ 0x2F0E5EEDL);
		serverDriven = false;
		
		cx = 0f;
		cz = 0f;
		radius = startRadius;
		
		stage = 0;
		stageTime = 0f;
		matchTime = 0f;
		
		shrinking = false;
		finished = false;
		active = true;
		
		playerOutside = false;
		playerInDanger = false;
		flashTimer = 0f;
		warningPulse = 0f;
		
		pickNextCircle();
	}
	
	public void stop() {
		active = false;
	}
	
	// ------------------------------------------------------------------ zone selection
	
	private void pickNextCircle() {
		Stage current = STAGES[stage];
		float safeRadius = Math.max(1f, radius - SAFE_MARGIN);
		nextRadius = Math.min(current.radius, safeRadius);
		
		float maxOffset = Math.max(0f, radius - nextRadius);
		float offset = maxOffset * (MIN_NEXT_OFFSET_RATIO + (MAX_NEXT_OFFSET_RATIO - MIN_NEXT_OFFSET_RATIO) * rng.nextFloat());
		
		float angle = rng.nextFloat() * (float) (Math.PI * 2.0);
		
		nextX = cx + (float) Math.cos(angle) * offset;
		nextZ = cz + (float) Math.sin(angle) * offset;
		
		// Keep the next circle fully inside the current playable area.
		float maxAllowedDistance = Math.max(0f, radius - nextRadius);
		float dx = nextX - cx;
		float dz = nextZ - cz;
		float distance = (float) Math.sqrt(dx * dx + dz * dz);
		
		if (distance > maxAllowedDistance && distance > 0f) {
			float scale = maxAllowedDistance / distance;
			nextX = cx + dx * scale;
			nextZ = cz + dz * scale;
		}
	}
	
	// ------------------------------------------------------------------ update
	
	// ------------------------------------------------------------------ server-driven mode (online matches)
	private boolean serverDriven;
	private float srvDps, srvShrinkStart, srvShrinkEnd, srvSec;
	
	/** Online matches: the dedicated server owns the zone and applies the damage. Called with the `zone` message. */
	public void applyServerZone(float cx, float cz, float r, float nx, float nz, float nr,
	int phase, float shrinkStartSec, float shrinkEndSec, float dps, float serverSec) {
		serverDriven = true; active = true; finished = false;
		this.cx = cx; this.cz = cz; this.radius = r;
		this.nextX = nx; this.nextZ = nz; this.nextRadius = nr;
		this.stage = Math.max(0, phase);
		this.srvShrinkStart = shrinkStartSec; this.srvShrinkEnd = shrinkEndSec; this.srvDps = dps; this.srvSec = serverSec;
		this.shrinking = serverSec >= shrinkStartSec && serverSec < shrinkEndSec;
	}
	
	/** Per-snapshot smooth zone circle from the server. */
	public void applyServerCircle(float cx, float cz, float r) { if (serverDriven) { this.cx = cx; this.cz = cz; this.radius = r; } }
	public boolean isServerDriven() { return serverDriven; }
	
	public void update(float dt, GameEngine eng) {
		if (!active) {
			return;
		}
		
		if (serverDriven) {
			matchTime += dt; srvSec += dt;
			shrinking = srvSec >= srvShrinkStart && srvSec < srvShrinkEnd;
			playerOutside = eng.player.alive && !inside(eng.player.x, eng.player.z);
			updateVisualState(dt);
			return;   // damage is applied by the server
		}
		matchTime += dt;
		advance(dt);
		updateVisualState(dt);
		applyDamage(dt, eng);
	}
	
	private void advance(float dt) {
		if (finished) {
			return;
		}
		
		stageTime += dt;
		Stage current = STAGES[stage];
		
		if (!shrinking) {
			if (stageTime >= current.waitSec) {
				shrinking = true;
				stageTime = 0f;
				
				fromX = cx;
				fromZ = cz;
				fromRadius = radius;
				
				shrinkStartX = cx;
				shrinkStartZ = cz;
				shrinkStartRadius = radius;
			}
			return;
		}
		
		float t = Math.min(1f, stageTime / current.shrinkSec);
		
		// Smooth ease-in/ease-out makes the wall movement feel less robotic.
		float eased = t * t * (3f - 2f * t);
		
		cx = fromX + (nextX - fromX) * eased;
		cz = fromZ + (nextZ - fromZ) * eased;
		radius = fromRadius + (nextRadius - fromRadius) * eased;
		
		if (t >= 1f) {
			shrinking = false;
			stageTime = 0f;
			
			cx = nextX;
			cz = nextZ;
			radius = nextRadius;
			
			if (stage + 1 < STAGES.length) {
				stage++;
				pickNextCircle();
			} else {
				finished = true;
			}
		}
	}
	
	private void updateVisualState(float dt) {
		warningPulse += dt;
		
		boolean outside = playerOutside;
		boolean closing = isShrinking() || secondsToShrink() <= 15f;
		
		playerInDanger = outside || closing;
	}
	
	private void applyDamage(float dt, GameEngine eng) {
		float dps = damagePerSecond();
		
		Player p = eng.player;
		playerOutside = p.alive && !inside(p.x, p.z);
		
		if (eng.match.phase == MatchPhase.PLAYING && playerOutside) {
			boolean wasAlive = p.alive;
			
			p.zoneDamage(dps * dt);
			eng.healing.onDamaged(dps);
			
			flashTimer -= dt;
			
			if (flashTimer <= 0f) {
				flashTimer = 1f;
				
				GameRenderer r = eng.getRenderer();
				if (r != null) {
					r.flashDamage();
				}
			}
			
			if (wasAlive && !p.alive) {
				Net.announce("The zone eliminated you");
			}
		}
		
		for (int i = 0; i < eng.bots.bots.size(); i++) {
			Bot b = eng.bots.bots.get(i);
			
			if (b.alive && !inside(b.x, b.z)) {
				b.damage(dps * BOT_DAMAGE_SCALE * dt);
			}
		}
	}
	
	// ------------------------------------------------------------------ queries
	
	public boolean isActive() {
		return active;
	}
	
	public boolean inside(float x, float z) {
		float dx = x - cx;
		float dz = z - cz;
		return dx * dx + dz * dz <= radius * radius;
	}
	
	public boolean isPlayerOutside() {
		return playerOutside;
	}
	
	public boolean isPlayerInDanger() {
		return playerInDanger;
	}
	
	public float damagePerSecond() {
		if (serverDriven) return srvDps;
		return STAGES[Math.min(stage, STAGES.length - 1)].damagePerSec;
	}
	
	public boolean shouldRelocate(float x, float z) {
		if (!active) {
			return false;
		}
		
		if (!inside(x, z)) {
			return true;
		}
		
		boolean closing = shrinking || (!finished && secondsToShrink() < BOT_REACT_SECONDS);
		
		if (!closing) {
			return false;
		}
		
		float dx = x - nextX;
		float dz = z - nextZ;
		float safeRadius = nextRadius - SAFE_MARGIN;
		
		return dx * dx + dz * dz > safeRadius * safeRadius;
	}
	
	public float centerX() {
		return cx;
	}
	
	public float centerZ() {
		return cz;
	}
	
	public float radius() {
		return radius;
	}
	
	public float targetX() {
		return nextX;
	}
	
	public float targetZ() {
		return nextZ;
	}
	
	public float targetRadius() {
		return nextRadius;
	}
	
	public boolean isShrinking() {
		return shrinking;
	}
	
	public boolean isFinished() {
		return finished;
	}
	
	public int stageNumber() {
		return Math.min(stage, STAGES.length - 1) + 1;
	}
	
	public float secondsToShrink() {
		if (serverDriven) return shrinking ? 0f : Math.max(0f, srvShrinkStart - srvSec);
		if (shrinking || finished) {
			return 0f;
		}
		
		return Math.max(0f, STAGES[stage].waitSec - stageTime);
	}
	
	public float secondsToFinishShrink() {
		if (serverDriven) return shrinking ? Math.max(0f, srvShrinkEnd - srvSec) : 0f;
		if (!shrinking) {
			return 0f;
		}
		
		return Math.max(0f, STAGES[stage].shrinkSec - stageTime);
	}
	
	public float distanceOutside(float x, float z) {
		float dx = x - cx;
		float dz = z - cz;
		return Math.max(0f, (float) Math.sqrt(dx * dx + dz * dz) - radius);
	}
	
	/** 0 = shrink just started, 1 = zone fully shrunk. */
	public float getShrinkProgress() {
		if (!shrinking) {
			return 0f;
		}
		
		float duration = STAGES[stage].shrinkSec;
		
		if (duration <= 0f) {
			return 1f;
		}
		
		return Math.min(1f, stageTime / duration);
	}
	
	/** 0 to 1 pulse value for HUD and renderer effects. */
	public float getWarningPulse() {
		return 0.5f + 0.5f * (float) Math.sin(warningPulse * 4.0);
	}
	
	/** True during the final stages, useful for extra-red zone visuals. */
	public boolean isFinalPhase() {
		return stage >= STAGES.length - 2;
	}
	
	/** Text for the HUD timer chip. */
	public String statusLine() {
		if (!active) {
			return "";
		}
		
		if (finished) {
			return "FINAL ZONE";
		}
		
		if (shrinking) {
			return "ZONE " + stageNumber() + "  •  SHRINKING " + clock(secondsToFinishShrink());
		}
		
		return "ZONE " + stageNumber() + "  •  SHRINKS IN " + clock(secondsToShrink());
	}
	
	private static String clock(float sec) {
		int s = Math.max(0, (int) Math.ceil(sec));
		return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
	}
}
