package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.CgVfxModule.BeamSource.Tangent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The gate for beams: Cascade's beam modules stepped on the GPU, each beam's record, its run (the state its strip reads)
 * and its drawn points held to a Java simulation of the same beams. Each case is a slot: a straight beam, a Hermite by
 * its tangents under a turned effect, a far end travelling and one arriving, noise re-picked every step, at a lock time
 * with a rolled range scale, smoothed at a rolled speed, oscillating, a rolled-down frequency set by distance, tessellated
 * with a rolled tangent strength, on interpolation points, and with noise on the target. Beams are seeded newborn and
 * stepped {@link #STEPS} times; the noise's picks and every per-frame roll go through {@code fx_rand}, whose Java twin
 * ({@code CgVfxEmitterInstance.rand}) makes the simulation exact. The drawn points are the strip factory's own
 * {@code fx_beam_point}, run by {@code harness:shaders/vfx_beam_points.compute}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-beams"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-beams" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-beams --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-beams] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxBeamsScene implements InteractiveSceneLifecycle {

    private static final int STEPS = 14, DRAIN = 60, BEAMS = 3, CAPACITY = 4, STRIDE = 25;
    private static final float DT = 1f / 30f, TOLERANCE = 2e-4f;
    private static final float[] IDENTITY = {0f, 0f, 0f, 1f};
    /** A quarter turn about +Y. */
    private static final float[] QUARTER = {0f, (float) Math.sqrt(0.5), 0f, (float) Math.sqrt(0.5)};
    private static final int HEADER = 7, TAG = 0, SOURCE = 1, SOURCE_TANGENT = 2, TARGET = 3, TARGET_TANGENT = 4,
            NOISE = 5, TURN = 6;

    /** One case: a slot's modules, its effect's turn, and its draw's interpolation points. */
    private record Case(String name, CgVfxModule.BeamSource source, CgVfxModule.BeamTarget target,
                        CgVfxModule.BeamNoise noise, float[] turn, int interpolationPoints) {
        int segments() {
            return noise != null ? (noise.frequency() + 1) * Math.max(noise.tessellation(), 1) : Math.max(interpolationPoints, 1);
        }

        float[] custom2() {
            return new float[]{interpolationPoints, noise != null && noise.targetNoise() ? 1f : 0f, 1f,
                    noise != null ? noise.frequency() : 0f};
        }
    }

    private static CgVfxModule.BeamSource source(float tx, float ty, float tz, Tangent method, float strength) {
        return new CgVfxModule.BeamSource(0f, 0f, 0f, false, tx, ty, tz, method, strength, false, false, false);
    }

    private static CgVfxModule.BeamTarget target(float x, float y, float z, float tx, float ty, float tz, Tangent method,
                                                 float strength, float lockRadius, float speed) {
        return new CgVfxModule.BeamTarget(x, y, z, false, tx, ty, tz, method, strength, false, false, false, lockRadius, speed);
    }

    /** Noise of frequency 7 over ±(0.5, 0.5, 0.2), varied by {@code with}'s arguments. */
    private static CgVfxModule.BeamNoise noise(int low, float lockTime, boolean smooth, boolean oscillate, int tessellation,
                                               float rangeScaleMin, float rangeScaleMax, float tangentMin, float tangentMax,
                                               float frequencyDistance, float scale, boolean targetNoise) {
        return new CgVfxModule.BeamNoise(7, low, lockTime, smooth, -0.5f, -0.5f, -0.2f, 0.5f, 0.5f, 0.2f, true, oscillate,
                1f, 1f, 1f, 2f, 3f, 4f, 0.01f, tessellation, rangeScaleMin, rangeScaleMax, tangentMin, tangentMax,
                frequencyDistance, scale, targetNoise, 2);
    }

    private static final Case[] PLAIN = {
            new Case("straight", source(0f, 0f, 0f, Tangent.DIRECT, 0f),
                    target(8f, 0f, 0f, 0f, 0f, 0f, Tangent.DIRECT, 0f, 0.01f, 0f), null, IDENTITY, 0),
            new Case("hermite", source(0f, 1f, 0f, Tangent.USER_SET, 4f),
                    target(6f, 2f, 0f, 0f, -1f, 0f, Tangent.USER_SET, 4f, 0.01f, 0f), null, QUARTER, 12),
            new Case("travelling", source(0f, 0f, 0f, Tangent.DIRECT, 0f),
                    target(0f, 0f, 9f, 0f, 0f, 0f, Tangent.DIRECT, 0f, 0.05f, 6f), null, IDENTITY, 0),
            new Case("arriving", source(0f, 0f, 0f, Tangent.DIRECT, 0f),
                    target(0f, 0f, 9f, 0f, 0f, 0f, Tangent.DIRECT, 0f, 0.5f, 28f), null, IDENTITY, 0),
    };

    private static final Case[] NOISY = {
            new Case("noise each step", source(0f, 0f, 0f, Tangent.DIRECT, 0f), target(10f, 0f, 0f, 0f, 0f, 0f, Tangent.DIRECT,
                    0f, 0.01f, 0f), noise(0, 0f, false, false, 1, 1f, 1f, 0f, 0f, 0f, 1f, false), IDENTITY, 0),
            new Case("noise lock time", source(0f, 0f, 0f, Tangent.DIRECT, 0f), target(10f, 0f, 0f, 0f, 0f, 0f, Tangent.DIRECT,
                    0f, 0.01f, 0f), noise(0, 0.11f, false, false, 1, 0.5f, 1.5f, 0f, 0f, 0f, 1f, false), QUARTER, 0),
            new Case("noise smooth", source(0f, 0f, 0f, Tangent.DIRECT, 0f), target(10f, 0f, 0f, 0f, 0f, 0f, Tangent.DIRECT,
                    0f, 0.01f, 0f), noise(0, 0.11f, true, false, 1, 1f, 1f, 0f, 0f, 0f, 1f, false), IDENTITY, 0),
            new Case("noise oscillating", source(0f, 0f, 0f, Tangent.DIRECT, 0f), target(10f, 0f, 0f, 0f, 0f, 0f, Tangent.DIRECT,
                    0f, 0.01f, 0f), noise(0, 0.11f, false, true, 1, 1f, 1f, 0f, 0f, 0f, 1f, false), IDENTITY, 0),
            new Case("noise by distance", source(0f, 0f, 0f, Tangent.DIRECT, 0f), target(10f, 0f, 0f, 0f, 0f, 0f, Tangent.DIRECT,
                    0f, 0.01f, 0f), noise(3, 0f, false, false, 1, 1f, 1f, 0f, 0f, 2.5f, 0.5f, false), IDENTITY, 0),
            new Case("noise tessellated", source(0f, 1f, 0f, Tangent.USER_SET, 3f), target(10f, 0f, 0f, 1f, 0f, 0f,
                    Tangent.USER_SET, 3f, 0.01f, 0f), noise(0, 0.11f, false, false, 3, 1f, 1f, 0.5f, 2f, 0f, 1f, false), QUARTER, 0),
            new Case("noise interpolated", source(0f, 1f, 0f, Tangent.USER_SET, 5f), target(10f, 0f, 0f, 0f, -1f, 0f,
                    Tangent.USER_SET, 5f, 0.01f, 0f), noise(0, 0.11f, false, false, 1, 1f, 1f, 0f, 0f, 0f, 1f, false), IDENTITY, 20),
            new Case("noise on the target", source(0f, 0f, 0f, Tangent.DIRECT, 0f), target(10f, 0f, 0f, 0f, 0f, 0f,
                    Tangent.DIRECT, 0f, 0.01f, 0f), noise(0, 0f, false, false, 1, 1f, 1f, 0f, 0f, 0f, 1f, true), IDENTITY, 0),
    };

    /** One pool: its cases a slot each, its beams, and what was read back. */
    private static final class Pool {
        final String name;
        final Case[] cases;
        final List<Beam> beams = new ArrayList<>();
        CgVfxEmitter[] emitters;
        CgVfxParticlePool pool;
        ByteBuffer custom;
        int points, steps, live = -1;
        ByteBuffer records, runs, drawn;

        Pool(String name, Case[] cases) {
            this.name = name;
            this.cases = cases;
        }
    }

    /** One beam as Java steps it: its record's state and its run as the noise kernel writes it. */
    private static final class Beam {
        final Case c;
        final int slot, id, seedBits;
        final float[] source = new float[4], sourceTangent = new float[4], target = new float[4],
                targetTangent = new float[4], noise = new float[4], turn = new float[4], position = new float[3];
        float age;
        float[] run, last;

        Beam(Case c, int slot, int id, float seed, int per) {
            this.c = c;
            this.slot = slot;
            this.id = id;
            this.seedBits = Float.floatToRawIntBits(seed);
            run = new float[per * 4];
            last = new float[per * 4];
        }
    }

    private final Pool[] pools = {new Pool("plain", PLAIN), new Pool("noisy", NOISY)};
    private final List<CgRequest> requests = new ArrayList<>();
    private CgCompute kernels;
    private int frame, checked;
    private float worst;
    private String failure;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        kernels = CgCompute.load("harness:shaders/vfx_beam_points.compute");
        for (Pool p : pools) {
            p.emitters = new CgVfxEmitter[p.cases.length];
            for (int i = 0; i < p.cases.length; i++) {
                Case c = p.cases[i];
                CgVfxEmitter.Builder b = CgVfxEmitter.builder("vfx-beams " + c.name()).capacity(CAPACITY).rate(0f)
                        .life(100f, 100f).size(0.1f, 0.1f).module(c.source()).module(c.target());
                if (c.noise() != null) b.module(c.noise());
                p.emitters[i] = b.build();
                CgVfxParticlePool.prepare(p.emitters[i]);
            }
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= STEPS) {
            step(frame);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (answered || frame > STEPS + DRAIN) {
            if (answered && !on().startsWith("recording")) compare();
            report();
            CgVfxParticlePool.release(this);
            running = false;
        }
    }

    private void step(int n) {
        CgRecording rec = new CgRecording();
        if (n == 1) open(rec);
        float loop = n * DT;
        for (Pool p : pools) {
            p.pool.beginStep(DT, 0f, 0f, 0f);
            for (int s = 0; s < p.cases.length; s++) p.pool.instance(s, 0x5EED + s, 1f, Float.NaN, view(p.cases[s].turn(), loop));
            p.pool.endStep();
            p.pool.record(rec);
            for (Beam b : p.beams) simulate(p, b, loop);
            p.steps++;
        }
        if (n == STEPS) {
            for (Pool p : pools) {
                CgGraphBuffer cases = CgGraphBuffer.transientBuffer("vfx-beams." + p.name + ".cases",
                        CgBufferDesc.elements(p.cases.length * 2, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                CgGraphBuffer out = CgGraphBuffer.transientBuffer("vfx-beams." + p.name + ".points",
                        CgBufferDesc.elements(p.pool.storage() * STRIDE, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                rec.update(cases, 0, p.custom.duplicate());
                CgComputePass pass = rec.compute("vfx-beams." + p.name);
                pass.dispatch(kernels.kernel("Points"), p.pool.storage() * STRIDE).bind("RECORDS", p.pool.records())
                        .bind("LIVE", p.pool.live()).bind("TABLE", p.pool.beamTable()).bind("RUNS", p.pool.beamRuns())
                        .bind("CASES", cases).bind("POINTS", out).set("_RecordVectors", p.pool.record().vectors())
                        .set("_Stride", STRIDE).set("_NoisePoints", p.points);
                pass.end();
                requests.add(rec.readback(out, 0, (long) p.pool.storage() * STRIDE * 16, data -> p.drawn = copy(data)));
                requests.add(rec.readback(p.pool.live(), 0, 4, data -> p.live = data.getInt(0)));
                requests.add(rec.readback(p.pool.records(), 0, (long) p.pool.storage() * p.pool.record().bytes(),
                        data -> p.records = copy(data)));
                requests.add(rec.readback(p.pool.beamRuns(), 0, p.pool.beamRuns().size(), data -> p.runs = copy(data)));
            }
        }
        CgImmediate.execute(rec);
    }

    /** Each pool's slots opened in order, its beams seeded newborn, and its cases' draw constants uploaded. */
    private void open(CgRecording rec) {
        int id = 0;
        for (Pool p : pools) {
            p.pool = CgVfxParticlePool.of(this, p.emitters[0]);
            for (int s = 0; s < p.cases.length; s++) {
                int slot = p.pool.open(p.emitters[s], CAPACITY);
                if (slot != s) fail(p.name + "'s slot " + s + " opened as " + slot + ": give the pool a fresh owner");
            }
            p.points = p.pool.shape().beamPoints();
            int per = p.pool.beamRunVectors();
            CgVfxRecord layout = p.pool.record();
            int total = p.cases.length * BEAMS;
            ByteBuffer packed = ByteBuffer.allocateDirect(total * layout.bytes()).order(ByteOrder.nativeOrder());
            for (int s = 0, r = 0; s < p.cases.length; s++) {
                for (int i = 0; i < BEAMS; i++, r++, id++) {
                    float seed = (id + 0.5f) / 64f;
                    layout.put(packed, r, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 100f, 0.1f, seed, id, s);
                    layout.putI(packed, r, CgVfxRecord.PARAM_ROW, p.pool.paramRow(s));
                    p.beams.add(new Beam(p.cases[s], s, id, seed, per));
                }
            }
            p.pool.seed(rec, packed, total);
            ByteBuffer custom = ByteBuffer.allocateDirect(p.cases.length * 32).order(ByteOrder.nativeOrder());
            for (Case c : p.cases) {
                for (float f : c.custom2()) custom.putFloat(f);
                custom.putFloat(c.segments()).putFloat(0f).putFloat(0f).putFloat(0f);
                if (c.segments() + 1 > STRIDE) fail(c.name() + " draws " + c.segments() + " segments, past the stride");
            }
            p.custom = custom.flip();
        }
    }

    private static CgVfxInstanceView view(float[] turn, float loop) {
        return new CgVfxInstanceView() {
            public double originX() { return 12.5; }
            public double originY() { return 64.0; }
            public double originZ() { return -30.25; }
            public float time() { return loop; }
            public float sourceX() { return 0f; }
            public float sourceY() { return 0f; }
            public float sourceZ() { return 0f; }
            public float rotationX() { return turn[0]; }
            public float rotationY() { return turn[1]; }
            public float rotationZ() { return turn[2]; }
            public float rotationW() { return turn[3]; }
            public float loopSeconds() { return loop; }
        };
    }

    // ---- The Java simulation: Cascade's beam modules as ParticleBeamModules.cpp updates them, one step at a time ----

    /** One step of beam {@code b}: its kinds in the Step, then the noise kernel writing its run. */
    private static void simulate(Pool p, Beam b, float loop) {
        Case c = b.c;
        boolean born = b.age <= 0f;
        // BeamSource (ParticleBeamModules.cpp:1886-2112): resolved at birth, and each update unless locked.
        if (born) b.source[3] = 0f;
        CgVfxModule.BeamSource s = c.source();
        resolve(b.source, b.sourceTangent, born, s.x(), s.y(), s.z(), s.tx(), s.ty(), s.tz(), s.method(), s.strength(),
                s.lock(), s.lockTangent(), s.lockStrength(), c.turn());
        if (born) set3(b.position, b.source);
        // BeamTarget (:2335, :2507), and the far end's travel toward it (:300-326).
        CgVfxModule.BeamTarget t = c.target();
        resolve(b.target, b.targetTangent, born, t.x(), t.y(), t.z(), t.tx(), t.ty(), t.tz(), t.method(), t.strength(),
                t.lock(), t.lockTangent(), t.lockStrength(), c.turn());
        if (t.speed() <= 0f || b.source[3] > 0.5f) {
            set3(b.position, b.target);
            b.source[3] = 1f;
        } else {
            float[] d = unit(sub(b.target, b.position), new float[3]);
            float[] next = new float[3];
            for (int a = 0; a < 3; a++) next[a] = b.position[a] + d[a] * t.speed() * DT;
            boolean within = true;
            for (int a = 0; a < 3; a++) within &= Math.abs(next[a] - b.target[a]) < t.lockRadius();
            if (within) {
                set3(b.position, b.target);
                b.source[3] = 1f;
            } else {
                set3(b.position, next);
            }
        }
        CgVfxModule.BeamNoise nz = c.noise();
        if (nz != null) {
            // BeamNoise (:1421-1426, :1514-1544): the frequency rolled at birth, the clock and the picks after.
            float[] n = b.noise;
            if (born) {
                float frequency = nz.frequency();
                if (nz.frequencyLow() > 0) {
                    frequency = (float) Math.floor(rand(b.seedBits, b.id, 0xBEA0) * (nz.frequency() - nz.frequencyLow())
                            + nz.frequencyLow());
                }
                n[0] = frequency;
                n[1] = 0f;
                n[2] = 0f;
                n[3] = 1f;
            } else if (nz.lockTime() > 1e-4f) {
                n[1] += DT;
                if (n[1] > nz.lockTime()) {
                    n[1] = 0f;
                    n[2] += 1f;
                }
            } else if (nz.lockTime() >= 0f) {
                n[2] += 1f;
            }
            // Range scale at the emitter's time (ParticleBeam2EmitterInstance.cpp:1403-1410).
            float rangeScale = mix(nz.rangeScaleMin(), nz.rangeScaleMax(), rand(b.slot, Float.floatToRawIntBits(loop), 0xBEA1));
            if (nz.frequencyDistance() > 0f && b.source[3] > 0.5f) {
                b.target[3] = Math.min((float) Math.floor(length(sub(b.target, b.source)) / nz.frequencyDistance()), n[0]);
                n[3] = nz.scale() * rangeScale;
            } else {
                b.target[3] = n[0];
                n[3] = rangeScale;
            }
            System.arraycopy(c.turn(), 0, b.turn, 0, 4);
        }
        b.age += DT;
        writeRun(p, b);
    }

    /** One end resolved: from the effect's origin, turned with it; a direct tangent its forward. */
    private static void resolve(float[] point, float[] tangent, boolean born, float x, float y, float z, float tx, float ty,
                                float tz, Tangent method, float strength, boolean lock, boolean lockTangent,
                                boolean lockStrength, float[] q) {
        if (born || !lock) set3(point, turn(q, new float[]{x, y, z}));
        if (born || !lockTangent) {
            if (method == Tangent.DIRECT) set3(tangent, turn(q, new float[]{1f, 0f, 0f}));
            else if (method == Tangent.USER_SET) set3(tangent, turn(q, new float[]{tx, ty, tz}));
            else set3(tangent, new float[]{tx, ty, tz});
        }
        if (born || !lockStrength) tangent[3] = strength;
    }

    /** The run as the noise kernel writes it: a tag, the tail's copies, then the noise's current and next points. */
    private static void writeRun(Pool p, Beam b) {
        float[] last = b.run;
        b.run = b.last;
        b.last = last;
        float[] run = b.run;
        CgVfxModule.BeamNoise nz = b.c.noise();
        float picks = b.noise[2], clock = b.noise[1];
        int tess = nz != null ? nz.tessellation() : 0;
        float tangent = nz != null ? mix(nz.tangentStrengthMin(), nz.tangentStrengthMax(), rand(b.slot, p.steps, 0xBEA2)) : 0f;
        put(run, TAG, (b.id & 0xFFFFFF) + 1f, picks, Math.max(tess, 1), tangent);
        put(run, SOURCE, b.source);
        put(run, SOURCE_TANGENT, b.sourceTangent);
        put(run, TARGET, b.target);
        put(run, TARGET_TANGENT, b.targetTangent);
        put(run, NOISE, b.noise);
        put(run, TURN, b.turn);
        if (nz == null || p.points == 0) return;
        boolean born = Math.abs(last[0] - (b.id & 0xFFFFFF) - 1f) > 0.5f;
        boolean picked = born || last[1] != picks;
        float[] speed = {
                mix(nz.speedMinX(), nz.speedMaxX(), rand(b.slot, p.steps, 0xBEA3)),
                mix(nz.speedMinY(), nz.speedMaxY(), rand(b.slot, p.steps, 0xBEA4)),
                mix(nz.speedMinZ(), nz.speedMaxZ(), rand(b.slot, p.steps, 0xBEA5))};
        for (int j = 0; j < p.points; j++) {
            int pointAt = HEADER + j, nextAt = pointAt + p.points;
            float[] point = vec3(last, pointAt), next = vec3(last, nextAt);
            if (born) {
                point = pick(nz, j, 0, true, picks, b);
                next = nz.smooth() ? pick(nz, j, 1, true, picks, b) : point;
            } else if (picked) {
                if (nz.lockTime() > 1e-4f && nz.smooth()) next = pick(nz, j, 1, false, picks, b);
                else point = pick(nz, j, 0, false, picks, b);
            }
            put(run, nextAt, next[0], next[1], next[2], 0f);
            // Smooth points move toward the next twice a sheet (ParticleSystemRender.cpp:2509-2524).
            if (nz.smooth() && nz.lockTime() >= 0f) {
                for (int i = 0, moves = 2 * Math.max(nz.sheets(), 1); i < moves; i++) {
                    float[] d = sub(next, point);
                    float l = length(d);
                    float[] check = new float[3];
                    boolean within = true;
                    for (int a = 0; a < 3; a++) {
                        check[a] = point[a] + (l > 1e-8f ? d[a] / l : 0f) * speed[a] * clock;
                        within &= Math.abs(check[a] - next[a]) < nz.lockRadius();
                    }
                    point = within ? next : check;
                }
            }
            put(run, pointAt, point[0], point[1], point[2], 0f);
        }
    }

    /** Point j's pick (ParticleBeamModules.cpp:1428-1457): which 0 the point, 1 the next a smooth beam heads for. */
    private static float[] pick(CgVfxModule.BeamNoise nz, int j, int which, boolean born, float picks, Beam b) {
        float[] min = {nz.minX(), nz.minY(), nz.minZ()}, max = {nz.maxX(), nz.maxY(), nz.maxZ()};
        float extreme = 0f;
        if (nz.uniform() && nz.oscillate()) {
            if (!born) extreme = ((b.id + (int) picks) & 1) == 0 ? 1f : -1f;
            else if (nz.smooth()) extreme = which == 0 ? 1f : -1f;
            else extreme = (j & 1) == 0 ? 1f : -1f;
        }
        if (extreme > 0f) return max;
        if (extreme < 0f) return min;
        int key = b.seedBits ^ ((int) picks * 0x9E3779B1);
        int k = j * 8 + which * 4;
        return new float[]{mix(min[0], max[0], rand(key, b.id, k)), mix(min[1], max[1], rand(key, b.id, k + 1)),
                mix(min[2], max[2], rand(key, b.id, k + 2))};
    }

    // ---- The drawn points from a run, as ParticleSystemRender.cpp's beam fills place them ----

    /** Point k of a beam's strip, as FillVertexData_NoNoise (:2948) and FillData_Noise (:3361) place it. */
    private static float[] point(float[] run, int k, int segments, int noisePoints, float[] end, float[] c2) {
        float[] source = vec4(run, SOURCE);
        k = Math.max(0, Math.min(k, segments));
        if (source[3] < 0.5f) return lerp(source, end, (float) k / Math.max(segments, 1));
        if (noisePoints == 0) {
            if (c2[0] <= 0f) return lerp(source, vec4(run, TARGET), Math.min(k, 1));
            return curve(run, k / c2[0]);
        }
        float[] tag = vec4(run, TAG);
        int steps = (int) (vec4(run, TARGET)[3] + 0.5f), tess = Math.max((int) (tag[2] + 0.5f), 1);
        boolean targetNoise = ((int) (c2[1] + 0.5f) & 1) != 0;
        int j = Math.min(k / tess, steps + 1), within = k - j * tess;
        float[] a = draw(run, j, steps, c2[0], c2[3], targetNoise);
        if (within == 0 || tess <= 1 || j > steps) return a;
        float[] b = draw(run, j + 1, steps, c2[0], c2[3], targetNoise);
        float[] turn = vec4(run, TURN);
        // Inner tangents along their neighbours at the noise's tangent strength (:3640-3650).
        float[] ta = j == 0 ? tangent(vec4(run, SOURCE_TANGENT), turn)
                : scale(unit(sub(b, draw(run, j - 1, steps, c2[0], c2[3], targetNoise)), new float[3]), tag[3]);
        float[] targetTangent = vec4(run, TARGET_TANGENT);
        float[] tb = j + 1 > steps ? scale(unit(targetTangent, new float[3]), targetTangent[3])
                : scale(unit(sub(draw(run, j + 2, steps, c2[0], c2[3], targetNoise), a), new float[3]), tag[3]);
        return cubic(a, ta, b, tb, (float) within / tess);
    }

    /** Noise point j of steps, on the line or the tangents' curve (FillData_InterpolatedNoise, :4441-4627), plus its noise. */
    private static float[] draw(float[] run, int j, int steps, float points, float frequency, boolean targetNoise) {
        float[] s = vec4(run, SOURCE), t = vec4(run, TARGET);
        if (j <= 0) return new float[]{s[0], s[1], s[2]};
        if (j > steps) {
            float[] o = targetNoise ? offset(run, (int) (frequency + 0.5f)) : new float[3];
            return new float[]{t[0] + o[0], t[1] + o[1], t[2] + o[2]};
        }
        float[] line;
        if (points > 0f) {
            float stepSize = points / steps, frac = stepSize - (float) Math.floor(stepSize);
            int index = (int) stepSize, s0 = (j - 1) * index, last = (int) (points + 0.5f) - 1;
            float[] a = curve(run, (Math.min(s0, last) + 1) / points);
            float[] b = j == steps ? t : curve(run, (Math.min(s0 + 1, last) + 1) / points);
            line = new float[3];
            for (int i = 0; i < 3; i++) line[i] = a[i] * (1f - frac) + b[i] * frac;
        } else {
            line = new float[3];
            for (int i = 0; i < 3; i++) line[i] = s[i] + (t[i] - s[i]) * ((float) j / (steps + 1));
        }
        float[] o = offset(run, j - 1);
        return new float[]{line[0] + o[0], line[1] + o[1], line[2] + o[2]};
    }

    /** Noise point j turned with its effect and scaled: NoiseRangeScale times LocalToWorld of the point. */
    private static float[] offset(float[] run, int j) {
        return scale(turn(vec4(run, TURN), vec3(run, HEADER + j)), vec4(run, NOISE)[3]);
    }

    /** The tangents' curve at a: FMath::CubicInterp of the ends (ParticleBeamModules.cpp:483-527). */
    private static float[] curve(float[] run, float a) {
        float[] turn = vec4(run, TURN);
        return cubic(vec4(run, SOURCE), tangent(vec4(run, SOURCE_TANGENT), turn), vec4(run, TARGET),
                tangent(vec4(run, TARGET_TANGENT), turn), a);
    }

    /** A tangent times its strength; a zero one the effect's forward (:497-514). */
    private static float[] tangent(float[] t, float[] turn) {
        float[] d = t[0] * t[0] + t[1] * t[1] + t[2] * t[2] > 1e-12f ? t : turn(turn, new float[]{1f, 0f, 0f});
        return new float[]{d[0] * t[3], d[1] * t[3], d[2] * t[3]};
    }

    private static int filled(float[] run, int noisePoints, int segments) {
        int filled = noisePoints > 0 && vec4(run, SOURCE)[3] > 0.5f
                ? ((int) (vec4(run, TARGET)[3] + 0.5f) + 1) * Math.max((int) (vec4(run, TAG)[2] + 0.5f), 1) : segments;
        return Math.min(filled, segments);
    }

    // ---- The comparison ----

    private void compare() {
        for (Pool p : pools) {
            if (p.live != p.beams.size()) {
                fail(p.name + ": " + p.live + " alive, not " + p.beams.size());
                continue;
            }
            CgVfxRecord layout = p.pool.record();
            int tail = layout.word(CgVfxRecord.Tail.BEAM), per = p.pool.beamRunVectors();
            for (int r = 0; r < p.live; r++) {
                int slot = layout.i(p.records, r, CgVfxRecord.SLOT), id = layout.i(p.records, r, CgVfxRecord.ID) & 0xFFFFFF;
                Beam b = find(p, slot, id);
                if (b == null) {
                    fail(p.name + ": record " + r + " is slot " + slot + "'s id " + id + ", seeded by nobody");
                    continue;
                }
                String who = p.name + " " + b.c.name() + " id " + id;
                for (int a = 0; a < 3; a++) near(who + " far end " + "xyz".charAt(a), layout.f(p.records, r, CgVfxRecord.POSITION + a), b.position[a]);
                float[][] tails = {b.source, b.sourceTangent, b.target, b.targetTangent, b.noise, b.turn};
                for (int v = 0; v < tails.length; v++) {
                    for (int a = 0; a < 4; a++) near(who + " tail " + v + "." + a, layout.f(p.records, r, tail + v * 4 + a), tails[v][a]);
                }
                int at = p.pool.beamFirst(slot) + (id % CAPACITY) * per;
                float[] gpuRun = new float[per * 4];
                for (int w = 0; w < gpuRun.length; w++) gpuRun[w] = p.runs.getFloat((at * 4 + w) * 4);
                for (int w = 0; w < gpuRun.length; w++) near(who + " run vec4 " + w / 4 + "." + w % 4, gpuRun[w], b.run[w]);
                Case c = b.c;
                int segments = c.segments(), filled = filled(b.run, p.points, segments);
                float[] c2 = c.custom2();
                int base = r * STRIDE * 16;
                near(who + " segments filled", p.drawn.getFloat(base + 12), filled);
                for (int k = 0; k <= segments; k++) {
                    float[] want = point(b.run, Math.min(k, filled), filled, p.points, b.position, c2);
                    for (int a = 0; a < 3; a++) near(who + " point " + k + " " + "xyz".charAt(a), p.drawn.getFloat(base + k * 16 + a * 4), want[a]);
                }
                shape(p, b, who, filled);
            }
        }
    }

    /** What each case is for, beyond agreeing with Java: so a simulation wrong in the same way still fails. */
    private void shape(Pool p, Beam b, String who, int filled) {
        Case c = b.c;
        float travelled = STEPS * DT * c.target().speed();
        switch (c.name()) {
            case "travelling" -> {
                if (b.source[3] > 0.5f) fail(who + " arrived; at " + c.target().speed() + " a second it must still travel");
                near(who + " distance travelled", b.position[2], travelled);
            }
            case "arriving" -> {
                if (b.source[3] < 0.5f) fail(who + " has not arrived within " + STEPS + " steps");
            }
            case "noise by distance" -> {
                int want = Math.min(4, (int) b.noise[0]);
                near(who + " steps by distance", b.target[3], want);
            }
            case "noise each step" -> near(who + " picks", b.noise[2], STEPS - 1);
            case "noise oscillating" -> {
                for (int j = 0; j < p.points; j++) {
                    float[] v = vec3(b.run, HEADER + j);
                    boolean top = v[0] == 0.5f && v[1] == 0.5f && v[2] == 0.2f, bottom = v[0] == -0.5f && v[1] == -0.5f && v[2] == -0.2f;
                    if (!top && !bottom) fail(who + " point " + j + " is no extreme of its range: " + v[0] + ", " + v[1] + ", " + v[2]);
                }
            }
            default -> {
            }
        }
        if (c.noise() != null && filled != (int) (b.target[3] + 1.5f) * Math.max(c.noise().tessellation(), 1)) {
            fail(who + " fills " + filled + " segments");
        }
    }

    private static Beam find(Pool p, int slot, int id) {
        for (Beam b : p.beams) if (b.slot == slot && b.id == id) return b;
        return null;
    }

    private void near(String what, float got, float want) {
        checked++;
        float error = Math.abs(got - want) / Math.max(1f, Math.abs(want));
        worst = Math.max(worst, error);
        if (!(error <= TOLERANCE)) fail(what + " is " + got + ", not " + want);
    }

    private void fail(String why) {
        if (failure == null) failure = why;
    }

    private static String on() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        return (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
    }

    private void report() {
        String on = on();
        int validation = PlatformServiceHarness.validationErrors();
        int done = 0;
        String unanswered = null;
        for (CgRequest r : requests) {
            if (r.done()) done++;
            else if (unanswered == null) unanswered = r + (r.failure() != null ? ": " + r.failure() : "");
        }
        if (GlErrorChecker.checkAndLog("vfx-beams")) {
            System.out.println("[vfx-beams] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-beams] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[vfx-beams] FAIL on " + on + ": " + done + " of " + requests.size() + " answered; " + unanswered);
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-beams] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[vfx-beams] FAIL on " + on + ": " + failure);
        } else {
            System.out.printf("[vfx-beams] PASS on %s: %d values over %d cases, worst relative error %.2e%n", on, checked,
                    PLAIN.length + NOISY.length, worst);
        }
    }

    // ---- Vectors ----

    private static float rand(int seed, int n, int k) {
        return CgVfxEmitterInstance.rand(seed, n, k);
    }

    /** GLSL's mix. */
    private static float mix(float a, float b, float t) {
        return a * (1f - t) + b * t;
    }

    /** v turned by the unit quaternion q. */
    private static float[] turn(float[] q, float[] v) {
        float tx = 2f * (q[1] * v[2] - q[2] * v[1]), ty = 2f * (q[2] * v[0] - q[0] * v[2]), tz = 2f * (q[0] * v[1] - q[1] * v[0]);
        return new float[]{v[0] + q[3] * tx + (q[1] * tz - q[2] * ty), v[1] + q[3] * ty + (q[2] * tx - q[0] * tz),
                v[2] + q[3] * tz + (q[0] * ty - q[1] * tx)};
    }

    /** FMath::CubicInterp. */
    private static float[] cubic(float[] p0, float[] t0, float[] p1, float[] t1, float a) {
        float a2 = a * a, a3 = a2 * a;
        float h0 = 2f * a3 - 3f * a2 + 1f, h1 = a3 - 2f * a2 + a, h2 = a3 - a2, h3 = -2f * a3 + 3f * a2;
        return new float[]{h0 * p0[0] + h1 * t0[0] + h2 * t1[0] + h3 * p1[0], h0 * p0[1] + h1 * t0[1] + h2 * t1[1] + h3 * p1[1],
                h0 * p0[2] + h1 * t0[2] + h2 * t1[2] + h3 * p1[2]};
    }

    private static float[] unit(float[] v, float[] fallback) {
        float l = length(v);
        return l > 1e-8f ? new float[]{v[0] / l, v[1] / l, v[2] / l} : fallback;
    }

    private static float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] scale(float[] v, float s) {
        return new float[]{v[0] * s, v[1] * s, v[2] * s};
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static void set3(float[] to, float[] from) {
        to[0] = from[0];
        to[1] = from[1];
        to[2] = from[2];
    }

    private static float[] vec3(float[] run, int v) {
        return new float[]{run[v * 4], run[v * 4 + 1], run[v * 4 + 2]};
    }

    private static float[] vec4(float[] run, int v) {
        return new float[]{run[v * 4], run[v * 4 + 1], run[v * 4 + 2], run[v * 4 + 3]};
    }

    private static void put(float[] run, int v, float x, float y, float z, float w) {
        run[v * 4] = x;
        run[v * 4 + 1] = y;
        run[v * 4 + 2] = z;
        run[v * 4 + 3] = w;
    }

    private static void put(float[] run, int v, float[] four) {
        put(run, v, four[0], four[1], four[2], four[3]);
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
        out.put(data.duplicate().order(ByteOrder.nativeOrder()));
        return out.flip();
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
