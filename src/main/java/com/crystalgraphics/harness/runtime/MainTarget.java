package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * The interactive runner's main target, as Minecraft draws its world into one: the world pass and the scene draw into
 * a framebuffer of the window's size, blitted into the window before the overlays. What only a framebuffer of our own
 * takes -- a second colour attachment beside it, the world's merged emission -- then runs as it does in the game.
 *
 * <pre>{@code
 * -Dcrystalgraphics.harness.mainTarget=true     // off: every scene draws into the window's own, 0
 * }</pre>
 */
public final class MainTarget {

    public static final boolean ON = Boolean.getBoolean("crystalgraphics.harness.mainTarget");

    private static final CgFrameBufferFormat FORMAT = CgFrameBufferFormat.builder("harness_main")
            .color(0, CgTextureType.RGBA8).depth(CgTextureType.DEPTH24_STENCIL8).build();

    private CgFrameBuffer target;

    /** Binds it at {@code width} x {@code height}, made again when the window's size moved; answers its id. */
    public int begin(int width, int height) {
        if (target == null || target.getWidth() != width || target.getHeight() != height) {
            if (target != null) target.delete();
            target = CgFrameBuffer.createOwned("harness_main", width, height, FORMAT);
        }
        target.bind();
        CgGL.glViewport(0, 0, width, height);
        return target.getId();
    }

    /** Copies its colour into the window and binds the window again. */
    public void end() {
        int w = target.getWidth(), h = target.getHeight();
        CgFrameBuffer.blitFrom(target.getId(), 0, 0, 0, w, h, 0, 0, w, h, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_NEAREST);
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
    }

    public void delete() {
        if (target != null) target.delete();
        target = null;
    }
}
