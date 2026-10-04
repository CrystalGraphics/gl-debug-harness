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
 * must. Each at every quality tier (each its own chain), three target sizes, one odd and one tall enough to halve the emission first, and at emission scales 1, 0.5 and the
 * tier's own (0), since the Emissive pass reads the scene's depth by its own pixel's share of the screen.
 *
 * <p>Prints {@code [bloom-occlusion] PASS} or {@code FAIL}; on Vulkan a validation error fails it. Captures land in its
 * output directory as {@code <tier>-<w>x<h>-<scale>-<hidden|front>-<off|on>.png}.</p>
 */
public class CgBloomOcclusionTestScene implements HarnessSceneLifecycle {

    /** The tall one makes the chain halve its emission first: twice at scale 1, once at 0.5. */
    private static final int[][] SIZES = {{320, 240}, {321, 241}, {640, 2100}};
    private static final float[] SCALES = {0f, 1f, 0.5f};
    /** How many pixels the ball in front must brighten: its halo, past its own silhouette. */
    private static final int FRONT_MIN_CHANGED = 50;

    private CgMaterial wall, glow, emissionOnly;

    @Override
    public void init(HarnessContext ctx) {
        wall = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
        glow = CgMaterial.load("assets/harness/shader/bloom_glow.shader");
        emissionOnly = CgMaterial.newInstance("crystalgraphics:shaders/emission_only.shader");
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
                for (float scale : SCALES) {
                    world.emissionScale(scale);
                    for (boolean hidden : new boolean[]{true, false}) {
                        String name = tier.name().toLowerCase() + "-" + size[0] + "x" + size[1] + "-" + scale + "-"
                                + (hidden ? "hidden" : "front");
                        draw(ctx, world, size[0], size[1], glow, hidden, 1f, 0f, name + "-off.png");
                        draw(ctx, world, size[0], size[1], glow, hidden, 1f, 1f, name + "-on.png");
                        String failure = compare(ctx, name, hidden);
                        if (failure != null) failures.add(name + ": " + failure);
                    }
                }
            }
        }
        CgGraphicsSettings.QUALITY.set(original);
        world.emissionScale(0f);
        // .emission(0) takes a ball in front out of the bloom
        draw(ctx, world, 320, 240, glow, false, 0f, 0f, "emission0-off.png");
        draw(ctx, world, 320, 240, glow, false, 0f, 1f, "emission0-on.png");
        String failure = compare(ctx, "emission0", true);
        if (failure != null) failures.add("emission(0): " + failure);
        // An emission-only ball draws nothing into the scene, and blooms
        draw(ctx, world, 320, 240, null, false, 1f, 0f, "emission-only-scene-off.png");
        draw(ctx, world, 320, 240, emissionOnly, false, 1f, 0f, "emission-only-scene-on.png");
        failure = compare(ctx, "emission-only-scene", true);
        if (failure != null) failures.add("emission-only, bloom off: " + failure);
        draw(ctx, world, 320, 240, emissionOnly, false, 1f, 0f, "emission-only-off.png");
        draw(ctx, world, 320, 240, emissionOnly, false, 1f, 1f, "emission-only-on.png");
        failure = compare(ctx, "emission-only", false);
        if (failure != null) failures.add("emission-only: " + failure);
        CgPostStack.get().bloom().intensity(1f);
        world.emissionScale(0f);
        report(failures, GlErrorChecker.checkAndLog("bloom-occlusion"));
    }

    /**
     * The wall at 5 blocks and {@code ball} (none if null) at 8 behind it or 3 in front, its glow scaled by
     * {@code emission}, into a target of its own, captured.
     */
    private void draw(HarnessContext ctx, CgWorldRenderer world, int w, int h, CgMaterial ball, boolean hidden,
                      float emission, float bloom, String file) {
        HarnessFboHelper target = HarnessFboHelper.create(w, h, true);
        target.bind();
        target.clear(0.05f, 0.05f, 0.07f, 1f);
        CgPostStack.get().bloom().intensity(bloom);
        CgMesh cube = CgMeshShapes.cube(), sphere = CgMeshShapes.sphere(24, 32);
        world.draw(cube, wall).at(0, 0, -5).transform(new Matrix4f().scale(6f, 6f, 0.2f)).submit();
        if (ball != null) {
            world.draw(sphere, ball).at(0, 0, hidden ? -8 : -3).transform(new Matrix4f().scale(hidden ? 1f : 0.6f))
                    .emission(emission).submit();
        }
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) w / h, 0.05f, 100f);
        Matrix4f view = new Matrix4f();
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
            stage.fire();
        }
        CgFrameRing.endFrame();   // a draw lives one frame: the next capture starts empty
        target.captureToFile(ctx.getOutputDir(), file);
        target.unbind();
        target.delete();
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
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[bloom-occlusion] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[bloom-occlusion] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if ("recording".equals(on)) {
            System.out.println("[bloom-occlusion] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else if (!failures.isEmpty()) {
            for (String failure : failures) System.out.println("[bloom-occlusion] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[bloom-occlusion] PASS on " + on + ": a ball behind the wall blooms nowhere and one in "
                    + "front blooms, at every tier, three sizes and emission scales 1, 0.5 and the tier's; emission(0) and an emission-only ball");
        }
    }

    @Override
    public void dispose() {
    }
}
