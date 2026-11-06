package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * The interactive runner's main target, as Minecraft draws its frame into one: the world pass, the scene, the overlays
 * and the loading screen draw into a framebuffer of the window's size, blitted into the window once at the frame's end
 * (Unreal's back buffer, copied to the window at present). The window itself is never a pass's target. What only a framebuffer of our own
 * takes -- the normal target the world lights read -- then runs as it does in the game.
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

    /** Before the overlays: its depth cleared, which their text tests against, not the world's. */
    public void overlays() {
        target.bind();
        CgGL.glClear(CgGL.GL_DEPTH_BUFFER_BIT);
    }

    /** Copies its colour into the window and binds the window: the frame's end. */
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
