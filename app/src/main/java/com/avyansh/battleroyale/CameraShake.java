package com.avyansh.battleroyale;

public final class CameraShake {
	private float yawAmp, pitchAmp, rollAmp;
	private float yawDecay, pitchDecay, rollDecay;
	private float t;
	private final float seedYaw, seedPitch, seedRoll;

	public CameraShake() {
		long s = System.nanoTime();
		seedYaw   = ((s * 1103515245L) & 0xFFFF) / 65535f * 6.2831853f;
		seedPitch = ((s * 214013L    + 2531011L) & 0xFFFF) / 65535f * 6.2831853f;
		seedRoll  = ((s * 48271L)    & 0xFFFF) / 65535f * 6.2831853f;
	}

	public void fire(float amplitude) {
		float decay = GameSettings.SHAKE_FIRE_DECAY;
		yawAmp   += amplitude * 0.55f;
		pitchAmp += amplitude * 0.85f;
		rollAmp  += amplitude * 0.20f;
		yawDecay = pitchDecay = rollDecay = decay;
	}

	public void hit(float amplitude) {
		float decay = GameSettings.SHAKE_HIT_DECAY;
		yawAmp   += amplitude * 0.60f;
		pitchAmp += amplitude * 0.60f;
		rollAmp  += amplitude * 0.90f;
		yawDecay = pitchDecay = rollDecay = decay;
	}

	public void land(float amplitude) {
		yawAmp   += amplitude * 0.15f;
		pitchAmp += amplitude * 1.10f;
		rollAmp  += amplitude * 0.20f;
		yawDecay = pitchDecay = rollDecay = 8.0f;
	}

	public void update(float dt) {
		t += dt;
		yawAmp   = decay(yawAmp,   yawDecay,   dt);
		pitchAmp = decay(pitchAmp, pitchDecay, dt);
		rollAmp  = decay(rollAmp,  rollDecay,  dt);
	}

	private static float decay(float a, float k, float dt) {
		if (a <= 0.0001f) return 0f;
		a -= a * k * dt;
		return a < 0.0001f ? 0f : a;
	}

	/** Degrees to add to yaw. */
	public float yawOffset() {
		return (float) (Math.sin(t * 41.3f + seedYaw) * yawAmp);
	}

	/** Degrees to add to pitch (smaller, so aim stays usable). */
	public float pitchOffset() {
		return (float) (Math.sin(t * 53.7f + seedPitch) * pitchAmp);
	}

	/** Roll in degrees for the view matrix (applied by GameEngine when building view). */
	public float rollOffset() {
		return (float) (Math.sin(t * 37.1f + seedRoll) * rollAmp);
	}
}
