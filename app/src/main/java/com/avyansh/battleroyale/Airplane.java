package com.avyansh.battleroyale;

public class Airplane {
	public float x, y, z;
	public float dirX, dirZ;
	public final float speed    = 85f;
	public final float altitude = 320f;

	private static final float START_RADIUS = 1250f;

	public boolean finished = false;

	public void respawn() {
		float angle = (GameSettings.WORLD_SEED % 360) * (float) Math.PI / 180f;
		dirX = (float) Math.cos(angle);
		dirZ = (float) Math.sin(angle);

		x = -dirX * START_RADIUS;
		z = -dirZ * START_RADIUS;
		y = altitude;
		finished = false;
	}

	public void update(float dt) {
		if (finished) return;
		x += dirX * speed * dt;
		z += dirZ * speed * dt;

		if (x * dirX + z * dirZ > START_RADIUS + 150f) {
			finished = true;
		}
	}

	public float progress() {
		float along = x * dirX + z * dirZ;
		return Math.max(0f, Math.min(1f, (along + START_RADIUS) / (START_RADIUS * 2f)));
	}

	public void buildMesh(MeshBuilder mb) {
		float cx = x, cy = y, cz = z;
		float bodyLen = 26f, bodyW = 3.2f, bodyH = 3.2f;

		mb.setCenter(cx, cy, cz);
		mb.box(cx - bodyW * 0.5f, cy - bodyH * 0.5f, cz - bodyLen * 0.5f,
		cx + bodyW * 0.5f, cy + bodyH * 0.5f, cz + bodyLen * 0.5f,
		0.82f, 0.84f, 0.88f);
		mb.box(cx - bodyW * 0.35f, cy + bodyH * 0.5f, cz - bodyLen * 0.4f,
		cx + bodyW * 0.35f, cy + bodyH * 0.55f, cz + bodyLen * 0.4f,
		0.92f, 0.94f, 0.97f);

		float noseX = cx + dirX * bodyLen * 0.58f;
		float noseZ = cz + dirZ * bodyLen * 0.58f;
		mb.addCone(noseX, cy - bodyH * 0.5f, noseZ, bodyW * 0.55f, bodyH * 3.2f, 6, 0.88f, 0.90f, 0.93f);

		mb.box(cx + dirX * bodyLen * 0.30f - 0.9f,
		cy + bodyH * 0.15f,
		cz + dirZ * bodyLen * 0.30f - 0.9f,
		cx + dirX * bodyLen * 0.30f + 0.9f,
		cy + bodyH * 0.55f,
		cz + dirZ * bodyLen * 0.30f + 0.9f,
		0.10f, 0.14f, 0.22f);

		float wingSpan = 34f;
		float perpX = -dirZ;
		float perpZ =  dirX;
		for (int side = -1; side <= 1; side += 2) {
			float wx = cx + perpX * wingSpan * 0.28f * side;
			float wz = cz + perpZ * wingSpan * 0.28f * side;
			float halfW = wingSpan * 0.28f;
			mb.box(wx - halfW * Math.abs(perpX) - 0.5f,
			cy - 0.20f,
			wz - halfW * Math.abs(perpZ) - 0.5f,
			wx + halfW * Math.abs(perpX) + 0.5f,
			cy + 0.20f,
			wz + halfW * Math.abs(perpZ) + 0.5f,
			0.68f, 0.70f, 0.74f);
			float ex = wx + perpX * 4f * side;
			float ez = wz + perpZ * 4f * side;
			mb.addCylinder(ex, cy - 1.8f, ez, 1.1f, 2.2f, 8, 0.30f, 0.31f, 0.36f);
			mb.addCylinder(ex, cy - 2.1f, ez, 1.2f, 0.25f, 8, 1.0f, 0.55f, 0.15f);
		}

		mb.box(cx - dirX * bodyLen * 0.50f - 0.25f,
		cy + bodyH * 0.35f,
		cz - dirZ * bodyLen * 0.50f - 0.25f,
		cx - dirX * bodyLen * 0.50f + 0.25f,
		cy + bodyH * 1.6f,
		cz - dirZ * bodyLen * 0.50f + 0.25f,
		0.78f, 0.80f, 0.84f);

		float tailW = 8f;
		mb.box(cx - dirX * bodyLen * 0.48f - tailW * 0.5f,
		cy + bodyH * 0.30f,
		cz - dirZ * bodyLen * 0.48f - 0.4f,
		cx - dirX * bodyLen * 0.48f + tailW * 0.5f,
		cy + bodyH * 0.45f,
		cz - dirZ * bodyLen * 0.48f + 0.4f,
		0.74f, 0.76f, 0.80f);

		mb.addCylinder(cx + dirX * 2f, cy - bodyH * 0.5f - 0.5f, cz + dirZ * 2f,
		0.5f, 0.8f, 6, 0.22f, 0.22f, 0.24f);

		mb.box(cx - 0.1f, cy - 0.3f, cz - 0.1f,
		cx + 0.1f, cy + 0.3f, cz + 0.1f,
		1.0f, 0.85f, 0.2f);
	}

	public float[] jumpOrigin() {
		return new float[] {
			x - dirX * 4f,
			y - 3f,
			z - dirZ * 4f
		};
	}

	public float[] jumpVelocity() {
		return new float[] { dirX * speed * 0.35f, 0f, dirZ * speed * 0.35f };
	}
}
