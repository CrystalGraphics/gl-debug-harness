package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * render-graph G1's gate on a real driver: one picture drawn three ways — the immediate {@link CgQuadRenderer}, a
 * {@link CgImmediate}, and a frame recorded and built on a worker thread then executed here — into three PNGs that
 * must be identical. A grid of flat quads and a row of rotated ones, deterministic.
 */
public class CgGraphExecutorTestScene implements HarnessSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.GraphExecutorTest");

    private static final int COLS = 16, ROWS = 10, SPINNERS = 6;
    private static final float SIZE = 32f, SPACING = 44f, ORIGIN = 20f, SPINNER = 60f;

    private CgMaterial material;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/quad_renderer_test.shader");
        material.applyProperties(b -> b.sampler("_MainTex", 0, CgFallbackTextures.WHITE_1x1));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        String dir = ctx.getOutputDir();

        HarnessFboHelper immediate = HarnessFboHelper.create(w, h, false);
        immediate.bind();
        immediate.clear(0.08f, 0.08f, 0.1f, 1f);
        drawImmediate(w, h);
        immediate.captureToFile(dir, "graph-executor-immediate.png");
        immediate.unbind();
        immediate.delete();

        HarnessFboHelper graph = HarnessFboHelper.create(w, h, false);
        graph.bind();
        graph.clear(0.08f, 0.08f, 0.1f, 1f);
        try (CgImmediate draw = CgImmediate.begin(constants(w, h))) {
            record(draw.chunks(), material.captureBindings(draw.bindings()));
        }
        graph.captureToFile(dir, "graph-executor-graph.png");
        graph.unbind();
        graph.delete();

        HarnessFboHelper threaded = HarnessFboHelper.create(w, h, false);
        threaded.bind();
        threaded.clear(0.08f, 0.08f, 0.1f, 1f);
        CgFrameBuilder builder = new CgFrameBuilder();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            CgFrame built = worker.submit(() -> {
                CgRecording rec = new CgRecording();
                CgRasterPass pass = rec.raster(CgGraphTexture.current(), CgLoad.load(), constants(w, h), null,
                        CgOrder.LOOKBACK);
                rec.chunks().begin();
                record(rec.chunks(), material.captureBindings(rec.bindings()));
                pass.add(rec.chunks().end());
                pass.end();
                return builder.build(new CgFrameGraph().add(rec.seal()));
            }).get();
            CgExecutor.execute(built);
            builder.recycle(built);
        } catch (Exception e) {
            throw new IllegalStateException("building on the worker failed", e);
        } finally {
            worker.shutdownNow();
        }
        threaded.captureToFile(dir, "graph-executor-threaded.png");
        threaded.unbind();
        threaded.delete();

        LOG.info("[graph-executor] three captures written; glError={}", GlErrorChecker.checkAndLog("graph-executor"));
    }

    private static CgPassConstants constants(int w, int h) {
        CgPassConstants c = new CgPassConstants().resolution(w, h);
        c.projection.setOrtho(0, w, h, 0, -1, 1);
        return c;
    }

    private void drawImmediate(int w, int h) {
        CgPassConstants constants = CgImmediate.constants();
        constants.view.identity();
        constants.projection.identity().ortho(0, w, h, 0, -1, 1);
        constants.resolution(w, h).cameraFromView();

        CgQuadRenderer renderer = CgQuadRenderer.create();
        renderer.begin();
        renderer.useMaterial(material);
        Matrix4f pose = new Matrix4f();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                renderer.quad().at(ORIGIN + col * SPACING, ORIGIN + row * SPACING).size(SIZE, SIZE)
                        .color(colour(col, row)).submit();
            }
        }
        for (int i = 0; i < SPINNERS; i++) {
            spinner(pose, i);
            renderer.quad().at(0f, 0f).size(SPINNER, SPINNER).pose(pose).color(colour(i, ROWS)).submit();
        }
        renderer.flush();
        renderer.end();
        renderer.delete();
    }

    /** The same quads as {@link #drawImmediate}, as records in one draw. */
    private void record(CgChunkBuilder c, int bindings) {
        c.draw(material.pipeline(CgInstanceKind.QUAD), bindings);
        Matrix4f pose = new Matrix4f();
        Vector3f origin = new Vector3f(), right = new Vector3f(), up = new Vector3f();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                float x = ORIGIN + col * SPACING, y = ORIGIN + row * SPACING;
                write(c, x, y, SIZE, 0, 0, SIZE, colour(col, row));
                c.bounds(x, y, x + SIZE, y + SIZE);
            }
        }
        for (int i = 0; i < SPINNERS; i++) {
            spinner(pose, i);
            pose.transformPosition(origin.set(0, 0, 0));
            pose.transformDirection(right.set(SPINNER, 0, 0));
            pose.transformDirection(up.set(0, SPINNER, 0));
            int at = write(c, origin.x, origin.y, right.x, right.y, up.x, up.y, colour(i, ROWS));
            c.data()[at + 2] = origin.z;
        }
    }

    /** One quad record: origin, right, up, uv 0..1, colour as floats in RGBA order. */
    private static int write(CgChunkBuilder c, float ox, float oy, float rx, float ry, float ux, float uy, int argb) {
        int at = c.instance();
        float[] r = c.data();
        r[at] = ox;
        r[at + 1] = oy;
        r[at + 4] = rx;
        r[at + 5] = ry;
        r[at + 8] = ux;
        r[at + 9] = uy;
        r[at + 14] = 1f;
        r[at + 15] = 1f;
        r[at + 16] = ((argb >>> 16) & 0xFF) / 255f;
        r[at + 17] = ((argb >>> 8) & 0xFF) / 255f;
        r[at + 18] = (argb & 0xFF) / 255f;
        r[at + 19] = ((argb >>> 24) & 0xFF) / 255f;
        return at;
    }

    private static void spinner(Matrix4f pose, int i) {
        float cx = ORIGIN + 60f + i * 120f, cy = ORIGIN + ROWS * SPACING + 60f;
        pose.translation(cx, cy, 0f).rotateZ(0.4f + i * 0.7f).translate(-SPINNER * 0.5f, -SPINNER * 0.5f, 0f);
    }

    private static int colour(int a, int b) {
        float hue = ((a + b * COLS) * 0.05f) % 1f;
        float r = clamp(Math.abs(hue * 6f - 3f) - 1f), g = clamp(2f - Math.abs(hue * 6f - 2f)), bl = clamp(2f - Math.abs(hue * 6f - 4f));
        return 0xFF000000 | ((int) (r * 255f) << 16) | ((int) (g * 255f) << 8) | (int) (bl * 255f);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    @Override
    public void dispose() {
    }
}
