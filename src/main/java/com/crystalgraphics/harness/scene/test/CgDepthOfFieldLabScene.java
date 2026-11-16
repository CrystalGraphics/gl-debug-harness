package com.crystalgraphics.harness.scene.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgTranslucencyPass;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.post.CgPost;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;

/**
 * Depth of field to play with (R14b): checkered pillars stepping away down the floor, rows of small lights near and far
 * that blur into the aperture's shape, and three rings of light far out, one for each translucency pass. The lens is
 * the client chain's camera settings ({@code CgPost.client().camera()}), as a camera rig's would be. Scene id
 * {@code dof-lab}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=dof-lab --rhi=vulkan"
 * // Up / Down: focus farther / nearer        F: focus on what the centre of the screen looks at
 * // K: pull focus between the nearest pillar and the rings, over 1.5 s
 * // Left / Right: f-stop wider / narrower    N: the lens's widest f-stop, f/1.2 or f/4 (round or bladed blur)
 * // B / Shift+B: one blade more / fewer      G: depth of field on or off
 * // Z / X: a shorter / longer lens (18 to 200 mm): the view's FOV on the sensor, and how much it blurs
 * }</pre>
 *
 * <p>The rings, left to right: {@code BeforeDOF} (blurred with the scene), {@code AfterDOF} and {@code AfterMotionBlur}
 * (sharp at any focus).</p>
 */
public final class CgDepthOfFieldLabScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final String DIR = "assets/harness/shader/";
    /** Unreal's full stops and the lens's widest, f/1.2. */
    private static final float[] STOPS = {1.2f, 1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f, 22f};
    private static final float NEAR_FOCUS = 2.5f, FAR_FOCUS = 30f, PULL_SECONDS = 1.5f;
    /** Where the pillars stand down the floor, and the rings. */
    private static final double[] PILLARS = {-2, -4, -7, -11, -16, -24, -36};
    private static final double RINGS_Z = -30;
    /** Lenses Z and X step through, in millimetres. */
    private static final float[] LENSES = {18f, 24f, 35f, 50f, 85f, 135f, 200f};

    private final List<CgMeshComponent> spawned = new ArrayList<>();
    /** Every solid's box, for focusing on what the centre looks at: min x, y, z, max x, y, z. */
    private final List<double[]> boxes = new ArrayList<>();
    private Camera3D camera;
    private float focus = 6f;
    private int stop = 2, blades = 5, lens = 3;
    private final Matrix4f projection = new Matrix4f();
    private boolean on = true, roundOpen = true;
    /** A focus pull under way: from, to, seconds so far; negative, none. */
    private float pullFrom, pullTo, pulled = -1f;

    @Override
    public void init(HarnessContext ctx) {
        camera = ctx.getCamera3D();
        camera.moveCamera(0f, 1.6f, 2f);
        camera.setYaw(0f);
        camera.setPitch(0f);
        camera.setMoveSpeed(6f);
        CgScene scene = CgScene.client();
        CgMesh cube = CgMeshShapes.cube(), ball = CgMeshShapes.sphere(16, 24);
        CgMaterialInstance solid = CgMaterial.load(DIR + "dof_lab_solid.shader").instance();
        CgMaterialInstance light = CgMaterial.load(DIR + "dof_lab_light.shader").instance();
        float[][] colours = {{0.9f, 0.3f, 0.25f}, {0.95f, 0.75f, 0.3f}, {0.4f, 0.85f, 0.4f}, {0.3f, 0.7f, 0.95f},
                {0.65f, 0.45f, 0.95f}, {0.95f, 0.5f, 0.8f}, {0.85f, 0.85f, 0.85f}};
        for (int i = 0; i < PILLARS.length; i++) {
            double x = i % 2 == 0 ? -1.4 : 1.4, z = PILLARS[i];
            float[] c = colours[i];
            spawned.add(scene.mesh(cube, solid).at(x, 1.2, z).scale(0.6f, 2.4f, 0.6f).customData(0, c[0], c[1], c[2], 1f).spawn());
            boxes.add(new double[]{x - 0.3, 0, z - 0.3, x + 0.3, 2.4, z + 0.3});
        }
        // A board across the far end, for the blur's edge against it
        spawned.add(scene.mesh(cube, solid).at(0, 2.5, -45).scale(16f, 5f, 0.2f).customData(0, 0.6f, 0.6f, 0.65f, 1f).spawn());
        boxes.add(new double[]{-8, 0, -45.1, 8, 5, -44.9});
        // Points of light: a far row the bokeh shows on, a near one blurring over the view
        for (int i = -8; i <= 8; i++) {
            float hue = (i + 8) / 16f;
            spawned.add(scene.mesh(ball, light).at(i * 0.9, 3.2 + 0.4 * Math.sin(i), -40).scale(0.09f)
                    .customData(0, 1f, 0.55f + 0.45f * hue, 0.3f + 0.7f * (1f - hue), 24f).spawn());
        }
        for (int i = -2; i <= 2; i++) {
            spawned.add(scene.mesh(ball, light).at(i * 0.5, 0.9 + 0.15 * i, 0.6).scale(0.03f)
                    .customData(0, 0.6f, 0.9f, 1f, 20f).spawn());
        }
        // The rings, one a pass
        CgMesh quad = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 1f, 1f);
        CgTranslucencyPass[] passes = {CgTranslucencyPass.BEFORE_DOF, CgTranslucencyPass.AFTER_DOF, CgTranslucencyPass.AFTER_MOTION_BLUR};
        float[][] ringColours = {{1f, 0.4f, 0.2f}, {0.3f, 1f, 0.5f}, {0.4f, 0.6f, 1f}};
        for (int k = 0; k < 3; k++) {
            CgMaterialState state = CgMaterialState.builder().translucencyPass(passes[k]).build();
            CgMaterialInstance ring = CgMaterial.load(DIR + "dof_lab_ring.shader", state).instance();
            float[] c = ringColours[k];
            spawned.add(scene.mesh(quad, ring).at((k - 1) * 4.5, 3, RINGS_Z).scale(3.5f).customData(0, c[0], c[1], c[2], 6f).spawn());
        }
        apply();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (pulled >= 0f) {
            pulled += frame.getDeltaTime();
            float t = Math.min(1f, pulled / PULL_SECONDS);
            t = t * t * (3f - 2f * t);
            focus = pullFrom * (float) Math.pow(pullTo / pullFrom, t);   // even in stops of distance, as a puller turns it
            if (pulled >= PULL_SECONDS) pulled = -1f;
            apply();
        }
        CgView.main().showFlags().depthOfField(on);
        HarnessWorld.fire(ctx, camera.getViewMatrix(), lensProjection(ctx));
    }

    /** The lens's projection: its horizontal FOV on the settings' sensor, the runner's aspect, near and far. */
    private Matrix4f lensProjection(HarnessContext ctx) {
        Matrix4f base = ctx.getProjection();
        float m00 = 2f * LENSES[lens] / CgPostSettings.DEFAULT_SENSOR_WIDTH;
        return projection.set(base).m00(m00).m11(m00 * base.m11() / base.m00());
    }

    /** The runner draws its floor through the lens too. */
    @Override
    public boolean worldView(HarnessContext ctx, Matrix4f outView, Matrix4f outProjection) {
        outView.set(camera.getViewMatrix());
        outProjection.set(lensProjection(ctx));
        return true;
    }

    private void apply() {
        CgPostSettings lens = CgPost.client().camera();
        lens.depthOfField(focus, STOPS[stop]).depthOfFieldBladeCount(blades).depthOfFieldMinFstop(roundOpen ? 1.2f : 4f);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed()) return true;
        boolean shift = CgInput.isKeyDown(CgKeyCodes.KEY_LSHIFT) || CgInput.isKeyDown(CgKeyCodes.KEY_RSHIFT);
        switch (event.key()) {
            case CgKeyCodes.KEY_UP -> focus = Math.min(200f, focus * 1.12f);
            case CgKeyCodes.KEY_DOWN -> focus = Math.max(0.3f, focus / 1.12f);
            case CgKeyCodes.KEY_LEFT -> stop = Math.max(0, stop - 1);
            case CgKeyCodes.KEY_RIGHT -> stop = Math.min(STOPS.length - 1, stop + 1);
            case CgKeyCodes.KEY_F -> {
                if (!event.repeat()) focus = autofocus();
            }
            case CgKeyCodes.KEY_K -> {
                if (event.repeat()) break;
                pullFrom = focus;
                pullTo = Math.abs(focus - NEAR_FOCUS) < Math.abs(focus - FAR_FOCUS) ? FAR_FOCUS : NEAR_FOCUS;
                pulled = 0f;
            }
            case CgKeyCodes.KEY_B -> {
                if (!event.repeat()) blades = shift ? Math.max(4, blades - 1) : Math.min(16, blades + 1);
            }
            case CgKeyCodes.KEY_N -> {
                if (!event.repeat()) roundOpen = !roundOpen;
            }
            case CgKeyCodes.KEY_G -> {
                if (!event.repeat()) on = !on;
            }
            case CgKeyCodes.KEY_Z -> {
                if (!event.repeat()) lens = Math.max(0, lens - 1);
            }
            case CgKeyCodes.KEY_X -> {
                if (!event.repeat()) lens = Math.min(LENSES.length - 1, lens + 1);
            }
            default -> {
                return true;
            }
        }
        apply();
        return true;
    }

    /** The eye distance to the nearest solid the centre of the screen looks at, else where it meets the floor. */
    private float autofocus() {
        Matrix4f view = camera.getViewMatrix();
        double ox = camera.getPosX(), oy = camera.getPosY(), oz = camera.getPosZ();
        double dx = -view.m02(), dy = -view.m12(), dz = -view.m22();
        double best = Double.MAX_VALUE;
        for (double[] b : boxes) {
            double near = 0, far = Double.MAX_VALUE;
            double[] o = {ox, oy, oz}, d = {dx, dy, dz};
            boolean hit = true;
            for (int a = 0; a < 3 && hit; a++) {
                if (Math.abs(d[a]) < 1e-9) {
                    hit = o[a] >= b[a] && o[a] <= b[a + 3];
                    continue;
                }
                double t1 = (b[a] - o[a]) / d[a], t2 = (b[a + 3] - o[a]) / d[a];
                near = Math.max(near, Math.min(t1, t2));
                far = Math.min(far, Math.max(t1, t2));
                hit = near <= far;
            }
            if (hit && near > 0) best = Math.min(best, near);
        }
        if (best == Double.MAX_VALUE && dy < -1e-6) best = -oy / dy;
        return best == Double.MAX_VALUE ? FAR_FOCUS : (float) Math.max(0.3, best);
    }

    @Override
    public String hudLine() {
        return String.format(Locale.ROOT, "Depth of field [G]: %s | Lens [Z/X]: %d mm | Focus [Up/Down, F, K pull]: %.2f m"
                        + " | f/%s [Left/Right] | Blades [B]: %d | Widest [N]: f/%s%nRings, left to right: BeforeDOF, AfterDOF,"
                        + " AfterMotionBlur",
                on ? "on" : "off", (int) LENSES[lens], focus, trim(STOPS[stop]), blades, roundOpen ? "1.2" : "4");
    }

    private static String trim(float f) {
        return f == (int) f ? Integer.toString((int) f) : Float.toString(f);
    }

    @Override
    public void dispose() {
        for (CgMeshComponent c : spawned) c.remove();
        spawned.clear();
        CgPost.client().camera().reset();
        CgView.main().showFlags().depthOfField(true);
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
