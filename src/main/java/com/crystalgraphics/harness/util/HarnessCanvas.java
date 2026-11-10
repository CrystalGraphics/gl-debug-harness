package com.crystalgraphics.harness.util;

import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgPassConstants;
import org.joml.Matrix4fc;

/**
 * The camera an immediate canvas draws under in a scene: what a {@code new CgCanvas()}'s {@code flush()} draws into
 * the bound target with.
 *
 * <pre>{@code
 * HarnessCanvas.screen(w, h);                                    // top-left origin, the target's pixels
 * canvas.text().text("Ready").font(font).at(8, 8).submit();
 * canvas.flush();
 *
 * HarnessCanvas.world(ctx.getProjection(), w, h);                // perspective: the view goes in each pose
 * canvas.text().text("Label").font(font).pose(viewTimesModel).submit();
 * canvas.flush();
 * }</pre>
 *
 * <p>The camera is read at each draw and each flush: change it only between a flush and the next draw.
 */
public final class HarnessCanvas {

    private HarnessCanvas() {
    }

    /** {@code CgImmediate.constants()} as a {@code width x height} screen, top-left origin. */
    public static CgPassConstants screen(int width, int height) {
        CgPassConstants camera = CgImmediate.constants();
        camera.view.identity();
        camera.projection.setOrtho(0f, width, height, 0f, -1f, 1f);
        camera.resolution(width, height);
        return camera;
    }

    /** {@code CgImmediate.constants()} as {@code projection} over a {@code width x height} target, view identity. */
    public static CgPassConstants world(Matrix4fc projection, int width, int height) {
        CgPassConstants camera = CgImmediate.constants();
        camera.view.identity();
        camera.projection.set(projection);
        camera.resolution(width, height);
        return camera;
    }
}
