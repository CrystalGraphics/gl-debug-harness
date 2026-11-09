package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.harness.config.HarnessContext;

/**
 * The window's resize, into the context's viewport: once a frame, before input, the camera and drawing. The frame's
 * target takes the new size at its next {@link MainTarget#begin}; the runner tells the engine and the scene.
 *
 * <pre>{@code
 * if (resizeHandler.checkAndPropagate()) scene.onResize(vp.getWidth(), vp.getHeight());
 * }</pre>
 */
public final class ResizeHandler {

    private final HarnessContext ctx;

    public ResizeHandler(HarnessContext ctx) {
        this.ctx = ctx;
    }

    /** True when the window was resized since the last frame, the context's viewport then updated. */
    public boolean checkAndPropagate() {
        if (!HarnessWindow.takeResized()) return false;
        ctx.setScreenDimensions(HarnessWindow.width(), HarnessWindow.height());
        return true;
    }

    @Override
    public String toString() {
        return "ResizeHandler[viewport=" + ctx.getViewport() + "]";
    }
}
