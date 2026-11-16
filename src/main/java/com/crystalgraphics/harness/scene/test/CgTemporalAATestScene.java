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
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
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
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;

/**
 * Temporal anti-aliasing's gate (R14a): the same scene drawn 24 frames running into one target, with the resolve off and
 * then on with the jitter, the last frame of each captured. A pane dithered to half coverage (DitherOpacityMask) settles
 * from texels on or off to an even half; a bar turned a few degrees gains edge texels between it and the ground, where
 * without the jitter there are none; a bar that moved away leaves no ghost behind it. Scene id {@code temporal-aa}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=temporal-aa --rhi=vulkan"
 * }</pre>
 */
public final class CgTemporalAATestScene implements HarnessSceneLifecycle {

    private static final int W = 640, H = 360, FRAMES = 24;
    private static final float FOV_Y = 40f;

    private CgMaterialInstance line, pane;
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();

    @Override
    public void init(HarnessContext ctx) {
        line = CgMaterial.load("assets/harness/shader/taa_lab_line.shader").instance();
        pane = CgMaterial.load("assets/harness/shader/taa_lab_pane.shader").instance();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgPostRenderer.install();
        CgCamera.perspectiveMatrix(projection, FOV_Y, (float) W / H, 0.05f, 100f);
        CgScene scene = CgScene.client();
        List<String> failures = new ArrayList<>();
        run(ctx, scene, false, "off.png");
        run(ctx, scene, true, "on.png");
        CgView.main().showFlags().temporalAA(false);
        CgView.main().jitter(false);

        // The pane, the turned bar's band and where the moving bar started, on screen: each centred on y = 0, so the
        // image's row order does not matter
        int[] paneBox = box(-1.6f, -0.6f, -0.5f, 0.5f, -4f, 3);
        int[] barBox = box(0.2f, 2.2f, -0.3f, 0.3f, -4f, 0);
        int[] ghostBox = box(-2.3f, -2.1f, -0.2f, 0.2f, -4f, 0);
        try {
            BufferedImage off = ImageIO.read(new File(ctx.getOutputDir(), "off.png"));
            BufferedImage on = ImageIO.read(new File(ctx.getOutputDir(), "on.png"));
            double spreadOff = spread(off, paneBox), spreadOn = spread(on, paneBox);
            int edgeOff = between(off, barBox), edgeOn = between(on, barBox);
            // Brighter than the same texels without the resolve, where the bar has moved away in both
            double ghost = mean(on, ghostBox) - mean(off, ghostBox);
            System.out.printf(Locale.ROOT, "[temporal-aa] dithered pane's spread over its mean: off %.3f, on %.3f; turned bar's"
                    + " in-between texels: off %d, on %d; behind the moved bar, over the ground: %.1f%n", spreadOff, spreadOn, edgeOff, edgeOn, ghost);
            if (spreadOn > 0.25 || spreadOn > spreadOff * 0.4) {
                failures.add(String.format(Locale.ROOT, "the dithered pane did not settle: spread %.3f on, %.3f off", spreadOn, spreadOff));
            }
            if (edgeOn < Math.max(40, edgeOff * 3)) {
                failures.add("the turned bar's edges gained too few in-between texels: " + edgeOn + " on, " + edgeOff + " off");
            }
            if (ghost > 3) failures.add(String.format(Locale.ROOT, "the moved bar left a ghost: %.1f behind it", ghost));
        } catch (IOException e) {
            failures.add("the captures could not be read: " + e);
        }
        report(failures, DeviceErrors.checkAndLog("temporal-aa"));
    }

