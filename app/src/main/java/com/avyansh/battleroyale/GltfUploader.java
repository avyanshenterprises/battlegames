package com.avyansh.battleroyale;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Uploads a parsed GltfModel to the GPU. Call on the GL thread once (from
 * ModelCache.init inside onSurfaceCreated). CPU copies of the vertex data are freed afterwards.
 */
public final class GltfUploader {
	private GltfUploader() {}

	public static void upload(GltfModel model) {
		if (model == null || !model.ready || model.meshes == null) return;
		if (model.uploaded) return;
		int primCount = 0;
		for (GltfModel.Mesh m : model.meshes) primCount += m.primitives.length;
		model.vboPos     = new int[primCount];
		model.vboNrm     = new int[primCount];
		model.vboUv      = new int[primCount];
		model.vboCol     = new int[primCount];
		model.vboJoints  = new int[primCount];
		model.vboWeights = new int[primCount];
		model.vboIdx     = new int[primCount];
		model.indexCount = new int[primCount];
		model.indexType  = new int[primCount];

		int k = 0;
		for (GltfModel.Mesh m : model.meshes) {
			for (GltfModel.Primitive pr : m.primitives) {
				if (pr.pos == null || pr.idx == null || pr.idx.length < 3) { k++; continue; }
				model.vboPos[k] = uploadFloats(pr.pos);
				model.vboNrm[k] = pr.nrm != null ? uploadFloats(pr.nrm) : 0;
				model.vboUv[k]  = pr.uv  != null ? uploadFloats(pr.uv)  : 0;
				model.vboCol[k] = pr.col != null ? uploadFloats(pr.col) : 0;
				if (pr.skinned && pr.joints != null && pr.weights != null) {
					model.vboJoints[k]  = uploadFloats(pr.joints);
					model.vboWeights[k] = uploadFloats(pr.weights);
				}
				short[] s = new short[pr.idx.length];
				for (int i = 0; i < s.length; i++) s[i] = (short) pr.idx[i];   // loader guarantees <= 65535
				model.vboIdx[k] = uploadShorts(s);
				model.indexType[k] = GLES20.GL_UNSIGNED_SHORT;
				model.indexCount[k] = s.length;
				// free CPU memory
				pr.pos = null; pr.nrm = null; pr.uv = null; pr.col = null;
				pr.joints = null; pr.weights = null; pr.idx = null;
				k++;
			}
		}
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
		model.uploaded = true;
	}

	private static int uploadFloats(float[] data) {
		FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		fb.put(data).position(0);
		int[] h = new int[1];
		GLES20.glGenBuffers(1, h, 0);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, h[0]);
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.length * 4, fb, GLES20.GL_STATIC_DRAW);
		return h[0];
	}

	private static int uploadShorts(short[] data) {
		ShortBuffer sb = ByteBuffer.allocateDirect(data.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
		sb.put(data).position(0);
		int[] h = new int[1];
		GLES20.glGenBuffers(1, h, 0);
		GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, h[0]);
		GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, data.length * 2, sb, GLES20.GL_STATIC_DRAW);
		return h[0];
	}
}
