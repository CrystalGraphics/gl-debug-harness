package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.demo.CgVfxParticleSmokeDemo;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;

/**
 * {@link CgVfxParticleSmokeDemo}, particles and smoke together on the showcase's floor under its sky: a slime mould, a
 * particle smoke column, a nebula, a fire whirl, fireflies in a grenade's smoke and a comet's trail, replayed every
 * {@link CgVfxParticleSmokeDemo#PERIOD} seconds. Scene id {@code vfx-particle-smoke}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-particle-smoke"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-particle-smoke --device=vulkan"
 * }</pre>
 */
public final class CgVfxParticleSmokeScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private CgVfxParticleSmokeDemo demo;
    private CgFont font;
    private final HarnessVfxControls controls = new HarnessVfxControls();

    @Override
    public void init(HarnessContext ctx) {
        // Back far enough to frame every station (CgVfxParticleSmokeDemo.REACH either side).
        ctx.getCamera3D().moveCamera(0f, 12f, CgVfxParticleSmokeDemo.REACH * 1.3f);
        ctx.getCamera3D().setPitch(-6f);
        ctx.getCamera3D().setMoveSpeed(16f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        demo = new CgVfxParticleSmokeDemo(font);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        demo.submit(world, 0.0, 0.0, 0.0, CgVfxDemoControls.get().time(frame.getElapsedTime()));
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
        if (demo != null) demo.delete();
        stage.delete();
        if (font != null) font.dispose();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
