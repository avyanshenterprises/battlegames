package com.avyansh.battleroyale;

/** One staircase tread: a rectangular, walkable, solid box. */
public final class FloorStep {
	public final float x0, x1, z0, z1;
	public final float topY;
	public final float thickness;

	public FloorStep(float x0, float z0, float x1, float z1, float topY, float thickness) {
		this.x0 = Math.min(x0, x1);
		this.x1 = Math.max(x0, x1);
		this.z0 = Math.min(z0, z1);
		this.z1 = Math.max(z0, z1);
		this.topY = topY;
		this.thickness = thickness;
	}

	public boolean containsXZ(float px, float pz, float r) {
		float cx = Math.max(x0, Math.min(x1, px));
		float cz = Math.max(z0, Math.min(z1, pz));
		float dx = px - cx, dz = pz - cz;
		return dx * dx + dz * dz < r * r;
	}
}
