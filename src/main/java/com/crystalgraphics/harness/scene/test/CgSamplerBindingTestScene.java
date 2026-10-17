package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.vertex.CgVertexFormat;
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
 * G0.2's gate, a sampler per binding: one 4x4 texture uploaded once (its left half red, its right half blue, its own
 * spec linear and clamped) drawn in one pass on four quads, each sampling u and v from -1 to 2 through another
 * binding's sampler: nearest wrapped, nearest clamped, nearest mirrored, and the texture's own. At u = -0.25 a wrapped
 * quad reads 0.75 (blue), a clamped one 0 (red), a mirrored one 0.25 (red); every quad must read exactly that, and the
 * texture is one upload throughout.
 *
 * <p>Prints {@code [sampler-binding] PASS} or {@code FAIL}; run it on {@code gl}, forced tiers, a downlevel context
 * and {@code vulkan}.</p>
 */
public class CgSamplerBindingTestScene implements HarnessSceneLifecycle {

    private static final int SIZE = 96, MARGIN = 8;
    private static final CgSampler[] SAMPLERS = {CgSampler.NEAREST_WRAP, CgSampler.NEAREST_CLAMP,
            CgSampler.builder().filter(CgSampler.Filter.NEAREST).address(CgSampler.Address.MIRROR).build(), null};
    private static final String[] NAMES = {"wrapped", "clamped", "mirrored", "its own"};
    /** At u = -0.25 (a twelfth of the way across), v = 0.5: red is the left half, blue the right. */
    private static final boolean[] BLUE_AT_LEFT = {true, false, false, false};

    private CgTexture2D texture;
    private final CgMaterial[] materials = new CgMaterial[SAMPLERS.length];
    private CgMesh square;

    @Override
    public void init(HarnessContext ctx) {
        texture = CgTexture2D.createEmpty(4, 4, CgTextureSpec.RGBA8_LINEAR);
        ByteBuffer texels = ByteBuffer.allocateDirect(4 * 4 * 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                boolean blue = x >= 2;
                texels.put((byte) (blue ? 0 : 255)).put((byte) 0).put((byte) (blue ? 255 : 0)).put((byte) 255);
            }
        }
        texels.flip();
        texture.uploadRegion(0, 0, 0, 4, 4, texels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        for (int i = 0; i < SAMPLERS.length; i++) {
            CgSampler sampler = SAMPLERS[i];
            materials[i] = CgMaterial.newInstance("assets/harness/shader/sampler_binding_test.shader");
            materials[i].applyProperties(b -> b.sampler("_MainTex", 0, texture, sampler));
        }
        square = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
            int a = m.vertex().position(0, 0, 0).normal(0, 0, 1).uv(0, 0).end();
            int b = m.vertex().position(1, 0, 0).normal(0, 0, 1).uv(1, 0).end();
            int c = m.vertex().position(1, 1, 0).normal(0, 0, 1).uv(1, 1).end();
            int d = m.vertex().position(0, 1, 0).normal(0, 0, 1).uv(0, 1).end();
            m.quad(a, b, c, d);
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            CgImmediate.execute(record(w, h));
            target.captureToFile(ctx.getOutputDir(), "sampler-binding.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("sampler-binding"));
    }

    private CgRecording record(int w, int h) {
        CgRecording rec = new CgRecording();
        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.set(new Matrix4f().setOrtho(0, w, h, 0, -1, 1));
        CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        CgChunkBuilder c = rec.chunks().begin();
        for (int i = 0; i < materials.length; i++) {
            c.draw(materials[i].pipeline(CgInstanceKind.OBJECT), materials[i].captureBindings(rec.bindings()), square);
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
            System.out.println("[sampler-binding] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[sampler-binding] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[sampler-binding] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "sampler-binding.png"));
        } catch (IOException e) {
            System.out.println("[sampler-binding] FAIL: the capture could not be read: " + e);
            return;
        }
        for (int i = 0; i < materials.length; i++) {
            // u = 3x - 1, so u = -0.25 is a quarter of the quad across; v = 0.5 its middle. Captures are top-down.
            int x = MARGIN + i * (SIZE + MARGIN) + SIZE / 4, y = MARGIN + SIZE / 2;
            int rgb = picture.getRGB(x, y) & 0xFFFFFF;
            int want = BLUE_AT_LEFT[i] ? 0x0000FF : 0xFF0000;
            if (rgb != want) {
                System.out.println("[sampler-binding] FAIL on " + on + ": the " + NAMES[i] + " quad reads 0x"
                        + Integer.toHexString(rgb) + " at u -0.25, where 0x" + Integer.toHexString(want) + " is due");
                return;
            }
        }
        System.out.println("[sampler-binding] PASS on " + on + ": one texture, one upload, sampled wrapped, clamped, "
                + "mirrored and as its own in one pass");
    }

    @Override
    public void dispose() {
        for (CgMaterial m : materials) if (m != null) m.delete();
        if (square != null) square.release();
        if (texture != null) texture.delete();
    }
}
