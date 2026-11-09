package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.harness.util.HarnessCanvas;
import com.crystalgraphics.render.canvas.CgCanvas;

/**
 * How long a face at a size nothing has drawn takes to appear whole: a list of short labels drawn every frame from a
 * cold registry, as a menu opening at a new size draws them. Each label is its own draw, so whether its submit came
 * back whole says how many are still missing a glyph.
 *
 * <p>First a primer, the same face at another size, is drawn until whole and then for {@value #PRIMER_SETTLE} more
 * frames, as a running UI has already drawn text: the first layout, the text pipeline and the workers' copy of the face
 * are paid there, so what is measured is the new size alone.</p>
 *
 * <p>Logs a {@code [text-cold]} line per frame until every label is whole, then a summary: frames and milliseconds from
 * the first draw, the first draw's cost on the render thread, and the worst frame's.</p>
 *
 * <ul>
 *   <li>{@code -Dcrystalgraphics.harness.textCold.font=<path>}: the face; IBM Plex Sans Regular by default</li>
 *   <li>{@code .size=<px>}: the size the font is loaded at, 11 by default</li>
 *   <li>{@code .scale=<f>}: the pose's scale, 2 by default, so the glyphs raster at size times scale</li>
 *   <li>{@code .warm=true}: {@code warmAscii} at that raster size before the primer, as a stylesheet warm runs
 *   before anything opens</li>
 *   <li>{@code .prime=false}: no primer, so the first draw pays every first use as well</li>
 *   <li>{@code .tail=<frames>}: frames drawn once whole, 5 by default; past a profile window's end to get its report</li>
 * </ul>
 */
public class CgTextColdScene implements InteractiveSceneLifecycle {

    private static final String[] LABELS = {
            "Crystal Editor", "VFX Lab", "Frame Profiler", "Notes", "Widget Census", "Machine Example",
            "Settings", "File Explorer", "Terminal", "Task Manager", "Calculator", "Paint",
    };
    private static final int MAX_FRAMES = 600;
    private static final String PRIMER = "Taskbar  12:30  Start  Search apps";
    private static final int PRIMER_SIZE = 14;
    private static final int PRIMER_SETTLE = 30;
    /** Frames drawn after the labels are whole: longer under a profile window, which writes only once it closes. */
    private static final int TAIL_FRAMES = Integer.getInteger("crystalgraphics.harness.textCold.tail", 5);

    private final String fontPath = HarnessFontUtil.resolveFontPath(
            System.getProperty("crystalgraphics.harness.textCold.font", HarnessFontUtil.LATIN_FONT));
    private final int size = Integer.getInteger("crystalgraphics.harness.textCold.size", 11);
    private final float scale = Float.parseFloat(System.getProperty("crystalgraphics.harness.textCold.scale", "2"));
    private final boolean warm = Boolean.getBoolean("crystalgraphics.harness.textCold.warm");
    private final boolean prime = !"false".equals(System.getProperty("crystalgraphics.harness.textCold.prime"));

    private CgFont font, primer;
    private int primedFor = -1;
    private final CgCanvas text = new CgCanvas();
    private final PoseStack pose = new PoseStack();
    private long firstDrawNanos;
    private long firstDrawCost, worstDrawCost;
    private int framesDrawn, wholeAt = -1;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        font = CgFont.load(fontPath, CgFontStyle.REGULAR, size);
        primer = CgFont.load(fontPath, CgFontStyle.REGULAR, PRIMER_SIZE);
        if (warm) CgFontRegistry.get().warmAscii(font, Math.round(size * scale));
        System.out.println("[text-cold] " + fontPath + " at " + size + " x " + scale + " = " + Math.round(size * scale)
                + "px" + (warm ? ", warmed" : ", cold") + (prime ? ", primed" : ", unprimed") + ", " + LABELS.length
                + " labels");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getViewport().getWidth(), h = ctx.getViewport().getHeight();
        if (prime && primedFor < PRIMER_SETTLE) {
            drawPrimer(w, h);
            return;
        }
        long start = System.nanoTime();
        if (framesDrawn == 0) firstDrawNanos = start;

        int incomplete = 0;
        HarnessCanvas.screen(w, h);
        for (int i = 0; i < LABELS.length; i++) {
            pose.setIdentity();
            pose.translate(20f, 20f + i * 18f * scale, 0f);
            pose.scale(scale, scale, 1f);
            boolean whole = text.text().layout(CgTextLayout.of(LABELS[i], font).build())
                    .font(font).pose(pose).at(0f, 0f).color(0xFFFFFFFF).submit();
            if (!whole) incomplete++;
        }
        text.flush();

        long cost = System.nanoTime() - start;
        if (framesDrawn == 0) firstDrawCost = cost;
        worstDrawCost = Math.max(worstDrawCost, cost);
        framesDrawn++;

        double sinceFirst = (start - firstDrawNanos) / 1e6;
        if (wholeAt < 0) {
            System.out.printf("[text-cold] frame %d at %.1f ms: %d of %d labels missing a glyph, %d jobs in flight,"
                            + " draw %.2f ms%n", framesDrawn, sinceFirst, incomplete, LABELS.length,
                    CgFontRegistry.get().getPendingAsyncGlyphCount(), cost / 1e6);
            if (incomplete == 0) {
                wholeAt = framesDrawn;
                System.out.printf("[text-cold] WHOLE after %d frames, %.1f ms from the first draw; first draw %.2f ms,"
                        + " worst %.2f ms%n", framesDrawn, sinceFirst, firstDrawCost / 1e6, worstDrawCost / 1e6);
            }
        }
        if ((wholeAt >= 0 && framesDrawn >= wholeAt + TAIL_FRAMES) || framesDrawn >= MAX_FRAMES) {
            if (wholeAt < 0) System.out.println("[text-cold] NOT WHOLE after " + MAX_FRAMES + " frames");
            running = false;
        }
    }

    /** The same face at another size, scaled as the labels are, until it has drawn whole for a while. */
    private void drawPrimer(int w, int h) {
        HarnessCanvas.screen(w, h);
        pose.setIdentity();
        pose.translate(20f, h - 60f, 0f);
        pose.scale(scale, scale, 1f);
        boolean whole = text.text().layout(CgTextLayout.of(PRIMER, primer).build())
                .font(primer).pose(pose).at(0f, 0f).color(0xFF9FD8FF).submit();
        text.flush();
        if (whole) primedFor++;
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
    }
}
