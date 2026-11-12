package com.crystalgraphics.harness.util;

import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.harness.capture.HarnessCapture;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgExecutor;
import org.jspecify.annotations.Nullable;

/**
 * A scene's offscreen host target, as Minecraft's main framebuffer is the host's: the scene draws what the host would
 * into it, fires the engine's stages into it, and photographs it. RGBA8 colour and, when asked for, 32-bit float depth,
 * which every device takes: textures of the scene's own, imported as one target.
 *
 * <pre>{@code
 * HarnessTarget target = HarnessTarget.create(w, h, true);     // init
 *
 * target.bind();                                              // render: the current target until unbind
 * target.clear(0.05f, 0.05f, 0.07f, 1f);
 * stage.host().set(0f, w, h, target.external());
 * stage.fire();
 * target.captureToFile(ctx.getOutputDir(), "frame.png");
 * target.unbind();                                            // the one before, back
 *
 * target.delete();                                            // dispose
 * }</pre>
 *
 * <ul>
 *   <li>{@link #bind()} and {@link #unbind()} pair; a second {@code bind} before the {@code unbind} does nothing.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class HarnessTarget {

    private final CgTexture2D colour;
    @Nullable
    private final CgTexture2D depth;
    private final CgExternalTarget target;
    private CgExecutor.@Nullable Into drawing;

    private HarnessTarget(CgTexture2D colour, @Nullable CgTexture2D depth) {
        this.colour = colour;
        this.depth = depth;
        this.target = CgTextures.target("harness_target", colour, depth);
    }

    /** A target of {@code width} x {@code height}, with depth when {@code withDepth}. */
    public static HarnessTarget create(int width, int height, boolean withDepth) {
        CgTexture2D colour = CgTextures.of("harness_target", CgTextureDesc.of(width, height, CgFormat.RGBA8_UNORM));
        CgTexture2D depth = withDepth
                ? CgTextures.of("harness_target.depth", CgTextureDesc.of(width, height, CgFormat.DEPTH32_FLOAT)) : null;
        return new HarnessTarget(colour, depth);
    }

    /** Makes it the current target of every execution, as the host's main target is, until {@link #unbind()}. */
    public void bind() {
        if (drawing == null) drawing = CgExecutor.into(target);
    }

    /** Puts back the current target before {@link #bind()}. */
    public void unbind() {
        if (drawing == null) return;
        drawing.close();
        drawing = null;
    }

    /** Clears its colour to {@code (r, g, b, a)}, and its depth to the far plane. */
    public void clear(float r, float g, float b, float a) {
        CgImmediate.clear(target, true, r, g, b, a, depth != null, 1.0, false, 0);
    }

    /** Photographs its colour into {@code outputDir/filename}, a PNG, top row first. */
    public void captureToFile(String outputDir, String filename) {
        HarnessCapture.target(target, outputDir, filename);
    }

    public void delete() {
        unbind();
        colour.release();
        if (depth != null) depth.release();
    }

    /** It as the render stages take a target: what {@code host().set(...)} and {@code fireOverlay} are given. */
    public CgExternalTarget external() {
        return target;
    }

    /** Its colour texture, for a material to sample or a readback to read. */
    public CgTexture2D getColor() {
        return colour;
    }

    public int getWidth() {
        return target.width();
    }

    public int getHeight() {
        return target.height();
    }
}
