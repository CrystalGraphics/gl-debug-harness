package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.demo.CgVfxFluidDemo;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;

/**
 * {@link CgVfxFluidDemo}, the fluid engine playing Flow's editor stages and Mantaflow's scenes, on the showcase's floor
 * under its sky, replayed every {@link CgVfxFluidDemo#PERIOD} seconds. Scene id {@code vfx-fluid}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-fluid"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-fluid --device=vulkan"
 * }</pre>
 */
public final class CgVfxFluidScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private CgVfxFluidDemo fluid;
    private CgFont font;
    private final HarnessVfxControls controls = new HarnessVfxControls();

    @Override
    public void init(HarnessContext ctx) {
        // Back far enough to frame every station (CgVfxFluidDemo.REACH either side), the jets' tops in view.
        ctx.getCamera3D().moveCamera(0f, 10f, CgVfxFluidDemo.REACH * 1.45f);
        ctx.getCamera3D().setPitch(-4f);
        ctx.getCamera3D().setMoveSpeed(16f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        fluid = new CgVfxFluidDemo(font);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        fluid.submit(world, 0.0, 0.0, 0.0, CgVfxDemoControls.get().time(frame.getElapsedTime()));
        stage.submitStage(world, 0.0, 0.0, 0.0, ctx.getCamera3D().getPosX(), ctx.getCamera3D().getPosY(),
                ctx.getCamera3D().getPosZ());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return controls.consume(event);
    }

    @Override
    public String hudLine() {
        return CgVfxDemoControls.get().hudLines(false);
    }

    @Override
    public void dispose() {
        if (fluid != null) fluid.delete();
        stage.delete();
        if (font != null) font.dispose();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
