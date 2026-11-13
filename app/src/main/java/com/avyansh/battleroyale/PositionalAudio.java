package com.avyansh.battleroyale;

/**
 * 3D positional audio maths for gunshots: distance from real world coordinates, stereo pan from the bearing relative to the
 * listener's heading, realistic volume roll-off per weapon class, optional occlusion (terrain / walls), and the rule for the
 * on-screen gunfire direction indicator (only within {@value #INDICATOR_RANGE} m). Output feeds
 * {@code SoundPool.play(id, left, right, ...)}, so it works on every Android version. Pure Java.
 */
public final class PositionalAudio {
	private PositionalAudio() {}
	public static final float INDICATOR_RANGE = 200f;
	public static final float MAX_GUNSHOT_RANGE = 200f;

	public static final class Result {
		public float distance, gain, pan, left, right, bearingRelDeg, lowpass, rate;
		public boolean audible, indicator;
	}

	public interface Occluder {
		/** 1 = clear line, 0 = completely blocked */
		float clearness(float sx, float sy, float sz, float lx, float ly, float lz);
	}

	/** maximum audible distance (metres) by weapon category index of {@link Weapon} (AR,SMG,SHOTGUN,SNIPER,DMR,LMG,PISTOL) */
	public static final float[] RANGE_BY_CATEGORY = { 200f, 170f, 190f, 200f, 200f, 200f, 140f };
	/** relative loudness 0..1 at the reference distance */
	public static final float[] LOUDNESS_BY_CATEGORY = { 0.90f, 0.70f, 1.00f, 1.00f, 0.95f, 0.95f, 0.65f };

	public static float rangeOf(int weaponType) {
		int c = Weapon.categoryOf(weaponType);
		return RANGE_BY_CATEGORY[c];
	}

	/**
	 * @param listenerYawDeg heading of the listener (camera yaw: forward = (sin yaw, -cos yaw))
	 * @param occ optional occlusion probe (may be null)
	 */
	public static void compute(float lx, float ly, float lz, float listenerYawDeg, float sx, float sy, float sz,
	                           int weaponType, Occluder occ, Result out) {
		float dx = sx - lx, dy = sy - ly, dz = sz - lz;
		float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		out.distance = d;
		int cat = Weapon.categoryOf(weaponType);
		float range = RANGE_BY_CATEGORY[cat];
		out.indicator = d <= INDICATOR_RANGE;
		float bearing = (float) Math.toDegrees(Math.atan2(dx, -dz));
		float rel = AimAssist.wrap(bearing - listenerYawDeg);
		out.bearingRelDeg = rel;
		if (d >= range) { out.audible = false; out.gain = 0f; out.left = out.right = 0f; out.pan = 0f; out.lowpass = 1f; out.rate = 1f; return; }
		// roll-off: inverse-square-ish near field blended to a smooth zero at max range
		float n = d / range;
		float g = (1f / (1f + 14f * n * n)) * (1f - n * n);
		g *= LOUDNESS_BY_CATEGORY[cat];
		float clear = 1f;
		if (occ != null) clear = Math.max(0f, Math.min(1f, occ.clearness(sx, sy, sz, lx, ly, lz)));
		g *= 0.25f + 0.75f * clear;
		float horiz = (float) Math.sqrt(dx * dx + dz * dz);
		// pan from bearing; sources almost on top of us (or straight above) collapse to centre
		float panRaw = (float) Math.sin(Math.toRadians(rel));
		float centre = horiz < 3f ? horiz / 3f : 1f;
		float pan = panRaw * centre;
		// behind the listener sounds duller
		float behind = (float) Math.max(0.0, -Math.cos(Math.toRadians(rel)));
		out.lowpass = 1f - 0.45f * behind - 0.4f * (1f - clear);
		g *= 1f - 0.25f * behind;
		out.gain = g;
		out.pan = pan;
		// equal-power pan
		float a = (pan + 1f) * 0.25f * (float) Math.PI;
		out.left = g * (float) Math.cos(a);
		out.right = g * (float) Math.sin(a);
		// far shots are lower pitched / slower-sounding
		out.rate = 1f - 0.12f * n;
		out.audible = g > 0.004f;
	}

	/** Cheap terrain occluder: walks the segment and darkens the sound each time the ground rises above the line of sight. */
	public static final class TerrainOccluder implements Occluder {
		private final CameraRig.World w;
		public TerrainOccluder(CameraRig.World w) { this.w = w; }
		@Override public float clearness(float sx, float sy, float sz, float lx, float ly, float lz) {
			final int steps = 10;
			int blocked = 0;
			for (int i = 1; i < steps; i++) {
				float t = i / (float) steps;
				float x = sx + (lx - sx) * t, y = sy + (ly - sy) * t, z = sz + (lz - sz) * t;
				if (w.groundAt(x, z) > y + 0.3f) blocked++;
			}
			float c = 1f - blocked * 0.18f;
			if (w.segmentBlocked(sx, sy, sz, lx, ly, lz)) c -= 0.45f;     // walls / buildings
			return Math.max(0f, c);
		}
	}
}
