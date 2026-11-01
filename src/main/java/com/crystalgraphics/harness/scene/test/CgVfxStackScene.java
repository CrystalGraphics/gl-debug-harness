package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxField;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxShapeCoalescing;

import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Shared pools draw what a pool of each definition's own does ({@code CgVfxShapeCoalescing}): shapes stacked into a
 * wider one's kernel with what they lack switched off, a Step placing by its pool's spawn shapes, and a table of texture
 * sets each slot names its entry in. Nine definitions are included together and stepped twice (a burst, then a step of
 * what lives): six whose module stacks are some of one wide stack in order, each born on its own spawn shape; two of one
 * shape sampling different vector fields; a force alone, stacked under those lacking the field. Every particle's record
 * is written by id to {@code -Dcrystalgraphics.harness.vfxStack.out}, to be matched against the reference run, each
 * definition in a pool of its own ({@code -Dcrystalgraphics.harness.vfxStack.apart=true}) unstacked; the scene itself
 * checks that each burst was read back whole and how many pools it took. Scene id {@code vfx-stack}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-stack" -Dcrystalgraphics.harness.vfxStack.out=shared.txt
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-stack" -Dcrystalgraphics.vfx.stackShapes=false \
 *     -Dcrystalgraphics.harness.vfxStack.apart=true -Dcrystalgraphics.harness.vfxStack.out=apart.txt
 * }</pre>
 */
