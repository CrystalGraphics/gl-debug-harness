package com.crystalgraphics.harness.scene.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.joml.Matrix4f;

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
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.upscale.CgAntiAliasing;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.view.CgView;

/**
 * TSR's gate (R14e): a still frame of bars and posts settled through temporal AA at 100%, the reference, then through TSR
 * at 50, 67 and 100%, and through temporal AA at 50% upscaled spatially, each into one 640x360 target. TSR at 50% must
 * keep more of the reference's edges than temporal AA at 50%, and every TSR picture stay near the reference. Bloom is
 * off: each step keeps a thin light's energy for bloom its own way, and the gate measures the upscale. Scene id
 * {@code temporal-upscale}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=temporal-upscale --rhi=vulkan"
 * }</pre>
 */
public final class CgTemporalUpscaleTestScene implements HarnessSceneLifecycle {

    private static final int W = 640, H = 360, FRAMES = 96;
    /** The last frames each run keeps: the picture is their mean, its wobble their spread. */
    private static final int LAST = 8;

    private CgMaterialInstance line;
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();

    @Override
    public void init(HarnessContext ctx) {
        line = CgMaterial.load("assets/harness/shader/taa_lab_line.shader").instance();
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
        try {
            run(ctx, CgAntiAliasing.TAA, 100f, "taa-full.png");
            run(ctx, CgAntiAliasing.TAA, 50f, "taa-half.png");
            run(ctx, CgAntiAliasing.TSR, 100f, "tsr-full.png");
            run(ctx, CgAntiAliasing.TSR, 67f, "tsr-67.png");
            run(ctx, CgAntiAliasing.TSR, 50f, "tsr-half.png");
            BufferedImage reference = read(ctx, "taa-full.png"), taaHalf = read(ctx, "taa-half.png");
            double referenceEdges = edges(reference), taaHalfEdges = edges(taaHalf);
            System.out.printf(Locale.ROOT, "[temporal-upscale] TAA 100%%: edge energy %.0f, wobble %.2f; TAA 50%% spatial: %.0f,"
                    + " %.2f from it, wobble %.2f%n", referenceEdges, wobble(ctx, "taa-full.png", reference), taaHalfEdges,
                    change(reference, taaHalf), wobble(ctx, "taa-half.png", taaHalf));
            double tsrHalfEdges = 0;
            for (String file : new String[]{"tsr-full.png", "tsr-67.png", "tsr-half.png"}) {
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
        } catch (IOException e) {
            failures.add("the captures could not be read: " + e);
        } finally {
            CgTemporalAA.enable(main, false);
            main.post().settings().bloomIntensity(bloom);
            main.screenPercentage(100f).antiAliasing(CgAntiAliasing.TAA);
        }
        report(failures, DeviceErrors.checkAndLog("temporal-upscale"));
    }

    /** The mean of a run's last frames, as one image. */
    private static BufferedImage read(HarnessContext ctx, String file) throws IOException {
        double[] sum = new double[W * H * 3];
        for (int k = 0; k < LAST; k++) {
            BufferedImage img = ImageIO.read(new File(ctx.getOutputDir(), frameFile(file, k)));
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
    private static double wobble(HarnessContext ctx, String file, BufferedImage mean) throws IOException {
        double sum = 0;
        for (int k = 0; k < LAST; k++) sum += change(mean, ImageIO.read(new File(ctx.getOutputDir(), frameFile(file, k))));
        return sum / LAST;
    }

    private static String frameFile(String file, int k) {
        return file.replace(".png", "-" + k + ".png");
    }

    /** FRAMES still frames through {@code method} at {@code percent} into one target, the last captured. */
    private void run(HarnessContext ctx, CgAntiAliasing method, float percent, String file) {
        CgView.main().screenPercentage(percent).antiAliasing(method);
        HarnessTarget target = HarnessTarget.create(W, H, true);
        CgScene scene = CgScene.client();
        CgMesh cube = CgMeshShapes.cube();
        for (int f = 0; f < FRAMES; f++) {
            target.bind();
            target.clear(0.02f, 0.02f, 0.03f, 1f);
            scene.once().mesh(cube, line).at(0.6, 0.4, -5).transform(new Matrix4f().rotateZ((float) Math.toRadians(4)).scale(3f, 0.15f, 0.02f))
                    .customData(0, 1f, 0.9f, 0.6f, 1f).add();
            for (int i = 0; i < 12; i++) {
                scene.once().mesh(cube, line).at(-3 + i * 0.5, 1.2, -9).scale(0.04f, 1.6f, 0.04f).customData(0, 0.6f, 0.85f, 1f, 2f).add();
            }
            CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
            HarnessWorld.fireCallbacks();
            CgFrameRing.endFrame();
            if (f >= FRAMES - LAST) target.captureToFile(ctx.getOutputDir(), frameFile(file, f - (FRAMES - LAST)));
            target.unbind();
        }
        target.delete();
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
                    + " keeping more edges than temporal AA upscaled spatially");
        }
    }

    @Override
    public void dispose() {
    }
}
