package com.avyansh.battleroyale;

public class PlayerAnimator {

	public static void drawCustomModel(ObjLoader.Model model,
	float x, float y, float z,
	float yawRad, float pitchRad,
	float targetHeight,
	float tr, float tg, float tb,
	float deathTilt,
	int aPos, int aNormal, int aColor, int aUv,
	int uVP, int uCamPos, int uLightDir, int uLightColor,
	int uAmbient, int uTex,
	float[] vpMatrix, float[] camPos,
	float[] lightDir, float[] lightColor, float[] ambient) {
		if (model == null || model.isEmpty()) return;

		float modelH = Math.max(0.001f, model.height());
		float scale  = targetHeight / modelH;

		float[] m = new float[16];
		android.opengl.Matrix.setIdentityM(m, 0);
		android.opengl.Matrix.translateM(m, 0, x, y, z);
		android.opengl.Matrix.rotateM(m, 0, (float) Math.toDegrees(yawRad), 0f, 1f, 0f);
		android.opengl.Matrix.rotateM(m, 0, (float) Math.toDegrees(pitchRad), 1f, 0f, 0f);
		if (deathTilt != 0f) android.opengl.Matrix.rotateM(m, 0, deathTilt, 1f, 0f, 0f);
		android.opengl.Matrix.scaleM(m, 0, scale, scale, scale);

		ModelShader.draw(model, m, vpMatrix, camPos, lightDir, lightColor, ambient);
	}

	public static void drawCharacter(MeshBuilder mb, float x, float y, float z, float yaw,
	float anim, float speed, boolean crouch, boolean air,
	float aimPitch, int weaponType, boolean dead, float deathT,
	boolean bot) {
		// Retained (unused by default). Procedural fallback would go here.
	}

	public static void drawFirstPersonGun(MeshBuilder mb, GameCamera cam, int weaponType) {
		// Retained (unused by default). EntityRenderer.drawFppWeapon is the active path.
	}

	public static void drawProceduralParachute(MeshBuilder mb,
	float px, float py, float pz,
	float sway) {
		float cx = px + sway;
		float cy = py + 3.6f;
		float cz = pz;
		mb.addCone(cx, cy, cz, 3.0f, 1.8f, 12, 0.92f, 0.20f, 0.16f);
		mb.addCone(cx, cy - 0.02f, cz, 3.0f, -0.9f, 12, 0.70f, 0.14f, 0.10f);
		for (int i = 0; i < 6; i++) {
			double a = i * (Math.PI * 2.0 / 6.0);
			float ox = (float) Math.cos(a) * 2.6f;
			float oz = (float) Math.sin(a) * 2.6f;
			mb.addCylinder(px + ox * 0.55f, py + 1.1f, pz + oz * 0.55f,
			0.04f, 2.4f, 4, 0.18f, 0.18f, 0.20f);
		}
		mb.box(px - 0.32f, py - 0.10f, pz - 0.30f,
			px + 0.32f, py + 0.55f, pz + 0.30f,
			0.22f, 0.20f, 0.18f);
	}
}

