package com.crystalgraphics.harness.scene.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import com.crystalgraphics.render.post.upscale.CgUpscaleFilter;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.view.CgView;

/**
 * Screen percentage's gate (R14d): the same frame drawn at 100% and at 50% through each upscale filter, into one 640x360
 * target. At 50% the world draws at 320x180 and the picture is upscaled to the target: each filter's picture stays near
 * the 100% one (bars and posts on a plain ground: a fine pattern aliases differently at each size, a lab's subject), every filter but bilinear keeps more of the edges than bilinear, {@code OVERLAY} draws at the full size,
 * and a host drawing a world of its own draws at 100% whatever is asked. Scene id {@code screen-percentage}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=screen-percentage --rhi=vulkan"
 * }</pre>
 */
public final class CgScreenPercentageTestScene implements HarnessSceneLifecycle {

    private static final int W = 640, H = 360, FRAMES = 30;

    private CgMaterialInstance line;
    private final Matrix4f projection = new Matrix4f(), view = new Matrix4f();
    /** The size {@code OVERLAY} last drew at, and the main view's world size that frame. */
    private int overlayWidth, overlayHeight, drawnWidth, drawnHeight;

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
        CgRenderStage.Registration overlay = CgRenderStage.OVERLAY.register(Integer.MAX_VALUE - 1, f -> {
            if (f.view() != CgView.main()) return;
            overlayWidth = f.width();
            overlayHeight = f.height();
            drawnWidth = f.view().width();
            drawnHeight = f.view().height();
        });
        CgView main = CgView.main();
        try {
            run(ctx, 100f, CgUpscaleFilter.CATMULL_ROM, false, "full.png");
            if (overlayWidth != W || drawnWidth != W) failures.add("at 100% the world drew at " + drawnWidth + " wide, OVERLAY at " + overlayWidth);
            Map<CgUpscaleFilter, String> files = new EnumMap<>(CgUpscaleFilter.class);
            for (CgUpscaleFilter filter : CgUpscaleFilter.values()) {
                String file = "half-" + filter.name().toLowerCase(Locale.ROOT) + ".png";
                run(ctx, 50f, filter, false, file);
                files.put(filter, file);
                if (drawnWidth != W / 2 || drawnHeight != H / 2) {
                    failures.add(filter + ": at 50% the world drew at " + drawnWidth + "x" + drawnHeight);
                }
                if (overlayWidth != W || overlayHeight != H) {
                    failures.add(filter + ": OVERLAY drew at " + overlayWidth + "x" + overlayHeight + ", not the target's " + W + "x" + H);
                }
            }
            run(ctx, 50f, CgUpscaleFilter.CATMULL_ROM, true, "host-world.png");
            if (drawnWidth != W) failures.add("a host drawing its own world drew at " + drawnWidth + " wide, not 100%");

            BufferedImage full = ImageIO.read(new File(ctx.getOutputDir(), "full.png"));
            double hostChange = change(full, ImageIO.read(new File(ctx.getOutputDir(), "host-world.png")));
            System.out.printf(Locale.ROOT, "[screen-percentage] a host's own world at 50%% asked: %.2f from 100%%%n", hostChange);
            if (hostChange > 0.5) failures.add(String.format(Locale.ROOT, "a host's own world was drawn smaller: %.2f from 100%%", hostChange));
            double fullEdges = edges(full);
            Map<CgUpscaleFilter, Double> edge = new EnumMap<>(CgUpscaleFilter.class);
            for (CgUpscaleFilter filter : CgUpscaleFilter.values()) {
                BufferedImage half = ImageIO.read(new File(ctx.getOutputDir(), files.get(filter)));
                double off = change(full, half), e = edges(half);
                edge.put(filter, e);
                System.out.printf(Locale.ROOT, "[screen-percentage] 50%% %s: %.2f from 100%%, edge energy %.0f (100%%: %.0f)%n",
                        filter, off, e, fullEdges);
                if (off > 15) failures.add(String.format(Locale.ROOT, "%s at 50%% is far from 100%%: %.2f", filter, off));
                if (e < 1) failures.add(filter + " at 50% drew nothing: no edges");
            }
            for (CgUpscaleFilter filter : new CgUpscaleFilter[]{CgUpscaleFilter.CATMULL_ROM, CgUpscaleFilter.LANCZOS, CgUpscaleFilter.EASU}) {
                if (edge.get(filter) <= edge.get(CgUpscaleFilter.BILINEAR)) {
                    failures.add(String.format(Locale.ROOT, "%s keeps no more edges than bilinear: %.0f against %.0f",
                            filter, edge.get(filter), edge.get(CgUpscaleFilter.BILINEAR)));
                }
            }
        } catch (IOException e) {
            failures.add("the captures could not be read: " + e);
        } finally {
            overlay.close();
            main.screenPercentage(100f).upscaleFilter(CgUpscaleFilter.CATMULL_ROM);
        }
        report(failures, DeviceErrors.checkAndLog("screen-percentage"));
    }

    /** FRAMES frames at {@code percent} through {@code filter} into one target, the last captured; {@code hostWorld} as Minecraft's. */
    private void run(HarnessContext ctx, float percent, CgUpscaleFilter filter, boolean hostWorld, String file) {
        CgView.main().screenPercentage(percent).upscaleFilter(filter);
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
            if (hostWorld) {
                // As Minecraft fires them: a host drawing a world of its own
                CgStageHost.frame(CgStageHost.WORLD_OPAQUE).view(CgView.main()).drawsWorld(true);
                CgStageHost.fire(CgStageHost.WORLD_OPAQUE);
                CgStageHost.fire(CgStageHost.WORLD_TRANSPARENT);
            } else {
                HarnessWorld.fireCallbacks();
            }
            CgFrameRing.endFrame();
            if (f == FRAMES - 1) target.captureToFile(ctx.getOutputDir(), file);
            target.unbind();
        }
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).drawsWorld(false);
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
            System.out.println("[screen-percentage] FAIL on " + on + ": device errors, logged above");
        } else if (validation > 0) {
            System.out.println("[screen-percentage] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[screen-percentage] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[screen-percentage] PASS on " + on + ": the world draws at 50% and every filter upscales it,"
                    + " OVERLAY at full size, a host's own world at 100%");
        }
    }

    @Override
    public void dispose() {
    }
}
