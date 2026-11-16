package com.crystalgraphics.harness.scene.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.debug.CgPostDebug;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgInstancedMeshComponent;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.render.view.CgView;

/**
 * The velocity layer's gate (RV): each kind of drawable moved a known amount in its last frames, the velocity pass's
 * target read back through the {@code velocity} debug view and its motion compared with the CPU's projection of the same
 * move. Rigid, attached to a moving parent, an instance of an instanced mesh, skinned with an inline material and with a
 * {@code vertex()} one, a still object, a frame record, and a blended pane, which writes no velocity but marks the
 * responsive target (Responsive AA). Time-animated vertices are {@code CgGlslWriterTest}'s: the
 * scene's clock stands still across the frames one harness frame draws. Scene id {@code velocity-conformance}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=velocity-conformance --rhi=vulkan"
 * }</pre>
 */
public final class CgVelocityConformanceScene implements HarnessSceneLifecycle {

    private static final int W = 640, H = 360, FRAMES = 40, MOVING = 3;
    private static final float FOV_Y = 40f, Z = -4f, SIZE = 0.4f, STEP = 0.03f, PARENT_Y = 5f;
    /** The debug view's scale: 0.5 + texels a frame / 32 in red and green. */
    private static final double RANGE = 32.0;
    private static final float[] COLUMNS = {-1.8f, -0.6f, 0.6f, 1.8f}, ROWS = {0.6f, -0.6f};

    private CgMaterialInstance flat, slide, glass;
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();

