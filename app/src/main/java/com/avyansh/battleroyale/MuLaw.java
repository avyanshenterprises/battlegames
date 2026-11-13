package com.avyansh.battleroyale;

/** G.711 mu-law codec: 16-bit PCM <-> 8-bit, halves the voice bandwidth with no dependencies. Pure Java. */
public final class MuLaw {
	private MuLaw() {}
	private static final int BIAS = 0x84, CLIP = 32635;

	public static byte encode(short pcm) {
		int s = pcm;
		int sign = (s >> 8) & 0x80;
		if (sign != 0) s = -s;
		if (s > CLIP) s = CLIP;
		s += BIAS;
		int exp = 7;
		for (int mask = 0x4000; (s & mask) == 0 && exp > 0; exp--, mask >>= 1) { }
		int mant = (s >> (exp + 3)) & 0x0F;
		return (byte) ~(sign | (exp << 4) | mant);
	}

	public static short decode(byte u) {
		int v = ~u & 0xFF;
		int sign = v & 0x80, exp = (v >> 4) & 7, mant = v & 0x0F;
		int s = ((mant << 3) + BIAS) << exp;
		s -= BIAS;
		return (short) (sign != 0 ? -s : s);
	}

	public static void encode(short[] in, int n, byte[] out) { for (int i = 0; i < n; i++) out[i] = encode(in[i]); }
	public static void decode(byte[] in, int n, short[] out) { for (int i = 0; i < n; i++) out[i] = decode(in[i]); }
}
