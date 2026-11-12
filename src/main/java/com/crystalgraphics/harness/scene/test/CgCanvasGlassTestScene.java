package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.canvas.CgCanvasBackdrop;
import com.crystalgraphics.render.canvas.CgCanvasGlass;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.canvas.CgQuadBatch;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Canvas glass in the world: a pane drawn through a world stage's canvas over a wall of red and blue stripes, under a
 * perspective camera, the backdrop captured and blurred by {@link CgCanvasBackdrop}. Three checks against the wall
 * drawn alone:
 * <ul>
 *   <li>a clear pane turned 30 degrees, no blur, shows the wall behind its flat middle exactly where it is;</li>
 *   <li>a frosted pane, untinted, blurs it: the stripes' spread inside it collapses to their mean in linear light, the
 *       blur running in the transparent stage's linear HDR scene;</li>
 *   <li>a slab in front hides a pane: world glass is depth-tested.</li>
 * </ul>
 *
 * <p>Prints {@code [canvas-glass] PASS} or {@code FAIL}; on Vulkan a validation error fails it. Captures land in its
 * output directory.</p>
 */
public class CgCanvasGlassTestScene implements HarnessSceneLifecycle {

    private static final int W = 320, H = 240;
    /** The middle block compared, in pixels each way from the centre: inside every pane's flat part. */
    private static final int MID = 12;
    /** A block is this many of a look's pixels. */
    private static final float UNITS_PER_PIXEL = 1f / 200f;

    private final CgCanvasBackdrop backdrop = new CgCanvasBackdrop("canvas-glass");
    private CgCanvasGlass pane;
    private CgMaterial slab;

    @Override
    public void init(HarnessContext ctx) {
        pane = new CgCanvasGlass(CgCanvasMaterials.WORLD);
        slab = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        CgPostStack.get().install();
        CgPostStack.get().bloom().intensity(0f);
        List<String> failures = new ArrayList<>();

        draw(ctx, world, null, false, "wall.png");
        CgCanvasGlass.Look clear = CgCanvasGlass.CLEAR.scaled(UNITS_PER_PIXEL);
        draw(ctx, world, clear, false, "clear.png");
        CgCanvasGlass.Look frosted = CgCanvasGlass.FROSTED.withTint(0).scaled(UNITS_PER_PIXEL);
        draw(ctx, world, frosted, false, "frosted.png");
        draw(ctx, world, null, true, "slab.png");
        draw(ctx, world, frosted, true, "hidden.png");

        BufferedImage wall = read(ctx, "wall.png"), clearPane = read(ctx, "clear.png");
        BufferedImage frostedPane = read(ctx, "frosted.png"), hidden = read(ctx, "hidden.png"), slabAlone = read(ctx, "slab.png");
        if (wall == null || clearPane == null || frostedPane == null || hidden == null || slabAlone == null) {
            failures.add("a capture could not be read");
        } else {
            int worst = 0;
            for (int y = H / 2 - MID; y < H / 2 + MID; y++) {
                for (int x = W / 2 - MID; x < W / 2 + MID; x++) worst = Math.max(worst, differ(wall.getRGB(x, y), clearPane.getRGB(x, y)));
            }
            if (worst > 3) failures.add("the clear pane's middle differs from the wall behind it by up to " + worst + " levels");

            double[] bare = spread(wall), blurred = spread(frostedPane);
            if (!(blurred[1] < bare[1] * 0.25)) {
                failures.add("the frosted pane's middle spreads " + (int) blurred[1] + " against the wall's " + (int) bare[1]
                        + ": not blurred");
            }
            for (int shift : new int[] {16, 0}) {
                int made = (int) Math.round(mean(frostedPane, shift)), want = (int) Math.round(linearMean(wall, shift));
                if (Math.abs(made - want) > 8) {
                    failures.add("the frosted pane's middle averages " + made + " in a channel whose linear mean is " + want);
                }
            }
            int seen = 0;
            for (int y = H / 2 - MID; y < H / 2 + MID; y++) {
                for (int x = W / 2 - MID; x < W / 2 + MID; x++) if (differ(hidden.getRGB(x, y), slabAlone.getRGB(x, y)) > 3) seen++;
            }
            if (seen > 0) failures.add("a pane behind the slab shows through it at " + seen + " pixels");
        }
        CgPostStack.get().bloom().intensity(1f);
        report(failures, DeviceErrors.checkAndLog("canvas-glass"));
    }

