package com.avyansh.battleroyale;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.lang.ref.WeakReference;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

public class Game3DView extends GLSurfaceView {

	private static final float STICK_RADIUS_DP   = 70f;
	private static final float DEAD_ZONE         = 0.14f;
	private static final float SPRINT_THRESHOLD  = 0.90f;
	private static final long  TAP_MAX_MS        = 220L;
	private static final float GYRO_DP_PER_DEG   = 1.6f;
	private static final float GYRO_YAW_SIGN     =  1f;
	private static final float GYRO_PITCH_SIGN   = -1f;

	private static volatile WeakReference<Game3DView> sCurrent = new WeakReference<>(null);
	public  static Game3DView getCurrent() { return sCurrent.get(); }

	public interface JoystickListener {
		void onJoystick(boolean visible, float originX, float originY,
		float knobX, float knobY, float radius);
	}
	public interface LookTapListener {
		void onLookTap(float x, float y);
	}

	private volatile JoystickListener joystickListener;
	private volatile LookTapListener  lookTapListener;
	public void setJoystickListener(JoystickListener l) { joystickListener = l; }
	public void setLookTapListener  (LookTapListener  l) { lookTapListener  = l; }

	private final GameEngine   engine;
	private final GameRenderer renderer;
	private final float density;
	private float stickRadius;
	private float stickRadiusInv;
	// customizable joystick (set by the HUD from hud_layout.xml / the HUD editor)
	private boolean fixedStick = false;
	private float anchorX = -1f, anchorY = -1f;
	private float zoneL = 0f, zoneT = 0f, zoneR = -1f, zoneB = -1f;
	private final float deadZone;
	private final float touchSlop;

	private int    moveId = -1;
	private float  moveX0, moveY0;
	private float  moveX,  moveY;
	private float  lastMoveF = Float.NaN, lastMoveS = Float.NaN;
	private boolean sprinting;

	private int   lookId = -1;
	private float lookX, lookY;
	private long  lookDownTime;
	private float lookDownX, lookDownY;
	private boolean lookMoved;

	private float lastTouchX, lastTouchY;

	private volatile float   sensitivity = 1f;
	private volatile boolean gyroAim     = true;

	private final SensorManager sensorManager;
	private final Sensor gyroSensor;
	private long lastGyroTime;

	private final SensorEventListener gyroListener = new SensorEventListener() {
		@Override public void onSensorChanged(SensorEvent e) {
			if (!gyroAim || lookId >= 0) return;
			if (lastGyroTime == 0L) { lastGyroTime = e.timestamp; return; }
			float dt = (e.timestamp - lastGyroTime) * 1e-9f;
			lastGyroTime = e.timestamp;
			float yawRate   = e.values[1];
			float pitchRate = e.values[0];
			float yawDp   = (float) Math.toDegrees(yawRate  ) * dt * GYRO_DP_PER_DEG * GYRO_YAW_SIGN   * sensitivity;
			float pitchDp = (float) Math.toDegrees(pitchRate) * dt * GYRO_DP_PER_DEG * GYRO_PITCH_SIGN * sensitivity;
			engine.addLook(yawDp, pitchDp);
		}
		@Override public void onAccuracyChanged(Sensor s, int a) { }
	};

	public Game3DView(Context context) {
		super(context);
		setPreserveEGLContextOnPause(true);
		setEGLContextClientVersion(2);
		setEGLConfigChooser(new SafeDepthChooser(context));

		density = context.getResources().getDisplayMetrics().density;

		engine   = new GameEngine();
		engine.sound.init(context);
		renderer = new GameRenderer(engine);
		renderer.setDensity(density);
		renderer.setContext(context.getApplicationContext());
		engine.setRenderer(renderer);
		setRenderer(renderer);
		setRenderMode(RENDERMODE_CONTINUOUSLY);

		stickRadius    = STICK_RADIUS_DP * density;
		stickRadiusInv = 1f / stickRadius;
		deadZone       = DEAD_ZONE;
		touchSlop      = ViewConfiguration.get(context).getScaledTouchSlop();

		sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		gyroSensor    = sensorManager == null
		? null
		: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

		sCurrent = new WeakReference<>(this);
	}

	@Override protected void onDetachedFromWindow() {
		onPause();
		super.onDetachedFromWindow();
		if (sCurrent.get() == this) sCurrent = new WeakReference<>(null);
	}

	@Override public void onPause() {
		super.onPause();
		cancelInput();
		if (sensorManager != null) sensorManager.unregisterListener(gyroListener);
		lastGyroTime = 0L;
		engine.sound.release();
	}

	@Override public void onResume() {
		super.onResume();
		// onPause() releases the SoundPool; without this the game stayed silent after the app was backgrounded
		if (!engine.sound.isReady()) engine.sound.init(getContext());
		if (renderer != null) renderer.notifyResumed();
		if (sensorManager != null && gyroSensor != null && gyroAim) {
			sensorManager.registerListener(gyroListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
		}
	}

	@Override public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		if (!hasWindowFocus) cancelInput();
	}

