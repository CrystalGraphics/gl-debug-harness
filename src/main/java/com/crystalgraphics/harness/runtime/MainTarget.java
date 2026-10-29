package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * The interactive runner's main target, as Minecraft draws its world into one: the world pass and the scene draw into
 * a framebuffer of the window's size, blitted into the window before the overlays. What only a framebuffer of our own
 * takes -- the normal target the world lights read, the world's merged emission -- then runs as it does in the game.
 * RGBA8 as Minecraft's: with {@code GL_FRAMEBUFFER_SRGB} off, the window's sRGB backbuffer takes its bytes unchanged.
 *
 * <pre>{@code
 * -Dcrystalgraphics.harness.mainTarget=false    // every scene draws into the window's own, 0: the lights then rebuild
 *                                               // our surfaces' normals from depth, as Minecraft's blocks
 * }</pre>
 */
public final class MainTarget {

    public static final boolean ON = Boolean.parseBoolean(System.getProperty("crystalgraphics.harness.mainTarget", "true"));

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
        // The world pass cleared ours, not the window's: the overlays' text depth-tests against it.
        CgGL.glClear(CgGL.GL_DEPTH_BUFFER_BIT);
    }

    public void delete() {
        if (target != null) target.delete();
        target = null;
    }
}
