package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgExecutor;
import org.jspecify.annotations.Nullable;

/**
 * The interactive runner's frame target, as Minecraft draws its frame into one: the world pass, the scene, the overlays
 * and the loading screen draw into textures of the window's size, copied into the window once at the frame's end
 * (Unreal's back buffer, copied to the window at present). What only a target of our own takes -- the normal target
 * the world lights read -- then runs as it does in the game. RGBA8 as Minecraft's: with {@code GL_FRAMEBUFFER_SRGB} off,
 * the window's sRGB backbuffer takes its bytes unchanged.
 *
 * <pre>{@code
 * ctx.setTarget(frame.begin(w, h));               // the current target until end()
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

    @Nullable
    private CgTexture2D colour, depth;
    @Nullable
    private CgExternalTarget target, window;
    private CgExecutor.@Nullable Into drawing;

    /** Makes the frame's target of {@code width} x {@code height} current, made again when that moved; answers it. */
    public CgExternalTarget begin(int width, int height) {
        if (target == null || target.width() != width || target.height() != height) {
            delete();
            window = CgExternalTarget.host(0, width, height);
            if (ON) {
                colour = CgTextures.of("harness_main", CgTextureDesc.of(width, height, CgFormat.RGBA8_UNORM));
                depth = CgTextures.of("harness_main.depth", CgTextureDesc.of(width, height, CgFormat.DEPTH24_PLUS_STENCIL8));
                target = CgTextures.target("harness_main", colour, depth);
            } else {
                target = window;
            }
        }
        drawing = CgExecutor.into(target);
        return target;
    }

    /** The frame's target as the render stages take it; {@link #begin} first. */
    public CgExternalTarget external() {
        return target;
    }

    /** Before the overlays: our own target's depth cleared for their text. */
    public void overlays() {
        if (ON) CgImmediate.clear(target, false, 0, 0, 0, 0, true, 1.0, false, 0);
    }

    /** Copies its colour into the window, where it is not the window's own, and puts back the current target: the frame's end. */
    public void end() {
        if (ON) CgImmediate.copyColor(target, window);
        if (drawing != null) drawing.close();
        drawing = null;
    }

    public void delete() {
        if (colour != null) colour.release();
        if (depth != null) depth.release();
        colour = depth = null;
        target = window = null;
    }
}
