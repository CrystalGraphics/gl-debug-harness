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
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.look.CgColorGrade;
import com.crystalgraphics.render.post.look.CgFilmLut;
import com.crystalgraphics.render.post.volume.CgImpact;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostSettings.Control;
import com.crystalgraphics.render.post.volume.CgPostSettings.Region;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The gate for the post stack's volumes and looks (render-post-process P7): bloom-occlusion's wall and glowing ball,
 * drawn once plain and once per look, each look set by a {@link CgPostVolume}. Checked from the pixels: a volume at
 * weight 0, one out of reach and a flash with the player's flashes at 0 change nothing; a flash brightens, a vignette
 * darkens the corners, an inverted impact frame is the negative, chromatic aberration moves edges, focus lines darken,
 * a drawn frame fills the glowing ball and not the wall; a volume of higher priority overrides a lower one exactly; and
 * Unreal 4.21's tonemapper draws each pixel as the CPU's bake of its table reads it, Unreal's colour grading each as
 * the CPU grades it, and a neutral grade changes nothing.
 *
 * <p>Prints {@code [post-looks] PASS} or {@code FAIL}; on Vulkan a validation error fails it. Captures land in its
 * output directory by look.</p>
 */
public class CgPostLooksTestScene implements HarnessSceneLifecycle {

    private static final int W = 320, H = 240;

    private CgMaterial wall, glow;