	public GameEngine   getEngine()       { return engine; }
	public GameRenderer getGameRenderer() { return renderer; }
	public float        getLastTouchX()   { return lastTouchX; }
	public float        getLastTouchY()   { return lastTouchY; }
	public boolean      isSprinting()     { return sprinting; }
	public float        getSensitivity()  { return sensitivity; }
	public float        getStickRadius()  { return stickRadius; }

	/** fixed = the stick always starts from (x,y); floating = it starts where the finger lands. */
	public void setStickAnchor(float x, float y, boolean fixed) { anchorX = x; anchorY = y; fixedStick = fixed; }
	/** Touches inside this rectangle start the joystick; all other touches look around. */
	public void setStickZone(float l, float t, float r, float b) { zoneL = l; zoneT = t; zoneR = r; zoneB = b; }
	public void setStickRadius(float px) {
		if (px < 20f) px = 20f;
		stickRadius = px;
		stickRadiusInv = 1f / px;
	}

	private boolean inStickZone(float x, float y) {
		if (zoneR < 0f) return x < getWidth() * 0.5f;
		return x >= zoneL && x <= zoneR && y >= zoneT && y <= zoneB;
	}

	public void setSensitivity(float s) {
		if (s < 0.1f) s = 0.1f;
		if (s > 3.0f) s = 3.0f;
		sensitivity = s;
	}

	public void setGyroAimEnabled(boolean enabled) {
		gyroAim = enabled;
		if (sensorManager == null) return;
		if (enabled && gyroSensor != null) {
			sensorManager.registerListener(gyroListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
		} else {
			sensorManager.unregisterListener(gyroListener);
		}
		lastGyroTime = 0L;
	}

	public void setFireHeld(boolean held) { engine.fireHeld = held; }
	public void requestJump() {
		if (engine.match.phase == MatchPhase.AIRPLANE) {
			engine.match.requestJump();
		} else {
			engine.jumpReq = true;
		}
	}
	public void requestReload() { engine.reloadReq = true; }
	public void requestMelee() { engine.meleeReq = true; }
	public void requestSwitch() { engine.switchReq = true; }
	public void requestDropWeapon() { engine.dropWeaponReq = true; }
	public void setAiming(boolean aim) { engine.aiming = aim; }
	public void setCrouch(boolean on) { engine.crouchOn = on; }
	public void setRun(boolean on) { engine.runOn = on; }
	public void requestRespawn() { engine.respawnReq = true; }
	public void toggleView() { engine.toggleView(); }

	@Override public boolean onTouchEvent(MotionEvent e) {
		final int action = e.getActionMasked();
		final int idx    = e.getActionIndex();

		// LOBBY: drag anywhere to spin the hero 360 degrees
		if (engine.lobby) {
			if (action == MotionEvent.ACTION_DOWN) lastTouchX = e.getX();
			else if (action == MotionEvent.ACTION_MOVE) {
				float x = e.getX();
				engine.player.yaw += (x - lastTouchX) * 0.012f;
				lastTouchX = x;
			}
			return true;
		}

		switch (action) {
			case MotionEvent.ACTION_DOWN:
			case MotionEvent.ACTION_POINTER_DOWN: {
				final int   id = e.getPointerId(idx);
				final float x  = e.getX(idx), y = e.getY(idx);
				lastTouchX = x;
				lastTouchY = y;

				boolean stickZone = inStickZone(x, y);
				if (stickZone && moveId < 0) {
					moveId    = id;
					boolean useAnchor = fixedStick && anchorX >= 0f;
					moveX0    = useAnchor ? anchorX : x;
					moveY0    = useAnchor ? anchorY : y;
					moveX     = x;
					moveY     = y;
					sprinting = false;
					pushMove(0f, 0f);
					if (useAnchor) updateStick(x, y); else notifyJoystick(true);
				} else if (!stickZone && lookId < 0) {
					lookId       = id;
					lookX        = x;
					lookY        = y;
					lookDownX    = x;
					lookDownY    = y;
					lookDownTime = SystemClock.uptimeMillis();
					lookMoved    = false;
				}
				break;
			}

			case MotionEvent.ACTION_MOVE: {
				final int count = e.getPointerCount();
				for (int i = 0; i < count; i++) {
					final int   id = e.getPointerId(i);
					final float x  = e.getX(i), y = e.getY(i);

					if (id == moveId) {
						updateStick(x, y);
					} else if (id == lookId) {
						final float ddx = x - lookX;
						final float ddy = y - lookY;
						final float mag = Math.abs(ddx) + Math.abs(ddy);

						if (!lookMoved && mag > touchSlop * 0.5f) lookMoved = true;
						if (mag > 0f) {
							engine.addLook(ddx * sensitivity, ddy * sensitivity);
						}
						lookX = x;
						lookY = y;
					}
				}
				break;
			}

			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_POINTER_UP:
			case MotionEvent.ACTION_CANCEL: {
				final int id = e.getPointerId(idx);

				if (id == moveId) {
					moveId    = -1;
					sprinting = false;
					pushMove(0f, 0f);
					notifyJoystick(false);
				}

				if (id == lookId) {
					if (action != MotionEvent.ACTION_CANCEL
					&& !lookMoved
					&& SystemClock.uptimeMillis() - lookDownTime < TAP_MAX_MS
					&& lookTapListener != null) {
						lookTapListener.onLookTap(lookDownX, lookDownY);
					}
					lookId = -1;
				}

				if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
					cancelInput();
				}
				break;
			}
		}
		return true;
	}

