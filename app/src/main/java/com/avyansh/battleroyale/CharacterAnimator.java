package com.avyansh.battleroyale;

import java.util.HashMap;

public final class CharacterAnimator {
	private final GltfModel model;
	private final AnimationMixer mixer;
	private final HashMap<String, AnimationClip> byName = new HashMap<>();
	private final AnimationClip fallback;
	
	private AnimationClip current;
	private float currentTime = 0f;
	private float speed = 1f;
	private boolean loop = true;
	
	private AnimationClip prev;
	private float prevTime = 0f;
	private float blend = 0f;
	private float blendSpeed = 6f;
	
	// ---- hand IK (rifle hold) ----
	private int rU = -1, rL = -1, rH = -1, lU = -1, lL = -1, lH = -1;
	private boolean ikReady;
	private boolean holdOn;
	private float holdWeight = 1f;
	private final float[] tR = new float[3], tL = new float[3];
	private float aimBlend = 0f;
	private boolean hasRifleClips;
	private int frameCounter;
	private boolean prevOnGround = true;
	private float landTimer = 0f;
	private final HashMap<AnimationClip, Float> groundSpeed = new HashMap<>();
	private int fL = -1, fR = -1;
	private boolean evaluatedOnce;
	private boolean emoting;
	// ---- strafing / backwards / upper-body layers ----
	private int spine = -1, chest = -1;
	private boolean[] upperMask;
	private float moveRel, bodyTwist, bodyTwistTarget;
	private boolean reverse;
	private AnimationClip reloadClip, meleeClip, aimClip, strafeLeftClip, strafeRightClip, backwardClip;
	private float reloadProgress = -1f;
	private float frameDt = 1f / 60f;
	private boolean aimOverlayRequested;
	private float meleeTime = -1f;
	private final java.util.ArrayList<String> emoteList = new java.util.ArrayList<>();
	
	public CharacterAnimator(GltfModel model) {
		this.model = model;
		this.mixer = new AnimationMixer(model);
		if (model.clips != null) {
			for (AnimationClip c : model.clips) byName.put(norm(c.name), c);
		}
		this.fallback = pick(null);
		this.current = fallback;
		for (String k : byName.keySet()) {
			if (k.contains("rifle") || k.contains("gun") || k.contains("pistol")) { hasRifleClips = true; break; }
		}
		findArmBones();
		spine = findBone(new String[] { "spine", "spine0", "spine01", "spine1", "abdomen", "torso" }, new String[] { "neck", "head" });
		chest = findBone(new String[] { "spine2", "spine02", "chest", "upperchest", "spine3" }, new String[] { "neck", "head" });
		if (chest < 0) chest = spine;
		if (spine >= 0 && model.jointCount() > 0) { upperMask = new boolean[mixer.nodeCount()]; mixer.maskUnder(spine, upperMask); }
		for (String k : byName.keySet()) {
			if (reloadClip == null && k.contains("reload")) reloadClip = byName.get(k);
			if (meleeClip == null && (k.contains("melee") || k.contains("punch") || k.contains("slash") || k.contains("stab") || k.contains("kick") || k.contains("attack"))) meleeClip = byName.get(k);
			if (aimClip == null && (k.contains("aim") || k.contains("ads")) && !k.contains("walk") && !k.contains("run")) aimClip = byName.get(k);
			if (strafeLeftClip == null && k.contains("strafe") && (k.contains("left") || k.endsWith("l"))) strafeLeftClip = byName.get(k);
			if (strafeRightClip == null && k.contains("strafe") && (k.contains("right") || k.endsWith("r"))) strafeRightClip = byName.get(k);
			if (backwardClip == null && (k.contains("backwardwalk") || k.contains("walkback") || k.contains("backwardrun"))) backwardClip = byName.get(k);
		}
		fL = findBone(new String[] { "leftfoot", "footl", "lfoot" }, new String[] { "toe", "twist" });
		fR = findBone(new String[] { "rightfoot", "footr", "rfoot" }, new String[] { "toe", "twist" });
		String[] words = { "emote", "dance", "wave", "salute", "clap", "taunt", "cheer", "victory", "pose" };
		if (model.clips != null) {
			for (AnimationClip c : model.clips) {
				String k = norm(c.name);
				for (String w : words) if (k.contains(w)) { emoteList.add(c.name); break; }
			}
		}
	}
	
