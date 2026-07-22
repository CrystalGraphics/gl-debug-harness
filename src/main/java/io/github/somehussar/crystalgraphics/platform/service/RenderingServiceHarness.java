package io.github.somehussar.crystalgraphics.platform.service;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.service.CgRenderingService;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * MC 1.7.10 implementation of {@link CgRenderingService}.
 *
 * <p>{@link #onFrameBegin} is called directly by
 * {@link CgRenderHook} each frame.
 * Viewport dimensions read from {@code Minecraft.displayWidth/displayHeight}.</p>
 */
public final class RenderingServiceHarness implements CgRenderingService {

    @Override
    public void onFrameBegin(float partialTick) {
        CgRenderPipeline.getInstance().execute(partialTick);
    }

    @Override public int getDisplayWidth()  { return HarnessContext.getInstance().getScreenWidth(); }
    @Override public int getDisplayHeight() { return HarnessContext.getInstance().getScreenHeight(); }
}
