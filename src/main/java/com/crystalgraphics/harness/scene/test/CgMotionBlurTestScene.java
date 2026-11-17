package com.crystalgraphics.harness.scene.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
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
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.post.settings.CgPostSettings.Control;
import com.crystalgraphics.render.post.settings.CgPostSettings.Region;
import com.crystalgraphics.render.post.upscale.CgAntiAliasing;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;

/**
 * R14c's gate: motion blur after the temporal step. The engine's clock steps 1/60 s a frame, so at Unreal's defaults
 * (half a frame's motion, a 30 fps shutter) a blur spans one frame's motion. Checked from the last frame's pixels:
 * <ul>
 *   <li>a bar moving sideways blurs its edges by that span (10-90% of the edge over 0.8 of it) under TSR at 100% and
 *       60%, TAA, and no temporal step; with motion blur off its edges stay sharp; a still bar stays as it was;</li>
 *   <li>a camera pan blurs the wall's stripes by the pan's span, while an {@code AfterMotionBlur} card over the wall
 *       stays sharp; with the camera's share cancelled the stripes stay sharp;</li>
 *   <li>a spinning bar blurs at its tip more than at its hub;</li>
 *   <li>a graded bar's blur past its silhouette keeps its look (the look weights blurred with it).</li>
 * </ul>
 * Prints {@code [motion-blur] PASS} or {@code FAIL}; a Vulkan validation error fails it.
 */
public final class CgMotionBlurTestScene implements HarnessSceneLifecycle {

    private static final int W = 480, H = 270, FRAMES = 48, MOVING = 12;
    private static final long WARM_NANOS = 30_000_000_000L;
    private static final float FPS = 60f;
    private static final float BAR_Z = -4f, BAR_Y = -0.5f, BAR_X0 = -1.4f, STEP = 0.2f, BAR_WIDTH = 0.3f;
    /** Sizes are the unit cube's scale: it spans -0.5 to 0.5, so the spinner reaches half its length from its axis. */
    private static final float STILL_X = 1.9f, SPIN_X = -1.7f, SPIN_Y = 0.6f, SPIN_Z = -5f, SPIN_LENGTH = 0.8f, SPIN_STEP = 0.25f;
    private static final float WALL_Z = -8f, STRIPE_Y = 2.6f, PAN_STEP = 0.03f;
    private static final float CARD_X = 0.4f, CARD_Y = -1.55f, CARD_HALF = 0.28f;

    private final CgPostSettings look = new CgPostSettings().tonemap().grade(Region.GLOBAL, Control.SATURATION, 1f, 1f, 1f, 0f).bloom(0f);
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();
    private CgMaterial lineMaterial, cardMaterial;
    private CgMaterialInstance line;

