package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
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
import com.crystalgraphics.render.graph.CgTextureDesc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The gate for raster passes into mip levels: a 40x24 RGBA16F chain drawn level by level in one recording, level 0 a
 * pattern of the pixel's position, each level after it cleared and then the mean of the 2x2 texels of the level above,
 * read through {@code chain.level(k - 1)} while the pass draws into level {@code k} of the same texture. Every texel of
 * every level is read back and checked against Java's answer, rounded to half floats as the texture stores them.
 *
 * <p>A pin that failed reads the level being drawn; a clear or a draw that reached the wrong level changes another's
 * texels; a viewport of the wrong size leaves texels unwritten. Prints {@code [raster-levels] PASS} or {@code FAIL};
 * on Vulkan a validation error fails it.</p>
 */
public class CgRasterLevelsTestScene implements HarnessSceneLifecycle {

    private static final int W = 40, H = 24;
    private static final CgFrameBufferFormat HALF = CgFrameBufferFormat.builder("raster-levels")
            .color(0, CgTextureType.RGBA16F).build();

    private CgMaterial top, down;

    @Override
    public void init(HarnessContext ctx) {
        top = CgMaterial.newInstance("assets/harness/shader/raster_levels_test.shader");
        down = CgMaterial.newInstance("assets/harness/shader/raster_levels_test.shader");
        down.enableKeyword("DOWN");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgGraphTexture chain = CgGraphTexture.requested("raster-levels", new CgTextureDesc(W, H, HALF).withMips());
        CgRecording rec = new CgRecording();
        for (int k = 0; k < chain.getLevels(); k++) {
            CgPassConstants constants = new CgPassConstants().resolution(Math.max(1, W >> k), Math.max(1, H >> k));
            CgRasterPass pass = rec.raster(chain, k, CgLoad.clear(0, 0, 0, 0), constants, null, CgOrder.LOOKBACK);
            CgMaterial material = k == 0 ? top : down;
            int bindings = material.captureBindings(rec.bindings());
            if (k > 0) bindings = rec.bindings().withTexture(bindings, 0, chain.level(k - 1));
            CgChunkBuilder c = rec.chunks().begin();
            c.draw(material.pipeline(CgInstanceKind.OBJECT), bindings, CgMesh.quads(1));
            c.instance();
            pass.add(c.end());
            pass.end();
        }
        CgImmediate.execute(rec);

        String failure = check(chain.framebuffer(), chain.getLevels());
        CgRecording release = new CgRecording();
        release.release(chain);
        CgImmediate.execute(release);
        report(failure, GlErrorChecker.checkAndLog("raster-levels"), chain.getLevels());
    }

    /** Null when every level holds what Java works out for it; else the first texel that does not. */
    private static String check(CgFrameBuffer chain, int levels) {
        float[] want = new float[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                want[i] = half((x + 0.5f) / 64f);
                want[i + 1] = half((y + 0.5f) / 64f);
                want[i + 2] = 0.25f;
                want[i + 3] = 1f;
            }
        }
        int w = W, h = H;
        for (int k = 0; k < levels; k++) {
            if (k > 0) {
                int pw = w, ph = h;
                w = Math.max(1, w >> 1);
                h = Math.max(1, h >> 1);
                float[] above = want;
                want = new float[w * h * 4];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int x0 = 2 * x, y0 = 2 * y, x1 = Math.min(x0 + 1, pw - 1), y1 = Math.min(y0 + 1, ph - 1);
                        for (int ch = 0; ch < 4; ch++) {
                            float sum = above[(y0 * pw + x0) * 4 + ch] + above[(y0 * pw + x1) * 4 + ch]
                                    + above[(y1 * pw + x0) * 4 + ch] + above[(y1 * pw + x1) * 4 + ch];
                            want[(y * w + x) * 4 + ch] = half(sum * 0.25f + (ch == 2 ? 0.0625f : 0f));
                        }
                    }
                }
            }
            float[] made = read(chain, k, w, h);
            for (int i = 0; i < want.length; i++) {
                if (Math.abs(made[i] - want[i]) > 2e-3f * Math.max(1f, Math.abs(want[i]))) {
                    int texel = i / 4;
                    return "level " + k + " (" + w + "x" + h + ") texel (" + texel % w + ", " + texel / w + ")." + i % 4
                            + " is " + made[i] + ", not " + want[i];
                }
            }
        }
        return null;
    }

    private static float[] read(CgFrameBuffer chain, int level, int w, int h) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder());
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, chain.getColorTexture(0).getId());
        CgGL.glGetTexImage(CgGL.GL_TEXTURE_2D, level, CgGL.GL_RGBA, CgGL.GL_FLOAT, pixels);
        float[] out = new float[w * h * 4];
        for (int i = 0; i < out.length; i++) out[i] = pixels.getFloat(i * 4);
        return out;
    }

    private static float half(float value) {
        return Float.float16ToFloat(Float.floatToFloat16(value));
    }

    private static void report(String failure, boolean glErrors, int levels) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[raster-levels] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[raster-levels] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if ("recording".equals(on)) {
            System.out.println("[raster-levels] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else if (failure != null) {
            System.out.println("[raster-levels] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[raster-levels] PASS on " + on + ": " + levels + " levels drawn one from the next in one "
                    + "texture, every texel as worked out");
        }
    }

    @Override
    public void dispose() {
    }
}