    @Override
    public void init(HarnessContext ctx) {
        flat = CgMaterial.load("assets/harness/shader/velocity_flat.shader").instance();
        slide = CgMaterial.load("assets/harness/shader/velocity_slide.shader").instance();
        glass = CgMaterial.load("assets/harness/shader/forward_transparent_test.shader").instance();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgPostRenderer.install();
        CgCamera.perspectiveMatrix(projection, FOV_Y, (float) W / H, 0.05f, 100f);
        CgView.main().showFlags().temporalAA(false);
        CgView.main().jitter(false);
        CgPostDebug.VIEW.set("velocity");
        CgScene scene = CgScene.client();
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgMesh cube = CgMeshShapes.cube();
        CgSkeletalMesh plank = plank();
        CgAnimSequence push = push();

        CgMeshComponent rigid = scene.mesh(cube, flat).at(COLUMNS[0], ROWS[0], Z).scale(SIZE).customData(0, 1f, 0.3f, 0.3f, 1f).spawn();
        // The parent stands above the frame; the child rides it, placed relative to it
        CgMeshComponent parent = scene.mesh(cube, flat).at(COLUMNS[1], PARENT_Y, Z).customData(0, 0.3f, 0.3f, 1f, 1f).spawn();
        CgMeshComponent child = scene.mesh(cube, flat).at(0, ROWS[0] - PARENT_Y, 0).scale(SIZE).customData(0, 0.3f, 1f, 0.3f, 1f)
                .attach(parent, null).spawn();
        CgInstancedMeshComponent instanced = scene.instanced(cube, flat).customData(0, 1f, 1f, 0.3f, 1f).spawn();
        int instance = instanced.add(COLUMNS[2], ROWS[0], Z, new Quaternionf(), SIZE);
        CgSkeletalMeshComponent inline = scene.skeletal(plank).at(COLUMNS[3], ROWS[0], Z).materials(s -> flat)
                .customData(0, 1f, 0.3f, 1f, 1f).spawn();
        CgSkeletalMeshComponent ownVertex = scene.skeletal(plank).at(COLUMNS[0], ROWS[1], Z).materials(s -> slide)
                .customData(0, 0.3f, 1f, 1f, 1f).customData(1, 1.0e9f, 0f, 0f, 0f).spawn();
        CgMeshComponent still = scene.mesh(cube, flat).at(COLUMNS[2], ROWS[1], Z).scale(SIZE).customData(0, 0.8f, 0.8f, 0.8f, 1f).spawn();
        // Over nothing the engine drew: only the responsive target says the engine drew there
        CgMeshComponent pane = scene.mesh(CgMeshShapes.quad(CgVertexFormat.SPATIAL, SIZE, SIZE), glass).at(COLUMNS[1], ROWS[1], Z)
                .customData(0, 1f, 0f, 1f, 0.5f).spawn();
        inline.anim().play(push, false);
        ownVertex.anim().play(push, false);

        for (int f = 0; f < FRAMES; f++) {
            int moved = Math.max(0, f - (FRAMES - 1 - MOVING));
            float dx = moved * STEP;
            target.bind();
            target.clear(0f, 0f, 0f, 1f);
            rigid.at(COLUMNS[0] + dx, ROWS[0], Z);
            parent.at(COLUMNS[1] + dx, PARENT_Y, Z);
            instanced.move(instance, COLUMNS[2] + dx, ROWS[0], Z);
            float tick = f > FRAMES - 1 - MOVING ? 1f / 60f : 0f;
            inline.anim().tick(tick);
            ownVertex.anim().tick(tick);
            scene.once().mesh(cube, flat).at(COLUMNS[3] + dx, ROWS[1], Z).scale(SIZE).customData(0, 0.5f, 0.5f, 1f, 1f).add();
            scene.update();
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (f == FRAMES - 1) target.captureToFile(ctx.getOutputDir(), "velocity.png");
            target.unbind();
        }
        CgPostDebug.VIEW.set("");
        for (var c : List.of(rigid, child, parent, still, pane)) c.remove();
        instanced.remove();
        inline.remove();
        ownVertex.remove();
        target.delete();

        List<String> failures = new ArrayList<>();
        try {
            BufferedImage img = ImageIO.read(new File(ctx.getOutputDir(), "velocity.png"));
            double step = texels(STEP);
            check(img, "rigid moved", COLUMNS[0] + STEP * MOVING, ROWS[0], step, failures);
            check(img, "attached to a moving parent", COLUMNS[1] + STEP * MOVING, ROWS[0], step, failures);
            check(img, "an instance moved", COLUMNS[2] + STEP * MOVING, ROWS[0], step, failures);
            check(img, "skinned, inline material", COLUMNS[3] + STEP * MOVING, ROWS[0], step, failures);
            check(img, "skinned, vertex() material", COLUMNS[0] + STEP * MOVING, ROWS[1], step, failures);
            check(img, "still", COLUMNS[2], ROWS[1], 0, failures);
            // A frame record keeps no previous transform: drawn as still, so the engine's texels are known to be its own
            check(img, "a frame record (no motion known)", COLUMNS[3] + STEP * MOVING, ROWS[1], 0, failures);
            int[] paneBox = box(COLUMNS[1] - SIZE * 0.25f, COLUMNS[1] + SIZE * 0.25f, ROWS[1] - SIZE * 0.25f, ROWS[1] + SIZE * 0.25f);
            double marked = marked(img, paneBox);
            System.out.printf(Locale.ROOT, "[velocity-conformance] a blended pane: marked responsive %.0f%%, velocity written %.0f%%%n",
                    marked * 100, written(img, paneBox) * 100);
            if (marked < 0.9) failures.add(String.format(Locale.ROOT, "a blended pane marked %.0f%% of it responsive", marked * 100));
            int[] gap = box(-0.15f, 0.15f, -0.1f, 0.1f);
            if (written(img, gap) > 0.05) failures.add("the velocity pass wrote where nothing was drawn");
        } catch (IOException e) {
            failures.add("the capture could not be read: " + e);
        }
        report(failures, DeviceErrors.checkAndLog("velocity-conformance"));
    }

    /** Compares the motion written over the object centred at (x, y) with {@code expected} texels along x. */
    private void check(BufferedImage img, String name, float x, float y, double expected, List<String> failures) {
        int[] b = box(x - SIZE * 0.25f, x + SIZE * 0.25f, y - SIZE * 0.25f, y + SIZE * 0.25f);
        double share = written(img, b);
        double[] m = motion(img, b);
        System.out.printf(Locale.ROOT, "[velocity-conformance] %s: written %.0f%%, motion (%.2f, %.2f) texels, expected (%.2f, 0)%n",
                name, share * 100, m[0], m[1], expected);
        if (share < 0.9) {
            failures.add(String.format(Locale.ROOT, "%s: the velocity pass wrote %.0f%% of it", name, share * 100));
        } else if (Math.abs(m[0] - expected) > 0.25 + 0.05 * Math.abs(expected) || Math.abs(m[1]) > 0.25) {
            failures.add(String.format(Locale.ROOT, "%s: motion (%.2f, %.2f), expected (%.2f, 0)", name, m[0], m[1], expected));
        }
    }

