package com.avyansh.battleroyale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * PUBG-style match overlay drawn above the 3D view (online server matches only):
 * squad panel (slot number, name, health bar, knocked / eliminated, distance), teammate and ping markers projected into the
 * world with their distance, directional gunfire arcs (only for shots within 200 m), alive / squads / real-vs-bot counters,
 * the knocked banner and a hold-to-revive button. Touches outside the revive button fall through to the HUD below.
 */
public final class MatchOverlayView extends View {
	private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private final RectF rect = new RectF();
	private final RectF reviveBtn = new RectF();
	private final float dp;
	private GameEngine eng;
	private boolean reviving;
	private String reviveTarget;
	private final float[] proj = new float[2];

	public MatchOverlayView(Context c) {
		super(c);
		dp = c.getResources().getDisplayMetrics().density;
		t.setTypeface(Typeface.DEFAULT_BOLD);
		t.setShadowLayer(3f, 0f, 0f, Color.BLACK);
		setWillNotDraw(false);
	}

	public void setEngine(GameEngine e) { eng = e; }

	/** World point -> screen pixels using the camera's view-projection matrix. Returns false when behind the camera. */
	private boolean project(float x, float y, float z, float[] out) {
		float[] m = eng.camera.vp;
		float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
		if (cw < 0.1f) return false;
		float cx = (m[0] * x + m[4] * y + m[8] * z + m[12]) / cw;
		float cy = (m[1] * x + m[5] * y + m[9] * z + m[13]) / cw;
		out[0] = (cx * 0.5f + 0.5f) * getWidth();
		out[1] = (1f - (cy * 0.5f + 0.5f)) * getHeight();
		return true;
	}

	@Override protected void onDraw(Canvas c) {
		if (eng == null || !ServerNet.inServerMatch()) return;
		int w = getWidth(), h = getHeight();
		MatchPhase ph = eng.match.phase;
		boolean live = ph == MatchPhase.PLAYING || ph == MatchPhase.DEAD || ph == MatchPhase.FREEFALL || ph == MatchPhase.PARACHUTE;
		drawCounters(c, w);
		if (live) {
			drawSquadPanel(c);
			drawWorldMarkers(c, w, h);
			drawGunfire(c, w, h);
		}
		drawKnocked(c, w, h);
		drawRevive(c, w, h);
		postInvalidateOnAnimation();
	}

	// ------------------------------------------------------------------ counters
	private void drawCounters(Canvas c, int w) {
		String a = "ALIVE " + ServerNet.aliveTotal;
		String b = ServerNet.aliveTeams > 0 ? "SQUADS " + ServerNet.aliveTeams : "";
		String d = ServerNet.serverRealPlayers() + " PLAYERS • " + ServerNet.serverBots() + " BOTS";
		float y = 78f * dp;
		t.setTextSize(11f * dp);
		float cw = Math.max(t.measureText(a + "   " + b), t.measureText(d)) + 20f * dp;
		rect.set(w * 0.5f - cw / 2f, y, w * 0.5f + cw / 2f, y + 38f * dp);
		p.setColor(0x99000000); p.setStyle(Paint.Style.FILL);
		c.drawRoundRect(rect, 10f * dp, 10f * dp, p);
		t.setTextAlign(Paint.Align.CENTER);
		t.setColor(0xFFFFD45A);
		c.drawText(b.length() > 0 ? a + "   " + b : a, w * 0.5f, y + 16f * dp, t);
		t.setTextSize(9f * dp); t.setColor(0xFFB8C4CC);
		c.drawText(d, w * 0.5f, y + 31f * dp, t);   // bots are always reported separately, never as players
	}

