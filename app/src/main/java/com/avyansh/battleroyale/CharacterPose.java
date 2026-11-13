package com.avyansh.battleroyale;

/**
 * Smoothly blended animation state fed to ModelShader.
 *
 * ModelShader uniforms:
 *   uPoseA = (legA, legB, armA, armB)
 *   uPoseB = (armDown, aimBlend, 0, raise)
 * We keep the same interface so no shader change is required.
 */
public final class CharacterPose {
	public float phase;
	public float speed01;
	public float aim;
	public float crouch;
	public float air;
	public float deadT;

	public float legA, legB, armA, armB;
	public float armDown = 1f;
	public float aimBlend = 1f;
	public float raise;

	private float smoothAim = 0f;
	private float runBlend = 0f;
	private float crouchBlend = 0f;
	private float swimBlend = 0f;
	private float reloadBlend = 0f;

	public void update(float dt, float speed, boolean aimingPose, boolean crouching,
	boolean onGround, boolean alive, float pitchRad) {
		update(dt, speed, aimingPose, crouching, onGround, alive, pitchRad, false, false, false);
	}

	public void update(float dt, float speed, boolean aimingPose, boolean crouching,
	boolean onGround, boolean alive, float pitchRad,
	boolean swimming, boolean reloading, boolean running) {

		float tSpeed = Math.min(1f, speed / 8f);
		speed01 += (tSpeed - speed01) * Math.min(1f, dt * 10f);

		// Advance the walk cycle. Walk speed scales frequency so feet don't slide.
		if (speed > 0.1f) {
			phase += dt * speed * 2.8f;
		} else if (swimming) {
			phase += dt * 2.6f;   // tread water bob
		}
		if (phase > 6.2831853f * 8f) phase -= 6.2831853f * 8f;

		smoothAim  += ((aimingPose ? 1f : 0f) - smoothAim)  * Math.min(1f, dt * 9f);
		crouchBlend+= ((crouching  ? 1f : 0f) - crouchBlend)* Math.min(1f, dt * 10f);
		runBlend   += ((running    ? 1f : 0f) - runBlend)   * Math.min(1f, dt * 6f);
		swimBlend  += ((swimming   ? 1f : 0f) - swimBlend)  * Math.min(1f, dt * 6f);
		reloadBlend+= ((reloading  ? 1f : 0f) - reloadBlend)* Math.min(1f, dt * 8f);

		aim = smoothAim;
		crouch = crouchBlend;
		air += ((onGround ? 0f : 1f) - air) * Math.min(1f, dt * 8f);
		deadT = alive ? 0f : deadT + dt;

		float amp = 0.9f * (float) Math.sqrt(speed01);
		float s = (float) Math.sin(phase);

		legA = s * amp;
		legB = -s * amp;

		// Swim: frog kick
		if (swimBlend > 0.01f) {
			float kick = (float) Math.sin(phase * 1.6f);
			legA = legA * (1f - swimBlend) + (0.35f + kick * 0.5f) * swimBlend;
			legB = legB * (1f - swimBlend) + (0.35f - kick * 0.5f) * swimBlend;
		}

		// Jump / airborne pose
		legA = legA * (1f - air) + 0.55f * air;
		legB = legB * (1f - air) - 0.25f * air;

		// Arms swing opposite legs
		armA = -legA * 0.7f;
		armB = -legB * 0.7f;

		// Reload overrides arms into a raised pose
		if (reloadBlend > 0.01f) {
			armA = armA * (1f - reloadBlend) + 1.35f * reloadBlend;
			armB = armB * (1f - reloadBlend) + 1.55f * reloadBlend;
		}

		armDown = 1f;
		aimBlend = 1f;

		float bob = (float) Math.sin(phase * 2f) * 0.05f * speed01;
		float lowReady = -0.55f;
		float aimed = Math.max(-0.5f, Math.min(0.8f, pitchRad)) - 0.30f;
		raise = lowReady + (aimed - lowReady) * aim + bob + runBlend * 0.15f;
	}

	public float deathAngle() {
		if (deadT <= 0f) return 0f;
		float t = Math.min(1f, deadT / 0.7f);
		t = t * t * (3f - 2f * t);
		return 85f * t;
	}
}