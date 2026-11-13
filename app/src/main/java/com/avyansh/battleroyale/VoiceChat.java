package com.avyansh.battleroyale;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Squad voice chat. Push-to-talk capture (16 kHz mono, mu-law, 40 ms frames) is sent to the server as `voice` messages; the server
 * relays them only to alive squad mates, and {@link #onFrame} plays them (one AudioTrack per speaker, mixed by the system).
 * Needs the RECORD_AUDIO permission for capture; playback works without it.
 */
public final class VoiceChat {
	private VoiceChat() {}
	private static final int RATE = 16000, FRAME = 640;          // 40 ms
	private static volatile boolean talking, running;
	private static Thread capture;
	private static int seq;
	private static final HashMap<String, AudioTrack> tracks = new HashMap<String, AudioTrack>();
	private static final HashMap<String, Long> lastFrame = new HashMap<String, Long>();
	private static final ConcurrentLinkedQueue<String> speaking = new ConcurrentLinkedQueue<String>();
	public static volatile boolean muted;                         // incoming muted by the player

	public static boolean isTalking() { return talking; }

	/** Push-to-talk. Returns false when the microphone could not be opened (permission missing). */
	public static synchronized boolean setTalking(boolean on) {
		if (!on) { talking = false; return true; }
		if (running) { talking = true; return true; }
		int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		if (min <= 0) return false;
		final AudioRecord rec;
		try {
			rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
			if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); return false; }
		} catch (SecurityException e) { return false; }
		running = true; talking = true;
		capture = new Thread(new Runnable() {
			@Override public void run() {
				short[] pcm = new short[FRAME / 2];
				byte[] enc = new byte[FRAME / 2];
				try {
					rec.startRecording();
					while (running) {
						int n = 0;
						while (n < pcm.length && running) { int r = rec.read(pcm, n, pcm.length - n); if (r <= 0) break; n += r; }
						if (n < pcm.length) break;
						if (!talking) { if (!ServerNet.inServerMatch()) break; continue; }
						MuLaw.encode(pcm, pcm.length, enc);
						ServerNet.sendVoice(seq++, Base64.encodeToString(enc, 0, enc.length, Base64.NO_WRAP));
					}
				} finally { try { rec.stop(); } catch (IllegalStateException ignored) { } rec.release(); running = false; }
			}
		}, "br-voice");
		capture.setDaemon(true);
		capture.start();
		return true;
	}

	/** A voice frame from a squad mate (called on the game thread). */
	public static void onFrame(String from, int frameSeq, String b64) {
		if (muted || b64 == null || b64.length() == 0) return;
		try {
			byte[] enc = Base64.decode(b64, Base64.NO_WRAP);
			if (enc.length == 0 || enc.length > FRAME) return;
			short[] pcm = new short[enc.length];
			MuLaw.decode(enc, enc.length, pcm);
			AudioTrack t = tracks.get(from);
			if (t == null) {
				int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
				t = new AudioTrack(AudioManager.STREAM_VOICE_CALL, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
					Math.max(min, FRAME * 6), AudioTrack.MODE_STREAM);
				t.play();
				tracks.put(from, t);
			}
			t.write(pcm, 0, pcm.length);     // non-blocking enough: buffer holds ~240 ms
			lastFrame.put(from, System.currentTimeMillis());
		} catch (IllegalArgumentException | IllegalStateException ignored) { }
	}

	/** Uid of the most recent speaker within 600 ms, or null (for the "speaking" icon). */
	public static String speaker() {
		long now = System.currentTimeMillis();
		for (java.util.Map.Entry<String, Long> e : lastFrame.entrySet()) if (now - e.getValue() < 600) return e.getKey();
		return null;
	}

	public static synchronized void release() {
		talking = false; running = false;
		for (AudioTrack t : tracks.values()) { try { t.stop(); } catch (IllegalStateException ignored) { } t.release(); }
		tracks.clear(); lastFrame.clear();
	}
}
