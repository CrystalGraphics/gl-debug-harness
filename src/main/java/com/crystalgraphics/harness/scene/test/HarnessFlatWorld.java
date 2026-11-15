package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgWorldQuery;

/**
 * A host's world that is stone below {@code ground} and open air above it, no block light anywhere: what the world
 * lights and the particles read the blocks round them from, under a scene's own floor at the same height.
 *
 * <pre>{@code
 * HarnessFlatWorld.provide(64, 15);                 // init: stone under y 64, full sky light over it
 * CgPlatform.provide(CgLevels.HOST, null);          // dispose
 * }</pre>
 *
 * <p>{@code skyLight} 0 is a world with no light at all, sky or block: the lights alone light it.</p>
 */
public final class HarnessFlatWorld implements CgWorldQuery {

    private final int ground, skyLight;

    public HarnessFlatWorld(int ground, int skyLight) {
        this.ground = ground;
        this.skyLight = skyLight;
    }

    /** Provides this world as the host's only level, with no entities. */
    public static void provide(int ground, int skyLight) {
        CgPlatform.provide(CgLevels.HOST, CgLevels.single(new HarnessFlatWorld(ground, skyLight), CgEntityQuery.NONE));
    }

    private boolean solid(int y) {
        return y < ground;
    }

    @Override
    public int collisionBoxes(int x, int y, int z, float[] out) {
        if (!solid(y) || out.length < 6) return solid(y) ? 1 : 0;
        out[0] = out[1] = out[2] = 0f;
        out[3] = out[4] = out[5] = 1f;
        return 1;
    }

    @Override public float collisionTop(int x, int y, int z) { return solid(y) ? 1f : Float.NaN; }
    @Override public float collisionBottom(int x, int y, int z) { return solid(y) ? 0f : Float.NaN; }
    @Override public int light(int x, int y, int z) { return solid(y) ? 0 : skyLight << 4; }
    @Override public int surface(int x, int y, int z) { return solid(y) ? SURFACE_STONE : SURFACE_NONE; }
    @Override public float hardness(int x, int y, int z) { return solid(y) ? 1.5f : Float.NaN; }
    @Override public int lightEmission(int x, int y, int z) { return 0; }
    @Override public int tint(int x, int y, int z) { return 0; }
    @Override public int biomeColor(int x, int y, int z, int kind) { return 0xFF808080; }
    @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
    @Override public int surfaceY(int x, int z, int kind) { return ground; }
    @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
    @Override public float fluidHeight(int x, int y, int z) { return Float.NaN; }
    @Override public int fluidKind(int x, int y, int z) { return FLUID_NONE; }
    @Override public int mapColor(int x, int y, int z) { return solid(y) ? 0xFF707070 : 0; }
    @Override public boolean loaded(int x, int z) { return true; }
    @Override public int minY() { return ground - 64; }
    @Override public int maxY() { return ground + 192; }
    @Override public int seaLevel() { return ground - 1; }
    @Override public int levelEpoch() { return 1; }
}
