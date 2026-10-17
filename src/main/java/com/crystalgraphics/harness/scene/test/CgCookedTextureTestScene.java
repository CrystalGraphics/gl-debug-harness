package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
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
import com.crystalgraphics.util.io.CgBc7;
import com.crystalgraphics.util.io.CgCookedTexture;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * G0.3's gate, cooked textures: one 64x48 image loaded from its PNG and from its {@code .cgtex} (BC7 sRGB, seven levels,
 * cooked by {@code cookTextures}), each drawn as its colour and as its alpha at three times its size; and the cooked one
 * at a quarter of its size, which samples its level 2, beside that level decoded on the CPU ({@link CgBc7}). Every cooked
 * quad must match its twin: the PNG within BC7's error, its own level all but exactly. On a context with BPTC the blocks
 * are sampled as they are; on {@code mac41} and under {@code -Dcrystalgraphics.texture.bc7=false} they were decoded to
 * RGBA8 as the texture loaded.
 *
 * <p>Prints {@code [cooked-texture] PASS} or {@code FAIL}, naming which path ran; run it on {@code gl}, the {@code mac41}
 * downlevel context and {@code vulkan}.</p>
 */
public class CgCookedTextureTestScene implements HarnessSceneLifecycle {

    private static final String PNG = "assets/harness/textures/cooked_fixture.png";
    private static final String COOKED = "assets/harness/textures/cooked_fixture.cgtex";
    private static final int BIG = 192, SMALL = 16, MARGIN = 8;
    /** Mean and worst difference a cooked quad may have from its PNG twin, per channel out of 255. */
    private static final double MEAN = 2.0;
    private static final int WORST = 40;
    /** The same for a level against itself decoded on the CPU: a GPU's BC7 decode and sRGB rounding apart. */
    private static final double LEVEL_MEAN = 0.5;
    private static final int LEVEL_WORST = 4;

    private CgTexture2D png, cooked, level2;
    private final CgMaterial[] materials = new CgMaterial[3];
    private CgMesh square;

    @Override
    public void init(HarnessContext ctx) {
        CgTextureSpec spec = CgTextureSpec.builder().type(CgTextureType.SRGB8_ALPHA8)
                .minFilter(CgGL.GL_NEAREST).magFilter(CgGL.GL_NEAREST).build();
        png = CgTexture2D.createDirect(PNG, spec);
        cooked = CgTexture2D.createDirect(COOKED, spec);
        CgCookedTexture file = CgCookedTexture.read(COOKED);
        ByteBuffer texels = ByteBuffer.allocateDirect(4 * 16 * 12);
        CgBc7.decode(file.level(2), 16, 12, texels);
        level2 = CgTexture2D.createFromPixels(16, 12, texels, spec);
        CgTexture2D[] textures = {png, cooked, level2};
        for (int i = 0; i < 3; i++) {
            CgTexture2D texture = textures[i];
            materials[i] = CgMaterial.newInstance("assets/harness/shader/cooked_texture_test.shader");
            materials[i].applyProperties(b -> b.sampler("_MainTex", 0, texture));
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
            target.captureToFile(ctx.getOutputDir(), "cooked-texture.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("cooked-texture"));
    }

    /** Quad {@code q} (0..3: colour then alpha, PNG then cooked) at its size's row; its top-left corner in pixels. */
    private static int[] corner(int q, int size) {
        int row = size == BIG ? MARGIN : 2 * MARGIN + BIG * 3 / 4;
        return new int[] {MARGIN + q * (size + MARGIN), row};
    }

    private CgRecording record(int w, int h) {
        CgRecording rec = new CgRecording();
        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.set(new Matrix4f().setOrtho(0, w, h, 0, -1, 1));
        CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        CgChunkBuilder c = rec.chunks().begin();
        for (int size : new int[] {BIG, SMALL}) {
            for (int q = 0; q < 4; q++) {
                // The big row's twin is the PNG; the small row's, the file's level 2 decoded here.
                CgMaterial m = materials[(q & 1) == 1 ? 1 : size == BIG ? 0 : 2];
                c.draw(m.pipeline(CgInstanceKind.OBJECT), m.captureBindings(rec.bindings()), square);
                int at = c.instance();
                float[] r = c.data();
                r[at] = r[at + 5] = r[at + 10] = r[at + 15] = 1f;
                int[] p = corner(q, size);
                r[at + 32] = p[0];
                r[at + 33] = p[1];
                r[at + 34] = size;
                r[at + 35] = q >= 2 ? 1f : 0f;
            }
        }
        pass.add(c.end());
        pass.end();
        return rec;
    }

    private void report(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + ", "
                + (CgCapabilities.detect().bc7() ? "BC7 sampled as blocks" : "BC7 decoded to RGBA8");
        if (glErrors) {
            System.out.println("[cooked-texture] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[cooked-texture] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if (device != null && "recording".equals(device.name())) {
            System.out.println("[cooked-texture] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        if (cooked.isMissing() || cooked.getLevels() != 7) {
            System.out.println("[cooked-texture] FAIL on " + on + ": the cooked texture loaded as "
                    + (cooked.isMissing() ? "missing" : cooked.getLevels() + " levels, not 7"));
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "cooked-texture.png"));
        } catch (IOException e) {
            System.out.println("[cooked-texture] FAIL: the capture could not be read: " + e);
            return;
        }
        StringBuilder seen = new StringBuilder();
        for (int size : new int[] {BIG, SMALL}) {
            for (int q = 0; q < 4; q += 2) {
                int[] a = corner(q, size), b = corner(q + 1, size);
                long sum = 0;
                int worst = 0, n = 0;
                for (int y = 1; y < size * 3 / 4 - 1; y++) {
                    for (int x = 1; x < size - 1; x++) {
                        int pa = picture.getRGB(a[0] + x, a[1] + y), pb = picture.getRGB(b[0] + x, b[1] + y);
                        for (int shift = 0; shift < 24; shift += 8) {
                            int d = Math.abs((pa >> shift & 0xFF) - (pb >> shift & 0xFF));
                            sum += d;
                            worst = Math.max(worst, d);
                            n++;
                        }
                    }
                }
                double mean = sum / (double) n;
                String what = (q == 0 ? "colour" : "alpha") + (size == BIG ? " against the PNG" : " of level 2");
                seen.append(String.format(" %s %.2f/%d;", what, mean, worst));
                if (size == BIG ? mean > MEAN || worst > WORST : mean > LEVEL_MEAN || worst > LEVEL_WORST) {
                    System.out.println("[cooked-texture] FAIL on " + on + ": the cooked " + what + " differs from its twin"
                            + String.format(" by %.2f on average and %d at worst", mean, worst));
                    return;
                }
            }
        }
        System.out.println("[cooked-texture] PASS on " + on + ": the cooked texture matches its twins (mean/worst:" + seen + ")");
    }

    @Override
    public void dispose() {
        for (CgMaterial m : materials) if (m != null) m.delete();
        if (square != null) square.release();
        if (png != null) png.delete();
        if (cooked != null) cooked.delete();
        if (level2 != null) level2.delete();
    }
}
