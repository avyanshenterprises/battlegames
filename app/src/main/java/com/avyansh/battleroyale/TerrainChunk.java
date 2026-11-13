package com.avyansh.battleroyale;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Random;

/**
* One square piece of the world. GL thread only.
*
* Geometry is split into separate GPU buffers so GameWorld can enforce triangle budgets:
*   terrain      - always drawn
*   props        - rocks / bushes, always drawn
*   buildings    - drawn nearest-first until GameSettings.MAX_BUILDING_TRIS
*   trees (hi)   - full detail or custom tree.obj instances
*   trees (lo)   - cheap far LOD; trees together are capped by GameSettings.MAX_TREE_TRIS
*/
public class TerrainChunk {
	private static final int FLOATS = MeshBuilder.FLOATS;   // pos3 normal3 color3 uv2
	private static final int STRIDE = FLOATS * 4;
	
	public final int cx, cz;
	public float minX, minY, minZ, maxX, maxY, maxZ;
	public boolean empty = false;
	
	public int triangleCount = 0;
	public int terrainTris, propTris, buildingTris, treeHiTris, treeLoTris;
	
	/** scratch fields used by GameWorld each frame */
	public float distSq;
	public int treeMode;   // 0 none, 1 lo, 2 hi
	
	private int tVbo, tIbo, tCount;
	private int pVbo, pCount;
	private int bVbo, bCount;
	private int hVbo, hCount;
	private int lVbo, lCount;
	
	public TerrainChunk(int cx, int cz) {
		this.cx = cx;
		this.cz = cz;
	}
	
	public float centerX() { return (cx + 0.5f) * GameSettings.chunkSize(); }
	public float centerZ() { return (cz + 0.5f) * GameSettings.chunkSize(); }
	
