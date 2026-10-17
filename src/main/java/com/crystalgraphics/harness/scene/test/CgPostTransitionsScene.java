package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * G0.10's gate, the post stack's transitions, each firing a cleared target checked at its middle:
 * <ul>
 *   <li>a post material ({@link CgPostMaterial}) inverting the picture at weights 1, 0.5 and 0;</li>
 *   <li>a fade ({@link CgPostSettings#fade}) halfway to red, with the HDR scene off (the composite's blend form) and on
 *       (its copy form);</li>
 *   <li>a crossfade ({@link CgPostStack#crossfade}) of 1 s from one colour into another: three quarters the old a
 *       quarter of the way in, the new alone after.</li>
 * </ul>
 *
 * <p>Prints {@code [post-transitions] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public class CgPostTransitionsScene implements HarnessSceneLifecycle {

    private static final int SIZE = 64, TOLERANCE = 4;
    private static final float[] A = {0.2f, 0.4f, 0.6f}, B = {0.9f, 0.1f, 0.1f};

    private HarnessFboHelper target;
    private HarnessContext ctx;
    private String failure;
    private int firing;

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        this.ctx = ctx;
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        CgPostStack.get().install();
        boolean hdr = world.hdrScene();
        target = HarnessFboHelper.create(SIZE, SIZE, false);
        try {
            CgPostMaterial invert = CgPostMaterial.of("harness:shader/post_invert.shader", CgPostPoint.AFTER_COMPOSITE);
            CgRenderStage.Registration on = CgPostStack.get().add(invert);
            check("a post material at weight 1", fire(A, 0f), 1f - A[0], 1f - A[1], 1f - A[2]);
            invert.weight(0.5f);
            check("a post material at weight 0.5", fire(A, 0f), 0.5f, 0.5f, 0.5f);
            invert.weight(0f);
            check("a post material at weight 0", fire(A, 0f), A[0], A[1], A[2]);
            on.close();

            CgPostVolume red = CgPostStack.get().volume(100, new CgPostSettings().fade(0xFFFF0000, 0.5f));
            world.hdrScene(false);
            check("a fade, blend form", fire(A, 0f), mix(A[0], 1f), mix(A[1], 0f), mix(A[2], 0f));
            world.hdrScene(true);
            check("a fade, copy form", fire(A, 0f), mix(A[0], 1f), mix(A[1], 0f), mix(A[2], 0f));
            red.close();
            world.hdrScene(hdr);

            CgPostStack.get().crossfade(1f);
            fire(A, 10f);
            float[] at = fire(B, 10.25f);
            check("a crossfade a quarter in", at, B[0] + (A[0] - B[0]) * 0.75f, B[1] + (A[1] - B[1]) * 0.75f,
                    B[2] + (A[2] - B[2]) * 0.75f);
            check("a crossfade done", fire(B, 11.5f), B[0], B[1], B[2]);
        } finally {
            world.hdrScene(hdr);
            target.delete();
        }
        report(GlErrorChecker.checkAndLog("post-transitions"));
    }

    private static float mix(float a, float b) {
        return a + (b - a) * 0.5f;
    }

    /** One firing of the world stages at {@code seconds} on a target cleared to {@code clear}; its middle pixel. */
    private float[] fire(float[] clear, float seconds) {
        CgFrameClock.set(seconds);
        target.bind();
        target.clear(clear[0], clear[1], clear[2], 1f);
        Matrix4f view = new Matrix4f(), projection = new Matrix4f().setOrtho(0, SIZE, 0, SIZE, -1, 1);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, SIZE, SIZE, target.getFboId()).view().set(0, 0, 0, view, projection);
            stage.fire();
        }
        String name = "post-transitions-" + (firing++) + ".png";
        target.captureToFile(ctx.getOutputDir(), name);
        target.unbind();
        try {
            BufferedImage picture = ImageIO.read(new File(ctx.getOutputDir(), name));
            int rgb = picture.getRGB(SIZE / 2, SIZE / 2);
            return new float[]{(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
        } catch (IOException e) {
            failure = "the capture could not be read: " + e;
            return new float[3];
        }
    }

    private void check(String what, float[] got, float r, float g, float b) {
        if (failure != null) return;
        float[] due = {r, g, b};
        for (int c = 0; c < 3; c++) {
            if (Math.abs(got[c] - due[c]) * 255f > TOLERANCE) {
                failure = what + " reads (" + bytes(got) + "), where (" + bytes(due) + ") is due";
                return;
            }
        }
    }

    private static String bytes(float[] c) {
        return Math.round(c[0] * 255) + ", " + Math.round(c[1] * 255) + ", " + Math.round(c[2] * 255);
    }

    private void report(boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) failure = "GL errors, logged above";
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        if (failure == null && "recording".equals(on)) {
            System.out.println("[post-transitions] PASS on recording: every command validated; nothing drawn to compare");
        } else if (failure != null) {
            System.out.println("[post-transitions] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[post-transitions] PASS on " + on + ": a post material by its weight, a fade in either "
                    + "composite form, and a cut's held frame crossfaded out");
        }
    }

    @Override
    public void dispose() {
    }
}
