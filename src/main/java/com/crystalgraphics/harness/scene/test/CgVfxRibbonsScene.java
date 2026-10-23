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
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxAnimTrailSource;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxRibbonGeneration;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxTrailRows;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxTrailSource;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnPerUnit;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for the ribbon generation ({@code CgVfxRibbonGeneration}, Niagara's GPU ribbon init) and Cascade's trail
 * spawning ({@code CgVfxTrailSource} and the Step's trail rows): three ribbon slots, the packed run, the draw counts and
 * the trails' records read back and held to a Java twin of every stage run on the same inputs.
 * <ul>
 *   <li><b>Niagara</b>: the renderer's defaults, linked by age, its tessellation automatic at the eye's distance.</li>
 *   <li><b>Shaped</b>: curve tension, UV0 scaled uniformly with smooth edges, UV1 tiled over its length, a custom
 *       factor, linked by spawn id with a gap that breaks it into two ribbons.</li>
 *   <li><b>Trail</b>: Cascade's, its source moving along a helix for {@value #TICKS} steps: spawned per unit on the
 *       Hermite between its last and current place, its chains refit, newest first, each segment's points by its
 *       stored tangents, its world up and its tiled U.</li>
 *   <li><b>Anim</b>: Cascade's anim trail, a blade's edge swept for {@value #TICKS} steps and stopped once between, so
 *       it lays two chains: each sample's tangent and interpolation parameter, refit beside the next, and each segment's
 *       points by its distance, turn and width.</li>
 * </ul>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-ribbons"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-ribbons" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-ribbons --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-ribbons] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxRibbonsScene implements InteractiveSceneLifecycle {

    private static final int WARM = 10, DRAIN = 40, N = 40, SEEDED = 2, NIAGARA = 0, SHAPED = 1, TRAIL = 2, ANIM = 3;
    private static final String[] NAMES = {"niagara", "shaped", "trail", "anim"};
    /** The tick the anim trail is stopped for, breaking it in two. */
    private static final int STOP = 7;
    private static final float ANIM_DISTANCE = 0.25f, ANIM_ANGLE = 20f, ANIM_WIDTH = 0.05f;
    /** Spawn id where the shaped slot's ribbon breaks: past its link gap from the one before. */
    private static final int BREAK = 25, GAP = 3;
    /** The trail's steps, its capacity, and its source's spacing: a particle every quarter block. */
    private static final int TICKS = 14, TRAIL_CAPACITY = 128;
    private static final float DT = 1e-4f, TICK = 0.05f, SPACING = 0.25f, TILING = 0.5f, TOLERANCE = 2e-3f;
    private static final double CAMERA_X = 0.5, CAMERA_Y = 72.0, CAMERA_Z = 9.5;

    private final CgVfxEmitter emitter = CgVfxEmitter.builder("ribbon-gate").state(CgVfxEmitterState.ONCE).capacity(N)
            .life(100f, 100f).size(0.1f, 0.1f, 1f).build();
    private final CgVfxRibbonRendererProperties trailRenderer = (CgVfxRibbonRendererProperties) CgVfxRenderers.ribbon("trail")
            .field("trail", Map.of("tessellation", Map.of("distance", 0.3, "tangent_scale", 5.0), "render_axis", "world_up",
                    "tiling_distance", (double) TILING)).build();
    private final CgVfxEmitter trailEmitter = CgVfxEmitter.builder("ribbon-trail").state(CgVfxEmitterState.ONCE)
            .capacity(TRAIL_CAPACITY).life(100f, 100f).size(0.1f, 0.1f, 1f).renderer(trailRenderer).build();
    private final CgVfxRibbonRendererProperties animRenderer = (CgVfxRibbonRendererProperties) CgVfxRenderers.animTrail("anim", "hilt", "tip")
            .field("tessellation", Map.of("distance", (double) ANIM_DISTANCE, "angle", (double) ANIM_ANGLE, "width", (double) ANIM_WIDTH))
            .field("tiling_distance", (double) TILING).build();
    private final CgVfxEmitter animEmitter = CgVfxEmitter.builder("ribbon-anim").state(CgVfxEmitterState.ONCE)
            .capacity(TRAIL_CAPACITY).life(100f, 100f).size(0.1f, 0.1f, 1f).renderer(animRenderer).build();
    private final CgVfxRibbonRendererProperties[] renderers = {
            (CgVfxRibbonRendererProperties) CgVfxRenderers.ribbon("niagara").build(),
            (CgVfxRibbonRendererProperties) CgVfxRenderers.ribbon("shaped").field("shape", "multi_plane")
                    .field("width_segments", 2).field("link_order_by_id", true).field("max_link_gap", GAP)
                    .field("tessellation", Map.of("mode", "custom", "factor", 4, "angle", 0, "tension", 0.4))
                    .field("uv0", Map.of("distribution", "scaled_uniformly", "leading_edge", "smooth_transition",
                            "trailing_edge", "smooth_transition", "scale", List.of(2.0, 1.0), "offset", List.of(0.25, 0.0)))
                    .field("uv1", Map.of("distribution", "tiled_over_ribbon_length", "tiling_length", 0.5))
                    .build(),
            trailRenderer, animRenderer};
    private final CgHostView view = new CgHostView();
    private final List<CgRequest> requests = new ArrayList<>();
    /** Each slot's pool: the seeded two share one, the trail its own. */
    private final Side[] sides = new Side[4];
    private final int[] slots = new int[4];
    /**
     * The trail twins, the ribbon trail's and the anim trail's: each spawn id's particle as the Step should leave it, its
     * place then its payload's ten words that count.
     */
    private final List<Map<Integer, float[]>> twins = List.of(new HashMap<>(), new HashMap<>());
    private Map<Integer, float[]> trail = twins.get(0);
    private ByteBuffer order, visible;
    private int frame, checked, units, refits;
    private String failure;
    private boolean compared, running = true;

    /** A pool and what was read back of it. */
    private static final class Side {
        CgVfxParticlePool pool;
        CgVfxRibbonGeneration ribbons;
        CgVfxRange range;
        ByteBuffer drawn, run, args, records;
    }

    @Override
    public void init(HarnessContext ctx) {
        Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, 0f, -0.15f, -1f, 0f, 1f, 0f);
        view.set(CAMERA_X, CAMERA_Y, CAMERA_Z, look, new Matrix4f().perspective((float) Math.toRadians(70), 4f / 3f, 0.05f, 300f));
        CgVfxParticlePool.prepare(emitter);
        CgVfxParticlePool.prepare(trailEmitter);
        CgVfxParticlePool.prepare(animEmitter);
        CgVfxRange.prepare();
        CgVfxRibbonGeneration.prepare();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame < WARM) return;
        if (frame == WARM) {
            CgRecording rec = new CgRecording();
            generate(rec);
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = !requests.isEmpty();
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && sides[ANIM].args != null) {
            compareTrail(TRAIL);
            compareTrail(ANIM);
            for (int s = 0; s < 4; s++) compare(s);
            compared = true;
        }
        if (compared || frame > WARM + DRAIN) {
            report();
            CgVfxParticlePool.release(this);
            running = false;
        }
    }

    private void generate(CgRecording rec) {
        Side seeded = new Side(), trailed = new Side();
        seeded.pool = CgVfxParticlePool.of(this, emitter);
        trailed.pool = CgVfxParticlePool.of(this, trailEmitter);
        // The two trails' emitters are of one shape, so they share a pool.
        if (CgVfxParticlePool.of(this, animEmitter) != trailed.pool) throw new IllegalStateException("the two trails' pools differ");
        sides[NIAGARA] = sides[SHAPED] = seeded;
        sides[TRAIL] = sides[ANIM] = trailed;
        for (int s = 0; s < SEEDED; s++) {
            slots[s] = seeded.pool.open(emitter, N);
            seeded.pool.cullAbout(slots[s], 100f);
        }
        slots[TRAIL] = trailed.pool.open(trailEmitter, TRAIL_CAPACITY);
        trailed.pool.cullAbout(slots[TRAIL], 100f);
        slots[ANIM] = trailed.pool.open(animEmitter, TRAIL_CAPACITY);
        trailed.pool.cullAbout(slots[ANIM], 100f);
        CgVfxRecord layout = seeded.pool.record();
        ByteBuffer packed = ByteBuffer.allocateDirect(SEEDED * N * layout.bytes()).order(ByteOrder.nativeOrder());
        Random random = new Random(1771);
        for (int s = 0, r = 0; s < SEEDED; s++) {
            for (int i = 0; i < N; i++, r++) {
                // Along a helix, its ages shuffled so the link order is not the seed order.
                int id = s == SHAPED && i >= BREAK ? i + GAP + 2 : i;
                float t = i / (float) N, age = s == SHAPED ? random.nextFloat() * 50f : (N - i) * 0.7f + random.nextFloat() * 0.3f;
                float x = (float) Math.cos(t * 9.0) * 2f, y = t * 6f, z = (float) Math.sin(t * 9.0) * 2f - 4f * s;
                layout.put(packed, r, x, y, z, 0f, 0f, 0f, age, 100f, 0.1f + 0.05f * random.nextFloat(), 0.5f, id, slots[s]);
            }
        }
        seeded.pool.seed(rec, packed, SEEDED * N);
        seeded.pool.beginStep(DT, 0f, 0f, 0f);
        for (int s = 0; s < SEEDED; s++) seeded.pool.instance(slots[s], 0x51B0 + s, 1f, Float.NaN, ORIGIN);
        seeded.pool.endStep();
        seeded.pool.record(rec);
        stepTrails(trailed.pool);
        trailed.pool.record(rec);
        Side[] pools = {seeded, trailed};
        for (Side side : pools) side.range = CgVfxRange.of(side.pool);
        CgVfxRange.layOut();
        for (Side side : pools) side.ribbons = CgVfxRibbonGeneration.of(side.pool);
        for (int s = 0; s < 4; s++) sides[s].ribbons.ribbon(slots[s], renderers[s]);
        CgVfxRange.record(rec, view);
        requests.add(rec.readback(seeded.range.order(), 0, seeded.range.order().size(), d -> order = copy(d)));
        requests.add(rec.readback(seeded.range.visible(), 0, seeded.range.visible().size(), d -> visible = copy(d)));
        for (Side side : pools) {
            CgVfxParticlePool p = side.pool;
            requests.add(rec.readback(p.drawn(), 0, (long) p.storage() * CgVfxRecord.DRAWN_BYTES, d -> side.drawn = copy(d)));
            requests.add(rec.readback(p.records(), 0, (long) p.storage() * p.record().bytes(), d -> side.records = copy(d)));
            requests.add(rec.readback(side.ribbons.run(), 0, side.ribbons.run().size(), d -> side.run = copy(d)));
            requests.add(rec.readback(side.ribbons.args(), 0, p.slotCount() * 4L, d -> side.args = copy(d)));
        }
    }

    // ── The trail: its source's rows through the Step, and their twin ──

    /**
     * The two trails one step a tick in their shared pool, their rows handed to it and to their twins: the ribbon
     * trail's source along a helix, the anim trail's edge swept, stopped at {@link #STOP}.
     */
    private void stepTrails(CgVfxParticlePool pool) {
        CgVfxTrailSource ribbon = new CgVfxTrailSource(trailRenderer.trail(), CgVfxSpawnPerUnit.every(1f, SPACING), 0f,
                TRAIL_CAPACITY, new float[3]);
        CgVfxAnimTrailSource anim = new CgVfxAnimTrailSource(animRenderer.anim(), TRAIL_CAPACITY);
        int[][] next = {{0}, {0}};
        float[][] at = {edge(0)};
        CgVfxInstanceView view = new CgVfxInstanceView() {
            public double originX() { return ORIGIN.originX(); }
            public double originY() { return ORIGIN.originY(); }
            public double originZ() { return ORIGIN.originZ(); }
            public float time() { return 0f; }
            public float sourceX() { return 0f; }
            public float sourceY() { return 0f; }
            public float sourceZ() { return 0f; }
            public float user(String name, int component, float fallback) {
                return name.equals("hilt") ? at[0][component] : name.equals("tip") ? at[0][3 + component] : fallback;
            }
        };
        for (int k = 0; k < TICKS; k++) {
            float a = k * 0.35f, x = (float) Math.cos(a) * 2f, y = k * 0.3f, z = (float) Math.sin(a) * 2f - 8f;
            at[0] = edge(k);
            pool.beginStep(TICK, 0f, 0f, 0f);
            pool.instance(slots[TRAIL], 0x51B2, 1f, Float.NaN, ORIGIN);
            pool.instance(slots[ANIM], 0x51B3, 1f, Float.NaN, ORIGIN);
            ribbon.tick(spawner(next[0]), TICK, k * TICK, x, y, z, 0f, 0f, 0f, 1f, 0f, 1f, true, k * TICK, (k + 1) * TICK);
            hand(pool, slots[TRAIL], ribbon, twins.get(0));
            anim.tick(spawner(next[1]), TICK, k * TICK, view, k != STOP, k * TICK, (k + 1) * TICK);
            hand(pool, slots[ANIM], anim, twins.get(1));
            pool.endStep();
        }
    }

    /** Spawn indices from {@code next[0]} on, none thinned, each living a hundred seconds. */
    private static CgVfxTrailSource.Spawner spawner(int[] next) {
        return new CgVfxTrailSource.Spawner() {
            @Override public int spawn(float born, int key) { return next[0]++; }
            @Override public boolean thinned(int k) { return false; }
            @Override public float life(int key) { return 100f; }
        };
    }

    /** A tick's rows to the pool's {@code slot} and to {@code twin}: the Step refits what lived before it, then spawns. */
    private void hand(CgVfxParticlePool pool, int slot, CgVfxTrailRows rows, Map<Integer, float[]> twin) {
        trail = twin;
        int[] f = rows.refitWords();
        for (int r = 0; r < rows.refits(); r++) refit(f, r * CgVfxTrailSource.REFIT_WORDS);
        refits += rows.refits();
        int[] w = rows.rowWords();
        for (int r = 0; r < rows.rows(); r++) {
            pool.spawnTrail(slot, rows.rowFirst(r), rows.rowCount(r), w, r * CgVfxTrailSource.ROW_WORDS, 0f);
            spawnTwin(w, r * CgVfxTrailSource.ROW_WORDS, rows.rowFirst(r), rows.rowCount(r));
        }
        pool.trailTable(slot, rows.minIds(), rows.trails(), f, rows.refits());
        trail = twins.get(0);
    }

    /** The blade's hilt and tip at tick {@code k}, from the effect's origin. */
    private static float[] edge(int k) {
        float a = k * 0.4f, hx = (float) Math.cos(a) * 0.5f, hy = k * 0.1f, hz = (float) Math.sin(a) * 0.5f - 12f;
        return new float[] {hx, hy, hz, hx + (float) Math.cos(a) * 1.5f, hy + 0.3f + 0.05f * k, hz + (float) Math.sin(a) * 1.5f};
    }

    private void spawnTwin(int[] w, int at, int first, int n) {
        float[] from = floats(w, at + 4), to = floats(w, at + 8), t0 = floats(w, at + 12), t1 = floats(w, at + 16);
        float[] up0 = floats(w, at + 20), up1 = floats(w, at + 24);
        int kind = Math.round(Float.intBitsToFloat(w[at + 19]));
        float trailIndex = Float.intBitsToFloat(w[at + 15]), count = Float.intBitsToFloat(w[at + 27]);
        float distance = up0[3], chain = Float.intBitsToFloat(w[at + 29]), interp = Float.intBitsToFloat(w[at + 30]);
        if (kind == CgVfxAnimTrailSource.SAMPLE) {
            // A sample: where its row says, its edge as its up.
            trail.put(first, new float[] {to[0], to[1], to[2], t0[0], t0[1], t0[2], trailIndex + 1024f, t1[0], t1[1], t1[2],
                    distance, chain, interp});
            return;
        }
        float[] last = kind == CgVfxTrailSource.MOVEMENT ? cubic(from, t0, to, t1, 1f / n) : null;
        for (int i = 0; i < n; i++) {
            float s = kind == CgVfxTrailSource.MOVEMENT ? (i + 1f) / n : 1f;
            float[] here = kind == CgVfxTrailSource.MOVEMENT ? cubic(from, t0, to, t1, s) : pos(to);
            float[] tangent = kind == CgVfxTrailSource.MOVEMENT ? scale(derivative(from, t0, to, t1, s), 1f / n) : pos(t0);
            if (kind == CgVfxTrailSource.MOVEMENT && i > 0) {
                float[] nextPoint = cubic(from, t0, to, t1, (i + 1f) / n);
                distance += len(sub(nextPoint, last));
                last = nextPoint;
            }
            trail.put(first + i, new float[] {here[0], here[1], here[2], tangent[0], tangent[1], tangent[2],
                    trailIndex + 1024f * count, up0[0] + (up1[0] - up0[0]) * s, up0[1] + (up1[1] - up0[1]) * s,
                    up0[2] + (up1[2] - up0[2]) * s, distance, chain, 0f});
        }
    }

    /** A refit, as the Step makes it: a chain laid again on a new curve, or one particle's tangent set. */
    private void refit(int[] f, int at) {
        int t = f[at], firstId = f[at + 1], count = f[at + 2], mode = f[at + 3];
        float[] p0 = floats(f, at + 4), t0 = floats(f, at + 7), p1 = floats(f, at + 10);
        float[] t1 = {Float.intBitsToFloat(f[at + 13]), Float.intBitsToFloat(f[at + 14]), Float.intBitsToFloat(f[at + 15])};
        for (int id = firstId; id < firstId + count; id++) {
            float[] p = trail.get(id);
            if (p == null || Math.round(p[6]) % 1024 != t) continue;
            if (mode == CgVfxAnimTrailSource.REFIT_SAMPLE) {
                System.arraycopy(t1, 0, p, 3, 3);
                p[12] = p0[0];
            } else if (mode == CgVfxTrailSource.REFIT_CHAIN) {
                float s = (id - firstId + 1f) / count;
                float[] here = cubic(p0, t0, p1, t1, s), tangent = scale(derivative(p0, t0, p1, t1, s), 1f / count);
                System.arraycopy(here, 0, p, 0, 3);
                System.arraycopy(tangent, 0, p, 3, 3);
            } else {
                System.arraycopy(t1, 0, p, 3, 3);
            }
        }
    }

    /** A trail's records against its twin: each spawn's place and payload as the Step left them. */
    private void compareTrail(int s) {
        Side side = sides[s];
        Map<Integer, float[]> trail = twins.get(s == TRAIL ? 0 : 1);
        CgVfxRecord layout = side.pool.record();
        int tail = layout.word(CgVfxRecord.Tail.RIBBON), slot = slots[s];
        if (tail < 0) {
            fail("the trail's pool keeps no trail payload");
            return;
        }
        int found = 0;
        for (int r = 0; r < side.pool.storage(); r++) {
            if (layout.i(side.records, r, CgVfxRecord.SLOT) != slot || !(layout.f(side.records, r, CgVfxRecord.LIFE) > 0f)) continue;
            int id = layout.i(side.records, r, CgVfxRecord.ID);
            float[] want = trail.get(id);
            if (want == null) {
                fail("trail record " + r + " holds spawn " + id + ", which its source never spawned");
                return;
            }
            found++;
            float[] got = new float[13];
            for (int c = 0; c < 3; c++) got[c] = layout.f(side.records, r, CgVfxRecord.POSITION + c);
            for (int c = 0; c < 10; c++) got[3 + c] = layout.f(side.records, r, tail + c);
            expect("trail spawn " + id + "'s place", new float[] {got[0], got[1], got[2], got[6]}, new float[] {want[0], want[1], want[2], want[6]});
            expect("trail spawn " + id + "'s tangent", new float[] {got[3], got[4], got[5], 0f}, new float[] {want[3], want[4], want[5], 0f});
            expect(NAMES[s] + " spawn " + id + "'s up", new float[] {got[7], got[8], got[9], got[10]}, new float[] {want[7], want[8], want[9], want[10]});
            expect(NAMES[s] + " spawn " + id + "'s chain", new float[] {got[11], got[12], 0f, 0f}, new float[] {want[11], want[12], 0f, 0f});
        }
        if (found != trail.size()) fail(NAMES[s] + " holds " + found + " particles, not the " + trail.size() + " its source spawned");
        if (trail.size() < 12) fail(NAMES[s] + "'s source spawned only " + trail.size());
        if (refits < TICKS / 2) fail("the trail's source refit its chains only " + refits + " times");
    }

    // ── The generation's twin: each kernel of ribbon.compute in Java, over the order and drawn parts the GPU read ──

    /** One sorted place: its position, its drawn element, its slot. */
    private record Place(int position, int element, int slot) {}

    private float[] particle(Side side, int element) {
        float[] f = new float[24];
        for (int i = 0; i < 24; i++) f[i] = side.drawn.getFloat((element * 24 + i) * 4);
        return f;
    }

    /** Element {@code e}'s trail payload: its tangent, trail and points; its up and distance; its chain and interpolation. */
    private float[] payload(Side side, int e) {
        CgVfxRecord layout = side.pool.record();
        int r = e / side.pool.shape().drawnStride(), tail = layout.word(CgVfxRecord.Tail.RIBBON);
        float[] f = new float[10];
        for (int c = 0; c < 10; c++) f[c] = layout.f(side.records, r, tail + c);
        return f;
    }

    private void compare(int s) {
        Side side = sides[s];
        int slot = slots[s];
        // Ribbons and units run on from the pool's first slot: the seeded pool's second slot follows its first.
        int unitBase = 0, ribbonBase = 0;
        for (int o = 0; o < s; o++) {
            if (sides[o] != side) continue;
            float[] rec = vec(side, side.ribbons.slotRecord(slots[o]));
            unitBase += Math.round(rec[1]);
            ribbonBase += ribbons(side, slots[o]);
        }
        int positionAt = side.ribbons.positionAt(), ribbonAt = side.ribbons.ribbonAt();
        CgVfxRibbonRendererProperties r = renderers[s];
        boolean trailed = r.trail() != null || r.anim() != null, cascade = r.trail() != null, anim = r.anim() != null;
        Map<Integer, float[]> trail = twins.get(anim ? 1 : 0);
        int base = side.pool.listBase(slot), capacity = side.pool.capacity(slot), orderBase = side.range.laidBase(slot) - base;
        int count = visible.getInt(side.range.laidWord(slot) * 4), want = trailed ? trail.size() : N;
        if (count != want) fail(NAMES[s] + ": " + count + " visible, not " + want);
        List<Place> places = new ArrayList<>();
        for (int p = base; p < base + count; p++) {
            int e = Math.round(order.getFloat((orderBase + p) * 4));
            if (e >= 0) places.add(new Place(p, e, slot));
        }
        Comparator<Place> byLink = Comparator.comparingDouble(pl -> {
            float[] d = particle(side, pl.element());
            return trailed ? d[23] : r.linkOrderById() ? -d[13] : d[7];
        });
        places.sort(byLink);
        int n = places.size();
        float[][] d = new float[n][], stored = new float[n][];
        for (int i = 0; i < n; i++) {
            d[i] = particle(side, places.get(i).element());
            if (trailed) stored[i] = payload(side, places.get(i).element());
        }
        boolean[] hasPrev = new boolean[n], hasNext = new boolean[n];
        for (int i = 0; i < n; i++) {
            hasPrev[i] = i > 0 && linked(r, d[i], d[i - 1]) && (!trailed || stored[i][8] == stored[i - 1][8]);
            hasNext[i] = i + 1 < n && linked(r, d[i], d[i + 1]) && (!trailed || stored[i][8] == stored[i + 1][8]);
        }
        float[][] tangent = new float[n][4];
        float total = 0f, lengthSq = 0f, lengthAngle = 0f;
        int[] own = new int[n];
        for (int i = 0; i < n; i++) {
            float[] here = pos(d[i]);
            if (hasNext[i] && hasPrev[i]) {
                float[] toNext = sub(pos(d[i + 1]), here), fromPrev = sub(here, pos(d[i - 1]));
                float a = len(toNext), b = len(fromPrev);
                float[] tn = a > 0 ? scale(toNext, 1 / a) : toNext, fp = b > 0 ? scale(fromPrev, 1 / b) : fromPrev;
                float[] avg = unit(add(fp, tn));
                tangent[i] = a + b > 0 ? new float[] {(1 - r.tessellation().tension()) * avg[0],
                        (1 - r.tessellation().tension()) * avg[1], (1 - r.tessellation().tension()) * avg[2], b} : new float[4];
                total += a;
                lengthSq += a * a;
                lengthAngle += a * (float) Math.acos(Math.max(-1, Math.min(1, dot(fp, tn))));
            } else if (hasNext[i]) {
                float[] toNext = sub(pos(d[i + 1]), here);
                float a = len(toNext);
                tangent[i] = a > 0 ? new float[] {toNext[0] / a, toNext[1] / a, toNext[2] / a, 0} : new float[] {toNext[0], toNext[1], toNext[2], 0};
            } else if (hasPrev[i]) {
                float[] fromPrev = sub(here, pos(d[i - 1]));
                float b = len(fromPrev);
                tangent[i] = b > 0 ? new float[] {fromPrev[0] / b, fromPrev[1] / b, fromPrev[2] / b, b} : new float[] {fromPrev[0], fromPrev[1], fromPrev[2], b};
            }
            // A Cascade trail draws its stored tangents.
            if (cascade) System.arraycopy(stored[i], 0, tangent[i], 0, 3);
        }
        for (int i = 0; i < n; i++) {
            own[i] = hasNext[i] ? 1 : 0;
            if (hasNext[i] && anim) {
                // DetermineVertexAndTriangleCount: older i + 1 (its Curr), newer i (its Prev), i + 2 (its Next).
                boolean hasNN = hasNext[i + 1];
                int points = 1 + (int) (len(sub(pos(d[i]), pos(d[i + 1]))) / ANIM_DISTANCE);
                float check = (dot(pos(stored[i + 1]), pos(stored[i])) - 1f) * -0.5f;
                if (hasNN) check = Math.max(check, (dot(pos(stored[i + 2]), pos(stored[i + 1])) - 1f) * -0.5f);
                points += (int) (check / (ANIM_ANGLE / 180f));
                float lc = len(new float[] {stored[i + 1][4], stored[i + 1][5], stored[i + 1][6]});
                float w = Math.abs(lc - len(new float[] {stored[i][4], stored[i][5], stored[i][6]}));
                if (hasNN) w = Math.max(w, Math.abs(len(new float[] {stored[i + 2][4], stored[i + 2][5], stored[i + 2][6]}) - lc));
                points += (int) (w / ANIM_WIDTH);
                own[i] = Math.max(1, Math.min(points, 64));
            } else if (hasNext[i] && trailed) {
                float[] older = unit(pos(stored[i + 1])), newer = unit(pos(stored[i]));
                float distance = len(sub(pos(d[i]), pos(d[i + 1])));
                float turn = (dot(older, newer) - 1f) * -0.5f;
                float steps = distance / r.trail().distanceStep();
                own[i] = Math.max((int) steps + (int) (turn * r.trail().tangentScale()), 1);
            }
        }
        // Distances run on from each ribbon's start; units and ribbons on from the pool's first.
        int[] ribbonOf = new int[n], before = new int[n];
        List<int[]> ribbonEnds = new ArrayList<>();
        for (int i = 0, u = unitBase, rb = ribbonBase - 1; i < n; i++) {
            if (!hasPrev[i]) {
                rb++;
                ribbonEnds.add(new int[] {i, i});
            } else {
                tangent[i][3] += tangent[i - 1][3];
            }
            ribbonOf[i] = rb;
            ribbonEnds.get(ribbonEnds.size() - 1)[1] = i;
            before[i] = u;
            u += own[i];
        }
        int slotUnits = 0;
        for (int i = 0; i < n; i++) slotUnits += own[i];
        // Each ribbon's U and its reflected end tangents (Niagara's only).
        float[][] uv = new float[ribbonEnds.size()][];
        for (int k = 0; k < ribbonEnds.size(); k++) {
            int first = ribbonEnds.get(k)[0], last = ribbonEnds.get(k)[1];
            float length = tangent[last][3];
            if (cascade) {
                float u = before[last] - before[first];
                uv[k] = new float[] {u > 0 ? 1 / u : 0, before[first], 0, 0, 0, 0};
            } else if (last - first > 1) {
                float[] a = uv(r.uv0(), d, tangent, first, last, length), b = uv(r.uv1(), d, tangent, first, last, length);
                uv[k] = new float[] {a[0], a[1], a[2], b[0], b[1], b[2]};
                float[] t0 = tangent[first], t1 = tangent[first + 1], tl = tangent[last], tp = tangent[last - 1];
                float df = 2 * dot(t0, t1), dl = 2 * dot(tl, tp);
                tangent[first] = new float[] {df * t0[0] - t1[0], df * t0[1] - t1[1], df * t0[2] - t1[2], t0[3]};
                tangent[last] = new float[] {dl * tl[0] - tp[0], dl * tl[1] - tp[1], dl * tl[2] - tp[2], tl[3]};
            } else {
                uv[k] = new float[] {1, 0, 1, 1, 0, 1};
            }
        }
        int factor = trailed ? 1 : factor(side, r, slot, total, lengthSq, lengthAngle);
        // The run, as the GPU wrote it: the slot's record, each place's, each ribbon's.
        expect(NAMES[s] + "'s slot record", vec(side, side.ribbons.slotRecord(slot)), new float[] {unitBase, slotUnits, factor, 1f / factor});
        expect(NAMES[s] + "'s slot range", vec(side, side.ribbons.slotRecord(slot) + 1),
                new float[] {base, base + capacity - 1, positionAt, ribbonAt});
        for (int i = 0; i < n; i++) {
            int q = base + i;
            expect(NAMES[s] + " place " + i + "'s tangent", vec(side, positionAt + q * 4), tangent[i]);
            expect(NAMES[s] + " place " + i, vec(side, positionAt + q * 4 + 1),
                    new float[] {places.get(i).position(), before[i], ribbonOf[i], own[i]});
            if (trailed) {
                expect(NAMES[s] + " place " + i + "'s up", vec(side, positionAt + q * 4 + 2),
                        new float[] {stored[i][4], stored[i][5], stored[i][6], stored[i][7] / TILING});
                expect(NAMES[s] + " place " + i + "'s interpolation", vec(side, positionAt + q * 4 + 3),
                        new float[] {anim ? stored[i][9] : 0f, 0f, 0f, 0f});
            }
        }
        for (int k = 0; k < ribbonEnds.size(); k++) {
            int rr = ribbonBase + k;
            float[] a = uv[k];
            expect(NAMES[s] + " ribbon " + k + "'s UV0", vec(side, ribbonAt + rr * 2),
                    new float[] {a[0], a[1], a[2], base + ribbonEnds.get(k)[0]});
            expect(NAMES[s] + " ribbon " + k + "'s UV1", vec(side, ribbonAt + rr * 2 + 1),
                    new float[] {a[3], a[4], a[5], base + ribbonEnds.get(k)[1]});
        }
        if (s == SHAPED && ribbonEnds.size() != 2) fail("the shaped slot's link gap made " + ribbonEnds.size() + " ribbons, not 2");
        if (s == TRAIL && ribbonEnds.size() != 1) fail("the trail made " + ribbonEnds.size() + " ribbons, not 1");
        if (s == ANIM && ribbonEnds.size() != 2) fail("the anim trail's stop made " + ribbonEnds.size() + " chains, not 2");
        int vertices = side.args.getInt(slot * 4);
        if (vertices != slotUnits * factor) fail(NAMES[s] + " draws " + vertices + " units, not " + slotUnits * factor);
        units += slotUnits * factor;
    }

    /** How many ribbons the GPU counted in {@code slot}: one past its last place's ribbon, less its first's. */
    private int ribbons(Side side, int slot) {
        int base = side.pool.listBase(slot), positionAt = side.ribbons.positionAt();
        int count = visible.getInt(side.range.laidWord(slot) * 4);
        if (count == 0) return 0;
        return Math.round(vec(side, positionAt + (base + count - 1) * 4 + 1)[2] - vec(side, positionAt + base * 4 + 1)[2]) + 1;
    }

    private boolean linked(CgVfxRibbonRendererProperties r, float[] a, float[] b) {
        return r.maxLinkGap() <= 0 || Math.abs(a[13] - b[13]) <= r.maxLinkGap();
    }

    /** CalculateUVScaleAndOffsets for one channel: U scale, offset, distribution scalar. */
    private static float[] uv(CgVfxRibbonRendererProperties.Uv u, float[][] d, float[][] tangent, int first, int last, float total) {
        float segments = last - first + 1, leading = 0, trailing = 0;
        if (u.leadingEdge() == CgVfxRibbonRendererProperties.Edge.SMOOTH_TRANSITION) {
            float a = d[first][7], b = d[first + 1][7], step = b - a, offset = a < step ? step - a : 0;
            leading = step > 0 ? offset / step : 0;
        }
        if (u.trailingEdge() == CgVfxRibbonRendererProperties.Edge.SMOOTH_TRANSITION) {
            float a = d[last - 1][7], b = d[last][7], step = b - a, offset = 1 - b < step ? step - (1 - b) : 0;
            trailing = step > 0 ? offset / step : 0;
        }
        float scale, offset, distribution;
        switch (u.distribution()) {
            case SCALED_UNIFORMLY -> {
                float available = segments - (leading + trailing);
                scale = segments / available;
                offset = -((leading / segments) * scale);
                distribution = 1 / segments;
            }
            case SCALED_USING_RIBBON_SEGMENT_LENGTH -> {
                float lead = tangent[first + 1][3] * leading, trail = (tangent[last][3] - tangent[last - 1][3]) * trailing;
                scale = total / (total - (lead + trail));
                offset = -((lead / total) * scale);
                distribution = 1 / total;
            }
            case TILED_OVER_RIBBON_LENGTH -> {
                scale = total / u.tilingLength();
                offset = -(tangent[first + 1][3] * leading / u.tilingLength());
                distribution = 1 / total;
            }
            default -> {
                scale = total / u.tilingLength();
                offset = 0;
                distribution = 1 / total;
            }
        }
        return new float[] {scale * u.scaleU(), offset * u.scaleU() + u.offsetU(), distribution};
    }

    /** CalculateTessellationFactor on a first frame: nothing smoothed yet. */
    private int factor(Side side, CgVfxRibbonRendererProperties r, int slot, float total, float lengthSq, float lengthAngle) {
        if (total <= 0) return 1;
        float inverse = 1f / Math.max(1f, total);
        float angle = lengthAngle * inverse, segment = lengthSq * inverse;
        float curvature = segment / Math.max(1e-8f, Math.abs((float) Math.sin(angle)));
        CgVfxRibbonRendererProperties.Tessellation t = r.tessellation();
        int most = 16;
        float minAngle = (float) Math.toRadians(15), screen = 0.002f;
        boolean constant = false;
        if (t.mode() == CgVfxRibbonRendererProperties.TessellationMode.CUSTOM) {
            most = Math.min(most, t.factor());
            constant = t.constant();
            minAngle = t.minAngle();
            screen = t.screenSpace() && !constant ? 0.002f : 0f;
        } else if (t.mode() == CgVfxRibbonRendererProperties.TessellationMode.DISABLED) {
            most = 1;
        }
        if (constant) return most;
        if (most <= 1 || curvature <= 1e-8f) return 1;
        float least = minAngle == 0f ? most : Math.max(1f, angle / Math.max(1e-8f, minAngle));
        CgVfxParticlePool pool = side.pool;
        double dx = pool.origin(slot, 0) - CAMERA_X, dy = pool.origin(slot, 1) - CAMERA_Y, dz = pool.origin(slot, 2) - CAMERA_Z;
        float error = Math.max(0.005f, screen * (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        float tess = angle / Math.max(0.002f, (float) Math.acos(curvature / (curvature + error)));
        return Math.min(Math.max(Math.round(tess), Math.round(least)), most);
    }

    private static float[] vec(Side side, int v) {
        ByteBuffer run = side.run;
        return new float[] {run.getFloat(v * 16), run.getFloat(v * 16 + 4), run.getFloat(v * 16 + 8), run.getFloat(v * 16 + 12)};
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

    /** Unreal's FMath::CubicInterp, and its derivative. */
    private static float[] cubic(float[] p0, float[] t0, float[] p1, float[] t1, float a) {
        float a2 = a * a, a3 = a2 * a, h0 = 2 * a3 - 3 * a2 + 1, h1 = a3 - 2 * a2 + a, h2 = a3 - a2, h3 = -2 * a3 + 3 * a2;
        return new float[] {h0 * p0[0] + h1 * t0[0] + h2 * t1[0] + h3 * p1[0], h0 * p0[1] + h1 * t0[1] + h2 * t1[1] + h3 * p1[1],
                h0 * p0[2] + h1 * t0[2] + h2 * t1[2] + h3 * p1[2]};
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

    private static float[] pos(float[] d) {
        return new float[] {d[0], d[1], d[2]};
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] add(float[] a, float[] b) {
        return new float[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static float[] scale(float[] a, float s) {
        return new float[] {a[0] * s, a[1] * s, a[2] * s};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float len(float[] a) {
        return (float) Math.sqrt(dot(a, a));
    }

    private static float[] unit(float[] a) {
        float l = len(a);
        return l > 1e-8f ? scale(a, 1 / l) : new float[3];
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
        if (GlErrorChecker.checkAndLog("vfx-ribbons")) {
            System.out.println("[vfx-ribbons] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-ribbons] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-ribbons] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-ribbons] FAIL on " + on + ": the run never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-ribbons] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-ribbons] PASS on " + on + ": " + checked + " records of four ribbon slots, Niagara's,"
                    + " a shaped one broken by its link gap and Cascade's trail of " + trail.size() + " spawned over "
                    + TICKS + " moving steps, an anim trail of " + twins.get(1).size() + " samples in two chains, refit "
                    + refits + " times, held to the twin to " + TOLERANCE + "; " + units + " units drawn");
        }
    }

    @Override public void dispose() {}

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
