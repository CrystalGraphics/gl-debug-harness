package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.mesh.CgMeshStore;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * gpu-compute C9's gate for multi-draw: one pass of 64 draws of distinct meshes under one pipeline and bindings -- a
 * mesh of two submeshes, a draw of three instances and a draw of a range among them -- then two {@code FRAME} meshes on
 * the frame ring, then the same twice over for triangle strips with no indices (8 in a slab, 2 on the ring). Drawn with
 * {@link CgMeshStore#multiDraw(boolean)} on, then off: the pictures must be byte-identical, every instance must cover
 * its cell, and the pass must take 4 calls on against 77 off.
 *
 * <p>Prints {@code [multi-draw] PASS} or {@code FAIL}. Where {@code CgCapabilities.multiDraw()} is false both pictures
 * are drawn a call a draw, and the count checked is that.</p>
 */
public class CgMultiDrawTestScene implements HarnessSceneLifecycle {

    private static final int STRIPS = 64, TWO_PARTS = 20, INSTANCED = 40, RANGED = 50, ARRAYS = 8;
    private static final int COLUMNS = 10, CELL = 40, SIZE = 32, MARGIN = 8;
    private static final int BACKGROUND_R = 20, BACKGROUND_G = 20, BACKGROUND_B = 26;
    /** One call a run when joined (indexed and array meshes, in slabs and on the ring); one a submesh when not. */
    private static final int JOINED_CALLS = 4, SEPARATE_CALLS = STRIPS + 1 + 2 + ARRAYS + 2;

    private CgMaterial material;
    private final CgMesh[] strips = new CgMesh[STRIPS];
    private final CgMesh[] frameMeshes = new CgMesh[2];
    /** Triangle strips with no indices, in a slab and on the ring. */
    private final CgMesh[] arrayStrips = new CgMesh[ARRAYS], frameArrayStrips = new CgMesh[2];
    private int cells;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/multi_draw_test.shader");
        for (int i = 0; i < STRIPS; i++) {
            int index = i, segments = i == RANGED ? 3 : 1 + i % 4;
            strips[i] = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
                if (index == TWO_PARTS) {
                    strip(m, 1, 0f, 0.5f);
                    m.submesh();
                    strip(m, 2, 0.5f, 1f);
                } else {
                    strip(m, segments, 0f, 1f);
                }
            });
        }
        for (int i = 0; i < frameMeshes.length; i++) {
            int segments = 2 + i;
            frameMeshes[i] = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> strip(m, segments, 0f, 1f));
        }
        for (int i = 0; i < ARRAYS; i++) {
            int segments = 1 + i % 3;
            arrayStrips[i] = CgMesh.build(CgVertexFormat.SPATIAL, m -> arrayStrip(m, segments));
        }
        for (int i = 0; i < frameArrayStrips.length; i++) {
            int segments = 3 + i;
            frameArrayStrips[i] = CgMesh.build(CgVertexFormat.SPATIAL, CgMesh.Usage.FRAME, m -> arrayStrip(m, segments));
        }
    }

    /** The same strip as a triangle strip of vertices alone. */
    private static void arrayStrip(CgMeshWriter m, int segments) {
        m.topology(CgMeshTopology.TRIANGLE_STRIP);
        for (int s = 0; s <= segments; s++) {
            float x = s / (float) segments;
            m.vertex().position(x, 0f, 0f).normal(0f, 0f, 1f).uv(x, 0f).end();
            m.vertex().position(x, 1f, 0f).normal(0f, 0f, 1f).uv(x, 1f).end();
        }
    }

    /** A strip of quads across the unit square's width, from {@code y0} to {@code y1}. */
    private static void strip(CgMeshWriter m, int segments, float y0, float y1) {
        int first = -1;
        for (int s = 0; s <= segments; s++) {
            float x = s / (float) segments;
            int bottom = m.vertex().position(x, y0, 0f).normal(0f, 0f, 1f).uv(x, 0f).end();
            m.vertex().position(x, y1, 0f).normal(0f, 0f, 1f).uv(x, 1f).end();
            if (first >= 0) m.quad(first, bottom, bottom + 1, first + 1);
            first = bottom;
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        CgMeshStore store = CgMeshStore.get();
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        long[] calls = new long[2];
        String[] captures = {"multi-draw-on.png", "multi-draw-off.png"};
        try {
            for (int mode = 0; mode < 2; mode++) {
                store.multiDraw(mode == 0);
                target.clear(BACKGROUND_R / 255f, BACKGROUND_G / 255f, BACKGROUND_B / 255f, 1f);
                long before = store.drawCalls();
                CgImmediate.execute(record(w, h));
                calls[mode] = store.drawCalls() - before;
                target.captureToFile(ctx.getOutputDir(), captures[mode]);
            }
        } finally {
            store.multiDraw(true);
            target.unbind();
            target.delete();
        }
        compare(ctx, calls, captures, GlErrorChecker.checkAndLog("multi-draw"));
    }

    /** Every strip into its cell under one pipeline and bindings, then the two ring meshes. */
    private CgRecording record(int w, int h) {
        CgRecording rec = new CgRecording();
        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.set(new Matrix4f().setOrtho(0, w, h, 0, -1, 1));
        CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants, null, CgOrder.LOOKBACK);
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        int bindings = material.captureBindings(rec.bindings());
        CgChunkBuilder c = rec.chunks().begin();
        cells = 0;
        for (int i = 0; i < STRIPS; i++) {
            c.draw(pipeline, bindings, strips[i]);
            if (i == RANGED) c.range(0, 6, 6);   // the middle quad of three
            for (int k = i == INSTANCED ? 3 : 1; k > 0; k--) record(c);
        }
        for (CgMesh[] group : new CgMesh[][]{frameMeshes, arrayStrips, frameArrayStrips}) {
            for (CgMesh mesh : group) {
                c.draw(pipeline, bindings, mesh);
                record(c);
            }
        }
        pass.add(c.end());
        pass.end();
        return rec;
    }

    /** The next cell's record: the identity, where the cell is, its colour. */
    private void record(CgChunkBuilder c) {
        int cell = cells++, at = c.instance();
        float[] r = c.data();
        r[at] = r[at + 5] = r[at + 10] = r[at + 15] = 1f;
        r[at + 32] = MARGIN + (cell % COLUMNS) * CELL;
        r[at + 33] = MARGIN + (cell / COLUMNS) * CELL;
        r[at + 34] = SIZE;
        r[at + 36] = (64 + cell * 37 % 192) / 255f;
        r[at + 37] = (64 + cell * 91 % 192) / 255f;
        r[at + 38] = (64 + cell * 53 % 192) / 255f;
        r[at + 39] = 1f;
    }

    private void compare(HarnessContext ctx, long[] calls, String[] captures, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        boolean multi = CgCapabilities.detect().multiDraw();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[multi-draw] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (validation > 0) {
            System.out.println("[multi-draw] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        long expected = multi ? JOINED_CALLS : SEPARATE_CALLS;
        if (calls[0] != expected || calls[1] != SEPARATE_CALLS) {
            System.out.println("[multi-draw] FAIL on " + on + ": " + calls[0] + " calls joined and " + calls[1]
                    + " not, where " + expected + " and " + SEPARATE_CALLS + " were due");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[multi-draw] PASS on recording: every command validated; a recording device draws nothing to compare");
            return;
        }
        try {
            BufferedImage joined = ImageIO.read(new File(ctx.getOutputDir(), captures[0]));
            BufferedImage separate = ImageIO.read(new File(ctx.getOutputDir(), captures[1]));
            String failure = check(joined, separate);
            if (failure != null) {
                System.out.println("[multi-draw] FAIL on " + on + ": " + failure);
                return;
            }
        } catch (IOException e) {
            System.out.println("[multi-draw] FAIL: a capture could not be read: " + e);
            return;
        }
        System.out.println("[multi-draw] PASS on " + on + ": " + cells + " cells drawn in " + calls[0] + " calls"
                + (multi ? " joined" : " (no multi-draw on this context)") + " and " + calls[1]
                + " separately, byte-identical");
    }

    /** Null when the pictures are the same and every cell holds its strip; else what is wrong. */
    private String check(BufferedImage joined, BufferedImage separate) {
        for (int y = 0; y < joined.getHeight(); y++) {
            for (int x = 0; x < joined.getWidth(); x++) {
                if (joined.getRGB(x, y) != separate.getRGB(x, y)) {
                    return "joined differs from separate first at (" + x + ", " + y + "): 0x"
                            + Integer.toHexString(joined.getRGB(x, y)) + " against 0x"
                            + Integer.toHexString(separate.getRGB(x, y));
                }
            }
        }
        int background = BACKGROUND_R << 16 | BACKGROUND_G << 8 | BACKGROUND_B;
        for (int cell = 0; cell < cells; cell++) {
            int x = MARGIN + (cell % COLUMNS) * CELL + SIZE / 2, y = MARGIN + (cell / COLUMNS) * CELL + SIZE / 2;
            if ((joined.getRGB(x, y) & 0xFFFFFF) == background) return "cell " + cell + " is empty at its centre";
        }
        return null;
    }

    @Override
    public void dispose() {
        for (CgMesh strip : strips) strip.release();
        for (CgMesh strip : arrayStrips) strip.release();
    }
}