    @Override
    public void init(HarnessContext ctx) {
        lineMaterial = CgMaterial.load("assets/harness/shader/taa_lab_line.shader");
        line = lineMaterial.instance();
        cardMaterial = CgMaterial.load("assets/harness/shader/motion_blur_card.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgPostRenderer.install();
        CgCamera.perspectiveMatrix(projection, 40f, (float) W / H, 0.05f, 100f);
        CgPipelinePrecache.Request warm = CgPipelinePrecache.request(p -> {
            p.add(lineMaterial, false);
            p.add(cardMaterial, false);
        });
        long until = System.nanoTime() + WARM_NANOS;
        while (!warm.ready() && System.nanoTime() < until) {
            LockSupport.parkNanos(1_000_000L);
            CgWarmBudget.nextFrame();
        }
        CgView main = CgView.main();
        float bloom = main.post().settings().bloomIntensity();
        main.post().settings().bloomIntensity(0f);
        List<String> failures = new ArrayList<>();
        try {
            float span = pixelsAt(STEP, BAR_Z);
            float panSpan = PAN_STEP * projection.m11() * H * 0.5f;
            Run off = run(CgAntiAliasing.TSR, 100f, true, false, false, false, true);
            Run tsr = run(CgAntiAliasing.TSR, 100f, true, true, false, false, true);
            Run tsr60 = run(CgAntiAliasing.TSR, 60f, true, true, false, false, true);
            Run taa = run(CgAntiAliasing.TAA, 100f, true, true, false, false, true);
            Run plain = run(CgAntiAliasing.TSR, 100f, false, true, false, false, true);
            Run graded = run(CgAntiAliasing.TSR, 100f, true, true, true, false, true);
            Run panOff = run(CgAntiAliasing.TSR, 100f, true, false, false, true, true);
            Run pan = run(CgAntiAliasing.TSR, 100f, true, true, false, true, true);
            Run panStill = run(CgAntiAliasing.TSR, 100f, true, true, false, true, false);
            for (Run r : List.of(off, tsr, tsr60, taa, plain, graded, panOff, pan, panStill)) save(ctx, r);

            float barX = BAR_X0 + STEP * MOVING;
            int[] barCentre = pixel(barX, BAR_Y, BAR_Z, identity());
            double want = 0.8 * span, slack = Math.max(2.0, 0.2 * want);
            double rampOff = ramp(off.image, barCentre[0], barCentre[1]);
            check(failures, "the bar, motion blur off", rampOff, 0, 3.0);
            for (Run r : List.of(tsr, tsr60, taa, plain)) {
                check(failures, "the bar, " + r.name, ramp(r.image, barCentre[0], barCentre[1]), want - slack, want + slack);
            }
            int[] still = box(STILL_X - 0.1f, STILL_X + 0.1f, BAR_Y - 0.3f, BAR_Y + 0.3f, BAR_Z, identity());
            double stillDiff = meanDifference(off.image, tsr.image, still);
            check(failures, "the still bar against motion blur off", stillDiff, 0, 2.0);

            Matrix4f panned = panned(PAN_STEP * MOVING);
            int[] stripe = nearestStripe(panned);
            double wantPan = 0.8 * panSpan, slackPan = Math.max(2.0, 0.25 * wantPan);
            check(failures, "the stripes, pan off", ramp(panOff.image, stripe[0], stripe[1]), 0, 3.0);
            check(failures, "the stripes, pan", ramp(pan.image, stripe[0], stripe[1]), wantPan - slackPan, wantPan + slackPan);
            check(failures, "the stripes, the camera's share cancelled", ramp(panStill.image, stripe[0], stripe[1]), 0, 3.0);
            int[] card = pixel(CARD_X, CARD_Y, BAR_Z, panned);
            check(failures, "the AfterMotionBlur card, pan", ramp(pan.image, card[0], card[1]), 0, 3.0);

            // Round the spinner: its outer reach sweeps fastest and smears, its hub hardly moves. The share of pixels the blur
            // changed by more than the frames' dither does
            float reach = SPIN_LENGTH * 0.5f;
            double tipDiff = ringChanged(off.image, tsr.image, 0.6f * reach, reach + 0.05f);
            double hubDiff = ringChanged(off.image, tsr.image, 0f, 0.2f * reach);
            if (!(tipDiff > 0.03 && tipDiff > hubDiff * 3.0)) {
                failures.add(String.format(Locale.ROOT, "the spinner: %.3f of its tip ring changed by the blur, of its hub %.3f", tipDiff, hubDiff));
            }

            // Past the bar's right edge (found unblurred), inside its blur: graded differs from ungraded where its weights
            // were blurred with it, as much as the wall far out does not
            int edge = rightEdge(off.image, barCentre[0], barCentre[1]);
            int[] beyond = {edge + 1, barCentre[1] - 3, edge + Math.max(2, (int) (span * 0.4)), barCentre[1] + 3};
            int[] far = {edge + 40, barCentre[1] - 3, edge + 50, barCentre[1] + 3};
            double satGraded = meanDifference(graded.image, tsr.image, beyond), satPlain = meanDifference(graded.image, tsr.image, far);
            if (!(satGraded > satPlain + 3.0)) {
                failures.add(String.format(Locale.ROOT, "the graded bar's blur: %.2f codes from ungraded, the wall far out %.2f", satGraded, satPlain));
            }
            System.out.printf(Locale.ROOT, "[motion-blur] span %.1f px a frame (want a ramp of %.1f), pan %.1f px (%.1f);"
                            + " spinner tip ring %.3f changed, hub %.3f; graded blur %.2f codes from ungraded, far out %.2f%n",
                    span, want, panSpan, wantPan, tipDiff, hubDiff, satGraded, satPlain);
        } catch (IOException e) {
            failures.add("a capture could not be written: " + e);
        } finally {
            main.post().settings().bloomIntensity(bloom);
            main.showFlags().motionBlur(false);
            main.cameraMotionBlur(true);
            main.screenPercentage(100f).antiAliasing(CgAntiAliasing.TSR);
            CgTemporalAA.enable(main, false);
        }
        report(failures, DeviceErrors.checkAndLog("motion-blur"));
    }

    private record Run(String name, BufferedImage image) {
    }

    /** FRAMES frames: the bar moving its last MOVING, or the camera panning; motion blur on or off; graded or not. */
    private Run run(CgAntiAliasing method, float percent, boolean temporal, boolean blur, boolean grade, boolean panCamera,
                    boolean cameraBlur) {
        CgView main = CgView.main();
        CgTemporalAA.enable(main, temporal);
        main.screenPercentage(percent).antiAliasing(method);
        main.showFlags().motionBlur(blur);
        main.cameraMotionBlur(cameraBlur);
        CgScene scene = CgScene.client();
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgMesh cube = CgMeshShapes.cube(), quad = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 1f, 1f);
        List<CgMeshComponent> spawned = new ArrayList<>();
        spawned.add(scene.mesh(cube, line).at(0, 0, WALL_Z).scale(12f, 8f, 0.1f).customData(0, 0.12f, 0.25f, 0.55f, 1f).spawn());
        for (int i = -6; i <= 6; i++) {
            spawned.add(scene.mesh(cube, line).at(i * 2f, STRIPE_Y, WALL_Z + 0.2f).scale(0.3f, 1f, 0.05f)
                    .customData(0, 0.85f, 0.85f, 0.8f, 1f).spawn());
        }
        CgMeshComponent bar = scene.mesh(cube, line).at(BAR_X0, BAR_Y, BAR_Z).scale(BAR_WIDTH, 0.6f, 0.15f)
                .customData(0, 0.9f, 0.45f, 0.08f, 1f).grade(grade ? look : null).spawn();
        CgMeshComponent spinner = scene.mesh(cube, line).at(SPIN_X, SPIN_Y, SPIN_Z).scale(SPIN_LENGTH, 0.05f, 0.05f)
                .customData(0, 0.9f, 0.9f, 0.9f, 1f).spawn();
        spawned.add(bar);
        spawned.add(spinner);
        spawned.add(scene.mesh(cube, line).at(STILL_X, BAR_Y, BAR_Z).scale(0.15f, 0.4f, 0.15f).customData(0, 0.3f, 0.9f, 0.3f, 1f).spawn());
        spawned.add(scene.mesh(quad, cardMaterial.instance()).at(CARD_X, CARD_Y, BAR_Z).scale(CARD_HALF).spawn());
        BufferedImage last = null;
        for (int f = 0; f < FRAMES; f++) {
            int moved = Math.max(0, f - (FRAMES - 1 - MOVING));
            CgFrameClock.set(f / (double) FPS);
            target.bind();
            target.clear(0.02f, 0.02f, 0.03f, 1f);
            if (!panCamera) bar.at(BAR_X0 + STEP * moved, BAR_Y, BAR_Z);
            spinner.rotation(new Quaternionf().rotateZ(SPIN_STEP * f));
            scene.update();
            Matrix4f v = panCamera ? panned(PAN_STEP * moved) : identity();
            view.set(v);
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (f == FRAMES - 1) last = HarnessCapture.image(target.external());
            target.unbind();
        }
        for (CgMeshComponent c : spawned) c.remove();
        target.delete();
        String name = (temporal ? method.name().toLowerCase(Locale.ROOT) + "-" + (int) percent : "no-temporal")
                + (blur ? "" : "-off") + (grade ? "-graded" : "") + (panCamera ? "-pan" : "") + (cameraBlur ? "" : "-camera-off");
        return new Run(name, last);
    }

