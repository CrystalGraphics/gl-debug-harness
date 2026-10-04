package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
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
 * The proof behind MRT emission (render-post-process P6): one pass drawing colour and emission at once, into a target
 * with a second colour attachment, under the one alpha blend GL 3.3 has. A wall, a glow in front of it, a glow behind
 * it and a half-alpha smoke over both, drawn three ways: into the two attachments at once, and into each alone as the
 * reference. Every texel of both attachments must match its reference; the emission must hold what the math says
 * (the glow, half of it under the smoke, nothing behind the wall); and a later pass sampling
 * {@code mrt.attachment(1)} must read it whole.
 *
 * <p>Prints {@code [mrt-emission] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgMrtEmissionTestScene implements HarnessSceneLifecycle {

    private static final int W = 64, H = 48;
    private static final CgFrameBufferFormat MRT = CgFrameBufferFormat.builder("mrt-emission")
            .color(0, CgTextureType.RGBA8).color(1, CgTextureType.R11F_G11F_B10F)
            .depth(CgTextureType.DEPTH24_STENCIL8).build();
    private static final CgFrameBufferFormat COLOR = CgFrameBufferFormat.builder("mrt-emission-color")
            .color(0, CgTextureType.RGBA8).depth(CgTextureType.DEPTH24_STENCIL8).build();
    private static final CgFrameBufferFormat GLOW = CgFrameBufferFormat.builder("mrt-emission-glow")
            .color(0, CgTextureType.R11F_G11F_B10F).depth(CgTextureType.DEPTH24_STENCIL8).build();
    /** What both glows are copied into to be read back: a device reads a texture back only in its own format. */
    private static final CgFrameBufferFormat COPY = CgFrameBufferFormat.builder("mrt-emission-copy")
            .color(0, CgTextureType.RGBA16F).build();

    /** Rectangle (x0, y0, x1, y1), depth, colour (rgba), glow (rgb), per draw; the smoke last, in a pass of its own. */
    private static final float[][] SCENE = {
            {0f, 0f, 1f, 1f, 0.5f, 0.2f, 0.2f, 0.3f, 1f, 0f, 0f, 0f},                // the wall
            {0.1f, 0.1f, 0.45f, 0.9f, 0.3f, 1f, 0.5f, 0.25f, 1f, 2f, 1f, 0.5f},      // a glow in front of it
            {0.55f, 0.1f, 0.9f, 0.9f, 0.8f, 1f, 0.5f, 0.25f, 1f, 2f, 1f, 0.5f},      // a glow behind it
    };
    private static final float[] SMOKE = {0.1f, 0.5f, 0.9f, 0.9f, 0.1f, 0.5f, 0.5f, 0.5f, 0.5f, 0f, 0f, 0f};

    private CgMaterial both, color, glow, copy, copyRef;

    @Override
    public void init(HarnessContext ctx) {
        both = CgMaterial.newInstance("assets/harness/shader/mrt_proof.shader");
        color = CgMaterial.newInstance("assets/harness/shader/mrt_proof_single.shader");
        glow = CgMaterial.newInstance("assets/harness/shader/mrt_proof_single.shader");
        glow.enableKeyword("GLOW");
        copy = CgMaterial.newInstance("assets/harness/shader/mrt_proof_copy.shader");
        copyRef = CgMaterial.newInstance("assets/harness/shader/mrt_proof_copy.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgGraphTexture mrt = CgGraphTexture.requested("mrt-emission", new CgTextureDesc(W, H, MRT));
        CgGraphTexture refColor = CgGraphTexture.requested("mrt-emission-color", new CgTextureDesc(W, H, COLOR));
        CgGraphTexture refGlow = CgGraphTexture.requested("mrt-emission-glow", new CgTextureDesc(W, H, GLOW));
        CgGraphTexture read = CgGraphTexture.requested("mrt-emission-copy", new CgTextureDesc(W, H, COPY));
        CgGraphTexture readRef = CgGraphTexture.requested("mrt-emission-copy-ref", new CgTextureDesc(W, H, COPY));
        CgPassConstants constants = new CgPassConstants().resolution(W, H);
        CgRecording rec = new CgRecording();
        draw(rec, mrt, both, constants);
        draw(rec, refColor, color, constants);
        draw(rec, refGlow, glow, constants);
        copy(rec, read, copy, mrt.attachment(1), constants);
        copy(rec, readRef, copyRef, refGlow, constants);
        CgImmediate.execute(rec);

        String failure = check(mrt, refColor, read, readRef);
        CgRecording release = new CgRecording();
        release.release(mrt);
        release.release(refColor);
        release.release(refGlow);
        release.release(read);
        release.release(readRef);
        CgImmediate.execute(release);
        report(failure, GlErrorChecker.checkAndLog("mrt-emission"));
    }

    /** {@code source} texel for texel into {@code target}, by a later pass. */
    private static void copy(CgRecording rec, CgGraphTexture target, CgMaterial material, CgTexture source,
                             CgPassConstants constants) {
        CgRasterPass pass = rec.raster(target, CgLoad.clear(0, 0, 0, 0), constants, null, CgOrder.LOOKBACK);
        material.applyProperties(b -> b.sampler("_Source", 0, source));
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(material.pipeline(CgInstanceKind.OBJECT), material.captureBindings(rec.bindings()), CgMesh.quads(1));
        c.instance();
        pass.add(c.end());
        pass.end();
    }

    /** The scene into {@code target} under {@code material}: the wall and glows, then the smoke in a later pass. */
    private static void draw(CgRecording rec, CgGraphTexture target, CgMaterial material, CgPassConstants constants) {
        CgRasterPass pass = rec.raster(target, CgLoad.clear(0, 0, 0, 0).andDepth(1), constants, null, CgOrder.LOOKBACK);
        CgChunkBuilder c = rec.chunks().begin();
        for (float[] d : SCENE) rect(c, material, rec, d);
        pass.add(c.end());
        pass.end();
        CgRasterPass smoke = rec.raster(target, CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        c = rec.chunks().begin();
        rect(c, material, rec, SMOKE);
        smoke.add(c.end());
        smoke.end();
    }

    private static void rect(CgChunkBuilder c, CgMaterial material, CgRecording rec, float[] d) {
        c.draw(material.pipeline(CgInstanceKind.OBJECT), material.captureBindings(rec.bindings()), CgMesh.quads(1));
        int at = c.instance();
        float[] data = c.data();
        System.arraycopy(d, 0, data, at + 32, 4);    // CUSTOM0: the rectangle
        data[at + 36] = d[4];                        // CUSTOM1.x: depth
        System.arraycopy(d, 5, data, at + 40, 4);    // CUSTOM2: colour
        System.arraycopy(d, 9, data, at + 44, 3);    // CUSTOM3: glow
    }

    /**
     * Null when both attachments match their references and the emission holds the math: attachment 1 read through
     * {@code attachment(1)} into {@code read}, the reference glow into {@code readRef}.
     */
    private static String check(CgGraphTexture mrt, CgGraphTexture refColor, CgGraphTexture read, CgGraphTexture readRef) {
        float[] color0 = readBytes(mrt.framebuffer().getColorTexture(0)), wantColor = readBytes(refColor.framebuffer().getColorTexture(0));
        float[] glow1 = readHalf(read.framebuffer().getColorTexture(0)), wantGlow = readHalf(readRef.framebuffer().getColorTexture(0));
        String d = differ("attachment 0 against its reference", color0, wantColor, 4);
        if (d == null) d = differ("attachment 1, read through attachment(1), against its reference", glow1, wantGlow, 3);
        if (d == null) d = expect(glow1, 0.25f, 0.3f, 2f, 1f, 0.5f, "the glow in front");
        if (d == null) d = expect(glow1, 0.25f, 0.7f, 1f, 0.5f, 0.25f, "the glow under half-alpha smoke");
        if (d == null) d = expect(glow1, 0.7f, 0.3f, 0f, 0f, 0f, "the glow behind the wall");
        if (d == null) d = expect(glow1, 0.7f, 0.7f, 0f, 0f, 0f, "smoke over the wall");
        return d;
    }

    private static String differ(String what, float[] made, float[] want, int channels) {
        for (int i = 0; i < made.length; i++) {
            if (i % 4 >= channels) continue;
            if (Math.abs(made[i] - want[i]) > 1e-3f * Math.max(1f, Math.abs(want[i]))) {
                int texel = i / 4;
                return what + ": texel (" + texel % W + ", " + texel / W + ")." + i % 4 + " is " + made[i] + ", not " + want[i];
            }
        }
        return null;
    }

    private static String expect(float[] glow, float u, float v, float r, float g, float b, String what) {
        int x = (int) (u * W), y = (int) (v * H), i = (y * W + x) * 4;
        float[] want = {r, g, b};
        for (int ch = 0; ch < 3; ch++) {
            if (Math.abs(glow[i + ch] - want[ch]) > 0.02f * Math.max(1f, want[ch])) {
                return what + " at (" + x + ", " + y + ") is (" + glow[i] + ", " + glow[i + 1] + ", " + glow[i + 2]
                        + "), not (" + r + ", " + g + ", " + b + ")";
            }
        }
        return null;
    }

    private static float[] readHalf(CgTexture texture) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(W * H * 16).order(ByteOrder.nativeOrder());
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, texture.getId());
        CgGL.glGetTexImage(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA, CgGL.GL_FLOAT, pixels);
        float[] out = new float[W * H * 4];
        for (int i = 0; i < out.length; i++) out[i] = pixels.getFloat(i * 4);
        return out;
    }

    private static float[] readBytes(CgTexture texture) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, texture.getId());
        CgGL.glGetTexImage(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, pixels);
        float[] out = new float[W * H * 4];
        for (int i = 0; i < out.length; i++) out[i] = (pixels.get(i) & 0xFF) / 255f;
        return out;
    }

    private static void report(String failure, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[mrt-emission] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[mrt-emission] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if ("recording".equals(on)) {
            System.out.println("[mrt-emission] PASS on recording: every command validated; a recording device draws nothing to compare");
        } else if (failure != null) {
            System.out.println("[mrt-emission] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[mrt-emission] PASS on " + on + ": colour and emission drawn in one pass match each drawn "
                    + "alone, smoke dims the glow by its alpha, the wall hides it, and attachment(1) reads it whole");
        }
    }

    @Override
    public void dispose() {
    }
}
