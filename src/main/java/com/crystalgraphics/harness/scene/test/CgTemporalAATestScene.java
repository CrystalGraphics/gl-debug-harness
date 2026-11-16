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
import com.crystalgraphics.render.post.upscale.CgAntiAliasing;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;

/**
 * Temporal anti-aliasing's gate (R14a): the same scene drawn 40 frames running into one target, with the resolve off and
 * then on with the jitter, the last frame of each captured. A pane dithered to half coverage (DitherOpacityMask) settles
 * from texels on or off to an even half; a bar turned a few degrees gains edge texels between it and the ground, where
 * without the jitter there are none; a bar that moved away leaves no ghost behind it; and what stands still holds still
 * from one jittered frame to the next, the jitter left out of the history's fetch. Scene id {@code temporal-aa}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=temporal-aa --rhi=vulkan"
 * }</pre>
 */
public final class CgTemporalAATestScene implements HarnessSceneLifecycle {

    private static final int W = 640, H = 360, FRAMES = 40;
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
        CgView.main().antiAliasing(CgAntiAliasing.TAA);
        run(ctx, scene, false, "off.png");
        run(ctx, scene, true, "on.png");
        pan(ctx, scene, true, "pan-on.png");
        for (boolean withPane : new boolean[]{false, true}) {
            dolly(ctx, scene, withPane, false, "dolly-" + withPane + "-still.png");
            dolly(ctx, scene, withPane, true, "dolly-" + withPane + "-moved.png");
        }
        CgView.main().showFlags().temporalAA(false);
        CgView.main().antiAliasing(CgAntiAliasing.TSR);
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
            // The turned bar from one jittered frame to the next: resolved, it holds (the pane's dither is new each frame
            // by design, and this frame's share of it moves its texels)
            double shake = change(ImageIO.read(new File(ctx.getOutputDir(), "before-on.png")), on, barBox);
            System.out.printf(Locale.ROOT, "[temporal-aa] the still things' change from frame to frame, jittered: %.2f%n", shake);
            if (shake > 2.0) failures.add(String.format(Locale.ROOT, "a still picture shakes with the jitter: %.2f a texel", shake));
            System.out.printf(Locale.ROOT, "[temporal-aa] dithered pane's spread over its mean: off %.3f, on %.3f; turned bar's"
                    + " in-between texels: off %d, on %d; behind the moved bar, over the ground: %.1f%n", spreadOff, spreadOn, edgeOff, edgeOn, ghost);
            if (spreadOn > 0.25 || spreadOn > spreadOff * 0.4) {
                failures.add(String.format(Locale.ROOT, "the dithered pane did not settle: spread %.3f on, %.3f off", spreadOn, spreadOff));
            }
            if (edgeOn < Math.max(40, edgeOff * 3)) {
                failures.add("the turned bar's edges gained too few in-between texels: " + edgeOn + " on, " + edgeOff + " off");
            }
            // The far thin bars from frame to frame over the last SHIMMER: the open one, the one behind the pane
            double[] shimmer = new double[2];
            for (int b = 0; b < 2; b++) {
                double x0 = b == 0 ? -1.6 : 0.2, x1 = b == 0 ? -0.2 : 1.6;
                int[] up = box((float) x0, (float) x1, 2.38f, 2.62f, -12f, 0), down = box((float) x0, (float) x1, -2.62f, -2.38f, -12f, 0);
                for (int k = 0; k + 1 < SHIMMER; k++) {
                    BufferedImage a = ImageIO.read(new File(ctx.getOutputDir(), "f" + (k + 1) + "-on.png"));
                    BufferedImage c = ImageIO.read(new File(ctx.getOutputDir(), "f" + k + "-on.png"));
                    shimmer[b] += (change(a, c, up) + change(a, c, down)) / (SHIMMER - 1);
                }
            }
            System.out.printf(Locale.ROOT, "[temporal-aa] thin far bars' change from frame to frame, jittered: open, writing its velocity, %.2f; behind the pane %.2f%n",
                    shimmer[0], shimmer[1]);
            // The far bars' glow (bloom over HDR texels thinner than a texel): the resolve keeps their light, on against off
            double[] glow = new double[2];
            for (int b = 0; b < 2; b++) {
                double x0 = b == 0 ? -2.2 : 0.2, x1 = b == 0 ? -0.2 : 2.2;
                for (float sign : new float[]{1f, -1f}) {
                    int[] around = box((float) x0, (float) x1, sign > 0 ? 1.7f : -3.3f, sign > 0 ? 3.3f : -1.7f, -12f, 0);
                    // Over the background, a corner far from everything
                    double ground = mean(off, new int[]{0, 0, 24, 24});
                    double offMean = mean(off, around) - ground, onMean = mean(on, around) - ground;
                    if (offMean > 1) glow[b] = Math.max(glow[b], onMean / offMean);
                }
            }
            System.out.printf(Locale.ROOT, "[temporal-aa] far bars' light kept, on over off: open %.2f, behind the pane %.2f%n", glow[0], glow[1]);
            // A written velocity keeps the jitter it carries (a half-float target rounded it away: 15.5)
            if (shimmer[0] > 6) failures.add(String.format(Locale.ROOT, "a still bar writing its velocity shakes: %.2f", shimmer[0]));
            // The camera moving: the bar's band after the move against the resolve standing still (against off, it would
            // count the resolve's own softening)
            BufferedImage panOn = ImageIO.read(new File(ctx.getOutputDir(), "pan-on.png"));
            double trail = change(on, panOn, barBox);
            System.out.printf(Locale.ROOT, "[temporal-aa] the camera panning: the bar's band against standing still %.2f%n", trail);
            for (boolean withPane : new boolean[]{false, true}) {
                BufferedImage still = ImageIO.read(new File(ctx.getOutputDir(), "dolly-" + withPane + "-still.png"));
                BufferedImage moved = ImageIO.read(new File(ctx.getOutputDir(), "dolly-" + withPane + "-moved.png"));
                double band = blockChange(still, moved, barBox), worst = worstBlock(still, moved);
                System.out.printf(Locale.ROOT, "[temporal-aa] dolly, pane %s: %d frames after stopping, against still by 8x8 blocks:"
                        + " the bar's band %.2f, the worst block %.1f%n", withPane, HOLD, band, worst);
                if (band > 2.5 || worst > 50) {   // the resolve before the parted-motion cut: 4.39, 99.7 with the pane
                    failures.add(String.format(Locale.ROOT, "the camera's dolly left a smear (pane %s): %.2f in the bar's band, %.1f at worst",
                            withPane, band, worst));
                }
            }
            if (trail > 6) failures.add(String.format(Locale.ROOT, "the camera's move leaves a trail: %.2f in the bar's band", trail));
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
        // The open far bar is posed every frame where it stands, as a paused spinner is: it writes its velocity
        Matrix4f thinPose = new Matrix4f().rotateZ((float) Math.toRadians(3)).scale(1.2f, 0.03f, 0.03f);
        CgMeshComponent thin = scene.mesh(cube, line).at(-0.9, 2.5, -12).transform(thinPose).customData(0, 1f, 0.35f, 0.25f, 4f).spawn();
        for (int f = 0; f < FRAMES; f++) {
            target.bind();
            target.clear(0.02f, 0.02f, 0.02f, 1f);
            scene.once().mesh(cube, pane).at(-1.1, 0, -4).scale(1f, 1f, 0.02f).customData(0, 0.4f, 0.9f, 0.6f, 0.5f).add();
            scene.once().mesh(cube, line).at(1.2, 0, -4).transform(new Matrix4f().rotateZ((float) Math.toRadians(4)).scale(2f, 0.12f, 0.02f))
                    .customData(0, 1f, 0.9f, 0.6f, 1f).add();
            // Far bars about a texel thick, turned 3 degrees: one open (the component), one behind a pane dithered to half
            thin.transform(thinPose);
            scene.once().mesh(cube, line).at(0.9, 2.5, -12).transform(new Matrix4f().rotateZ((float) Math.toRadians(3)).scale(1.2f, 0.03f, 0.03f))
                    .customData(0, 1f, 0.35f, 0.25f, 4f).add();
            scene.once().mesh(cube, pane).at(0.45, 1.25, -6).scale(0.9f, 0.3f, 0.01f).customData(0, 0.4f, 0.9f, 0.6f, 0.5f).add();
            // Under the ghost box for the first half, then gone two blocks to the right, between the pane and the bar
            mover.at(f < FRAMES / 2 ? -2.2 : -0.2, 0, -4);
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (f >= FRAMES - SHIMMER) target.captureToFile(ctx.getOutputDir(), "f" + (FRAMES - 1 - f) + "-" + file);
            if (f == FRAMES - 2) target.captureToFile(ctx.getOutputDir(), "before-" + file);
            if (f == FRAMES - 1) target.captureToFile(ctx.getOutputDir(), file);
            target.unbind();
        }
        mover.remove();
        thin.remove();
        target.delete();
    }

    /**
     * FRAMES frames of the turned bar alone with the camera moving sideways 2 cm a frame, the resolve {@code on} or off,
     * the last captured: the history is fetched by the camera's motion over the depth, or it trails.
     */
    private void pan(HarnessContext ctx, CgScene scene, boolean on, String file) {
        CgView.main().showFlags().temporalAA(on);
        CgView.main().jitter(on);
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgMesh cube = CgMeshShapes.cube();
        Matrix4f moving = new Matrix4f();
        for (int f = 0; f < FRAMES; f++) {
            target.bind();
            target.clear(0.02f, 0.02f, 0.02f, 1f);
            scene.once().mesh(cube, line).at(1.2, 0, -4).transform(new Matrix4f().rotateZ((float) Math.toRadians(4)).scale(2f, 0.12f, 0.02f))
                    .customData(0, 1f, 0.9f, 0.6f, 1f).add();
            // Ending where the still runs stood, so the bar's band is where it was
            double x = (f - (FRAMES - 1)) * 0.02;
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(x, 0, 0, moving.identity(), projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (f == FRAMES - 1) target.captureToFile(ctx.getOutputDir(), file);
            target.unbind();
        }
        target.delete();
    }

    private static final int HOLD = 10, SHIMMER = 8;

    /** The resolve on: FRAMES frames dollying forward 2 cm a frame ({@code moving}) or standing, then HOLD frames held. */
    private void dolly(HarnessContext ctx, CgScene scene, boolean withPane, boolean moving, String file) {
        CgView.main().showFlags().temporalAA(true);
        CgView.main().jitter(true);
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgMesh cube = CgMeshShapes.cube();
        Matrix4f still = new Matrix4f();
        for (int f = 0; f < FRAMES + HOLD; f++) {
            target.bind();
            target.clear(0.02f, 0.02f, 0.02f, 1f);
            scene.once().mesh(cube, line).at(1.2, 0, -4).transform(new Matrix4f().rotateZ((float) Math.toRadians(4)).scale(2f, 0.12f, 0.02f))
                    .customData(0, 1f, 0.9f, 0.6f, 1f).add();
            if (withPane) {
                scene.once().mesh(cube, pane).at(0.9, 0, -2).scale(0.6f, 0.6f, 0.01f).customData(0, 0.4f, 0.9f, 0.6f, 0.5f).add();
            }
            double z = moving ? Math.max(0, FRAMES - 1 - f) * 0.02 : 0;
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, z, still.identity(), projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (f == FRAMES + HOLD - 1) target.captureToFile(ctx.getOutputDir(), file);
            target.unbind();
        }
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

    /** The mean absolute change of 8x8-block mean luma over the box: what smears, without the dither's grain. */
    private static double blockChange(BufferedImage a, BufferedImage b, int[] box) {
        double sum = 0;
        int n = 0;
        for (int y = box[1]; y + 8 <= box[3]; y += 8) {
            for (int x = box[0]; x + 8 <= box[2]; x += 8, n++) sum += Math.abs(block(a, x, y) - block(b, x, y));
        }
        return sum / Math.max(1, n);
    }

    /** The largest 8x8-block mean luma change over the frame. */
    private static double worstBlock(BufferedImage a, BufferedImage b) {
        double worst = 0;
        for (int y = 0; y + 8 <= H; y += 8) for (int x = 0; x + 8 <= W; x += 8) worst = Math.max(worst, Math.abs(block(a, x, y) - block(b, x, y)));
        return worst;
    }

    private static double block(BufferedImage img, int x0, int y0) {
        double sum = 0;
        for (int y = y0; y < y0 + 8; y++) for (int x = x0; x < x0 + 8; x++) sum += luma(img.getRGB(x, y));
        return sum / 64;
    }

    /** The mean absolute luma change over the box between two captures. */
    private static double change(BufferedImage a, BufferedImage b, int[] box) {
        double sum = 0;
        int n = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++, n++) sum += Math.abs(luma(a.getRGB(x, y)) - luma(b.getRGB(x, y)));
        }
        return sum / Math.max(1, n);
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
