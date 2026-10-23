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
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxTrailHeads;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxTrailSource;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnPerUnit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The gate for trails that follow another emitter's particles ({@code CgVfxTrailHeads} and the pool's heads kernel,
 * Cascade's TrailSource with method particle): three moving particles published at tier 0, three trails at tier 1
 * each bound to one of them on the GPU, stepped {@value #STEPS} times and once more. A Java twin runs {@link CgVfxTrailSource} along
 * each trail's source particle's path and holds the trail's records to it: every spawn's place, tangent, up and tiled
 * distance, the refits of the chains before, and the oldest killed past {@code max_particles}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-trail-heads"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-trail-heads" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-trail-heads --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-trail-heads] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxTrailHeadsScene implements InteractiveSceneLifecycle {

    private static final int WARM = 10, STEPS = 30, DRAIN = 40, SOURCES = 3, TRAILS = 3, MOST = 12, CAPACITY = 64;
    private static final float TICK = 0.05f, SPACING = 0.2f, TOLERANCE = 2e-3f;
    private static final int SEED_BITS = 0x7A11;

    private final CgVfxEmitter source = CgVfxEmitter.builder("heads-source").state(CgVfxEmitterState.ONCE).capacity(SOURCES)
            .life(100f, 100f).size(0.1f, 0.1f, 1f).publishes(true).build();
    private final CgVfxRibbonRendererProperties renderer = (CgVfxRibbonRendererProperties) CgVfxRenderers.ribbon("heads-trail")
            .field("trail", Map.of("max_trails", TRAILS, "max_particles", MOST, "tessellation", Map.of("distance", 0.1))).build();
    private final CgVfxEmitter trail = CgVfxEmitter.builder("heads-trail").state(CgVfxEmitterState.ONCE).capacity(CAPACITY)
            .life(100f, 100f).size(0.1f, 0.1f, 1f).ratePerBlock(CgVfxSpawnPerUnit.every(1f, SPACING))
            .spawnFrom(CgVfxSpawnFrom.trailing("heads-source", CgVfxSpawnFrom.Selection.RANDOM).at(0)).renderer(renderer).build();
    /** Each source particle's start and velocity: it moves along a line, a step at a time. */
    private final float[][] starts = {{0f, 0f, 0f}, {1f, 2f, -1f}, {-2f, 1f, 1f}};
    private final float[][] velocities = {{4f, 0f, 1f}, {-1f, 3f, 2f}, {2.5f, -2f, -3f}};
    private final List<CgRequest> requests = new ArrayList<>();
    private CgVfxParticlePool sourcePool, trailPool;
    private int sourceSlot, trailSlot, frame, checked, refits, live = -1;
    private CgVfxTrailHeads heads;
    private ByteBuffer records, headWords;
    private String failure;
    private boolean compared, running = true;
    private final int[] next = {0};

    @Override
    public void init(HarnessContext ctx) {
        CgVfxParticlePool.prepare(source);
        CgVfxParticlePool.prepare(trail, 1);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame < WARM) return;
        if (frame <= WARM + STEPS) {
            CgRecording rec = new CgRecording();
            if (frame == WARM) seed(rec);
            step(rec, frame - WARM);
            if (frame == WARM + STEPS) readBack(rec);
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = !requests.isEmpty();
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && records != null && headWords != null && live >= 0) {
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
        sourcePool = CgVfxParticlePool.of(this, source);
        sourceSlot = sourcePool.open(source, SOURCES);
        trailPool = CgVfxParticlePool.of(this, trail, 1);
        trailSlot = trailPool.open(trail, CAPACITY);
        trailPool.readFrom(trailSlot, sourcePool, sourceSlot);
        heads = new CgVfxTrailHeads(renderer.trail(), CgVfxSpawnPerUnit.every(1f, SPACING), 0f, CAPACITY, false);
        CgVfxRecord layout = sourcePool.record();
        ByteBuffer packed = ByteBuffer.allocateDirect(SOURCES * layout.bytes()).order(ByteOrder.nativeOrder());
        for (int id = 0; id < SOURCES; id++) {
            float[] p = starts[id], v = velocities[id];
            layout.put(packed, id, p[0], p[1], p[2], v[0], v[1], v[2], 0f, 100f, 0.1f, 0.5f, id, sourceSlot);
        }
        sourcePool.seed(rec, packed, SOURCES);
    }

    /** Step {@code k}: the sources move, then each trail's head is worked out on the GPU and spawns. */
    private void step(CgRecording rec, int k) {
        sourcePool.beginStep(TICK, 0f, 0f, 0f);
        sourcePool.instance(sourceSlot, SEED_BITS, 1f, Float.NaN, ORIGIN);
        sourcePool.endStep();
        trailPool.beginStep(TICK, 0f, 0f, 0f);
        trailPool.instance(trailSlot, SEED_BITS + 1, 1f, Float.NaN, ORIGIN);
        heads.tick(books, TICK, k * TICK, 0f, 0f, 0f, 1f, 1f, true, k * TICK, (k + 1) * TICK);
        int[] w = heads.rowWords();
        for (int r = 0; r < heads.rows(); r++) {
            trailPool.spawnTrail(trailSlot, heads.rowFirst(r), heads.rowCount(r), w, r * CgVfxTrailSource.ROW_WORDS, 0f);
        }
        trailPool.trailTable(trailSlot, heads.minIds(), 0, heads.refitWords(), 0);
        trailPool.endStep();
        CgVfxParticlePool.record(rec, List.of(sourcePool, trailPool));
    }

    private final CgVfxTrailSource.Spawner books = new CgVfxTrailSource.Spawner() {
        @Override public int spawn(float born, int key) { return next[0]++; }
        @Override public boolean thinned(int k) { return false; }
        @Override public float life(int key) { return 100f; }
        @Override public int reserve(int n) {
            int k = next[0];
            next[0] += n;
            return k;
        }
    };

    private void readBack(CgRecording rec) {
        requests.add(rec.readback(trailPool.live(), 0, 4, d -> live = d.getInt(0)));
        requests.add(rec.readback(trailPool.records(), 0, (long) trailPool.storage() * trailPool.record().bytes(), d -> records = copy(d)));
        requests.add(rec.readback(trailPool.heads(), 0, trailPool.heads().size(), d -> headWords = copy(d)));
    }

    // ── The twin: each trail run on the CPU along its source particle's path ──

    private void compare() {
        CgVfxRecord layout = trailPool.record();
        int tail = layout.word(CgVfxRecord.Tail.RIBBON);
        Map<Long, float[]> gpu = new HashMap<>();
        for (int r = 0; r < live; r++) {
            if (layout.i(records, r, CgVfxRecord.SLOT) != trailSlot) continue;
            int t = Math.round(layout.f(records, r, tail + 3)) % 1024, id = layout.i(records, r, CgVfxRecord.ID);
            float[] got = new float[11];
            for (int c = 0; c < 3; c++) got[c] = layout.f(records, r, CgVfxRecord.POSITION + c);
            for (int c = 0; c < 8; c++) got[3 + c] = layout.f(records, r, tail + c);
            gpu.put((long) t << 32 | id, got);
        }
        int expected = 0;
        for (int t = 0; t < TRAILS; t++) {
            int head = trailPool.head(trailSlot, t);
            int sourceId = headWords.getInt(head * 16);
            if (sourceId < 0 || sourceId >= SOURCES) {
                fail("trail " + t + " follows source particle " + sourceId + ", none of the " + SOURCES);
                return;
            }
            Map<Integer, float[]> twin = twin(sourceId);
            expected += twin.size();
            for (Map.Entry<Integer, float[]> e : twin.entrySet()) {
                float[] got = gpu.get((long) t << 32 | e.getKey()), want = e.getValue();
                if (got == null) {
                    TreeSet<Long> have = new TreeSet<>();
                    for (long key : gpu.keySet()) if (key >> 32 == t) have.add(key & 0xFFFFFFFFL);
                    fail("trail " + t + "'s spawn " + e.getKey() + " is missing: the GPU holds " + have + ", the twin "
                            + new TreeSet<>(twin.keySet()) + "; heads " + Arrays.toString(headDump(t)));
                    return;
                }
                expect("trail " + t + " spawn " + e.getKey() + "'s place", new float[] {got[0], got[1], got[2], got[6]},
                        new float[] {want[0], want[1], want[2], want[6] + t});
                expect("trail " + t + " spawn " + e.getKey() + "'s tangent", new float[] {got[3], got[4], got[5], 0f},
                        new float[] {want[3], want[4], want[5], 0f});
                expect("trail " + t + " spawn " + e.getKey() + "'s up", new float[] {got[7], got[8], got[9], got[10]},
                        new float[] {want[7], want[8], want[9], want[10]});
            }
        }
        if (gpu.size() != expected) fail(gpu.size() + " trail particles alive, not the twin's " + expected);
        if (expected < TRAILS * MOST - 3) fail("the trails hold only " + expected + ", short of their most");
        if (refits == 0) fail("no chain was refit");
    }

    /** Trail {@code t}'s head as read back: its state, then the start of its result. */
    private float[] headDump(int t) {
        int head = trailPool.head(trailSlot, t);
        float[] f = new float[24];
        for (int i = 0; i < 24; i++) f[i] = headWords.getFloat((head * 4 + i) * 4);
        f[0] = headWords.getInt(head * 16); f[1] = headWords.getInt(head * 16 + 4); f[16] = headWords.getInt(head * 16 + 64);
        f[17] = headWords.getInt(head * 16 + 68); f[18] = headWords.getInt(head * 16 + 72);
        return f;
    }

    /** Trail along source particle {@code id}: its spawns alive at the end, each its place, tangent, payload and up. */
    private Map<Integer, float[]> twin(int id) {
        CgVfxRibbonRendererProperties.Trail one = renderer.trail();
        CgVfxRibbonRendererProperties.Trail single = new CgVfxRibbonRendererProperties.Trail(1, one.maxParticles(), one.sheets(),
                one.renderAxis(), one.distanceStep(), one.maxTessellation(), one.tangentScale(), one.tangentInterp(),
                one.recalculatePrevious(), one.everyFrame(), one.tangentSpawnScale(), one.spawnInitialParticle(),
                one.clipSourceSegment(), one.deadOnDeactivate(), one.deadOnSourceLoss(), one.tilingDistance());
        CgVfxTrailSource cpu = new CgVfxTrailSource(single, CgVfxSpawnPerUnit.every(1f, SPACING), 0f, CAPACITY, new float[3]);
        int[] ids = {0};
        CgVfxTrailSource.Spawner spawner = new CgVfxTrailSource.Spawner() {
            @Override public int spawn(float born, int key) { return ids[0]++; }
            @Override public boolean thinned(int k) { return false; }
            @Override public float life(int key) { return 100f; }
        };
        Map<Integer, float[]> twin = new HashMap<>();
        for (int k = 0; k <= STEPS; k++) {
            // The source as it was published at step k: moved a step further.
            float s = (k + 1) * TICK;
            float x = starts[id][0] + velocities[id][0] * s, y = starts[id][1] + velocities[id][1] * s,
                    z = starts[id][2] + velocities[id][2] * s;
            cpu.tick(spawner, TICK, k * TICK, x, y, z, 0f, 0f, 0f, 1f, 0f, 1f, true, k * TICK, (k + 1) * TICK);
            int[] f = cpu.refitWords();
            for (int r = 0; r < cpu.refits(); r++) refit(twin, f, r * CgVfxTrailSource.REFIT_WORDS);
            refits += cpu.refits();
            int[] w = cpu.rowWords();
            for (int r = 0; r < cpu.rows(); r++) spawn(twin, w, r * CgVfxTrailSource.ROW_WORDS, cpu.rowFirst(r), cpu.rowCount(r));
            int oldest = cpu.minIds()[0];
            twin.keySet().removeIf(i -> i < oldest);
        }
        return twin;
    }

    private static void spawn(Map<Integer, float[]> twin, int[] w, int at, int first, int n) {
        float[] from = floats(w, at + 4), to = floats(w, at + 8), t0 = floats(w, at + 12), t1 = floats(w, at + 16);
        float[] up0 = floats(w, at + 20), up1 = floats(w, at + 24);
        float count = Float.intBitsToFloat(w[at + 27]), distance = up0[3];
        float[] last = cubic(from, t0, to, t1, 1f / n);
        for (int i = 0; i < n; i++) {
            float s = (i + 1f) / n;
            float[] here = cubic(from, t0, to, t1, s), tangent = derivative(from, t0, to, t1, s);
            if (i > 0) {
                float[] next = cubic(from, t0, to, t1, (i + 1f) / n);
                distance += length(next[0] - last[0], next[1] - last[1], next[2] - last[2]);
                last = next;
            }
            twin.put(first + i, new float[] {here[0], here[1], here[2], tangent[0] / n, tangent[1] / n, tangent[2] / n,
                    1024f * count, up0[0] + (up1[0] - up0[0]) * s, up0[1] + (up1[1] - up0[1]) * s,
                    up0[2] + (up1[2] - up0[2]) * s, distance});
        }
    }

    private static void refit(Map<Integer, float[]> twin, int[] f, int at) {
        int firstId = f[at + 1], count = f[at + 2];
        float[] p0 = floats(f, at + 4), t0 = floats(f, at + 7), p1 = floats(f, at + 10);
        float[] t1 = {Float.intBitsToFloat(f[at + 13]), Float.intBitsToFloat(f[at + 14]), Float.intBitsToFloat(f[at + 15])};
        for (int id = firstId; id < firstId + count; id++) {
            float[] p = twin.get(id);
            if (p == null) continue;
            float s = (id - firstId + 1f) / count;
            float[] here = cubic(p0, t0, p1, t1, s), tangent = derivative(p0, t0, p1, t1, s);
            for (int c = 0; c < 3; c++) {
                p[c] = here[c];
                p[3 + c] = tangent[c] / count;
            }
        }
    }

    /** Unreal's FMath::CubicInterp, and its derivative. */
    private static float[] cubic(float[] p0, float[] t0, float[] p1, float[] t1, float a) {
        float a2 = a * a, a3 = a2 * a, h0 = 2 * a3 - 3 * a2 + 1, h1 = a3 - 2 * a2 + a, h2 = a3 - a2, h3 = -2 * a3 + 3 * a2;
        float[] out = new float[3];
        for (int c = 0; c < 3; c++) out[c] = h0 * p0[c] + h1 * t0[c] + h2 * t1[c] + h3 * p1[c];
        return out;
    }

    private static float[] derivative(float[] p0, float[] t0, float[] p1, float[] t1, float a) {
        float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            out[c] = (6 * p0[c] + 3 * t0[c] + 3 * t1[c] - 6 * p1[c]) * a * a + (-6 * p0[c] - 4 * t0[c] - 2 * t1[c] + 6 * p1[c]) * a + t0[c];
        }
        return out;
    }

    private static float[] floats(int[] w, int at) {
        return new float[] {Float.intBitsToFloat(w[at]), Float.intBitsToFloat(w[at + 1]), Float.intBitsToFloat(w[at + 2]),
                Float.intBitsToFloat(w[at + 3])};
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private void expect(String what, float[] got, float[] want) {
        checked++;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(got[i] - want[i]) > TOLERANCE * Math.max(1f, Math.abs(want[i]))) {
                fail(what + " is " + text(got) + ", not " + text(want));
                return;
            }
        }
    }

    private static String text(float[] v) {
        StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < v.length; i++) b.append(i > 0 ? ", " : "").append(v[i]);
        return b.append(')').toString();
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
        if (GlErrorChecker.checkAndLog("vfx-trail-heads")) {
            System.out.println("[vfx-trail-heads] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-trail-heads] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-trail-heads] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-trail-heads] FAIL on " + on + ": the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-trail-heads] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-trail-heads] PASS on " + on + ": " + checked + " records of " + TRAILS + " trails on the GPU, each"
                    + " behind a moving particle of another emitter over " + (STEPS + 1) + " steps, killed past " + MOST + " and refit "
                    + refits + " times, held to the twin to " + TOLERANCE);
        }
    }

    @Override public void dispose() {}

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
