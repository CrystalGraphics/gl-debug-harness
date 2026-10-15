package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.trail.CgVfxTrail;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Trails ({@link CgVfxTrail}) swirling round the origin, a glowing ki streak each: the cost of many trails, their points
 * uploaded once a frame and every strip built on the GPU. Scene id {@code vfx-trails}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-trails"
 * // how many trails (the default):
 * ... -Dcrystalgraphics.harness.trails.count=64
 * // a report: profile-harness-300f/report.txt
 * ... -Dcrystalgraphics.harness.profile=300 -Dcrystalgraphics.harness.profile.warmup=60 -Dcrystalgraphics.trace.channels=crystalgraphics,gpu
 * }</pre>
 */
public final class CgVfxTrailsScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxTrails");

    private static final int COUNT = Integer.getInteger("crystalgraphics.harness.trails.count", 64);
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("harness:trails");
    private static final CgVfxSlot STREAK = CgVfxSlot.trail("harness:trails/streak");
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(CgVfxKit.of("harness:trails")
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/trail/glow.shader").on(STREAK).size(0.12f).build())
            .build()).build();

    private final CgVfxSystem vfx = new CgVfxSystem();

    /** One point swirling round the origin, its streak behind it. */
    private static final class Swirl extends CgVfxEffect<Swirl> {
        private final CgVfxTrail streak = CgVfxTrail.ribbon(0.6f);
        private final float phase, radius, speed;

        Swirl(int k) {
            super(LOOK, 0, 0, 0);
            phase = k * 2.3999632f;
            radius = 1.5f + 2.5f * fraction(k * 0.618034f);
            speed = 1.1f + 0.6f * fraction(k * 0.37f);
        }

        @Override
        protected void tick(float dt) {
            float a = age * speed * 2f + phase;
            streak.tick(dt, Math.cos(a) * radius, 1.5 + Math.sin(age * 1.7f + phase) * 1.2, Math.sin(a) * radius);
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            frame.trail(this, STREAK, streak);
        }
    }

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 6f, 12f);
        ctx.getCamera3D().setPitch(-22f);
        for (int k = 0; k < COUNT; k++) vfx.play(new Swirl(k));
        LOG.info("[vfx-trails] {} trails", COUNT);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        vfx.update(frame.getElapsedTime());
        vfx.submit(world);
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    private static float fraction(float v) {
        return v - (float) Math.floor(v);
    }

    @Override
    public void dispose() {
        vfx.delete();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
