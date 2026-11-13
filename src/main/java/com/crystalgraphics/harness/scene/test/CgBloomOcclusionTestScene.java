package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.camera.CgCamera;
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
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The world bloom's gate: an opaque ball with an Emissive pass, drawn through {@code CgWorldRenderer} and bloomed by
 * {@code CgPostStack}, once with bloom off and once on. Behind a wall, bloom must change no pixel; in front of it, it
 * must. Each at every quality tier (each its own chain) and three target sizes, one odd and one tall.
 *
 * <p>A glow is light in the HDR scene and bloom takes what passes white, so it checks too that a pane in front covers
 * a glow as it covers the colour ({@link #covered}).</p>
 *
 * <p>Prints {@code [bloom-occlusion] PASS} or {@code FAIL}; on Vulkan a validation error fails it. Captures land in its
 * output directory as {@code <tier>-<w>x<h>-<scale>-<hidden|front>-<off|on>.png}.</p>
 */
public class CgBloomOcclusionTestScene implements HarnessSceneLifecycle {

    /** The tall one makes the chain halve its emission first: twice at scale 1, once at 0.5. */
    private static final int[][] SIZES = {{320, 240}, {321, 241}, {640, 2100}};
    /** How many pixels the ball in front must brighten: its halo, past its own silhouette. */
    private static final int FRONT_MIN_CHANGED = 50;

    private CgMaterialInstance wall, glow, emissionOnly, glowAlpha, pane, translucentPane;

    @Override
    public void init(HarnessContext ctx) {
        wall = CgMaterial.load("assets/harness/shader/bloom_wall.shader").instance();
        glow = CgMaterial.load("assets/harness/shader/bloom_glow.shader").instance();
        emissionOnly = CgMaterial.load("crystalgraphics:shaders/materials/emission_only.shader").instance();
        // Its javadoc's glow: past white under the HDR scene's glow gain, as a glow meant to bloom is.
        emissionOnly.set1f("_EmissionStrength", 3f);
        glowAlpha = CgMaterial.load("assets/harness/shader/bloom_glow_alpha.shader").instance();
        pane = CgMaterial.load("assets/harness/shader/bloom_cover.shader").instance();
        translucentPane = CgMaterial.load("assets/harness/shader/bloom_cover.shader").instance();
        translucentPane.set1f("_Alpha", 0.5f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        CgPostStack.get().install();
        List<String> failures = new ArrayList<>();
        CgQuality original = CgGraphicsSettings.QUALITY.get();
        for (CgQuality tier : CgQuality.values()) {
            CgGraphicsSettings.QUALITY.set(tier);
            for (int[] size : SIZES) {
                for (boolean hidden : new boolean[]{true, false}) {
                    String name = tier.name().toLowerCase() + "-" + size[0] + "x" + size[1] + "-" + (hidden ? "hidden" : "front");
                    draw(ctx, world, size[0], size[1], glow, hidden, 1f, 0f, name + "-off.png");
                    draw(ctx, world, size[0], size[1], glow, hidden, 1f, 1f, name + "-on.png");
                    String failure = compare(ctx, name, hidden);
                    if (failure != null) failures.add(name + ": " + failure);
                }
            }
        }
        CgGraphicsSettings.QUALITY.set(original);
        // .emission(0) takes a ball in front out of the bloom
        draw(ctx, world, 320, 240, glow, false, 0f, 0f, "emission0-off.png");
        draw(ctx, world, 320, 240, glow, false, 0f, 1f, "emission0-on.png");
        String failure = compare(ctx, "emission0", true);
        if (failure != null) failures.add("emission(0): " + failure);
        // An emission-only ball's glow is in the scene, and blooms
        draw(ctx, world, 320, 240, null, false, 1f, 0f, "emission-only-scene-off.png");
        draw(ctx, world, 320, 240, emissionOnly, false, 1f, 0f, "emission-only-scene-on.png");
        failure = compare(ctx, "emission-only-scene", false);
        if (failure != null) failures.add("emission-only, bloom off: " + failure);
        draw(ctx, world, 320, 240, emissionOnly, false, 1f, 0f, "emission-only-off.png");
        draw(ctx, world, 320, 240, emissionOnly, false, 1f, 1f, "emission-only-on.png");
        failure = compare(ctx, "emission-only", false);
        if (failure != null) failures.add("emission-only: " + failure);
        covered(ctx, world, failures);
        CgPostStack.get().bloom().intensity(1f);
        report(failures, DeviceErrors.checkAndLog("bloom-occlusion"));
    }

    /**
     * The wall at 5 blocks and {@code ball} (none if null) at 8 behind it or 3 in front, its glow scaled by
     * {@code emission}, into a target of its own, captured.
     */
    private void draw(HarnessContext ctx, CgWorldRenderer world, int w, int h, CgMaterialInstance ball, boolean hidden,
                      float emission, float bloom, String file) {
        draw(ctx, world, w, h, ball, hidden, emission, bloom, null, file);
    }

    /** As {@link #draw}, with {@code cover} (none if null) a pane at 2 blocks, in front of a ball in front. */
    private void draw(HarnessContext ctx, CgWorldRenderer world, int w, int h, CgMaterialInstance ball, boolean hidden,
                      float emission, float bloom, CgMaterialInterface cover, String file) {
        HarnessTarget target = HarnessTarget.create(w, h, true);
        drawInto(ctx, world, target, w, h, ball, hidden, emission, bloom, cover, file);
        target.unbind();
        target.delete();
    }

    /** As {@link #draw}, into {@code target}, the stages told the host's size is {@code w} x {@code h}. */
    private void drawInto(HarnessContext ctx, CgWorldRenderer world, HarnessTarget target, int w, int h, CgMaterialInstance ball,
                          boolean hidden, float emission, float bloom, CgMaterialInterface cover, String file) {
        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        CgPostStack.get().bloom().intensity(bloom);
        CgMesh cube = CgMeshShapes.cube(), sphere = CgMeshShapes.sphere(24, 32);
        world.draw(cube, wall).at(0, 0, -5).transform(new Matrix4f().scale(6f, 6f, 0.2f)).submit();
        if (ball != null) {
            world.draw(sphere, ball).at(0, 0, hidden ? -8 : -3).transform(new Matrix4f().scale(hidden ? 1f : 0.6f))
                    .emission(emission).submit();
        }
        if (cover != null) world.draw(cube, cover).at(0, 0, -2).transform(new Matrix4f().scale(2f, 2f, 0.05f)).submit();
        Matrix4f projection = CgCamera.perspectiveMatrix(new Matrix4f(), 60f, (float) w / h, 0.05f, 100f);
        Matrix4f view = new Matrix4f();
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, w, h, target.external()).camera().set(0, 0, 0, view, projection);
            stage.fire();
        }
        CgFrameRing.endFrame();   // a draw lives one frame: the next capture starts empty
        target.captureToFile(ctx.getOutputDir(), file);
    }

    /**
     * A glow is light in the scene, so a pane in front covers it as it covers the colour: behind an
     * opaque pane, an opaque ball's glow (added before the transparent pass) and a transparent ball's (added after its
     * own draw) leave the picture the pane alone makes, bloom on, byte for byte. Behind a translucent pane the halo
     * beside the ball blooms less than bare, or not at all once the pane takes the glow under white.
     */
    private void covered(HarnessContext ctx, CgWorldRenderer world, List<String> failures) {
        draw(ctx, world, 321, 241, null, false, 1f, 1f, pane, "cover-pane.png");
        for (CgMaterialInstance ball : List.of(glow, glowAlpha)) {
            String name = "cover-" + (ball == glow ? "opaque" : "transparent");
            draw(ctx, world, 321, 241, ball, false, 1f, 1f, pane, name + ".png");
            String failure = same(ctx, "cover-pane.png", name + ".png");
            if (failure != null) failures.add(name + ": " + failure);
        }
        for (boolean behind : new boolean[]{false, true}) {
            String name = behind ? "cover-translucent" : "cover-bare";
            draw(ctx, world, 321, 241, glow, false, 1f, 0f, behind ? translucentPane : null, name + "-off.png");
            draw(ctx, world, 321, 241, glow, false, 1f, 1f, behind ? translucentPane : null, name + "-on.png");
        }
        // Beside the ball's silhouette (about 42 px across at 3 blocks), inside the pane's (about 104)
        int x = 321 / 2 + 60, y = 241 / 2, bare = brightening(ctx, "cover-bare", x, y), dimmed = brightening(ctx, "cover-translucent", x, y);
        if (bare <= 0 || dimmed >= bare) {
            failures.add("cover-translucent: bloom brightened (" + x + ", " + y + ") by " + dimmed + " behind a translucent pane and "
                    + bare + " bare, where it should bloom bare and less behind");
        }
    }

    /** How much bloom brightened the pixel at {@code (x, y)} of {@code name}'s captures, its channels summed; -1 unread. */
    private static int brightening(HarnessContext ctx, String name, int x, int y) {
        try {
            int off = ImageIO.read(new File(ctx.getOutputDir(), name + "-off.png")).getRGB(x, y);
            int on = ImageIO.read(new File(ctx.getOutputDir(), name + "-on.png")).getRGB(x, y);
            return channelSum(on) - channelSum(off);
        } catch (IOException e) {
            return -1;
        }
    }

    private static int channelSum(int argb) {
        return (argb >> 16 & 0xff) + (argb >> 8 & 0xff) + (argb & 0xff);
    }

    /** Null when two captures are the same byte for byte; else the first pixel that differs. */
    private static String same(HarnessContext ctx, String a, String b) {
        BufferedImage first, second;
        try {
            first = ImageIO.read(new File(ctx.getOutputDir(), a));
            second = ImageIO.read(new File(ctx.getOutputDir(), b));
        } catch (IOException e) {
            return "a capture could not be read: " + e;
        }
        int differ = 0, firstX = -1, firstY = -1;
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y) && differ++ == 0) {
                    firstX = x;
                    firstY = y;
                }
            }
        }
        if (differ == 0) return null;
        return a + " and " + b + " differ at " + differ + " pixels, first at (" + firstX + ", " + firstY + "): 0x"
                + Integer.toHexString(first.getRGB(firstX, firstY)) + " and 0x" + Integer.toHexString(second.getRGB(firstX, firstY));
    }

    /** Null when bloom left a hidden ball's picture alone or brightened a ball in front; else what went wrong. */
    private static String compare(HarnessContext ctx, String name, boolean hidden) {
        BufferedImage off, on;
        try {
            off = ImageIO.read(new File(ctx.getOutputDir(), name + "-off.png"));
            on = ImageIO.read(new File(ctx.getOutputDir(), name + "-on.png"));
        } catch (IOException e) {
            return "a capture could not be read: " + e;
        }
        int changed = 0, firstX = -1, firstY = -1;
        for (int y = 0; y < off.getHeight(); y++) {
            for (int x = 0; x < off.getWidth(); x++) {
                if (off.getRGB(x, y) != on.getRGB(x, y)) {
                    if (changed++ == 0) {
                        firstX = x;
                        firstY = y;
                    }
                }
            }
        }
        if (hidden && changed > 0) {
            return "bloom changed " + changed + " pixels of a ball behind the wall, first at (" + firstX + ", " + firstY
                    + "): 0x" + Integer.toHexString(off.getRGB(firstX, firstY)) + " became 0x"
                    + Integer.toHexString(on.getRGB(firstX, firstY));
        }
        if (!hidden && changed < FRONT_MIN_CHANGED) {
            return "bloom changed " + changed + " pixels of a ball in front of the wall, where its halo should change at"
                    + " least " + FRONT_MIN_CHANGED;
        }
        return null;
    }

    private static void report(List<String> failures, boolean glErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[bloom-occlusion] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[bloom-occlusion] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[bloom-occlusion] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[bloom-occlusion] PASS on " + on + ": a ball behind the wall blooms nowhere"
                    + " and one in front blooms, at every tier and three sizes; emission(0), an emission-only ball's glow in the"
                    + " scene, an opaque pane hiding an opaque and a transparent glow byte for byte, and a translucent one dimming the bloom");
        }
    }

    @Override
    public void dispose() {
    }
}