public final class CgVfxStackScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CgVfxStackScene.class.getName());
    private static final int PARTICLES = 256, SEED_BITS = 0x5AC4;
    private static final float DT = 0.25f;

    private final List<CgVfxEmitter> emitters = new ArrayList<>();
    private final List<CgVfxParticlePool> pools = new ArrayList<>();
    private final List<Integer> slots = new ArrayList<>();
    private final List<ByteBuffer> read = new ArrayList<>();
    private final List<CgRequest> requests = new ArrayList<>();
    private static final CgVfxField FIELD_A = CgVfxField.of(4, 4, 4, false, (x, y, z, out) -> {
        out[0] = 0.5f + x - 0.3f * z;
        out[1] = 1f - 0.4f * y;
        out[2] = -0.8f + 0.6f * z;
    });
    private static final CgVfxField FIELD_B = CgVfxField.of(4, 4, 4, false, (x, y, z, out) -> {
        out[0] = -1f + 0.2f * y;
        out[1] = 0.3f * x - 0.5f;
        out[2] = 1.2f - 0.4f * x;
    });
    /** Each definition in a pool of its own: the reference a shared run is matched against. */
    private static final boolean APART = Boolean.getBoolean("crystalgraphics.harness.vfxStack.apart");

    private final Object owner = new Object();
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgShape plane = CgPlane.of(0, 1, 0, 6);
        // The wide stack: gravity, drag, a plane to bounce off, a box killing what enters it.
        add("stack-wide", CgSphere.of(1.5), new CgVfxModule.Gravity(9.8f), new CgVfxModule.Drag(0.4f, 0.05f),
                new CgVfxModule.Collide(plane, 0.5f, 0.2f, 0.1f).at(0f, -1f, 0f), new CgVfxModule.Kill(CgBox.of(1, 1, 1), true).at(2f, 0f, 0f));
        add("stack-drag", CgBox.of(1, 0.5, 1), new CgVfxModule.Drag(0.6f, 0f));
        add("stack-gravity", CgCone.of(2, 0.6), new CgVfxModule.Gravity(4f));
        add("stack-gravity-collide", CgCylinder.of(1, 0.5), new CgVfxModule.Gravity(9.8f),
                new CgVfxModule.Collide(plane, 0.8f, 0f, 0f).at(0f, -0.5f, 0f));
        add("stack-drag-kill", CgRing.of(0.5, 1.5, 0.2), new CgVfxModule.Drag(0.2f, 0.1f),
                new CgVfxModule.Kill(CgBox.of(0.5, 2, 0.5), true).at(-1f, 0f, 0f));
        add("stack-none", CgCapsule.of(1, 0.3));
        // One shape on two fields: one pool, its table two sets, each slot's call chosen by its entry; and a force alone
        // stacked under them, lacking the textured kind.
        add("stack-field-a", CgSphere.of(1), new CgVfxModule.VectorField(FIELD_A, CgBox.of(3, 3, 3), 3f, 1f), new CgVfxModule.Force(0f, 1f, 0f));
        add("stack-field-b", CgSphere.of(1), new CgVfxModule.VectorField(FIELD_B, CgBox.of(3, 3, 3), 3f, 1f), new CgVfxModule.Force(0f, 1f, 0f));
        add("stack-force", CgBox.of(0.5, 0.5, 0.5), new CgVfxModule.Force(1f, 0f, 0f));
        CgVfxShapeCoalescing.loaded().include(emitters);
        for (CgVfxEmitter e : emitters) CgVfxParticlePool.precache(e);
    }

    private void add(String name, CgShape shape, CgVfxModule... modules) {
        CgVfxEmitter.Builder b = CgVfxEmitter.builder(name).capacity(PARTICLES).burst(0f, PARTICLES).shape(shape)
                .speed(1f, 4f).life(4f, 5f).size(0.1f, 0.2f);
        for (CgVfxModule m : modules) b.module(m);
        emitters.add(b.build());
        read.add(null);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= 2) {
            CgRecording rec = new CgRecording();
            if (frame == 1) {
                for (CgVfxEmitter e : emitters) {
                    CgVfxParticlePool pool = CgVfxParticlePool.of(APART ? e : owner, e);
                    slots.add(pool.open(e, PARTICLES));
                    pools.add(pool);
                }
            }
            List<CgVfxParticlePool> distinct = distinct();
            for (CgVfxParticlePool pool : distinct) pool.beginStep(DT, 0f, 0f, 0f);
            for (int e = 0; e < emitters.size(); e++) {
                CgVfxParticlePool pool = pools.get(e);
                pool.instance(slots.get(e), SEED_BITS + e, 1f, Float.NaN, origin());
                // The burst at the first step's start; the second steps what it left.
                if (frame == 1) pool.spawn(slots.get(e), 0, PARTICLES, PARTICLES, 0f, DT, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
            }
            for (CgVfxParticlePool pool : distinct) pool.endStep();
            CgVfxParticlePool.record(rec, distinct);
            if (frame == 2) {
                for (int e = 0; e < emitters.size(); e++) {
                    int at = e;
                    CgVfxParticlePool pool = pools.get(e);
                    requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * pool.record().bytes(),
                            data -> read.set(at, copy(data))));
                }
            }
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = !requests.isEmpty();
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (answered && !read.contains(null)) {
            write();
            running = false;
        } else if (frame > 600) {
            LOGGER.severe("[vfx-stack] FAIL: no readback after 600 frames");
            running = false;
        }
    }

    private List<CgVfxParticlePool> distinct() {
        List<CgVfxParticlePool> out = new ArrayList<>();
        for (CgVfxParticlePool p : pools) if (!out.contains(p)) out.add(p);
        return out;
    }

    /** Each definition's particles by id: its core words, then each tail attribute its layout holds, by name. */
    private void write() {
        String path = System.getProperty("crystalgraphics.harness.vfxStack.out", "vfx-stack.txt");
        boolean whole = true;
        try (PrintWriter out = new PrintWriter(path)) {
            for (int e = 0; e < emitters.size(); e++) {
                CgVfxParticlePool pool = pools.get(e);
                CgVfxRecord layout = pool.record();
                ByteBuffer records = read.get(e);
                TreeMap<Integer, String> byId = new TreeMap<>();
                for (int r = 0; r < pool.storage(); r++) {
                    if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || layout.i(records, r, CgVfxRecord.SLOT) != slots.get(e)) continue;
                    StringBuilder line = new StringBuilder();
                    int[] core = {CgVfxRecord.POSITION, CgVfxRecord.POSITION + 1, CgVfxRecord.POSITION + 2, CgVfxRecord.AGE,
                            CgVfxRecord.VELOCITY, CgVfxRecord.VELOCITY + 1, CgVfxRecord.VELOCITY + 2, CgVfxRecord.SIZE,
                            CgVfxRecord.LIFE, CgVfxRecord.SEED, CgVfxRecord.SPIN, CgVfxRecord.SPIN_RATE, CgVfxRecord.HEAT};
                    for (int w : core) line.append(' ').append(layout.f(records, r, w));
                    for (CgVfxRecord.Tail t : layout.tail()) {
                        for (int c = 0; c < t.components(); c++) line.append(' ').append(t).append(c).append('=')
                                .append(layout.f(records, r, layout.word(t) + c));
                    }
                    byId.put(layout.i(records, r, CgVfxRecord.ID), line.toString());
                }
                if (byId.size() == 0) whole = false;
                for (var p : byId.entrySet()) out.println(emitters.get(e).name() + " " + p.getKey() + p.getValue());
                LOGGER.info("[vfx-stack] " + emitters.get(e).name() + ": " + byId.size() + " particles, pool " + pool.shape().key());
            }
        } catch (Exception ex) {
            LOGGER.severe("[vfx-stack] FAIL: " + ex);
            return;
        }
        LOGGER.info("[vfx-stack] " + (whole ? "PASS" : "FAIL") + ": " + distinct().size() + " pools for " + emitters.size()
                + " definitions, written to " + path);
    }

    private static CgVfxInstanceView origin() {
        return new CgVfxInstanceView() {
            public double originX() { return 0.0; }
            public double originY() { return 4.0; }
            public double originZ() { return 0.0; }
            public float time() { return 0f; }
            public float sourceX() { return 0f; }
            public float sourceY() { return 0f; }
            public float sourceZ() { return 0f; }
        };
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
        out.put(data);
        out.flip();
        return out;
    }

    @Override
    public void dispose() {
        CgVfxParticlePool.release(owner);
        if (APART) for (CgVfxEmitter e : emitters) CgVfxParticlePool.release(e);
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
