package com.avyansh.battleroyale;

import java.util.ArrayList;
import java.util.List;

/** One named animation. Tracks store keyframe times + values for one node+path. */
public final class AnimationClip {
	public String name = "";
	public float duration = 0f;
	public List<Track> tracks = new ArrayList<>();

	public static final class Track {
		public int nodeIndex;
		public String path;            // "translation" | "rotation" | "scale"
		public String interpolation = "LINEAR";
		public float[] times;
		public float[] values;
		public int components;         // 3 or 4
		/** cached by AnimationMixer: 1 = translation, 2 = rotation, 3 = scale, -1 = ignored. */
		public int kind;
		public boolean step;
	}
}
