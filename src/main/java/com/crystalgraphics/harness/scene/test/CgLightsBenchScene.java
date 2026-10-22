package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

/**
 * Effect lights' cost: {@code n} point lights over the floor, a grid of them glowing on the ground, drawn as the
 * beams' light pools draw them, a sphere's far wall each through {@code orb_light.shader}. Profile it for the GPU time
 * a light costs. Scene id {@code lights-bench}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-bench" -Dcrystalgraphics.harness.profile=300 \
 *     -Dcrystalgraphics.trace.channels=crystalgraphics,gpu,crystalgraphics.gpu.groups
 * ... -Dcrystalgraphics.harness.lights=10000      // lights, 1,000 by default
 * }</pre>
 */
public final class CgLightsBenchScene implements InteractiveSceneLifecycle {

    private static final int LIGHTS = Integer.getInteger("crystalgraphics.harness.lights", 1000);
    /** Blocks between lights, how far each reaches, and the source's size. */
    private static final float SPACING = 1.5f, REACH = 2.5f, SIZE = 0.4f;
    private static final String ORB_LIGHT = "crystalgraphics:shaders/vfx/beam/orb_light.shader";

    private final Matrix4f reach = new Matrix4f().scaling(REACH);
    private CgMaterial orbLight;
    private CgMesh sphere;
    private int columns;

    @Override
    public void init(HarnessContext ctx) {
        columns = (int) Math.ceil(Math.sqrt(LIGHTS));
        float half = columns * SPACING * 0.5f;
        ctx.getCamera3D().moveCamera(0f, half * 1.1f, half * 1.4f);
        ctx.getCamera3D().setPitch(-40f);
        orbLight = CgMaterial.load(ORB_LIGHT);
        sphere = CgMeshShapes.sphere(12, 16);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        float half = columns * SPACING * 0.5f;
        for (int i = 0; i < LIGHTS; i++) {
            float x = (i % columns) * SPACING - half, z = (i / columns) * SPACING - half;
            world.draw(sphere, orbLight).at(x, 0.6, z).transform(reach).custom(1, SIZE, 0f, 1f, 0f)
                    .custom(3, 0f, 0f, 0f, 1f).submit();
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
