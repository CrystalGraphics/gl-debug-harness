package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.render.world.CgWorldVoxelWindow;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for a collider's Cascade response ({@code CgVfxModule.CollisionResponse}, 4.21's
 * {@code ParticleModules_Collision.cpp}): four groups dropped onto a ground of the voxel window through the engine's own
 * {@code CollideWorld}, each checked from its records and its collision and death rows against Java.
 * <ul>
 *   <li><b>Damping</b>, no gravity: one bounce, after which its velocity is its damping, rolled per particle and per
 *       axis from its id, times its velocity mirrored about the ground, to a tolerance; its mesh turn scaled from
 *       then on.</li>
 *   <li><b>Freeze</b> after 3: collisions numbered 1 to 3, then still on the ground, holding its state.</li>
 *   <li><b>Halt</b> after a count rolled between 1 and 2: that many collisions, then none, fallen through the ground.</li>
 *   <li><b>Kill</b> with a delay of half its life: all alive at a quarter of it though sunk in the ground, then each
 *       killed at its first contact, heard dying.</li>
 * </ul>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-cascade-collide"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-cascade-collide" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-cascade-collide --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-cascade-collide] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxCascadeCollideScene implements InteractiveSceneLifecycle {

    /** Frames the window fills before anything moves, then steps (MID a quarter of the delayed group's life), then frames for the answers. */
    private static final int FILL = 120, STEPS = 180, MID = 30, DRAIN = 40;
    private static final float DT = 1f / 60f;
    private static final int CX = -1000, CZ = 2000, GROUND = 64;
    private static final double[] ORIGIN = {CX + 0.25, 66.5, CZ + 0.75};
    /** The ground's top from the origin. */
    private static final float FLOOR = (float) (GROUND - ORIGIN[1]);
    private static final int N = 64, SEED_BITS = 0x5EED;
    private static final int DAMP = 0, FREEZE = 1, HALT = 2, KILL = 3;
    private static final String[] NAMES = {"damping", "freeze", "halt", "kill"};
    private static final float LO_X = 0.2f, LO_Y = 0.4f, LO_Z = 0.6f, HI_X = 0.4f, HI_Y = 0.8f, HI_Z = 0.9f;
    private static final float TURN = 0.5f, DELAY = 0.5f, KILL_LIFE = 2f;
    /** What the collider's rolls are keyed by: fx_contact.glsl's FX_COLLISION_KEY. */
    private static final int KEY = 0x434F4C4C;
    private static final float TOLERANCE = 1e-4f;

    private final CgVfxEmitter[] emitters = {
            emitter(NAMES[DAMP], false, CgVfxModule.CollisionResponse.NONE
                    .damping(LO_X, LO_Y, LO_Z, HI_X, HI_Y, HI_Z, CgVfxModule.CollisionResponse.PER_AXIS).rotation(TURN, TURN, TURN)),
            emitter(NAMES[FREEZE], true, CgVfxModule.CollisionResponse.NONE.limit(3, CgVfxModule.CollisionCompletion.FREEZE)),
            emitter(NAMES[HALT], true, CgVfxModule.CollisionResponse.NONE.limit(1f, 2f, CgVfxModule.CollisionCompletion.HALT)),
            emitter(NAMES[KILL], true, CgVfxModule.CollisionResponse.NONE.delay(DELAY)
                    .limit(1, CgVfxModule.CollisionCompletion.KILL))};
    private final CgVfxParticlePool[] pools = new CgVfxParticlePool[4];
    private final List<CgVfxParticlePool> distinct = new ArrayList<>();
    private final int[] slots = new int[4];
    private final Map<CgVfxParticlePool, ByteBuffer> records = new IdentityHashMap<>(), midRecords = new IdentityHashMap<>();
    private final Map<CgVfxParticlePool, Integer> live = new IdentityHashMap<>(), midLive = new IdentityHashMap<>();
    /** Per group, per particle, its collision firings in the order heard, and its deaths heard. */
    private final List<Map<Integer, List<Integer>>> hits = new ArrayList<>();
    private final List<Map<Integer, Integer>> deaths = new ArrayList<>();
    /** Each damping particle's velocity as seeded. */
    private final float[][] seeded = new float[N][];
    private final World world = new World();
    private final CgHostView view = new CgHostView();
    private final List<CgRequest> requests = new ArrayList<>();
    private final CgVfxEventListener listener = this::heard;
    private int frame, checked;
    private String failure;
    private boolean compared, running = true;

    private static CgVfxEmitter emitter(String name, boolean gravity, CgVfxModule.CollisionResponse response) {
        CgVfxEmitter.Builder b = CgVfxEmitter.builder("cascade-collide-" + name).state(CgVfxEmitterState.ONCE).capacity(N)
                .burst(0f, 1).life(100f, 100f).size(0.1f, 0.1f, 1f)
                .event(CgVfxEvent.onCollision().readback(4 * N)).event(CgVfxEvent.onDeath().readback(N));
        if (gravity) b.module(new CgVfxModule.Gravity(9.8f));
        return b.module(new CgVfxModule.CollideWorld(0.5f, 0f, 0f).response(response)).build();
    }

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(world, CgEntityQuery.NONE));
        Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, 0.1f, -0.45f, -1f, 0f, 1f, 0f);
        view.set(CX, 76.0, CZ + 20.5, look, new Matrix4f().perspective((float) Math.toRadians(80), 16f / 9f, 0.05f, 300f));
        for (int g = 0; g < 4; g++) {
            CgVfxParticlePool.prepare(emitters[g]);
            hits.add(new HashMap<>());
            deaths.add(new HashMap<>());
        }
        CgVfxParticlePool.listen(listener);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= FILL + STEPS + 1) {
            CgRecording rec = new CgRecording();
            CgWorldVoxelWindow.get().useDistance().record(rec, view);
            if (frame == FILL) seed(rec);
            if (frame > FILL) step(rec);
            if (frame == FILL + MID || frame == FILL + STEPS + 1) readBack(rec, frame == FILL + MID);
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        boolean all = records.size() == distinct.size() && midRecords.size() == distinct.size();
        if (!compared && answered && all && frame > FILL + STEPS + 15) {
            compare();
            compared = true;
        }
        if (compared || frame > FILL + STEPS + DRAIN) {
            report();
            CgVfxParticlePool.stopListening(listener);
            CgVfxParticlePool.release(this);
            running = false;
        }
    }

    private void readBack(CgRecording rec, boolean mid) {
        for (CgVfxParticlePool pool : distinct) {
            requests.add(rec.readback(pool.live(), 0, 4, data -> (mid ? midLive : live).put(pool, data.getInt(0))));
            requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * pool.record().bytes(),
                    data -> (mid ? midRecords : records).put(pool, copy(data))));
        }
    }

    /** Each pool seeded once with every group it holds: a seed replaces what a pool held. */
    private void seed(CgRecording rec) {
        if (!CgWorldVoxelWindow.get().live()) fail("the window has no level after " + FILL + " frames");
        for (int g = 0; g < 4; g++) {
            pools[g] = CgVfxParticlePool.of(this, emitters[g]);
            slots[g] = pools[g].open(emitters[g], N);
            if (!distinct.contains(pools[g])) distinct.add(pools[g]);
        }
        Random random = new Random(1202);
        for (CgVfxParticlePool pool : distinct) {
            CgVfxRecord layout = pool.record();
            List<Integer> groups = new ArrayList<>();
            for (int g = 0; g < 4; g++) if (pools[g] == pool) groups.add(g);
            ByteBuffer packed = ByteBuffer.allocateDirect(groups.size() * N * layout.bytes()).order(ByteOrder.nativeOrder());
            int at = 0;
            for (int g : groups) {
                for (int id = 0; id < N; id++, at++) {
                    float x = -3f + random.nextFloat() * 4f, z = (random.nextFloat() * 2f - 1f) * 3f;
                    float vx = random.nextFloat() - 0.5f, vz = random.nextFloat() - 0.5f, seed = random.nextFloat();
                    switch (g) {
                        case DAMP -> {
                            float vy = -2f - 2f * random.nextFloat();
                            seeded[id] = new float[]{vx, vy, vz};
                            layout.put(packed, at, x, FLOOR + 1f, z, vx, vy, vz, 0f, 100f, 0.1f, seed, id, slots[g]);
                        }
                        // Sunk a little into the ground and still: without its delay, its first step kills it.
                        case KILL -> layout.put(packed, at, x, FLOOR - 0.2f, z, 0f, 0f, 0f, 0f, KILL_LIFE, 0.1f, seed, id, slots[g]);
                        default -> layout.put(packed, at, x, FLOOR + 2f, z, vx, 0f, vz, 0f, 100f, 0.1f, seed, id, slots[g]);
                    }
                }
            }
            pool.seed(rec, packed, at);
        }
    }

    private void step(CgRecording rec) {
        for (CgVfxParticlePool pool : distinct) {
            pool.beginStep(DT, 0f, 0f, 0f);
            for (int g = 0; g < 4; g++) if (pools[g] == pool) pool.instance(slots[g], SEED_BITS, 1f, Float.NaN, origin());
            pool.endStep();
        }
        CgVfxParticlePool.record(rec, distinct);
    }

    private void heard(CgVfxGpuEmitter definition, int event, CgVfxEventRows heard) {
        int g = -1;
        for (int k = 0; k < 4; k++) if (definition == emitters[k]) g = k;
        if (g < 0) {
            fail("rows heard for " + definition.name());
            return;
        }
        if (heard.dropped() > 0) fail(heard.dropped() + " rows dropped");
        for (int i = 0; i < heard.count(); i++) {
            if (event == 0) hits.get(g).computeIfAbsent(heard.parentId(i), k -> new ArrayList<>()).add(heard.firing(i));
            else deaths.get(g).merge(heard.parentId(i), 1, Integer::sum);
        }
    }

    private void compare() {
        damping();
        frozen();
        halted();
        killed();
    }

    /** The records of group {@code g} at the end (or {@code mid}), by id. */
    private Map<Integer, Integer> group(int g, boolean mid) {
        CgVfxParticlePool pool = pools[g];
        ByteBuffer data = (mid ? midRecords : records).get(pool);
        int count = (mid ? midLive : live).get(pool);
        Map<Integer, Integer> byId = new HashMap<>();
        for (int r = 0; r < count; r++) {
            if (pool.record().i(data, r, CgVfxRecord.SLOT) == slots[g]) byId.put(pool.record().i(data, r, CgVfxRecord.ID), r);
        }
        return byId;
    }

    /** One bounce each, its velocity its rolled damping times its seeded velocity mirrored about the ground. */
    private void damping() {
        CgVfxRecord layout = pools[DAMP].record();
        ByteBuffer data = records.get(pools[DAMP]);
        int turnState = layout.word(CgVfxRecord.Tail.TURN_STATE);
        if (turnState < 0) fail("a damping collider stores no turn state");
        Map<Integer, Integer> byId = group(DAMP, false);
        if (byId.size() != N) fail(byId.size() + " damping particles alive, not " + N);
        for (Map.Entry<Integer, Integer> e : byId.entrySet()) {
            int id = e.getKey(), r = e.getValue();
            List<Integer> firings = hits.get(DAMP).getOrDefault(id, List.of());
            if (!firings.equals(List.of(1))) fail("damping particle " + id + "'s collisions are " + firings + ", not [1]");
            float[] v = seeded[id];
            float[] want = {mix(LO_X, HI_X, roll(id, 0)) * v[0], mix(LO_Y, HI_Y, roll(id, 1)) * -v[1], mix(LO_Z, HI_Z, roll(id, 2)) * v[2]};
            for (int a = 0; a < 3; a++) {
                float got = layout.f(data, r, CgVfxRecord.VELOCITY + a);
                if (Math.abs(got - want[a]) > TOLERANCE * (1f + Math.abs(want[a]))) {
                    fail("damping particle " + id + " moves " + got + " on axis " + a + ", not " + want[a]);
                }
                float scale = turnState < 0 ? TURN : layout.f(data, r, turnState + 3 + a);
                if (Math.abs(scale - TURN) > TOLERANCE) fail("damping particle " + id + "'s turn scale is " + scale + ", not " + TURN);
            }
            checked++;
            if (failure != null) return;
        }
    }

    /** Collisions numbered 1 to 3, then still on the ground, its velocity none, held still and done colliding. */
    private void frozen() {
        CgVfxRecord layout = pools[FREEZE].record();
        ByteBuffer data = records.get(pools[FREEZE]);
        int flags = layout.word(CgVfxRecord.Tail.FLAGS);
        Map<Integer, Integer> byId = group(FREEZE, false);
        if (byId.size() != N) fail(byId.size() + " frozen particles alive, not " + N);
        for (Map.Entry<Integer, Integer> e : byId.entrySet()) {
            int id = e.getKey(), r = e.getValue();
            List<Integer> firings = hits.get(FREEZE).getOrDefault(id, List.of());
            if (!firings.equals(List.of(1, 2, 3))) fail("frozen particle " + id + "'s collisions are " + firings + ", not [1, 2, 3]");
            int state = layout.i(data, r, flags) & 0xFFFE;
            if (state != 6) fail("frozen particle " + id + "'s state is " + state + ", not still and halted (6)");
            for (int a = 0; a < 3; a++) {
                if (layout.f(data, r, CgVfxRecord.VELOCITY + a) != 0f) fail("frozen particle " + id + " still moves");
            }
            float y = layout.f(data, r, CgVfxRecord.POSITION + 1);
            if (Math.abs(y - FLOOR) > 0.05f) fail("frozen particle " + id + " rests at " + y + ", not on the ground at " + FLOOR);
            checked++;
            if (failure != null) return;
        }
    }

    /** Its rolled count of collisions, then none: halted alone, and fallen through the ground. */
    private void halted() {
        CgVfxRecord layout = pools[HALT].record();
        ByteBuffer data = records.get(pools[HALT]);
        int flags = layout.word(CgVfxRecord.Tail.FLAGS);
        int ones = 0, twos = 0;
        Map<Integer, Integer> byId = group(HALT, false);
        if (byId.size() != N) fail(byId.size() + " halted particles alive, not " + N);
        for (Map.Entry<Integer, Integer> e : byId.entrySet()) {
            int id = e.getKey(), r = e.getValue();
            int most = Math.max((int) Math.floor(mix(1f, 2f, roll(id, 3)) + 0.5f), 1);
            if (most == 1) ones++;
            else twos++;
            List<Integer> firings = hits.get(HALT).getOrDefault(id, List.of());
            List<Integer> want = most == 1 ? List.of(1) : List.of(1, 2);
            if (!firings.equals(want)) fail("halted particle " + id + "'s collisions are " + firings + ", not " + want);
            if ((layout.i(data, r, flags) & 0xFFFE) != 2) fail("halted particle " + id + " is not halted alone");
            float y = layout.f(data, r, CgVfxRecord.POSITION + 1);
            if (y >= FLOOR) fail("halted particle " + id + " is at " + y + ", not fallen through the ground at " + FLOOR);
            checked++;
            if (failure != null) return;
        }
        if (ones == 0 || twos == 0) fail(ones + " halted after 1 and " + twos + " after 2: both are the test");
    }

    /** All alive within their delay though sunk in the ground; then every one killed at its first contact. */
    private void killed() {
        int alive = group(KILL, true).size();
        if (alive != N) fail(alive + " of " + N + " delayed particles alive at a quarter of their life");
        if (!group(KILL, false).isEmpty()) fail(group(KILL, false).size() + " delayed particles outlived their first contact");
        for (int id = 0; id < N; id++) {
            List<Integer> firings = hits.get(KILL).getOrDefault(id, List.of());
            if (!firings.equals(List.of(1))) fail("delayed particle " + id + "'s collisions are " + firings + ", not [1]");
            if (deaths.get(KILL).getOrDefault(id, 0) != 1) fail("delayed particle " + id + " is not heard dying once");
            checked++;
        }
    }

    /** The collider's {@code k}th roll for particle {@code id}: fx_contact.glsl's, keyed by its id. */
    private static float roll(int id, int k) {
        return CgVfxEmitterInstance.rand(id, KEY, k);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
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
        if (GlErrorChecker.checkAndLog("vfx-cascade-collide")) {
            System.out.println("[vfx-cascade-collide] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-cascade-collide] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-cascade-collide] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-cascade-collide] FAIL on " + on + ": the rows or the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-cascade-collide] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-cascade-collide] PASS on " + on + ": " + checked + " particles: damping per axis to "
                    + TOLERANCE + " with its turn scaled, frozen after 3, halted after 1 or 2 and fallen through, killed at"
                    + " the first contact past a delay of half their life");
        }
    }

    @Override
    public void dispose() {
        CgPlatform.provide(CgLevels.SERVICE, null);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** Solid below {@link #GROUND}; full sky light. */
    private static final class World implements CgWorldQuery {
        static boolean solid(int y) {
            return y < GROUND;
        }

        @Override
        public int collisionBoxes(int x, int y, int z, float[] out) {
            if (!solid(y)) return 0;
            if (out.length >= 6) {
                out[0] = 0f;
                out[1] = 0f;
                out[2] = 0f;
                out[3] = 1f;
                out[4] = 1f;
                out[5] = 1f;
            }
            return 1;
        }

        @Override public float collisionTop(int x, int y, int z) { return solid(y) ? 1f : Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return solid(y) ? 0f : Float.NaN; }
        @Override public int light(int x, int y, int z) { return 15 << 4; }
        @Override public int surface(int x, int y, int z) { return solid(y) ? SURFACE_STONE : SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return solid(y) ? 1.5f : Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0xFF808080; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return GROUND; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public float fluidHeight(int x, int y, int z) { return Float.NaN; }
        @Override public int fluidKind(int x, int y, int z) { return FLUID_NONE; }
        @Override public int mapColor(int x, int y, int z) { return solid(y) ? 0xFF707070 : 0; }
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public int minY() { return 0; }
        @Override public int maxY() { return 256; }
        @Override public int seaLevel() { return 63; }
        @Override public int levelEpoch() { return 1; }
    }
}
