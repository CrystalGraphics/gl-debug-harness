package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgPassRecorder;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.CgTextRenderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * render-graph G2.2a's gate: text recorded on a worker thread while the render thread draws text in the same faces.
 *
 * <p>For {@value #STRESS_FRAMES} frames a worker lays out and records a block of CJK text — a window that slides every
 * frame, so it keeps shaping, rasterising, placing and growing atlases — and builds it, overlapping the render thread's
 * frame end (which commits worker-generated glyphs) and its next frame (which draws another block immediately). Then,
 * with the glyph workers drained, one fixed text is drawn both ways into {@code text-threaded-worker.png} and
 * {@code text-threaded-render.png}, which must be identical. Logs {@code [text-threaded]} lines ending PASS or FAIL.</p>
 */
public class CgTextThreadedTestScene implements InteractiveSceneLifecycle {

    private static final int STRESS_FRAMES = 300;
    private static final int GLYPHS_PER_BLOCK = 160;
    private static final int SMALL_PX = 18, LARGE_PX = 40;
    private static final String FIXED = "線程記録の検証 — 文字列は両方で同じ: 東京 大阪 京都 名古屋 札幌 福岡 0123456789 ABCxyz";

    private CgFont small, large;
    private CgTextRenderer workerText, renderText;
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private ExecutorService worker;
    private Future<CgFrame> pending;
    private int failures;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        small = CgFont.load(HarnessFontUtil.JAPANESE_FONT, CgFontStyle.REGULAR, SMALL_PX);
        large = CgFont.load(HarnessFontUtil.JAPANESE_FONT, CgFontStyle.REGULAR, LARGE_PX);
        // Both made here: a renderer's quad renderer and material are set up on the render thread.
        workerText = CgTextRenderer.createManualSized();
        renderText = CgTextRenderer.createManualSized();
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "text-threaded-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getViewport().getWidth(), h = ctx.getViewport().getHeight();
        long n = frame.getFrameNumber();
        if (n <= STRESS_FRAMES) {
            stress(ctx, w, h, (int) n);
        } else if (n == STRESS_FRAMES + 1) {
            converge(ctx, w, h);
        }
    }

    private void stress(HarnessContext ctx, int w, int h, int n) {
        try {
            // The render thread's own text, while the worker records the block submitted last frame.
            renderText.context().updateOrtho(w, h);
            renderText.beginBatch();
            renderText.draw().layout(CgTextLayout.of(block(n * 41 + 1500), large).maxWidth(w * 0.45f).build())
                    .font(large).at(w * 0.5f + 10f, 20f).color(0xFFFFD27F).submit();
            // The worker's own face too: shaping and rasterising it here contends for its lock.
            renderText.draw().layout(CgTextLayout.of(block(n * 53 + 3000), small).maxWidth(w * 0.45f).build())
                    .font(small).at(w * 0.5f + 10f, h * 0.6f).color(0xFF9FD8FF).submit();
            renderText.endBatch();

            if (pending != null) {
                CgFrame built = pending.get();
                CgExecutor.execute(built);
                builder.recycle(built);
            }
        } catch (Exception e) {
            failures++;
            System.out.println("[text-threaded] frame " + n + " failed: " + e);
            e.printStackTrace(System.out);
        }
        if (n == STRESS_FRAMES) {
            pending = null;
            ctx.getArtifactService().requestCapture("stress");
            System.out.println("[text-threaded] stress: " + STRESS_FRAMES + " frames, " + failures + " failures");
            return;
        }
        int next = n + 1;
        // Submitted last, so it runs through this frame's end and into the next frame's drawing.
        pending = worker.submit(() -> record(w, h, block(next * 37), small, 20f, 20f, w * 0.45f));
    }

    private void converge(HarnessContext ctx, int w, int h) {
        boolean drained = CgFontRegistry.get().awaitAsyncGlyphs(10_000);
        String dir = ctx.getOutputDir();
        int differing = -1;
        try {
            // Twice each: a first draw may still be on the bitmap tier where its distance field is being generated.
            for (int pass = 0; pass < 2; pass++) {
                CgFrame built = worker.submit(() -> record(w, h, FIXED, large, 20f, 40f, w - 40f)).get();
                HarnessFboHelper fromWorker = HarnessFboHelper.create(w, h, false);
                fromWorker.bind();
                fromWorker.clear(0.08f, 0.08f, 0.1f, 1f);
                CgExecutor.execute(built);
                builder.recycle(built);
                ByteBuffer workerPixels = null;
                if (pass == 1) {
                    fromWorker.captureToFile(dir, "text-threaded-worker.png");
                    workerPixels = read(w, h);
                }
                fromWorker.unbind();
                fromWorker.delete();

                HarnessFboHelper fromRender = HarnessFboHelper.create(w, h, false);
                fromRender.bind();
                fromRender.clear(0.08f, 0.08f, 0.1f, 1f);
                renderText.context().updateOrtho(w, h);
                renderText.beginBatch();
                renderText.draw().layout(CgTextLayout.of(FIXED, large).maxWidth(w - 40f).build())
                        .font(large).at(20f, 40f).color(0xFFFFFFFF).submit();
                renderText.endBatch();
                if (pass == 1) {
                    fromRender.captureToFile(dir, "text-threaded-render.png");
                    differing = differing(workerPixels, read(w, h));
                }
                fromRender.unbind();
                fromRender.delete();
                CgFontRegistry.get().awaitAsyncGlyphs(10_000);
            }
        } catch (Exception e) {
            failures++;
            System.out.println("[text-threaded] converged draw failed: " + e);
            e.printStackTrace(System.out);
        }
        // stdout: the harness does not print a scene's log4j lines.
        boolean pass = failures == 0 && differing == 0;
        System.out.println("[text-threaded] " + (pass ? "PASS" : "FAIL") + ": " + failures + " failures, "
                + differing + " pixels differ between the worker's and the render thread's text"
                + " (glyph workers drained: " + drained + ")");
        running = false;
    }

    /** Lays out and records {@code text} through the worker's renderer into a frame of its own. Worker thread. */
    private CgFrame record(int w, int h, String text, CgFont font, float x, float y, float maxWidth) {
        CgRecording recording = new CgRecording();
        CgPassRecorder recorder = new CgPassRecorder();
        CgPassConstants constants = new CgPassConstants().resolution(w, h);
        constants.projection.setOrtho(0, w, h, 0, -1, 1);
        recorder.recordInto(recording, CgGraphTexture.current(), CgLoad.load(), constants);
        workerText.context().updateOrtho(w, h);
        workerText.sink(recorder);
        try {
            workerText.beginBatch();
            workerText.draw().layout(CgTextLayout.of(text, font).maxWidth(maxWidth).build())
                    .font(font).at(x, y).color(0xFFFFFFFF).submit();
            workerText.endBatch();
        } finally {
            workerText.sink(null);
        }
        recorder.stop();
        return builder.build(new CgFrameGraph().add(recording.seal()));
    }

    /** The bound framebuffer's pixels, RGBA. */
    private static ByteBuffer read(int w, int h) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        CgGL.glReadPixels(0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static int differing(ByteBuffer a, ByteBuffer b) {
        int count = 0;
        for (int i = 0; i < a.capacity(); i += 4) {
            if (a.getInt(i) != b.getInt(i)) count++;
        }
        return count;
    }

    /** {@value #GLYPHS_PER_BLOCK} CJK ideographs from a window starting at {@code offset}, in lines of forty. */
    private static String block(int offset) {
        StringBuilder text = new StringBuilder(GLYPHS_PER_BLOCK + GLYPHS_PER_BLOCK / 40);
        for (int i = 0; i < GLYPHS_PER_BLOCK; i++) {
            if (i > 0 && i % 40 == 0) text.append('\n');
            text.append((char) (0x4E00 + Math.floorMod(offset + i, 6000)));
        }
        return text.toString();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true;
    }

    @Override
    public void dispose() {
        if (worker != null) worker.shutdownNow();
        if (workerText != null) workerText.delete();
        if (renderText != null) renderText.delete();
    }
}
