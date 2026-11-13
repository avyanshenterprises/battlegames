package com.avyansh.battleroyale;

/** Shared between the UI thread (HUD touch) and the GL thread (game loop). */
public class GameState {
	// ---------- input: written by HUD, read by renderer ----------
	public volatile float moveF, moveS;                       // joystick: forward, strafe (-1..1)
	public volatile boolean fireHeld, aimHeld, crouchOn, runOn;
	public volatile boolean jumpReq, reloadReq, switchReq, viewReq, respawnReq;
	
	private float lookDx, lookDy;
	
	public synchronized void addLook(float dx, float dy) {
		lookDx += dx;
		lookDy += dy;
	}
	
	public synchronized void takeLook(float[] out) {
		out[0] = lookDx;
		out[1] = lookDy;
		lookDx = 0f;
		lookDy = 0f;
	}
	
	// ---------- output: written by renderer, read by HUD ----------
	public volatile float health = 100f, maxHealth = 100f;
	public volatile int ammo, reserve, kills, botsAlive, fps, chunks, quality;
	public volatile String weaponName = "";
	public volatile boolean reloading, dead, thirdPerson = true;
	public volatile float reloadProgress, hitMarker, damageFlash, aimSpread;
}