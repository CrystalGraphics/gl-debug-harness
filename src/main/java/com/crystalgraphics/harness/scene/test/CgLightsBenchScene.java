package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.render.world.CgWorldRenderer;

/**
 * Effect lights' cost: {@code n} point lights over the floor, a grid of them glowing on the ground, added to the
 * world's light list as a slot's light adds its own. Profile it for the GPU time the list costs: {@code world.lights}
 * bins them, {@code world.lightsApply} adds them onto the scene. Scene id {@code lights-bench}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-bench" -Dcrystalgraphics.harness.profile=300 \
 *     -Dcrystalgraphics.trace.channels=crystalgraphics,gpu
 * ... -Dcrystalgraphics.harness.lights=10000      // lights, 1,000 by default
 * }</pre>
 */
public final class CgLightsBenchScene implements InteractiveSceneLifecycle {

    private static final int LIGHTS = Integer.getInteger("crystalgraphics.harness.lights", 1000);
    /** Blocks between lights, how far each reaches, and the size of what glows. */
    private static final float SPACING = 1.5f, REACH = 2.5f, SIZE = 0.4f;
    /** As bright as a slot's light of that size: four times its glow, times {@code (100 × SIZE)²}. */
    private static final float INTENSITY = 4f * 1.0e4f * SIZE * SIZE;

    private int columns;

    @Override
    public void init(HarnessContext ctx) {
        columns = (int) Math.ceil(Math.sqrt(LIGHTS));
        float half = columns * SPACING * 0.5f;
        ctx.getCamera3D().moveCamera(0f, half * 1.1f, half * 1.4f);
        ctx.getCamera3D().setPitch(-40f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldLights lights = CgWorldRenderer.get().lights();
        float half = columns * SPACING * 0.5f;
        for (int i = 0; i < LIGHTS; i++) {
            float x = (i % columns) * SPACING - half, z = (i / columns) * SPACING - half;
            lights.add(x, 0.6, z, REACH, 0xE62E73FF, INTENSITY, 0f);
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return true; }

    @Override public boolean uses3DCamera() { return true; }

    @Override public boolean shouldShutdownOnComplete() { return false; }
}
