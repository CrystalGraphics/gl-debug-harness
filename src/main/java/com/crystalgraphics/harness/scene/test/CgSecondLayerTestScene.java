package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * G0.4's gate, a mesh's second UV set and colour: a glTF quad ({@code two_uv_quad.gltf}: {@code TEXCOORD_0} across it,
 * {@code TEXCOORD_1} fixed at (0.75, 0.5), {@code COLOR_0} (0.5, 1, 0.25)) loaded into {@code SPATIAL}, drawn with one
 * shader twice: as itself, sampling a texture (left half white, right half red) at its first UV, and with
 * {@code SECOND_LAYER}, at its second. A quarter across, the first reads white times the colour, (128, 255, 64); the
 * second reads red times it everywhere, (128, 0, 0).
 *
 * <p>Prints {@code [second-layer] PASS} or {@code FAIL}; run it on {@code gl} and {@code vulkan}.</p>
 */
public class CgSecondLayerTestScene implements HarnessSceneLifecycle {

    private static final int SIZE = 96, MARGIN = 8, TOLERANCE = 3;
    private static final int[][] DUE = {{128, 255, 64}, {128, 0, 0}};
    private static final String[] NAMES = {"first UV", "second UV (SECOND_LAYER)"};

    private CgTexture2D texture;
    private final CgMaterial[] materials = new CgMaterial[2];
    private CgMesh quad;

    @Override
    public void init(HarnessContext ctx) {
        texture = CgTexture2D.createEmpty(2, 1, CgTextureSpec.RGBA8_NEAREST);
        ByteBuffer texels = ByteBuffer.allocateDirect(8);
        texels.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);   // white, left
        texels.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255).flip(); // red, right
        texture.uploadRegion(0, 0, 0, 2, 1, texels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        for (int i = 0; i < 2; i++) {
            materials[i] = CgMaterial.newInstance("assets/harness/shader/second_layer_test.shader");
            materials[i].applyProperties(b -> b.sampler("_MainTex", 0, texture));
        }
        materials[1].enableKeyword("SECOND_LAYER");
        quad = CgMeshLoader.load("assets/harness/models/two_uv_quad.gltf");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            CgImmediate.execute(record(w, h));
            target.captureToFile(ctx.getOutputDir(), "second-layer.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("second-layer"));
    }

    private CgRecording record(int w, int h) {
        CgRecording rec = new CgRecording();
        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.set(new Matrix4f().setOrtho(0, w, h, 0, -1, 1));
        CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        CgChunkBuilder c = rec.chunks().begin();
        for (int i = 0; i < materials.length; i++) {
            c.draw(materials[i].pipeline(CgInstanceKind.OBJECT), materials[i].captureBindings(rec.bindings()), quad);
            int at = c.instance();
            float[] r = c.data();
            r[at] = r[at + 5] = r[at + 10] = r[at + 15] = 1f;
            r[at + 32] = MARGIN + i * (SIZE + MARGIN);
            r[at + 33] = MARGIN;
            r[at + 34] = SIZE;
        }
        pass.add(c.end());
        pass.end();
        return rec;
    }

    private void report(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) {
            System.out.println("[second-layer] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[second-layer] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[second-layer] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "second-layer.png"));
        } catch (IOException e) {
            System.out.println("[second-layer] FAIL: the capture could not be read: " + e);
            return;
        }
        for (int i = 0; i < 2; i++) {
            // A quarter across and halfway up the quad; captures are top-down, the quad's v runs with its y.
            int x = MARGIN + i * (SIZE + MARGIN) + SIZE / 4, y = MARGIN + SIZE / 2;
            int rgb = picture.getRGB(x, y);
            int[] got = {rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF};
            for (int k = 0; k < 3; k++) {
                if (Math.abs(got[k] - DUE[i][k]) > TOLERANCE) {
                    System.out.println("[second-layer] FAIL on " + on + ": the " + NAMES[i] + " quad reads ("
                            + got[0] + ", " + got[1] + ", " + got[2] + "), where (" + DUE[i][0] + ", " + DUE[i][1]
                            + ", " + DUE[i][2] + ") is due");
                    return;
                }
            }
        }
        System.out.println("[second-layer] PASS on " + on + ": a glTF's second UV set and colour drawn through SPATIAL, "
                + "SECOND_LAYER reading the second");
    }

    @Override
    public void dispose() {
        for (CgMaterial m : materials) if (m != null) m.delete();
        if (texture != null) texture.delete();
    }
}
