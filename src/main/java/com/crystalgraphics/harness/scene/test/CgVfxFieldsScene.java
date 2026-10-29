package com.crystalgraphics.harness.scene.test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.joml.Quaternionf;
import org.joml.Vector3f;

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
import com.crystalgraphics.vfx.field.CgFieldBounds;
import com.crystalgraphics.vfx.field.CgFieldDefinition;
import com.crystalgraphics.vfx.field.CgFieldEval;
import com.crystalgraphics.vfx.field.CgFieldPlacement;
import com.crystalgraphics.vfx.field.CgFieldWorld;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;

/**
 * The gate for force fields on the GPU: each of Chaos's node kinds the GPU evaluates, a turned and a world-oriented
 * placement and the four targets it applies, each a station of its own group. 512 particles are seeded at known places
 * with no velocity and stepped once; each one's change of velocity ({@code linear_force}), of place
 * ({@code linear_velocity}, {@code orbit}) or its death ({@code kill}) must be what {@code CgFieldEval}, the Java mirror,
 * gives where it stood. A neighbour reading no group stays as it was seeded.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-fields"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-fields" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-fields --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-fields] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxFieldsScene implements InteractiveSceneLifecycle {

    private static final int PARTICLES = 512, DRAIN = 60, SEED_BITS = 0x0F1E;
    private static final float DT = 0.25f, TOLERANCE = 2e-4f, AGE = 0.7f;
    private static final double[] ORIGIN = {12.5, 64.0, -30.25};
    /** Where each field sits from the instance's origin. */
    private static final float[] PLACE = {0.5f, 0.25f, -0.5f};

    /** A station: its name, graph, target, turn and orientation. */
    private record Station(String name, CgFieldDefinition.Graph graph, Quaternionf turn, boolean world) {
    }

    private final List<Station> stations = new ArrayList<>();
    private final List<CgVfxEmitter> emitters = new ArrayList<>();
    private final List<CgVfxParticlePool> pools = new ArrayList<>();
    private final List<Integer> slots = new ArrayList<>();
    private final List<ByteBuffer> read = new ArrayList<>();
    private final List<CgRequest> requests = new ArrayList<>();
    private final List<Object> owners = new ArrayList<>();
    private final List<CgFieldWorld.Instance> placed = new ArrayList<>();
    private final float[][] seeded = new float[PARTICLES][];
    private int frame, checked;
    private float worst;
    private String failure;
    private boolean compared, running = true;

    /** A scalar node's value read through a push straight up: {@code up × scalar}. */
    private static CgFieldDefinition.Graph up(String kind, Object... fields) {
        return CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
                .node("s", kind, fields)
                .node("u", "uniform_vector", "magnitude", 1, "direction", List.of(0, 1, 0))
                .node("o", "operator", "op", "multiply", "left", "u", "right", "s")
                .build();
    }

    private void station(String name, CgFieldDefinition.Graph graph) {
        stations.add(new Station(name, graph, new Quaternionf(), false));
    }

    @Override
    public void init(HarnessContext ctx) {
        for (String falloff : List.of("none", "linear", "inverse", "squared", "logarithmic")) {
            station("radial-" + falloff, up("radial_falloff", "magnitude", 2, "min_range", 0.25, "max_range", 1.5, "default", 0.5,
                    "radius", 3, "position", List.of(0.5, 0, 0), "falloff", falloff));
        }
        station("plane", up("plane_falloff", "magnitude", 3, "default", -1, "distance", 2.5, "position", List.of(0, 0.5, 0),
                "normal", List.of(0, 1, 0)));
        station("box", up("box_falloff", "magnitude", 1, "default", 0.25, "transform", Map.of("location", List.of(1, 0, 0),
                "rotation", List.of(0.0, 0.0, 0.38268343, 0.9238795), "scale", List.of(4, 3, 5))));
        for (String fn : List.of("cosine", "gaussian", "falloff", "decay")) {
            station("wave-" + fn, up("wave_scalar", "magnitude", 2, "wavelength", 3, "period", 0.5, "function", fn, "falloff", "squared"));
        }
        station("uniform", CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
                .node("v", "uniform_vector", "magnitude", 4, "direction", List.of(0.6, 0, 0.8)).build());
        station("radial-vector", CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
                .node("v", "radial_vector", "magnitude", -3, "position", List.of(0, 1, 0)).build());
        station("rotated-radial", CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
                .node("v", "rotated_radial_vector", "magnitude", 2, "angle", 0.7, "axis", List.of(0, 1, 1)).build());
        for (String op : List.of("divide", "subtract", "add")) {
            station("operator-" + op, CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
                    .node("v", "radial_vector", "magnitude", 2)
                    .node("s", "radial_falloff", "radius", 4, "default", 0.5)
                    .node("o", "operator", "magnitude", 1.5, "op", op, "left", "v", "right", "s").build());
        }
        for (String op : List.of("inside", "outside")) {
            station("culling-" + op, CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
                    .node("c", "radial_falloff", "radius", 2.5)
                    .node("v", "uniform_vector", "magnitude", 2, "direction", List.of(0, 0, 1))
                    .node("k", "culling", "culling", "c", "field", "v", "op", op).build());
        }
        CgFieldDefinition.Graph turned = CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_FORCE)
                .node("v", "radial_vector", "magnitude", 2, "position", List.of(1, 0, 0))
                .node("s", "radial_falloff", "radius", 3, "position", List.of(1, 0, 0))
                .node("o", "operator", "left", "v", "right", "s").build();
        stations.add(new Station("turned", turned, new Quaternionf().rotateXYZ(0.4f, 1.1f, -0.3f), false));
        stations.add(new Station("world", turned, new Quaternionf().rotateXYZ(0.4f, 1.1f, -0.3f), true));
        stations.add(new Station("velocity", CgFieldDefinition.graph(CgFieldDefinition.Target.LINEAR_VELOCITY)
                .node("v", "radial_vector", "magnitude", 3).build(), new Quaternionf(), false));
        stations.add(new Station("orbit", CgFieldDefinition.graph(CgFieldDefinition.Target.ORBIT)
                .node("v", "uniform_vector", "magnitude", 1.5, "direction", List.of(0, 1, 0)).build(), new Quaternionf(), false));
        stations.add(new Station("kill", CgFieldDefinition.graph(CgFieldDefinition.Target.KILL)
                .node("b", "box_falloff", "transform", Map.of("scale", List.of(5, 4, 6))).build(), new Quaternionf(), false));
        stations.add(new Station("neighbour", null, new Quaternionf(), false));

        Map<String, Object> still = Map.of("_v", 1, "properties", Map.of("capacity", PARTICLES),
                "emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                        "inputs", Map.of("loop", "once", "duration", 1.0))));
        for (Station s : stations) {
            CgVfxEmitter base = CgVfxEmitterAssets.compile("fields-" + s.name(), new LinkedHashMap<>(still), null);
            if (s.graph() != null) {
                int group = CgFieldWorld.group("harness.fields." + s.name());
                base = base.toBuilder().module(new CgVfxModule.PhysicsField(group, s.graph().target(), 1f)).build();
            }
            emitters.add(base);
            read.add(null);
        }
        Random random = new Random(1301);
        for (int id = 0; id < PARTICLES; id++) {
            float life = 2f + random.nextFloat() * 2f, age = random.nextFloat();
            seeded[id] = new float[]{age, life, (random.nextFloat() * 2f - 1f) * 4f, (random.nextFloat() * 2f - 1f) * 4f,
                    (random.nextFloat() * 2f - 1f) * 4f};
        }
        for (CgVfxEmitter emitter : emitters) CgVfxParticlePool.prepare(emitter);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= 2) {
            CgRecording rec = new CgRecording();
            if (frame == 1) {
                seed(rec);
            } else {
                CgFieldWorld.around(ORIGIN[0], ORIGIN[1], ORIGIN[2]);
                for (int e = 0; e < stations.size(); e++) {
                    Station s = stations.get(e);
                    if (s.graph() == null) continue;
                    CgFieldPlacement p = new CgFieldPlacement(s.name(), s.graph(), "harness.fields." + s.name(), 1f, true,
                            s.world(), Float.POSITIVE_INFINITY, s.graph().bounds(), null);
                    placed.add(CgFieldWorld.add(p, null).place(ORIGIN[0] + PLACE[0], ORIGIN[1] + PLACE[1], ORIGIN[2] + PLACE[2], s.turn(), AGE));
                }
                for (int e = 0; e < pools.size(); e++) {
                    CgVfxParticlePool pool = pools.get(e);
                    pool.beginStep(DT, 0f, 0f, 0f);
                    pool.instance(slots.get(e), SEED_BITS, 1f, Float.NaN, origin());
                    pool.endStep();
                }
                CgVfxParticlePool.record(rec, pools);
                for (int e = 0; e < pools.size(); e++) {
                    int at = e;
                    CgVfxParticlePool pool = pools.get(e);
                    requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * pool.record().bytes(),
                            data -> read.set(at, copy(data))));
                }
            }
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && !read.contains(null) && frame > 10) {
            for (int e = 0; e < stations.size(); e++) compare(e);
            compared = true;
        }
        if (compared || frame > DRAIN) {
            report();
            for (CgFieldWorld.Instance i : placed) i.remove();
            for (Object owner : owners) CgVfxParticlePool.release(owner);
            running = false;
        }
    }

    private void seed(CgRecording rec) {
        for (CgVfxEmitter emitter : emitters) {
            Object owner = new Object();
            owners.add(owner);
            CgVfxParticlePool pool = CgVfxParticlePool.of(owner, emitter);
            int slot = pool.open(emitter, PARTICLES);
            CgVfxRecord layout = pool.record();
            ByteBuffer packed = ByteBuffer.allocateDirect(PARTICLES * layout.bytes()).order(ByteOrder.nativeOrder());
            for (int id = 0; id < PARTICLES; id++) {
                float[] s = seeded[id];
                layout.put(packed, id, s[2], s[3], s[4], 0f, 0f, 0f, s[0], s[1], 0.1f, 0f, id, slot);
            }
            pool.seed(rec, packed, PARTICLES);
            pools.add(pool);
            slots.add(slot);
        }
    }

    /** What station {@code s} does to the particle seeded at {@code (x, y, z)} from the origin, in the world's axes. */
    private static float[] want(Station s, float x, float y, float z) {
        // Into the field's own space: from its place, then its inverse turn unless it keeps the world's axes.
        Vector3f q = new Vector3f(x - PLACE[0], y - PLACE[1], z - PLACE[2]);
        Quaternionf inverse = s.world() ? new Quaternionf() : new Quaternionf(s.turn()).invert();
        inverse.transform(q);
        CgFieldBounds b = s.graph().bounds();
        if (q.x < b.minX() || q.y < b.minY() || q.z < b.minZ() || q.x > b.maxX() || q.y > b.maxY() || q.z > b.maxZ()) return new float[4];
        float[] v = new float[4];
        CgFieldEval.graph(s.graph(), q.x, q.y, q.z, AGE, 0, 0, v);
        Vector3f out = new Quaternionf(inverse).invert().transform(new Vector3f(v[0], v[1], v[2]));
        return new float[]{out.x, out.y, out.z, v[0]};
    }

    private void compare(int e) {
        Station s = stations.get(e);
        ByteBuffer records = read.get(e);
        CgVfxRecord layout = pools.get(e).record();
        boolean[] seen = new boolean[PARTICLES];
        for (int r = 0; r < PARTICLES; r++) {
            int id = layout.i(records, r, CgVfxRecord.ID);
            if (layout.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES || seen[id]) continue;
            seen[id] = true;
            float[] sd = seeded[id];
            float[] got = new float[6];
            for (int c = 0; c < 3; c++) {
                got[c] = layout.f(records, r, CgVfxRecord.VELOCITY + c);
                got[3 + c] = layout.f(records, r, CgVfxRecord.POSITION + c) - sd[2 + c];
            }
            float[] want;
            int measured;
            if (s.graph() == null) {
                want = new float[3];
                measured = 0;
            } else {
                float[] w = want(s, sd[2], sd[3], sd[4]);
                switch (s.graph().target().name()) {
                    case "kill" -> {
                        if (w[3] > 0f) fail(s.name() + ": particle " + id + " lives where the field is " + w[3]);
                        checked++;
                        continue;
                    }
                    case "linear_velocity" -> {
                        want = new float[]{w[0] * DT, w[1] * DT, w[2] * DT};
                        measured = 3;
                    }
                    case "orbit" -> {
                        float rate = (float) Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
                        Vector3f from = new Vector3f(sd[2] - PLACE[0], sd[3] - PLACE[1], sd[4] - PLACE[2]);
                        Vector3f to = new Vector3f(from);
                        if (rate > 1e-8f) new Quaternionf().rotateAxis(rate * DT, w[0] / rate, w[1] / rate, w[2] / rate).transform(to);
                        want = new float[]{to.x - from.x, to.y - from.y, to.z - from.z};
                        measured = 3;
                    }
                    default -> {
                        want = new float[]{w[0] * DT, w[1] * DT, w[2] * DT};
                        measured = 0;
                    }
                }
            }
            for (int c = 0; c < 3; c++) {
                float g = got[measured + c], off = Math.abs(g - want[c]) / (TOLERANCE * (1f + Math.abs(want[c])));
                worst = Math.max(worst, off);
                if (!(off <= 1f)) {
                    fail(s.name() + ": particle " + id + " lane " + c + " is " + g + ", the mirror gives " + want[c]);
                    return;
                }
            }
            checked++;
        }
        for (int id = 0; id < PARTICLES; id++) {
            if (seen[id]) continue;
            // Only the kill station loses particles, and only those inside its box.
            if (s.graph() != null && s.graph().target() == CgFieldDefinition.Target.KILL
                    && want(s, seeded[id][2], seeded[id][3], seeded[id][4])[3] > 0f) {
                checked++;
                continue;
            }
            fail(s.name() + ": particle " + id + " was not read back");
            return;
        }
    }

    private static CgVfxInstanceView origin() {
        return new CgVfxInstanceView() {
            public double originX() { return ORIGIN[0]; }
            public double originY() { return ORIGIN[1]; }
            public double originZ() { return ORIGIN[2]; }
            public float time() { return 0f; }
            public float sourceX() { return 0f; }
            public float sourceY() { return 0f; }
            public float sourceZ() { return 0f; }
        };
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
        out.put(data.duplicate());
        out.flip();
        return out;
    }

    private void fail(String what) {
        if (failure == null) failure = what;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        int validation = PlatformServiceHarness.validationErrors();
        if (GlErrorChecker.checkAndLog("vfx-fields")) {
            System.out.println("[vfx-fields] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-fields] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-fields] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-fields] FAIL on " + on + ": the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-fields] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-fields] PASS on " + on + ": " + stations.size() + " stations, " + checked
                    + " particles, every lane the mirror's (worst share of the tolerance " + worst + ")");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
