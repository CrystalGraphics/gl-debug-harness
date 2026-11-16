package com.crystalgraphics.harness.scene.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgTranslucencyPass;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
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
import com.crystalgraphics.render.post.CgPost;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.scene.CgScene;

/**
 * Depth of field's gate (R14b): one frame drawn with the lens's blur off, then on, focused on a near board through a
 * long lens wide open. Measured by edge energy (each pixel's squared difference from its right and lower neighbours,
 * which a blur spreads thin where a summed difference would hold) inside each thing's box on screen: the board in focus keeps its edges, the far board loses them, a far ring drawn
 * {@code BeforeDOF} blurs with it and one drawn {@code AfterDOF} keeps its own. Scene id {@code depth-of-field}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=depth-of-field --rhi=vulkan"
 * }</pre>
 */
public final class CgDepthOfFieldTestScene implements HarnessSceneLifecycle {

    private static final int W = 960, H = 540;
    /** A 75 mm lens on the settings' sensor, at f/1.4, focused on the near board. */
    private static final float FOV_Y = 10.5f, FOCUS = 1.5f, FSTOP = 1.4f;
    private static final float NEAR_Z = -1.5f, FAR_Z = -24f, RINGS_Z = -22f;

    private CgMaterialInstance solid, before, after;
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();

    @Override
    public void init(HarnessContext ctx) {
        solid = CgMaterial.load("assets/harness/shader/dof_lab_solid.shader").instance();
        before = CgMaterial.load("assets/harness/shader/dof_lab_ring.shader").instance();
        after = CgMaterial.load("assets/harness/shader/dof_lab_ring.shader",
                CgMaterialState.builder().translucencyPass(CgTranslucencyPass.AFTER_DOF).build()).instance();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgPostRenderer.install();
        CgCamera.perspectiveMatrix(projection, FOV_Y, (float) W / H, 0.05f, 200f);
        CgScene scene = CgScene.client();
        List<String> failures = new ArrayList<>();
        CgPost.client().camera().reset();
        draw(ctx, scene, "off.png");
        CgPost.client().camera().depthOfField(FOCUS, FSTOP);
        draw(ctx, scene, "on.png");
        CgPost.client().camera().reset();

        // Side by side, none over another: the board in focus, the two rings, the far board
        int[] near = box(-0.24f, -0.16f, -0.06f, 0.06f, NEAR_Z);
        int[] far = box(1.2f, 3.4f, -1.5f, 1.5f, FAR_Z);
        int[] ringBefore = box(-1.9f, -0.9f, -0.5f, 0.5f, RINGS_Z);
        int[] ringAfter = box(-0.5f, 0.5f, -0.5f, 0.5f, RINGS_Z);
        double nearKept = kept(ctx, near), farKept = kept(ctx, far), beforeKept = kept(ctx, ringBefore), afterKept = kept(ctx, ringAfter);
        if (Double.isNaN(nearKept)) {
            failures.add("the captures could not be read");
        } else {
            if (nearKept < 0.85) failures.add(String.format(Locale.ROOT, "the board in focus kept %.2f of its edges, under 0.85", nearKept));
            if (farKept > 0.3) failures.add(String.format(Locale.ROOT, "the far board kept %.2f of its edges, over 0.3", farKept));
            if (beforeKept > 0.4) failures.add(String.format(Locale.ROOT, "the BeforeDOF ring kept %.2f of its edges, over 0.4", beforeKept));
            if (afterKept < 0.9) failures.add(String.format(Locale.ROOT, "the AfterDOF ring kept %.2f of its edges, under 0.9", afterKept));
        }
        System.out.printf(Locale.ROOT, "[depth-of-field] edges kept: in focus %.3f, far %.3f, BeforeDOF ring %.3f, AfterDOF ring %.3f%n",
                nearKept, farKept, beforeKept, afterKept);
        report(failures, DeviceErrors.checkAndLog("depth-of-field"));
    }

