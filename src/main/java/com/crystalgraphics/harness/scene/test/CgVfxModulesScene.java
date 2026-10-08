package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.demo.CgVfxModules;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.render.world.CgWorldRenderer;

/**
 * {@link CgVfxModules}, X6's module catalogue, on the showcase's floor under its sky, to fly round and compare. Every
 * station replays every few seconds, so V (the simulation on the CPU or the GPU) reaches it within one. Scene id
 * {@code vfx-modules}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-modules"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-modules --device=vulkan"
 * // V: CPU or GPU simulation; the stations and where they stand are logged at the start
 * }</pre>
 */
public final class CgVfxModulesScene implements InteractiveSceneLifecycle {

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private CgVfxModules modules;
    private CgFont font;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 9f, 34f);
        ctx.getCamera3D().setPitch(-14f);
        ctx.getCamera3D().setMoveSpeed(8f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        modules = new CgVfxModules(font);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        modules.submit(world, 0.0, 0.0, 0.0, (float) frame.getElapsedTime());
        stage.submitStage(world, 0.0, 0.0, 0.0, ctx.getCamera3D().getPosX(), ctx.getCamera3D().getPosY(),
                ctx.getCamera3D().getPosZ());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        if (modules != null) modules.delete();
        stage.delete();
        if (font != null) font.dispose();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
