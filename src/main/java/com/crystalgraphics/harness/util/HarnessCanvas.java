package com.crystalgraphics.harness.util;

import com.crystalgraphics.camera.CgCamera;
import org.joml.Matrix4fc;

/**
 * The camera a scene's immediate canvases draw through: one, set in place, as every canvas given it reads it at each
 * draw and each flush.
 *
 * <pre>{@code
 * canvas.camera(HarnessCanvas.screen(w, h));                     // top-left origin, the target's pixels
 * canvas.text().text("Ready").font(font).at(8, 8).submit();
 * canvas.flush();
 *
 * canvas.camera(HarnessCanvas.world(ctx.getProjection(), w, h)); // perspective: the view goes in each pose
 * canvas.text().text("Label").font(font).pose(viewTimesModel).submit();
 * canvas.flush();
 * }</pre>
 *
 * <p>Change it only between a flush and the next draw: text reads it as it is drawn, quads at the flush.
 */
public final class HarnessCanvas {

    private static final CgCamera CAMERA = new CgCamera();

    private HarnessCanvas() {
    }

    /** The camera as a {@code width x height} screen, top-left origin. */
    public static CgCamera screen(int width, int height) {
        return CAMERA.screenLens(width, height);
    }

    /** The camera as {@code projection} over a {@code width x height} target, view identity. */
    public static CgCamera world(Matrix4fc projection, int width, int height) {
        CAMERA.view().identity();
        return CAMERA.projection(projection).resolution(width, height);
    }
}
