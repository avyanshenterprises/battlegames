package com.avyansh.battleroyale;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class GameWorld {
	private static final String VS =
	"uniform mat4 uVP; uniform mat4 uModel; uniform vec3 uCamPos;\n" +
	"attribute vec3 aPos; attribute vec3 aNormal; attribute vec3 aColor; attribute vec2 aUv;\n" +
	"varying vec3 vN; varying vec3 vC; varying vec2 vUv; varying float vDist;\n" +
	"void main(){\n" +
	"  vN = mat3(uModel) * aNormal; vC = aColor; vUv = aUv;\n" +
	"  gl_Position = uVP * vec4(aPos, 1.0);\n" +
	"  vDist = gl_Position.w;\n" +
	"}\n";

	private static final String FS = ShaderUtil.FRAG_HEADER +
	"varying vec3 vN; varying vec3 vC; varying vec2 vUv; varying float vDist;\n" +
	"uniform sampler2D uTex;\n" +
	"uniform vec3 uLightDir; uniform vec3 uLightColor; uniform vec3 uAmbient;\n" +
	"uniform vec3 uFogColor; uniform float uFogStart; uniform float uFogEnd;\n" +
	"void main(){\n" +
	"  vec3 n = normalize(vN);\n" +
	"  if (!gl_FrontFacing) n = -n;\n" +
	"  float ndl = max(dot(n, uLightDir), 0.0);\n" +
	"  float d1 = texture2D(uTex, vUv).r;\n" +
	"  float d2 = texture2D(uTex, vUv * 0.173 + vec2(0.37, 0.11)).r;\n" +
	"  float d3 = texture2D(uTex, vUv * 3.7).r;\n" +
	"  float nearK = 1.0 - smoothstep(18.0, 80.0, vDist);\n" +
	"  float detail = d1 * 0.65 + d2 * 0.35;\n" +
	"  detail = mix(detail, detail * 0.55 + d3 * 0.45, nearK);\n" +
	"  vec3 base = vC * (0.30 + 0.85 * detail);\n" +
	"  float hemi = 0.55 + 0.45 * n.y;\n" +
	"  vec3 lit = base * (uAmbient * hemi + uLightColor * ndl);\n" +
	"  float f = smoothstep(uFogStart, uFogEnd, vDist);\n" +
	"  gl_FragColor = vec4(mix(lit, uFogColor, f), 1.0);\n" +
	"}\n";

	public final TerrainGenerator generator = new TerrainGenerator(GameSettings.WORLD_SEED);
	private final HashMap<Long, TerrainChunk> chunks = new HashMap<Long, TerrainChunk>();
	private final WaterRenderer water = new WaterRenderer();

	private int program, texture;
	private final float[] identity = new float[16];
	private int aPos, aNormal, aColor, aUv;
	private int uVP, uModel, uCamPos, uLightDir, uLightColor, uAmbient, uTex;
	private int uFogColor, uFogStart, uFogEnd;
	private int settledCx = Integer.MIN_VALUE, settledCz = Integer.MIN_VALUE;

	public int statLoaded, statDrawn, statTriangles, statTreeTris, statBuildTris;

	private final ArrayList<TerrainChunk> visible = new ArrayList<TerrainChunk>();
	private static final Comparator<TerrainChunk> NEAREST_FIRST = new Comparator<TerrainChunk>() {
		@Override public int compare(TerrainChunk a, TerrainChunk b) { return Float.compare(a.distSq, b.distSq); }
	};

	private static long key(int cx, int cz) {
		return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
	}

	public void init() {
		chunks.clear();
		settledCx = Integer.MIN_VALUE;
		settledCz = Integer.MIN_VALUE;
		program = ShaderUtil.createProgram(VS, FS);
		android.opengl.Matrix.setIdentityM(identity, 0);
		aPos = GLES20.glGetAttribLocation(program, "aPos");
		aNormal = GLES20.glGetAttribLocation(program, "aNormal");
		aColor = GLES20.glGetAttribLocation(program, "aColor");
		aUv = GLES20.glGetAttribLocation(program, "aUv");
		uVP = GLES20.glGetUniformLocation(program, "uVP");
		uModel = GLES20.glGetUniformLocation(program, "uModel");
		uCamPos = GLES20.glGetUniformLocation(program, "uCamPos");
		uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
		uLightColor = GLES20.glGetUniformLocation(program, "uLightColor");
		uAmbient = GLES20.glGetUniformLocation(program, "uAmbient");
		uTex = GLES20.glGetUniformLocation(program, "uTex");
		uFogColor = GLES20.glGetUniformLocation(program, "uFogColor");
		uFogStart = GLES20.glGetUniformLocation(program, "uFogStart");
		uFogEnd = GLES20.glGetUniformLocation(program, "uFogEnd");
		texture = TextureUtil.createDetailTexture();
		water.init();
	}

	public void update(GameCamera cam) {
		float size = GameSettings.chunkSize();
		int ccx = (int) Math.floor(cam.pos[0] / size);
		int ccz = (int) Math.floor(cam.pos[2] / size);
		if (ccx == settledCx && ccz == settledCz) {
			statLoaded = chunks.size();
			return;
		}
		int R = GameSettings.VIEW_RADIUS_CHUNKS;
		int H = GameSettings.WORLD_HALF_CHUNKS;

		Iterator<Map.Entry<Long, TerrainChunk>> it = chunks.entrySet().iterator();
		while (it.hasNext()) {
			TerrainChunk c = it.next().getValue();
			if (Math.abs(c.cx - ccx) > R + 1 || Math.abs(c.cz - ccz) > R + 1) {
				c.release();
				it.remove();
			}
		}

		long start = System.nanoTime();
		boolean finished = false;
		while (true) {
			int bestD = Integer.MAX_VALUE, bx = 0, bz = 0;
			for (int dz = -R; dz <= R; dz++) {
				for (int dx = -R; dx <= R; dx++) {
					int cx = ccx + dx, cz = ccz + dz;
					if (cx < -H || cx >= H || cz < -H || cz >= H) continue;
					int d = dx * dx + dz * dz;
					if (d > R * R + 1) continue;
					if (chunks.containsKey(key(cx, cz))) continue;
					if (d < bestD) { bestD = d; bx = cx; bz = cz; }
				}
			}
			if (bestD == Integer.MAX_VALUE) { finished = true; break; }
			TerrainChunk c = new TerrainChunk(bx, bz);
			c.build(generator);
			chunks.put(key(bx, bz), c);
			if ((System.nanoTime() - start) / 1000000f > GameSettings.CHUNK_BUILD_BUDGET_MS) break;
		}
		if (finished) { settledCx = ccx; settledCz = ccz; }
		statLoaded = chunks.size();
	}

	public void render(GameCamera cam, GameEngine eng, float time) {
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthFunc(GLES20.GL_LEQUAL);
		GLES20.glDepthMask(true);
		GLES20.glDisable(GLES20.GL_BLEND);
		GLES20.glEnable(GLES20.GL_CULL_FACE);
		GLES20.glCullFace(GLES20.GL_BACK);
		GLES20.glFrontFace(GLES20.GL_CCW);

		GLES20.glUseProgram(program);
		GLES20.glUniformMatrix4fv(uVP, 1, false, cam.vp, 0);
		GLES20.glUniformMatrix4fv(uModel, 1, false, identity, 0);
		GLES20.glUniform3f(uCamPos, cam.pos[0], cam.pos[1], cam.pos[2]);
		GLES20.glUniform3fv(uLightDir, 1, eng.lightDir, 0);
		GLES20.glUniform3fv(uLightColor, 1, eng.lightColor, 0);
		GLES20.glUniform3fv(uAmbient, 1, eng.ambient, 0);
		GLES20.glUniform3fv(uFogColor, 1, eng.fog, 0);
		GLES20.glUniform1f(uFogStart, GameSettings.fogStart());
		GLES20.glUniform1f(uFogEnd, GameSettings.fogEnd());
		ModelShader.setFog(eng.fog, GameSettings.fogStart(), GameSettings.fogEnd());
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
		GLES20.glUniform1i(uTex, 0);

		GLES20.glEnableVertexAttribArray(aPos);
		GLES20.glEnableVertexAttribArray(aNormal);
		GLES20.glEnableVertexAttribArray(aColor);
		GLES20.glEnableVertexAttribArray(aUv);

		// ---- collect visible chunks, nearest first ----
		visible.clear();
		for (TerrainChunk c : chunks.values()) {
			if (c.empty) continue;
			if (!cam.isBoxVisible(c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ)) continue;
			float dx = c.centerX() - cam.pos[0], dz = c.centerZ() - cam.pos[2];
			c.distSq = dx * dx + dz * dz;
			visible.add(c);
		}
		Collections.sort(visible, NEAREST_FIRST);

		int tris = 0, treeTris = 0, bldTris = 0;
		final float hiD2 = GameSettings.TREE_HI_DIST * GameSettings.TREE_HI_DIST;
		for (int i = 0; i < visible.size(); i++) {
			TerrainChunk c = visible.get(i);
			c.drawTerrain(aPos, aNormal, aColor, aUv);
			c.drawProps(aPos, aNormal, aColor, aUv);
			tris += c.terrainTris + c.propTris;

			// buildings: nearest first, hard cap
			if (c.buildingTris > 0 && bldTris + c.buildingTris <= GameSettings.MAX_BUILDING_TRIS) {
				c.drawBuildings(aPos, aNormal, aColor, aUv);
				bldTris += c.buildingTris;
			}

			// trees: full detail close by, cheap LOD further away, hard cap on the total
			c.treeMode = 0;
			if (c.distSq < hiD2 && c.treeHiTris > 0 && treeTris + c.treeHiTris <= GameSettings.MAX_TREE_TRIS) {
				c.treeMode = 2;
				treeTris += c.treeHiTris;
			} else if (c.treeLoTris > 0 && treeTris + c.treeLoTris <= GameSettings.MAX_TREE_TRIS) {
				c.treeMode = 1;
				treeTris += c.treeLoTris;
			}
		}
		// tree pass (custom OBJ trees often have single-sided leaf cards, so draw them two-sided)
		if (TreeModel.ready) GLES20.glDisable(GLES20.GL_CULL_FACE);
		for (int i = 0; i < visible.size(); i++) {
			TerrainChunk c = visible.get(i);
			if (c.treeMode == 2) c.drawTrees(true, aPos, aNormal, aColor, aUv);
			else if (c.treeMode == 1) c.drawTrees(false, aPos, aNormal, aColor, aUv);
		}
		if (TreeModel.ready) GLES20.glEnable(GLES20.GL_CULL_FACE);

		statDrawn = visible.size();
		statTreeTris = treeTris;
		statBuildTris = bldTris;
		statTriangles = tris + treeTris + bldTris;

		GLES20.glDisableVertexAttribArray(aPos);
		GLES20.glDisableVertexAttribArray(aNormal);
		GLES20.glDisableVertexAttribArray(aColor);
		GLES20.glDisableVertexAttribArray(aUv);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

		water.render(cam, eng, time);
	}

	public final TerrainGenerator terrain = generator;

	public float ground(float x, float z) {
		return Math.max(generator.heightAt(x, z), GameSettings.WATER_LEVEL);
	}

	/** Find the building whose footprint contains (x,z), or null. */
	public Building findBuilding(float x, float z, float margin) {
		for (int i = 0; i < generator.buildings.size(); i++) {
			Building b = generator.buildings.get(i);
			if (x >= b.x - b.w * 0.5f - margin && x <= b.x + b.w * 0.5f + margin
				&& z >= b.z - b.d * 0.5f - margin && z <= b.z + b.d * 0.5f + margin) {
				return b;
			}
		}
		return null;
	}

	public float groundForPlayer(float x, float z, float yFeet, float tolerance) {
		float h = Math.max(generator.heightAt(x, z), GameSettings.WATER_LEVEL);
		Building inside = findBuilding(x, z, 0.6f);
		if (inside == null) return h;
		// Tolerance grows with feet height — the player can reach a floor ~1.2 m above them
		float adaptiveTol = Math.max(tolerance, Math.min(1.2f, yFeet - inside.baseY));
		float floor = inside.groundHeight(x, z, yFeet + adaptiveTol * 0.1f, adaptiveTol);
		return Math.max(h, floor);
	}

	public float groundFor(float x, float z) { return ground(x, z); }

	public int getUmodel()       { return uModel; }
	public int getProgram()      { return program; }
	public int getTexture()      { return texture; }
	public int getaPos()         { return aPos; }
	public int getaNormal()      { return aNormal; }
	public int getaColor()       { return aColor; }
	public int getaUv()          { return aUv; }
	public int getUvp()          { return uVP; }
	public int getUcamPos()      { return uCamPos; }
	public int getUlightDir()    { return uLightDir; }
	public int getUlightColor()  { return uLightColor; }
	public int getUambient()     { return uAmbient; }
	public int getUtex()         { return uTex; }

	public boolean blocked(float x, float z, float radius) {
		for (int i = 0; i < generator.buildings.size(); i++) {
			Building b = generator.buildings.get(i);
			if (b.blocksAt(x, z, b.baseY + 0.5f, radius)) return true;
			if (b.blocksAt(x, z, b.baseY + b.h - 0.5f, radius)) return true;
		}
		return false;
	}

	public boolean blockedForPlayer(float x, float z, float yFeet, float radius) {
		for (int i = 0; i < generator.buildings.size(); i++) {
			Building b = generator.buildings.get(i);
			if (b.blocksAt(x, z, yFeet, radius)) return true;
		}
		return false;
	}

	public boolean canWalk(float fromX, float fromZ, float toX, float toZ, float y, float radius) {
		if (!TerrainGenerator.inPlayArea(toX, toZ, 8f)) return false;
		if (generator.heightAt(toX, toZ) < GameSettings.WATER_LEVEL - 0.9f) return false;
		float dx = toX - fromX, dz = toZ - fromZ;
		float dist = (float) Math.sqrt(dx * dx + dz * dz);
		if (ground(toX, toZ) - y > Math.max(0.7f, dist * 1.4f)) return false;
		return !blocked(toX, toZ, radius);
	}
}