	// ------------------------------------------------------------------ squad panel
	private void drawSquadPanel(Canvas c) {
		SquadModel sq = ServerNet.squad;
		float x = 10f * dp, y = 120f * dp, rowH = 34f * dp, wRow = 150f * dp;
		for (int i = 0; i < sq.members.size(); i++) {
			SquadModel.Member m = sq.members.get(i);
			float top = y + i * (rowH + 4f * dp);
			rect.set(x, top, x + wRow, top + rowH);
			p.setStyle(Paint.Style.FILL);
			p.setColor(m.isMe ? 0xAA1E3A5A : 0x99000000);
			c.drawRoundRect(rect, 8f * dp, 8f * dp, p);
			// slot number badge
			p.setColor(m.state == SquadModel.DEAD ? 0xFF555555 : m.state == SquadModel.KNOCKED ? 0xFFE53935 : 0xFF2E9BFF);
			c.drawCircle(x + 14f * dp, top + rowH / 2f, 10f * dp, p);
			t.setTextAlign(Paint.Align.CENTER); t.setColor(Color.WHITE); t.setTextSize(11f * dp);
			c.drawText(String.valueOf(m.slot + 1), x + 14f * dp, top + rowH / 2f + 4f * dp, t);
			// name + distance
			t.setTextAlign(Paint.Align.LEFT); t.setTextSize(10f * dp);
			t.setColor(m.state == SquadModel.DEAD ? 0xFF888888 : Color.WHITE);
			String nm = m.name.length() > 10 ? m.name.substring(0, 10) : m.name;
			c.drawText(nm, x + 30f * dp, top + 13f * dp, t);
			if (!m.isMe) {
				t.setTextAlign(Paint.Align.RIGHT); t.setColor(0xFFB8C4CC);
				c.drawText(SquadModel.distanceLabel(m.distance), x + wRow - 6f * dp, top + 13f * dp, t);
			}
			// health bar / status
			float bx = x + 30f * dp, by = top + 20f * dp, bw = wRow - 38f * dp, bh = 7f * dp;
			if (m.state == SquadModel.DEAD) {
				t.setTextAlign(Paint.Align.LEFT); t.setTextSize(9f * dp); t.setColor(0xFFFF7777);
				c.drawText("ELIMINATED", bx, by + bh, t);
			} else if (m.state == SquadModel.KNOCKED) {
				t.setTextAlign(Paint.Align.LEFT); t.setTextSize(9f * dp); t.setColor(0xFFFF5252);
				c.drawText("DOWN • NEEDS REVIVE", bx, by + bh, t);
			} else {
				rect.set(bx, by, bx + bw, by + bh);
				p.setColor(0x66FFFFFF);
				c.drawRoundRect(rect, 3f * dp, 3f * dp, p);
				float f = Math.max(0f, Math.min(1f, m.hp / 100f));
				rect.set(bx, by, bx + bw * f, by + bh);
				p.setColor(f > 0.5f ? 0xFF7DFF9A : f > 0.25f ? 0xFFFFC857 : 0xFFFF5252);
				c.drawRoundRect(rect, 3f * dp, 3f * dp, p);
			}
		}
	}

	// ------------------------------------------------------------------ teammate / ping markers in the world
	private void drawWorldMarkers(Canvas c, int w, int h) {
		SquadModel sq = ServerNet.squad;
		t.setTextAlign(Paint.Align.CENTER); t.setTextSize(10f * dp);
		for (int i = 0; i < sq.members.size(); i++) {
			SquadModel.Member m = sq.members.get(i);
			if (m.isMe || m.state == SquadModel.DEAD) continue;
			if (!project(m.x, m.y + 2.6f, m.z, proj)) continue;
			drawDiamond(c, proj[0], proj[1], m.state == SquadModel.KNOCKED ? 0xFFFF5252 : 0xFF7DFF9A);
			t.setColor(Color.WHITE);
			c.drawText((m.slot + 1) + "  " + SquadModel.distanceLabel(m.distance), proj[0], proj[1] - 9f * dp, t);
		}
		for (int i = 0; i < sq.markers.size(); i++) {
			SquadModel.Marker mk = sq.markers.get(i);
			float gy = eng.world.ground(mk.x, mk.z) + 1.5f;
			if (!project(mk.x, gy, mk.z, proj)) continue;
			int col = mk.kind == 1 ? 0xFFFF5252 : mk.kind == 2 ? 0xFFFFD54F : mk.kind == 3 ? 0xFF7DFF9A : 0xFFFFFFFF;
			drawDiamond(c, proj[0], proj[1], col);
			float d = (float) Math.hypot(mk.x - eng.player.x, mk.z - eng.player.z);
			t.setColor(col);
			c.drawText(SquadModel.distanceLabel(d), proj[0], proj[1] - 9f * dp, t);
		}
	}