	// ------------------------------------------------------------------
	// emotes: any clip whose name contains emote / dance / wave / salute / clap / taunt / cheer ...
	// ------------------------------------------------------------------
	public String[] emoteNames() { return emoteList.toArray(new String[0]); }
	public boolean isEmoting() { return emoting; }
	public void cancelEmote() { emoting = false; }
	
	public void playEmote(String clipName) {
		AnimationClip c = byName.get(norm(clipName));
		if (c == null) return;
		prev = current;
		prevTime = currentTime;
		current = c;
		currentTime = 0f;
		blend = 0f;
		speed = 1f;
		String k = norm(c.name);
		loop = k.contains("loop") || k.contains("dance");
		emoting = true;
	}
	

	// ------------------------------------------------------------------
	// strafe / backwards: legs follow the movement direction, the torso keeps facing the aim direction
	// ------------------------------------------------------------------
	/** Radians the character's legs are turned away from its aim direction (renderer rotates the model by this). */
	public float bodyTwist() { return bodyTwist; }
	public int chestBone() { return chest; }
	public boolean hasMeleeClip() { return meleeClip != null; }

	/**
	 * @param rel movement direction relative to facing, radians (0 = forward, +PI/2 = right, PI = backwards)
	 * Call once per frame before selectFor*().
	 */
	public void setMoveRel(float rel, float speed, boolean grounded, float dt) {
		frameDt = clamp(dt, 1f / 240f, 0.1f);
		reverse = false;
		bodyTwistTarget = 0f;
		if (speed > 0.3f && grounded && !emoting) {
			float a = Math.abs(rel);
			if (a > 1.75f) {                       // > ~100 degrees: play the cycle backwards, legs turned the short way
				reverse = true;
				bodyTwistTarget = rel > 0f ? rel - (float) Math.PI : rel + (float) Math.PI;
			} else bodyTwistTarget = rel;
			bodyTwistTarget = clamp(bodyTwistTarget, -1.4f, 1.4f);
		}
		bodyTwist += (bodyTwistTarget - bodyTwist) * Math.min(1f, dt * 9f);
	}

	/** progress 0..1 while the weapon is being reloaded, otherwise -1. */
	public void setReloadProgress(float p) { reloadProgress = p; }

	/** Starts a melee swing (plays a melee clip if the model has one; otherwise the renderer animates the arms). */
	public void playMelee() { meleeTime = 0f; }
	public boolean isMeleeing() { return meleeTime >= 0f; }
	/** 0..1 through the swing, -1 when not swinging */
	public float meleeProgress() { return meleeTime < 0f ? -1f : Math.min(1f, meleeTime / 0.45f); }