    /** The wall, then {@code look}'s pane (none if null) through the transparent stage's canvas, a slab in front if asked. */
    private void draw(HarnessContext ctx, CgWorldRenderer world, @Nullable CgCanvasGlass.Look look, boolean slabInFront,
                      String file) {
        HarnessTarget target = HarnessTarget.create(W, H, true);
        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        if (slabInFront) {
            CgMesh cube = CgMeshShapes.cube();
            world.draw(cube, slab).at(0, 0, -1.5f).transform(new Matrix4f().scale(1.5f, 1.5f, 0.05f)).submit();
        }
        CgRenderStage.Registration drawing = CgRenderStage.WORLD_TRANSPARENT.register(100, frame -> {
            CgCanvas canvas = frame.canvas();
            for (int i = 0; i < 24; i++) {
                int argb = (i & 1) == 0 ? 0xFFE03020 : 0xFF2050E0;
                canvas.quad(CgCanvasMaterials.WORLD.image()).at(-3f + i * 0.25f, -2f, -4f).size(0.25f, 4f).color(argb)
                        .submit();
            }
            if (look == null) return;
            Matrix4f pose = new Matrix4f().translate(0f, 0f, -2f).rotateY((float) Math.toRadians(30));
            CgQuadBatch.Quad quad = canvas.quad(pane.material()).pose(pose).at(-0.6f, -0.4f).size(1.2f, 0.8f);
            pane.look(look).saturation(1f).specular(0f).noise(0f).size(1.2f, 0.8f).radii(0.05f)
                    .backdrop(backdrop.capture(canvas, quad, look.blur(), pane.reach()))
                    .apply();
            quad.submit();
        });
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) W / H, 0.05f, 100f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.external()).camera().set(0, 0, 0, new Matrix4f(), projection);
            stage.fire();
        }
        drawing.close();
        CgFrameRing.endFrame();
        target.captureToFile(ctx.getOutputDir(), file);
        target.unbind();
        target.delete();
    }

    /** The middle block's mean level and its standard deviation, over red and blue. */
    private static double[] spread(BufferedImage image) {
        double sum = 0, squares = 0;
        int n = 0;
        for (int y = H / 2 - MID; y < H / 2 + MID; y++) {
            for (int x = W / 2 - MID; x < W / 2 + MID; x++) {
                int argb = image.getRGB(x, y);
                for (int v : new int[] {argb >> 16 & 0xFF, argb & 0xFF}) {
                    sum += v;
                    squares += (double) v * v;
                    n++;
                }
            }
        }
        double mean = sum / n;
        return new double[] {mean, Math.sqrt(Math.max(0, squares / n - mean * mean))};
    }

    /** The middle block's mean of the channel at {@code shift}. */
    private static double mean(BufferedImage image, int shift) {
        double sum = 0;
        int n = 0;
        for (int y = H / 2 - MID; y < H / 2 + MID; y++) {
            for (int x = W / 2 - MID; x < W / 2 + MID; x++, n++) sum += image.getRGB(x, y) >> shift & 0xFF;
        }
        return sum / n;
    }

    /** As {@link #mean}, averaged in linear light and encoded again: what a blur in a linear scene gives. */
    private static double linearMean(BufferedImage image, int shift) {
        double sum = 0;
        int n = 0;
        for (int y = H / 2 - MID; y < H / 2 + MID; y++) {
            for (int x = W / 2 - MID; x < W / 2 + MID; x++, n++) {
                double c = (image.getRGB(x, y) >> shift & 0xFF) / 255.0;
                sum += c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
            }
        }
        double l = sum / n;
        return 255.0 * (l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055);
    }

    private static int differ(int a, int b) {
        int worst = 0;
        for (int shift = 0; shift <= 16; shift += 8) worst = Math.max(worst, Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)));
        return worst;
    }

    @Nullable
    private static BufferedImage read(HarnessContext ctx, String file) {
        try {
            return ImageIO.read(new File(ctx.getOutputDir(), file));
        } catch (IOException e) {
            return null;
        }
    }

    private static void report(List<String> failures, boolean glErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[canvas-glass] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[canvas-glass] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[canvas-glass] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[canvas-glass] PASS on " + on + ": a turned clear pane shows the wall behind it in place,"
                    + " a frosted one blurs it, a slab in front hides one");
        }
    }

    @Override
    public void dispose() {
        backdrop.delete();
    }
}
