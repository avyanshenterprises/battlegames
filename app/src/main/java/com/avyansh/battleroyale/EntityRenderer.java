package com.avyansh.battleroyale;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class EntityRenderer {
	private static final int FLOATS = MeshBuilder.FLOATS;
	private static final int STRIDE = FLOATS * 4;
	
	private final MeshBuilder mb = new MeshBuilder();
	private int vbo = 0;
	private final float[] identity = new float[16];
	private FloatBuffer scratch = ByteBuffer.allocateDirect(FLOATS * 4 * 30000).order(ByteOrder.nativeOrder()).asFloatBuffer();
	
	private final int[] tmpInt = new int[1];
	private final float[] charM = new float[16];
	private final float[] weaponM = new float[16];
	private CharacterAnimator playerAnimator;
	private CharacterAnimator[] botAnimators = new CharacterAnimator[256];
	private final java.util.HashMap<String, CharacterAnimator> netAnimators = new java.util.HashMap<>();
	
	private final CharacterPose playerPose = new CharacterPose();
	private float lastTime = -1f;
	private final CharacterPose[] botPoses = new CharacterPose[256];
	
	private TextSprite[] lootSprites = new TextSprite[20];
	private int[] lootSpriteColors = new int[20];
	
	public EntityRenderer() {
		for (int i = 0; i < botPoses.length; i++) botPoses[i] = new CharacterPose();
	}
	
	public void render(GameEngine eng, GameCamera cam, float time,
	int aPos, int aNormal, int aColor, int aUv,
	int uVP, int uModel, int uCamPos, int uLightDir, int uLightColor,
	int uAmbient, int uTex) {
		
		SkinShader.setFog(eng.fog, GameSettings.fogStart(), GameSettings.fogEnd());
		ModelShader.setFog(eng.fog, GameSettings.fogStart(), GameSettings.fogEnd());
		
		mb.vc = 0;
		GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, tmpInt, 0);
		SkinShader.restoreProgram = tmpInt[0];
		
		// real frame time for animations (was a fixed 1/60 -> too fast/slow off 60 fps)
		float adt = (lastTime < 0f) ? 0.016f : (time - lastTime);
		if (adt < 0.001f) adt = 0.001f;
		if (adt > 0.05f) adt = 0.05f;
		lastTime = time;
		
		// ---------- AIRPLANE ----------
		if (eng.match.phase == MatchPhase.AIRPLANE
		|| eng.match.phase == MatchPhase.FREEFALL
		|| eng.match.phase == MatchPhase.PARACHUTE) {
			Airplane p = eng.match.plane;
			if (!p.finished || p.progress() < 1.0f) {
				float dx = p.x - cam.x(), dy = p.y - cam.y(), dz = p.z - cam.z();
				if (dx * dx + dy * dy + dz * dz < 1500f * 1500f) {
					if (ModelCache.AIRPLANE_GLB != null && ModelCache.AIRPLANE_GLB.uploaded) {
						drawGlbStatic(ModelCache.AIRPLANE_GLB, p.x, p.y, p.z, p.dirX, p.dirZ, 1.0f,
						cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient);
					} else if (ModelCache.AIRPLANE != null && !ModelCache.AIRPLANE.isEmpty()) {
						drawObjModelWithTransform(ModelCache.AIRPLANE,
						p.x, p.y, p.z, p.dirX, p.dirZ, 1.0f,
						cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient,
						null, 0f);
					}
				}
			}
		}
		
		// ---------- PARACHUTE ----------
		if (eng.match.chuteOpen) {
			if (ModelCache.PARACHUTE_GLB != null && ModelCache.PARACHUTE_GLB.uploaded) {
				drawGlbParachute(ModelCache.PARACHUTE_GLB, eng.player.x, eng.player.y, eng.player.z,
				cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient);
			} else if (ModelCache.PARACHUTE != null && !ModelCache.PARACHUTE.isEmpty()) {
				float modelH = Math.max(0.001f, ModelCache.PARACHUTE.height());
				float scale = 6f / modelH;
				drawObjModelWithTransform(ModelCache.PARACHUTE,
				eng.player.x, eng.player.y, eng.player.z, 0f, 1f, scale,
				cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient, null, 0f);
			}
		}
		
		// ---------- PLAYER ----------
		if (eng.match.phase == MatchPhase.LOBBY
		|| eng.match.phase == MatchPhase.AIRPLANE
		|| eng.match.phase == MatchPhase.PLAYING
		|| eng.match.phase == MatchPhase.DEAD
		|| eng.match.phase == MatchPhase.WIN
		|| eng.match.phase == MatchPhase.FREEFALL
		|| eng.match.phase == MatchPhase.PARACHUTE) {
			
			Player pl = eng.player;
			// in the airplane the player is shown standing on the plane, facing its flight direction
			boolean inPlane = eng.match.phase == MatchPhase.AIRPLANE;
			float qx = pl.x, qy = pl.y, qz = pl.z, qyaw = pl.yaw;
			if (inPlane) {
				Airplane ap = eng.match.plane;
				qx = ap.x; qy = ap.y + ModelCache.PLANE_TOP; qz = ap.z;
				qyaw = (float) Math.atan2(ap.dirX, -ap.dirZ);
			}
			boolean isFPP = (eng.firstPerson || eng.scopeBlend > 0.85f)
			&& eng.match.phase == MatchPhase.PLAYING
			&& pl.alive;
			
			if (!isFPP) {
				boolean drawn = false;
				
				// GLB path
				if (ModelCache.PLAYER_GLB != null && ModelCache.PLAYER_GLB.uploaded) {
					if (playerAnimator == null) playerAnimator = new CharacterAnimator(ModelCache.PLAYER_GLB);
					if (eng.emoteNames.length == 0 && playerAnimator.emoteNames().length > 0) eng.emoteNames = playerAnimator.emoteNames();
					int er = eng.emoteRequest;
					if (er >= 0) {
						eng.emoteRequest = -1;
						String[] en = eng.emoteNames;
						if (er < en.length && pl.alive && pl.onGround && !pl.swimming) playerAnimator.playEmote(en[er]);
					}
					if (inPlane) { playerAnimator.cancelEmote(); playerAnimator.setState("idle", 1f, true); }
					else playerAnimator.selectForPlayer(pl, eng.aiming || eng.fireHeld, pl.reloading, eng.runOn);
					if (!inPlane) {
						playerAnimator.setMoveRel(pl.moveRel, pl.speedNow, pl.onGround, adt);
						playerAnimator.setReloadProgress(pl.reloading ? eng.weapons.reloadProgress() : -1f);
						if (pl.meleeT > 0.4f && !playerAnimator.isMeleeing()) playerAnimator.playMelee();
						backWeapon = -1;
						int cur = eng.weapons.currentIndex();
						for (int si = 0; si < WeaponManager.SLOTS && backWeapon < 0; si++) {
							Weapon sw = eng.weapons.slot(si);
							if (si != cur && sw != null) backWeapon = sw.type;
						}
					}
					drawGlbCharacter(ModelCache.PLAYER_GLB, playerAnimator,
					qx, qy, qz, qyaw,
					cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient,
					adt, 1, eng.weapons.current()==null ? -1 : eng.weapons.current().type, eng.aiming || eng.fireHeld, pl.reloading,
					pl.aimPitchRad, pl.alive && !pl.swimming && !playerAnimator.isEmoting());
					drawn = true;
				}
				
				// OBJ / procedural fallback
				if (!drawn && ModelCache.PLAYER != null && !ModelCache.PLAYER.isEmpty()) {
					playerPose.update(adt, pl.speedNow, eng.aiming || eng.fireHeld, pl.crouching,
					pl.onGround, pl.alive, pl.aimPitchRad,
					pl.swimming, pl.reloading, eng.runOn);
					float modelH = Math.max(0.001f, ModelCache.PLAYER.height());
					float scale = 1.85f / modelH;
					float yawRad = qyaw;
					float moveBob = pl.speedNow > 0.15f
					? (float) Math.abs(Math.sin(pl.animTime)) * 0.045f : 0f;
					float deathTilt = pl.alive ? 0f : playerPose.deathAngle();
					drawObjModelWithTransform(ModelCache.PLAYER,
					qx, qy + moveBob, qz,
					(float) Math.sin(yawRad), -(float) Math.cos(yawRad), scale,
					cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient,
					playerPose, deathTilt);
				}
			} else if (eng.scopeBlend < 0.5f) {
				drawFppWeapon(cam, aPos, aNormal, aColor, aUv,
				uVP, uModel, uCamPos, uLightDir, uLightColor, uAmbient, uTex);
			}
		}
		
		buildNameTags(eng, cam.vp);
		
		// ---------- LOBBY SQUAD: teammates stand next to the hero, name tags follow them ----------
		if (eng.match.phase == MatchPhase.LOBBY) {
			Player hp = eng.player;
			projectTag(eng, cam.vp, 0, hp.x, hp.y + LOBBY_TAG_HEIGHT, hp.z);
			String[] mates = eng.lobbyMates;
			if (mates != null && ModelCache.PLAYER_GLB != null && ModelCache.PLAYER_GLB.uploaded) {
				for (int i = 0; i < Math.min(3, mates.length); i++) {
					if (lobbyAnimators[i] == null) lobbyAnimators[i] = new CharacterAnimator(ModelCache.PLAYER_GLB);
					lobbyAnimators[i].setState("idle", 1f, true);
					float mx = hp.x + LOBBY_MATE_DX[i], mz = hp.z + (i == 2 ? -0.5f : 0f);
					float my = Math.max(eng.world.ground(mx, mz), 0f);
					float myaw = (float) Math.PI + (LOBBY_MATE_DX[i] < 0f ? -0.28f : 0.28f);
					drawGlbCharacter(ModelCache.PLAYER_GLB, lobbyAnimators[i], mx, my, mz, myaw,
					cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient, adt, 1, -1, false, false, 0f, true);
					projectTag(eng, cam.vp, i + 1, mx, my + LOBBY_TAG_HEIGHT, mz);
				}
			}
		}
		
		// ---------- BOTS ----------
		// EntityRenderer.java - Replace the BOTS section
		// ---------- BOTS ----------
		if (eng.match.phase == MatchPhase.PLAYING
		|| eng.match.phase == MatchPhase.DEAD
		|| eng.match.phase == MatchPhase.WIN) {
			for (int i = 0; i < eng.bots.bots.size(); i++) {
				Bot b = eng.bots.bots.get(i);
				if (b == null) continue;
				
				// Early distance check
				float dx = b.x - cam.x(), dz = b.z - cam.z();
				float d2 = dx * dx + dz * dz;
				if (d2 > 250f * 250f) continue;
				
				// Frustum cull using camera
				if (!cam.isBoxVisible(b.x - 1f, b.y - 0.1f, b.z - 1f,
				b.x + 1f, b.y + 2.2f, b.z + 1f)) continue;
				
				// Skip bots behind the camera when far
				if (d2 > 80f * 80f) {
					float hl = (float) Math.sqrt(cam.fx * cam.fx + cam.fz * cam.fz);
					float dist = (float) Math.sqrt(d2);
					if (hl > 0.2f && (dx * cam.fx + dz * cam.fz) / (dist * hl) < 0.1f) continue;
				}
				
				boolean drawn = false;
				if (ModelCache.BOT_GLB != null && ModelCache.BOT_GLB.uploaded && i < botAnimators.length) {
					// Aggressive LOD: full animation only close, static pose far
					int lod = d2 < 25f * 25f ? 1 : (d2 < 60f * 60f ? 2 : 6);
					if (botAnimators[i] == null) botAnimators[i] = new CharacterAnimator(ModelCache.BOT_GLB);
					botAnimators[i].selectForBot(b, false);
					drawGlbCharacter(ModelCache.BOT_GLB, botAnimators[i],
					b.x, b.y, b.z, b.yaw,
					cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient,
					adt, lod, b.weapon.type, b.speedNow < 0.5f, false, 0f, b.alive);
					drawn = true;
				}
				if (!drawn && ModelCache.BOT != null && !ModelCache.BOT.isEmpty()) {
					CharacterPose bp = botPoses[i < botPoses.length ? i : 0];
					bp.update(adt * 3f, b.speedNow, false, false, b.onGround, b.alive, 0f, false, false, false);
					float modelH = Math.max(0.001f, ModelCache.BOT.height());
					float scale = 1.85f / modelH;
					float deathTilt = b.alive ? 0f : bp.deathAngle();
					drawObjModelWithTransform(ModelCache.BOT,
					b.x, b.y, b.z,
					(float) Math.sin(b.yaw), -(float) Math.cos(b.yaw), scale,
					cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient,
					bp, deathTilt);
				}
			}
		}
		
		// ---------- REMOTE PLAYERS (online) ----------
		if (Net.active && Net.inMatch && (eng.match.phase == MatchPhase.PLAYING || eng.match.phase == MatchPhase.DEAD || eng.match.phase == MatchPhase.WIN)) {
			GltfModel nm = (ModelCache.BOT_GLB != null && ModelCache.BOT_GLB.uploaded) ? ModelCache.BOT_GLB : ModelCache.PLAYER_GLB;
			if (nm != null && nm.uploaded) synchronized (Net.remotes) {
				for (int i = 0; i < Net.remotes.size(); i++) {
					NetPlayer n = Net.remotes.get(i);
					float dx = n.x - cam.x(), dz = n.z - cam.z();
					float d2 = dx * dx + dz * dz;
					if (d2 > 300f * 300f) continue;
					CharacterAnimator an = netAnimators.get(n.id);
					if (an == null) { an = new CharacterAnimator(nm); netAnimators.put(n.id, an); }
					an.selectForNet(n);
					an.setMoveRel(n.moveRel, n.speedNow, n.onGround, adt);
					int lod = d2 < 40f * 40f ? 1 : (d2 < 90f * 90f ? 2 : 4);
					drawGlbCharacter(nm, an, n.x, n.y, n.z, n.yaw, cam.vp, cam.pos, eng.lightDir, eng.lightColor, eng.ambient,
					adt, lod, n.weapon, n.aiming || n.firing, false, n.pitch, n.alive && !n.swim);
				}
			}
		}
		
		// ---------- LOOT ----------
		// ═══ FIX: loot loop mein time pass karo ═══
		// Replace the LOOT section in render():
		// ---------- LOOT ----------
		for (int i = 0; i < eng.loot.all.size(); i++) {
			Loot l = eng.loot.all.get(i);
			if (l.taken) continue;
			float dx = l.x - cam.x(), dz = l.z - cam.z();
			if (dx * dx + dz * dz > 60f * 60f) continue;
			
			float bob = (float) Math.sin(time * 2.0f + i * 1.7f) * 0.12f;
			float y = l.y + 0.35f + bob;
			
			// ═══ FIX: time ko drawLootIcon mein pass karo ═══
			drawLootIcon(l, y, time, cam, eng);
		}
		
		if (mb.vc > 0) {
			Matrix.setIdentityM(identity, 0);
			GLES20.glUniformMatrix4fv(uModel, 1, false, identity, 0);
			GLES20.glUniformMatrix4fv(uVP, 1, false, cam.vp, 0);
			uploadAndDraw(aPos, aNormal, aColor, aUv);
		}
	}
	
	// ═══ FIX: method signature update karo ═══
	private void drawLootIcon(Loot l, float y, float time, GameCamera cam, GameEngine eng) {
		// Weapons use 3D model
		if (l.isWeapon()) {
			GltfModel groundGun = WeaponMeshes.get(l.weaponType());
			if (groundGun != null && groundGun.uploaded) {
				float[] gm = new float[16];
				Matrix.setIdentityM(gm, 0);
				Matrix.translateM(gm, 0, l.x, l.y + 0.10f, l.z);
				Matrix.rotateM(gm, 0, (float)(l.x * 37 + l.z * 17) % 360f, 0f, 1f, 0f);
				Matrix.rotateM(gm, 0, 15f, 1f, 0f, 0f);
				Matrix.scaleM(gm, 0, 0.85f, 0.85f, 0.85f);
				SkinShader.draw(groundGun, null, gm, null, cam.vp, cam.pos,
				eng.lightDir, eng.lightColor, eng.ambient);
			}
			return;
		}
		
		// Other items: colored pulsing box
		int color = LootManager.colorOf(l.type);
		float r = ((color >> 16) & 255) / 255f;
		float g = ((color >> 8) & 255) / 255f;
		float b = (color & 255) / 255f;
		
		float size = 0.20f;
		// ═══ FIX: time ab parameter hai, use ho sakta hai ═══
		float pulse = 1f + (float)Math.sin(time * 4f + l.x + l.z) * 0.1f;
		size *= pulse;
		
		mb.box(l.x - size, y - size * 0.4f, l.z - size,
		l.x + size, y + size * 0.6f, l.z + size,
		r * 0.3f, g * 0.3f, b * 0.3f);
		
		mb.box(l.x - size * 0.8f, y + size * 0.6f, l.z - size * 0.8f,
		l.x + size * 0.8f, y + size * 0.8f, l.z + size * 0.8f,
		r, g, b);
		
		float ringR = size * 1.4f;
		for (int seg = 0; seg < 8; seg++) {
			float a0 = seg * 0.7854f;
			float a1 = (seg + 1) * 0.7854f;
			float x0 = l.x + (float)Math.cos(a0) * ringR;
			float z0 = l.z + (float)Math.sin(a0) * ringR;
			float x1 = l.x + (float)Math.cos(a1) * ringR;
			float z1 = l.z + (float)Math.sin(a1) * ringR;
			mb.tri(x0, y - size * 0.5f, z0,
			x1, y - size * 0.5f, z1,
			x0, y - size * 0.4f, z0,
			r * 0.5f, g * 0.5f, b * 0.5f);
		}
	}
	
	private static final float TAG_RANGE_BOT = 55f, TAG_RANGE_PLAYER = 70f, TAG_HEIGHT = 2.15f;
	private static final int MAX_BOT_TAGS = 6;
	private final java.util.ArrayList<NameTag> tagList = new java.util.ArrayList<>();
	private final float[] tagScreen = new float[2];
	
	/** Projects a world point to the screen (0..1); false when it is behind the camera or off screen. */
	private boolean project(float[] vp, float wx, float wy, float wz) {
		tagIn[0] = wx; tagIn[1] = wy; tagIn[2] = wz; tagIn[3] = 1f;
		Matrix.multiplyMV(tagOut, 0, vp, 0, tagIn, 0);
		if (tagOut[3] <= 0.05f) return false;
		float sx = (tagOut[0] / tagOut[3]) * 0.5f + 0.5f, sy = 0.5f - (tagOut[1] / tagOut[3]) * 0.5f;
		if (sx < 0f || sx > 1f || sy < 0f || sy > 1f) return false;
		tagScreen[0] = sx; tagScreen[1] = sy;
		return true;
	}
	
	/** Name tags for teammates, nearby enemy players and the closest few bots (drawn by GameActivity). */
	private void buildNameTags(GameEngine eng, float[] vp) {
		tagList.clear();
		if (eng.match.phase == MatchPhase.PLAYING) {
			float px = eng.player.x, pz = eng.player.z;
			if (Net.active && Net.inMatch) synchronized (Net.remotes) {
				for (int i = 0; i < Net.remotes.size(); i++) {
					NetPlayer n = Net.remotes.get(i);
					if (!n.alive) continue;
					boolean friend = Net.isFriend(n);
					float dx = n.x - px, dz = n.z - pz;
					if (!friend && dx * dx + dz * dz > TAG_RANGE_PLAYER * TAG_RANGE_PLAYER) continue;
					if (project(vp, n.x, n.y + TAG_HEIGHT, n.z)) tagList.add(new NameTag(n.name, tagScreen[0], tagScreen[1], friend, false));
				}
			}
			// closest bots first
			java.util.ArrayList<Bot> near = new java.util.ArrayList<>();
			for (int i = 0; i < eng.bots.bots.size(); i++) {
				Bot b = eng.bots.bots.get(i);
				if (!b.alive) continue;
				float dx = b.x - px, dz = b.z - pz;
				if (dx * dx + dz * dz <= TAG_RANGE_BOT * TAG_RANGE_BOT) near.add(b);
			}
			final float fx = px, fz = pz;
			java.util.Collections.sort(near, new java.util.Comparator<Bot>() {
				@Override public int compare(Bot a, Bot c) {
					float da = (a.x - fx) * (a.x - fx) + (a.z - fz) * (a.z - fz);
					float dc = (c.x - fx) * (c.x - fx) + (c.z - fz) * (c.z - fz);
					return Float.compare(da, dc);
				}
			});
			int shown = 0;
			for (int i = 0; i < near.size() && shown < MAX_BOT_TAGS; i++) {
				Bot b = near.get(i);
				if (project(vp, b.x, b.y + TAG_HEIGHT, b.z)) { tagList.add(new NameTag(b.name, tagScreen[0], tagScreen[1], false, true)); shown++; }
			}
		}
		eng.nameTags = tagList.toArray(new NameTag[0]);
	}
	
	private final CharacterAnimator[] lobbyAnimators = new CharacterAnimator[3];
	private static final float[] LOBBY_MATE_DX = { -1.5f, 1.5f, -3.0f };
	/** Lobby name tags sit at knee level (metres above the feet). */
	private static final float LOBBY_TAG_HEIGHT = 0.45f;
	private final float[] tagIn = new float[4], tagOut = new float[4];
	
	private void projectTag(GameEngine eng, float[] vp, int idx, float wx, float wy, float wz) {
		tagIn[0] = wx; tagIn[1] = wy; tagIn[2] = wz; tagIn[3] = 1f;
		Matrix.multiplyMV(tagOut, 0, vp, 0, tagIn, 0);
		if (tagOut[3] <= 0.01f) { eng.lobbyTagX[idx] = -1f; return; }
		eng.lobbyTagX[idx] = (tagOut[0] / tagOut[3]) * 0.5f + 0.5f;
		eng.lobbyTagY[idx] = 0.5f - (tagOut[1] / tagOut[3]) * 0.5f;
	}
	
	private static final float[][] AMMO_RGB = {
		{ 0.30f, 0.62f, 0.30f }, { 0.85f, 0.50f, 0.15f }, { 0.92f, 0.80f, 0.20f },
		{ 0.82f, 0.20f, 0.18f }, { 0.25f, 0.45f, 0.85f }, { 0.55f, 0.25f, 0.75f } };
	
	/** World model of a non-weapon pickup (simple boxes, one silhouette per item kind). */
	private void drawLootItem(Loot l, float y) {
		int c = LootManager.colorOf(l.type);
		float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
		float x = l.x, z = l.z;
		switch (l.type) {
			case Loot.BANDAGE:
			mb.box(x - 0.16f, y - 0.06f, z - 0.16f, x + 0.16f, y + 0.06f, z + 0.16f, 0.92f, 0.92f, 0.90f);
			mb.box(x - 0.05f, y + 0.061f, z - 0.16f, x + 0.05f, y + 0.07f, z + 0.16f, 0.85f, 0.12f, 0.12f);
			return;
			case Loot.FIRST_AID:
			mb.box(x - 0.24f, y - 0.14f, z - 0.18f, x + 0.24f, y + 0.14f, z + 0.18f, 0.88f, 0.88f, 0.84f);
			mb.box(x - 0.045f, y + 0.141f, z - 0.15f, x + 0.045f, y + 0.15f, z + 0.15f, 0.85f, 0.12f, 0.12f);
			mb.box(x - 0.15f, y + 0.141f, z - 0.045f, x + 0.15f, y + 0.15f, z + 0.045f, 0.85f, 0.12f, 0.12f);
			return;
			case Loot.MEDKIT:
			mb.box(x - 0.30f, y - 0.18f, z - 0.24f, x + 0.30f, y + 0.18f, z + 0.24f, 0.78f, 0.78f, 0.72f);
			mb.box(x - 0.055f, y + 0.185f, z - 0.20f, x + 0.055f, y + 0.205f, z + 0.20f, 0.85f, 0.12f, 0.12f);
			mb.box(x - 0.20f, y + 0.185f, z - 0.055f, x + 0.20f, y + 0.205f, z + 0.055f, 0.85f, 0.12f, 0.12f);
			mb.box(x - 0.31f, y - 0.02f, z - 0.06f, x + 0.31f, y + 0.04f, z + 0.06f, 0.85f, 0.12f, 0.12f);
			return;
			case Loot.PAINKILLER:
			mb.box(x - 0.08f, y - 0.16f, z - 0.08f, x + 0.08f, y + 0.10f, z + 0.08f, 0.95f, 0.60f, 0.20f);
			mb.box(x - 0.06f, y + 0.10f, z - 0.06f, x + 0.06f, y + 0.16f, z + 0.06f, 0.95f, 0.95f, 0.95f);
			return;
			case Loot.ENERGY_DRINK:
			mb.box(x - 0.07f, y - 0.18f, z - 0.07f, x + 0.07f, y + 0.14f, z + 0.07f, 0.15f, 0.65f, 0.95f);
			mb.box(x - 0.06f, y + 0.14f, z - 0.06f, x + 0.06f, y + 0.17f, z + 0.06f, 0.80f, 0.82f, 0.85f);
			return;
			default: break;
		}
		if (l.isVest()) {
			mb.box(x - 0.19f, y - 0.22f, z - 0.08f, x + 0.19f, y + 0.20f, z + 0.08f, r * 0.8f, g * 0.8f, b * 0.8f);
			mb.box(x - 0.17f, y + 0.20f, z - 0.07f, x - 0.08f, y + 0.30f, z + 0.07f, r * 0.6f, g * 0.6f, b * 0.6f);
			mb.box(x + 0.08f, y + 0.20f, z - 0.07f, x + 0.17f, y + 0.30f, z + 0.07f, r * 0.6f, g * 0.6f, b * 0.6f);
			mb.box(x - 0.12f, y - 0.10f, z + 0.081f, x + 0.12f, y + 0.08f, z + 0.10f, r, g, b);
		} else if (l.isHelmet()) {
			mb.box(x - 0.24f, y - 0.02f, z - 0.20f, x + 0.24f, y + 0.18f, z + 0.20f, r, g, b);
			mb.box(x - 0.27f, y - 0.08f, z - 0.22f, x + 0.27f, y + 0.01f, z + 0.16f, r * 0.72f, g * 0.72f, b * 0.72f);
			mb.box(x - 0.20f, y - 0.12f, z + 0.12f, x + 0.20f, y - 0.04f, z + 0.24f, 0.10f, 0.12f, 0.15f);
		} else if (l.isBackpack()) {
			float k = 0.85f + 0.15f * l.level();
			mb.box(x - 0.22f * k, y - 0.22f * k, z - 0.16f * k, x + 0.22f * k, y + 0.24f * k, z + 0.16f * k, r, g, b);
			mb.box(x - 0.17f * k, y - 0.02f, z + 0.15f * k, x + 0.17f * k, y + 0.15f * k, z + 0.24f * k, r * 0.75f, g * 0.75f, b * 0.75f);
		} else if (l.isAmmo()) {
			float[] a = AMMO_RGB[l.ammoType()];
			mb.box(x - 0.24f, y - 0.14f, z - 0.18f, x + 0.24f, y + 0.14f, z + 0.18f, a[0] * 0.6f, a[1] * 0.6f, a[2] * 0.6f);
			mb.box(x - 0.20f, y + 0.141f, z - 0.14f, x + 0.20f, y + 0.17f, z + 0.14f, a[0], a[1], a[2]);
			mb.box(x - 0.05f, y + 0.171f, z - 0.10f, x + 0.05f, y + 0.19f, z + 0.10f, 0.85f, 0.72f, 0.30f);
		} else {
			mb.box(x - 0.28f, y - 0.20f, z - 0.22f, x + 0.28f, y + 0.20f, z + 0.22f, 0.12f, 0.30f, 0.42f);
		}
	}
	
	private static boolean glbFacesPlusZ(GltfModel m) {
		if (GameSettings.AUTO_DETECT_FACING && m.facingDetected) return m.facesPlusZ;
		return GameSettings.GLB_FACES_PLUS_Z;
	}
	
	// GLB character - scaled from its REAL bind-pose bounds so any GLB (cm, m, rotated rig...)
	// renders 1.85 m tall with its feet on the ground and its front facing the walking direction.
	// The character holds a 3D gun: both hands are put on the gun by two-bone IK.
	private void drawGlbCharacter(GltfModel model, CharacterAnimator animator,
	float px, float py, float pz, float yawRad,
	float[] vp, float[] camPos,
	float[] lightDir, float[] lightColor, float[] ambient,
	float dt, int lodSkip, int weaponType, boolean aiming, boolean reloading,
	float aimPitch, boolean armed) {
		
		float targetHeight = 1.85f;
		float scale = 1f, offX = 0f, offY = 0f, offZ = 0f;
		float minY = 0f;
		if (model.hasBounds && model.height() > 1e-4f) {
			scale = targetHeight / model.height();
			offY = -model.minY * scale;
			minY = model.minY;
			if (model.jointCount() == 0) {          // static model: centre it on the player
				offX = -(model.minX + model.maxX) * 0.5f * scale;
				offZ = -(model.minZ + model.maxZ) * 0.5f * scale;
			}
		}
		if (Float.isNaN(scale) || Float.isInfinite(scale) || scale <= 0f) scale = 1f;
		boolean plusZ = model.frontPlusZ();
		float faceFix = plusZ ? 180f : 0f;
		
		// ---------------- weapon pose (metres, character frame) ----------------
		// EntityRenderer.java - Replace the weapon pose section in drawGlbCharacter
		// ---------------- weapon pose (metres, character frame) ----------------
		GltfModel gun = armed ? WeaponMeshes.get(weaponType) : null;
		// Aim blend drives both weapon placement and optional upper-body aim animation, even when a weapon mesh is missing.
		animator.stepAim(dt, aiming);
		boolean hold = gun != null && GameSettings.HAND_IK && animator.ikReady() && model.jointCount() > 0;
		float sinY = (float) Math.sin(yawRad), cosY = (float) Math.cos(yawRad);
		float rtX = cosY, rtZ = sinY;           // character right (world)
		float fwX = sinY, fwZ = -cosY;          // character forward (world)
		float gx = 0, gy = 0, gz = 0, Xx = 0, Xy = 0, Xz = 0, Yx = 0, Yy = 0, Yz = 0, Zx = 0, Zy = 0, Zz = 0;
		
		if (gun != null) {
			float b = animator.aimBlend();
			
			// PUBG-style weapon position: closer to body, right hand on grip
			float wx = 0.08f + (0.12f - 0.08f) * b;     // right of body
			float wy = 1.15f + (1.35f - 1.15f) * b;     // shoulder height when aiming
			float wz = 0.15f + (0.25f - 0.15f) * b;     // forward
			
			// Pitch weapon with aim
			float pitch = -0.25f + (aimPitch + 0.25f) * b;
			if (reloading) { wy -= 0.15f; pitch = -0.55f; }
			if (Weapon.isPistol(weaponType)) { wz += 0.08f; wy -= 0.05f; }
			float mp = animator.meleeProgress();
			if (mp >= 0f) {                                   // gun-butt strike: pull back, then thrust forward
				float sw = (float) Math.sin(mp * Math.PI);
				wz += (mp < 0.35f ? -0.15f * (mp / 0.35f) : 0.55f * sw);
				wy -= 0.12f * sw; pitch += 0.5f * sw;
			}
			
			float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
			
			// Weapon basis: X = character LEFT, Y = up, Z = barrel (forward pitched)
			Xx = -rtX; Xy = 0f; Xz = -rtZ;
			Zx = fwX * cp; Zy = sp; Zz = fwZ * cp;
			Yx = -fwX * sp; Yy = cp; Yz = -fwZ * sp;
			
			gx = px + rtX * wx + fwX * wz;
			gy = py + wy;
			gz = pz + rtZ * wx + fwZ * wz;
			
			if (hold) {
				float[] gr = WeaponMeshes.GRIP_R[weaponType], gl = WeaponMeshes.GRIP_L[weaponType];
				
				// Right hand on grip - offset adjusted for natural hold
				float rx = gx + Xx * gr[0] + Yx * gr[1] + Zx * gr[2];
				float ry = gy + Xy * gr[0] + Yy * gr[1] + Zy * gr[2];
				float rz = gz + Xz * gr[0] + Yz * gr[1] + Zz * gr[2];
				
				// Left hand on foregrip - support hand
				float lx = gx + Xx * gl[0] + Yx * gl[1] + Zx * gl[2];
				float ly = gy + Xy * gl[0] + Yy * gl[1] + Zy * gl[2];
				float lz = gz + Xz * gl[0] + Yz * gl[1] + Zz * gl[2];
				
				// world -> MODEL space
				float mrx = plusZ ? -1f : 1f, mfz = plusZ ? 1f : -1f;
				float[] t = tmp6;
				toModel(rx - px, ry - py, rz - pz, rtX, rtZ, fwX, fwZ, mrx, mfz, scale, minY, t, 0);
				toModel(lx - px, ly - py, lz - pz, rtX, rtZ, fwX, fwZ, mrx, mfz, scale, minY, t, 3);
				
				// Stronger IK weight for better grip
				animator.setHold(true, 1f, t[0], t[1], t[2], t[3], t[4], t[5]);
			}
		} else {
			animator.setHold(false, 0f, 0, 0, 0, 0, 0, 0);
		}
		if (!hold) animator.setHold(false, 0f, 0, 0, 0, 0, 0, 0);
		animator.update(dt, lodSkip);
		
		// ---------------- body ----------------
		float[] m = charM;
		Matrix.setIdentityM(m, 0);
		Matrix.translateM(m, 0, px, py, pz);
		Matrix.rotateM(m, 0, -(float) Math.toDegrees(yawRad) + GameSettings.CHARACTER_YAW_OFFSET_DEG + faceFix, 0f, 1f, 0f);
		Matrix.translateM(m, 0, offX, offY, offZ);
		Matrix.scaleM(m, 0, scale, scale, scale);
		float[] joints = model.jointCount() > 0 ? animator.mixer().getJointMatrices() : null;
		SkinShader.draw(model, null, m, joints, vp, camPos, lightDir, lightColor, ambient);
		
		// ---------------- second weapon slung on the back ----------------
		if (backWeapon >= 0 && armed) {
			GltfModel bg = WeaponMeshes.get(backWeapon);
			if (bg != null) {
				float ux = 0.30f * rtX, uy = 0.95f, uz = 0.30f * rtZ;           // barrel direction: up, tilted to the right
				float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
				float bzx = ux / ul, bzy = uy / ul, bzz = uz / ul;
				float byx = -fwX, byy = 0f, byz = -fwZ;                          // weapon "up" faces away from the back
				float bxx = byy * bzz - byz * bzy, bxy = byz * bzx - byx * bzz, bxz = byx * bzy - byy * bzx;
				float[] w = weaponM;
				w[0] = bxx; w[1] = bxy; w[2] = bxz; w[3] = 0f;
				w[4] = byx; w[5] = byy; w[6] = byz; w[7] = 0f;
				w[8] = bzx; w[9] = bzy; w[10] = bzz; w[11] = 0f;
				w[12] = px - fwX * 0.22f - bzx * 0.30f; w[13] = py + 1.25f - bzy * 0.30f; w[14] = pz - fwZ * 0.22f - bzz * 0.30f; w[15] = 1f;
				SkinShader.draw(bg, null, w, null, vp, camPos, lightDir, lightColor, ambient);
			}
			backWeapon = -1;
		}
		// ---------------- gun in the hands ----------------
		if (gun != null) {
			float[] w = weaponM;
			w[0] = Xx; w[1] = Xy; w[2] = Xz; w[3] = 0f;
			w[4] = Yx; w[5] = Yy; w[6] = Yz; w[7] = 0f;
			w[8] = Zx; w[9] = Zy; w[10] = Zz; w[11] = 0f;
			w[12] = gx; w[13] = gy; w[14] = gz; w[15] = 1f;
			SkinShader.draw(gun, null, w, null, vp, camPos, lightDir, lightColor, ambient);
		}
	}
	
	private final float[] tmp6 = new float[6];
	/** weapon type slung on the back for the next drawGlbCharacter call (-1 = none) */
	private int backWeapon = -1;
	
	private static void toModel(float dx, float dy, float dz, float rtX, float rtZ, float fwX, float fwZ,
	float mrx, float mfz, float scale, float minY, float[] out, int o) {
		float xr = dx * rtX + dz * rtZ;      // metres to the character's right
		float zf = dx * fwX + dz * fwZ;      // metres forward
		out[o]     = (mrx * xr) / scale;
		out[o + 1] = minY + dy / scale;
		out[o + 2] = (mfz * zf) / scale;
	}
	
	// Static GLB (airplane): fitted to a target size, centred, nose along the flight direction.
	private void drawGlbStatic(GltfModel model, float px, float py, float pz,
	float dirX, float dirZ, float scaleMul,
	float[] vp, float[] camPos,
	float[] lightDir, float[] lightColor, float[] ambient) {
		float fit = 1f, cx = 0f, cy = 0f, cz = 0f;
		if (model.hasBounds) {
			float ext = Math.max(model.maxX - model.minX, model.maxZ - model.minZ);
			if (ext > 1e-4f && GameSettings.AIRPLANE_TARGET_SIZE > 0f) fit = GameSettings.AIRPLANE_TARGET_SIZE / ext;
			cx = (model.minX + model.maxX) * 0.5f;
			cy = (model.minY + model.maxY) * 0.5f;
			cz = (model.minZ + model.maxZ) * 0.5f;
		}
		float sc = fit * scaleMul;
		float yawDeg = (float) Math.toDegrees(Math.atan2(-dirX, -dirZ)) + GameSettings.AIRPLANE_YAW_OFFSET_DEG
		+ (GameSettings.GLB_FACES_PLUS_Z ? 180f : 0f);
		float[] m = new float[16];
		Matrix.setIdentityM(m, 0);
		Matrix.translateM(m, 0, px, py, pz);
		Matrix.rotateM(m, 0, yawDeg, 0f, 1f, 0f);
		Matrix.scaleM(m, 0, sc, sc, sc);
		Matrix.translateM(m, 0, -cx, -cy, -cz);
		SkinShader.draw(model, null, m, null, vp, camPos, lightDir, lightColor, ambient);
	}
	
	// Parachute GLB: ~6 m tall, canopy above the player.
	private void drawGlbParachute(GltfModel model, float px, float py, float pz,
	float[] vp, float[] camPos,
	float[] lightDir, float[] lightColor, float[] ambient) {
		float scale = 1f, offX = 0f, offY = 0f, offZ = 0f;
		if (model.hasBounds && model.height() > 1e-4f) {
			scale = 6f / model.height();
			offX = -(model.minX + model.maxX) * 0.5f * scale;
			offY = -model.minY * scale;
			offZ = -(model.minZ + model.maxZ) * 0.5f * scale;
		}
		float[] m = new float[16];
		Matrix.setIdentityM(m, 0);
		Matrix.translateM(m, 0, px, py, pz);
		Matrix.translateM(m, 0, offX, offY, offZ);
		Matrix.scaleM(m, 0, scale, scale, scale);
		SkinShader.draw(model, null, m, null, vp, camPos, lightDir, lightColor, ambient);
	}
	
	// FPP weapon viewmodel
	private void drawFppWeapon(GameCamera cam,
	int aPos, int aNormal, int aColor, int aUv,
	int uVP, int uModel, int uCamPos, int uLightDir, int uLightColor,
	int uAmbient, int uTex) {
		mb.setCenter(cam.pos[0], cam.pos[1], cam.pos[2]);
		float fwd = 0.55f, side = 0.22f, down = -0.20f;
		float gx = cam.pos[0] + cam.fx * fwd + cam.rx * side + cam.ux * down;
		float gy = cam.pos[1] + cam.fy * fwd + cam.uy * down;
		float gz = cam.pos[2] + cam.fz * fwd + cam.rz * side + cam.uz * down;
		mb.box(gx - 0.05f, gy - 0.05f, gz - 0.22f,
		gx + 0.05f, gy + 0.05f, gz + 0.22f, 0.14f, 0.14f, 0.16f);
		mb.box(gx - 0.02f, gy - 0.03f, gz + 0.20f,
		gx + 0.02f, gy + 0.02f, gz + 0.42f, 0.08f, 0.08f, 0.09f);
		float hx = cam.pos[0] + cam.fx * (fwd - 0.10f) + cam.rx * (side - 0.08f) + cam.ux * (down - 0.10f);
		float hy = cam.pos[1] + cam.fy * (fwd - 0.10f) + cam.uy * (down - 0.10f);
		float hz = cam.pos[2] + cam.fz * (fwd - 0.10f) + cam.rz * (side - 0.08f) + cam.uz * (down - 0.10f);
		mb.box(hx - 0.06f, hy - 0.06f, hz - 0.06f,
		hx + 0.06f, hy + 0.06f, hz + 0.06f, 0.90f, 0.72f, 0.58f);
		Matrix.setIdentityM(identity, 0);
		GLES20.glUniformMatrix4fv(uModel, 1, false, identity, 0);
		GLES20.glUniformMatrix4fv(uVP, 1, false, cam.vp, 0);
		uploadAndDraw(aPos, aNormal, aColor, aUv);
	}
	
	private void uploadAndDraw(int aPos, int aNormal, int aColor, int aUv) {
		int need = mb.vc * FLOATS;
		if (need > scratch.capacity()) {
			scratch = ByteBuffer.allocateDirect(need * 4)
			.order(ByteOrder.nativeOrder()).asFloatBuffer();
		}
		scratch.position(0);
		scratch.put(mb.v, 0, need).position(0);
		if (vbo == 0) { int[] h = new int[1]; GLES20.glGenBuffers(1, h, 0); vbo = h[0]; }
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, need * 4, scratch, GLES20.GL_DYNAMIC_DRAW);
		GLES20.glVertexAttribPointer(aPos,    3, GLES20.GL_FLOAT, false, STRIDE, 0);
		GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, 12);
		GLES20.glVertexAttribPointer(aColor,  3, GLES20.GL_FLOAT, false, STRIDE, 24);
		GLES20.glVertexAttribPointer(aUv,     2, GLES20.GL_FLOAT, false, STRIDE, 36);
		GLES20.glEnableVertexAttribArray(aPos);
		GLES20.glEnableVertexAttribArray(aNormal);
		GLES20.glEnableVertexAttribArray(aColor);
		GLES20.glEnableVertexAttribArray(aUv);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mb.vc);
		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aNormal);
		GLES20.glDisableVertexAttribArray(aColor);
		GLES20.glDisableVertexAttribArray(aUv);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
	}
	
	private static void drawObjModelWithTransform(
	ObjLoader.Model model,
	float px, float py, float pz,
	float dirX, float dirZ, float scale,
	float[] vp, float[] camPos,
	float[] lightDir, float[] lightColor, float[] ambient,
	CharacterPose pose, float deathTiltDeg) {
		
		if (model == null || model.isEmpty()) return;
		float yawDeg = (float) Math.toDegrees(Math.atan2(-dirX, -dirZ));
		if (model == ModelCache.AIRPLANE) yawDeg += GameSettings.AIRPLANE_YAW_OFFSET_DEG;
		else if (model == ModelCache.PLAYER || model == ModelCache.BOT) yawDeg += GameSettings.CHARACTER_YAW_OFFSET_DEG;
		float[] m = new float[16];
		Matrix.setIdentityM(m, 0);
		Matrix.translateM(m, 0, px, py, pz);
		Matrix.rotateM(m, 0, yawDeg, 0f, 1f, 0f);
		if (deathTiltDeg != 0f) Matrix.rotateM(m, 0, deathTiltDeg, 1f, 0f, 0f);
		Matrix.scaleM(m, 0, scale, scale, scale);
		ModelShader.draw(model, m, vp, camPos, lightDir, lightColor, ambient, pose);
	}
}