	private void drawDiamond(Canvas c, float x, float y, int color) {
		float r = 6f * dp;
		path.reset(); path.moveTo(x, y - r); path.lineTo(x + r, y); path.lineTo(x, y + r); path.lineTo(x - r, y); path.close();
		p.setStyle(Paint.Style.FILL); p.setColor(color);
		c.drawPath(path, p);
		p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * dp); p.setColor(Color.WHITE);
		c.drawPath(path, p);
		p.setStyle(Paint.Style.FILL);
	}

	// ------------------------------------------------------------------ gunfire direction arcs (<= 200 m only)
	private void drawGunfire(Canvas c, int w, int h) {
		GunfireIndicators g = ServerNet.gunfire;
		float cx = w * 0.5f, cy = h * 0.5f, r = Math.min(w, h) * 0.30f;
		rect.set(cx - r, cy - r, cx + r, cy + r);
		p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(7f * dp); p.setStrokeCap(Paint.Cap.ROUND);
		for (int i = 0; i < GunfireIndicators.MAX; i++) {
			if (!g.active(i)) continue;
			float rel = g.relative(i, eng.camera.yaw);          // 0 = ahead, +90 = right
			int a = (int) (255 * g.alpha(i));
			p.setColor(Color.argb(a, 255, 70, 60));
			c.drawArc(rect, rel - 90f - 14f, 28f, false, p);   // canvas 0deg = 3 o'clock, so -90 = 12 o'clock
		}
		p.setStyle(Paint.Style.FILL);
	}

	// ------------------------------------------------------------------ knocked / revive
	private void drawKnocked(Canvas c, int w, int h) {
		if (!ServerNet.iAmKnocked) return;
		p.setStyle(Paint.Style.FILL); p.setColor(0x44B00020);
		c.drawRect(0, 0, w, h, p);
		t.setTextAlign(Paint.Align.CENTER); t.setTextSize(20f * dp); t.setColor(0xFFFF5252);
		c.drawText("YOU ARE DOWN", w * 0.5f, h * 0.28f, t);
		t.setTextSize(11f * dp); t.setColor(Color.WHITE);
		c.drawText("Crawl to cover — a teammate can revive you", w * 0.5f, h * 0.28f + 20f * dp, t);
	}

	private void drawRevive(Canvas c, int w, int h) {
		SquadModel.Member near = null;
		SquadModel sq = ServerNet.squad;
		if (!ServerNet.iAmKnocked && eng.player.alive) {
			for (int i = 0; i < sq.members.size(); i++) {
				SquadModel.Member m = sq.members.get(i);
				if (!m.isMe && m.state == SquadModel.KNOCKED && m.distance < 3.2f) { near = m; break; }
			}
		}
		if (near == null) {
			if (reviving) { reviving = false; if (reviveTarget != null) ServerNet.sendRevive(reviveTarget, false); reviveTarget = null; }
			reviveBtn.setEmpty();
			return;
		}
		reviveTarget = near.id;
		float bw = 150f * dp, bh = 44f * dp;
		reviveBtn.set(w * 0.5f - bw / 2f, h * 0.68f, w * 0.5f + bw / 2f, h * 0.68f + bh);
		p.setStyle(Paint.Style.FILL); p.setColor(reviving ? 0xFF2E9BFF : 0xCC1E7A3E);
		c.drawRoundRect(reviveBtn, 22f * dp, 22f * dp, p);
		t.setTextAlign(Paint.Align.CENTER); t.setTextSize(12f * dp); t.setColor(Color.WHITE);
		c.drawText(reviving ? "REVIVING " + near.name : "HOLD TO REVIVE", reviveBtn.centerX(), reviveBtn.centerY() + 4f * dp, t);
	}

	@Override public boolean onTouchEvent(MotionEvent e) {
		if (reviveBtn.isEmpty()) return false;
		int a = e.getActionMasked();
		boolean inside = reviveBtn.contains(e.getX(), e.getY());
		if (a == MotionEvent.ACTION_DOWN && inside && reviveTarget != null) { reviving = true; ServerNet.sendRevive(reviveTarget, true); return true; }
		if (reviving && (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)) { reviving = false; ServerNet.sendRevive(reviveTarget, false); return true; }
		return reviving;
	}
}
