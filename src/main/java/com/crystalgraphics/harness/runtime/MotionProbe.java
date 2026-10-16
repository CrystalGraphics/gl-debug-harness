package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * How much the picture changes frame to frame: the instrument for "it moves at a low rate". Each frame after
 * {@code from}, the scene as drawn (before the HUD) is read back and compared with the frame before, and one line is
 * logged per frame; then a summary and the run stops. A body that updates every frame changes every frame; one held
 * for k frames shows k - 1 frames that barely change between each that does, and the period is its update rate.
 *
 * <pre>{@code
 * -Dcrystalgraphics.harness.motion=240 -Dcrystalgraphics.harness.fixedDelta=0.0083333
 * # [motion] frame 181: change 1.92, moved 3.10%
 * # [motion] 240 frames: median change 1.88, 0 held (under a fifth of the median), longest hold 0
 * }</pre>
 *
 * <ul>
 *   <li>Pair with {@code fixedDelta}, or a slow frame moves the picture further and reads as motion.</li>
 *   <li>Anything that changes on its own every frame (a jittered march, the HUD) is measured too: turn it off, or
 *       frame the camera so it is the only thing that moves.</li>
 *   <li>Reads back synchronously each frame: it times nothing.</li>
 * </ul>
 */
public final class MotionProbe {

    private static final Logger LOGGER = Logger.getLogger(MotionProbe.class.getName());
    /** A pixel moved when a channel changed by more than this, of 255. */
    private static final int MOVED = 2;

    private final int frames, from;
    private final float[] change;
    private ByteBuffer now, last;
    private int width, height, taken;

    public MotionProbe(int frames, int from) {
        this.frames = frames;
        this.from = from;
        this.change = new float[frames];
    }

    /** Reads the bound framebuffer's {@code w} x {@code h} after frame {@code frame}; true once the sample is full. */
    public boolean sample(long frame, int w, int h) {
        if (frame <= from || taken >= frames) return taken >= frames;
        if (now == null || w != width || h != height) {
            width = w;
            height = h;
            now = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            last = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            taken = -1;
        }
        now.clear();
        CgGL.glPixelStorei(CgGL.GL_PACK_ALIGNMENT, 1);
        CgGL.glReadPixels(0, 0, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, now);
        if (taken >= 0) {
            long sum = 0;
            int moved = 0;
            for (int i = 0; i < w * h * 4; i += 4) {
                int d = 0;
                for (int c = 0; c < 3; c++) d = Math.max(d, Math.abs((now.get(i + c) & 255) - (last.get(i + c) & 255)));
                sum += d;
                if (d > MOVED) moved++;
            }
            float mean = (float) sum / (w * h);
            change[taken] = mean;
            LOGGER.info(String.format("[motion] frame %d: change %.3f, moved %.2f%%", frame, mean, 100f * moved / (w * h)));
        }
        taken++;
        ByteBuffer t = last;
        last = now;
        now = t;
        if (taken < frames) return false;
        summarize();
        return true;
    }

    private void summarize() {
        float[] sorted = change.clone();
        Arrays.sort(sorted);
        float median = sorted[frames / 2];
        int held = 0, run = 0, longest = 0;
        for (float c : change) {
            if (c < median * 0.2f) {
                held++;
                longest = Math.max(longest, ++run);
            } else {
                run = 0;
            }
        }
        LOGGER.info(String.format("[motion] %d frames: median change %.3f, %d held (under a fifth of the median),"
                + " longest hold %d", frames, median, held, longest));
    }
}
