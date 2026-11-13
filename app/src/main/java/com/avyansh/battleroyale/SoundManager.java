package com.avyansh.battleroyale;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;

public class SoundManager {
	private static final String TAG = "SoundManager";
	private SoundPool pool;
	private final HashMap<String, Integer> sounds = new HashMap<>();
	private final HashMap<String, Long> lastPlayed = new HashMap<>();
	private final HashMap<String, Long> soundCooldowns = new HashMap<>();
	private boolean enabled = true;
	private float masterVolume = 0.85f;
	private Context ctx;
	
	private static final long DEFAULT_COOLDOWN_MS = 60L;
	
	public void init(Context context) {
		this.ctx = context.getApplicationContext();
		
		// Per-sound cooldowns
		soundCooldowns.put("fire_ar", 55L);
		soundCooldowns.put("fire_pistol", 55L);
		soundCooldowns.put("fire_shotgun", 200L);
		soundCooldowns.put("fire_sniper", 300L);
		soundCooldowns.put("reload", 200L);
		soundCooldowns.put("jump", 250L);
		soundCooldowns.put("hit", 140L);
		soundCooldowns.put("kill", 400L);
		soundCooldowns.put("land", 400L);
		soundCooldowns.put("chute", 500L);
		soundCooldowns.put("loot", 100L);
		soundCooldowns.put("hurt", 150L);
		soundCooldowns.put("dead", 800L);
		soundCooldowns.put("medkit", 300L);
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			AudioAttributes attrs = new AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_GAME)
			.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
			.build();
			pool = new SoundPool.Builder()
			.setMaxStreams(16)
			.setAudioAttributes(attrs)
			.build();
		} else {
			pool = new SoundPool(16, android.media.AudioManager.STREAM_MUSIC, 0);
		}
		
		loadAll();
	}
	
	private void loadAll() {
		if (ctx == null) return;
		load("fire_ar");
		load("fire_pistol");
		load("fire_shotgun");
		load("fire_sniper");
		load("reload");
		load("jump");
		load("hit");
		load("kill");
		load("land");
		load("chute");
		load("loot");
		load("hurt");
		load("dead");
		load("medkit");
	}
	
	private void load(String name) {
		if (ctx == null || pool == null) return;
		String[] extensions = { ".ogg", ".wav" };
		for (String ext : extensions) {
			String path = "sounds/" + name + ext;
			AssetFileDescriptor fd = null;
			try {
				AssetManager am = ctx.getAssets();
				fd = am.openFd(path);
				int id = pool.load(fd, 1);
				if (id != 0) {
					sounds.put(name, id);
					Log.i(TAG, "Loaded sound: " + path + " (id=" + id + ")");
					return;
				}
			} catch (Throwable t) {
				// try next
			} finally {
				if (fd != null) {
					try { fd.close(); } catch (Throwable ignored) {}
				}
			}
		}
		Log.i(TAG, "Sound missing: " + name + " (silent)");
	}
	
	private boolean canPlay(String name) {
		long now = System.currentTimeMillis();
		Long last = lastPlayed.get(name);
		Long cd = soundCooldowns.get(name);
		long cooldown = cd != null ? cd : DEFAULT_COOLDOWN_MS;
		if (last != null && now - last < cooldown) return false;
		lastPlayed.put(name, now);
		return true;
	}
	
	public void play(String name) {
		if (!enabled || pool == null) return;
		Integer id = sounds.get(name);
		if (id == null) return;
		if (!canPlay(name)) return;
		try {
			pool.play(id, masterVolume, masterVolume, 1, 0, 1f);
		} catch (Throwable ignored) {}
	}
	
	public void playPitched(String name, float pitch) {
		if (!enabled || pool == null) return;
		Integer id = sounds.get(name);
		if (id == null) return;
		if (!canPlay(name)) return;
		try {
			pool.play(id, masterVolume, masterVolume, 1, 0, pitch);
		} catch (Throwable ignored) {}
	}
    
    public void playPitchedVolume(String name, float pitch, float volumeScale) {
		if (!enabled || pool == null) return;
		Integer id = sounds.get(name);
		if (id == null) return;
		if (!canPlay(name)) return;
		try {
			// Volume is masterVolume * volumeScale
			pool.play(id, masterVolume * volumeScale, masterVolume * volumeScale, 1, 0, pitch);
		} catch (Throwable ignored) {}
	}
	
	/** Stereo playback with independent left/right gain (used by the 3D positional audio). */
	public void playStereo(String name, float left, float right, float rate) {
		if (!enabled || pool == null) return;
		Integer id = sounds.get(name);
		if (id == null) return;
		if (!canPlay(name)) return;
		try {
			pool.play(id, Math.min(1f, left * masterVolume), Math.min(1f, right * masterVolume), 1, 0, Math.max(0.5f, Math.min(2f, rate)));
		} catch (Throwable ignored) {}
	}
	
	private final PositionalAudio.Result audioTmp = new PositionalAudio.Result();
	
	/**
	 * Enemy gunfire at a world position: volume falls off with 3D distance, stereo pan follows the bearing relative to
	 * the camera, terrain/walls muffle it, and each weapon uses its own sound. Beyond the weapon's audible range nothing plays.
	 * @return true when the shot was audible
	 */
	public boolean playAt(int weaponType, float sx, float sy, float sz, GameEngine eng) {
		if (weaponType < 0 || weaponType >= Weapon.COUNT) return false;
		PositionalAudio.compute(eng.player.x, eng.player.y + 1.6f, eng.player.z, eng.camera.yaw, sx, sy, sz,
		weaponType, eng.audioOccluder(), audioTmp);
		if (!audioTmp.audible) return false;
		Weapon.Def d = Weapon.DEFS[weaponType];
		String name = "fire_" + d.sound;
		playStereo(name, audioTmp.left * 1.6f, audioTmp.right * 1.6f, d.pitch * audioTmp.rate * (0.8f + 0.2f * audioTmp.lowpass));
		return true;
	}
	
	public boolean isReady() { return pool != null; }
	public void setEnabled(boolean e) { enabled = e; }
	public void setVolume(float v) { masterVolume = Math.max(0f, Math.min(1f, v)); }
	
	public void release() {
		if (pool != null) {
			pool.release();
			pool = null;
		}
		sounds.clear();
		lastPlayed.clear();
		soundCooldowns.clear();
	}
}
