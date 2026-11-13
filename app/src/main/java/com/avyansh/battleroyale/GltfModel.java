package com.avyansh.battleroyale;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import java.util.ArrayList;
import java.util.List;

/**
 * Fully resolved glTF / GLB model.
 *
 * GltfLoader flattens the scene: every mesh instance becomes one or more Primitives that own
 * their vertex arrays (static meshes already have their node transform baked in). So any GLB
 * - skinned or static, one mesh or fifty, with or without textures - is drawn the same way.
 */
public final class GltfModel {
	public String name;
	public boolean ready;
	/** First texture (legacy field, kept so older code still compiles). */
	public int textureId;

	public Node[] nodes;
	public Skin[] skins;
	public Mesh[] meshes;
	public List<AnimationClip> clips = new ArrayList<>();

	public float[] bindPose;

	// ---- GPU handles, one entry per primitive ----
	public int[] vboPos, vboNrm, vboUv, vboCol, vboJoints, vboWeights, vboIdx;
	public int[] indexCount, indexType;
	public boolean uploaded;

	/** All GL textures created for this model (released with the model). */
	public final ArrayList<Integer> glTextures = new ArrayList<>();

	// ---- bind-pose bounds (what is really drawn, after skinning) ----
	public boolean hasBounds;
	public float minX, minY, minZ, maxX, maxY, maxZ;
	/** true when the model's front faces +Z (the glTF convention); detected from the feet. */
	public boolean facesPlusZ = true;
	public boolean facingDetected;

	public static final class Node {
		public int index;
		public String name;
		public int mesh = -1;
		public int skin = -1;
		public int[] children;
		public float[] localMatrix = new float[16];
		public float[] worldMatrix = new float[16];
	}

	public static final class Skin {
		public int[] joints;
		public float[] inverseBind;
	}

	public static final class Mesh {
		public String name;
		public Primitive[] primitives;
	}

	public static final class Primitive {
		// resolved CPU data (freed after upload)
		public float[] pos, nrm, uv, col, joints, weights;
		public int[] idx;
		public boolean skinned;

		// material
		public float[] baseColor = { 1f, 1f, 1f, 1f };
		public int texture;            // GL texture id, 0 = none
		public Bitmap texBitmap;       // only kept when loaded without GPU (tree conversion)
		public boolean alphaMask;
		public float alphaCutoff = 0.5f;
		public boolean doubleSided;
	}

	/** Resolved facing: detected from the feet when possible, else GameSettings.GLB_FACES_PLUS_Z. */
	public boolean frontPlusZ() {
		return (GameSettings.AUTO_DETECT_FACING && facingDetected) ? facesPlusZ : GameSettings.GLB_FACES_PLUS_Z;
	}

	public boolean isEmpty() { return meshes == null || meshes.length == 0; }

	/** Joints of the first skin (0 for a static model). */
	public int jointCount() {
		return skins != null && skins.length > 0 && skins[0].joints != null ? skins[0].joints.length : 0;
	}

	public float height() { return hasBounds ? (maxY - minY) : 0f; }

	public void release() {
		if (!uploaded) return;
		if (vboPos != null) {
			for (int i = 0; i < vboPos.length; i++) {
				del(vboPos[i]); del(vboNrm[i]); del(vboUv[i]); del(vboCol[i]);
				del(vboJoints[i]); del(vboWeights[i]); del(vboIdx[i]);
			}
		}
		if (!glTextures.isEmpty()) {
			int[] t = new int[glTextures.size()];
			for (int i = 0; i < t.length; i++) t[i] = glTextures.get(i);
			GLES20.glDeleteTextures(t.length, t, 0);
			glTextures.clear();
		}
		uploaded = false;
	}

	private static void del(int buf) {
		if (buf == 0) return;
		GLES20.glDeleteBuffers(1, new int[] { buf }, 0);
	}
}