    private static Matrix4f identity() {
        return new Matrix4f();
    }

    private static Matrix4f panned(float yaw) {
        return new Matrix4f().rotateY(yaw);
    }

    private static void save(HarnessContext ctx, Run r) throws IOException {
        ImageIO.write(r.image(), "png", new File(ctx.getOutputDir(), r.name() + ".png"));
    }

    /** A move of {@code dx} along x at depth {@code z}, in pixels. */
    private float pixelsAt(float dx, float z) {
        return Math.abs(project(dx, 0f, z, identity()) - project(0f, 0f, z, identity()));
    }

    private float project(float x, float y, float z, Matrix4f v) {
        Vector4f c = new Matrix4f(projection).mul(v).transform(new Vector4f(x, y, z, 1f));
        return (c.x / c.w * 0.5f + 0.5f) * W;
    }

    /** The pixel the world point projects to: x, then y from the top. */
    private int[] pixel(float x, float y, float z, Matrix4f v) {
        Vector4f c = new Matrix4f(projection).mul(v).transform(new Vector4f(x, y, z, 1f));
        return new int[] {(int) Math.floor((c.x / c.w * 0.5 + 0.5) * W), (int) Math.floor((0.5 - c.y / c.w * 0.5) * H)};
    }

    /** The stripe whose centre lands nearest the screen's middle under {@code v}: its pixel. */
    private int[] nearestStripe(Matrix4f v) {
        int[] best = null;
        for (int i = -6; i <= 6; i++) {
            int[] p = pixel(i * 2f, STRIPE_Y, WALL_Z + 0.2f, v);
            if (best == null || Math.abs(p[0] - W / 2) < Math.abs(best[0] - W / 2)) best = p;
        }
        return best;
    }

