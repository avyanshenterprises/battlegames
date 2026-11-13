package com.avyansh.battleroyale;

import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GameRenderer implements GLSurfaceView.Renderer {
	
	public static boolean SHOW_DEBUG_HUD = true;
	public static boolean SHOW_CROSSHAIR = true;
	public static int QUALITY = 2;
	private static final float MAX_DT = 0.05f;
	
	private final GameEngine engine;
	private final SkyDome sky = new SkyDome();
	private final SkyBranding branding = new SkyBranding();
	private final CloudRenderer clouds = new CloudRenderer();
	private final TextSprite debugHud = new TextSprite();
	private final TextSprite crosshair = new TextSprite();
	private final EntityRenderer entities = new EntityRenderer();
	private final ZoneRenderer zoneRenderer = new ZoneRenderer();
	private final HudRenderer hud = new HudRenderer();
	private final ScopeOverlay scope = new ScopeOverlay();
	// Selected scope
	private volatile int selectedScope = ScopeOverlay.RED_DOT;
	private float currentZoom = 1f;
	private float zoomSpeed = 9f;
	
	private boolean scopeInit = false;
	private boolean hudReady = false;
	
	private final float[] ortho = new float[16];
	private final float[] hudModel = new float[16];
	private final float[] hudMvp = new float[16];
	private final float[] crosshairModel = new float[16];
	private final float[] crosshairMvp = new float[16];
	
	private int width = 1, height = 1;
	private float density = 1f;
	
	private long lastNs = 0L;
	private float time = 0f;
	private int frames = 0;
	private float fpsAccum = 0f;
	private int fps = 0;
	private String lastHud = "";
	
	private boolean skyInit, brandInit, debugHudInit, crosshairInit, worldInit, cloudsInit, effectsInit;
	private android.content.Context appContext;
	
	public interface FpsListener { void onFps(int fps, int chunksDrawn); }
	private FpsListener fpsListener;
	private int lastEmittedFps = -1, lastEmittedChunks = -1;
	public void setFpsListener(FpsListener l) { fpsListener = l; }
	
	private float damageFlashAlpha = 0f;
	private float hitMarkerAlpha = 0f;
	private float crosshairSpread = 0f;
	
	public void flashDamage() { damageFlashAlpha = 0.55f; }
	public void flashHitMarker() { hitMarkerAlpha = 0.9f; }
	// Add inside GameRenderer
	private boolean hitMarkerHeadshot = false;
	
	//public void flashHitMarker() { flashHitMarker(false); }
	
	public void flashHitMarker(boolean headshot) {
		hitMarkerAlpha = 0.9f;
		hitMarkerHeadshot = headshot;
	}
	public GameRenderer(GameEngine engine) {
		this.engine = engine;
	}
	
	public void setContext(android.content.Context ctx) {
		this.appContext = ctx.getApplicationContext();
	}
	
	public void notifyResumed() { lastNs = 0L; }
	
	@Override
	public void onSurfaceCreated(GL10 unused, EGLConfig config) {
		GLES20.glClearColor(0.55f, 0.72f, 0.92f, 1f);
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthFunc(GLES20.GL_LEQUAL);
		
		lastNs = 0L;
		frames = 0;
		fpsAccum = 0f;
		
		safely("world.init",     () -> { engine.world.init();     worldInit = true; });
		safely("models.init",    () -> {
			// a new GL context knows none of the old shader programs
			ModelShader.invalidate();
			SkinShader.invalidate();
			zoneRenderer.invalidate();
			if (appContext != null) ModelCache.init(appContext);
		});
		safely("sky.init",       () -> { sky.init();              skyInit = true; });
		safely("clouds.init",    () -> { clouds.init();           cloudsInit = true; });
		safely("effects.init",   () -> { engine.effects.init();   effectsInit = true; });
		safely("branding.init",  () -> { branding.init();         brandInit = true; });
		safely("debugHud.init",  () -> { debugHud.init();         debugHudInit = true; });
		safely("scope.init",     () -> { scope.init(); scope.resize(width, height, density); scopeInit = true; });
		safely("hud.init",       () -> { hud.init(width, height, density); hudReady = true; });
		safely("crosshair.init", () -> {
			crosshair.init();
			crosshair.setText("+", 40, Color.WHITE, 0, false);
			crosshairInit = true;
		});
		
		lastHud = "";
	}
	
	@Override
	public void onSurfaceChanged(GL10 unused, int w, int h) {
		width  = Math.max(1, w);
		height = Math.max(1, h);
		GLES20.glViewport(0, 0, width, height);
		engine.camera.setViewport(width, height);
		Matrix.orthoM(ortho, 0, 0f, width, 0f, height, -1f, 1f);
		if (hudReady) hud.resize(width, height);
		if (scopeInit) scope.resize(width, height, density);
	}
	
	public void setDensity(float d) { if (d > 0f) density = d; }
	
	@Override
	public void onDrawFrame(GL10 unused) {
		long now = System.nanoTime();
		float dt = lastNs == 0L ? 0.016f : (now - lastNs) / 1_000_000_000f;
		lastNs = now;
		if (dt > MAX_DT) dt = MAX_DT;
		time += dt;
		
		updateScopeZoom(dt);
		
		int steps = 1;
		float step = dt;
		if (dt > 0.033f) { steps = 2; step = dt * 0.5f; }
		for (int i = 0; i < steps; i++) {
			final float s = step;
			safely("engine.update", () -> engine.update(s));
			safely("world.update",  () -> engine.world.update(engine.camera));
		}
		
		GLES20.glDepthMask(true);
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
		
		if (skyInit)    safely("sky.render",      () -> sky.render(engine.camera, engine));
		final float frameDt = dt;
		
		if (cloudsInit) {
			safely("clouds.render",
			() -> clouds.render(engine.camera, engine, frameDt));
		}
		if (brandInit)  safely("branding.render", () -> branding.render(engine.camera, time, engine));
		if (worldInit)  safely("world.render",    () -> engine.world.render(engine.camera, engine, time));
		if (worldInit)  safely("entities",        () -> drawEntities());
		if (worldInit && !engine.lobby) safely("zone.render", () -> zoneRenderer.render(engine.camera, engine.zone, time));
		
		// Render effects (blood, tracers) after world/entities, before HUD
		if (effectsInit) safely("effects.render", () -> engine.effects.render(engine.camera));
		
        if (!engine.lobby) drawCrosshair();
		//if (scopeInit) safely("scope.render", () -> scope.render(engine.scopeFrameAlpha(), engine.sniperScope()));
		if (scopeInit) {
			
			final int activeScope = engine.sniperScope()
			&& selectedScope == ScopeOverlay.RED_DOT
			? ScopeOverlay.SCOPE_6X
			: selectedScope;
			
			safely("scope.render", () ->
			scope.render(
			engine.scopeFrameAlpha(),
			activeScope
			)
			);
		}
        if (false && hudReady && !engine.lobby) safely("hud.render", () -> hud.render(engine, engine.camera));

        if (!engine.lobby) drawDebugHud(dt);
		
		emitFpsIfChanged();
	}
	
	private void drawEntities() {
		GameWorld world = engine.world;
		GLES20.glUseProgram(world.getProgram());
		ShaderState.set(world.getProgram(), world.getTexture());
		GLES20.glUniformMatrix4fv(world.getUvp(), 1, false, engine.camera.vp, 0);
		GLES20.glUniform3f(world.getUcamPos(),
		engine.camera.x(), engine.camera.y(), engine.camera.z());
		GLES20.glUniform3fv(world.getUlightDir(), 1, engine.lightDir, 0);
		GLES20.glUniform3fv(world.getUlightColor(), 1, engine.lightColor, 0);
		GLES20.glUniform3fv(world.getUambient(), 1, engine.ambient, 0);
		
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, world.getTexture());
		GLES20.glUniform1i(world.getUtex(), 0);
		
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthMask(true);
		GLES20.glDisable(GLES20.GL_BLEND);
		GLES20.glEnable(GLES20.GL_CULL_FACE);
		GLES20.glCullFace(GLES20.GL_BACK);
		
		entities.render(engine, engine.camera, time,
		world.getaPos(), world.getaNormal(), world.getaColor(), world.getaUv(),
		world.getUvp(), world.getUmodel(), world.getUcamPos(),
		world.getUlightDir(), world.getUlightColor(),
		world.getUambient(), world.getUtex());
	}
	
	private void drawDebugHud(float dt) {
		if (!SHOW_DEBUG_HUD) return;
		
		frames++;
		fpsAccum += dt;
		if (fpsAccum >= 0.5f) {
			fps = Math.round(frames / fpsAccum);
			frames = 0;
			fpsAccum = 0f;
		}
		if (!debugHudInit || !debugHud.isReady()) return;
		
		if (fpsAccum == 0f) {
			int minutes = (int) (engine.timeOfDay * 24f * 60f);
			String s = "FPS " + fps
			+ "   Chunks " + engine.world.statDrawn + "/" + engine.world.statLoaded
			+ "   Tris " + (engine.world.statTriangles / 1000) + "k"
			+ " (trees " + (engine.world.statTreeTris / 1000) + "k, bld " + (engine.world.statBuildTris / 1000) + "k)"
			+ "   " + pad2(minutes / 60) + ":" + pad2(minutes % 60)
			+ "   Q" + QUALITY;
			if (!s.equals(lastHud)) {
				lastHud = s;
				debugHud.setText(s, (int) (16f * density), Color.WHITE, 0, false);
			}
		}
		
		float w = debugHud.getWidthPx(), h = debugHud.getHeightPx();
		float margin = 12f * density;
		Matrix.setIdentityM(hudModel, 0);
		Matrix.translateM(hudModel, 0, margin + w * 0.5f, margin + h * 0.5f, 0f);
		Matrix.scaleM(hudModel, 0, w, h, 1f);
		Matrix.multiplyMM(hudMvp, 0, ortho, 0, hudModel, 0);
		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		debugHud.draw(hudMvp, 1f);
	}
	
	private void drawCrosshair() {
		if (!SHOW_CROSSHAIR || !crosshairInit || !crosshair.isReady()) return;
		if (engine.scopeBlend > 0.4f) return;   // the scope frame has its own reticle
		
		// Dynamic spread calculation
		float targetSpread = 0f;
		if (engine.weapons.isReloading()) targetSpread += 20f;
		if (engine.player.speedNow > 0.1f) targetSpread += 10f * engine.player.speedNow; 
		if (engine.fireHeld) targetSpread += 15f; 
		if(engine.weapons.current()!=null) targetSpread += engine.weapons.current().spread * 500f; 
		
		crosshairSpread += (targetSpread - crosshairSpread) * 0.2f; 
		
		float baseSize = 8f * density;
		float finalSize = baseSize + crosshairSpread * density;
		
		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
		
		// Horizontal line
		Matrix.setIdentityM(crosshairModel, 0);
		Matrix.translateM(crosshairModel, 0, width * 0.5f, height * 0.5f, 0f);
		Matrix.scaleM(crosshairModel, 0, finalSize, 2f * density, 1f);
		Matrix.multiplyMM(crosshairMvp, 0, ortho, 0, crosshairModel, 0);
		crosshair.draw(crosshairMvp, 0.85f);
		
		// Vertical line
		Matrix.setIdentityM(crosshairModel, 0);
		Matrix.translateM(crosshairModel, 0, width * 0.5f, height * 0.5f, 0f);
		Matrix.scaleM(crosshairModel, 0, 2f * density, finalSize, 1f);
		Matrix.multiplyMM(crosshairMvp, 0, ortho, 0, crosshairModel, 0);
		crosshair.draw(crosshairMvp, 0.85f);
		
		// Hit Marker (Red X)
		if (hitMarkerAlpha > 0f) {
			hitMarkerAlpha -= 0.05f;
			Matrix.setIdentityM(crosshairModel, 0);
			Matrix.translateM(crosshairModel, 0, width * 0.5f, height * 0.5f, 0f);
			Matrix.rotateM(crosshairModel, 0, 45f, 0, 0, 1);
			Matrix.scaleM(crosshairModel, 0, finalSize * 1.5f, 2f * density, 1f);
			Matrix.multiplyMM(crosshairMvp, 0, ortho, 0, crosshairModel, 0);
			float cr = hitMarkerHeadshot ? 1.0f : 1.0f;
			float cg = hitMarkerHeadshot ? 0.9f : 0.0f;
			float cb = hitMarkerHeadshot ? 0.1f : 0.0f;
			crosshair.drawColor(crosshairMvp, hitMarkerAlpha, cr, cg, cb);
			
			Matrix.setIdentityM(crosshairModel, 0);
			Matrix.translateM(crosshairModel, 0, width * 0.5f, height * 0.5f, 0f);
			Matrix.rotateM(crosshairModel, 0, -45f, 0, 0, 1);
			Matrix.scaleM(crosshairModel, 0, finalSize * 1.5f, 2f * density, 1f);
			Matrix.multiplyMM(crosshairMvp, 0, ortho, 0, crosshairModel, 0);
			crosshair.drawColor(crosshairMvp, hitMarkerAlpha, cr, cg, cb);
		}
		
		// Damage flash (screen red overlay)
		if (damageFlashAlpha > 0f) {
			damageFlashAlpha -= 0.05f;
			GLES20.glDisable(GLES20.GL_DEPTH_TEST);
			GLES20.glEnable(GLES20.GL_BLEND);
			GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
			// Simple full screen red quad
			// (Simplified for this fix)
		}
		
		GLES20.glDisable(GLES20.GL_BLEND);
	}
	
	private void emitFpsIfChanged() {
		if (fpsListener == null) return;
		if (fps == lastEmittedFps && engine.world.statDrawn == lastEmittedChunks) return;
		lastEmittedFps = fps;
		lastEmittedChunks = engine.world.statDrawn;
		fpsListener.onFps(fps, engine.world.statDrawn);
	}
	
	private static String pad2(int v) { return v < 10 ? "0" + v : Integer.toString(v); }
	
	private static void safely(String tag, Runnable r) {
		try { r.run(); }
		catch (Throwable t) { android.util.Log.e("AvyanshGL", "subsystem " + tag + " failed: " + t, t); }
	}
	
	public void setScopeType(int type) {
		if (type < ScopeOverlay.RED_DOT || type > ScopeOverlay.SCOPE_6X) {
			return;
		}
		selectedScope = type;
	}
	
	public int getScopeType() {
		return selectedScope;
	}
	
	public float getCurrentZoom() {
		return currentZoom;
	}
	
	private void updateScopeZoom(float dt) {
		boolean aiming = engine.scopeFrameAlpha() > 0.05f;
		float targetZoom = 1f;
		
		if (aiming) {
			int type = selectedScope;
			// Existing sniper system compatibility
			if (engine.sniperScope() && type == ScopeOverlay.RED_DOT) {
				type = ScopeOverlay.SCOPE_6X;
			}
			
			targetZoom = scope.getZoom(type);
		}
		
		float factor = 1f - (float)Math.exp(-zoomSpeed * dt);
		currentZoom += (targetZoom - currentZoom) * factor;
		if (Math.abs(targetZoom - currentZoom) < 0.005f) {
			currentZoom = targetZoom;
		}
		
		engine.camera.setScopeZoom(currentZoom);
	}
}
