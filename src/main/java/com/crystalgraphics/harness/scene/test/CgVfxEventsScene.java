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
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for events (plan vfx-gpu X5): debris falls onto the fixed ground in one pool, and a child pool spawns from
 * its events in the same step, with no CPU in the loop. Three children at each landing, carrying half the debris's
 * speed; two at an age it reaches mid-fall, launched about its tilted direction; its death only reported. Every event's
 * rows reach the CPU, and from them each child is worked out in Java (its key, where it was born, how it was launched,
 * where it has drifted since): the children read back must be exactly those, each where Java puts it. The child pool's
 * origin is not the parent's, so a child is placed from the parent's block.
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

    private static final int PARENTS = 400, STEPS = 150, DRAIN = 40;
    private static final float DT = 1f / 60f, GROUND = -1f, AGE = 0.3f, PARENT_LIFE = 1.5f;
    private static final int LANDING = 0, AT_AGE = 1, DEATH = 2;
    private static final int[] COUNT = {3, 2};
    private static final float[] INHERIT = {0.5f, 0f};
    private static final double[] PARENT_ORIGIN = {-700.25, 70.5, 1300.75}, CHILD_ORIGIN = {-697.0, 68.5, 1302.25};
    private static final int SEED_BITS = 0x5EED;
    private static final float TOLERANCE = 2e-3f;

    /** No forces: a child drifts at its launch, so where it is follows from its age. */
    private final CgVfxEmitter dust = CgVfxEmitter.builder("events-dust").capacity(2000).burst(0f, 1).shape(0.4f)
            .launch(0.2f, 1f, 1.5f).speed(1f, 3f).life(50f, 60f).size(0.1f, 0.2f, 1f).build();
    private final CgVfxGpuEmitter debris = new Evented(CgVfxEmitter.builder("events-debris").capacity(PARENTS).burst(0f, 1)
            .life(PARENT_LIFE, PARENT_LIFE).size(0.1f, 0.2f, 1f).module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Ground(0f, 1f, 100f)).build(),
            List.of(CgVfxEvent.onLanding().spawn(dust, COUNT[0]).inherit(INHERIT[0]).readback(1000),
                    CgVfxEvent.onAge(AGE).spawn(dust, COUNT[1]).readback(1000), CgVfxEvent.onDeath().readback(1000)));
    private final float[] spawn = new float[4 * CgVfxGpuEmitter.SPAWN_VECTORS];
    private final List<CgRequest> requests = new ArrayList<>();
    /** Each event's rows as they arrived: per event, parent id to (x, y, z, vx, vy, vz, nx, ny, nz). */
    private final List<Map<Integer, double[]>> rows = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
    private final CgVfxEventListener listener = this::heard;
    private CgVfxParticlePool parents, children;
    private int parentSlot;
    private final int[] childSlots = new int[2];
    private ByteBuffer childRecords;
    private int frame, childLive = -1, checked, repeated, dropped;
    private float worst;
    private String failure;
    private boolean compared, running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgVfxParticlePool.prepare(debris);
        CgVfxParticlePool.prepare(dust);
        CgVfxParticlePool.listen(listener);
        CgVfxWords words = new CgVfxWords();
        int[] raw = new int[spawn.length];
        words.target(raw, 0, CgVfxGpuEmitter.SPAWN_VECTORS, dust.name());
        dust.writeSpawn(words);
        words.finish("its spawn numbers");
        for (int i = 0; i < raw.length; i++) spawn[i] = Float.intBitsToFloat(raw[i]);
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
                requests.add(rec.readback(children.records(), 0, (long) children.storage() * CgVfxRecord.BYTES,
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
        children = CgVfxParticlePool.of(this, dust);
        if (parents == children) fail("the debris and its dust share a pool");
        parentSlot = parents.open(debris, PARENTS);
        childSlots[0] = children.open(dust, PARENTS * COUNT[0]);
        childSlots[1] = children.open(dust, PARENTS * COUNT[1]);
        children.feed(childSlots[0], parents, parentSlot, LANDING);
        children.feed(childSlots[1], parents, parentSlot, AT_AGE);
        Random random = new Random(1201);
        ByteBuffer packed = ByteBuffer.allocateDirect(PARENTS * CgVfxRecord.BYTES).order(ByteOrder.nativeOrder());
        for (int r = 0; r < PARENTS; r++) {
            float x = (random.nextFloat() * 2f - 1f) * 3f, y = 1f + random.nextFloat() * 2f, z = (random.nextFloat() * 2f - 1f) * 3f;
            float vx = (random.nextFloat() * 2f - 1f) * 2f, vz = (random.nextFloat() * 2f - 1f) * 2f;
            int at = r * CgVfxRecord.BYTES;
            packed.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, 0f);
            packed.putFloat(at + 16, x).putFloat(at + 20, y).putFloat(at + 24, z).putFloat(at + 28, PARENT_LIFE);
            packed.putFloat(at + 32, vx).putFloat(at + 36, 0f).putFloat(at + 40, vz).putFloat(at + 44, 0.1f);
            packed.putFloat(at + 48, random.nextFloat()).putFloat(at + 52, 0f).putFloat(at + 56, 0f).putFloat(at + 60, 0f);
            packed.putInt(at + 64, r).putInt(at + 68, parentSlot).putInt(at + 72, 0).putInt(at + 76, 0);
        }
        parents.seed(rec, packed, PARENTS);
    }

    private void step(CgRecording rec) {
        parents.beginStep(DT, 0f, 0f, 0f);
        parents.instance(parentSlot, SEED_BITS, 1f, GROUND, origin(PARENT_ORIGIN));
        parents.endStep();
        children.beginStep(DT, 0f, 0f, 0f);
        for (int slot : childSlots) children.instance(slot, SEED_BITS, 1f, Float.NaN, origin(CHILD_ORIGIN));
        children.endStep();
        CgVfxParticlePool.record(rec, List.of(parents, children));
    }

    private void heard(CgVfxGpuEmitter definition, int event, CgVfxEventRows heard) {
        if (definition != debris) {
            fail("rows heard for " + definition.name());
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
            if (Math.abs(landing[1] - (PARENT_ORIGIN[1] + GROUND)) > 1e-4) fail("a landing at y " + landing[1]);
        }
        Map<Integer, Integer> byId = new HashMap<>();
        for (int r = 0; r < childLive; r++) {
            if (byId.put(CgVfxRecord.i(childRecords, r, CgVfxRecord.ID), r) != null) fail("two children share an id");
        }
        int expected = 0;
        for (int e = 0; e < 2; e++) {
            for (Map.Entry<Integer, double[]> parent : rows.get(e).entrySet()) {
                for (int i = 0; i < COUNT[e]; i++, expected++) {
                    int key = CgVfxEvent.childKey(parent.getKey(), e, i);
                    Integer r = byId.get(key);
                    if (r == null) {
                        fail("no child " + i + " of debris " + parent.getKey() + "'s event " + e);
                        return;
                    }
                    if (CgVfxRecord.i(childRecords, r, CgVfxRecord.SLOT) != childSlots[e]) fail("child " + key + " is in the wrong slot");
                    check(key, e, parent.getValue(), r);
                }
            }
        }
        if (childLive != expected) fail(childLive + " children alive, " + expected + " expected");
    }

    /** Child {@code key} as {@code step_launch} launches it from the event, then drifted for its age. */
    private void check(int key, int e, double[] event, int r) {
        float up = spawn[1] + (spawn[2] - spawn[1]) * (float) Math.pow(rand(key, 0), spawn[3]);
        float heading = rand(key, 1) * 6.2831853f;
        float across = (float) Math.sqrt(Math.max(1f - up * up, 0f));
        float[] dir = orient(across * (float) Math.cos(heading), up, across * (float) Math.sin(heading),
                (float) event[6], (float) event[7], (float) event[8]);
        float start = spawn[0] * (float) Math.pow(rand(key, 2), 1.0 / 3.0);
        float speed = spawn[4] + (spawn[5] - spawn[4]) * rand(key, 3);
        float age = CgVfxRecord.f(childRecords, r, CgVfxRecord.AGE);
        for (int a = 0; a < 3; a++) {
            double born = event[a] - CHILD_ORIGIN[a] + dir[a] * start;
            double velocity = dir[a] * speed + INHERIT[e] * event[3 + a];
            double want = born + velocity * age;
            float got = CgVfxRecord.f(childRecords, r, CgVfxRecord.POSITION + a);
            float gotV = CgVfxRecord.f(childRecords, r, CgVfxRecord.VELOCITY + a);
            float off = (float) Math.max(Math.abs(got - want), Math.abs(gotV - velocity)) / (TOLERANCE * (1f + (float) Math.abs(want)));
            worst = Math.max(worst, off);
            if (!(off <= 1f)) {
                fail("child " + key + " axis " + a + " is at " + got + " moving " + gotV + ", not " + want + " moving " + velocity);
                return;
            }
        }
        checked++;
    }

    private static float rand(int key, int draw) {
        return CgVfxEmitterInstance.rand(SEED_BITS, key, draw);
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
            System.out.println("[vfx-events] PASS on " + on + ": " + PARENTS + " debris each landed, reached " + AGE
                    + " s and died once, every row heard; " + checked + " children spawned on the GPU in the step of their "
                    + "event, each where Java puts it from its row (worst share of the tolerance " + worst + ")");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** A definition with events, the rest its delegate's: the builder's {@code event} is the other side's to add. */
    private record Evented(CgVfxEmitter of, List<CgVfxEvent> events) implements CgVfxGpuEmitter {
        public String name() { return of.name(); }
        public CgVfxEmitter.Renderer renderer() { return of.renderer(); }
        public List<? extends CgVfxGpuModule> modules() { return of.modules(); }
        public void writeSpawn(CgVfxWords out) { of.writeSpawn(out); }
        public void writeCurves(float[] out, int at, int texels) { of.writeCurves(out, at, texels); }
    }
}
