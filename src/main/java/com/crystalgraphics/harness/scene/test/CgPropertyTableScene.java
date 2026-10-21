package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
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
 * A property table read on the GPU: a material's {@code vec4[64]}, every element a different colour, drawn as 64 quads
 * each reading its own element. Past 16 elements a read takes the chunk of 16 holding it, so every chunk is crossed.
 *
 * <p>Prints {@code [property-table] PASS} or {@code FAIL}; run it on {@code gl} at each forced tier, with
 * {@code -Dcrystalgraphics.shaderBuffer.tier=TBO}, and on {@code vulkan}.</p>
 */
public class CgPropertyTableScene implements HarnessSceneLifecycle {

    private static final int COUNT = 64, ACROSS = 8, SIZE = 12, MARGIN = 4, TOLERANCE = 1;

    private CgMaterial material;

    /** Element {@code i}'s colour: every channel differs from its neighbours'. */
    private static float[] colour(int i) {
        return new float[] {i / 63f, ((i * 37) % 64) / 63f, (63 - i) / 63f};
    }

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.newInstance("assets/harness/shader/property_table.shader");
        float[] table = new float[COUNT * 4];
        for (int i = 0; i < COUNT; i++) {
            float[] c = colour(i);
            System.arraycopy(c, 0, table, i * 4, 3);
            table[i * 4 + 3] = 1f;
        }
        material.applyProperties(b -> b.array("_Table", table));
    }

    private static int x(int i) {
        return MARGIN + (i % ACROSS) * (SIZE + MARGIN);
    }

    private static int y(int i) {
        return MARGIN + (i / ACROSS) * (SIZE + MARGIN);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            CgWorldRenderer world = CgWorldRenderer.get();
            world.install();
            for (int i = 0; i < COUNT; i++) {
                world.draw(CgMesh.quads(1), material).custom(0, x(i), y(i), SIZE, i).submit();
            }
            Matrix4f view = new Matrix4f(), projection = new Matrix4f().setOrtho(0, w, h, 0, -1, 1);
            for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
                stage.fire();
            }
            target.captureToFile(ctx.getOutputDir(), "property-table.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("property-table"));
    }

    private void report(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) {
            System.out.println("[property-table] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[property-table] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[property-table] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "property-table.png"));
        } catch (IOException e) {
            System.out.println("[property-table] FAIL: the capture could not be read: " + e);
            return;
        }
        for (int i = 0; i < COUNT; i++) {
            int rgb = picture.getRGB(x(i) + SIZE / 2, y(i) + SIZE / 2);
            int[] got = {rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF};
            float[] want = colour(i);
            for (int c = 0; c < 3; c++) {
                if (Math.abs(got[c] - Math.round(want[c] * 255f)) > TOLERANCE) {
                    System.out.println("[property-table] FAIL on " + on + ": element " + i + " reads (" + got[0] + ", "
                            + got[1] + ", " + got[2] + "), where (" + Math.round(want[0] * 255f) + ", "
                            + Math.round(want[1] * 255f) + ", " + Math.round(want[2] * 255f) + ") is due");
                    return;
                }
            }
        }
        System.out.println("[property-table] PASS on " + on + ": all 64 elements of a vec4[64] read as set, across its four chunks");
    }

    @Override
    public void dispose() {
        if (material != null) material.delete();
    }
}
