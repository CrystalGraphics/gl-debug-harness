package com.crystalgraphics.harness.scene.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.capture.HarnessCapture;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.upscale.CgAntiAliasing;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;

/**
 * TSR's gate (R14e): a still frame of bars and posts settled through temporal AA at 100%, the reference, then through TSR
 * at 50, 67 and 100% and at each anti-aliasing quality, and through temporal AA at 50% upscaled spatially, each into one
 * 640x360 target. TSR at 50% must keep more of the reference's edges than temporal AA at 50%, and every TSR picture stay
 * near the reference. A bar that jumps away must leave TSR no more ghost than temporal AA, over the frames after, and a
 * turned bar appearing with no history behind it must land smoother from Medium than at Low (spatial anti-aliasing); a
 * patch blinking every frame must hold at least twice as still from High as at Medium (the flicker heuristic); and
 * a burst of translucent quads, which write no velocity, must leave TSR no longer a trail than temporal AA. Bloom
 * is off: each step keeps a thin light's energy for bloom its own way, and the gate measures the upscale; TSR's GPU time
 * is {@code temporal-upscale-timing}'s. Scene id {@code temporal-upscale}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=temporal-upscale --rhi=vulkan"
 * }</pre>
 */
public final class CgTemporalUpscaleTestScene implements HarnessSceneLifecycle {

    private static final int W = 640, H = 360, FRAMES = 96;
    /** The last frames each run keeps: the picture is their mean, its wobble their spread. */
    private static final int LAST = 8;
    /** Where the jumping bar stands until the last frames, and where it jumps to (world x; y 1.2 is the screen's middle). */
    private static final float BAR_FROM = -2.2f, BAR_TO = -1.6f, BAR_Y = 1.2f, BAR_Z = -4f;
    /** The bar's turn: its edges appear as stairs, which the spatial anti-aliasing smooths the frame they are seen. */
    private static final float BAR_TURN = (float) Math.toRadians(30);

    /**
     * The burst: QUADS translucent quads in a row, crossing the posts at {@link #BURST_SPEED} a frame from {@link #BURST_X}
     * over the last BURST_FRAMES frames; past the last frame, x {@code BURST_X} to {@link #BURST_TRAIL} is behind all of them.
     */
    private static final int QUADS = 8, BURST_FRAMES = 20;
    private static final float BURST_X = -3.2f, BURST_SPEED = 0.12f, BURST_Z = -6f, BURST_TRAIL = -1.3f;

    /**
     * A patch whose shading flips every frame between two brightnesses, as specular aliasing does: what the flicker
     * heuristic (High and up) is for. Below it the rejection drops the history each frame and the patch flickers at the
     * input's full swing.
     */
    private static final float BLINK_X = -2.4f, BLINK_Y = 2.6f, BLINK_Z = -8f, BLINK_LOW = 0.5f, BLINK_HIGH = 1f;

    /** The turned bar sliding: from where, how far a frame. Its trail is what the reprojection field (High and up) keeps clean. */
    private static final float SLIDE_FROM = -2.6f, SLIDE_STEP = 0.02f;

    /** What a run draws besides the still bars: the turned bar (jumping, appearing, sliding), the burst, or the blinking patch. */
    private enum Bar { NONE, JUMPS, APPEARS, SLIDES, BURST, BURST_APART, BLINK }

    private CgMaterialInstance line, glass, spark;
    /** Every capture read, decoded once. */
    private final Map<String, BufferedImage> captures = new HashMap<>();
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();

