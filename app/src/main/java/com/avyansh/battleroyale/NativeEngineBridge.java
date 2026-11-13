package com.avyansh.battleroyale;

/**
 * Optional JNI entry point for the native-engine scaffold.
 * This is not yet connected to Game3DView or the gameplay loop.
 */
public final class NativeEngineBridge {
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("avyansh_native");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private NativeEngineBridge() {}

    public static boolean isAvailable() { return AVAILABLE; }

    public static boolean initialize() {
        return AVAILABLE && nativeInitialize();
    }

    public static void update(float deltaSeconds) {
        if (AVAILABLE) nativeUpdate(deltaSeconds);
    }

    public static void shutdown() {
        if (AVAILABLE) nativeShutdown();
    }

    private static native boolean nativeInitialize();
    private static native void nativeUpdate(float deltaSeconds);
    private static native void nativeShutdown();
}
