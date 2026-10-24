package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The gate for particles born on another emitter's ({@code CgVfxSpawnFrom}, Cascade's {@code LocationEmitter}): three
 * sources publishing at tier 0, each read by an emitter at tier 1 that bursts once, checked from its records against the
 * sources' seeded particles.
 * <ul>
 *   <li><b>Random</b> from still particles: every one born exactly where a source particle is, on many of them.</li>
 *   <li><b>Sequential</b> from moving particles, inheriting half their velocity: as many as the source holds, each
 *       source particle's half velocity taken once.</li>
 *   <li><b>Empty</b>: a source with no particle leaves each where its emitter put it, at the origin.</li>
 *   <li><b>Follow</b> (Cascade's {@code LocationEmitterDirect}): each reader on the moving source particle of its own id
 *       every step, its place, velocity, age and life the source's at the end; a reader whose source died, gone with
 *       it.</li>
 * </ul>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spawn-from"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spawn-from" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spawn-from --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-spawn-from] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxSpawnFromScene implements InteractiveSceneLifecycle {

    /** Frames before seeding, then steps, then frames for the answers. */
    private static final int WARM = 10, STEPS = 6, DRAIN = 40;
    private static final float DT = 1f / 60f;
    private static final int N = 48, SEED_BITS = 0x5F12;
    private static final int STILL = 0, MOVING = 1, EMPTY = 2, FOLLOW = 3, GROUPS = 4;
    private static final String[] NAMES = {"still", "moving", "empty", "follow"};
    /** A followed particle of each four lives this long, dying at the third step: its reader with it. */
    private static final float SHORT_LIFE = 2.5f * (1f / 60f);
    private static final float INHERIT = 0.5f, TOLERANCE = 1e-4f;

    private final CgVfxEmitter[] sources = new CgVfxEmitter[GROUPS];
    private final CgVfxEmitter[] readers = new CgVfxEmitter[GROUPS];
    private final CgVfxParticlePool[] sourcePools = new CgVfxParticlePool[GROUPS], readerPools = new CgVfxParticlePool[GROUPS];
    private final int[] sourceSlots = new int[GROUPS], readerSlots = new int[GROUPS];
    /** An emitter born on its own particles: half its capacity seeded, half spawned on them at the second step. */
    private final CgVfxEmitter itself = CgVfxEmitter.builder("spawn-from-itself").state(CgVfxEmitterState.ONCE).capacity(N)
            .speed(0f, 0f).life(100f, 100f).size(0.1f, 0.1f, 1f).publishes(true)
            .spawnFrom(CgVfxSpawnFrom.ofItself(CgVfxSpawnFrom.Selection.RANDOM)).build();
    private CgVfxParticlePool itselfPool;
    private int itselfSlot;
    private final List<CgVfxParticlePool> distinct = new ArrayList<>();
    private final Map<CgVfxParticlePool, ByteBuffer> records = new IdentityHashMap<>();
    private final Map<CgVfxParticlePool, Integer> live = new IdentityHashMap<>();
    private final List<CgRequest> requests = new ArrayList<>();
    /** Each seeded particle's place (the still source's) and velocity (the moving source's). */
    private final float[][] places = new float[N][], velocities = new float[N][];
    private int frame, checked;
    private String failure;
    private boolean compared, running = true;

    public CgVfxSpawnFromScene() {
        for (int g = 0; g < GROUPS; g++) {
            sources[g] = CgVfxEmitter.builder("spawn-from-source-" + NAMES[g]).state(CgVfxEmitterState.ONCE).capacity(N)
                    .life(100f, 100f).size(0.1f, 0.1f, 1f).publishes(true).build();
        }
        readers[STILL] = reader(NAMES[STILL], CgVfxSpawnFrom.of("still", CgVfxSpawnFrom.Selection.RANDOM));
        readers[MOVING] = reader(NAMES[MOVING], CgVfxSpawnFrom.of("moving", CgVfxSpawnFrom.Selection.SEQUENTIAL)
                .inherit(INHERIT, 0f));
        readers[EMPTY] = reader(NAMES[EMPTY], CgVfxSpawnFrom.of("empty", CgVfxSpawnFrom.Selection.RANDOM));
        readers[FOLLOW] = reader(NAMES[FOLLOW], CgVfxSpawnFrom.following("follow"));
    }

    private static CgVfxEmitter reader(String name, CgVfxSpawnFrom from) {
        return CgVfxEmitter.builder("spawn-from-reader-" + name).state(CgVfxEmitterState.ONCE).capacity(N)
                .burst(0f, N).speed(0f, 0f).life(100f, 100f).size(0.1f, 0.1f, 1f).spawnFrom(from.at(0)).build();
    }

    @Override
    public void init(HarnessContext ctx) {
        for (int g = 0; g < GROUPS; g++) {
            CgVfxParticlePool.prepare(sources[g]);
            CgVfxParticlePool.prepare(readers[g], 1);
        }
        CgVfxParticlePool.prepare(itself);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame < WARM) return;
        if (frame <= WARM + STEPS + 1) {
            CgRecording rec = new CgRecording();
            if (frame == WARM) seed(rec);
            else step(rec, frame == WARM + 1);
            if (frame == WARM + STEPS + 1) readBack(rec);
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && records.size() == distinct.size()) {
            compare();
            compared = true;
        }
        if (compared || frame > WARM + STEPS + DRAIN) {
            report();
            CgVfxParticlePool.release(this);
            running = false;
        }
    }

    private void seed(CgRecording rec) {
        for (int g = 0; g < GROUPS; g++) {
            sourcePools[g] = CgVfxParticlePool.of(this, sources[g]);
            sourceSlots[g] = sourcePools[g].open(sources[g], N);
            if (!distinct.contains(sourcePools[g])) distinct.add(sourcePools[g]);
        }
        for (int g = 0; g < GROUPS; g++) {
            readerPools[g] = CgVfxParticlePool.of(this, readers[g], 1);
            readerSlots[g] = readerPools[g].open(readers[g], N);
            readerPools[g].readFrom(readerSlots[g], sourcePools[g], sourceSlots[g]);
            if (!distinct.contains(readerPools[g])) distinct.add(readerPools[g]);
        }
        itselfPool = CgVfxParticlePool.of(this, itself);
        itselfSlot = itselfPool.open(itself, N);
        itselfPool.readFrom(itselfSlot, itselfPool, itselfSlot);
        distinct.add(itselfPool);
        Random random = new Random(1203);
        for (int id = 0; id < N; id++) {
            places[id] = new float[]{random.nextFloat() * 8f - 4f, random.nextFloat() * 3f, random.nextFloat() * 8f - 4f};
            velocities[id] = new float[]{random.nextFloat() * 4f - 2f, random.nextFloat() * 4f - 2f, random.nextFloat() * 4f - 2f};
        }
        List<CgVfxParticlePool> seeded = new ArrayList<>();
        for (int g = 0; g < 3; g++) if (!seeded.contains(sourcePools[g])) seeded.add(sourcePools[g]);
        for (CgVfxParticlePool pool : seeded) {
            CgVfxRecord layout = pool.record();
            ByteBuffer packed = ByteBuffer.allocateDirect(3 * N * layout.bytes()).order(ByteOrder.nativeOrder());
            int at = 0;
            for (int g : new int[]{STILL, MOVING, FOLLOW}) {
                if (sourcePools[g] != pool) continue;
                for (int id = 0; id < N; id++, at++) {
                    float[] p = places[id], v = g == STILL ? new float[3] : velocities[id];
                    float age = g == FOLLOW ? 0.01f * id : 0f, life = g == FOLLOW && id % 4 == 0 ? SHORT_LIFE : 100f;
                    if (g == FOLLOW && id % 4 == 0) age = 0f;
                    layout.put(packed, at, p[0], p[1], p[2], v[0], v[1], v[2], age, life, 0.1f, 0.5f, id, sourceSlots[g]);
                }
            }
            pool.seed(rec, packed, at);
        }
        CgVfxRecord layout = itselfPool.record();
        ByteBuffer packed = ByteBuffer.allocateDirect(N / 2 * layout.bytes()).order(ByteOrder.nativeOrder());
        for (int id = 0; id < N / 2; id++) {
            float[] p = places[id];
            layout.put(packed, id, p[0], p[1], p[2], 0f, 0f, 0f, 0f, 100f, 0.1f, 0.5f, id, itselfSlot);
        }
        itselfPool.seed(rec, packed, N / 2);
    }

    /** Every slot stepped; the readers burst on the first. */
    private void step(CgRecording rec, boolean first) {
        for (CgVfxParticlePool pool : distinct) {
            pool.beginStep(DT, 0f, 0f, 0f);
            for (int g = 0; g < GROUPS; g++) {
                if (sourcePools[g] == pool) pool.instance(sourceSlots[g], SEED_BITS, 1f, Float.NaN, ORIGIN);
                if (readerPools[g] == pool) {
                    pool.instance(readerSlots[g], SEED_BITS + g, 1f, Float.NaN, ORIGIN);
                    if (first) pool.spawn(readerSlots[g], 0, N);
                }
            }
            if (pool == itselfPool) {
                pool.instance(itselfSlot, SEED_BITS + GROUPS, 1f, Float.NaN, ORIGIN);
                // The second step: the first has published the seeded half.
                if (frame == WARM + 2) pool.spawn(itselfSlot, 0, N / 2);
            }
            pool.endStep();
        }
        CgVfxParticlePool.record(rec, distinct);
    }

    private void readBack(CgRecording rec) {
        for (CgVfxParticlePool pool : distinct) {
            requests.add(rec.readback(pool.liveBuffer(), pool.liveOffset(), 4, data -> live.put(pool, data.getInt(0))));
            requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * pool.record().bytes(),
                    data -> records.put(pool, copy(data))));
        }
    }

    /** Group {@code g}'s reader records at the end, all {@code N} of them alive. */
    private List<Integer> rows(int g) {
        return rows(g, N);
    }

    /** Group {@code g}'s reader records at the end, {@code alive} of them. */
    private List<Integer> rows(int g, int alive) {
        CgVfxParticlePool pool = readerPools[g];
        ByteBuffer data = records.get(pool);
        List<Integer> rows = new ArrayList<>();
        for (int r = 0; r < live.get(pool); r++) {
            if (pool.record().i(data, r, CgVfxRecord.SLOT) == readerSlots[g]) rows.add(r);
        }
        if (rows.size() != alive) fail(rows.size() + " " + NAMES[g] + " readers alive, not " + alive);
        return rows;
    }

    private void compare() {
        still();
        moving();
        empty();
        follow();
        itself();
    }

    /**
     * The emitter born on its own particles: all {@code N} alive, each on one of the seeded half's places, the spawned
     * half doubling several of them.
     */
    private void itself() {
        CgVfxRecord layout = itselfPool.record();
        ByteBuffer data = records.get(itselfPool);
        float[][] seededPlaces = Arrays.copyOf(places, N / 2);
        int[] on = new int[N / 2];
        int alive = 0;
        for (int r = 0; r < live.get(itselfPool); r++) {
            if (layout.i(data, r, CgVfxRecord.SLOT) != itselfSlot) continue;
            alive++;
            float[] at = vector(layout, data, r, CgVfxRecord.POSITION);
            int place = nearest(seededPlaces, at);
            if (place < 0) {
                fail("a particle born on its own emitter's is at " + text(at) + ", on none of them");
                return;
            }
            on[place]++;
            checked++;
        }
        int shared = 0;
        for (int n : on) if (n > 1) shared++;
        if (alive != N) fail(alive + " particles born on their own emitter's alive, not " + N);
        else if (shared < 4) fail("the particles born on their own emitter's share " + shared + " of its places");
    }

    /**
     * Each follower on the source particle of its id: its place, velocity, age and life the source's after the last step;
     * every fourth source died at the third step, and its follower with it.
     */
    private void follow() {
        CgVfxParticlePool sp = sourcePools[FOLLOW];
        CgVfxRecord sl = sp.record(), rl = readerPools[FOLLOW].record();
        ByteBuffer sd = records.get(sp), rd = records.get(readerPools[FOLLOW]);
        int[] source = new int[N];
        Arrays.fill(source, -1);
        for (int r = 0; r < live.get(sp); r++) {
            if (sl.i(sd, r, CgVfxRecord.SLOT) == sourceSlots[FOLLOW]) source[sl.i(sd, r, CgVfxRecord.ID)] = r;
        }
        for (int r : rows(FOLLOW, N - N / 4)) {
            int id = rl.i(rd, r, CgVfxRecord.ID), s = id >= 0 && id < N ? source[id] : -1;
            if (s < 0) {
                fail("follower " + id + " outlived its source");
                return;
            }
            for (int w : new int[]{CgVfxRecord.POSITION, CgVfxRecord.POSITION + 1, CgVfxRecord.POSITION + 2, CgVfxRecord.VELOCITY,
                    CgVfxRecord.VELOCITY + 1, CgVfxRecord.VELOCITY + 2, CgVfxRecord.AGE, CgVfxRecord.LIFE}) {
                float got = rl.f(rd, r, w), want = sl.f(sd, s, w);
                if (Math.abs(got - want) > TOLERANCE * (1f + Math.abs(want))) {
                    fail("follower " + id + "'s word " + w + " is " + got + ", its source's " + want);
                    return;
                }
            }
            checked++;
        }
    }

    /** Each where a still source particle is; at least a quarter of them used. */
    private void still() {
        CgVfxRecord layout = readerPools[STILL].record();
        ByteBuffer data = records.get(readerPools[STILL]);
        Set<Integer> used = new HashSet<>();
        for (int r : rows(STILL)) {
            float[] at = vector(layout, data, r, CgVfxRecord.POSITION);
            int match = nearest(places, at);
            if (match < 0) fail("a random reader at " + text(at) + " is on no source particle");
            used.add(match);
            checked++;
        }
        if (used.size() < N / 4) fail("random readers sat on " + used.size() + " of " + N + " source particles");
    }

    /** Each source particle's half velocity taken exactly once. */
    private void moving() {
        CgVfxRecord layout = readerPools[MOVING].record();
        ByteBuffer data = records.get(readerPools[MOVING]);
        float[][] wanted = new float[N][];
        for (int id = 0; id < N; id++) {
            wanted[id] = new float[]{velocities[id][0] * INHERIT, velocities[id][1] * INHERIT, velocities[id][2] * INHERIT};
        }
        int[] taken = new int[N];
        for (int r : rows(MOVING)) {
            float[] v = vector(layout, data, r, CgVfxRecord.VELOCITY);
            int match = nearest(wanted, v);
            if (match < 0) fail("a sequential reader moves " + text(v) + ", no source particle's half velocity");
            else taken[match]++;
            checked++;
        }
        for (int id = 0; id < N; id++) {
            if (taken[id] != 1) fail("source particle " + id + "'s velocity was taken " + taken[id] + " times, not once");
        }
    }

    /** With nothing published, each stays where its emitter put it. */
    private void empty() {
        CgVfxRecord layout = readerPools[EMPTY].record();
        ByteBuffer data = records.get(readerPools[EMPTY]);
        for (int r : rows(EMPTY)) {
            float[] at = vector(layout, data, r, CgVfxRecord.POSITION);
            if (Math.abs(at[0]) + Math.abs(at[1]) + Math.abs(at[2]) > TOLERANCE) {
                fail("a reader of an empty source is at " + text(at) + ", not its emitter's origin");
            }
            checked++;
        }
    }

    private static float[] vector(CgVfxRecord layout, ByteBuffer data, int r, int word) {
        return new float[]{layout.f(data, r, word), layout.f(data, r, word + 1), layout.f(data, r, word + 2)};
    }

    /** The index of the vector in {@code among} within tolerance of {@code v}, or -1. */
    private static int nearest(float[][] among, float[] v) {
        for (int i = 0; i < among.length; i++) {
            float[] a = among[i];
            boolean near = true;
            for (int k = 0; k < 3; k++) near &= Math.abs(a[k] - v[k]) <= TOLERANCE * (1f + Math.abs(a[k]));
            if (near) return i;
        }
        return -1;
    }

    private static String text(float[] v) {
        return "(" + v[0] + ", " + v[1] + ", " + v[2] + ")";
    }

    private static final CgVfxInstanceView ORIGIN = new CgVfxInstanceView() {
        public double originX() { return 0.5; }
        public double originY() { return 70.0; }
        public double originZ() { return -0.5; }
        public float time() { return 0f; }
        public float sourceX() { return 0f; }
        public float sourceY() { return 0f; }
        public float sourceZ() { return 0f; }
    };

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
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
        if (GlErrorChecker.checkAndLog("vfx-spawn-from")) {
            System.out.println("[vfx-spawn-from] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-spawn-from] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-spawn-from] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-spawn-from] FAIL on " + on + ": the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-spawn-from] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-spawn-from] PASS on " + on + ": " + checked + " particles: random on still sources to "
                    + TOLERANCE + ", sequential taking each moving source's half velocity once, an empty source's at the"
                    + " origin, followers on their sources, an emitter on its own");
        }
    }

    @Override public void dispose() {}

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
