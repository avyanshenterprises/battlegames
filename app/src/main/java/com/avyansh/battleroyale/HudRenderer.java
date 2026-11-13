package com.avyansh.battleroyale;

import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.Matrix;

public class HudRenderer {

	private final TextSprite hpText = new TextSprite();
	private final TextSprite ammoText = new TextSprite();
	private final TextSprite weaponText = new TextSprite();
	private final TextSprite aliveText = new TextSprite();
	private final TextSprite compassText = new TextSprite();
	private final TextSprite phaseText = new TextSprite();
	private final TextSprite killFeedText = new TextSprite();
	private final TextSprite medkitText = new TextSprite();
	private final TextSprite healText = new TextSprite();

	private final float[] ortho = new float[16];
	private final float[] model = new float[16];
	private final float[] mvp = new float[16];

	private int vboQuad = 0;
	private int width = 1, height = 1;
	private float density = 1f;

	private String lastHpText = "";
	private String lastAmmoText = "";
	private String lastWeaponText = "";
	private String lastAliveText = "";
	private String lastCompassText = "";
	private String lastPhaseText = "";
	private String lastKillFeedText = "";
	private String lastMedkitText = "";
	private String lastHealText = "";

	private boolean ready = false;

	public void init(int width, int height, float density) {
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
		this.density = density > 0f ? density : 1f;

		Matrix.orthoM(ortho, 0, 0f, this.width, 0f, this.height, -1f, 1f);

		hpText.init();
		ammoText.init();
		weaponText.init();
		aliveText.init();
		compassText.init();
		phaseText.init();
		killFeedText.init();
		medkitText.init();
		healText.init();

		float[] quad = {
			0f, 0f,  1f, 0f,  0f, 1f,
			1f, 0f,  1f, 1f,  0f, 1f
		};
		int[] h = new int[1];
		GLES20.glGenBuffers(1, h, 0);
		vboQuad = h[0];
		java.nio.FloatBuffer fb = java.nio.ByteBuffer
		.allocateDirect(quad.length * 4)
		.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
		fb.put(quad).position(0);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboQuad);
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.length * 4, fb, GLES20.GL_STATIC_DRAW);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

		ready = true;
	}

	public void resize(int w, int h) {
		this.width = Math.max(1, w);
		this.height = Math.max(1, h);
		Matrix.orthoM(ortho, 0, 0f, this.width, 0f, this.height, -1f, 1f);
		lastHpText = lastAmmoText = lastWeaponText = "";
		lastAliveText = lastCompassText = lastPhaseText = lastKillFeedText = "";
		lastMedkitText = lastHealText = "";
	}

	public void render(GameEngine eng, GameCamera cam) {
		if (!ready) return;

		float W = width, H = height;
		float dp = density;

		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

		// HP BAR
		float barW = 320f * dp;
		float barH = 22f * dp;
		float barX = W * 0.5f - barW * 0.5f;
		float barY = 32f * dp + barH;

		float hpFrac = Math.max(0f, eng.player.health / Math.max(1f, eng.player.maxHealth));

		drawRect(barX - 3f, barY - barH - 3f, barW + 6f, barH + 6f, 0.05f, 0.05f, 0.05f, 0.85f);

		float hr, hg, hb;
		if (hpFrac > 0.6f)      { hr = 0.20f; hg = 0.85f; hb = 0.30f; }
		else if (hpFrac > 0.3f) { hr = 0.95f; hg = 0.75f; hb = 0.15f; }
		else                    { hr = 0.95f; hg = 0.20f; hb = 0.18f; }

		drawRect(barX, barY - barH, barW * hpFrac, barH, hr, hg, hb, 1f);

		float armorFrac = Math.max(0f, Math.min(1f,
			eng.player.armor / Math.max(1f, eng.player.maxArmor)));
		drawRect(barX, barY + 5f * dp, barW, 6f * dp, 0.04f, 0.07f, 0.10f, 0.85f);
		if (armorFrac > 0f) {
			drawRect(barX, barY + 5f * dp, barW * armorFrac, 6f * dp,
				0.18f, 0.62f, 0.98f, 1f);
		}

		drawRect(barX, barY - barH, barW, 2f, 1f, 1f, 1f, 0.95f);
		drawRect(barX, barY - 2f, barW, 2f, 1f, 1f, 1f, 0.95f);
		drawRect(barX, barY - barH, 2f, barH, 1f, 1f, 1f, 0.95f);
		drawRect(barX + barW - 2f, barY - barH, 2f, barH, 1f, 1f, 1f, 0.95f);

		String hp = (int) Math.ceil(eng.player.health) + " / " + (int) eng.player.maxHealth;
		if (!hp.equals(lastHpText)) {
			hpText.setText(hp, (int) (16f * dp), Color.WHITE, 0, false);
			lastHpText = hp;
		}
		float hpW = hpText.getWidthPx(), hpH = hpText.getHeightPx();
		drawSprite(hpText, W * 0.5f - hpW * 0.5f, barY - barH + (barH - hpH) * 0.5f, hpW, hpH, 1f);

		// HEALING PROGRESS BAR (above HP bar)
		if (eng.healing.isHealing()) {
			float pw = 220f * dp;
			float ph = 8f * dp;
			float px = W * 0.5f - pw * 0.5f;
			float py = barY + 20f * dp;
			drawRect(px - 1f, py - 1f, pw + 2f, ph + 2f, 0.05f, 0.05f, 0.05f, 0.85f);
			drawRect(px, py, pw * eng.healing.progress(), ph, 0.95f, 0.55f, 0.20f, 1f);
			String txt = "HEALING...";
			if (!txt.equals(lastHealText)) {
				healText.setText(txt, (int) (12f * dp), 0xFFFFE082, 0, false);
				lastHealText = txt;
			}
			drawSprite(healText, W * 0.5f - healText.getWidthPx() * 0.5f,
				py + ph + 2f, healText.getWidthPx(), healText.getHeightPx(), 1f);
		}

		// Medkit counter
		String medStr = "Heal: " + eng.player.healCount();
		if (!medStr.equals(lastMedkitText)) {
			medkitText.setText(medStr, (int) (16f * dp), 0xFFFFFFFF, 0, false);
			lastMedkitText = medStr;
		}
		float mkW = medkitText.getWidthPx();
		float mkH = medkitText.getHeightPx();
		drawSprite(medkitText, 120f * dp, H - 200f * dp, mkW, mkH, 1f);

		// Compass
		float cW = 420f * dp;
		float cH = 26f * dp;
		float cX = W * 0.5f - cW * 0.5f;
		float cY = H - cH - 16f * dp;
		drawRect(cX - 1f, cY - 1f, cW + 2f, cH + 2f, 0.05f, 0.05f, 0.05f, 0.6f);

		float yaw = cam.yaw;
		if (yaw < 0) yaw += 360f;
		String[] dirs = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
		int dirIdx = ((int) Math.round(yaw / 45f)) & 7;
		int deg = (int) Math.round(yaw);
		String comp = dirs[dirIdx] + "   " + deg + "°";
		if (!comp.equals(lastCompassText)) {
			compassText.setText(comp, (int) (16f * dp), Color.WHITE, 0, false);
			lastCompassText = comp;
		}
		float compW = compassText.getWidthPx();
		float compH = compassText.getHeightPx();
		drawSprite(compassText, W * 0.5f - compW * 0.5f, cY + (cH - compH) * 0.5f, compW, compH, 1f);

		// Alive counter
		int alive = eng.bots.aliveCount() + (eng.player.alive ? 1 : 0);
		String aliveStr = alive + " / 20";
		if (!aliveStr.equals(lastAliveText)) {
			aliveText.setText(aliveStr, (int) (18f * dp), 0xFFFFD54F, 0, false);
			lastAliveText = aliveStr;
		}
		float aW = aliveText.getWidthPx();
		float aH = aliveText.getHeightPx();
		drawSprite(aliveText, W - 24f * dp - aW, H - 24f * dp - aH, aW, aH, 1f);

		// Phase label
		String phaseStr;
		switch (eng.match.phase) {
			case AIRPLANE:  phaseStr = "PLANE — tap JUMP to drop"; break;
			case FREEFALL:  phaseStr = "FREEFALL"; break;
			case PARACHUTE: phaseStr = "PARACHUTE"; break;
			case PLAYING:   phaseStr = "LANDED — fight!"; break;
			case DEAD:      phaseStr = "YOU DIED — tap RESPAWN"; break;
			case WIN:       phaseStr = "WINNER WINNER!"; break;
			default:        phaseStr = ""; break;
		}
		if (!phaseStr.equals(lastPhaseText)) {
			phaseText.setText(phaseStr, (int) (14f * dp), 0xFFFFE082, 0, false);
			lastPhaseText = phaseStr;
		}
		float pW = phaseText.getWidthPx();
		float pH = phaseText.getHeightPx();
		drawSprite(phaseText, W * 0.5f - pW * 0.5f, cY - pH - 8f * dp, pW, pH, 1f);

		// Weapon + ammo
		Weapon wpn = eng.weapons.current();
        if(wpn==null){
            String empty="NO WEAPON  ·  LOOT A GUN";
            if(!empty.equals(lastWeaponText)){weaponText.setText(empty,(int)(13f*dp),Color.WHITE,0,false);lastWeaponText=empty;}
            drawSprite(weaponText,W-210f*dp,42f*dp,weaponText.getWidthPx(),weaponText.getHeightPx(),1f);
        } else {
		float slotW = 180f * dp;
		float slotH = 60f * dp;
		float slotX = W - slotW - 24f * dp;
		float slotY = 24f * dp;

		drawRect(slotX, slotY, slotW, slotH, 0.05f, 0.05f, 0.05f, 0.72f);
		drawRect(slotX, slotY, slotW, 2f, 0.5f, 0.5f, 0.5f, 1f);
		drawRect(slotX, slotY + slotH - 2f, slotW, 2f, 0.5f, 0.5f, 0.5f, 1f);
		drawRect(slotX, slotY, 2f, slotH, 0.5f, 0.5f, 0.5f, 1f);
		drawRect(slotX + slotW - 2f, slotY, 2f, slotH, 0.5f, 0.5f, 0.5f, 1f);

		if (!wpn.name.equals(lastWeaponText)) {
			weaponText.setText(wpn.name, (int) (13f * dp), Color.WHITE, 0, false);
			lastWeaponText = wpn.name;
		}
		drawSprite(weaponText, slotX + 8f * dp,
		slotY + slotH - weaponText.getHeightPx() - 6f * dp,
		weaponText.getWidthPx(), weaponText.getHeightPx(), 1f);

		String ammo = wpn.ammo + " / " + eng.weapons.reserveOf(wpn);
		if (!ammo.equals(lastAmmoText)) {
			ammoText.setText(ammo, (int) (20f * dp), 0xFFFFD54F, 0, false);
			lastAmmoText = ammo;
		}
		drawSprite(ammoText, slotX + slotW - ammoText.getWidthPx() - 8f * dp,
		slotY + 6f * dp, ammoText.getWidthPx(), ammoText.getHeightPx(), 1f);

		if (eng.weapons.isReloading()) {
			float prog = eng.weapons.reloadProgress();
			drawRect(slotX, slotY - 8f * dp, slotW * prog, 6f * dp, 0.95f, 0.75f, 0.15f, 1f);
			drawRect(slotX, slotY - 8f * dp, slotW, 6f * dp, 0.15f, 0.15f, 0.15f, 0.6f);
			drawRect(slotX, slotY - 8f * dp, slotW * prog, 6f * dp, 0.95f, 0.75f, 0.15f, 1f);
		}

        }

		if (eng.player.kills > 0) {
			String feed = "Kills: " + eng.player.kills;
			if (!feed.equals(lastKillFeedText)) {
				killFeedText.setText(feed, (int) (14f * dp), 0xFFFF8A80, 0, false);
				lastKillFeedText = feed;
			}
			float kfW = killFeedText.getWidthPx();
			float kfH = killFeedText.getHeightPx();
			drawSprite(killFeedText, W * 0.5f - kfW * 0.5f,
			barY + 6f * dp, kfW, kfH, 1f);
		}

		GLES20.glDisable(GLES20.GL_BLEND);
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
	}

	private void drawRect(float x, float y, float w, float h, float r, float g, float b, float a) {
		if (w <= 0f || h <= 0f) return;
		Matrix.setIdentityM(model, 0);
		Matrix.translateM(model, 0, x, y, 0f);
		Matrix.scaleM(model, 0, w, h, 1f);
		Matrix.multiplyMM(mvp, 0, ortho, 0, model, 0);
		drawSolidQuad(mvp, r, g, b, a);
	}

	private void drawSprite(TextSprite sprite, float x, float y, float w, float h, float alpha) {
		if (sprite == null || !sprite.isReady()) return;
		Matrix.setIdentityM(model, 0);
		Matrix.translateM(model, 0, x + w * 0.5f, y + h * 0.5f, 0f);
		Matrix.scaleM(model, 0, w, h, 1f);
		Matrix.multiplyMM(mvp, 0, ortho, 0, model, 0);
		sprite.draw(mvp, alpha);
	}

	private static int solidProgram = 0;
	private static int solidAPos = -1, solidUMvp = -1, solidUColor = -1;

	private void ensureSolidShader() {
		if (solidProgram != 0) return;
		String vs =
		"uniform mat4 uMVP; attribute vec2 aPos;" +
		"void main(){ gl_Position = uMVP * vec4(aPos,0.0,1.0); }";
		String fs = ShaderUtil.FRAG_HEADER +
		"uniform vec4 uColor;" +
		"void main(){ gl_FragColor = uColor; }";
		solidProgram = ShaderUtil.createProgram(vs, fs);
		solidAPos = GLES20.glGetAttribLocation(solidProgram, "aPos");
		solidUMvp = GLES20.glGetUniformLocation(solidProgram, "uMVP");
		solidUColor = GLES20.glGetUniformLocation(solidProgram, "uColor");
	}

	private void drawSolidQuad(float[] mvp, float r, float g, float b, float a) {
		ensureSolidShader();
		GLES20.glUseProgram(solidProgram);
		GLES20.glUniformMatrix4fv(solidUMvp, 1, false, mvp, 0);
		GLES20.glUniform4f(solidUColor, r, g, b, a);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboQuad);
		GLES20.glVertexAttribPointer(solidAPos, 2, GLES20.GL_FLOAT, false, 8, 0);
		GLES20.glEnableVertexAttribArray(solidAPos);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
		GLES20.glDisableVertexAttribArray(solidAPos);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
	}
}