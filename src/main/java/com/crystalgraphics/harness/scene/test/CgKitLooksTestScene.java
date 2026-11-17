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
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.post.CgPost;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.look.CgFilmLut;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.post.settings.CgPostSettings.Control;
import com.crystalgraphics.render.post.settings.CgPostSettings.Region;
import com.crystalgraphics.render.post.upscale.CgAntiAliasing;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.view.CgView;

/**
 * R14g's gate: looks per primitive, as weights the temporal step resolves. Two kits' balls in two looks (A drains the
 * colour, B warms it) before a wall in the frame's look; B's additive aura over A's edge and the wall; A's dark smoke and
 * B's masked card over the wall. Every run goes through the main view's temporal step, its picture the mean of its last
 * frames. Checked from the pixels:
 * <ul>
 *   <li>a pixel wholly a look's is the CPU's bake of its table ({@code CgFilmLut}) read at the ungraded picture, under TSR
 *       at 100% and under TAA; the wall away from every look is the ungraded picture;</li>
 *   <li>at 60% a graded silhouette wobbles no more than the same silhouette ungraded;</li>
 *   <li>the aura's halo over the wall takes B's warmth; the smoke over the wall A's grey; the card's holes stay the
 *       wall's, its cells take B;</li>
 *   <li>defocused by depth of field, A's blur over the wall keeps A's grey (its weights carried through the blur).</li>
 * </ul>
 * Prints {@code [kit-looks] PASS} or {@code FAIL}; a Vulkan validation error fails it.
 */
public final class CgKitLooksTestScene implements HarnessSceneLifecycle {

    private static final int W = 480, H = 270, FRAMES = 64, LAST = 16;
    private static final long WARM_NANOS = 30_000_000_000L;
    /** A box's mean against its table's read, and against the ungraded picture, per channel: the jitter's noise. */
    private static final double TABLE_CODES = 1.5;
    private static final float BALL_Z = -4f, BALL_R = 0.6f, AX = -1.2f, BX = 0.8f;

    private final CgPostSettings lookA = new CgPostSettings().tonemap().grade(Region.GLOBAL, Control.SATURATION, 1f, 1f, 1f, 0f).bloom(0f);
    private final CgPostSettings lookB = new CgPostSettings().tonemap().grade(Region.GLOBAL, Control.GAIN, 1.4f, 0.9f, 0.5f, 1f).bloom(0f);
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();
    private CgMaterial lineMaterial, aura, smoke, card;
    private CgMaterialInstance line;

