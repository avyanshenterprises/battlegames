package com.avyansh.battleroyale;

import android.graphics.Color;

public final class GameSettings {
	private GameSettings() {}
	
	// ---------- Model Orientation ----------
	public static boolean MODEL_FACES_PLUS_Z = false;
	
	// ---------- Character Rigging ----------
	public static boolean AUTO_RIG_OBJ = true;
	public static float RIG_HIP_FRAC = 0.52f;
	public static float RIG_SHOULDER_FRAC = 0.80f;
	public static float RIG_ARM_START_FRAC = 0.55f;
	
	// ---------- Camera (FREE FIRE STYLE) ----------
	public static boolean DEFAULT_FIRST_PERSON = false;
	public static float FPP_EYE_HEIGHT = 1.68f;
	public static float FPP_EYE_HEIGHT_CROUCH = 1.15f;

	public static float TPP_DISTANCE = 3.4f;
	public static float TPP_HEIGHT = 1.40f;
	public static float TPP_HEIGHT_CROUCH = 1.10f;
	public static float TPP_SHOULDER = 0.48f;         // camera shifted right => body sits left of the crosshair
	public static float TPP_MIN_DISTANCE = 0.9f;
	public static float TPP_PITCH_MIN = -65f;         // look down limit (degrees)
	public static float TPP_PITCH_MAX = 70f;          // look up limit
	
	// ---------- Scope (Free Fire style) ----------
	public static float SCOPE_ZOOM_SMALL  = 1.4f;     // pistol / shotgun
	public static float SCOPE_ZOOM_AR     = 2.4f;     // assault rifle
	public static float SCOPE_ZOOM_SNIPER = 6.5f;     // sniper
	public static float SCOPE_BLEND_SPEED = 14f;      // how fast the camera slides into the scope
	public static float SCOPE_SPREAD_MUL  = 0.35f;    // bullet spread while scoped
	
	// ---------- Model facing ----------
	public static float CHARACTER_YAW_OFFSET_DEG = 0f;
	public static float AIRPLANE_YAW_OFFSET_DEG  = 0f;
	
	// ---------- GLB models ----------
	public static boolean HAND_IK = true;               // put both hands on the gun with IK (set false if arms look wrong)
	public static boolean STRIP_ROOT_MOTION = true;    // remove forward drift from Mixamo clips (game moves the character)
	public static boolean AUTO_DETECT_FACING = true;
	public static boolean GLB_FACES_PLUS_Z   = true;
	public static float   AIRPLANE_TARGET_SIZE = 40f;   // metres, longest horizontal size of an airplane GLB (0 = keep authored size)
	
	// ---------- Render budgets (triangles drawn per frame) ----------
	public static int MAX_TREE_TRIS     = 35000;      // all trees together
	public static int MAX_BUILDING_TRIS = 35000;      // all buildings together
	public static float TREE_HI_DIST    = 110f;       // full-detail trees only inside this range
	public static float TREE_MODEL_SCALE = 1.0f;
	public static int   TREE_MAX_MODEL_TRIS = 0;   // heavy tree GLB/OBJ files are simplified to this many triangles (no lag)      // size multiplier for a custom tree.obj
	
	// ---------- Camera Shake ----------
	public static float SHAKE_FIRE = 0f;
	public static float SHAKE_FIRE_DECAY = 6.5f;
	public static float SHAKE_HIT = 1.20f;
	public static float SHAKE_HIT_DECAY = 4.0f;
	public static float SHAKE_LAND = 0.70f;
	
	// ---------- Fall Damage ----------
	public static boolean FALL_DAMAGE_ON = true;
	public static float FALL_SAFE_SPEED = 9.5f;
	public static float FALL_DAMAGE_PER_UNIT = 1.5f;
	public static float FALL_FATAL_SPEED = 28f;
	
	// ---------- Healing ----------
	public static float HEAL_AMOUNT = 55f;
	public static float HEAL_DURATION = 4.5f;
	public static float HEAL_INTERRUPT_DAMAGE = 8f;
	public static int   MEDKIT_MAX_STACK = 3;
	
