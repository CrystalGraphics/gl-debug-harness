package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

/**
 * The VFX showcase ({@link CgVfxShowcase}): sixteen effect spheres on a neon floor under a starfield, the camera
 * circling them slowly. Scene id {@code vfx-spheres}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.vfx.orbit=false    // fly it yourself
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.vfx.focus=11        // circle one sphere, close
 * }</pre>
 *
 * <p>Back row to front: gold, copper, mercury, colour-shift paint; soap bubble, crystal ball, hologram, ice; plasma,
 * lightning globe, lava world, star; black hole, galaxy in glass, force field, neon circuit.</p>
 */
public final class CgVfxShowcaseScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxShowcase");

    /** {@code -Dcrystalgraphics.harness.vfx.orbit=false}: the harness's own camera, flown with the mouse and keys. */
    private static final boolean ORBIT = false;//!"false".equals(System.getProperty("crystalgraphics.harness.vfx.orbit"));
    private static final float ORBIT_RADIUS = 13.5f, ORBIT_HEIGHT = 6.2f, ORBIT_SPEED = 0.09f;
    /** {@code -Dcrystalgraphics.harness.vfx.focus=<0..15>}: the orbit circles that sphere, in the order above, close up. */
    private static final int FOCUS = Integer.getInteger("crystalgraphics.harness.vfx.focus", -1);
    /** {@code -Dcrystalgraphics.harness.vfx.focus.distance=<metres>}: how far from it, 6.5 by default. */
    private static final float FOCUS_RADIUS = Float.parseFloat(System.getProperty("crystalgraphics.harness.vfx.focus.distance", "6.5"));
    private static final float FOCUS_HEIGHT = 0.2f;

    private final CgVfxShowcase showcase = new CgVfxShowcase();
    private final Matrix4f orbitView = new Matrix4f();
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, ORBIT_HEIGHT, ORBIT_RADIUS);
        ctx.getCamera3D().setPitch(-22f);
        ctx.getCamera3D().setMoveSpeed(4f);
        LOG.info("[vfx-spheres] back row to front: gold, copper, mercury, colour-shift paint | bubble, crystal, "
                + "hologram, ice | plasma, lightning, lava, star | black hole, galaxy, force field, circuit");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        float seconds = (float) frame.getElapsedTime();
        CgWorldRenderer world = CgWorldRenderer.get();
        float camX, camY, camZ;
        Matrix4f view;
        if (FOCUS >= 0 && FOCUS < CgVfxShowcase.COUNT) {
            float tx = (FOCUS % 4 - 1.5f) * CgVfxShowcase.SPACING, tz = (FOCUS / 4 - 1.5f) * CgVfxShowcase.SPACING;
            float ty = CgVfxShowcase.HEIGHT;
            float angle = seconds * ORBIT_SPEED * 2f;
            camX = tx + (float) Math.sin(angle) * FOCUS_RADIUS;
            camY = ty + FOCUS_HEIGHT;
            camZ = tz + (float) Math.cos(angle) * FOCUS_RADIUS;
            view = orbitView.setLookAt(camX, camY, camZ, tx, ty, tz, 0f, 1f, 0f);
        } else if (ORBIT) {
            float angle = seconds * ORBIT_SPEED;
            camX = (float) Math.sin(angle) * ORBIT_RADIUS;
            camY = ORBIT_HEIGHT + 0.8f * (float) Math.sin(seconds * 0.23f);
            camZ = (float) Math.cos(angle) * ORBIT_RADIUS;
            view = orbitView.setLookAt(camX, camY, camZ, 0f, 1.1f, 0f, 0f, 1f, 0f);
        } else {
            camX = ctx.getCamera3D().getPosX();
            camY = ctx.getCamera3D().getPosY();
            camZ = ctx.getCamera3D().getPosZ();
            view = ctx.getCamera3D().getViewMatrix();
        }
        showcase.submit(world, 0.0, 0.0, 0.0, seconds);
        showcase.submitStage(world, 0.0, 0.0, 0.0, camX, camY, camZ);
        HarnessWorld.fire(ctx, view, ctx.getProjection());
    }

    @Override
    public void dispose() {
        showcase.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