    @Override
    public void init(HarnessContext ctx) {
        lineMaterial = CgMaterial.load("assets/harness/shader/taa_lab_line.shader");
        line = lineMaterial.instance();
        aura = CgMaterial.load("assets/harness/shader/kit_looks_aura.shader");
        smoke = CgMaterial.load("assets/harness/shader/kit_looks_smoke.shader");
        card = CgMaterial.load("assets/harness/shader/kit_looks_card.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgPostRenderer.install();
        CgCamera.perspectiveMatrix(projection, 40f, (float) W / H, 0.05f, 100f);
        view.identity();
        CgPipelinePrecache.Request warm = CgPipelinePrecache.request(p -> {
            for (CgMaterial m : List.of(lineMaterial, aura, smoke, card)) p.add(m, false);
        });
        long until = System.nanoTime() + WARM_NANOS;
        while (!warm.ready() && System.nanoTime() < until) {
            LockSupport.parkNanos(1_000_000L);
            CgWarmBudget.nextFrame();
        }
        CgView main = CgView.main();
        CgTemporalAA.enable(main, true);
        float bloom = main.post().settings().bloomIntensity();
        main.post().settings().bloomIntensity(0f);
        List<String> failures = new ArrayList<>();
        try {
            Run plain = run(CgAntiAliasing.TSR, 100f, false, false), graded = run(CgAntiAliasing.TSR, 100f, true, false);
            Run plainTaa = run(CgAntiAliasing.TAA, 100f, false, false), gradedTaa = run(CgAntiAliasing.TAA, 100f, true, false);
            Run plain60 = run(CgAntiAliasing.TSR, 60f, false, false), graded60 = run(CgAntiAliasing.TSR, 60f, true, false);
            Run plainDof = run(CgAntiAliasing.TSR, 100f, false, true), gradedDof = run(CgAntiAliasing.TSR, 100f, true, true);
            for (Run r : List.of(plain, graded, plain60, graded60, plainDof, gradedDof)) save(ctx, r);

            // A's lower left and B's lower right: under neither the aura nor an edge
            int[] insideA = box(AX - 0.4f, AX - 0.15f, -0.4f, -0.15f, BALL_Z), insideB = box(BX + 0.1f, BX + 0.35f, -0.35f, -0.1f, BALL_Z);
            int[] wall = box(-4.6f, -4.2f, -2.5f, -2.2f, -8f);
            float[] tableA = bake(lookA), tableB = bake(lookB);
            check(failures, "TSR 100%: A's pixels", tableError(plain.mean, graded.mean, tableA, insideA), TABLE_CODES);
            check(failures, "TSR 100%: B's pixels", tableError(plain.mean, graded.mean, tableB, insideB), TABLE_CODES);
            check(failures, "TAA: A's pixels", tableError(plainTaa.mean, gradedTaa.mean, tableA, insideA), TABLE_CODES);
            check(failures, "TSR 100%: the wall away from every look", difference(plain.mean, graded.mean, wall), TABLE_CODES);

            int[] silhouette = box(AX - BALL_R - 0.08f, AX - BALL_R + 0.08f, -0.3f, 0.3f, BALL_Z);
            double wobbleGraded = wobble(graded60, silhouette), wobblePlain = wobble(plain60, silhouette);
            if (wobbleGraded > wobblePlain * 1.25 + 0.5) {
                failures.add(String.format(Locale.ROOT, "TSR 60%%: A's silhouette wobbles %.2f graded against %.2f", wobbleGraded, wobblePlain));
            }

            int[] halo = box(-0.3f, -0.1f, 0.9f, 1.05f, -3.5f);
            double haloWarm = warmth(graded.mean, halo), haloWas = warmth(plain.mean, halo);
            if (!(haloWarm > haloWas * 1.15)) failures.add(String.format(Locale.ROOT, "the aura's halo: warmth %.2f against %.2f", haloWarm, haloWas));
            int[] smoky = box(2.0f, 2.4f, 0.5f, 0.8f, BALL_Z);
            double smokeSat = saturation(graded.mean, smoky), smokeWas = saturation(plain.mean, smoky);
            if (!(smokeSat < smokeWas * 0.6)) failures.add(String.format(Locale.ROOT, "the smoke: saturation %.2f against %.2f", smokeSat, smokeWas));
            // The card spans x 1.75-2.65, y -1.25 to -0.35: its first cell (0, 0) is a hole, the next a cell
            int[] hole = box(1.80f, 1.92f, -1.20f, -1.08f, BALL_Z), cell = box(2.03f, 2.15f, -1.20f, -1.08f, BALL_Z);
            check(failures, "the card's hole", difference(plain.mean, graded.mean, hole), TABLE_CODES);
            double cellWarm = warmth(graded.mean, cell), cellWas = warmth(plain.mean, cell);
            if (!(cellWarm > cellWas * 1.15)) failures.add(String.format(Locale.ROOT, "the card's cell: warmth %.2f against %.2f", cellWarm, cellWas));

            // A's blur 6 to 12 pixels past its silhouette, left of it (the aura is to its right), in blue, where A's grey
            // over the blue wall shows: carried through the blur, its weight greys it; held sharp, it is the frame's,
            // as ungraded as the wall far out (measured: 7.4 codes carried, 3.2 held sharp, 2.7 far out)
            int[] centre = box(AX, AX, 0f, 0f, BALL_Z), edge = box(AX - BALL_R, AX - BALL_R, 0f, 0f, BALL_Z);
            int cx = centre[0], cy = centre[1], r = cx - edge[0];
            double blurDiff = ringDifference(plainDof.mean, gradedDof.mean, cx, cy, r + 6, r + 12),
                    farDiff = ringDifference(plainDof.mean, gradedDof.mean, cx, cy, r + 30, r + 40);
            if (!(blurDiff > farDiff + 2.5)) {
                failures.add(String.format(Locale.ROOT, "A's blur over the wall: %.2f codes from ungraded, the wall far out %.2f", blurDiff, farDiff));
            }
            System.out.printf(Locale.ROOT, "[kit-looks] wobble at 60%%: graded %.2f, ungraded %.2f; halo warmth %.2f from %.2f;"
                            + " smoke saturation %.2f from %.2f; card cell warmth %.2f from %.2f; A's blur %.2f codes from ungraded, the wall far out %.2f%n",
                    wobbleGraded, wobblePlain, haloWarm, haloWas, smokeSat, smokeWas, cellWarm, cellWas, blurDiff, farDiff);
        } catch (IOException e) {
            failures.add("a capture could not be written: " + e);
        } finally {
            main.post().settings().bloomIntensity(bloom);
            main.screenPercentage(100f).antiAliasing(CgAntiAliasing.TSR);
        }
        report(failures, DeviceErrors.checkAndLog("kit-looks"));
    }

    /** A run's last frames and their mean. */
    private record Run(String name, BufferedImage mean, List<BufferedImage> frames) {
    }

    /** FRAMES still frames through {@code method} at {@code percent}, graded or not, the lens focused past the balls or not. */
    private Run run(CgAntiAliasing method, float percent, boolean grade, boolean defocus) {
        CgView.main().screenPercentage(percent).antiAliasing(method);
        CgScene scene = CgScene.client();
        CgPost.client().camera().reset();
        if (defocus) CgPost.client().camera().depthOfField(0.3f, 1f);
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgMesh cube = CgMeshShapes.cube(), ball = CgMeshShapes.sphere(24, 32), quad = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 1f, 1f);
        List<BufferedImage> frames = new ArrayList<>();
        for (int f = 0; f < FRAMES; f++) {
            target.bind();
            target.clear(0.02f, 0.02f, 0.03f, 1f);
            scene.once().mesh(cube, line).at(0, 0, -8).scale(12f, 8f, 0.1f).customData(0, 0.12f, 0.25f, 0.55f, 1f).add();
            scene.once().mesh(ball, line).at(AX, 0, BALL_Z).scale(BALL_R).customData(0, 0.6f, 0.3f, 0.08f, 1f)
                    .grade(grade ? lookA : null).add();
            scene.once().mesh(ball, line).at(BX, 0, BALL_Z).scale(BALL_R).customData(0, 0.6f, 0.3f, 0.08f, 1f)
                    .grade(grade ? lookB : null).add();
            // The quad spans -1 to 1: the aura 0.6 round over A's upper right, the smoke and the card 0.9 across
            scene.once().mesh(quad, aura).at(-0.4, 0.6, -3.5).scale(0.6f).grade(grade ? lookB : null).add();
            scene.once().mesh(quad, smoke).at(2.2, 0.75, BALL_Z).scale(0.45f).grade(grade ? lookA : null).add();
            scene.once().mesh(quad, card).at(2.2, -0.8, BALL_Z).scale(0.45f).customData(0, 0.6f, 0.3f, 0.08f, 1f)
                    .grade(grade ? lookB : null).add();
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (f >= FRAMES - LAST) frames.add(HarnessCapture.image(target.external()));
            target.unbind();
        }
        target.delete();
        CgPost.client().camera().reset();
        String name = method.name().toLowerCase(Locale.ROOT) + "-" + (int) percent + (grade ? "-graded" : "-plain") + (defocus ? "-dof" : "");
        return new Run(name, mean(frames), frames);
    }

