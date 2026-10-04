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
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The gate for the distortion pass (render-distortion D1, D2): a backdrop whose colour is each pixel's own coordinates,
 * a nearer wall over the left, and quads bending it by whole pixels, checked pixel by pixel against where each should
 * have sampled, worked out here: one quad's offset, two adding where they overlap, one behind the wall writing nothing
 * where the wall hides it, one mirrored at the right border, one whose samples into the wall are refused, one split
 * into red and blue; a solid quad before the apply bent with the scene, one marked {@code afterDistortion()} not.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=distortion"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=distortion --device=vulkan" -Dcrystalgraphics.vulkan.syncValidation=true
 * </pre>
 *
 * <p>Pixels within one of an edge are not checked: which side of an edge a pixel lands is the rasteriser's.</p>
 */
public class CgDistortionTestScene implements HarnessSceneLifecycle {

    private static final int W = 256, H = 192;
    private static final float TAN = (float) Math.tan(Math.toRadians(30)), ASPECT = (float) W / H;
    private static final int WALL_X = 60;
    private static final int[] YELLOW = {255, 255, 0}, MAGENTA = {255, 0, 255}, BLUE = {0, 0, 255};

    /** A screen rect in pixels from the bottom left, at an eye depth, and what it bends by. */
    private record Quad(int x0, int y0, int x1, int y1, float depth, float ox, float oy, float split) {
        boolean covers(int x, int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }

        boolean nearEdge(int x, int y) {
            boolean inY = y >= y0 - 1 && y <= y1, inX = x >= x0 - 1 && x <= x1;
            return inY && (Math.abs(x - x0) <= 1 || Math.abs(x - x1) <= 1) || inX && (Math.abs(y - y0) <= 1 || Math.abs(y - y1) <= 1);
        }
    }

    private static final Quad[] BENDS = {
            new Quad(90, 100, 150, 150, 3f, 4f, 0f, 0f),     // Q1
            new Quad(130, 120, 170, 170, 3f, 0f, 2f, 0f),    // Q2, adding to Q1 where they overlap
            new Quad(40, 20, 90, 60, 7f, 5f, 0f, 0f),        // behind the wall: nothing where it hides it
            new Quad(230, 20, 280, 60, 3f, 6f, 0f, 0f),      // mirrored at the right border
            new Quad(60, 70, 100, 90, 3f, -6f, 0f, 0f),      // samples into the wall refused
            new Quad(160, 20, 200, 60, 3f, 2f, 0f, 0.5f),    // split: red 3 pixels, blue 1
    };
    private static final Quad SOLID_BENT = new Quad(110, 105, 120, 115, 2.5f, 0, 0, 0);
    private static final Quad SOLID_SHARP = new Quad(130, 105, 140, 115, 2.5f, 0, 0, 0);

    private CgMaterial backdrop, wall;
    private final List<CgMaterial> quads = new ArrayList<>();

    @Override
    public void init(HarnessContext ctx) {
        backdrop = CgMaterial.newInstance("assets/harness/shader/distortion_backdrop.shader");
        wall = CgMaterial.newInstance("assets/harness/shader/distortion_backdrop.shader");
        wall.enableKeyword("WALL");
    }

    private CgMaterial quad(float r, float g, float b, float a, float ox, float oy, float split) {
        CgMaterial m = CgMaterial.newInstance("assets/harness/shader/distortion_quad.shader");
        m.applyProperties(p -> {
            p.vec4("_Color", r, g, b, a);
            p.vec4("_Offset", ox, oy, split, 0f);
        });
        quads.add(m);
        return m;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        HarnessFboHelper target = HarnessFboHelper.create(W, H, true);
        target.bind();
        target.clear(0f, 0f, 0f, 1f);
        CgMesh slab = CgMeshShapes.cube();
        submit(world.draw(slab, backdrop), new Quad(-20, -20, W + 20, H + 20, 10f, 0, 0, 0));
        submit(world.draw(slab, wall), new Quad(-20, -20, WALL_X, H + 20, 5f, 0, 0, 0));
        for (Quad q : BENDS) submit(world.draw(slab, quad(0, 0, 0, 0, q.ox, q.oy, q.split)), q);
        submit(world.draw(slab, quad(1, 1, 0, 1, 0, 0, 0)), SOLID_BENT);
        submit(world.draw(slab, quad(1, 0, 1, 1, 0, 0, 0)).afterDistortion(), SOLID_SHARP);

        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), ASPECT, 0.05f, 100f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(0, 0, 0, new Matrix4f(), projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        target.captureToFile(ctx.getOutputDir(), "distortion.png");
        target.unbind();
        target.delete();
        report(check(ctx));
    }

