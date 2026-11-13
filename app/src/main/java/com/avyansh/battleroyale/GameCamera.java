
package com.avyansh.battleroyale;

import android.opengl.Matrix;

/**
* Perspective camera with frustum culling.
* Supports real 2x, 3x, 4x and 6x scope zoom.
*/
public class GameCamera {
	
	public final float[] pos = new float[] {
		90f, 40f, 60f
	};
	
	public float yaw = 0f;
	public float pitch = 8f;
	
	public final float[] view = new float[16];
	public final float[] proj = new float[16];
	public final float[] vp = new float[16];
	
	public float fx, fy, fz;
	public float rx, rz;
	public float ux, uy, uz;
	
	public float aspect = 16f / 9f;
	
	// Original camera FOV
	private float baseFov = GameSettings.FOV_DEG;
	
	// Current camera FOV
	public float fov = GameSettings.FOV_DEG;
	
	// Current optical zoom
	private float scopeZoom = 1f;
	
	private final float[][] planes = new float[6][4];
	
	public GameCamera() {
		setViewport(1600, 900);
		update();
	}
	
	public float x() {
		return pos[0];
	}
	
	public float y() {
		return pos[1];
	}
	
	public float z() {
		return pos[2];
	}
	
	public float getBaseFov() {
		return baseFov;
	}
	
	public float getScopeZoom() {
		return scopeZoom;
	}
	
	public void setViewport(int w, int h) {
		
		aspect = (float) w / (float) Math.max(1, h);
		
		updateProjection();
	}
	
	/**
* Set normal camera FOV.
* Calling this resets active scope zoom.
*/	
	public void setFov(float f) {
		
		baseFov = clamp(f, 20f, 120f);
		
		scopeZoom = 1f;
		
		updateProjection();
	}
	
	/**
* Actual 3D camera magnification.
*/	
	public void setScopeZoom(float zoom) {
		
		scopeZoom = clamp(zoom, 1f, 6f);
		
		updateProjection();
	}
	
	/**
* Restore normal camera view.
*/	
	public void resetScopeZoom() {
		
		scopeZoom = 1f;
		
		updateProjection();
	}
	
	private void updateProjection() {
		
		fov = clamp(baseFov / scopeZoom, 1.5f, 120f);
		
		Matrix.perspectiveM(
		proj,
		0,
		fov,
		aspect,
		GameSettings.NEAR,
		GameSettings.FAR
		);
	}
	
	private float clamp(float value, float min, float max) {
		
		return Math.max(min, Math.min(max, value));
	}
	
	/**
* Update camera orientation and view projection.
*/	
	public void update() {
		
		float yr = (float) Math.toRadians(yaw);
		float pr = (float) Math.toRadians(pitch);
		
		float cp = (float) Math.cos(pr);
		float sp = (float) Math.sin(pr);
		
		float sy = (float) Math.sin(yr);
		float cy = (float) Math.cos(yr);
		
		fx = sy * cp;
		fy = sp;
		fz = -cy * cp;
		
		rx = cy;
		rz = sy;
		
		ux = -rz * fy;
		uy = rz * fx - rx * fz;
		uz = rx * fy;
		
		Matrix.setLookAtM(
		view, 0,
		pos[0], pos[1], pos[2],
		pos[0] + fx,
		pos[1] + fy,
		pos[2] + fz,
		0f, 1f, 0f
		);
		
		Matrix.multiplyMM(
		vp, 0,
		proj, 0,
		view, 0
		);
		
		extractPlanes();
	}
	
	private void extractPlanes() {
		
		float[] m = vp;
		
		setPlane(0,
		m[3] + m[0],
		m[7] + m[4],
		m[11] + m[8],
		m[15] + m[12]);
		
		setPlane(1,
		m[3] - m[0],
		m[7] - m[4],
		m[11] - m[8],
		m[15] - m[12]);
		
		setPlane(2,
		m[3] + m[1],
		m[7] + m[5],
		m[11] + m[9],
		m[15] + m[13]);
		
		setPlane(3,
		m[3] - m[1],
		m[7] - m[5],
		m[11] - m[9],
		m[15] - m[13]);
		
		setPlane(4,
		m[3] + m[2],
		m[7] + m[6],
		m[11] + m[10],
		m[15] + m[14]);
		
		setPlane(5,
		m[3] - m[2],
		m[7] - m[6],
		m[11] - m[10],
		m[15] - m[14]);
	}
	
	private void setPlane(
	int i,
	float a,
	float b,
	float c,
	float d
	) {
		
		planes[i][0] = a;
		planes[i][1] = b;
		planes[i][2] = c;
		planes[i][3] = d;
	}
	
	/**
* Frustum culling test.
*/	
	public boolean isBoxVisible(
	float minX, float minY, float minZ,
	float maxX, float maxY, float maxZ
	) {
		
		for (int i = 0; i < 6; i++) {
			
			float[] p = planes[i];
			
			float px = p[0] > 0f ? maxX : minX;
			float py = p[1] > 0f ? maxY : minY;
			float pz = p[2] > 0f ? maxZ : minZ;
			
			if (
			p[0] * px +
			p[1] * py +
			p[2] * pz +
			p[3] < 0f
			) {
				return false;
			}
		}
		
		return true;
	}
}