    /** The near board at the focus, the far board, and a ring a pass in front of it, into a target of its own, captured. */
    private void draw(HarnessContext ctx, CgScene scene, String file) {
        HarnessTarget target = HarnessTarget.create(W, H, true);
        target.bind();
        target.clear(0.02f, 0.02f, 0.03f, 1f);
        CgMesh cube = CgMeshShapes.cube(), quad = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 1f, 1f);
        scene.once().mesh(cube, solid).at(-0.2, 0, NEAR_Z).transform(new Matrix4f().scale(0.08f, 0.12f, 0.01f))
                .customData(0, 0.9f, 0.8f, 0.4f, 1f).add();
        scene.once().mesh(cube, solid).at(2.3, 0, FAR_Z).transform(new Matrix4f().scale(2.2f, 3f, 0.1f))
                .customData(0, 0.4f, 0.7f, 0.95f, 1f).add();
        scene.once().mesh(quad, before).at(-1.4, 0, RINGS_Z).transform(new Matrix4f().scale(1f)).customData(0, 1f, 0.5f, 0.2f, 0.8f).add();
        scene.once().mesh(quad, after).at(0.0, 0, RINGS_Z).transform(new Matrix4f().scale(1f)).customData(0, 0.3f, 1f, 0.5f, 0.8f).add();
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
        HarnessWorld.fireCallbacks();
        CgFrameRing.endFrame();
        target.captureToFile(ctx.getOutputDir(), file);
        target.unbind();
        target.delete();
    }

    /** The pixel box {@code [x0, y0, x1, y1]} over world rectangle x in [x0, x1], y in [y0, y1] at depth z. */
    private int[] box(float x0, float x1, float y0, float y1, float z) {
        Vector4f a = projection.transform(new Vector4f(x0, y0, z, 1f)), b = projection.transform(new Vector4f(x1, y1, z, 1f));
        int px0 = (int) Math.floor((a.x / a.w * 0.5 + 0.5) * W), px1 = (int) Math.ceil((b.x / b.w * 0.5 + 0.5) * W);
        // The rectangles are centred on y = 0, so the image's row order does not matter
        int py0 = (int) Math.floor((0.5 - b.y / b.w * 0.5) * H), py1 = (int) Math.ceil((0.5 - a.y / a.w * 0.5) * H);
        return new int[]{Math.max(0, px0 - 4), Math.max(0, py0 - 4), Math.min(W - 2, px1 + 4), Math.min(H - 2, py1 + 4)};
    }

    /** The share of {@code box}'s edge energy without the blur that is left with it; NaN where a capture is unread. */
    private static double kept(HarnessContext ctx, int[] box) {
        try {
            BufferedImage off = ImageIO.read(new File(ctx.getOutputDir(), "off.png"));
            BufferedImage on = ImageIO.read(new File(ctx.getOutputDir(), "on.png"));
            double sharp = edges(off, box);
            return sharp <= 0 ? 0 : edges(on, box) / sharp;
        } catch (IOException e) {
            return Double.NaN;
        }
    }

    private static double edges(BufferedImage img, int[] box) {
        double sum = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int c = luma(img.getRGB(x, y)), dx = c - luma(img.getRGB(x + 1, y)), dy = c - luma(img.getRGB(x, y + 1));
                sum += (double) dx * dx + (double) dy * dy;
            }
        }
        return sum;
    }

    private static int luma(int argb) {
        return (argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff);
    }

    private static void report(List<String> failures, boolean deviceErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (deviceErrors) {
            System.out.println("[depth-of-field] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[depth-of-field] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[depth-of-field] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[depth-of-field] PASS on " + on + ": the board in focus keeps its edges, the far board and a"
                    + " BeforeDOF ring blur, an AfterDOF ring stays sharp");
        }
    }

    @Override
    public void dispose() {
    }
}