    /** A move of {@code dx} metres along x at the gate's depth, in texels. */
    private double texels(float dx) {
        Vector4f a = projection.transform(new Vector4f(0f, 0f, Z, 1f)), b = projection.transform(new Vector4f(dx, 0f, Z, 1f));
        return (b.x / b.w - a.x / a.w) * 0.5 * W;
    }

    /** The share of the box the velocity pass wrote (the view's blue). */
    private static double written(BufferedImage img, int[] box) {
        int n = 0, w = 0;
        for (int y = box[1]; y < box[3]; y++) for (int x = box[0]; x < box[2]; x++, n++) if ((img.getRGB(x, y) & 0xff) > 128) w++;
        return (double) w / Math.max(1, n);
    }

    /** The share of the box the view shows responsive and unwritten: blue at half, no red or green. */
    private static double marked(BufferedImage img, int[] box) {
        int n = 0, m = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++, n++) {
                int argb = img.getRGB(x, y), b = argb & 0xff;
                if (b > 96 && b < 160 && (argb >> 8 & 0xff) < 16 && (argb >> 16 & 0xff) < 16) m++;
            }
        }
        return (double) m / Math.max(1, n);
    }

    /** The mean motion over the box's written texels, in texels a frame (up +y). */
    private static double[] motion(BufferedImage img, int[] box) {
        double sx = 0, sy = 0;
        int n = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int argb = img.getRGB(x, y);
                if ((argb & 0xff) <= 128) continue;
                sx += ((argb >> 16 & 0xff) / 255.0 - 0.5) * RANGE;
                sy += ((argb >> 8 & 0xff) / 255.0 - 0.5) * RANGE;
                n++;
            }
        }
        return new double[]{sx / Math.max(1, n), sy / Math.max(1, n)};
    }

    /** The pixel box over world rectangle x in [x0, x1], y in [y0, y1] at the gate's depth. */
    private int[] box(float x0, float x1, float y0, float y1) {
        Vector4f a = projection.transform(new Vector4f(x0, y0, Z, 1f)), b = projection.transform(new Vector4f(x1, y1, Z, 1f));
        int px0 = (int) Math.floor((a.x / a.w * 0.5 + 0.5) * W), px1 = (int) Math.ceil((b.x / b.w * 0.5 + 0.5) * W);
        int ya = (int) Math.round((0.5 - a.y / a.w * 0.5) * H), yb = (int) Math.round((0.5 - b.y / b.w * 0.5) * H);
        return new int[]{Math.max(0, px0), Math.max(0, Math.min(ya, yb)), Math.min(W - 1, px1), Math.min(H - 1, Math.max(ya, yb))};
    }

    /** A plank of two triangles on one bone: what a skinned case poses. */
    private static CgSkeletalMesh plank() {
        CgSkeleton skeleton = CgSkeleton.builder().bone("root", null, new Vector3f()).build();
        float h = SIZE * 0.5f;
        CgMesh quad = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            int a = m.vertex().position(-h, -h, 0).normal(0, 0, 1).uv(0, 0).bone(0, 1f).end();
            int b = m.vertex().position(h, -h, 0).normal(0, 0, 1).uv(1, 0).bone(0, 1f).end();
            int c = m.vertex().position(h, h, 0).normal(0, 0, 1).uv(1, 1).bone(0, 1f).end();
            int d = m.vertex().position(-h, h, 0).normal(0, 0, 1).uv(0, 1).bone(0, 1f).end();
            m.triangle(a, b, c);
            m.triangle(a, c, d);
        });
        return CgSkeletalMesh.builder(quad, skeleton).build();
    }

    /** The root bone pushed STEP along x a key, 60 keys a second: one key a frame at the gate's tick. */
    private static CgAnimSequence push() {
        int keys = MOVING + 2;
        float[] t = new float[keys * 3], r = new float[keys * 4], s = new float[keys * 3];
        for (int k = 0; k < keys; k++) {
            t[k * 3] = k * STEP;
            r[k * 4 + 3] = 1f;
            s[k * 3] = s[k * 3 + 1] = s[k * 3 + 2] = 1f;
        }
        return CgAnimSequence.builder("velocity:push", 60, keys).track("root", t, r, s).build();
    }

    private static void report(List<String> failures, boolean deviceErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (deviceErrors) {
            System.out.println("[velocity-conformance] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[velocity-conformance] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[velocity-conformance] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[velocity-conformance] PASS on " + on + ": rigid, attached, instanced and skinned both ways"
                    + " move as projected; still objects and frame records written still; a blended pane marked responsive");
        }
    }

    @Override
    public void dispose() {
    }
}