    @Override
    public void init(HarnessContext ctx) {
        wall = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        glow = CgMaterial.load("assets/harness/shader/bloom_glow.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        CgPostStack post = CgPostStack.get();
        post.install();
        post.bloom().intensity(1f);
        List<String> failures = new ArrayList<>();
        float flashes = CgGraphicsSettings.FLASHES.get();
        CgGraphicsSettings.FLASHES.set(1f);

        BufferedImage base = draw(ctx, world, "base", v -> { });
        same(failures, "a volume at weight 0", base,
                draw(ctx, world, "weight0", v -> v.add(post.volume(0, new CgPostSettings().flash(1f)).weight(0f))));
        same(failures, "a volume out of reach", base, draw(ctx, world, "far",
                v -> v.add(post.volume(0, new CgPostSettings().flash(1f)).at(0, 0, 100).radius(5f).blend(5f))));
        CgGraphicsSettings.FLASHES.set(0f);
        same(failures, "a flash with flashes at 0", base,
                draw(ctx, world, "flashes0", v -> v.add(post.volume(0, new CgPostSettings().flash(1f)))));
        CgGraphicsSettings.FLASHES.set(1f);

        BufferedImage flash = draw(ctx, world, "flash", v -> v.add(post.volume(0, new CgPostSettings().flash(1f))));
        if (!(mean(flash, 0, 0, W, H) > mean(base, 0, 0, W, H) * 1.25f)) {
            failures.add("a one-stop flash: mean " + mean(flash, 0, 0, W, H) + " against " + mean(base, 0, 0, W, H));
        }
        BufferedImage vignette = draw(ctx, world, "vignette", v -> v.add(post.volume(0, new CgPostSettings().vignette(1f))));
        if (!(mean(vignette, 0, 0, 24, 24) < mean(base, 0, 0, 24, 24) * 0.7f)) {
            failures.add("a full vignette: corner mean " + mean(vignette, 0, 0, 24, 24) + " against " + mean(base, 0, 0, 24, 24));
        }
        same(failures, "a higher-priority vignette over a lower", vignette, draw(ctx, world, "priority", v -> {
            v.add(post.volume(0, new CgPostSettings().vignette(0.2f)));
            v.add(post.volume(5, new CgPostSettings().vignette(1f)));
        }));
        BufferedImage invert = draw(ctx, world, "invert", v -> v.add(post.volume(0, new CgPostSettings().impact(CgImpact.INVERT, 1f))));
        float negative = 255f - mean(base, 0, 0, W, H);
        if (Math.abs(mean(invert, 0, 0, W, H) - negative) > 8f) {
            failures.add("an inverted impact frame: mean " + mean(invert, 0, 0, W, H) + " against " + negative);
        }
        BufferedImage chromatic = draw(ctx, world, "chromatic", v -> v.add(post.volume(0, new CgPostSettings().chromatic(5f))));   // Unreal's percent, its usual most
        BufferedImage linear = draw(ctx, world, "copy-form", v -> v.add(post.volume(0, new CgPostSettings().vignette(0.0001f))));
        if (changed(chromatic, linear) < 50) failures.add("chromatic aberration moved " + changed(chromatic, linear) + " pixels");
        // Unreal 4.21's tonemapper: each pixel as the CPU's bake of the same table reads it from the untoned picture.
        CgPostSettings stage = new CgPostSettings().tonemap().filmHeal(0.18f);
        BufferedImage toned = draw(ctx, world, "tonemap", v -> v.add(post.volume(0, stage)));
        float[] table = new float[CgFilmLut.CELLS * 3];
        CgFilmLut.bake(stage, table);
        toneWorst = toneError(linear, toned, table);
        if (toneWorst > 3) failures.add("the tonemapper: a pixel " + toneWorst + " codes from the CPU's table");
        // Unreal's colour grading: each pixel as the CPU grades the untoned picture, then tinted.
        CgPostSettings cold = new CgPostSettings().grade(Region.SHADOWS, Control.GAIN, 0.8f, 0.9f, 1.2f, 1f)
                .grade(Region.GLOBAL, Control.SATURATION, 1f, 1f, 1f, 0.7f).grade(Region.HIGHLIGHTS, Control.GAIN, 1f, 1f, 1f, 0.9f)
                .grade(Region.MIDTONES, Control.GAMMA, 1.1f, 1f, 0.9f, 1f).whiteBalance(5500f, 0f).tint(1f, 0.95f, 0.9f);
        BufferedImage graded = draw(ctx, world, "grade", v -> v.add(post.volume(0, cold)));
        double[] grade = new double[CgColorGrade.PACKED];
        CgColorGrade.pack(cold, grade);
        gradeWorst = gradeError(linear, graded, grade, new double[] {cold.tintR(), cold.tintG(), cold.tintB()});
        if (gradeWorst > 3) failures.add("the grade: a pixel " + gradeWorst + " codes from the CPU's");
        same(failures, "a neutral grade", base, draw(ctx, world, "grade-neutral", v -> v.add(post.volume(0,
                new CgPostSettings().grade(Region.SHADOWS, Control.GAIN, 1f, 1f, 1f, 1f).whiteBalance(6500f, 0f).tint(1f, 1f, 1f)))));
        BufferedImage lines = draw(ctx, world, "lines", v -> v.add(post.volume(0, new CgPostSettings().impact(CgImpact.LINES, 1f))));
        if (!(mean(lines, 0, 0, W, H) < mean(base, 0, 0, W, H) * 0.95f)) {
            failures.add("focus lines: mean " + mean(lines, 0, 0, W, H) + " against " + mean(base, 0, 0, W, H));
        }
        // Drawn frames: the subject is what glows (the ball's Emissive pass), not the lit wall.
        BufferedImage subject = draw(ctx, world, "subject", v -> v.add(post.volume(0, new CgPostSettings().impact(CgImpact.SUBJECT, 1f))));
        if (!(mean(subject, W / 2 - 4, H / 2 - 4, 8, 8) > 200f && mean(subject, 0, 0, 24, 24) < 30f)) {
            failures.add("a subject frame: ball " + mean(subject, W / 2 - 4, H / 2 - 4, 8, 8) + ", corner " + mean(subject, 0, 0, 24, 24));
        }
        BufferedImage focus = draw(ctx, world, "focus-lines", v -> v.add(post.volume(0, new CgPostSettings().impact(CgImpact.FOCUS_LINES, 1f))));
        float rim = mean(focus, 0, 0, W, 24);
        if (!(mean(focus, W / 2 - 4, H / 2 - 4, 8, 8) > 200f && rim > 60f && rim < 245f)) {
            failures.add("focus lines on white: ball " + mean(focus, W / 2 - 4, H / 2 - 4, 8, 8) + ", top rows " + rim);
        }

        CgGraphicsSettings.FLASHES.set(flashes);
        report(failures, GlErrorChecker.checkAndLog("post-looks"));
    }

    /** The wall and the ball in front of it under the volumes {@code setup} opens, captured; then the volumes closed. */
    private BufferedImage draw(HarnessContext ctx, CgWorldRenderer world, String name, Consumer<List<CgPostVolume>> setup) {
        List<CgPostVolume> open = new ArrayList<>();
        setup.accept(open);
        HarnessFboHelper target = HarnessFboHelper.create(W, H, true);
        target.bind();
        target.clear(0.25f, 0.3f, 0.4f, 1f);
        CgMesh cube = CgMeshShapes.cube(), ball = CgMeshShapes.sphere(24, 32);
        world.draw(cube, wall).at(0, 0, -5).transform(new Matrix4f().scale(6f, 6f, 0.2f)).submit();
        world.draw(ball, glow).at(0, 0, -3).transform(new Matrix4f().scale(0.6f)).submit();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) W / H, 0.05f, 100f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(0, 0, 0, new Matrix4f(), projection);
            stage.fire();
        }
        CgFrameRing.endFrame();
        target.captureToFile(ctx.getOutputDir(), name + ".png");
        target.unbind();
        target.delete();
        for (CgPostVolume v : open) v.close();
        try {
            return ImageIO.read(new File(ctx.getOutputDir(), name + ".png"));
        } catch (IOException e) {
            throw new IllegalStateException("capture " + name + " could not be read", e);
        }
    }

    private static void same(List<String> failures, String what, BufferedImage a, BufferedImage b) {
        int n = changed(a, b);
        if (n > 0) failures.add(what + " changed " + n + " pixels");
    }

    /** The grade's worst pixel against the CPU's, in codes: for the verdict. */
    private static double gradeWorst;

    /**
     * The most any of every 7th pixel of {@code graded} is from {@code plain}'s decoded through the packed grade and its
     * tint, in codes. A pixel near white, or graded past it, is skipped: the roll to white is not the grade's.
     */
    private static double gradeError(BufferedImage plain, BufferedImage graded, double[] grade, double[] tint) {
        double worst = 0;
        double[] in = new double[3], out = new double[3];
        for (int y = 0; y < plain.getHeight(); y += 7) {
            for (int x = 0; x < plain.getWidth(); x += 7) {
                int p = plain.getRGB(x, y), t = graded.getRGB(x, y);
                if ((p >> 16 & 0xFF) > 245 || (p >> 8 & 0xFF) > 245 || (p & 0xFF) > 245) continue;
                in[0] = decode(p >> 16 & 0xFF);
                in[1] = decode(p >> 8 & 0xFF);
                in[2] = decode(p & 0xFF);
                CgColorGrade.apply(grade, in, out);
                if (Math.max(out[0], Math.max(out[1], out[2])) > 1) continue;
                for (int c = 0; c < 3; c++) {
                    double want = encode(Math.max(0, out[c]) * tint[c]) * 255;
                    worst = Math.max(worst, Math.abs((t >> (16 - 8 * c) & 0xFF) - want));
                }
            }
        }
        return worst;
    }

    private static double encode(double c) {
        return c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    /** The tonemapper's worst pixel against the CPU's table, in codes: for the verdict. */
    private static double toneWorst;

    /**
     * The most any of every 7th pixel of {@code toned} is from {@code plain}'s decoded through {@code table}, in codes.
     * A pixel near white is skipped: under the HDR scene, its untoned value was rolled off from past white.
     */
    private static double toneError(BufferedImage plain, BufferedImage toned, float[] table) {
        double worst = 0;
        double[] want = new double[3];
        for (int y = 0; y < plain.getHeight(); y += 7) {
            for (int x = 0; x < plain.getWidth(); x += 7) {
                int p = plain.getRGB(x, y), t = toned.getRGB(x, y);
                if ((p >> 16 & 0xFF) > 245 || (p >> 8 & 0xFF) > 245 || (p & 0xFF) > 245) continue;
                CgFilmLut.tonemap(table, decode(p >> 16 & 0xFF), decode(p >> 8 & 0xFF), decode(p & 0xFF), want);
                worst = Math.max(worst, Math.abs((t >> 16 & 0xFF) - want[0] * 255));
                worst = Math.max(worst, Math.abs((t >> 8 & 0xFF) - want[1] * 255));
                worst = Math.max(worst, Math.abs((t & 0xFF) - want[2] * 255));
            }
        }
        return worst;
    }

    private static double decode(int code) {
        double c = code / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int changed(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }

    /** Mean of r, g and b over a box, top-left. */
    private static float mean(BufferedImage image, int x0, int y0, int w, int h) {
        long sum = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int p = image.getRGB(x, y);
                sum += ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
            }
        }
        return sum / (3f * w * h);
    }

    private static void report(List<String> failures, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[post-looks] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[post-looks] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if ("recording".equals(on)) {
            System.out.println("[post-looks] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("[post-looks] FAIL on " + on + ": " + f);
        } else {
            System.out.println("[post-looks] PASS on " + on + ": weight 0, out of reach and flashes 0 change nothing; "
                    + "flash, vignette, invert, aberration and focus lines each show; a drawn frame's subject is the glow; "
                    + "priority overrides; the tonemapper within " + String.format("%.2f", toneWorst) + " codes of the CPU's table; "
                    + "the grade within " + String.format("%.2f", gradeWorst) + " codes of the CPU's, a neutral one exact");
        }
    }

    @Override
    public void dispose() {
    }
}
