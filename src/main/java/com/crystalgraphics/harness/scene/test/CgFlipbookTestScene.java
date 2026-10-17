package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.compute.ops.CgRng;
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
import com.crystalgraphics.vfx.look.CgVfxFlipbook;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * G0.5's gate, a flipbook's frames: a 4x4 sheet of solid colours drawn through {@code fx_particle.glsl}'s
 * {@code fx_subuv_frames} and {@code fx_subimage_uv}, as {@code sprite.shader} draws it, a row of eight particles per
 * flipbook, each at an age, a progress and a seed of its own. Every cell must show the frame Java says:
 *
 * <ol>
 *   <li>a rect table of four cells out of order, looped at 30 frames a second: CC2's billboard, frame for frame;</li>
 *   <li>the grid over a life;</li>
 *   <li>frames 4 to 7 looped at 8 a second, wrapping inside the range;</li>
 *   <li>a random frame changed 10 times a second, against {@code CgRng};</li>
 *   <li>frames 0 and 1 blended over a life.</li>
 * </ol>
 *
 * <p>Prints {@code [flipbook] PASS} or {@code FAIL}; run it on {@code gl}, {@code mac41} and {@code vulkan}.</p>
 */
public class CgFlipbookTestScene implements HarnessSceneLifecycle {

    private static final int CELLS = 8, SIZE = 24, MARGIN = 8, SHEET = 64, TOLERANCE = 3;
    private static final int[] PERM = {5, 12, 0, 9};
    private static final float SEED = 0.37f;
    private static final String[] NAMES = {"rect table, 30 a second", "grid over a life", "frames 4 to 7 looped",
            "random, 10 a second", "frames 0 and 1 blended"};

    private final CgVfxFlipbook[] flipbooks = new CgVfxFlipbook[NAMES.length];
    private final CgMaterial[] materials = new CgMaterial[NAMES.length];
    private CgTexture2D sheet;
    private CgMesh quad;

    @Override
    public void init(HarnessContext ctx) {
        sheet = CgTexture2D.createEmpty(SHEET, SHEET, CgTextureSpec.RGBA8_NEAREST);
        ByteBuffer texels = ByteBuffer.allocateDirect(SHEET * SHEET * 4);
        for (int y = 0; y < SHEET; y++) {
            for (int x = 0; x < SHEET; x++) {
                int[] c = colour(y / 16 * 4 + x / 16);
                texels.put((byte) c[0]).put((byte) c[1]).put((byte) c[2]).put((byte) 255);
            }
        }
        texels.flip();
        sheet.uploadRegion(0, 0, 0, SHEET, SHEET, texels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);

        float[] rects = new float[PERM.length * 4];
        for (int i = 0; i < PERM.length; i++) {
            float u = PERM[i] % 4 * 0.25f, v = PERM[i] / 4 * 0.25f;
            rects[i * 4] = u;
            rects[i * 4 + 1] = v;
            rects[i * 4 + 2] = u + 0.25f;
            rects[i * 4 + 3] = v + 0.25f;
        }
        flipbooks[0] = CgVfxFlipbook.rects(rects).loop(30f).blend(false);
        flipbooks[1] = CgVfxFlipbook.grid(4, 4).blend(false);
        flipbooks[2] = CgVfxFlipbook.grid(4, 4).frames(4, 7).loop(8f).blend(false);
        flipbooks[3] = CgVfxFlipbook.grid(4, 4).random(10f);
        flipbooks[4] = CgVfxFlipbook.grid(4, 4).frames(0, 1);
        for (int i = 0; i < flipbooks.length; i++) {
            CgVfxFlipbook f = flipbooks[i];
            float[] subImage = f.subImage(), subUv = f.subUv();
            materials[i] = CgMaterial.newInstance("assets/harness/shader/flipbook_test.shader");
            materials[i].applyProperties(b -> {
                b.sampler("_Sheet", 0, sheet);
                b.vec4("_SubImage", subImage[0], subImage[1], subImage[2], subImage[3]);
                b.vec4("_SubUV", subUv[0], subUv[1], subUv[2], subUv[3]);
                if (f.isTable()) b.sampler("_SubImages", 1, f.table());
            });
        }
        quad = CgMesh.quads(1);
    }

