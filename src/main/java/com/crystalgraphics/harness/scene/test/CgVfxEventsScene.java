package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
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
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.form.CgVfxFormCompiler;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for events (plan vfx-gpu X5, aaa-milestones P9): a system file's handlers, by the format's triggers,
 * compiled onto their sources, and a landing, which only Java has. Debris falls onto the fixed ground in one pool, and
 * a child pool spawns from its events in the same step, with no CPU in the loop:
 * <ul>
 *   <li>landing: three dust at each, carrying half the debris's speed;</li>
 *   <li>{@code spawn}: one to three at its first step, the count rolled per debris;</li>
 *   <li>{@code death}: one, born at the effect's origin ({@code use_emitter_location});</li>
 *   <li>{@code location}: one every step it lives, at {@code max_per_frame}'s 64 a frame: counted, not placed;</li>
 *   <li>{@code burst}: a second source spawning a batch of bursts and one of rate spawns each step, two dust at each
 *       batch's source.</li>
 * </ul>
 * The first three's rows reach the CPU, and from them each child is worked out in Java (its key, where it was born,
 * how it was launched, where it has drifted since), as each burst child from its batch: the children read back must be
 * exactly those, each where Java puts it. The child pool's origin is not the parents', so a child is placed from the
 * parent's block.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-events"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-events" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-events --device=vulkan" -Dcrystalgraphics.vulkan.syncValidation=true
 * }</pre>
 *
 * <p>Prints {@code [vfx-events] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxEventsScene implements InteractiveSceneLifecycle {

    private static final int PARENTS = 400, STEPS = 150, DRAIN = 40, BURST_STEPS = 20, CANDIDATES = 3, BURSTS = 1;
    private static final float DT = 1f / 60f, GROUND = -1f, PARENT_LIFE = 1.5f;
    /** The debris's events, in the order its handlers resolve; the puffer's one. */
    private static final int LANDING = 0, SPAWN = 1, DEATH = 2, LOCATION = 3, BURST = 0;
    private static final int[] COUNT_MIN = {3, 1, 1}, COUNT_MAX = {3, 3, 1};
    private static final float[][] INHERIT = {{0.5f, 0.2f, 0.8f}, {0f, 0f, 0f}, {0f, 0f, 0f}};
    private static final int BURST_COUNT = 2, LOCATION_CAP = CgVfxEvent.MAX_PER_FRAME;
    private static final double[] PARENT_ORIGIN = {-700.25, 70.5, 1300.75}, CHILD_ORIGIN = {-697.0, 68.5, 1302.25},
            PUFFER_ORIGIN = {-702.5, 69.0, 1298.0};
    private static final float[] PUFF_FROM = {0.75f, 0.5f, -0.25f};
    private static final int SEED_BITS = 0x5EED;
    private static final float TOLERANCE = 2e-3f;
    /** Every debris's size: Ground rests it as a ball of that on the ground. */
    private static final float DEBRIS_SIZE = 0.1f;

    /** No forces: a child drifts at its launch, so where it is follows from its age. */
    private static final CgVfxEmitter DUST = CgVfxEmitter.builder("events-dust").state(CgVfxEmitterState.ONCE)
            .capacity(2000).burst(0f, 1).shape(CgSphere.of(0.4f))
            .hemisphere().speed(1f, 3f).life(50f, 60f).size(0.1f, 0.2f, 1f).build();
    private static final CgVfxEmitter DEBRIS = CgVfxEmitter.builder("events-debris").state(CgVfxEmitterState.ONCE)
            .capacity(PARENTS).burst(0f, 1)
            .life(PARENT_LIFE, PARENT_LIFE).size(0.1f, 0.2f, 1f).module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Ground(0f, 1f, 100f)).build();
    private static final CgVfxEmitter PUFFER = CgVfxEmitter.builder("events-puffer").state(CgVfxEmitterState.ONCE)
            .capacity(256).burst(0f, 1).life(30f, 30f).size(0.1f, 0.1f, 1f).build();

    private CgVfxGpuEmitter debris, puffer;
    private CgVfxEmitter dust;
    private final float[] spawn = new float[4 * CgVfxGpuEmitter.SPAWN_VECTORS];
    private final List<CgRequest> requests = new ArrayList<>();
    /** Each reported event's rows as they arrived: per event, parent id to (x, y, z, vx, vy, vz, nx, ny, nz). */
    private final List<Map<Integer, double[]>> rows = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
    private final CgVfxEventListener listener = this::heard;
    private CgVfxParticlePool parents, puffers, children;
    private int parentSlot, pufferSlot, locationSlot, burstSlot;
    private final int[] childSlots = new int[3];
    private ByteBuffer childRecords;
    private int frame, childLive = -1, checked, repeated, dropped, locationSteps, located;
    private float worst;
    private String failure;
    private boolean compared, running = true;

    @Override
    public void init(HarnessContext ctx) {
        compileSystem();
        CgVfxParticlePool.prepare(debris);
        CgVfxParticlePool.prepare(puffer);
        CgVfxParticlePool.prepare(dust, 1);
        CgVfxParticlePool.listen(listener);
        CgVfxWords words = new CgVfxWords();
        int[] raw = new int[spawn.length];
        words.target(raw, 0, CgVfxGpuEmitter.SPAWN_VECTORS, dust.name());
        dust.writeSpawn(words);
        words.finish("its spawn numbers");
        for (int i = 0; i < raw.length; i++) spawn[i] = Float.intBitsToFloat(raw[i]);
    }

    /**
     * The system file: debris (h1), the puffer (h2) and the dust (h3), its handlers the dust's, by the format's
     * triggers. The landing goes first in Java, and the debris's first three events report rows too.
     */
    private void compileSystem() {
        Map<String, Object> dustTree = CgVfxEmitterAssets.write(DUST);
        dustTree.put("events", List.of(
                handler("e2", "spawn", "h1", Map.of("emitter", "h3", "count", Map.of("range", List.of(1, 3))), PARENTS),
                handler("e3", "death", "h1", Map.of("emitter", "h3", "count", 1, "use_emitter_location", true), PARENTS),
                handler("e4", "location", "h1", Map.of("emitter", "h3", "count", 1), -1),
                handler("e5", "burst", "h2", Map.of("emitter", "h3", "count", BURST_COUNT), PARENTS)));
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        tree.put("emitters", List.of(handle("h1", DEBRIS), handle("h2", PUFFER), Map.of("id", "h3", "name", DUST.name(), "emitter", dustTree)));
        CgVfxSystemDefinition system = CgVfxSystemAssets.compile(
                CgAssetData.of("harness:systems/vfx-events", CgParticleKinds.SYSTEM, tree, null), (kind, ref) -> null);
        CgVfxEmitter debrisFile = system.emitters().get(0);
        puffer = system.emitters().get(1);
        dust = system.emitters().get(2);
        List<CgVfxEvent> events = new ArrayList<>(debrisFile.events());
        events.add(LANDING, CgVfxEvent.onLanding().spawn(dust, COUNT_MAX[LANDING]).inherit(INHERIT[LANDING][0], INHERIT[LANDING][1], INHERIT[LANDING][2]).maxPerFrame(PARENTS));
        for (int e = 0; e < 3; e++) events.set(e, events.get(e).readback(1000));
        debris = new Evented(debrisFile, events);
        if (events.get(LOCATION).trigger() != CgVfxEvent.Trigger.LOCATION || puffer.events().get(BURST).trigger() != CgVfxEvent.Trigger.BURST) {
            fail("the handlers resolved out of order");
        }
    }

    private static Map<String, Object> handler(String id, String trigger, String source, Map<String, Object> spawn, int most) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("trigger", trigger);
        h.put("source", source);
        h.put("spawn", spawn);
        if (most > 0) h.put("max_per_frame", most);
        return h;
    }

    private static Map<String, Object> handle(String id, CgVfxEmitter e) {
        return Map.of("id", id, "name", e.name(), "emitter", CgVfxEmitterAssets.write(e));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= STEPS + 1) {
            CgRecording rec = new CgRecording();
            if (frame == 1) seed(rec);
            else step(rec);
            if (frame == STEPS + 1) {
                requests.add(rec.readback(children.live(), 0, 4, data -> childLive = data.getInt(0)));
                requests.add(rec.readback(children.records(), 0, (long) children.storage() * children.record().bytes(),
                        data -> childRecords = copy(data)));
            }
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && childRecords != null && frame > STEPS + 10) {
            compare();
            compared = true;
        }
        if (compared || frame > STEPS + DRAIN) {
            report();
            CgVfxParticlePool.stopListening(listener);
            CgVfxParticlePool.release(this);
            running = false;
        }
    }

    private void seed(CgRecording rec) {
        parents = CgVfxParticlePool.of(this, debris);
        puffers = CgVfxParticlePool.of(this, puffer);
        children = CgVfxParticlePool.of(this, dust, 1);   // its feed tier: spawned by the debris's and the puffer's events
        if (parents == children || puffers == children || parents == puffers) fail("two of the pools are one");
        parentSlot = parents.open(debris, PARENTS);
        pufferSlot = puffers.open(puffer, BURST_STEPS * CANDIDATES);
        for (int e = 0; e < 3; e++) {
            childSlots[e] = children.open(dust, PARENTS * COUNT_MAX[e]);
            children.feed(childSlots[e], parents, parentSlot, e);
        }
        locationSlot = children.open(dust, LOCATION_CAP * (int) (PARENT_LIFE / DT + 4));
        children.feed(locationSlot, parents, parentSlot, LOCATION);
        burstSlot = children.open(dust, BURST_STEPS * 2 * BURST_COUNT);
        children.feed(burstSlot, puffers, pufferSlot, BURST);
        Random random = new Random(1201);
        CgVfxRecord layout = parents.record();
        ByteBuffer packed = ByteBuffer.allocateDirect(PARENTS * layout.bytes()).order(ByteOrder.nativeOrder());
        for (int r = 0; r < PARENTS; r++) {
            float x = (random.nextFloat() * 2f - 1f) * 3f, y = 1f + random.nextFloat() * 2f, z = (random.nextFloat() * 2f - 1f) * 3f;
            float vx = (random.nextFloat() * 2f - 1f) * 2f, vz = (random.nextFloat() * 2f - 1f) * 2f;
            layout.put(packed, r, x, y, z, vx, 0f, vz, 0f, PARENT_LIFE, DEBRIS_SIZE, random.nextFloat(), r, parentSlot);
        }
        parents.seed(rec, packed, PARENTS);
        // The location event fires each step a debris lives, its death's included: the step its age reaches its life.
        float age = 0f;
        do {
            age += DT;
            locationSteps++;
        } while (age < PARENT_LIFE);
    }

    private void step(CgRecording rec) {
        parents.beginStep(DT, 0f, 0f, 0f);
        parents.instance(parentSlot, SEED_BITS, 1f, GROUND, origin(PARENT_ORIGIN));
        parents.endStep();
        puffers.beginStep(DT, 0f, 0f, 0f);
        puffers.instance(pufferSlot, SEED_BITS, 1f, Float.NaN, origin(PUFFER_ORIGIN));
        int n = frame - 2;
        if (n < BURST_STEPS) {
            puffers.spawn(pufferSlot, n * CANDIDATES, CANDIDATES, BURSTS, 0f, DT, PUFF_FROM[0], PUFF_FROM[1], PUFF_FROM[2],
                    PUFF_FROM[0], PUFF_FROM[1], PUFF_FROM[2], 0f, 0f);
        }
        puffers.endStep();
        children.beginStep(DT, 0f, 0f, 0f);
        for (int slot : childSlots) children.instance(slot, SEED_BITS, 1f, Float.NaN, origin(CHILD_ORIGIN));
        children.instance(locationSlot, SEED_BITS, 1f, Float.NaN, origin(CHILD_ORIGIN));
        children.instance(burstSlot, SEED_BITS, 1f, Float.NaN, origin(CHILD_ORIGIN));
        children.endStep();
        CgVfxParticlePool.record(rec, new ArrayList<>(new LinkedHashSet<>(List.of(parents, puffers, children))));
    }

    private void heard(CgVfxGpuEmitter definition, int event, CgVfxEventRows heard) {
        if (definition != debris || event > DEATH) {
            fail("rows heard for " + definition.name() + "'s event " + event);
            return;
        }
        dropped += heard.dropped();
        for (int i = 0; i < heard.count(); i++) {
            double[] row = {heard.x(i), heard.y(i), heard.z(i), heard.vx(i), heard.vy(i), heard.vz(i), heard.nx(i), heard.ny(i), heard.nz(i)};
            if (rows.get(event).put(heard.parentId(i), row) != null) repeated++;
        }
    }

    private void compare() {
        for (int e = 0; e < 3; e++) {
            if (rows.get(e).size() != PARENTS) fail("event " + e + " fired for " + rows.get(e).size() + " of " + PARENTS + " debris");
        }
        if (repeated > 0 || dropped > 0) fail(repeated + " events fired twice, " + dropped + " rows dropped");
        for (double[] landing : rows.get(LANDING).values()) {
            if (Math.abs(landing[1] - (PARENT_ORIGIN[1] + GROUND + DEBRIS_SIZE)) > 1e-4) fail("a landing at y " + landing[1]);
        }
        for (double[] death : rows.get(DEATH).values()) {
            for (int a = 0; a < 3; a++) if (Math.abs(death[a] - PARENT_ORIGIN[a]) > 1e-4) fail("a death not at the effect's origin");
        }
        // An id is unique within its slot, as Niagara's within its emitter: two sources' keys may meet across slots.
        Map<Long, Integer> byId = new HashMap<>();
        CgVfxRecord layout = children.record();
        for (int r = 0; r < childLive; r++) {
            int slot = layout.i(childRecords, r, CgVfxRecord.SLOT);
            if (slot == locationSlot) {
                located++;
            } else if (byId.put(slotted(slot, layout.i(childRecords, r, CgVfxRecord.ID)), r) != null) {
                fail("two children share an id");
            }
        }
        int expected = 0;
        for (int e = 0; e < 3; e++) {
            for (Map.Entry<Integer, double[]> parent : rows.get(e).entrySet()) {
                int n = CgVfxEvent.rolledCount(COUNT_MIN[e], COUNT_MAX[e], CgVfxEmitterInstance.rand(SEED_BITS,
                        CgVfxEvent.childKey(parent.getKey(), e, CgVfxEvent.MAX_CHILDREN), 0));
                for (int i = 0; i < n; i++, expected++) {
                    if (!child(CgVfxEvent.childKey(parent.getKey(), e, i), childSlots[e], INHERIT[e], parent.getValue(), byId)) return;
                }
            }
        }
        // Each step's two batches, at the puffer's source: the burst batch's first spawn, then the rate batch's.
        for (int s = 0; s < BURST_STEPS; s++) {
            for (int first : new int[]{s * CANDIDATES, s * CANDIDATES + BURSTS}) {
                double[] event = {PUFFER_ORIGIN[0] + PUFF_FROM[0], PUFFER_ORIGIN[1] + PUFF_FROM[1], PUFFER_ORIGIN[2] + PUFF_FROM[2],
                        0, 0, 0, 0, 1, 0};
                for (int i = 0; i < BURST_COUNT; i++, expected++) {
                    if (!child(CgVfxEvent.childKey(first, BURST, i), burstSlot, INHERIT[SPAWN], event, byId)) return;
                }
            }
        }
        if (located != LOCATION_CAP * locationSteps) {
            fail(located + " children of location events, not " + LOCATION_CAP + " a step for " + locationSteps + " steps");
        }
        if (childLive != expected + located) fail(childLive + " children alive, " + (expected + located) + " expected");
    }

    /** Child {@code key} of slot {@code slot} from event row {@code event}: there, and where Java puts it. */
    private static long slotted(int slot, int id) {
        return (long) slot << 32 | id & 0xFFFFFFFFL;
    }

    private boolean child(int key, int slot, float[] inherit, double[] event, Map<Long, Integer> byId) {
        Integer r = byId.get(slotted(slot, key));
        if (r == null) {
            fail("no child " + key + " in slot " + slot);
            return false;
        }
        check(key, inherit, event, r);
        return failure == null;
    }

    /** Child {@code key} as {@code step_launch} launches it from the event, then drifted for its age. */
    private void check(int key, float[] inherit, double[] event, int r) {
        // step_place's sphere: a direction drawn evenly, folded up for a hemisphere (its flags' bit 4).
        float up = rand(SHAPE, key, 0) * 2f - 1f;
        if (((int) spawn[25] & 4) != 0) up = Math.abs(up);
        float heading = rand(SHAPE, key, 1) * 6.2831853f;
        float across = (float) Math.sqrt(Math.max(1f - up * up, 0f));
        float[] dir = orient(across * (float) Math.cos(heading), up, across * (float) Math.sin(heading),
                (float) event[6], (float) event[7], (float) event[8]);
        float start = spawn[29] * (float) Math.pow(rand(SHAPE, key, 2), 1.0 / 3.0);
        float speed = spawn[0] + (spawn[1] - spawn[0]) * rand(SPEED, key, 0);
        CgVfxRecord layout = children.record();
        float age = layout.f(childRecords, r, CgVfxRecord.AGE);
        for (int a = 0; a < 3; a++) {
            double born = event[a] - CHILD_ORIGIN[a] + dir[a] * start;
            double velocity = dir[a] * speed + inherit[a] * event[3 + a];
            double want = born + velocity * age;
            float got = layout.f(childRecords, r, CgVfxRecord.POSITION + a);
            float gotV = layout.f(childRecords, r, CgVfxRecord.VELOCITY + a);
            float off = (float) Math.max(Math.abs(got - want), Math.abs(gotV - velocity)) / (TOLERANCE * (1f + (float) Math.abs(want)));
            worst = Math.max(worst, off);
            if (!(off <= 1f)) {
                fail("child " + key + " axis " + a + " is at " + got + " moving " + gotV + ", not " + want + " moving " + velocity);
                return;
            }
        }
        checked++;
    }

    /** The launch's rolls of an emitter made in code, keyed as the kernel keys them (core.md § Values). */
    private static final int SHAPE = CgVfxFormCompiler.key("shape", "shape"), SPEED = CgVfxFormCompiler.key("velocity", "speed");

    private static float rand(int roll, int key, int draw) {
        return CgVfxEmitterInstance.rand(SEED_BITS ^ roll, key, draw);
    }

    /** {@code step_orient}: Duff et al.'s basis about n, the identity for straight up. */
    private static float[] orient(float x, float y, float z, float nx, float ny, float nz) {
        if (nx == 0f && nz == 0f && ny > 0f) return new float[]{x, y, z};
        float sg = nz >= 0f ? 1f : -1f;
        float a = -1f / (sg + nz), b = nx * ny * a;
        float tx = 1f + sg * nx * nx * a, ty = sg * b, tz = -sg * nx;
        float ux = b, uy = sg + ny * ny * a, uz = -ny;
        return new float[]{tx * x + nx * y + ux * z, ty * x + ny * y + uy * z, tz * x + nz * y + uz * z};
    }

    private static CgVfxInstanceView origin(double[] o) {
        return new CgVfxInstanceView() {
            public double originX() { return o[0]; }
            public double originY() { return o[1]; }
            public double originZ() { return o[2]; }
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
        if (GlErrorChecker.checkAndLog("vfx-events")) {
            System.out.println("[vfx-events] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-events] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-events] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-events] FAIL on " + on + ": the rows or the children never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-events] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-events] PASS on " + on + ": a system file's spawn, death, location and burst "
                    + "handlers and a landing in Java; " + PARENTS + " debris each spawned, landed and died once, every row heard; " + checked
                    + " children spawned on the GPU in the step of their event, each where Java puts it (worst share of the "
                    + "tolerance " + worst + "); " + located + " location children, " + LOCATION_CAP + " a step for "
                    + locationSteps + " steps");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** A definition with events, the rest its delegate's: the debris's file events, the first three reporting rows. */
    private record Evented(CgVfxEmitter of, List<CgVfxEvent> events) implements CgVfxGpuEmitter {
        public String name() { return of.name(); }
        public CgVfxDrawLayout drawLayout() { return of.drawLayout(); }
        public List<? extends CgVfxGpuModule> modules() { return of.modules(); }
        public int[] moduleWords() { return of.moduleWords(); }
        public void writeSpawn(CgVfxWords out) { of.writeSpawn(out); }
        public void writeCurves(float[] out, int at, int texels) { of.writeCurves(out, at, texels); }
    }
}
