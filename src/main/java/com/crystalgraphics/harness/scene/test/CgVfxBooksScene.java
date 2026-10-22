package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The books against the GPU: the CPU books ({@code CgVfxEmitterInstance.finished()}) must never close a slot while its
 * particles live. Each frame is {@value #FRAME_SECONDS} s, so the books tick many times to the GPU's one step. After the
 * pools record, every pool's records are read back and each slot's opening noted; a slot whose opening changes between
 * two frames was closed between them, and any particle of it the first frame's step left with more life than the second
 * frame's step is one the books dropped alive. Bursts, a rate stopped part way, a loop of long lives and a parent whose
 * deaths spawn children play, several a frame. Scene id {@code vfx-books}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-books"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-books" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-books --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-books] PASS} or {@code FAIL}; run it at every forced tier and on {@code vulkan}.</p>
 */
public final class CgVfxBooksScene implements InteractiveSceneLifecycle {

    private static final float FRAME_SECONDS = 0.2f;
    /** Frames that play new effects, then frames for every one to finish, then frames for the last readbacks. */
    private static final int PLAYING = 40, FINISHING = 40, DRAIN = 30;
    /** Within this of a step's length a particle may die inside it: a float age against a float life. */
    private static final float EPSILON = 1e-3f;

    private static final CgVfxEmitter CHILD = CgVfxEmitter.builder("books_child").capacity(400).life(0.8f, 1.6f)
            .speed(0.5f, 1f).build();
    private static final CgVfxEmitter BURST = CgVfxEmitter.builder("books_burst").state(CgVfxEmitterState.ONCE)
            .capacity(80).burst(0f, 60).life(0.3f, 2.5f).speed(1f, 3f).shape(CgSphere.of(0.5)).build();
    private static final CgVfxEmitter RATE = CgVfxEmitter.builder("books_rate").capacity(300).rate(80f).life(0.5f, 1.5f)
            .speed(0.5f, 2f).build();
    private static final CgVfxEmitter LONG = CgVfxEmitter.builder("books_long").state(CgVfxEmitterState.once(1f))
            .capacity(200).rate(50f).life(2f, 3f).speed(0.2f, 0.5f).build();
    private static final CgVfxEmitter PARENT = CgVfxEmitter.builder("books_parent").state(CgVfxEmitterState.ONCE)
            .capacity(40).burst(0f, 30).life(0.6f, 1f).speed(1f, 2f).event(CgVfxEvent.onDeath().spawn(CHILD, 2).maxPerFrame(40)).build();
    private static final CgVfxLook[] LOOKS = {
            CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(BURST).build(),
            CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(RATE).build(),
            CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(LONG).build(),
            CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(PARENT).emitter(CHILD).build()};

    /** One frame's reading of a pool: each slot's opening when its step was recorded, and its particles' life left. */
    private static final class Reading {
        final long[] openings;
        float[] lifeLeft;
        int live = -1;
        ByteBuffer records;
        CgVfxRecord layout;

        Reading(long[] openings) {
            this.openings = openings;
        }