    @Override
    public void init(HarnessContext ctx) {
        line = CgMaterial.load("assets/harness/shader/taa_lab_line.shader").instance();
        glass = CgMaterial.load("assets/harness/shader/forward_transparent_test.shader").instance();
        spark = CgMaterial.load("assets/harness/shader/upscale_spark.shader").instance();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgPostRenderer.install();
        CgCamera.perspectiveMatrix(projection, 40f, (float) W / H, 0.05f, 100f);
        view.identity().translate(0f, -1.2f, 0f);
        List<String> failures = new ArrayList<>();
        CgView main = CgView.main();
        CgTemporalAA.enable(main, true);
        float bloom = main.post().settings().bloomIntensity();
        main.post().settings().bloomIntensity(0f);
        CgQuality quality = CgGraphicsSettings.ANTI_ALIASING.get();
        try {
            CgGraphicsSettings.ANTI_ALIASING.set(CgQuality.ULTRA);
            run(ctx, CgAntiAliasing.TAA, 100f, "taa-full.png", Bar.NONE);
            run(ctx, CgAntiAliasing.TAA, 50f, "taa-half.png", Bar.NONE);
            run(ctx, CgAntiAliasing.TSR, 100f, "tsr-full.png", Bar.NONE);
            run(ctx, CgAntiAliasing.TSR, 67f, "tsr-67.png", Bar.NONE);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half.png", Bar.NONE);
            run(ctx, CgAntiAliasing.TAA, 100f, "taa-full-jump.png", Bar.JUMPS);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-jump.png", Bar.JUMPS);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-appear.png", Bar.APPEARS);
            run(ctx, CgAntiAliasing.TAA, 100f, "taa-full-burst.png", Bar.BURST);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-burst.png", Bar.BURST);
            run(ctx, CgAntiAliasing.TAA, 100f, "taa-full-apart.png", Bar.BURST_APART);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-apart.png", Bar.BURST_APART);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-blink.png", Bar.BLINK);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-slide.png", Bar.SLIDES);
            CgGraphicsSettings.ANTI_ALIASING.set(CgQuality.MEDIUM);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-medium.png", Bar.NONE);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-medium-blink.png", Bar.BLINK);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-medium-slide.png", Bar.SLIDES);
            CgGraphicsSettings.ANTI_ALIASING.set(CgQuality.LOW);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-low.png", Bar.NONE);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-low-jump.png", Bar.JUMPS);
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half-low-appear.png", Bar.APPEARS);

            BufferedImage reference = read(ctx, "taa-full.png"), taaHalf = read(ctx, "taa-half.png");
            double referenceEdges = edges(reference), taaHalfEdges = edges(taaHalf);
            System.out.printf(Locale.ROOT, "[temporal-upscale] TAA 100%%: edge energy %.0f, wobble %.2f; TAA 50%% spatial: %.0f,"
                    + " %.2f from it, wobble %.2f%n", referenceEdges, wobble(ctx, "taa-full.png", reference), taaHalfEdges,
                    change(reference, taaHalf), wobble(ctx, "taa-half.png", taaHalf));
            double tsrHalfEdges = 0;
            for (String file : new String[]{"tsr-full.png", "tsr-67.png", "tsr-half.png", "tsr-half-medium.png", "tsr-half-low.png"}) {
                BufferedImage img = read(ctx, file);
                double off = change(reference, img), e = edges(img);
                if (file.equals("tsr-half.png")) tsrHalfEdges = e;
                System.out.printf(Locale.ROOT, "[temporal-upscale] %s: %.2f from TAA 100%%, edge energy %.0f, wobble %.2f%n", file,
                        off, e, wobble(ctx, file, img));
                if (off > 15) failures.add(String.format(Locale.ROOT, "%s is far from the reference: %.2f", file, off));
                if (e < 1) failures.add(file + " drew nothing: no edges");
            }
            if (tsrHalfEdges <= taaHalfEdges) {
                failures.add(String.format(Locale.ROOT, "TSR at 50%% keeps no more edges than TAA at 50%% upscaled: %.0f against %.0f",
                        tsrHalfEdges, taaHalfEdges));
            }

            // Where the bar stood, against the same method's still picture, over the frames after it jumped
            int[] box = barBox(BAR_FROM);
            double taaGhost = ghost(ctx, "taa-full-jump.png", reference, box);
            double tsrGhost = ghost(ctx, "tsr-half-jump.png", read(ctx, "tsr-half.png"), box);
            double lowGhost = ghost(ctx, "tsr-half-low-jump.png", read(ctx, "tsr-half-low.png"), box);
            System.out.printf(Locale.ROOT, "[temporal-upscale] behind the jumped bar, over the %d frames after: TAA 100%% %.2f,"
                    + " TSR 50%% %.2f, TSR 50%% Low %.2f%n", LAST, taaGhost, tsrGhost, lowGhost);
            if (tsrGhost > taaGhost + 1.0) {
                failures.add(String.format(Locale.ROOT, "TSR's jumped bar leaves more ghost than TAA's: %.2f against %.2f", tsrGhost, taaGhost));
            }
            // The bar the frame it appears, no history behind it: smoothed at once from Medium, at Low only by the kernel
            int[] appeared = barBox(BAR_TO);
            int spatial = between(capture(ctx, frameFile("tsr-half-appear.png", 0)), appeared);
            int low = between(capture(ctx, frameFile("tsr-half-low-appear.png", 0)), appeared);
            System.out.printf(Locale.ROOT, "[temporal-upscale] the appeared bar's in-between texels the frame it appears: TSR 50%% %d,"
                    + " Low %d%n", spatial, low);
            if (spatial <= low) {
                failures.add("the spatial anti-aliasing smoothed nothing: the appeared bar has " + spatial + " in-between texels, Low " + low);
            }

            // Where the burst passed, against the same method's still picture, on the last frame
            int[] trail = box(BURST_X, BURST_TRAIL, BAR_Y - 0.2f, BAR_Y + 0.2f, BURST_Z);
            BufferedImage tsrStill = read(ctx, "tsr-half.png");
            for (String drawn : new String[]{"burst", "apart"}) {
                double taaTrail = trail(ctx, "taa-full-" + drawn + ".png", reference, trail);
                double tsrTrail = trail(ctx, "tsr-half-" + drawn + ".png", tsrStill, trail);
                double tsrHere = Math.abs(mean(capture(ctx, frameFile("tsr-half-" + drawn + ".png", LAST - 1)),
                        burstBox()) - mean(tsrStill, burstBox()));
                System.out.printf(Locale.ROOT, "[temporal-upscale] the translucent burst (%s): behind it TAA 100%% %.2f, TSR 50%% %.2f;"
                        + " where it is, TSR %.2f over the still picture%n", drawn.equals("apart") ? "separate translucency" : "in the scene",
                        taaTrail, tsrTrail, tsrHere);
                if (tsrTrail > taaTrail + 1.0) {
                    failures.add(String.format(Locale.ROOT, "TSR smears the %s burst more than TAA: %.2f against %.2f", drawn, tsrTrail, taaTrail));
                }
                if (tsrHere < 1.0) failures.add("TSR lost the " + drawn + " burst: " + String.format(Locale.ROOT, "%.2f", tsrHere));
            }

            // Behind the sliding bar on the last frame, against the same quality's still picture
            float slidAt = SLIDE_FROM + (FRAMES - 1) * SLIDE_STEP;
            int[] behind = box(slidAt - 0.6f, slidAt - 0.25f, BAR_Y - 0.25f, BAR_Y + 0.25f, BAR_Z);
            double ultraTrail = trail(ctx, "tsr-half-slide.png", read(ctx, "tsr-half.png"), behind);
            double mediumTrail = trail(ctx, "tsr-half-medium-slide.png", read(ctx, "tsr-half-medium.png"), behind);
            System.out.printf(Locale.ROOT, "[temporal-upscale] behind the sliding bar: Ultra (reprojection field) %.2f, Medium %.2f%n",
                    ultraTrail, mediumTrail);
            if (ultraTrail > mediumTrail + 1.0) {
                failures.add(String.format(Locale.ROOT, "the sliding bar trails more from High: %.2f against Medium's %.2f", ultraTrail, mediumTrail));
            }

            // The blinking patch from frame to frame: from High the flicker heuristic keeps its history through the blink
            int[] blink = box(BLINK_X - 0.3f, BLINK_X + 0.3f, BLINK_Y - 0.3f, BLINK_Y + 0.3f, BLINK_Z);
            double ultraBlink = wobble(ctx, "tsr-half-blink.png", read(ctx, "tsr-half-blink.png"), blink);
            double mediumBlink = wobble(ctx, "tsr-half-medium-blink.png", read(ctx, "tsr-half-medium-blink.png"), blink);
            System.out.printf(Locale.ROOT, "[temporal-upscale] the patch blinking every frame, its wobble at 50%%: Ultra (flicker"
                    + " heuristic) %.2f, Medium %.2f%n", ultraBlink, mediumBlink);
            if (ultraBlink > mediumBlink * 0.5) {
                failures.add(String.format(Locale.ROOT, "the flicker heuristic did not hold the blinking patch: %.2f against Medium's %.2f",
                        ultraBlink, mediumBlink));
            }

        } catch (IOException e) {
            failures.add("the captures could not be read: " + e);
        } finally {
            CgTemporalAA.enable(main, false);
            main.post().settings().bloomIntensity(bloom);
            main.screenPercentage(100f).antiAliasing(CgAntiAliasing.TSR);
            CgGraphicsSettings.ANTI_ALIASING.set(quality);
        }
        report(failures, DeviceErrors.checkAndLog("temporal-upscale"));
    }

    /** The mean of a run's last frames, as one image. */
    private BufferedImage read(HarnessContext ctx, String file) throws IOException {
        double[] sum = new double[W * H * 3];
        for (int k = 0; k < LAST; k++) {
            BufferedImage img = capture(ctx, frameFile(file, k));
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int c = img.getRGB(x, y), i = (y * W + x) * 3;
                    sum[i] += c >> 16 & 0xff;
                    sum[i + 1] += c >> 8 & 0xff;
                    sum[i + 2] += c & 0xff;
                }
            }
        }
        BufferedImage mean = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 3;
                mean.setRGB(x, y, (int) Math.round(sum[i] / LAST) << 16 | (int) Math.round(sum[i + 1] / LAST) << 8
                        | (int) Math.round(sum[i + 2] / LAST));
            }
        }
        ImageIO.write(mean, "png", new File(ctx.getOutputDir(), file));
        return mean;
    }

    /** How far a run's last frames move about their mean: the mean absolute luma change, 0 to 765. */
    private double wobble(HarnessContext ctx, String file, BufferedImage mean) throws IOException {
        double sum = 0;
        for (int k = 0; k < LAST; k++) sum += change(mean, capture(ctx, frameFile(file, k)));
        return sum / LAST;
    }

    /** {@link #wobble(HarnessContext, String, BufferedImage)} within a box: its mean absolute luma change. */
    private double wobble(HarnessContext ctx, String file, BufferedImage mean, int[] box) throws IOException {
        double sum = 0;
        for (int k = 0; k < LAST; k++) {
            BufferedImage img = capture(ctx, frameFile(file, k));
            double s = 0;
            int n = 0;
            for (int y = box[1]; y < box[3]; y++) {
                for (int x = box[0]; x < box[2]; x++) {
                    s += Math.abs(luma(img.getRGB(x, y)) - luma(mean.getRGB(x, y)));
                    n++;
                }
            }
            sum += s / Math.max(1, n);
        }
        return sum / LAST;
    }

    /** How much brighter than {@code still} the box is over a jump run's last frames, on average, 0 to 765. */
    private double ghost(HarnessContext ctx, String file, BufferedImage still, int[] box) throws IOException {
        double sum = 0;
        for (int k = 0; k < LAST; k++) {
            sum += Math.max(0, mean(capture(ctx, frameFile(file, k)), box) - mean(still, box));
        }
        return sum / LAST;
    }

    private BufferedImage capture(HarnessContext ctx, String file) throws IOException {
        BufferedImage img = captures.get(file);
        if (img == null) {
            img = ImageIO.read(new File(ctx.getOutputDir(), file));
            captures.put(file, img);
        }
        return img;
    }

    private static String frameFile(String file, int k) {
        return file.replace(".png", "-" + k + ".png");
    }

    /**
     * FRAMES still frames through {@code method} at {@code percent} into one target, the last LAST captured. A turned bar
     * that {@link Bar#JUMPS} stands at {@link #BAR_FROM} until those frames and jumps to {@link #BAR_TO} as they begin, its
     * velocity written (a component, as what moves is); one that {@link Bar#APPEARS} is spawned at {@link #BAR_TO} then.
     */
    private void run(HarnessContext ctx, CgAntiAliasing method, float percent, String file, Bar what) {
        CgView.main().screenPercentage(percent).antiAliasing(method);
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgScene scene = CgScene.client();
        CgMesh cube = CgMeshShapes.cube(), quad = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 1f, 1f);
        CgMeshComponent bar = null;
        for (int f = 0; f < FRAMES; f++) {
            target.bind();
            target.clear(0.02f, 0.02f, 0.03f, 1f);
            draw(scene, cube);
            if (bar == null && (what == Bar.JUMPS || what == Bar.SLIDES || what == Bar.APPEARS && f == FRAMES - LAST)) {
                bar = scene.mesh(cube, line).at(what == Bar.JUMPS ? BAR_FROM : what == Bar.SLIDES ? SLIDE_FROM : BAR_TO, BAR_Y, BAR_Z)
                        .transform(new Matrix4f().rotateZ(BAR_TURN).scale(0.2f, 0.4f, 0.02f)).customData(0, 1f, 0.3f, 0.3f, 1f).spawn();
            }
            if (what == Bar.JUMPS) bar.at(f < FRAMES - LAST ? BAR_FROM : BAR_TO, BAR_Y, BAR_Z);
            if (what == Bar.SLIDES) bar.at(SLIDE_FROM + f * SLIDE_STEP, BAR_Y, BAR_Z);
            if (what == Bar.BLINK) {
                float b = f % 2 == 0 ? BLINK_LOW : BLINK_HIGH;
                scene.once().mesh(cube, line).at(BLINK_X, BLINK_Y, BLINK_Z).scale(0.6f, 0.6f, 0.02f).customData(0, b, b, b, 1f).add();
            }
            if ((what == Bar.BURST || what == Bar.BURST_APART) && f >= FRAMES - BURST_FRAMES) {
                float front = BURST_X + (f - (FRAMES - BURST_FRAMES)) * BURST_SPEED;
                for (int q = 0; q < QUADS; q++) {
                    scene.once().mesh(quad, what == Bar.BURST ? glass : spark).at(front + q * 0.07f, BAR_Y - 0.1f + (q % 3) * 0.1f, BURST_Z).scale(0.08f)
                            .customData(0, 1f, 0.6f, 0.2f, 1.6f).add();
                }
            }
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            // Kept in memory: a PNG a frame would outlast the harness's stall watchdog; each run's mean is written
            if (f >= FRAMES - LAST) captures.put(frameFile(file, f - (FRAMES - LAST)), HarnessCapture.image(target.external()));
            target.unbind();
        }
        if (bar != null) bar.remove();
        target.delete();
    }

    /** The still bars and posts, for one frame. */
    private void draw(CgScene scene, CgMesh cube) {
        scene.once().mesh(cube, line).at(0.6, 0.4, -5).transform(new Matrix4f().rotateZ((float) Math.toRadians(4)).scale(3f, 0.15f, 0.02f))
                .customData(0, 1f, 0.9f, 0.6f, 1f).add();
        for (int i = 0; i < 12; i++) {
            scene.once().mesh(cube, line).at(-3 + i * 0.5, 1.2, -9).scale(0.04f, 1.6f, 0.04f).customData(0, 0.6f, 0.85f, 1f, 2f).add();
        }
    }

    /** How much brighter than {@code still} the box is on a run's last frame, 0 to 765. */
    private double trail(HarnessContext ctx, String file, BufferedImage still, int[] box) throws IOException {
        return Math.max(0, mean(capture(ctx, frameFile(file, LAST - 1)), box) - mean(still, box));
    }

    /** Where the burst is on the last frame. */
    private int[] burstBox() {
        float front = BURST_X + (BURST_FRAMES - 1) * BURST_SPEED;
        return box(front - 0.04f, front + (QUADS - 1) * 0.07f + 0.04f, BAR_Y - 0.15f, BAR_Y + 0.15f, BURST_Z);
    }

    /** The turned bar's bounds at {@code x}, on screen. */
    private int[] barBox(float x) {
        return box(x - 0.2f, x + 0.2f, BAR_Y - 0.25f, BAR_Y + 0.25f, BAR_Z);
    }

    /** A world rectangle at depth {@code z} on screen: centred on the middle row, so the row order does not matter. */
    private int[] box(float x0, float x1, float y0, float y1, float z) {
        Matrix4f clip = new Matrix4f(projection).mul(view);
        Vector4f a = clip.transform(new Vector4f(x0, y0, z, 1f));
        Vector4f b = clip.transform(new Vector4f(x1, y1, z, 1f));
        int px0 = (int) Math.floor((a.x / a.w * 0.5 + 0.5) * W), px1 = (int) Math.ceil((b.x / b.w * 0.5 + 0.5) * W);
        int ya = (int) Math.round((0.5 - a.y / a.w * 0.5) * H), yb = (int) Math.round((0.5 - b.y / b.w * 0.5) * H);
        return new int[]{Math.max(0, px0), Math.max(0, Math.min(ya, yb)), Math.min(W - 1, px1), Math.min(H - 1, Math.max(ya, yb))};
    }

    /** Texels in the box between a fifth and four fifths of its brightest: an edge's in-between coverage. */
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
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                sum += luma(img.getRGB(x, y));
                n++;
            }
        }
        return sum / Math.max(1, n);
    }

    /** The mean absolute luma change between two captures, 0 to 765. */
    private static double change(BufferedImage a, BufferedImage b) {
        double sum = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) sum += Math.abs(luma(a.getRGB(x, y)) - luma(b.getRGB(x, y)));
        return sum / (W * H);
    }

    /** The sum of absolute horizontal and vertical luma steps over the picture: how much edge it keeps. */
    private static double edges(BufferedImage img) {
        double sum = 0;
        for (int y = 0; y + 1 < H; y++) {
            for (int x = 0; x + 1 < W; x++) {
                int l = luma(img.getRGB(x, y));
                sum += Math.abs(l - luma(img.getRGB(x + 1, y))) + Math.abs(l - luma(img.getRGB(x, y + 1)));
            }
        }
        return sum / 1000.0;
    }

    private static int luma(int argb) {
        return (argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff);
    }

    private static void report(List<String> failures, boolean deviceErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (deviceErrors) {
            System.out.println("[temporal-upscale] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[temporal-upscale] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[temporal-upscale] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[temporal-upscale] PASS on " + on + ": TSR rebuilds 50% and 67% near the 100% reference,"
                    + " keeping more edges than temporal AA upscaled spatially; a jumped bar leaves no more ghost, an appeared one lands smoothed, a burst no longer a trail");
        }
    }

    @Override
    public void dispose() {
    }
}
