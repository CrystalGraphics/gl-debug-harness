package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.element.CgVfxExplosion;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Particles alone: the explosion kit ({@link CgVfxExplosion}: billows, debris, embers, ink) bursting on the showcase's
 * floor every few seconds, in three spots, with nothing else drawn. For judging particle motion, as P switches the
 * particle step between the old 120 Hz and the new 60 Hz. Scene id {@code vfx-particles}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-particles"
 * // P: particles at 60 Hz (new) or 120 Hz (old), on the HUD under "VFX sim"
 * }</pre>
 */
public final class CgVfxParticlesScene implements InteractiveSceneLifecycle {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics.VfxParticles");

    private static final CgVfxSchema SCHEMA = new CgVfxSchema();
    private static final CgVfxExplosion BLAST = new CgVfxExplosion(SCHEMA, "blast");
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).add(BLAST).build();
    /** Seconds between bursts, and where they go round, in front of the showcase's grid. */
    private static final float PERIOD = 2.5f;
    private static final float[][] SPOTS = {{-7f, 12f}, {0f, 15f}, {7f, 12f}};

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private final CgVfxSystem vfx = new CgVfxSystem();
    private float nextBurst;
    private int bursts;

    /** One burst of the kit's emitters at its origin, on a floor at the origin's height; dies when they all have. */
    private static final class Burst extends CgVfxEffect {
        private final List<CgVfxEmitterInstance> emitters = new ArrayList<>();

        Burst(double x, double y, double z) {
            super(LOOK, x, y, z);
            List<CgVfxEmitter> kinds = LOOK.emitters();
            for (int i = 0; i < kinds.size(); i++) {
                CgVfxEmitterInstance emitter = new CgVfxEmitterInstance(kinds.get(i), seed + i * 0.137f);
                emitter.start(0f, 0.6f, 0f);
                emitter.ground(0f);
                emitters.add(emitter);
            }
        }

        @Override
        protected void tick(float dt) {
            boolean done = true;
            for (int i = 0; i < emitters.size(); i++) {
                tick(emitters.get(i), dt);
                done &= emitters.get(i).finished();
            }
            if (done && age > 1f) die();
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            for (int i = 0; i < emitters.size(); i++) frame.particles(this, emitters.get(i));
        }
    }

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 7f, 34f);
        ctx.getCamera3D().setPitch(-12f);
        ctx.getCamera3D().setMoveSpeed(6f);
        LOG.info("[vfx-particles] a blast every {}s in three spots; P switches the particle step (60 Hz new, 120 Hz old)", PERIOD);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        float seconds = (float) frame.getElapsedTime();
        CgWorldRenderer world = CgWorldRenderer.get();
        if (seconds >= nextBurst) {
            float[] spot = SPOTS[bursts++ % SPOTS.length];
            vfx.play(new Burst(spot[0], 0.0, spot[1]));
            nextBurst = seconds + PERIOD;
        }
        vfx.update(seconds);
        vfx.submit(world);
        stage.submitStage(world, 0.0, 0.0, 0.0, ctx.getCamera3D().getPosX(), ctx.getCamera3D().getPosY(),
                ctx.getCamera3D().getPosZ());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override
    public void dispose() {
        vfx.delete();
        stage.delete();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