    /**
     * How wide the edges of the shape centred at (cx, cy) are: on the rows round cy, the pixels between 10% and 90% of
     * the way from the background to the shape's middle, left and right of it, averaged.
     */
    private static double ramp(BufferedImage img, int cx, int cy) {
        // One channel, the one the shape differs most in: a mix of two colours is monotonic in each channel, not in luma
        double best = -1, result = 0;
        for (int c = 0; c < 3; c++) {
            double[] profile = new double[W];
            for (int x = 0; x < W; x++) {
                double s = 0;
                for (int y = cy - 3; y <= cy + 3; y++) s += img.getRGB(x, Math.max(0, Math.min(H - 1, y))) >> (8 * c) & 0xFF;
                profile[x] = s / 7.0;
            }
            double inside = profile[Math.max(0, Math.min(W - 1, cx))];
            double contrast = Math.abs(inside - background(profile, cx, -1)) + Math.abs(inside - background(profile, cx, 1));
            if (contrast > best) {
                best = contrast;
                result = (edge(profile, cx, -1, inside) + edge(profile, cx, 1, inside)) * 0.5;
            }
        }
        return result;
    }

    /** The profile 40 to 60 pixels out from cx, by dir. */
    private static double background(double[] profile, int cx, int dir) {
        double bg = 0;
        int n = 0;
        for (int k = 40; k <= 60; k++) {
            int x = cx + dir * k;
            if (x < 0 || x >= W) continue;
            bg += profile[x];
            n++;
        }
        return bg / Math.max(1, n);
    }

    /** Walking from cx by dir until the profile settles back to the background: the pixels between 10% and 90%. */
    private static double edge(double[] profile, int cx, int dir, double inside) {
        double bg = background(profile, cx, dir);
        double span = inside - bg;
        if (Math.abs(span) < 1e-3) return 0;
        int count = 0;
        for (int k = 0; k < 40; k++) {
            int x = cx + dir * k;
            if (x < 0 || x >= W) break;
            double t = (profile[x] - bg) / span;
            if (t > 0.1 && t < 0.9) count++;
        }
        return count;
    }