	private void updateStick(float x, float y) {
		moveX = x;
		moveY = y;

		float dx  = (x - moveX0) * stickRadiusInv;
		float dy  = (y - moveY0) * stickRadiusInv;
		float len = (float) Math.sqrt(dx * dx + dy * dy);

		if (len > 1f) { dx /= len; dy /= len; len = 1f; }

		if (len < deadZone) {
			dx = dy = 0f;
			len = 0f;
		} else {
			float t = (len - deadZone) / (1f - deadZone);
			dx *= t / len;
			dy *= t / len;
			len = t;
		}

		sprinting = len >= SPRINT_THRESHOLD;
		pushMove(-dy, dx);
		notifyJoystick(true);
	}

	private void cancelInput() {
		moveId    = -1;
		lookId    = -1;
		sprinting = false;
		pushMove(0f, 0f);
		notifyJoystick(false);
		engine.fireHeld = false;
		engine.crouchOn = false;
		engine.aiming   = false;
	}

	private void pushMove(float f, float s) {
		if (f == lastMoveF && s == lastMoveS) return;
		lastMoveF = f;
		lastMoveS = s;
		engine.setMove(f, s);
	}

	private void notifyJoystick(boolean visible) {
		final JoystickListener l = joystickListener;
		if (l == null) return;

		if (!visible) {
			l.onJoystick(false, 0, 0, 0, 0, stickRadius);
			return;
		}

		float dx  = moveX - moveX0;
		float dy  = moveY - moveY0;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		float kx, ky;
		if (len > stickRadius) {
			kx = moveX0 + dx / len * stickRadius;
			ky = moveY0 + dy / len * stickRadius;
		} else {
			kx = moveX;
			ky = moveY;
		}
		l.onJoystick(true, moveX0, moveY0, kx, ky, stickRadius);
	}

	private static class SafeDepthChooser implements GLSurfaceView.EGLConfigChooser {
		private static final int EGL_OPENGL_ES2_BIT = 0x0004;
		private static final int EGL_SAMPLE_BUFFERS = 0x3032;
		private static final int EGL_SAMPLES        = 0x3031;

		private final Context ctx;
		SafeDepthChooser(Context ctx) { this.ctx = ctx; }

		@Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
			int[][] tiers = {
				{ 8, 8, 8, 0, 24, 8, 4 },
				{ 8, 8, 8, 0, 24, 8, 2 },
				{ 8, 8, 8, 0, 24, 8, 0 },
				{ 8, 8, 8, 0, 24, 0, 0 },
				{ 8, 8, 8, 0, 16, 0, 0 },
				{ 5, 6, 5, 0, 16, 0, 0 },
				{ 5, 6, 5, 0,  0, 0, 0 },
			};

			for (int[] t : tiers) {
				final int samples = t[6];
				final int[] attrs;

				if (samples > 0) {
					attrs = new int[] {
						EGL10.EGL_RED_SIZE,        t[0],
						EGL10.EGL_GREEN_SIZE,      t[1],
						EGL10.EGL_BLUE_SIZE,       t[2],
						EGL10.EGL_ALPHA_SIZE,      t[3],
						EGL10.EGL_DEPTH_SIZE,      t[4],
						EGL10.EGL_STENCIL_SIZE,    t[5],
						EGL_SAMPLE_BUFFERS,        1,
						EGL_SAMPLES,               samples,
						EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
						EGL10.EGL_NONE
					};
				} else {
					attrs = new int[] {
						EGL10.EGL_RED_SIZE,        t[0],
						EGL10.EGL_GREEN_SIZE,      t[1],
						EGL10.EGL_BLUE_SIZE,       t[2],
						EGL10.EGL_ALPHA_SIZE,      t[3],
						EGL10.EGL_DEPTH_SIZE,      t[4],
						EGL10.EGL_STENCIL_SIZE,    t[5],
						EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
						EGL10.EGL_NONE
					};
				}

				final int[] num = new int[1];
				if (!egl.eglChooseConfig(display, attrs, null, 0, num) || num[0] <= 0) continue;

				final EGLConfig[] out = new EGLConfig[1];
				if (!egl.eglChooseConfig(display, attrs, out, 1, num) || num[0] <= 0) continue;
				if (out[0] != null) return out[0];
			}

			throw new IllegalArgumentException("No suitable OpenGL ES 2.0 config");
		}
	}
}
