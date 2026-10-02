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
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.vfx.look=-0.5,0.7,0.5 // look at the sky
 * }</pre>
 *
 * <p>Back row to front: gold, copper, mercury, colour-shift paint; soap bubble, crystal ball, hologram, ice; plasma,
 * lightning globe, lava world, supernova; black hole, galaxy in glass, force field, neon circuit.</p>
 */
public final class CgVfxShowcaseScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxShowcase");

    /** {@code -Dcrystalgraphics.harness.vfx.orbit=false}: the harness's own camera, flown with the mouse and keys. */
    private static final boolean ORBIT = false;//!"false".equals(System.getProperty("crystalgraphics.harness.vfx.orbit"));
    private static final float ORBIT_RADIUS = 13.5f, ORBIT_HEIGHT = 6.2f, ORBIT_SPEED = 0.09f;
    /** {@code -Dcrystalgraphics.harness.vfx.focus=<0..15>}: the orbit circles that sphere, in the order above, close up. */
    private static final int FOCUS = Integer.getInteger("crystalgraphics.harness.vfx.focus", -1);
    /** {@code -Dcrystalgraphics.harness.vfx.look=x,y,z}: from where the scene starts, look along that direction. */
    private static final float[] LOOK = parseLook(System.getProperty("crystalgraphics.harness.vfx.look"));
    /** {@code -Dcrystalgraphics.harness.vfx.eye=x,y,z} with {@code .vfx.at=x,y,z}: a fixed camera there, looking at that point. */
    private static final float[] EYE = parseLook(System.getProperty("crystalgraphics.harness.vfx.eye"));
    private static final float[] AT = parseLook(System.getProperty("crystalgraphics.harness.vfx.at", "0,1.35,0"));
    /** {@code -Dcrystalgraphics.harness.vfx.focus.distance=<metres>}: how far from it, 6.5 by default. */
    private static final float FOCUS_RADIUS = Float.parseFloat(System.getProperty("crystalgraphics.harness.vfx.focus.distance", "6.5"));
    /** {@code -Dcrystalgraphics.harness.vfx.focus.height=<metres>}: how far above it, 0.2 by default. */
    private static final float FOCUS_HEIGHT = Float.parseFloat(System.getProperty("crystalgraphics.harness.vfx.focus.height", "0.2"));

    private final CgVfxShowcase showcase = new CgVfxShowcase();
    private final Matrix4f orbitView = new Matrix4f();
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, ORBIT_HEIGHT, ORBIT_RADIUS);
        ctx.getCamera3D().setPitch(-22f);
        ctx.getCamera3D().setMoveSpeed(4f);
        LOG.info("[vfx-spheres] back row to front: gold, copper, mercury, colour-shift paint | bubble, crystal, "
                + "hologram, ice | plasma, lightning, lava, supernova | black hole, galaxy, force field, circuit");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        float seconds = (float) frame.getElapsedTime();
        CgWorldRenderer world = CgWorldRenderer.get();
        float camX, camY, camZ;
        Matrix4f view;
        if (EYE != null) {
            camX = EYE[0];
            camY = EYE[1];
            camZ = EYE[2];
            view = orbitView.setLookAt(camX, camY, camZ, AT[0], AT[1], AT[2], 0f, 1f, 0f);
        } else if (LOOK != null) {
            camX = 0f;
            camY = ORBIT_HEIGHT;
            camZ = ORBIT_RADIUS;
            view = orbitView.setLookAt(camX, camY, camZ, camX + LOOK[0], camY + LOOK[1], camZ + LOOK[2], 0f, 1f, 0f);
        } else if (FOCUS >= 0 && FOCUS < CgVfxShowcase.COUNT) {
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

    private static float[] parseLook(String value) {
        if (value == null) return null;
        String[] parts = value.split(",");
        return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2])};
    }

    @Override
    public void dispose() {
        showcase.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