	// ------------------------------------------------------------------
	// bones for the two-bone hand IK (works for Mixamo, Unreal, Blender rigs...)
	// ------------------------------------------------------------------
	private static String key(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; s != null && i < s.length(); i++) {
			char c = Character.toLowerCase(s.charAt(i));
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
		}
		return sb.toString();
	}
	
	private int findBone(String[] pats, String[] excl) {
		int best = -1, bestLen = 1 << 20;
		for (int i = 0; i < model.nodes.length; i++) {
			String n = key(model.nodes[i].name);
			boolean ok = false;
			for (String p : pats) if (n.endsWith(p)) { ok = true; break; }
			if (!ok) continue;
			for (String e : excl) if (n.contains(e)) { ok = false; break; }
			if (ok && n.length() < bestLen) { best = i; bestLen = n.length(); }
		}
		return best;
	}
	
	// CharacterAnimator.java - Replace findArmBones and related IK setup
	private void findArmBones() {
		String[] fingers = { "thumb", "index", "middle", "ring", "pinky", "little", "finger", "twist", "roll", "helper" };
		String[] armEx = { "fore", "lower", "twist", "roll", "shoulder", "clavicle", "helper", "hand" };
		String[] foreEx = { "twist", "roll", "helper", "hand" };
		
		rH = findBone(new String[] { "righthand", "handr", "rhand", "handright", "hand_r" }, fingers);
		lH = findBone(new String[] { "lefthand", "handl", "lhand", "handleft", "hand_l" }, fingers);
		rL = findBone(new String[] { "rightforearm", "forearmr", "rightlowerarm", "lowerarmr", "rforearm", "rlowerarm", "forearmright", "lowerarmright", "forearm_r" }, foreEx);
		lL = findBone(new String[] { "leftforearm", "forearml", "leftlowerarm", "lowerarml", "lforearm", "llowerarm", "forearmleft", "lowerarmleft", "forearm_l" }, foreEx);
		rU = findBone(new String[] { "rightarm", "armr", "rightupperarm", "upperarmr", "rarm", "rupperarm", "armright", "upperarmright", "upperarm_r", "arm_r" }, armEx);
		lU = findBone(new String[] { "leftarm", "arml", "leftupperarm", "upperarml", "larm", "lupperarm", "armleft", "upperarmleft", "upperarm_l", "arm_l" }, armEx);
		
		ikReady = model.jointCount() > 0 && rU >= 0 && rL >= 0 && rH >= 0 && lU >= 0 && lL >= 0 && lH >= 0;
		android.util.Log.i("CharacterAnimator", "arm IK " + (ikReady ? "ready" : "NOT available (bones not found)") +
		" rU=" + rU + " rL=" + rL + " rH=" + rH + " lU=" + lU + " lL=" + lL + " lH=" + lH);
	}
	
	/** Hands go to these MODEL-space points while 'on' (weight 0..1). */
	public void setHold(boolean on, float weight, float rx, float ry, float rz, float lx, float ly, float lz) {
		holdOn = on;
		holdWeight = weight;
		tR[0] = rx; tR[1] = ry; tR[2] = rz;
		tL[0] = lx; tL[1] = ly; tL[2] = lz;
	}
	
	public boolean ikReady() { return ikReady; }
	
	/** Smoothly moves between the low-ready pose and the shouldered aim pose. */
	public void stepAim(float dt, boolean aim) {
		float target = aim ? 1f : 0f;
		aimBlend += (target - aimBlend) * Math.min(1f, dt * 9f);
	}
	
	public float aimBlend() { return aimBlend; }
	
	public AnimationMixer mixer() { return mixer; }
	
	/** Real ground speed (m/s) of a locomotion clip, measured from the planted foot, so feet do not slide. */
	private float nativeSpeed(AnimationClip c, float fallback) {
		if (c == null || fL < 0 || fR < 0 || c.duration < 0.2f || model.height() < 0.1f) return fallback;
		Float cached = groundSpeed.get(c);
		if (cached != null) return cached;
		float res = fallback;
		try {
			int steps = 24;
			float dt = c.duration / steps;
			float[] w = null;
			double sum = 0; int cnt = 0;
			float prevZ = 0f; boolean prevL = false; boolean have = false;
			for (int i = 0; i <= steps; i++) {
				mixer.evaluate(c, Math.min(c.duration, i * dt));
				mixer.buildWorlds();
				w = mixer.nodeWorldMatrices();
				boolean useL = w[fL * 16 + 13] < w[fR * 16 + 13];
				float z = w[(useL ? fL : fR) * 16 + 14];
				if (have && useL == prevL) { sum += -(z - prevZ) / dt; cnt++; }
				prevZ = z; prevL = useL; have = true;
			}
			if (cnt > 3) {
				float v = (float) Math.abs(sum / cnt) * (1.85f / model.height());
				if (v > 0.4f && v < 14f) res = v;
			}
		} catch (Throwable ignored) {}
		groundSpeed.put(c, res);
		return res;
	}
	
	private boolean lastWasLoco;
	
	private static boolean isLoco(String d) {
		return "walk".equals(d) || "run".equals(d) || "sprint".equals(d) || "crouchwalk".equals(d);
	}
	
	public void setState(String desired, float speedScale, boolean looping) {
		AnimationClip next = pick(desired);
		if (next == null) return;
		boolean loco = isLoco(desired);
		if (next != current) {
			prev = current;
			prevTime = currentTime;
			current = next;
			currentTime = 0f;
			blend = 0f;
			// walk <-> run: keep the feet in the same phase of the step (no foot pop)
			if (loco && lastWasLoco && prev != null && prev.duration > 0.01f && next.duration > 0.01f) {
				currentTime = (prevTime / prev.duration) * next.duration;
			}
			blendSpeed = ("death".equals(desired) || "jump".equals(desired) || "fall".equals(desired)) ? 10f : 7f;
		}
		lastWasLoco = loco;
		this.speed = speedScale;
		this.loop = looping;
	}
	
	public void update(float dt) { update(dt, 1); }
	
	/** lodSkip = 1: full rate; 3: evaluate the pose every 3rd frame (far away characters). */
	public void update(float dt, int lodSkip) {
		if (current != null) {
			currentTime += dt * speed;
			if (loop && current.duration > 0.001f) currentTime = ((currentTime % current.duration) + current.duration) % current.duration;
			else if (!loop && currentTime > current.duration) currentTime = current.duration;
		}
		if (prev != null) {
			prevTime += dt * speed;
			if (loop && prev.duration > 0.001f) prevTime = ((prevTime % prev.duration) + prev.duration) % prev.duration;
			blend += dt * blendSpeed;
			if (blend >= 1f) { prev = null; blend = 1f; }
		}
		if (emoting && !loop && current != null && currentTime >= current.duration - 0.02f) emoting = false;
		frameCounter++;
		if (lodSkip > 1 && evaluatedOnce && (frameCounter % lodSkip) != 0) return;   // reuse last pose
		if (prev != null && blend < 1f) {
			mixer.evaluateBlend(prev, prevTime, current, currentTime, smoothstep(blend));
		} else if (current != null) {
			mixer.evaluate(current, currentTime);
		}
		// Upper-body overlays keep locomotion running while aiming/reloading/firing.
		if (upperMask != null) {
			if (aimOverlayRequested && aimClip != null && aimClip != current && aimBlend > 0.02f && aimClip.duration > 0.001f)
				mixer.overlay(aimClip, currentTime % aimClip.duration, upperMask, aimBlend);
			if (reloadClip != null && reloadProgress >= 0f && reloadProgress <= 1f && (reverse || bodyTwistTarget != 0f || lastWasLoco))
				mixer.overlay(reloadClip, reloadProgress * reloadClip.duration, upperMask, 1f);
			if (meleeTime >= 0f) {
				if (meleeClip != null) {
					meleeTime += dt;
					if (meleeTime >= meleeClip.duration) meleeTime = -1f;
					else mixer.overlay(meleeClip, meleeTime, upperMask, 1f);
				} else {
					meleeTime += dt;
					if (meleeTime > 0.45f) meleeTime = -1f;     // procedural swing is done by the renderer through the hand IK
				}
			}
		} else if (meleeTime >= 0f) { meleeTime += dt; if (meleeTime > 0.45f) meleeTime = -1f; }
		// torso keeps facing the aim direction while the legs turn toward the movement direction
		if (spine >= 0 && Math.abs(bodyTwist) > 0.01f) {
			mixer.buildWorlds();
			mixer.twistAboutY(spine, bodyTwist, 1f);
		}
		if (holdOn && ikReady && holdWeight > 0.01f) {
			boolean pz = model.frontPlusZ();
			float rx = pz ? -1f : 1f;                    // model-space "character right"
			mixer.buildWorlds();
			mixer.solveTwoBone(rU, rL, rH, tR[0], tR[1], tR[2], rx * 0.7f, -1f, 0f, holdWeight);
			mixer.solveTwoBone(lU, lL, lH, tL[0], tL[1], tL[2], -rx * 0.7f, -1f, 0f, holdWeight);
		}
		mixer.buildJointMatrices();
		evaluatedOnce = true;
	}
	
	private static float smoothstep(float t) { return t * t * (3f - 2f * t); }
	
	// Different GLB files name their clips differently ("Walking", "mixamo.com|Run", ...).
	private static final String[][] ALIASES = {
		{ "idle", "stand", "breath", "wait", "rest" },
		{ "walk" },
		{ "run", "sprint", "jog", "fastrun" },
		{ "jump", "leap" },
		{ "fall", "falling", "air" },
		{ "swim", "tread" },
		{ "crouchwalk", "sneak", "crouchwalking", "crouchedwalking", "walkingcrouched" },
		{ "crouch" },
		{ "land" },
		{ "aim", "rifle", "shoot", "fire" },
		{ "reload" },
		{ "death", "die", "dead", "dying" }
	};
	
	private AnimationClip lookup(String key) {
		// Mixamo style "Rifle Run", "Rifle Walk", ... win when the file has them
		if (hasRifleClips && !key.contains("rifle") && !key.equals("death")) {
			for (String k : byName.keySet()) {
				if ((k.contains("rifle") || k.contains("gun") || k.contains("pistol")) && k.contains(key)) return byName.get(k);
			}
		}
		AnimationClip c = byName.get(key);
		if (c != null) return c;
		for (String k : byName.keySet()) {
			if (k.contains(key) || (k.length() >= 3 && key.contains(k))) return byName.get(k);
		}
		for (String[] group : ALIASES) {
			boolean match = false;
			for (String w : group) if (key.contains(w) || w.contains(key)) { match = true; break; }
			if (!match) continue;
			for (String w : group) {
				for (String k : byName.keySet()) if (k.contains(w)) return byName.get(k);
			}
		}
		return null;
	}
	
	/** What to play when the model has no clip for a state. */
	private static String fallbackFor(String key) {
		if (key.equals("sprint")) return "run";
		if (key.equals("run")) return "walk";
		if (key.equals("walk")) return "run";
		if (key.equals("crouchwalk")) return "walk";
		if (key.equals("crouch") || key.equals("aim") || key.equals("reload") || key.equals("land")) return "idle";
		if (key.equals("jump") || key.equals("fall") || key.equals("swim")) return "idle";
		return null;
	}
	
	private AnimationClip pick(String desired) {
		if (desired != null) {
			String key = norm(desired);
			for (int guard = 0; guard < 3 && key != null; guard++) {
				AnimationClip c = lookup(key);
				if (c != null) return c;
				key = fallbackFor(key);
			}
		}
		for (String alias : new String[] { "idle", "stand", "breath" }) {
			AnimationClip c = lookup(alias);
			if (c != null) return c;
		}
		// no idle clip at all: use clip 0 as the fallback
		if (model.clips != null && !model.clips.isEmpty()) return model.clips.get(0);
		return null;
	}
	
	private static String norm(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = Character.toLowerCase(s.charAt(i));
			if (c != ' ' && c != '_' && c != '-') sb.append(c);
		}
		return sb.toString();
	}
	
	// Natural ground speed (m/s) of the usual Mixamo cycles; playback rate = real speed / this, so feet do not slide.
	private static final float WALK_NATIVE = 1.4f, RUN_NATIVE = 3.8f, SPRINT_NATIVE = 5.8f, CROUCH_NATIVE = 1.2f;
	
	private void locomotion(float spd, boolean armedAim, boolean forceSprint) {
		String st = forceSprint && spd > 3.4f ? "sprint" : (spd < 2.6f ? "walk" : (spd < 6.6f ? "run" : "sprint"));
		AnimationClip c = pick(st);
		float def = st.equals("walk") ? WALK_NATIVE : (st.equals("run") ? RUN_NATIVE : SPRINT_NATIVE);
		float nat = nativeSpeed(c, def);
		float sc = clamp(spd / nat, 0.9f, 1.1f);
		setState(st, reverse ? -sc : sc, true);   // normal 1x playback (negative = walking backwards)
	}
	
	private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
	
	public void selectForPlayer(Player p, boolean aiming, boolean reloading, boolean running) {
		aimOverlayRequested = aiming;
		if (emoting) {
			// moving, jumping, swimming, shooting or dying cancels the emote
			if (!p.alive || p.swimming || !p.onGround || p.speedNow > 0.5f || aiming || reloading) emoting = false;
			else return;
		}
		if (!p.alive) { setState("death", 1f, false); return; }
		if (p.swimming) { setState("swim", clamp(p.speedNow / 1.6f, 0.7f, 1.6f), true); return; }
		if (!prevOnGround && p.onGround && !p.swimming) landTimer = 0.35f;
		prevOnGround = p.onGround;
		if (landTimer > 0f && p.onGround) {
			landTimer -= frameDt;
			if (p.speedNow < 4f) { setState("land", 1f, false); return; }
		}
		if (!p.onGround) {
			if (p.vy > 0.5f) setState("jump", 1f, false);
			else             setState("fall", 1f, true);
			return;
		}
		float spd = p.speedNow;
		if (reloading && spd < 0.3f && !p.crouching) { setState("reload", 1f, false); return; }
		// reloading while moving: legs keep running/walking, the arms play the reload (overlay or weapon IK)
		if (p.crouching) {
			if (spd > 0.2f) { float cs = clamp(spd / nativeSpeed(pick("crouchwalk"), CROUCH_NATIVE), 0.9f, 1.1f); setState("crouchwalk", reverse ? -cs : cs, true); }
			else            setState("crouch", 1f, true);
			return;
		}
		if (spd < 0.3f) { setState(aiming ? "aim" : "idle", 1f, true); return; }
		float rel = Math.abs(p.moveRel);
		if (rel > 0.72f && rel < 2.35f) {
			AnimationClip side = p.moveRel > 0f ? strafeRightClip : strafeLeftClip;
			if (side != null) { setState(side.name, clamp(spd / nativeSpeed(side, 3.2f), 0.85f, 1.2f), true); return; }
		}
		if (reverse && backwardClip != null) { setState(backwardClip.name, clamp(spd / nativeSpeed(backwardClip, 2.8f), 0.85f, 1.2f), true); return; }
		locomotion(spd, aiming, running);
	}
	
	public void selectForNet(NetPlayer n) {
		aimOverlayRequested = n.aiming;
		if (!n.alive) { setState("death", 1f, false); return; }
		if (n.swim) { setState("swim", clamp(n.speedNow / 1.6f, 0.7f, 1.6f), true); return; }
		if (!n.onGround) { setState("fall", 1f, true); return; }
		float spd = n.speedNow;
		if (n.crouch) { if (spd > 0.2f) setState("crouchwalk", clamp(spd / nativeSpeed(pick("crouchwalk"), CROUCH_NATIVE), 0.9f, 1.1f), true); else setState("crouch", 1f, true); return; }
		if (spd < 0.3f) { setState(n.aiming ? "aim" : "idle", 1f, true); return; }
		locomotion(spd, n.aiming, false);
	}
	
	public void selectForBot(Bot b, boolean aiming) {
		aimOverlayRequested = aiming;
		if (!b.alive) { setState("death", 1f, false); return; }
		if (!b.onGround) { setState("fall", 1f, true); return; }
		float spd = b.speedNow;
		if (spd < 0.3f) { setState(aiming ? "aim" : "idle", 1f, true); return; }
		locomotion(spd, aiming, false);
	}
}
