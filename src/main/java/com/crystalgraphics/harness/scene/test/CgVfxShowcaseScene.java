package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The VFX showcase ({@link CgVfxShowcase}): sixteen effect spheres on a neon floor under a starfield, the camera
 * circling them slowly. Scene id {@code vfx-spheres}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.vfx.orbit=false    // fly it yourself
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.vfx.focus=11        // circle one sphere, close
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.vfx.look=-0.5,0.7,0.5 // look at the sky
 * // every moment of one wave's life (or every wave's, with true), each framed and photographed on the frame it happens:
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.vfx.moments=kamehameha -Dcrystalgraphics.harness.fixedDelta=0.0166667
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres-stress" -Dcrystalgraphics.harness.vfx.beams=60   // twice the load
 * }</pre>
 *
 * <p>Back row to front: gold, copper, mercury, colour-shift paint; soap bubble, crystal ball, hologram, ice; plasma,
 * lightning globe, lava world, supernova; black hole, galaxy in glass, force field, neon circuit.</p>
 *
 * <p>{@code vfx-spheres-stress} is the same scene with {@link #STRESS_BEAMS} beams instead of three
 * ({@link CgVfxShowcase#stress}), every one holding at once: the baseline for a frame full of effects, slow on purpose.
 * Its moments name lanes {@code beam00} and on.</p>
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
    /**
     * {@code -Dcrystalgraphics.harness.vfx.moments=<lane>}: photograph each moment that lane's wave announces
     * ({@code CgVfxMomentListener}) on the frame it happens, the camera framing it, as
     * {@code vfx-spheres-NN-<lane>-<moment>.png}, and exit when its first shot ends. {@code true} watches every lane:
     * {@code kamehameha}, {@code finalFlash}, {@code galickGun}.
     */
    private static final String MOMENTS = System.getProperty("crystalgraphics.harness.vfx.moments");
    private static final boolean ALL_LANES = "true".equals(MOMENTS);
    /** Where a moment's camera stands from what it frames, and how far for each block of the frame's radius. */
    private static final float[] MOMENT_VIEW = normalized(0.35f, 0.62f, 0.7f);
    private static final float MOMENT_DISTANCE = 1.55f;
    /** {@code -Dcrystalgraphics.harness.vfx.beams=<n>}: the stress scene's beams, 30 by default. */
    public static final int STRESS_BEAMS = Integer.getInteger("crystalgraphics.harness.vfx.beams", 30);

    private final CgVfxShowcase showcase;
    private final String id;
    private final Matrix4f orbitView = new Matrix4f();
    private boolean running = true;
    /** This frame's moments, framed on the first; null when none. */
    private StringBuilder momentNames;
    private double momentX, momentY, momentZ;
    private float momentRadius;
    private int momentCount;
    private final Set<String> lanesEnded = new HashSet<>();
    /** Each watched lane's first shot: a later one may start while it still lingers, and is not photographed. */
    private final Map<String, CgVfxEffect> firstShots = new HashMap<>();

    /** {@code vfx-spheres}: three beams. */
    public CgVfxShowcaseScene() {
        this("vfx-spheres", new CgVfxShowcase());
    }

    private CgVfxShowcaseScene(String id, CgVfxShowcase showcase) {
        this.id = id;
        this.showcase = showcase;
    }

    /** {@code vfx-spheres-stress}: {@link #STRESS_BEAMS} beams. */
    public static CgVfxShowcaseScene stress() {
        return new CgVfxShowcaseScene("vfx-spheres-stress", CgVfxShowcase.stress(STRESS_BEAMS));
    }

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, ORBIT_HEIGHT, ORBIT_RADIUS);
        ctx.getCamera3D().setPitch(-22f);
        ctx.getCamera3D().setMoveSpeed(4f);
        LOG.info("[" + id + "] " + showcase.laneCount() + " beams; back row to front: gold, copper, mercury, colour-shift paint | bubble, crystal, "
                + "hologram, ice | plasma, lightning, lava, supernova | black hole, galaxy, force field, circuit");
        if (MOMENTS != null && !"false".equals(MOMENTS)) showcase.vfx().onMoment((effect, name, x, y, z, radius) -> {
            String lane = showcase.laneOf(effect);
            if (lane == null || !(ALL_LANES || lane.equals(MOMENTS))) return;
            if (firstShots.computeIfAbsent(lane, l -> effect) != effect) return;
            String label = lane + "-" + name;
            if (momentNames == null) {
                momentNames = new StringBuilder(label);
                momentX = x;
                momentY = y;
                momentZ = z;
                momentRadius = radius;
            } else {
                momentNames.append('+').append(label);
            }
            if (name.equals(CgEnergyWave.MOMENT_END)) lanesEnded.add(lane);
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        float seconds = (float) frame.getElapsedTime();
        CgWorldRenderer world = CgWorldRenderer.get();
        // First, so a moment announced in it chooses this frame's camera.
        showcase.submit(world, 0.0, 0.0, 0.0, seconds);
        float camX, camY, camZ;
        Matrix4f view;
        if (momentNames != null) {
            float distance = Math.max(momentRadius * MOMENT_DISTANCE, 3f);
            camX = (float) momentX + MOMENT_VIEW[0] * distance;
            camY = (float) momentY + MOMENT_VIEW[1] * distance;
            camZ = (float) momentZ + MOMENT_VIEW[2] * distance;
            view = orbitView.setLookAt(camX, camY, camZ, (float) momentX, (float) momentY, (float) momentZ, 0f, 1f, 0f);
            ctx.getArtifactService().requestCapture(String.format("%02d-%s", ++momentCount, momentNames));
            momentNames = null;
            if (lanesEnded.size() >= (ALL_LANES ? showcase.laneCount() : 1)) running = false;
        } else if (EYE != null) {
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
        showcase.submitStage(world, 0.0, 0.0, 0.0, camX, camY, camZ);
        HarnessWorld.fire(ctx, view, ctx.getProjection());
    }

    private static float[] normalized(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / length, y / length, z / length};
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