	public void build(TerrainGenerator gen) {
		int n = GameSettings.CHUNK_CELLS;
		float cs = GameSettings.CELL_SIZE;
		float size = n * cs;
		float x0 = cx * size, z0 = cz * size;
		
		int gw = n + 3;
		float[] hg = new float[gw * gw];
		float hmin = 1e9f, hmax = -1e9f;
		for (int j = 0; j < gw; j++) {
			for (int i = 0; i < gw; i++) {
				float h = gen.heightAt(x0 + (i - 1) * cs, z0 + (j - 1) * cs);
				hg[j * gw + i] = h;
				if (i >= 1 && i <= n + 1 && j >= 1 && j <= n + 1) {
					if (h < hmin) hmin = h;
					if (h > hmax) hmax = h;
				}
			}
		}
		if (hmax < -14f) { empty = true; return; }
		
		int vn = n + 1;
		float[] vd = new float[vn * vn * FLOATS];
		float[] col = new float[3];
		int o = 0;
		for (int j = 0; j < vn; j++) {
			for (int i = 0; i < vn; i++) {
				int gi = i + 1, gj = j + 1;
				float h = hg[gj * gw + gi];
				float nx = hg[gj * gw + gi - 1] - hg[gj * gw + gi + 1];
				float nz = hg[(gj - 1) * gw + gi] - hg[(gj + 1) * gw + gi];
				float ny = 2f * cs;
				float inv = 1f / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				nx *= inv; ny *= inv; nz *= inv;
				float wx = x0 + i * cs, wz = z0 + j * cs;
				gen.groundColor(wx, wz, h, ny, col);
				vd[o++] = wx; vd[o++] = h; vd[o++] = wz;
				vd[o++] = nx; vd[o++] = ny; vd[o++] = nz;
				vd[o++] = col[0]; vd[o++] = col[1]; vd[o++] = col[2];
				vd[o++] = wx * 0.1f; vd[o++] = wz * 0.1f;
			}
		}
		short[] id = new short[n * n * 6];
		int k = 0;
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				int a = j * vn + i;
				int b = a + vn;
				id[k++] = (short) a;
				id[k++] = (short) b;
				id[k++] = (short) (a + 1);
				id[k++] = (short) (a + 1);
				id[k++] = (short) b;
				id[k++] = (short) (b + 1);
			}
		}
		tCount = id.length;
		tVbo = uploadFloats(vd, vd.length);
		tIbo = uploadShorts(id);
		terrainTris = tCount / 3;
		
		MeshBuilder mbB = new MeshBuilder();   // buildings
		MeshBuilder mbP = new MeshBuilder();   // small props
		MeshBuilder mbH = new MeshBuilder();   // trees, high detail
		MeshBuilder mbL = new MeshBuilder();   // trees, low detail
		
		boolean hasBuilding = false;
		for (int i = 0; i < gen.buildings.size(); i++) {
			Building b = gen.buildings.get(i);
			if (b.x >= x0 && b.x < x0 + size && b.z >= z0 && b.z < z0 + size) {
				mbB.building(b);
				hasBuilding = true;
			}
		}
		placeTrees(mbH, mbL, gen, x0, z0, size);
		placeSmallProps(mbP, gen, x0, z0, size);
		
		if (mbB.vc > 0) { bCount = mbB.vc; bVbo = uploadFloats(mbB.v, mbB.vc * FLOATS); }
		if (mbP.vc > 0) { pCount = mbP.vc; pVbo = uploadFloats(mbP.v, mbP.vc * FLOATS); }
		if (mbH.vc > 0) { hCount = mbH.vc; hVbo = uploadFloats(mbH.v, mbH.vc * FLOATS); }
		if (mbL.vc > 0) { lCount = mbL.vc; lVbo = uploadFloats(mbL.v, mbL.vc * FLOATS); }
		buildingTris = bCount / 3;
		propTris = pCount / 3;
		treeHiTris = hCount / 3;
		treeLoTris = lCount / 3;
		
		minX = x0 - 12f; maxX = x0 + size + 12f;
		minZ = z0 - 12f; maxZ = z0 + size + 12f;
		minY = hmin - 2f;
		maxY = hmax + (hasBuilding ? 60f : 30f);
		triangleCount = terrainTris + propTris + buildingTris + treeHiTris;
	}
	
	// TerrainChunk.java - Replace placeTrees method
	private void placeTrees(MeshBuilder mbH, MeshBuilder mbL, TerrainGenerator gen,
	float x0, float z0, float size) {
		Random rnd = new Random(gen.seed * 1000003L + cx * 73856093L + cz * 19349663L);
		float sp = 7f;  // Slightly tighter for more trees
		int cnt = (int) Math.ceil(size / sp);
		
		int treeCount = 0;
		int maxTrees = 12;  // Cap per chunk for performance
		
		for (int gz = 0; gz < cnt && treeCount < maxTrees; gz++) {
			for (int gx = 0; gx < cnt && treeCount < maxTrees; gx++) {
				float wx = x0 + gx * sp + rnd.nextFloat() * sp;
				float wz = z0 + gz * sp + rnd.nextFloat() * sp;
				float roll = rnd.nextFloat(), sr = rnd.nextFloat(), tint = rnd.nextFloat();
				if (wx >= x0 + size || wz >= z0 + size) continue;
				if (roll > gen.forestDensity(wx, wz) * 0.9f) continue;
				
				float h = gen.propHeight(wx, wz);
				if (Float.isNaN(h)) continue;
				
				float height = (8f + sr * 10f) * GameSettings.TREE_SCALE;
				if (height > GameSettings.MAX_TREE_HEIGHT) height = GameSettings.MAX_TREE_HEIGHT;
				float radius = height * 0.32f;
				int seed = (int) (wx * 31 + wz * 17);
				
				if (TreeModel.ready) {
					float yaw = (seed & 1023) * 0.00614f;
					mbH.addModelTree(wx, h - 0.2f, wz, height * GameSettings.TREE_MODEL_SCALE,
					yaw, 0.88f + 0.22f * tint, seed);
					mbL.addModelTree(wx, h - 0.2f, wz, height * GameSettings.TREE_MODEL_SCALE * 0.8f,
					yaw, 0.88f + 0.22f * tint, seed);
				} else {
					// Use realistic tree with small leaves
					mbH.addRealisticTree(wx, h - 0.2f, wz, height, radius, tint, seed);
					// Far LOD: simpler but still leafy
					mbL.addRealisticTree(wx, h - 0.2f, wz, height * 0.9f, radius * 0.8f, tint, seed);
				}
				treeCount++;
			}
		}
	}
	
	private void placeSmallProps(MeshBuilder mb, TerrainGenerator gen, float x0, float z0, float size) {
		Random rnd = new Random(gen.seed * 998244353L + cx * 83492791L + cz * 297121507L);
		float sp = 7f;
		int cnt = (int) Math.ceil(size / sp);
		for (int gz = 0; gz < cnt; gz++) {
			for (int gx = 0; gx < cnt; gx++) {
				float wx = x0 + gx * sp + rnd.nextFloat() * sp;
				float wz = z0 + gz * sp + rnd.nextFloat() * sp;
				float roll = rnd.nextFloat(), kind = rnd.nextFloat(), sr = rnd.nextFloat();
				if (wx >= x0 + size || wz >= z0 + size) continue;
				if (roll > 0.22f) continue;
				float h = gen.propHeight(wx, wz);
				if (Float.isNaN(h)) continue;
				boolean dry = gen.desertness(wx, wz) > 0.5f;
				boolean rock = kind > (h > 30f ? 0.25f : 0.65f);
				if (rock) {
					float r = 1.0f + sr * 2.6f;
					if (r > 2.4f) {
						mb.addBoulder(wx, h + r * 0.1f, wz, r, (int) (wx * 41 + wz * 13),
						0.46f, 0.45f, 0.42f);
					} else {
						mb.addRock(wx, h + r * 0.1f, wz, r, (int) (wx * 41 + wz * 13),
						0.46f, 0.45f, 0.42f);
					}
				} else if (dry) {
					float r = 0.8f + sr * 0.9f;
					mb.diamond(wx, h + 0.5f, wz, r, 1.0f, 0.6f, 5, 0.55f, 0.48f, 0.24f);
				} else {
					float r = 0.9f + sr * 1.0f;
					mb.diamond(wx, h + 0.55f, wz, r, 1.1f, 0.6f, 5, 0.18f, 0.42f, 0.13f);
				}
			}
		}
	}
	
	private static int uploadFloats(float[] data, int len) {
		FloatBuffer fb = ByteBuffer.allocateDirect(len * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		fb.put(data, 0, len).position(0);
		int[] h = new int[1];
		GLES20.glGenBuffers(1, h, 0);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, h[0]);
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, len * 4, fb, GLES20.GL_STATIC_DRAW);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		return h[0];
	}
	
	private static int uploadShorts(short[] data) {
		ShortBuffer sb = ByteBuffer.allocateDirect(data.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
		sb.put(data).position(0);
		int[] h = new int[1];
		GLES20.glGenBuffers(1, h, 0);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, h[0]);
		GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, data.length * 2, sb, GLES20.GL_STATIC_DRAW);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
		return h[0];
	}
	
	private void pointers(int aPos, int aNormal, int aColor, int aUv) {
		GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, 0);
		GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, 12);
		GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, STRIDE, 24);
		GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, STRIDE, 36);
	}
	
	private void drawArrays(int vbo, int count, int aPos, int aNormal, int aColor, int aUv) {
		if (vbo == 0 || count == 0) return;
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
		pointers(aPos, aNormal, aColor, aUv);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
	}
	
	public void drawTerrain(int aPos, int aNormal, int aColor, int aUv) {
		if (empty || tVbo == 0) return;
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tVbo);
		pointers(aPos, aNormal, aColor, aUv);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, tIbo);
		GLES20.glDrawElements(GLES20.GL_TRIANGLES, tCount, GLES20.GL_UNSIGNED_SHORT, 0);
	}
	
	public void drawProps(int aPos, int aNormal, int aColor, int aUv) {
		if (empty) return;
		drawArrays(pVbo, pCount, aPos, aNormal, aColor, aUv);
	}
	
	public void drawBuildings(int aPos, int aNormal, int aColor, int aUv) {
		if (empty) return;
		drawArrays(bVbo, bCount, aPos, aNormal, aColor, aUv);
	}
	
	public void drawTrees(boolean hi, int aPos, int aNormal, int aColor, int aUv) {
		if (empty) return;
		if (hi) drawArrays(hVbo, hCount, aPos, aNormal, aColor, aUv);
		else    drawArrays(lVbo, lCount, aPos, aNormal, aColor, aUv);
	}
	
	public void release() {
		int[] h = new int[1];
		if (tVbo != 0) { h[0] = tVbo; GLES20.glDeleteBuffers(1, h, 0); tVbo = 0; }
		if (tIbo != 0) { h[0] = tIbo; GLES20.glDeleteBuffers(1, h, 0); tIbo = 0; }
		if (pVbo != 0) { h[0] = pVbo; GLES20.glDeleteBuffers(1, h, 0); pVbo = 0; }
		if (bVbo != 0) { h[0] = bVbo; GLES20.glDeleteBuffers(1, h, 0); bVbo = 0; }
		if (hVbo != 0) { h[0] = hVbo; GLES20.glDeleteBuffers(1, h, 0); hVbo = 0; }
		if (lVbo != 0) { h[0] = lVbo; GLES20.glDeleteBuffers(1, h, 0); lVbo = 0; }
	}
}
