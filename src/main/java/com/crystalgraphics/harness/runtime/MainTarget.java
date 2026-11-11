package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.render.graph.CgExecutor;
import org.jspecify.annotations.Nullable;

/**
 * The interactive runner's frame target, as Minecraft draws its frame into one: the world pass, the scene, the overlays
 * and the loading screen draw into a framebuffer of the window's size, copied into the window once at the frame's end
 * (Unreal's back buffer, copied to the window at present). What only a framebuffer of our own takes -- the normal target
 * the world lights read -- then runs as it does in the game. RGBA8 as Minecraft's: with {@code GL_FRAMEBUFFER_SRGB} off,
 * the window's sRGB backbuffer takes its bytes unchanged.
 *
 * <pre>{@code
 * ctx.setTargetFramebuffer(frame.begin(w, h));   // the current target until end()
 * ... the frame ...
 * frame.overlays();                               // its depth cleared for the HUD
 * frame.end();                                    // into the window
 *
 * -Dcrystalgraphics.harness.mainTarget=false     // the window's own framebuffer instead: the lights then rebuild our
 *                                                // surfaces' normals from depth, as Minecraft's blocks
 * }</pre>
 */
public final class MainTarget {

    public static final boolean ON = Boolean.parseBoolean(System.getProperty("crystalgraphics.harness.mainTarget", "true"));

    private static final CgFrameBufferFormat FORMAT = CgFrameBufferFormat.builder("harness_main")
            .color(0, CgTextureType.RGBA8).depth(CgTextureType.DEPTH24_STENCIL8).build();

    @Nullable
    private CgFrameBuffer target;
    private CgExecutor.@Nullable Into drawing;

    /** Makes the frame's target of {@code width} x {@code height} current, made again when that moved; answers its id. */
    public int begin(int width, int height) {
        if (target == null || target.getWidth() != width || target.getHeight() != height) {
            if (target != null) target.delete();
            target = ON ? CgFrameBuffer.createOwned("harness_main", width, height, FORMAT)
                    : CgFrameBuffer.wrap("harness_window", 0, width, height);
        }
        drawing = CgExecutor.into(target.external());
        return target.getId();
    }

    /** The frame's target as the render stages take it; {@link #begin} first. */
    public CgExternalTarget external() {
        return target.external();
    }

    /** Before the overlays: our own target's depth cleared for their text. */
    public void overlays() {
        if (ON) target.clearDepth();
    }

    /** Copies its colour into the window, where it is not the window's own, and puts back the current target: the frame's end. */
    public void end() {
        if (ON) target.copyColorTo(0);
        if (drawing != null) drawing.close();
        drawing = null;
    }

    public void delete() {
        if (target != null) target.delete();
        target = null;
    }
}
