package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnPerUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Cascade ribbon trails swirling round the origin, a glowing ki streak each behind a source its effect moves: the cost of
 * many trails, each spawned on the CPU and generated on the GPU. Scene id {@code vfx-trails}.
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

    private static final int COUNT = Integer.getInteger("crystalgraphics.harness.trails.count", 64), POINTS = 96;
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("harness:trails");
    /** A particle each 0.12 blocks its source moves, living 0.6 s, 0.12 either side tapering to a sixth. */
    private static final CgVfxEmitter STREAK = CgVfxEmitter.builder("streak").capacity(POINTS)
            .ratePerBlock(CgVfxSpawnPerUnit.every(1f, 0.12f)).maxStep(3f).speed(0f, 0f).life(0.6f, 0.6f)
            .size(0.12f, 0.12f, 1f).size(CgKeyframes.start(0f, 1f).to(1f, 0.15f, CgEasings.LINEAR).build())
            .renderer(CgVfxRenderers.ribbon("glow").material(CgMaterialDefinition.of("harness:materials/trails-glow",
                            "crystalgraphics:shaders/vfx/trail/glow.shader").usage(CgMaterialUsage.RIBBONS).build())
                    .field("trail", Map.of("max_particles", POINTS, "tiling_distance", 1.0)).sortOrderHint(192).build())
            .build();
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).emitter(STREAK).build();

    private final CgVfxSystem vfx = new CgVfxSystem();

    /** One point swirling round the origin, its streak behind it. */
    private static final class Swirl extends CgVfxEffect<Swirl> {
        private final CgVfxEmitterInstance streak;
        private final float phase, radius, speed;

        Swirl(int k) {
            super(LOOK, 0, 0, 0);
            phase = k * 2.3999632f;
            radius = 1.5f + 2.5f * fraction(k * 0.618034f);
            speed = 1.1f + 0.6f * fraction(k * 0.37f);
            streak = new CgVfxEmitterInstance(look().emitter("streak"), seed);
            streak.start((float) Math.cos(phase) * radius, 1.5f + (float) Math.sin(phase) * 1.2f, (float) Math.sin(phase) * radius);
        }

        @Override
        protected void tick(float dt) {
            float a = age * speed * 2f + phase;
            streak.moveTo((float) Math.cos(a) * radius, 1.5f + (float) Math.sin(age * 1.7f + phase) * 1.2f,
                    (float) Math.sin(a) * radius);
            tick(streak, dt);
        }

        @Override
        protected void submit(CgVfxFrame frame) {
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