	// ---------- Swimming ----------
	public static float SWIM_DEPTH_OFFSET = 1.15f;
	public static float SWIM_SPEED = 3.2f;
	public static float SWIM_SURFACE_OFFSET = 0.55f;
	public static float SWIM_ENTER_DEPTH = 1.25f;
	public static float SWIM_EXIT_DEPTH = 0.65f;
	
	// ---------- Sky branding ----------
	public static boolean BRANDING_FACE_CAMERA = true;
	public static float   BRANDING_BOB_AMPLITUDE = 6f;
	public static float   BRANDING_BOB_SPEED = 0.45f;
	public static float   BRANDING_SWAY_AMPLITUDE = 12f;
	public static float   BRANDING_SWAY_SPEED = 0.30f;
	public static float   BRANDING_YAW_WOBBLE_DEG = 3f;
	public static float   BRANDING_GLOW_BREATH = 0.06f;
	public static float   BRANDING_SHIMMER = 0.10f;
	public static float   BRANDING_SCALE_PULSE = 0.02f;
	public static float   BRANDING_LIGHT_RESPONSE = 0.6f;
	public static boolean BRANDING_LAZY_FOLLOW = false;
	public static float   BRANDING_FOLLOW_SPEED = 20f;
	public static boolean BRANDING_VISIBLE = true;
	public static String  BRANDING_TEXT = "BRIJESH DEVELOPMENT";
	public static float   BRANDING_WIDTH = 560f;
	public static float   BRANDING_DISTANCE = 1000f;
	public static float   BRANDING_YAW_DEG = 0f;
	public static float   BRANDING_PITCH_DEG = 20f;
	public static int     BRANDING_TEXT_COLOR = Color.WHITE;
	public static int     BRANDING_GLOW_COLOR = Color.rgb(30, 140, 255);
	public static int     BRANDING_TEXT_SIZE_PX = 110;
	public static float   BRANDING_PULSE = 0.08f;
	
	public static float CLOUD_OPACITY = 0.55f;
	public static float CLOUD_COVER = 0.7f;
	
	public static float WATER_WAVE_AMP   = 0.9f;
	public static float WATER_WAVE_LEN   = 27f;
	public static float WATER_SHORE_FOAM = 0.45f;
	public static float WATER_GLINT      = 1.0f;
	public static float WATER_OPACITY    = 0.95f;
	
	public static float FOV_DEG = 45f;
	public static float NEAR = 0.5f;
	public static float FAR = 2200f;
	public static float CAMERA_SPEED = 25f;
	public static float LOOK_SENSITIVITY = 0.14f;
	public static float EYE_HEIGHT_MIN = 2.0f;
	
	public static int   WORLD_SEED = 20261104;
	public static int   CHUNK_CELLS = 32;
	public static float CELL_SIZE = 2f;
	public static int   VIEW_RADIUS_CHUNKS = 4;
	public static int   WORLD_HALF_CHUNKS = 24;
	public static float WATER_LEVEL = 0f;
	public static float CHUNK_BUILD_BUDGET_MS = 2f;
	
	public static float   DAY_LENGTH_SECONDS = 300f;
	public static float   START_TIME_OF_DAY = 0.5f;
	public static boolean FREEZE_TIME = true;
	
	public static float TREE_SCALE = 1.0f;
	public static float MAX_TREE_HEIGHT = 29f;
	public static int HOUSE_QUALITY = 2;
	
	// ---------- Performance ----------
	public static int MAX_BOTS_VISIBLE = 15;           // only animate/draw this many bots
	public static float BOT_ANIM_DIST = 60f;           // full animation only within this range
	public static float BOT_LOD_DIST = 30f;            // high LOD within this range
	public static int TREE_LOD_FAR = 3;                // 1=best, 3=cheapest for far trees
	public static int GRASS_DENSITY = 1;               // 0=none, 1=normal, 2=dense
	
	public static float chunkSize() { return CHUNK_CELLS * CELL_SIZE; }
	public static float fogEnd() { return VIEW_RADIUS_CHUNKS * chunkSize() * 0.9f; }
	public static float fogStart() { return fogEnd() * 0.30f; }
}
