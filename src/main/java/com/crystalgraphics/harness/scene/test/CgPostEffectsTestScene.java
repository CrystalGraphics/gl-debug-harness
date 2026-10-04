package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The post stack's mod SPI, used as a mod would (render-post-process P8): an effect at each {@link CgPostPoint}.
 * {@code AFTER_WORLD} draws a red rectangle into the top left corner; {@code BEFORE_COMPOSITE} feeds the composite a full
 * vignette; {@code AFTER_COMPOSITE} draws a green rectangle into the top right corner and, at a higher order, a blue one
 * over half of it. From the pixels: the red is darkened (the composite ran after it), the green and blue are exact (they
 * ran after the composite), blue covers green where they overlap (order), and closing the registrations restores the
 * plain picture.
 *
 * <p>Prints {@code [post-effects] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgPostEffectsTestScene implements HarnessSceneLifecycle {

    private static final int W = 320, H = 240;

    private CgMaterial wall;

    /** A mod's effect: a solid rectangle drawn over the target at its point and order. */
    private static final class Rect implements CgPostEffect {
        private final CgPostPoint point;
        private final int order;
        private final CgMaterial material = CgMaterial.newInstance("assets/harness/shader/post_rect.shader");

        Rect(CgPostPoint point, int order, float x0, float y0, float x1, float y1, float r, float g, float b) {
            this.point = point;
            this.order = order;
            material.applyProperties(p -> p.vec4("_Rect", x0, y0, x1, y1).vec4("_Color", r, g, b, 1f));
        }

        @Override
        public CgPostPoint point() {
            return point;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public boolean active(CgPostContext post) {
            return true;
        }

        @Override
        public void record(CgPostContext post) {
            CgRasterPass pass = post.recording().raster(post.target(), CgLoad.load(), post.constants(), null, CgOrder.SORTED);
            CgChunkBuilder chunks = post.recording().chunks().begin();
            chunks.draw(material.pipeline(CgInstanceKind.OBJECT), material.captureBindings(post.recording().bindings()),
                    CgMesh.quads(1));
            chunks.instance();
            pass.add(chunks.end());
            pass.end();
        }
    }

    /** A mod's effect feeding the composite: a full vignette. */
    private static final class Vignette implements CgPostEffect {
        @Override
        public CgPostPoint point() {
            return CgPostPoint.BEFORE_COMPOSITE;
        }

        @Override
        public boolean active(CgPostContext post) {
            return true;
        }

        @Override
        public void record(CgPostContext post) {
            post.composite().vignette(1f);
        }
    }

    @Override
    public void init(HarnessContext ctx) {
        wall = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        CgPostStack post = CgPostStack.get();
        post.install();
        List<String> failures = new ArrayList<>();

        BufferedImage plain = draw(ctx, world, "plain");
        List<CgRenderStage.Registration> effects = new ArrayList<>();
        effects.add(post.add(new Rect(CgPostPoint.AFTER_COMPOSITE, 1, 0.85f, 0.85f, 1f, 1f, 0f, 0f, 1f)));   // blue, later
        effects.add(post.add(new Rect(CgPostPoint.AFTER_WORLD, 0, 0f, 0.7f, 0.3f, 1f, 1f, 0f, 0f)));         // red
        effects.add(post.add(new Vignette()));
        effects.add(post.add(new Rect(CgPostPoint.AFTER_COMPOSITE, 0, 0.7f, 0.7f, 1f, 1f, 0f, 1f, 0f)));     // green
        BufferedImage with = draw(ctx, world, "effects");
        for (CgRenderStage.Registration r : effects) r.close();
        BufferedImage after = draw(ctx, world, "closed");

        int red = with.getRGB(1, 1), green = with.getRGB(W * 3 / 4, H / 4 - 4), blue = with.getRGB(W - 2, 1);
        if (((red >> 16) & 0xFF) > 200 || (red & 0xFFFF) != 0) {
            failures.add("AFTER_WORLD's red corner is 0x" + Integer.toHexString(red) + ": not darkened by the composite's vignette");
        }
        if ((green & 0xFFFFFF) != 0x00FF00) {
            failures.add("AFTER_COMPOSITE's green is 0x" + Integer.toHexString(green) + ", not exact: the composite ran after it");
        }
        if ((blue & 0xFFFFFF) != 0x0000FF) {
            failures.add("order 1's blue is 0x" + Integer.toHexString(blue) + " where it overlaps order 0's green");
        }
        int changed = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (plain.getRGB(x, y) != after.getRGB(x, y)) changed++;
        if (changed > 0) failures.add("closing the registrations left " + changed + " pixels changed");
        report(failures, GlErrorChecker.checkAndLog("post-effects"));
    }

    /** The wall, through both world stages, captured. */
    private BufferedImage draw(HarnessContext ctx, CgWorldRenderer world, String name) {
        HarnessFboHelper target = HarnessFboHelper.create(W, H, true);
        target.bind();
        target.clear(0.25f, 0.3f, 0.4f, 1f);
        CgMesh cube = CgMeshShapes.cube();
        world.draw(cube, wall).at(0, 0, -5).transform(new Matrix4f().scale(6f, 6f, 0.2f)).submit();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) W / H, 0.05f, 100f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(0, 0, 0, new Matrix4f(), projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        target.captureToFile(ctx.getOutputDir(), name + ".png");
        target.unbind();
        target.delete();
        try {
            return ImageIO.read(new File(ctx.getOutputDir(), name + ".png"));
        } catch (IOException e) {
            throw new IllegalStateException("capture " + name + " could not be read", e);
        }
    }

    private static void report(List<String> failures, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[post-effects] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[post-effects] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if ("recording".equals(on)) {
            System.out.println("[post-effects] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("[post-effects] FAIL on " + on + ": " + f);
        } else {
            System.out.println("[post-effects] PASS on " + on + ": an effect at each point runs in order around the "
                    + "composite, orders hold within a point, and closing them restores the picture");
        }
    }

    @Override
    public void dispose() {
    }
}