        boolean answered() {
            return live >= 0 && records != null;
        }
    }

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final List<CgVfxSystemInstance<?>> playing = new ArrayList<>();
    /** Per frame, per pool. */
    private final List<Map<CgVfxParticlePool, Reading>> readings = new ArrayList<>();
    private final List<CgRequest> requests = new ArrayList<>();
    private CgRenderStage.Registration watch;
    private double clock;
    private int frame, closesChecked, particlesChecked, slotsSeen;
    private float tightest = Float.POSITIVE_INFINITY;
    private String failure;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 3f, 12f);
        // After the pools record their step: each pool's records read back, its slots' openings noted.
        watch = CgRenderStage.WORLD_OPAQUE.register(Integer.MAX_VALUE, this::watch);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        if (frame < PLAYING) {
            for (int i = 0; i < LOOKS.length; i++) {
                if ((frame + i) % 2 != 0) continue;
                playing.add(vfx.play(new CgVfxSystemInstance<>(LOOKS[i], i * 3 - 4.5, 1, 0)));
            }
            // The rate's open loop is stopped part way: what it spawned must still play out.
            for (CgVfxSystemInstance<?> s : playing) if (s.age() > 1.3f && s.look() == LOOKS[1]) s.stop();
        } else if (frame == PLAYING) {
            for (CgVfxSystemInstance<?> s : playing) s.stop();
        }
        clock += FRAME_SECONDS;
        vfx.update(clock);
        vfx.submit(CgWorldRenderer.get());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        frame++;
        check();
        if (frame >= PLAYING + FINISHING + DRAIN || frame >= PLAYING + FINISHING && answered()) {
            check();
            report();
            running = false;
        }
    }

    private void watch(CgStageFrame stage) {
        Map<CgVfxParticlePool, Reading> now = new HashMap<>();
        for (CgVfxParticlePool pool : CgVfxParticlePool.pools()) {
            long[] openings = new long[pool.slotCount()];
            for (int s = 0; s < openings.length; s++) openings[s] = pool.opening(s);
            Reading r = new Reading(openings);
            r.layout = pool.record();
            now.put(pool, r);
            if (pool.records() == null || pool.storage() == 0) {
                r.live = 0;
                r.records = ByteBuffer.allocate(0);
                continue;
            }
            requests.add(stage.recording().readback(pool.live(), 0, 4, data -> r.live = data.getInt(0)));
            requests.add(stage.recording().readback(pool.records(), 0, (long) pool.storage() * r.layout.bytes(),
                    data -> r.records = copy(data)));
        }
        readings.add(now);
    }

    /** Every frame k answered and k + 1 noted: the slots closed between them against what k's step left alive. */
    private void check() {
        for (int k = 0; k + 1 < readings.size(); k++) {
            Map<CgVfxParticlePool, Reading> at = readings.get(k), next = readings.get(k + 1);
            if (at == null) continue;
            boolean all = true;
            for (Reading r : at.values()) all &= r.answered();
            if (!all) continue;
            for (Map.Entry<CgVfxParticlePool, Reading> e : at.entrySet()) {
                Reading r = e.getValue(), after = next.get(e.getKey());
                left(r);
                for (int s = 0; s < r.openings.length; s++) {
                    if (r.openings[s] == 0) continue;
                    slotsSeen++;
                    long later = after == null || s >= after.openings.length ? 0 : after.openings[s];
                    if (later == r.openings[s]) continue;
                    closesChecked++;
                    float left = s < r.lifeLeft.length ? r.lifeLeft[s] : 0f;
                    tightest = Math.min(tightest, FRAME_SECONDS - left);
                    if (left > FRAME_SECONDS + EPSILON) {
                        fail("frame " + (k + 1) + ": " + "pool " + e.getKey() + "'s slot " + s + " closed with a particle of "
                                + left + " s of life left, past the " + FRAME_SECONDS + " s step after it");
                    }
                }
            }
            readings.set(k, null);
        }
    }

    /** The most life any particle of each slot has left after the frame's step. */
    private void left(Reading r) {
        if (r.lifeLeft != null) return;
        r.lifeLeft = new float[r.openings.length];
        for (int i = 0; i < r.live; i++) {
            int slot = r.layout.i(r.records, i, CgVfxRecord.SLOT);
            float left = r.layout.f(r.records, i, CgVfxRecord.LIFE) - r.layout.f(r.records, i, CgVfxRecord.AGE);
            if (slot < 0 || slot >= r.lifeLeft.length) continue;
            particlesChecked++;
            r.lifeLeft[slot] = Math.max(r.lifeLeft[slot], left);
        }
    }

    private boolean answered() {
        for (CgRequest r : requests) if (r.status() == CgRequest.Status.PENDING) return false;
        return true;
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(data.order());
        out.put(data.duplicate()).flip();
        return out;
    }

    private void fail(String what) {
        if (failure == null) failure = what;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        int validation = PlatformServiceHarness.validationErrors();
        if (failure == null && closesChecked == 0) failure = "no slot closed: nothing was checked";
        if (failure == null && particlesChecked == 0) failure = "no particle was read back: nothing was checked";
        if (GlErrorChecker.checkAndLog("vfx-books")) {
            System.out.println("[vfx-books] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-books] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-books] PASS on recording: a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[vfx-books] FAIL on " + on + ": " + failure);
        } else {
            System.out.printf("[vfx-books] PASS on %s: %d slot closes under a %.1f s frame, none on a live particle "
                            + "(%d particle readings, %d slot readings; the closest close left %.3f s spare)%n",
                    on, closesChecked, FRAME_SECONDS, particlesChecked, slotsSeen, tightest);
        }
    }

    @Override
    public void dispose() {
        if (watch != null) watch.close();
        vfx.delete();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
