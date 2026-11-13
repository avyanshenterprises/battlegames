package com.avyansh.battleroyale;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Top-down island map: pinch to zoom, drag to pan, shows you, other players, towns and the airplane route. */
public class MapView extends View {
	private static final int RES = 640;
	private final float extent = TerrainGenerator.ISLAND_RADIUS + 160f;      // metres from the centre to the map edge
	private Bitmap terrain;
	private volatile boolean building;
	private GameEngine engine;

	private float scale;                       // pixels per metre
	private float centerX, centerZ;            // world point at the middle of the view
	private boolean follow = true;
	private float minScale, maxScale;

	private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Matrix m = new Matrix();
	private final Path tri = new Path();
	private final ScaleGestureDetector sgd;
	private boolean mini;
	private float lastX, lastY;
	private int dragId = -1;
	private long downT; private float downX, downY; private boolean moved;
	private final float dp;

	public MapView(Context c) { this(c, null); }

	public MapView(Context c, AttributeSet a) {
		super(c, a);
		dp = c.getResources().getDisplayMetrics().density;
		text.setColor(Color.WHITE);
		text.setTextAlign(Paint.Align.CENTER);
		text.setTextSize(10f * dp);
		text.setShadowLayer(3f, 0f, 0f, Color.BLACK);
		sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
			@Override public boolean onScale(ScaleGestureDetector d) { zoomBy(d.getScaleFactor()); return true; }
		});
	}

	public void setEngine(GameEngine e) { engine = e; }

	/** Minimap mode: fixed zoom around the player, no labels, taps go to the parent (opens the big map). */
	public void setMini(boolean on) {
		mini = on;
		if (on) {
			setClipToOutline(true);
			setOutlineProvider(new android.view.ViewOutlineProvider() {
				@Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 14f * dp); }
			});
		}
	}

	public void zoomBy(float f) {
		scale = Math.max(minScale, Math.min(maxScale, scale * f));
		invalidate();
	}

	public void recenter() { follow = true; invalidate(); }

	@Override protected void onSizeChanged(int w, int h, int ow, int oh) {
		if (mini) { scale = (Math.min(w, h) * 0.5f) / 260f; minScale = maxScale = scale; return; }
		float fit = Math.min(w, h) / (extent * 2f);
		minScale = fit * 0.9f;
		maxScale = fit * 9f;
		if (scale == 0f) scale = fit * 1.2f;
		scale = Math.max(minScale, Math.min(maxScale, scale));
	}

	/** Paints the real game world top-down: the game's own ground colours, hill shading, roads, forests, buildings. */
	private void buildTerrain() {
		if (terrain != null || building || engine == null) return;
		building = true;
		final TerrainGenerator g = engine.world.generator;
		new Thread(new Runnable() {
			@Override public void run() {
				try {
					final int n = RES;
					float cell = 2f * extent / n;
					float[] hg = new float[n * n];
					for (int j = 0; j < n; j++) for (int i = 0; i < n; i++)
						hg[j * n + i] = g.heightAt(-extent + (i + 0.5f) * cell, -extent + (j + 0.5f) * cell);
					int[] px = new int[n * n];
					float[] c = new float[3];
					for (int j = 0; j < n; j++) {
						for (int i = 0; i < n; i++) {
							float wx = -extent + (i + 0.5f) * cell, wz = -extent + (j + 0.5f) * cell;
							float h = hg[j * n + i];
							float hl = hg[j * n + Math.max(0, i - 1)], hr = hg[j * n + Math.min(n - 1, i + 1)];
							float hu = hg[Math.max(0, j - 1) * n + i], hd = hg[Math.min(n - 1, j + 1) * n + i];
							float gx = (hr - hl) / (2f * cell), gz = (hd - hu) / (2f * cell);
							float ny = 1f / (float) Math.sqrt(1f + gx * gx + gz * gz);
							float r, gr, bl;
							if (h <= 0f) {                                        // sea: lighter near the shore, deep further out
								float d = Math.min(1f, -h / 25f);
								r = 0.16f - 0.10f * d; gr = 0.47f - 0.22f * d; bl = 0.66f - 0.18f * d;
							} else {
								g.groundColor(wx, wz, h, ny, c);
								r = c[0]; gr = c[1]; bl = c[2];
								// forests: dark tree speckle
								float f = g.forestDensity(wx, wz);
								if (f > 0.15f) {
									int hash = (i * 73856093) ^ (j * 19349663);
									float rnd = ((hash & 0xFFFF) / 65535f);
									if (rnd < f * 0.55f) { r = r * 0.45f + 0.04f; gr = gr * 0.62f + 0.10f; bl = bl * 0.45f + 0.03f; }
								}
								// sun from the north-west: light slopes facing it, darken the others
								float sh = 1f + (-gx * 0.75f - gz * 0.75f) * 2.2f;
								sh = Math.max(0.6f, Math.min(1.35f, sh));
								r *= sh; gr *= sh; bl *= sh;
								// thin white surf on the shoreline
								if (h < 0.9f) { float t = 1f - h / 0.9f; r += (1f - r) * t * 0.5f; gr += (1f - gr) * t * 0.5f; bl += (1f - bl) * t * 0.5f; }
							}
							px[j * n + i] = Color.rgb(clamp255(r), clamp255(gr), clamp255(bl));
						}
					}
					Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
					bmp.setPixels(px, 0, n, 0, 0, n, n);
					// buildings: real footprints with their roof colours
					Canvas bc = new Canvas(bmp);
					Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
					for (Building bd : g.buildings) {
						float cx = (bd.x + extent) / cell, cz = (bd.z + extent) / cell;
						float hw = Math.max(1.4f, bd.w * 0.5f / cell), hd2 = Math.max(1.4f, bd.d * 0.5f / cell);
						bp.setStyle(Paint.Style.FILL); bp.setColor(0xFF1B1B1B);
						bc.drawRect(cx - hw - 0.9f, cz - hd2 - 0.9f, cx + hw + 0.9f, cz + hd2 + 0.9f, bp);
						bp.setColor(Color.rgb(clamp255(bd.roof[0] * 0.9f + 0.1f), clamp255(bd.roof[1] * 0.9f + 0.1f), clamp255(bd.roof[2] * 0.9f + 0.1f)));
						bc.drawRect(cx - hw, cz - hd2, cx + hw, cz + hd2, bp);
					}
					terrain = bmp;
				} catch (Throwable t) {
					android.util.Log.e("MapView", "terrain bitmap failed: " + t);
				} finally {
					building = false;
					postInvalidate();
				}
			}
		}).start();
	}

	private static int clamp255(float v) { return Math.max(0, Math.min(255, (int) (v * 255f))); }

	@Override protected void onAttachedToWindow() { super.onAttachedToWindow(); }

	@Override protected void onDraw(Canvas c) {
		super.onDraw(c);
		if (engine == null) return;
		buildTerrain();
		int w = getWidth(), h = getHeight();
		Player pl = engine.player;
		if (follow) { centerX = pl.x; centerZ = pl.z; }
		c.drawColor(0xFF0B2A3F);

		if (terrain != null) {
			float s = scale * (2f * extent / RES);
			m.reset();
			m.postScale(s, s);
			m.postTranslate(w * 0.5f + (-extent - centerX) * scale, h * 0.5f + (-extent - centerZ) * scale);
			p.setFilterBitmap(true);
			c.drawBitmap(terrain, m, p);
		} else {
			text.setTextSize(12f * dp);
			c.drawText("Loading map...", w * 0.5f, h * 0.5f, text);
			text.setTextSize(10f * dp);
			postInvalidateDelayed(300);
		}

		// PUBG-style 8x8 grid with letters / numbers
		if (!mini) {
			float cellM = 2f * extent / 8f;
			p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f); p.setColor(0x44FFFFFF);
			for (int k = 0; k <= 8; k++) {
				float gx = sx(-extent + k * cellM, w), gz = sy(-extent + k * cellM, h);
				c.drawLine(gx, sy(-extent, h), gx, sy(extent, h), p);
				c.drawLine(sx(-extent, w), gz, sx(extent, w), gz, p);
			}
			p.setStyle(Paint.Style.FILL);
			text.setTextSize(12f * dp);
			for (int k = 0; k < 8; k++) {
				float mx = sx(-extent + (k + 0.5f) * cellM, w), my = sy(-extent + (k + 0.5f) * cellM, h);
				c.drawText(String.valueOf((char) ('A' + k)), Math.max(14f * dp, Math.min(w - 14f * dp, mx)), 54f * dp, text);
				c.drawText(String.valueOf(k + 1), 14f * dp, Math.max(60f * dp, Math.min(h - 8f * dp, my)), text);
			}
			text.setTextSize(10f * dp);
		}
		p.setStyle(Paint.Style.FILL);

		// towns
		TerrainGenerator.Site[] sites = engine.world.generator.sites;
		if (sites != null) for (TerrainGenerator.Site s : sites) {
			float x = sx(s.x, w), y = sy(s.z, h);
			String label = s.type == TerrainGenerator.SITE_CITY ? "CITY" : s.type == TerrainGenerator.SITE_BASE ? "MILITARY BASE"
				: s.type == TerrainGenerator.SITE_FARM ? "FARMLAND" : "VILLAGE";
			if (!mini) { text.setTextSize(11f * dp); text.setFakeBoldText(true); c.drawText(label, x, y, text); text.setFakeBoldText(false); text.setTextSize(10f * dp); }
		}

		// zone: blue = current circle, white = next safe circle
		ZoneController zone = engine.zone;
		if (zone.isActive()) {
			p.setStyle(Paint.Style.STROKE);
			p.setStrokeWidth(2.2f * dp);
			p.setColor(0xFF3D9BFF);
			c.drawCircle(sx(zone.centerX(), w), sy(zone.centerZ(), h), zone.radius() * scale, p);
			if (!zone.isFinished()) {
				p.setColor(0xFFFFFFFF);
				p.setStrokeWidth(1.6f * dp);
				c.drawCircle(sx(zone.targetX(), w), sy(zone.targetZ(), h), zone.targetRadius() * scale, p);
			}
			p.setStyle(Paint.Style.FILL);
		}

		// airplane route
		if (engine.match.phase == MatchPhase.AIRPLANE || engine.match.phase == MatchPhase.FREEFALL) {
			Airplane a = engine.match.plane;
			p.setColor(0xCCFFD54F); p.setStrokeWidth(2f * dp); p.setStyle(Paint.Style.STROKE);
			c.drawLine(sx(a.x - a.dirX * 3000f, w), sy(a.z - a.dirZ * 3000f, h), sx(a.x + a.dirX * 3000f, w), sy(a.z + a.dirZ * 3000f, h), p);
			p.setStyle(Paint.Style.FILL);
			p.setColor(0xFFFFFFFF);
			drawArrow(c, sx(a.x, w), sy(a.z, h), (float) Math.toDegrees(Math.atan2(a.dirX, -a.dirZ)), 9f * dp, 0xFFFFD54F);
		}

		// squad pings (online): diamonds that fade after SquadModel.MARKER_LIFE seconds
		if (ServerNet.inServerMatch()) {
			for (int i = 0; i < ServerNet.squad.markers.size(); i++) {
				SquadModel.Marker mk = ServerNet.squad.markers.get(i);
				float mx = sx(mk.x, w), my = sy(mk.z, h);
				int col = mk.kind == 1 ? 0xFFFF5252 : mk.kind == 2 ? 0xFFFFD54F : mk.kind == 3 ? 0xFF7DFF9A : 0xFFFFFFFF;
				p.setStyle(Paint.Style.FILL); p.setColor(col);
				float r = 6f * dp;
				tri.reset(); tri.moveTo(mx, my - r); tri.lineTo(mx + r, my); tri.lineTo(mx, my + r); tri.lineTo(mx - r, my); tri.close();
				c.drawPath(tri, p);
				if (!mini && mk.by != null) { NetPlayer o = null; synchronized (Net.remotes) { for (NetPlayer q : Net.remotes) if (q.id.equals(mk.by)) { o = q; break; } } if (o != null) c.drawText(o.name, mx, my - r - 3f * dp, text); }
			}
		}
		// other players (online server matches: teammates only, enemies are revealed by gunfire, not by the map)
		if (Net.active && Net.inMatch) synchronized (Net.remotes) {
			for (NetPlayer n : Net.remotes) {
				if (!n.alive) continue;
				if (ServerNet.inServerMatch() && !Net.isFriend(n)) continue;
				drawArrow(c, sx(n.x, w), sy(n.z, h), (float) Math.toDegrees(n.yaw), 7f * dp, Net.isFriend(n) ? 0xFF4CD964 : 0xFFFF5252);
				if (!mini) c.drawText(n.name, sx(n.x, w), sy(n.z, h) - 10f * dp, text);
			}
		}

		// you
		float yawDeg = (engine.match.phase == MatchPhase.AIRPLANE) ? (float) Math.toDegrees(Math.atan2(engine.match.plane.dirX, -engine.match.plane.dirZ)) : engine.camera.yaw;
		float px = engine.match.phase == MatchPhase.AIRPLANE ? engine.match.plane.x : pl.x;
		float pz = engine.match.phase == MatchPhase.AIRPLANE ? engine.match.plane.z : pl.z;
		p.setColor(0x5540C4FF);
		c.drawCircle(sx(px, w), sy(pz, h), 13f * dp, p);
		drawArrow(c, sx(px, w), sy(pz, h), yawDeg, 9f * dp, 0xFF40E0FF);

		if (mini) { postInvalidateDelayed(120); return; }
		// compass
		text.setTextSize(13f * dp);
		c.drawText("N", w - 22f * dp, 62f * dp, text);
		text.setTextSize(10f * dp);
		String sc = Math.round(80f / scale) + " m";
		p.setColor(0xFFFFFFFF); p.setStrokeWidth(2f * dp); p.setStyle(Paint.Style.STROKE);
		c.drawLine(16f * dp, h - 20f * dp, 16f * dp + 80f, h - 20f * dp, p);
		p.setStyle(Paint.Style.FILL);
		c.drawText(sc, 16f * dp + 40f, h - 26f * dp, text);
		postInvalidateDelayed(100);       // the player / enemies move
	}

	private float sx(float wx, int w) { return w * 0.5f + (wx - centerX) * scale; }
	private float sy(float wz, int h) { return h * 0.5f + (wz - centerZ) * scale; }

	private void drawArrow(Canvas c, float x, float y, float deg, float r, int color) {
		c.save();
		c.translate(x, y);
		c.rotate(deg);
		tri.reset();
		tri.moveTo(0, -r * 1.3f); tri.lineTo(r * 0.8f, r); tri.lineTo(0, r * 0.45f); tri.lineTo(-r * 0.8f, r); tri.close();
		p.setStyle(Paint.Style.FILL); p.setColor(color);
		c.drawPath(tri, p);
		p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * dp); p.setColor(Color.WHITE);
		c.drawPath(tri, p);
		p.setStyle(Paint.Style.FILL);
		c.restore();
	}

	@Override public boolean onTouchEvent(MotionEvent e) {
		if (mini) return false;
		sgd.onTouchEvent(e);
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				downT = System.currentTimeMillis(); downX = e.getX(); downY = e.getY(); moved = false;
				lastX = e.getX(); lastY = e.getY(); dragId = e.getPointerId(0);
				getParent().requestDisallowInterceptTouchEvent(true);
				break;
			case MotionEvent.ACTION_MOVE:
				if (!sgd.isInProgress() && e.getPointerCount() == 1) {
					float dx = e.getX() - lastX, dy = e.getY() - lastY;
					if (Math.abs(dx) + Math.abs(dy) > 2f) { follow = false; }
					if (Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > 12f * dp) moved = true;
					if (!follow) { centerX -= dx / scale; centerZ -= dy / scale; }
					invalidate();
				}
				lastX = e.getX(); lastY = e.getY();
				break;
			case MotionEvent.ACTION_UP:
				// long press on the full map = ping for the squad
				if (!moved && System.currentTimeMillis() - downT > 450 && ServerNet.inServerMatch()) {
					ServerNet.ping(centerX + (e.getX() - getWidth() * 0.5f) / scale, centerZ + (e.getY() - getHeight() * 0.5f) / scale, 0);
				}
				break;
			case MotionEvent.ACTION_POINTER_UP:
				int up = e.getActionIndex(), keep = up == 0 ? 1 : 0;
				if (keep < e.getPointerCount()) { lastX = e.getX(keep); lastY = e.getY(keep); }
				break;
		}
		return true;
	}
}
