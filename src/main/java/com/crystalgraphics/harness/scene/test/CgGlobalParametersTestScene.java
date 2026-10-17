package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.api.mesh.CgMesh;
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
import java.util.List;

/**
 * G0.6's gate, global parameters: one value of a {@link CgParameterCollection}, set once, read by two different
 * materials drawn through the world renderer, and a third drawn by the first shader ignoring it. The two readers come out
 * darkened (the second also tinted by a vector of the same collection, read in its vertex stage); the third as its
 * colour.
 *
 * <p>Prints {@code [global-parameters] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public class CgGlobalParametersTestScene implements HarnessSceneLifecycle {

    static final CgParameterCollection GATE = CgParameterCollection.of("harnessGate");
    static final CgParameterCollection.Scalar DARKEN = GATE.scalar("darken", 0f);
    static final CgParameterCollection.Vector TINT = GATE.vector("tint", 0xFFFFFFFF);

    private static final int SIZE = 48, MARGIN = 8, TOLERANCE = 3;
    private static final float[] COLOUR = {0.9f, 0.6f, 0.8f};
    private static final float[] TINTED = {1f, 0.5f, 0.25f};
    private static final float DARK = 0.5f;
    private static final String[] NAMES = {"the first shader reading it", "the first shader ignoring it",
            "a second shader reading it"};

    private final CgMaterial[] materials = new CgMaterial[3];

    @Override
    public void init(HarnessContext ctx) {
        for (int i = 0; i < materials.length; i++) {
            String path = i < 2 ? "assets/harness/shader/global_parameters_a.shader"
                    : "assets/harness/shader/global_parameters_b.shader";
            float reads = i == 1 ? 0f : 1f;
            materials[i] = CgMaterial.newInstance(path);
            materials[i].applyProperties(b -> {
                b.vec4("_Color", COLOUR[0], COLOUR[1], COLOUR[2], 1f);
                if (path.endsWith("_a.shader")) b.set1f("_Reads", reads);
            });
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            DARKEN.set(DARK);                                   // keyed once, for every material reading it
            TINT.set(TINTED[0], TINTED[1], TINTED[2], 1f);            CgWorldRenderer world = CgWorldRenderer.get();
            world.install();
            for (int i = 0; i < materials.length; i++) {
                world.draw(CgMesh.quads(1), materials[i]).custom(0, MARGIN + i * (SIZE + MARGIN), MARGIN, SIZE, 0f)
                        .submit();
            }
            Matrix4f view = new Matrix4f(), projection = new Matrix4f().setOrtho(0, w, h, 0, -1, 1);
            for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
                stage.fire();
            }
            target.captureToFile(ctx.getOutputDir(), "global-parameters.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("global-parameters"));
    }

    /** The colour due on quad {@code i}, 0 to 255. */
    private static int[] due(int i) {
        int[] out = new int[3];
        for (int c = 0; c < 3; c++) {
            float v = COLOUR[c];
            if (i == 0) v *= 1f - DARK;
            if (i == 2) v *= TINTED[c] * (1f - DARK);
            out[c] = Math.round(v * 255f);
        }
        return out;
    }

    private void report(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) {
            System.out.println("[global-parameters] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[global-parameters] FAIL on " + on + ": " + validation
                    + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[global-parameters] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "global-parameters.png"));
        } catch (IOException e) {
            System.out.println("[global-parameters] FAIL: the capture could not be read: " + e);
            return;
        }
        for (int i = 0; i < materials.length; i++) {
            int rgb = picture.getRGB(MARGIN + i * (SIZE + MARGIN) + SIZE / 2, MARGIN + SIZE / 2);
            int[] got = {rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF}, due = due(i);
            for (int c = 0; c < 3; c++) {
                if (Math.abs(got[c] - due[c]) > TOLERANCE) {
                    System.out.println("[global-parameters] FAIL on " + on + ": " + NAMES[i] + " reads (" + got[0]
                            + ", " + got[1] + ", " + got[2] + "), where (" + due[0] + ", " + due[1] + ", " + due[2]
                            + ") is due");
                    return;
                }
            }
        }
        System.out.println("[global-parameters] PASS on " + on + ": one value set once darkens both materials reading "
                + "it and not the one that does not; a vector of the same collection reaches a vertex stage");
    }

    @Override
    public void dispose() {
        for (CgMaterial m : materials) if (m != null) m.delete();
    }
}