    private static void save(HarnessContext ctx, Run r) throws IOException {
        ImageIO.write(r.mean(), "png", new File(ctx.getOutputDir(), r.name() + ".png"));
    }

    private static float[] bake(CgPostSettings look) {
        float[] table = new float[CgFilmLut.CELLS * 3];
        CgFilmLut.bake(look, table);
        return table;
    }

    /** How far {@code box}'s mean in {@code graded} is from its mean in {@code plain} read through {@code table}: the worst channel, in codes. */
    private static double tableError(BufferedImage plain, BufferedImage graded, float[] table, int[] box) {
        double[] want = new double[3], sumWant = new double[3], sumGot = new double[3];
        int n = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int p = plain.getRGB(x, y), t = graded.getRGB(x, y);
                CgFilmLut.tonemap(table, decode(p >> 16 & 0xFF), decode(p >> 8 & 0xFF), decode(p & 0xFF), want);
                for (int c = 0; c < 3; c++) {
                    sumWant[c] += want[c] * 255;
                    sumGot[c] += t >> (16 - 8 * c) & 0xFF;
                }
                n++;
            }
        }
        double worst = 0;
        for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(sumGot[c] - sumWant[c]) / Math.max(1, n));
        return worst;
    }

    private static void check(List<String> failures, String what, double codes, double most) {
        System.out.printf(Locale.ROOT, "[kit-looks] %s: %.2f codes%n", what, codes);
        if (!(codes <= most)) failures.add(String.format(Locale.ROOT, "%s: %.2f codes (at most %.1f)", what, codes, most));
    }

    /** How far {@code box}'s mean differs between the two: the worst channel, in codes. */
    private static double difference(BufferedImage a, BufferedImage b, int[] box) {
        double[] sum = new double[3];
        int n = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                for (int c = 0; c < 3; c++) sum[c] += (p >> (8 * c) & 0xFF) - (q >> (8 * c) & 0xFF);
                n++;
            }
        }
        return Math.max(Math.abs(sum[0]), Math.max(Math.abs(sum[1]), Math.abs(sum[2]))) / Math.max(1, n);
    }

    /** The mean absolute blue difference between the two over the left half of the ring {@code lo} to {@code hi} pixels round (cx, cy). */
    private static double ringDifference(BufferedImage a, BufferedImage b, int cx, int cy, int lo, int hi) {
        double sum = 0;
        int n = 0;
        for (int y = Math.max(0, cy - hi); y < Math.min(H, cy + hi); y++) {
            for (int x = Math.max(0, cx - hi); x < cx; x++) {
                double d = Math.hypot(x - cx, y - cy);
                if (d < lo || d >= hi) continue;
                sum += Math.abs((a.getRGB(x, y) & 0xFF) - (b.getRGB(x, y) & 0xFF));
                n++;
            }
        }
        return sum / Math.max(1, n);
    }

    /** How far the run's last frames move about their mean in {@code box}: the mean absolute luma change. */
    private static double wobble(Run r, int[] box) {
        double sum = 0;
        for (BufferedImage img : r.frames()) {
            double s = 0;
            int n = 0;
            for (int y = box[1]; y < box[3]; y++) {
                for (int x = box[0]; x < box[2]; x++) {
                    s += Math.abs(luma(img.getRGB(x, y)) - luma(r.mean().getRGB(x, y)));
                    n++;
                }
            }
            sum += s / Math.max(1, n);
        }
        return sum / r.frames().size();
    }

    /** Red over blue, meaned over the box: how warm it is. */
    private static double warmth(BufferedImage img, int[] box) {
        double r = 0, b = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int p = img.getRGB(x, y);
                r += p >> 16 & 0xFF;
                b += p & 0xFF;
            }
        }
        return r / Math.max(1.0, b);
    }

    /** The mean of (max - min) / max over the box: 0 for grey. */
    private static double saturation(BufferedImage img, int[] box) {
        double sum = 0;
        int n = 0;
        for (int y = box[1]; y < box[3]; y++) {
            for (int x = box[0]; x < box[2]; x++) {
                int p = img.getRGB(x, y), r = p >> 16 & 0xFF, g = p >> 8 & 0xFF, b = p & 0xFF;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                sum += max == 0 ? 0 : (max - min) / (double) max;
                n++;
            }
        }
        return sum / Math.max(1, n);
    }

    private static BufferedImage mean(List<BufferedImage> frames) {
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int r = 0, g = 0, b = 0;
                for (BufferedImage img : frames) {
                    int p = img.getRGB(x, y);
                    r += p >> 16 & 0xFF;
                    g += p >> 8 & 0xFF;
                    b += p & 0xFF;
                }
                int n = frames.size();
                out.setRGB(x, y, Math.round(r / (float) n) << 16 | Math.round(g / (float) n) << 8 | Math.round(b / (float) n));
            }
        }
        return out;
    }

    /** The pixels of the world rectangle at depth {@code z}: x0 to x1, y0 to y1, top-left first. */
    private int[] box(float x0, float x1, float y0, float y1, float z) {
        Matrix4f clip = new Matrix4f(projection).mul(view);
        Vector4f a = clip.transform(new Vector4f(x0, y0, z, 1f)), b = clip.transform(new Vector4f(x1, y1, z, 1f));
        int px0 = (int) Math.floor((a.x / a.w * 0.5 + 0.5) * W), px1 = (int) Math.ceil((b.x / b.w * 0.5 + 0.5) * W);
        int ya = (int) Math.round((0.5 - a.y / a.w * 0.5) * H), yb = (int) Math.round((0.5 - b.y / b.w * 0.5) * H);
        return new int[] {Math.max(0, px0), Math.max(0, Math.min(ya, yb)), Math.min(W - 1, px1), Math.min(H - 1, Math.max(ya, yb))};
    }

    private static double decode(int code) {
        double c = code / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int luma(int argb) {
        return (argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff);
    }

    private static void report(List<String> failures, boolean deviceErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (deviceErrors) {
            System.out.println("[kit-looks] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[kit-looks] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[kit-looks] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[kit-looks] PASS on " + on + ": a pixel wholly a look's is its table's read under TSR and TAA,"
                    + " the frame away from them untouched; a graded silhouette at 60% no wobblier; an aura, smoke and a card's"
                    + " cells take their look, its holes the frame's; a defocused look's blur keeps its look");
        }
    }

    @Override
    public void dispose() {
    }
}
