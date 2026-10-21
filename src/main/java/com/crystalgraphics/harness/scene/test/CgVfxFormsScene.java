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
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;
import com.crystalgraphics.vfx.particle.form.CgVfxFormContext;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for inputs in forms (aaa-milestones P6.3, P6.4). Module inputs: an emitter compiled from its file a form,
 * each giving {@code acceleration}'s value as a range per channel, a range rolled once, a locked range, a pick, curves by
 * age, by the particle's age, by its speed, by a random draw and by its id, and a binding to its age. Particles of known
 * ids, ages, lives and velocities are seeded and stepped once; with nothing else pulling, each one's change of velocity
 * over the step is its acceleration, which must be {@code CgVfxFormInput}'s Java evaluation for that particle, every
 * lane. Spawn values: an emitter a spawn form ({@code initialize_particle}'s lifetime and sprite size,
 * {@code add_velocity}'s speed, {@code shape_location}'s radius by the loop), 512 spawned at a known loop time and
 * stepped once, each one's life, size, speed or distance from the source the form's Java evaluation for its key.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-forms"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-forms" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-forms --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-forms] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxFormsScene implements InteractiveSceneLifecycle {

    private static final int PARTICLES = 512, DRAIN = 60;
    private static final float DT = 0.5f, TOLERANCE = 2e-4f;
    private static final int SEED_BITS = 0x0F0E;
    private static final double[] ORIGIN = {12.5, 64.0, -30.25};

    /** Each form by name: its value as an emitter file writes it, an acceleration's three lanes. */
    private static final Map<String, Object> FORMS = new LinkedHashMap<>();
    /** Each spawn form by name: its emitter file's {@code particle_spawn}. */
    private static final Map<String, List<Object>> SPAWNS = new LinkedHashMap<>();
    /** The loop's seconds the spawn stations spawn at. */
    private static final float LOOP = 0.7f;

    static {
        Map<String, Object> cubic = Map.of("keys", List.of(List.of(0.0, -2.0, "cubic", "break", 0.0, 6.0),
                List.of(0.4, 1.5, "cubic", "break", -1.0, 2.0), List.of(1.0, 0.25, "cubic", "break", 0.0, 0.0)));
        Map<String, Object> linear = Map.of("keys", List.of(List.of(0.0, 1.0, "linear"), List.of(0.3, 4.0, "linear"),
                List.of(1.0, -1.0, "linear")));
        Map<String, Object> step = Map.of("keys", List.of(List.of(0.0, 0.5, "constant"), List.of(0.6, 3.0, "constant")));
        Map<String, Object> seconds = Map.of("keys", List.of(List.of(0.0, 0.0), List.of(2.0, 5.0, "linear"), List.of(4.0, 1.0)));
        FORMS.put("range", Map.of("range", List.of(List.of(-1.0, 2.0, 0.5), List.of(3.0, -2.0, 4.0)), "per_channel", true));
        FORMS.put("range-once", Map.of("range", List.of(List.of(0.0, 0.0, 0.0), List.of(2.0, 4.0, 6.0))));
        FORMS.put("range-locked", Map.of("range", List.of(List.of(0.0, 0.0, 0.0), List.of(1.0, 2.0, 3.0)), "per_channel", true,
                "locked", "xz"));
        FORMS.put("pick", Map.of("pick", List.of(List.of(1.0, 2.0, 3.0), List.of(-1.0, -2.0, -3.0))));
        FORMS.put("curve-age", Map.of("curves", List.of(cubic, linear, step), "by", "age"));
        FORMS.put("curve-particles-age", Map.of("curve", seconds, "by", "particles.age"));
        FORMS.put("curve-speed", Map.of("curve", seconds, "by", "speed"));
        FORMS.put("curve-random", Map.of("curve", cubic, "by", "random"));
        FORMS.put("curve-id", Map.of("curve", Map.of("keys", List.of(List.of(0.0, 0.0), List.of(511.0, 3.0, "linear"))), "by", "id"));
        FORMS.put("age-binding", Map.of("bind", "particles.age", "scale", 2.0));
        SPAWNS.put("spawn-life-pick", List.of(init(Map.of("lifetime", Map.of("pick", List.of(1.5, 3.0))))));
        SPAWNS.put("spawn-life-id", List.of(init(Map.of("lifetime",
                Map.of("curve", Map.of("keys", List.of(List.of(0.0, 1.0), List.of(511.0, 4.0, "linear"))), "by", "id")))));
        SPAWNS.put("spawn-size-random", List.of(init(Map.of("lifetime", 3.0, "sprite_size", CgVfxSpawnForm.skewed(0.1f, 0.5f, 2f)))));
        SPAWNS.put("spawn-speed-random", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "velocity",
                "module", "crystalgraphics:add_velocity", "inputs", Map.of("mode", "outward", "speed", Map.of("curve", cubic, "by", "random")))));
        SPAWNS.put("spawn-radius-loop", List.of(init(Map.of("lifetime", 3.0)), Map.of("id", "shape",
                "module", "crystalgraphics:shape_location", "inputs", Map.of("shape", "sphere", "surface_only", true,
                        "sphere_radius", CgVfxSpawnForm.swept(2f, 3f, 1.6f)))));
    }

    private static Map<String, Object> init(Map<String, Object> inputs) {
        return Map.of("id", "init", "module", "crystalgraphics:initialize_particle", "inputs", inputs);
    }

    private final List<CgVfxEmitter> emitters = new ArrayList<>();
    private final List<CgVfxParticlePool> pools = new ArrayList<>();
    private final List<Integer> slots = new ArrayList<>();
    private final List<ByteBuffer> read = new ArrayList<>();
    private final List<CgRequest> requests = new ArrayList<>();
    /** Each particle's seeded age, life and velocity, by its id. */
    private final float[][] seeded = new float[PARTICLES][];
    private int frame, checked;
    private float worst;
    private String failure;
    private boolean compared, running = true;

    @Override
    public void init(HarnessContext ctx) {
        for (Map.Entry<String, Object> form : FORMS.entrySet()) {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("_v", 1);
            tree.put("properties", Map.of("capacity", PARTICLES));
            tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                    "inputs", Map.of("loop", "once", "duration", 1.0))));
            tree.put("particle_update", List.of(Map.of("id", "push", "module", "crystalgraphics:acceleration",
                    "inputs", Map.of("acceleration", form.getValue()))));
            CgVfxEmitter emitter = CgVfxEmitterAssets.compile("forms-" + form.getKey(), tree, null);
            if (emitter.moduleForms().size() != 1) fail(form.getKey() + " compiled " + emitter.moduleForms().size() + " forms");
            emitters.add(emitter);
            CgVfxParticlePool.prepare(emitter);
            read.add(null);
        }
        for (Map.Entry<String, List<Object>> spawn : SPAWNS.entrySet()) {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("_v", 1);
            tree.put("properties", Map.of("capacity", PARTICLES));
            tree.put("emitter_update", List.of(Map.of("id", "state", "module", "crystalgraphics:emitter_state",
                    "inputs", Map.of("loop", "once", "duration", 1.6))));
            tree.put("particle_spawn", spawn.getValue());
            CgVfxEmitter emitter = CgVfxEmitterAssets.compile("forms-" + spawn.getKey(), tree, null);
            if (emitter.spawnForms().size() != 1) fail(spawn.getKey() + " compiled " + emitter.spawnForms().size() + " spawn forms");
            emitters.add(emitter);
            CgVfxParticlePool.prepare(emitter);
            read.add(null);
        }
        Random random = new Random(1201);
        for (int id = 0; id < PARTICLES; id++) {
            // Every one outlives the step: a death would compact it away.
            float life = 1f + random.nextFloat() * 4f, age = random.nextFloat() * (life - DT - 0.1f);
            seeded[id] = new float[]{age, life, (random.nextFloat() * 2f - 1f) * 3f, (random.nextFloat() * 2f - 1f) * 3f,
                    (random.nextFloat() * 2f - 1f) * 3f};
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= 2) {
            CgRecording rec = new CgRecording();
            if (frame == 1) {
                seed(rec);
            } else {
                for (int e = 0; e < pools.size(); e++) {
                    CgVfxParticlePool pool = pools.get(e);
                    pool.beginStep(DT, 0f, 0f, 0f);
                    pool.instance(slots.get(e), SEED_BITS, 1f, Float.NaN, origin());
                    // A spawn station's particles: one burst at the step's start, at the loop's LOOP seconds.
                    if (e >= FORMS.size()) pool.spawn(slots.get(e), 0, PARTICLES, PARTICLES, 0f, DT, 0f, 0f, 0f, 0f, 0f, 0f, LOOP, LOOP);
                    pool.endStep();
                }
                CgVfxParticlePool.record(rec, pools);
                for (int e = 0; e < pools.size(); e++) {
                    int at = e;
                    CgVfxParticlePool pool = pools.get(e);
                    requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * CgVfxRecord.BYTES,
                            data -> read.set(at, copy(data))));
                }
            }
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && !read.contains(null) && frame > 10) {
            for (int e = 0; e < emitters.size(); e++) {
                if (e < FORMS.size()) compare(e);
                else compareSpawn(e);
            }
            compared = true;
        }
        if (compared || frame > DRAIN) {
            report();
            CgVfxParticlePool.release(this);
            running = false;
        }
    }

    private void seed(CgRecording rec) {
        for (CgVfxEmitter emitter : emitters) {
            CgVfxParticlePool pool = CgVfxParticlePool.of(this, emitter);
            if (pools.contains(pool)) fail(emitter.name() + " shares a pool with another form");
            int slot = pool.open(emitter, PARTICLES);
            if (pools.size() >= FORMS.size()) {
                pools.add(pool);
                slots.add(slot);
                continue;
            }
            ByteBuffer packed = ByteBuffer.allocateDirect(PARTICLES * CgVfxRecord.BYTES).order(ByteOrder.nativeOrder());
            for (int id = 0; id < PARTICLES; id++) {
                float[] s = seeded[id];
                int at = id * CgVfxRecord.BYTES;
                packed.putFloat(at + 12, s[0]).putFloat(at + 28, s[1]);
                packed.putFloat(at + 32, s[2]).putFloat(at + 36, s[3]).putFloat(at + 40, s[4]).putFloat(at + 44, 0.1f);
                packed.putInt(at + 64, id).putInt(at + 68, slot);
            }
            pool.seed(rec, packed, PARTICLES);
            pools.add(pool);
            slots.add(slot);
        }
    }

    /** Every particle of form {@code e}: its change of velocity over the step, against the form's Java evaluation. */
    private void compare(int e) {
        CgVfxEmitter emitter = emitters.get(e);
        CgVfxFormInput form = emitter.moduleForms().get(0).compiled();
        ByteBuffer records = read.get(e);
        CgVfxFormContext ctx = new CgVfxFormContext().seed(SEED_BITS);
        float[] want = new float[form.components()];
        boolean[] seen = new boolean[PARTICLES];
        for (int r = 0; r < PARTICLES; r++) {
            int id = CgVfxRecord.i(records, r, CgVfxRecord.ID);
            if (CgVfxRecord.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES || seen[id]) continue;
            seen[id] = true;
            float[] s = seeded[id];
            float speed = (float) Math.sqrt(s[2] * s[2] + s[3] * s[3] + s[4] * s[4]);
            form.eval(ctx.particle(id).age(s[0] / s[1]).particleAge(s[0]).speed(speed), want);
            for (int c = 0; c < 3; c++) {
                float got = (CgVfxRecord.f(records, r, CgVfxRecord.VELOCITY + c) - s[2 + c]) / DT;
                float off = Math.abs(got - want[Math.min(c, want.length - 1)]) / (TOLERANCE * (1f + Math.abs(want[Math.min(c, want.length - 1)])));
                worst = Math.max(worst, off);
                if (!(off <= 1f)) {
                    fail(emitter.name() + ": particle " + id + " lane " + c + " is " + got + ", Java gives " + want[Math.min(c, want.length - 1)]);
                    return;
                }
            }
            checked++;
        }
        for (int id = 0; id < PARTICLES; id++) {
            if (!seen[id]) {
                fail(emitter.name() + ": particle " + id + " was not read back");
                return;
            }
        }
    }

    /** Every particle of spawn station {@code e}: its life, size, speed or distance, against the form's Java evaluation. */
    private void compareSpawn(int e) {
        CgVfxEmitter emitter = emitters.get(e);
        CgVfxSpawnForm form = emitter.spawnForms().get(0);
        ByteBuffer records = read.get(e);
        CgVfxFormContext ctx = new CgVfxFormContext().seed(SEED_BITS).loop(LOOP, 0);
        boolean[] seen = new boolean[PARTICLES];
        for (int r = 0; r < PARTICLES; r++) {
            int id = CgVfxRecord.i(records, r, CgVfxRecord.ID);
            if (CgVfxRecord.f(records, r, CgVfxRecord.LIFE) == 0f || id < 0 || id >= PARTICLES || seen[id]) continue;
            seen[id] = true;
            int at = switch (form.target()) {
                case LIFETIME -> CgVfxRecord.LIFE;
                case SPRITE_SIZE -> CgVfxRecord.SIZE;
                case SPEED -> CgVfxRecord.VELOCITY;
                case RADIUS -> CgVfxRecord.POSITION;
            };
            float got = switch (form.target()) {
                case SPEED, RADIUS -> length(records, r, at);
                default -> CgVfxRecord.f(records, r, at);
            };
            float want = form.compiled().channel(ctx.particle(id), 0);
            // A negative speed launches the other way: its velocity's length is the value's size.
            if (form.target() == CgVfxSpawnInput.SPEED) want = Math.abs(want);
            float off = Math.abs(got - want) / (TOLERANCE * (1f + Math.abs(want)));
            worst = Math.max(worst, off);
            if (!(off <= 1f)) {
                fail(emitter.name() + ": particle " + id + "'s " + form.target() + " is " + got + ", Java gives " + want);
                return;
            }
            checked++;
        }
        for (int id = 0; id < PARTICLES; id++) {
            if (!seen[id]) {
                fail(emitter.name() + ": particle " + id + " was not read back");
                return;
            }
        }
    }

    private static float length(ByteBuffer records, int r, int at) {
        float x = CgVfxRecord.f(records, r, at), y = CgVfxRecord.f(records, r, at + 1), z = CgVfxRecord.f(records, r, at + 2);
        return (float) Math.sqrt(x * x + y * y + z * z);
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
        if (GlErrorChecker.checkAndLog("vfx-forms")) {
            System.out.println("[vfx-forms] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-forms] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-forms] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-forms] FAIL on " + on + ": the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-forms] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-forms] PASS on " + on + ": " + FORMS.size() + " module forms, " + SPAWNS.size() + " spawn forms, " + checked
                    + " particles, every lane the form's Java evaluation (worst share of the tolerance " + worst + ")");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