    /** A slab covering {@code q}'s pixels at its depth, facing the camera at the origin. */
    private static void submit(CgWorldRenderer.Draw draw, Quad q) {
        float d = q.depth;
        float x0 = (q.x0 / (float) W * 2 - 1) * d * TAN * ASPECT, x1 = (q.x1 / (float) W * 2 - 1) * d * TAN * ASPECT;
        float y0 = (q.y0 / (float) H * 2 - 1) * d * TAN, y1 = (q.y1 / (float) H * 2 - 1) * d * TAN;
        draw.at((x0 + x1) * 0.5, (y0 + y1) * 0.5, -d).transform(new Matrix4f().scale(x1 - x0, y1 - y0, 0.001f)).submit();
    }

    private static String check(HarnessContext ctx) {
        BufferedImage image;
        try {
            image = ImageIO.read(new File(ctx.getOutputDir(), "distortion.png"));
        } catch (IOException e) {
            return "the capture could not be read: " + e;
        }
        int checked = 0, wrong = 0, bent = 0;
        String first = null;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int[] want = expected(x, y);
                if (want == null) continue;
                checked++;
                if (!Arrays.equals(want, scene(x, y))) bent++;
                int rgb = image.getRGB(x, H - 1 - y);
                int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                if (Math.abs(r - want[0]) > 1 || Math.abs(g - want[1]) > 1 || Math.abs(b - want[2]) > 1) {
                    if (wrong++ == 0) {
                        first = "(" + x + ", " + y + ") is " + r + ", " + g + ", " + b + "; expected " + want[0] + ", "
                                + want[1] + ", " + want[2];
                    }
                }
            }
        }
        System.out.println("[distortion]   checked " + checked + " pixels, " + bent + " of them not as the scene was");
        return wrong == 0 ? null : wrong + " of " + checked + " pixels wrong, first " + first;
    }

    /** What pixel (x, y) should hold, from the bottom left; null where it sits too near an edge to say. */
    private static int[] expected(int x, int y) {
        if (Math.abs(x - WALL_X) <= 1 || SOLID_BENT.nearEdge(x, y) || SOLID_SHARP.nearEdge(x, y)) return null;
        float ox = 0, oy = 0, split = 0;
        for (Quad q : BENDS) {
            if (q.nearEdge(x, y)) return null;
            if (!q.covers(x, y) || (q.depth > 5f && x < WALL_X)) continue;
            ox += q.ox;
            oy += q.oy;
            split = Math.max(split, q.split);
        }
        if (SOLID_SHARP.covers(x, y)) return MAGENTA;
        if (ox == 0 && oy == 0) return scene(x, y);
        int sx = mirror(Math.round(x + ox), W), sy = mirror(Math.round(y + oy), H);
        int rx = mirror(Math.round(x + ox * (1 + split)), W), bx = mirror(Math.round(x + ox * (1 - split)), W);
        for (int px : new int[]{sx, rx, bx}) {
            if (Math.abs(px - WALL_X) <= 1 || SOLID_BENT.nearEdge(px, sy)) return null;
        }
        if (sx < WALL_X && x >= WALL_X) return scene(x, y);   // nearer than the pixel: refused
        return new int[]{scene(rx, sy)[0], scene(sx, sy)[1], scene(bx, sy)[2]};
    }

    /** The scene before the apply: the wall, the solid quad drawn with it, else the backdrop's coordinates. */
    private static int[] scene(int x, int y) {
        if (x < WALL_X) return BLUE;
        if (SOLID_BENT.covers(x, y)) return YELLOW;
        return new int[]{x, y, x};
    }

    private static int mirror(int p, int size) {
        if (p < 0) return -1 - p;
        return p >= size ? 2 * size - 1 - p : p;
    }

    private static void report(String failure) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (GlErrorChecker.checkAndLog("distortion")) failure = "GL errors, logged above";
        else if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        else if ("recording".equals(on)) failure = null;
        System.out.println(failure == null ? "[distortion] PASS on " + on : "[distortion] FAIL on " + on + ": " + failure);
    }

    @Override
    public void dispose() {
    }
}
