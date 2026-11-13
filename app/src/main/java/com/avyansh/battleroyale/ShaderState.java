package com.avyansh.battleroyale;

/**
 * Tiny global holder for the active shader program and the terrain detail texture.
 * Set by GameWorld.render() at the start of each frame.
 *
 * This avoids threading 8+ uniform handles through every helper method.
 */
public final class ShaderState {
    private ShaderState() {}

    private static int sProgram = 0;
    private static int sTerrainTex = 0;

    public static void set(int program, int terrainTex) {
        sProgram = program;
        sTerrainTex = terrainTex;
    }

    public static int current() { return sProgram; }
    public static int terrainTexture() { return sTerrainTex; }
}