    /** FRAMES frames into one target, the resolve and jitter {@code on} or off, the last one captured. */
    private void run(HarnessContext ctx, CgScene scene, boolean on, String file) {
        CgView.main().showFlags().temporalAA(on);
        CgView.main().jitter(on);
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgMesh cube = CgMeshShapes.cube();
        // The moving bar is a component, as what moves is: its velocity written where it moved
        CgMeshComponent mover = scene.mesh(cube, line).at(-2.2, 0, -4).scale(0.2f, 0.4f, 0.02f).customData(0, 1f, 0.3f, 0.3f, 1f).spawn();
        for (int f = 0; f < FRAMES; f++) {
            target.bind();
            target.clear(0.02f, 0.02f, 0.02f, 1f);
            scene.once().mesh(cube, pane).at(-1.1, 0, -4).scale(1f, 1f, 0.02f).customData(0, 0.4f, 0.9f, 0.6f, 0.5f).add();
            scene.once().mesh(cube, line).at(1.2, 0, -4).transform(new Matrix4f().rotateZ((float) Math.toRadians(4)).scale(2f, 0.12f, 0.02f))
                    .customData(0, 1f, 0.9f, 0.6f, 1f).add();
            // Under the ghost box for the first half, then gone two blocks to the right, between the pane and the bar
            mover.at(f < FRAMES / 2 ? -2.2 : -0.2, 0, -4);
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (f == FRAMES - 1) target.captureToFile(ctx.getOutputDir(), file);
            target.unbind();
        }
        mover.remove();
        target.delete();
    }

    /** The pixel box over world rectangle x in [x0, x1], y in [y0, y1] at depth z, shrunk by {@code inset} texels. */
    private int[] box(float x0, float x1, float y0, float y1, float z, int inset) {
        Vector4f a = projection.transform(new Vector4f(x0, y0, z, 1f)), b = projection.transform(new Vector4f(x1, y1, z, 1f));
        int px0 = (int) Math.floor((a.x / a.w * 0.5 + 0.5) * W), px1 = (int) Math.ceil((b.x / b.w * 0.5 + 0.5) * W);
        int ya = (int) Math.round((0.5 - a.y / a.w * 0.5) * H), yb = (int) Math.round((0.5 - b.y / b.w * 0.5) * H);
        int py0 = Math.min(ya, yb), py1 = Math.max(ya, yb);
        return new int[]{Math.max(0, px0 + inset), Math.max(0, py0 + inset), Math.min(W - 2, px1 - inset), Math.min(H - 2, py1 - inset)};
    }

    /** The box's luma spread (standard deviation) over its mean. */
    private static double spread(BufferedImage img, int[] box) {
        double sum = 0, square = 0;
        int n = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int l = luma(img.getRGB(x, y));
                sum += l;
                square += (double) l * l;
                n++;
            }
        }
        double mean = sum / Math.max(1, n);
        return mean <= 0 ? 0 : Math.sqrt(Math.max(0, square / n - mean * mean)) / mean;
    }

    /** Texels in the box between a fifth and four fifths of the box's brightest: an edge's partial coverage. */
    private static int between(BufferedImage img, int[] box) {
        int most = 0;
        for (int y = box[1]; y < box[3]; y++) for (int x = box[0]; x < box[2]; x++) most = Math.max(most, luma(img.getRGB(x, y)));
        int count = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int l = luma(img.getRGB(x, y));
                if (l > most / 5 && l < most * 4 / 5) count++;
            }
        }
        return count;
    }

    private static double mean(BufferedImage img, int[] box) {
        double sum = 0;
        int n = 0;
        for (int y = box[1]; y < box[3]; y++) for (int x = box[0]; x < box[2]; x++, n++) sum += luma(img.getRGB(x, y));
        return sum / Math.max(1, n);
    }

    private static int luma(int argb) {
        return (argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff);
    }

    private static void report(List<String> failures, boolean deviceErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (deviceErrors) {
            System.out.println("[temporal-aa] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[temporal-aa] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[temporal-aa] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[temporal-aa] PASS on " + on + ": a dithered pane settles, a turned bar's edges resolve, a"
                    + " moved bar leaves no ghost");
        }
    }

    @Override
    public void dispose() {
    }
}