    private static void check(List<String> failures, String what, double value, double lo, double hi) {
        System.out.printf(Locale.ROOT, "[motion-blur] %s: %.2f (%.1f to %.1f)%n", what, value, lo, hi);
        if (!(value >= lo && value <= hi)) failures.add(String.format(Locale.ROOT, "%s: %.2f, not %.1f to %.1f", what, value, lo, hi));
    }

    /** How far {@code box}'s mean differs between the two: the worst channel, in codes. */
    private static double difference(BufferedImage a, BufferedImage b, int[] box) {
        double[] sum = new double[3];
        int n = 0;
        for (int y = box[1]; y <= box[3]; y++) {
            for (int x = box[0]; x <= box[2]; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                for (int c = 0; c < 3; c++) sum[c] += Math.abs((p >> (8 * c) & 0xFF) - (q >> (8 * c) & 0xFF));
                n++;
            }
        }
        return Math.max(sum[0], Math.max(sum[1], sum[2])) / Math.max(1, n);
    }

    /** How far {@code box}'s mean differs between the two: the worst channel, in codes. */
    private static double meanDifference(BufferedImage a, BufferedImage b, int[] box) {
        double[] sum = new double[3];
        int n = 0;
        for (int y = box[1]; y <= box[3]; y++) {
            for (int x = box[0]; x <= box[2]; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                for (int c = 0; c < 3; c++) sum[c] += (p >> (8 * c) & 0xFF) - (q >> (8 * c) & 0xFF);
                n++;
            }
        }
        return Math.max(Math.abs(sum[0]), Math.max(Math.abs(sum[1]), Math.abs(sum[2]))) / Math.max(1, n);
    }

    /** The share of pixels whose world distance from the spinner's axis is lo to hi that differ by more than 25 codes. */
    private double ringChanged(BufferedImage a, BufferedImage b, float lo, float hi) {
        int[] c = pixel(SPIN_X, SPIN_Y, SPIN_Z, identity());
        double perPixel = -SPIN_Z / (projection.m11() * H * 0.5);
        double sum = 0;
        int n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double r = Math.hypot(x - c[0], y - c[1]) * perPixel;
                if (r < lo || r >= hi) continue;
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = 0;
                for (int k = 0; k < 3; k++) d = Math.max(d, Math.abs((p >> (8 * k) & 0xFF) - (q >> (8 * k) & 0xFF)));
                if (d > 25) sum++;
                n++;
            }
        }
        return sum / Math.max(1, n);
    }

    /** The bar's right edge on row cy: where its blue (the bar has little, the wall much) crosses halfway. */
    private static int rightEdge(BufferedImage img, int cx, int cy) {
        double inside = img.getRGB(cx, cy) & 0xFF, wall = img.getRGB(Math.min(W - 1, cx + 60), cy) & 0xFF;
        for (int x = cx; x < Math.min(W, cx + 60); x++) {
            if ((img.getRGB(x, cy) & 0xFF) > (inside + wall) * 0.5) return x;
        }
        return cx + 30;
    }

    /** The pixels of the world rectangle at depth {@code z} under {@code v}: x0 to x1, y0 to y1, top-left first. */
    private int[] box(float x0, float x1, float y0, float y1, float z, Matrix4f v) {
        int[] a = pixel(x0, y0, z, v), b = pixel(x1, y1, z, v);
        return new int[] {Math.max(0, Math.min(a[0], b[0])), Math.max(0, Math.min(a[1], b[1])),
                Math.min(W - 1, Math.max(a[0], b[0])), Math.min(H - 1, Math.max(a[1], b[1]))};
    }

    private static void report(List<String> failures, boolean deviceErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (deviceErrors) {
            System.out.println("[motion-blur] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[motion-blur] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[motion-blur] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[motion-blur] PASS on " + on + ": a moving bar and a camera pan blur by a frame's motion under"
                    + " TSR, TAA and none; still things, an AfterMotionBlur card and a cancelled camera stay sharp; a spinner's"
                    + " tip blurs; a graded bar's blur keeps its look");
        }
    }

    @Override
    public void dispose() {
    }
}
