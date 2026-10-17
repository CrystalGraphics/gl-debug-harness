package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.CgVfxBurst;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * G0.11's scene, the particle frame at a move's scale: 30 emitters each a shape of its own (its module kinds are the
 * subset of five that its number spells), so 30 pools stepping and ranging every frame, with few particles each so
 * what a frame costs is the fixed cost per pool. Scene id {@code vfx-shapes}.
 *
 * <pre>{@code
 * // GPU zones per frame, on each device: vfx.pool.step and vfx.pool.range in the report
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-shapes" -Dcrystalgraphics.harness.profile=600 \
 *     -Dcrystalgraphics.harness.profile.warmup=300 -Dcrystalgraphics.trace.channels=crystalgraphics,gpu
 * ... --args="--mode=vfx-shapes --device=vulkan" -Dcrystalgraphics.harness.vulkanValidation=false ...
 * // more or fewer shapes
 * ... -Dcrystalgraphics.harness.vfx.shapes=30
 * }</pre>
 */
public final class CgVfxShapesScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxShapes");
    private static final int SHAPES = Integer.getInteger("crystalgraphics.harness.vfx.shapes", 30);

    private final CgVfxSystem vfx = new CgVfxSystem();

    private static CgVfxLook look() {
        CgVfxLook.Builder look = CgVfxLook.builder(CgVfxBurst.SCHEMA);
        for (int s = 1; s <= SHAPES; s++) {
            CgVfxEmitter.Builder e = CgVfxEmitter.builder("shape" + s).capacity(256).rate(60f).shape(CgSphere.of(0.4))
                    .launch(0.2f, 1f, 1f).speed(0.5f, 1.5f).life(2f, 3f).size(0.05f, 0.1f).spin(0.5f, 2f);
            // The modules its number spells: each subset is a shape of its own, so each emitter its own pool.
            if ((s & 1) != 0) e.module(new CgVfxModule.Gravity(2f));
            if ((s & 2) != 0) e.module(new CgVfxModule.Drag(0.4f, 0f));
            if ((s & 4) != 0) e.module(new CgVfxModule.Turbulence(1f, 0.8f, 0.5f));
            if ((s & 8) != 0) e.module(new CgVfxModule.Spin(0.5f));
            if ((s & 16) != 0) e.module(new CgVfxModule.Damping(0.3f));
            if ((s & 32) != 0) e.module(new CgVfxModule.LimitSpeed(3f));
            CgVfxEmitter emitter = e.build();
            look.emitter(emitter).layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/spark.shader").on(emitter).build());
        }
        return look.build();
    }

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 3f, 14f);
        ctx.getCamera3D().setPitch(-8f);
        CgVfxLook look = look();
        for (int i = 0; i < 3; i++) vfx.play(new CgVfxBurst(look, (i - 1) * 4.0, 1.0, 0.0)).ground(0f);
        LOG.info("[vfx-shapes] {} shapes, three effects of each", SHAPES);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        vfx.update(frame.getElapsedTime());
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        vfx.delete();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