    /** Frame k's solid colour on the sheet. */
    private static int[] colour(int k) {
        return new int[] {40 + k * 13, 255 - k * 15, k * 67 % 256};
    }

    /** Row r, cell k: its age in seconds, its progress, and the colour due there. */
    private static float[] particle(int r, int k, int[] due) {
        float age = 0f, progress = 0f;
        int frame;
        switch (r) {
            case 0 -> { age = (k + 0.5f) / 30f; frame = PERM[k % 4]; }
            case 1 -> { progress = (2 * k + 0.5f) / 15f; frame = 2 * k; }
            case 2 -> { age = (k + 0.5f) / 8f; frame = 4 + k % 4; }
            case 3 -> {
                age = (k + 0.5f) / 10f;
                float unit = CgRng.unit(CgRng.rng(Float.floatToRawIntBits(SEED), 0, k, 7));
                frame = Math.min((int) Math.floor(unit * 16f), 15);
            }
            default -> {
                progress = k / 7f;
                int[] a = colour(0), b = colour(1);
                for (int c = 0; c < 3; c++) due[c] = Math.round(a[c] + (b[c] - a[c]) * progress);
                return new float[] {age, progress};
            }
        }
        System.arraycopy(colour(frame), 0, due, 0, 3);
        return new float[] {age, progress};
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            CgImmediate.execute(record(w, h));
            target.captureToFile(ctx.getOutputDir(), "flipbook.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("flipbook"));
    }

    private CgRecording record(int w, int h) {
        CgRecording rec = new CgRecording();
        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.set(new Matrix4f().setOrtho(0, w, h, 0, -1, 1));
        CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        CgChunkBuilder c = rec.chunks().begin();
        int[] due = new int[3];
        for (int r = 0; r < materials.length; r++) {
            for (int k = 0; k < CELLS; k++) {
                float[] p = particle(r, k, due);
                c.draw(materials[r].pipeline(CgInstanceKind.OBJECT), materials[r].captureBindings(rec.bindings()), quad);
                int at = c.instance();
                float[] d = c.data();
                d[at] = d[at + 5] = d[at + 10] = d[at + 15] = 1f;
                d[at + 32] = MARGIN + k * (SIZE + MARGIN);
                d[at + 33] = MARGIN + r * (SIZE + MARGIN);
                d[at + 34] = SIZE;
                d[at + 35] = p[0];
                d[at + 36] = p[1];
                d[at + 37] = SEED;
            }
        }
        pass.add(c.end());
        pass.end();
        return rec;
    }

    private void report(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) {
            System.out.println("[flipbook] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[flipbook] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[flipbook] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "flipbook.png"));
        } catch (IOException e) {
            System.out.println("[flipbook] FAIL: the capture could not be read: " + e);
            return;
        }
        int[] due = new int[3];
        for (int r = 0; r < materials.length; r++) {
            for (int k = 0; k < CELLS; k++) {
                particle(r, k, due);
                int rgb = picture.getRGB(MARGIN + k * (SIZE + MARGIN) + SIZE / 2, MARGIN + r * (SIZE + MARGIN) + SIZE / 2);
                int[] got = {rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF};
                for (int ch = 0; ch < 3; ch++) {
                    if (Math.abs(got[ch] - due[ch]) > TOLERANCE) {
                        System.out.println("[flipbook] FAIL on " + on + ": " + NAMES[r] + ", particle " + k + " reads ("
                                + got[0] + ", " + got[1] + ", " + got[2] + "), where (" + due[0] + ", " + due[1] + ", "
                                + due[2] + ") is due");
                        return;
                    }
                }
            }
        }
        System.out.println("[flipbook] PASS on " + on + ": a rect table frame for frame, the grid over a life, a range "
                + "looped, random frames and a blend, " + materials.length * CELLS + " particles");
    }

    @Override
    public void dispose() {
        for (CgMaterial m : materials) if (m != null) m.delete();
        if (sheet != null) sheet.delete();
    }
}
