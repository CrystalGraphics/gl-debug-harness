package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.CgVfxBlasts;
import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@link CgVfxBlasts} over the showcase's floor and under its sky: blasts as particles alone, about 360,000 alive at
 * once. Scene id {@code vfx-blasts}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-blasts"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-blasts" -Dcrystalgraphics.harness.vfx.blasts=240
 * }</pre>
 */
public final class CgVfxBlastsScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxBlasts");

    /** {@code -Dcrystalgraphics.harness.vfx.blasts=<n>}: how many, 120 by default. */
    private static final int BLASTS = Integer.getInteger("crystalgraphics.harness.vfx.blasts", 120);

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private final CgVfxBlasts blasts = new CgVfxBlasts(BLASTS);
    private final HarnessVfxControls controls = new HarnessVfxControls();

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 30f, 78f);
        ctx.getCamera3D().setPitch(-22f);
        ctx.getCamera3D().setMoveSpeed(10f);
        LOG.info("[vfx-blasts] {} blasts of particles, each bursting every {}s", BLASTS, CgVfxBlasts.CYCLE);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        blasts.submit(world, 0.0, 0.0, 0.0, CgVfxDemoControls.get().time(frame.getElapsedTime()));
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
        blasts.delete();
        stage.delete();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
