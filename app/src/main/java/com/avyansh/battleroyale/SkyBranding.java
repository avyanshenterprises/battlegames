package com.avyansh.battleroyale;

import android.opengl.GLES20;
import android.opengl.Matrix;

public class SkyBranding {

    private final TextSprite sprite = new TextSprite();
    private final float[] model = new float[16];
    private final float[] mvp   = new float[16];

    // Cache last built text so we don't rebuild the texture every frame.
    private String lastText;
    private int    lastSize, lastColor, lastGlow;

    // Lazy-follow state: persistent yaw offset applied on top of BRANDING_YAW_DEG.
    // Interpolates toward camera yaw when BRANDING_LAZY_FOLLOW is on.
    private float followYawOffsetDeg = 0f;
    private long  lastRenderNs = 0L;

    public void init() {
        sprite.init();
        lastText = null;
        followYawOffsetDeg = 0f;
        lastRenderNs = 0L;
        rebuildIfNeeded();
    }

    // ------------------------------------------------------------------
    // Texture rebuild (only when text / style changes)
    // ------------------------------------------------------------------
    private void rebuildIfNeeded() {
        String t = GameSettings.BRANDING_TEXT;
        if (t == null) t = "";
        if (t.equals(lastText)
                && lastSize  == GameSettings.BRANDING_TEXT_SIZE_PX
                && lastColor == GameSettings.BRANDING_TEXT_COLOR
                && lastGlow  == GameSettings.BRANDING_GLOW_COLOR) {
            return;
        }
        lastText  = t;
        lastSize  = GameSettings.BRANDING_TEXT_SIZE_PX;
        lastColor = GameSettings.BRANDING_TEXT_COLOR;
        lastGlow  = GameSettings.BRANDING_GLOW_COLOR;
        sprite.setText(t, lastSize, lastColor, lastGlow, true);
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------
    public void render(GameCamera cam, float timeSec) {
        render(cam, timeSec, null);
    }

    /**
     * @param engine optional — used to modulate brightness with day/night.
     *               Pass null to always use full brightness (legacy behaviour).
     */
    public void render(GameCamera cam, float timeSec, GameEngine engine) {
        if (!GameSettings.BRANDING_VISIBLE) return;
        rebuildIfNeeded();
        if (!sprite.isReady()) return;

        // --- 1. Lazy-follow: slowly rotate the sign's yaw toward camera heading ---
        updateFollowYaw(cam, timeSec);

        // --- 2. Effective yaw / pitch ---
        //     Wobble adds a small sine offset so the sign never looks perfectly static.
        float wobble = GameSettings.BRANDING_YAW_WOBBLE_DEG
                * (float) Math.sin(timeSec * 0.7f);
        float yawDeg = GameSettings.BRANDING_YAW_DEG + followYawOffsetDeg + wobble;
        float pitchDeg = GameSettings.BRANDING_PITCH_DEG
                + 0.4f * (float) Math.sin(timeSec * 0.5f);   // slight vertical wobble

        float yr = (float) Math.toRadians(yawDeg);
        float pr = (float) Math.toRadians(pitchDeg);
        float dx = (float) (Math.sin(yr) * Math.cos(pr));
        float dy = (float) Math.sin(pr);
        float dz = (float) (-Math.cos(yr) * Math.cos(pr));

        // --- 3. Clamp distance inside sky & FAR so it never pokes behind fog ---
        float dist = GameSettings.BRANDING_DISTANCE;
        float maxDist = GameSettings.FAR * 0.90f;          // stay clear of far clip
        if (dist > maxDist) dist = maxDist;
        float minDist = 50f;
        if (dist < minDist) dist = minDist;

        // --- 4. Bob & sway ---
        float bob  = GameSettings.BRANDING_BOB_AMPLITUDE
                * (float) Math.sin(timeSec * GameSettings.BRANDING_BOB_SPEED * 6.2831853f);
        float sway = GameSettings.BRANDING_SWAY_AMPLITUDE
                * (float) Math.sin(timeSec * GameSettings.BRANDING_SWAY_SPEED * 6.2831853f + 1.1f);

        // Sway is perpendicular to the camera-forward direction (horizontal)
        float rightX = (float) Math.cos(yr);
        float rightZ = (float) Math.sin(yr);

        float cx = cam.pos[0] + dx * dist + rightX * sway;
        float cy = cam.pos[1] + dy * dist + bob;
        float cz = cam.pos[2] + dz * dist + rightZ * sway;

        // --- 5. Billboard basis (cylindrical: up stays world-up) ---
        float tx = -dx, ty = -dy, tz = -dz;
        float rx = tz, rz = -tx;
        float len = (float) Math.sqrt(rx * rx + rz * rz);
        if (len < 1e-4f) { rx = 1f; rz = 0f; len = 1f; }
        rx /= len;
        rz /= len;
        float ux =  ty * rz;
        float uy =  tz * rx - tx * rz;
        float uz = -ty * rx;

        // --- 6. Scale heartbeat ---
        float scale = 1f + GameSettings.BRANDING_SCALE_PULSE
                * (float) Math.sin(timeSec * 1.6f);
        float w = GameSettings.BRANDING_WIDTH * scale;
        float h = w / sprite.getAspect();

        // --- 7. Build model matrix (column-major) ---
        model[0]  = rx * w;  model[1]  = 0f;     model[2]  = rz * w;  model[3]  = 0f;
        model[4]  = ux * h;  model[5]  = uy * h; model[6]  = uz * h;  model[7]  = 0f;
        model[8]  = tx;      model[9]  = ty;     model[10] = tz;      model[11] = 0f;
        model[12] = cx;      model[13] = cy;     model[14] = cz;      model[15] = 1f;
        Matrix.multiplyMM(mvp, 0, cam.vp, 0, model, 0);

        // --- 8. Lighting-aware brightness ---
        float light = 1f;
        if (engine != null) {
            // Brighter at night, softer in full daylight; keeps sign readable without
            // washing out against a white sky.
            float resp = GameSettings.BRANDING_LIGHT_RESPONSE;
            float dayBright  = 0.78f;
            float nightBright = 1.00f;
            float target = dayBright + (nightBright - dayBright) * engine.nightFactor;
            light = 1f + (target - 1f) * resp;
        }

        // --- 9. Alpha: breathing glow + base pulse ---
        float breath = GameSettings.BRANDING_GLOW_BREATH
                * (float) Math.sin(timeSec * 0.9f);
        float pulse = GameSettings.BRANDING_PULSE
                * (float) Math.sin(timeSec * 1.6f);
        float alpha = (0.92f + pulse + breath) * light;
        if (alpha < 0f) alpha = 0f;
        if (alpha > 1f) alpha = 1f;

        // --- 10. Colour shimmer (subtle per-channel tint) ---
        float shim = GameSettings.BRANDING_SHIMMER;
        if (shim > 0.0001f) {
            float s = (float) Math.sin(timeSec * 2.3f) * shim;
            alpha *= (1f + s * 0.15f);
        }

        // --- 11. Draw ---
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        sprite.draw(mvp, alpha);
    }

    // ------------------------------------------------------------------
    // Lazy-follow: keeps the sign roughly in front of the player
    // ------------------------------------------------------------------
    private void updateFollowYaw(GameCamera cam, float timeSec) {
        if (!GameSettings.BRANDING_LAZY_FOLLOW) {
            // Ease the offset back to zero so switching the flag at runtime doesn't pop
            if (Math.abs(followYawOffsetDeg) > 0.05f) {
                followYawOffsetDeg *= 0.9f;
            } else {
                followYawOffsetDeg = 0f;
            }
            return;
        }
        // Camera yaw (deg) is authoritative; walk offset toward it with BRANDING_FOLLOW_SPEED deg/sec.
        float dt = 0.016f;
        if (lastRenderNs != 0L) {
            dt = Math.min(0.05f, (System.nanoTime() - lastRenderNs) / 1e9f);
        }
        lastRenderNs = System.nanoTime();

        float wantOffset = cam.yaw;                 // we want effective yaw == cam.yaw
        float delta = wantOffset - followYawOffsetDeg;
        // wrap into [-180, 180] to always take shortest path
        while (delta >  180f) delta -= 360f;
        while (delta < -180f) delta += 360f;
        float step = GameSettings.BRANDING_FOLLOW_SPEED * dt;
        if (delta >  step) delta =  step;
        if (delta < -step) delta = -step;
        followYawOffsetDeg += delta;
    }
}
