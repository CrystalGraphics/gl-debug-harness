package com.crystalgraphics.harness.util;

import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gpu.resource.CgFrameBuffer;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.harness.capture.HarnessCapture;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.graph.CgExecutor;
import org.jspecify.annotations.Nullable;

/**
 * A scene's offscreen host target, as Minecraft's main framebuffer is the host's: the scene draws what the host would
 * into it, fires the engine's stages into it, and photographs it. RGBA8 colour and, when asked for, 32-bit float depth,
 * which every Vulkan device takes; made on the installed device where there is one, so the frame graph names its
 * images as the device's.
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

    private static final CgFrameBufferFormat COLOR = CgFrameBufferFormat.builder("harness_target")
            .color(0, CgTextureType.RGBA8).build();
    private static final CgFrameBufferFormat COLOR_DEPTH = CgFrameBufferFormat.builder("harness_target_depth")
            .color(0, CgTextureType.RGBA8).depth(CgTextureType.DEPTH32F).build();

    private final CgFrameBuffer framebuffer;
    private final boolean hasDepth;
    private CgExecutor.@Nullable Into drawing;

    private HarnessTarget(CgFrameBuffer framebuffer, boolean hasDepth) {
        this.framebuffer = framebuffer;
        this.hasDepth = hasDepth;
    }

    /** A target of {@code width} x {@code height}, with depth when {@code withDepth}. */
    public static HarnessTarget create(int width, int height, boolean withDepth) {
        CgFrameBufferFormat format = withDepth ? COLOR_DEPTH : COLOR;
        CgFrameBuffer framebuffer = CgRHI.installed()
                ? CgFrameBuffer.createOnDevice("harness_target", width, height, format, 1)
                : CgFrameBuffer.createOwned("harness_target", width, height, format);
        return new HarnessTarget(framebuffer, withDepth);
    }

    /** Makes it the current target of every execution, as the host's main target is, until {@link #unbind()}. */
    public void bind() {
        if (drawing == null) drawing = CgExecutor.into(framebuffer.external());
    }

    /** Puts back the current target before {@link #bind()}. */
    public void unbind() {
        if (drawing == null) return;
        drawing.close();
        drawing = null;
    }

    /** Clears its colour to {@code (r, g, b, a)}, and its depth to the far plane. */
    public void clear(float r, float g, float b, float a) {
        if (hasDepth) framebuffer.clearAll(r, g, b, a);
        else framebuffer.clearColor(r, g, b, a);
    }

    /** Photographs its colour into {@code outputDir/filename}, a PNG, top row first. */
    public void captureToFile(String outputDir, String filename) {
        HarnessCapture.framebuffer(framebuffer, outputDir, filename);
    }

    public void delete() {
        unbind();
        framebuffer.delete();
    }

    /** It as the render stages take a target: what {@code host().set(...)} and {@code fireOverlay} are given. */
    public CgExternalTarget external() {
        return framebuffer.external();
    }

    /** The framebuffer id a stage's host frame names it by. */
    public int getFboId() {
        return framebuffer.getId();
    }

    /** Its colour texture, for a material to sample. */
    public CgTexture2D getColor() {
        return (CgTexture2D) framebuffer.getColorTexture(0);
    }

    public CgFrameBuffer framebuffer() {
        return framebuffer;
    }

    public int getWidth() {
        return framebuffer.getWidth();
    }

    public int getHeight() {
        return framebuffer.getHeight();
    }
}
