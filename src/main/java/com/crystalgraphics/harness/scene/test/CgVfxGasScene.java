package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.demo.CgVfxGasDemo;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;

/**
 * {@link CgVfxGasDemo}, gas simulated on the GPU, on the showcase's floor under its sky: a smoke grenade blasted open, a
 * bonfire, a steam vent and a mushroom cloud, replayed every {@link CgVfxGasDemo#PERIOD} seconds. Scene id
 * {@code vfx-gas}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-gas"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-gas --device=vulkan"
 * }</pre>
 */
public final class CgVfxGasScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private CgVfxGasDemo gas;
    private CgFont font;
    private final HarnessVfxControls controls = new HarnessVfxControls();

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 10f, 42f);
        ctx.getCamera3D().setPitch(-10f);
        ctx.getCamera3D().setMoveSpeed(8f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        gas = new CgVfxGasDemo(font);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        gas.submit(world, 0.0, 0.0, 0.0, CgVfxDemoControls.get().time(frame.getElapsedTime()));
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
        if (gas != null) gas.delete();
        stage.delete();
        if (font != null) font.dispose();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
