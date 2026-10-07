package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.service.CgWorldQuery;
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
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.world.CgVfxVoxelWindow;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The gate for collisions and repeating events (plan vfx-gpu X6): debris thrown at a wall over the ground, bounced by a
 * module kind given as GLSL text that reads the voxel window's solid octants and its distance field. Each impact fast
 * enough is a punctual collision, a slower one a slide that is none; the fourth kills the debris. Every collision spawns
 * dust in a second pool in its step, and a rate reports each debris every {@link #PERIOD} seconds. Checked from the rows
 * the CPU hears: each debris's collisions numbered 1 to its count with no gap, each struck faster than the slide and
 * with a normal off the ground or the wall, each child where Java puts it from its row under the firing's key; each
 * rate firing once, 1 to {@code floor(age / PERIOD)}; a death for each debris killed; nothing ends inside a solid block.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-collide"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-collide" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-collide --device=vulkan" -Dcrystalgraphics.vulkan.syncValidation=true
 * }</pre>
 *
 * <p>Prints {@code [vfx-collide] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxCollideScene implements InteractiveSceneLifecycle {

    /** Frames the window fills and floods before anything moves, then steps, then frames for the answers. */
    private static final int FILL = 120, STEPS = 180, DRAIN = 40;
    private static final float DT = 1f / 60f;
    /** Solid below GROUND, and a wall from WALL_X east up to WALL_TOP; far from 0 at negative x, so the window wraps. */
    private static final int CX = -1000, CZ = 2000, GROUND = 64, WALL_X = CX + 4, WALL_TOP = 69;
    private static final double[] PARENT_ORIGIN = {CX + 0.25, 66.5, CZ + 0.75}, CHILD_ORIGIN = {CX - 2.0, 65.5, CZ + 1.25};
    private static final int PARENTS = 400, COUNT = 2, KILL_AFTER = 4, SEED_BITS = 0x5EED;
    private static final float BOUNCE = 0.6f, SLIDE = 1.5f, PERIOD = 0.0937f, INHERIT = 0.25f;
    private static final int COLLISION = 0, RATE = 1, DEATH = 2;
    private static final float TOLERANCE = 2e-3f;

    /** No forces: a child drifts at its launch, so where it is follows from its age. */
    private final CgVfxEmitter dust = CgVfxEmitter.builder("collide-dust").capacity(4000).burst(0f, 1).shape(0.2f)
            .launch(0.2f, 1f, 1.5f).speed(0.5f, 1.5f).life(50f, 60f).size(0.1f, 0.2f, 1f).build();
    private final CgVfxEmitter base = CgVfxEmitter.builder("collide-debris").capacity(PARENTS).burst(0f, 1)
            .life(100f, 100f).size(0.1f, 0.2f, 1f).module(new CgVfxModule.Gravity(9.8f)).build();
    private final CgVfxGpuEmitter debris = new Colliding(base, List.of(base.modules().get(0), new Bounce()),
            List.of(CgVfxEvent.onCollision().spawn(dust, COUNT).inherit(INHERIT).readback(4000),
                    CgVfxEvent.every(PERIOD).readback(4000), CgVfxEvent.onDeath().readback(4000)));
    private final World world = new World();
    private final CgHostView view = new CgHostView();
    private final float[] spawn = new float[4 * CgVfxGpuEmitter.SPAWN_VECTORS];
    private final List<CgRequest> requests = new ArrayList<>();
    /** Per event, per debris, its rows by firing: (x, y, z, vx, vy, vz, nx, ny, nz). */
    private final List<Map<Integer, TreeMap<Integer, double[]>>> rows = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
    private final CgVfxEventListener listener = this::heard;
    private CgVfxParticlePool parents, children;
    private int parentSlot, childSlot;
    private ByteBuffer parentRecords, childRecords;
    private int frame, parentLive = -1, childLive = -1, collisions, onGround, onWall, rates, killed, checked, repeated, dropped;
    private float worst;
    private String failure;
    private boolean compared, running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgWorldQuery.SERVICE, world);
        Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, 0.1f, -0.45f, -1f, 0f, 1f, 0f);
        view.set(CX, 76.0, CZ + 20.5, look, new Matrix4f().perspective((float) Math.toRadians(80), 16f / 9f, 0.05f, 300f));
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
        if (frame <= FILL + STEPS + 1) {
            CgRecording rec = new CgRecording();
            CgVfxVoxelWindow.get().useDistance().record(rec, view);
            if (frame == FILL) seed(rec);
            if (frame > FILL) step(rec);
            if (frame == FILL + STEPS + 1) {
                requests.add(rec.readback(parents.live(), 0, 4, data -> parentLive = data.getInt(0)));
                requests.add(rec.readback(parents.records(), 0, (long) parents.storage() * CgVfxRecord.BYTES,
                        data -> parentRecords = copy(data)));
                requests.add(rec.readback(children.live(), 0, 4, data -> childLive = data.getInt(0)));
                requests.add(rec.readback(children.records(), 0, (long) children.storage() * CgVfxRecord.BYTES,
                        data -> childRecords = copy(data)));
            }
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && parentRecords != null && childRecords != null && frame > FILL + STEPS + 15) {
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

    private void seed(CgRecording rec) {
        if (!CgVfxVoxelWindow.get().live()) fail("the window has no level after " + FILL + " frames");
        parents = CgVfxParticlePool.of(this, debris);
        children = CgVfxParticlePool.of(this, dust);
        if (parents == children) fail("the debris and its dust share a pool");
        parentSlot = parents.open(debris, PARENTS);
        childSlot = children.open(dust, PARENTS * COUNT * KILL_AFTER);
        children.feed(childSlot, parents, parentSlot, COLLISION);
        Random random = new Random(1201);
        ByteBuffer packed = ByteBuffer.allocateDirect(PARENTS * CgVfxRecord.BYTES).order(ByteOrder.nativeOrder());
        for (int r = 0; r < PARENTS; r++) {
            float x = -3f + random.nextFloat() * 5f, y = -1f + random.nextFloat() * 2.5f, z = (random.nextFloat() * 2f - 1f) * 3f;
            float vx = 1f + random.nextFloat() * 6f, vy = -1f + random.nextFloat() * 4f, vz = random.nextFloat() * 2f - 1f;
            int at = r * CgVfxRecord.BYTES;
            packed.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, 0f);
            packed.putFloat(at + 16, x).putFloat(at + 20, y).putFloat(at + 24, z).putFloat(at + 28, 100f);
            packed.putFloat(at + 32, vx).putFloat(at + 36, vy).putFloat(at + 40, vz).putFloat(at + 44, 0.1f);
            packed.putFloat(at + 48, random.nextFloat()).putFloat(at + 52, 0f).putFloat(at + 56, 0f).putFloat(at + 60, 0f);
            packed.putInt(at + 64, r).putInt(at + 68, parentSlot).putInt(at + 72, 0).putInt(at + 76, 0);
        }
        parents.seed(rec, packed, PARENTS);
    }

    private void step(CgRecording rec) {
        parents.beginStep(DT, 0f, 0f, 0f);
        parents.instance(parentSlot, SEED_BITS, 1f, Float.NaN, origin(PARENT_ORIGIN));
        parents.endStep();
        children.beginStep(DT, 0f, 0f, 0f);
        children.instance(childSlot, SEED_BITS, 1f, Float.NaN, origin(CHILD_ORIGIN));
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
            if (rows.get(event).computeIfAbsent(heard.parentId(i), k -> new TreeMap<>()).put(heard.firing(i), row) != null) repeated++;
        }
    }

    private void compare() {
        if (repeated > 0 || dropped > 0) fail(repeated + " firings heard twice, " + dropped + " rows dropped");
        Map<Integer, Integer> alive = new HashMap<>();
        for (int r = 0; r < parentLive; r++) {
            alive.put(CgVfxRecord.i(parentRecords, r, CgVfxRecord.ID), r);
            double[] at = place(parentRecords, r, PARENT_ORIGIN);
            if (world.inside(at[0], at[1], at[2])) fail("debris " + CgVfxRecord.i(parentRecords, r, CgVfxRecord.ID) + " ends inside a solid block");
        }
        killed = PARENTS - parentLive;
        if (rows.get(DEATH).size() != killed) fail(rows.get(DEATH).size() + " deaths heard, " + killed + " debris gone");
        if (killed == 0 || parentLive == 0) fail("the bounce killed " + killed + " of " + PARENTS + " debris: both kinds are the test");
        for (int id = 0; id < PARENTS; id++) {
            TreeMap<Integer, double[]> hits = rows.get(COLLISION).getOrDefault(id, new TreeMap<>());
            Integer r = alive.get(id);
            int want = r == null ? KILL_AFTER : CgVfxRecord.i(parentRecords, r, CgVfxRecord.FLAGS) >>> CgVfxRecord.COLLISIONS_SHIFT;
            if (r == null && !rows.get(DEATH).containsKey(id)) fail("debris " + id + " is gone with no death heard");
            if (!numbered(hits, want)) fail("debris " + id + "'s collisions are " + hits.keySet() + ", not 1 to " + want);
            for (Map.Entry<Integer, double[]> hit : hits.entrySet()) collided(id, hit.getKey(), hit.getValue());
            TreeMap<Integer, double[]> rated = rows.get(RATE).getOrDefault(id, new TreeMap<>());
            int firings = r == null ? rated.size() : (int) Math.floor(CgVfxRecord.f(parentRecords, r, CgVfxRecord.AGE) / PERIOD);
            if (!numbered(rated, firings) || firings == 0) fail("debris " + id + "'s rate fired " + rated.keySet() + ", not 1 to " + firings);
            rates += rated.size();
            if (failure != null) return;
        }
        if (onGround == 0 || onWall == 0) fail(onGround + " collisions with the ground and " + onWall + " with the wall: both are the test");
        int expected = collisions * COUNT;
        if (childLive != expected) fail(childLive + " dust alive, " + expected + " expected");
    }

    /** Whether {@code firings} holds exactly 1 to {@code count}. */
    private static boolean numbered(TreeMap<Integer, double[]> firings, int count) {
        return firings.size() == count && (count == 0 || firings.firstKey() == 1 && firings.lastKey() == count);
    }

    /** One collision: outside the solid, struck faster than the slide, off a face, and its dust where Java puts it. */
    private void collided(int id, int firing, double[] row) {
        collisions++;
        float into = -(float) (row[3] * row[6] + row[4] * row[7] + row[5] * row[8]);
        double length = Math.sqrt(row[6] * row[6] + row[7] * row[7] + row[8] * row[8]);
        if (world.inside(row[0], row[1], row[2])) fail("debris " + id + "'s collision " + firing + " is inside a solid block");
        if (into < SLIDE - 1e-4f || Math.abs(length - 1.0) > 1e-3) {
            fail("debris " + id + "'s collision " + firing + " struck at " + into + " along a normal " + length + " long");
        }
        if (row[7] > 0.7) onGround++;
        if (row[6] < -0.7) onWall++;
        Map<Integer, Integer> byId = childrenById();
        for (int i = 0; i < COUNT; i++) {
            int key = CgVfxEvent.childKey(id, COLLISION, i, firing);
            Integer r = byId.get(key);
            if (r == null) {
                fail("no dust " + i + " of debris " + id + "'s collision " + firing);
                return;
            }
            check(key, row, r);
        }
    }

    private Map<Integer, Integer> childIds;

    private Map<Integer, Integer> childrenById() {
        if (childIds == null) {
            childIds = new HashMap<>();
            for (int r = 0; r < childLive; r++) {
                if (childIds.put(CgVfxRecord.i(childRecords, r, CgVfxRecord.ID), r) != null) fail("two dust share an id");
            }
        }
        return childIds;
    }

    /** Dust {@code key} as {@code step_launch} launches it about the collision's normal, then drifted for its age. */
    private void check(int key, double[] event, int r) {
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
            double velocity = dir[a] * speed + INHERIT * event[3 + a];
            double want = born + velocity * age;
            float got = CgVfxRecord.f(childRecords, r, CgVfxRecord.POSITION + a);
            float gotV = CgVfxRecord.f(childRecords, r, CgVfxRecord.VELOCITY + a);
            float off = (float) Math.max(Math.abs(got - want), Math.abs(gotV - velocity)) / (TOLERANCE * (1f + (float) Math.abs(want)));
            worst = Math.max(worst, off);
            if (!(off <= 1f)) {
                fail("dust " + key + " axis " + a + " is at " + got + " moving " + gotV + ", not " + want + " moving " + velocity);
                return;
            }
        }
        checked++;
    }

    private static double[] place(ByteBuffer records, int r, double[] origin) {
        return new double[]{origin[0] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION),
                origin[1] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 1),
                origin[2] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 2)};
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
        if (GlErrorChecker.checkAndLog("vfx-collide")) {
            System.out.println("[vfx-collide] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-collide] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-collide] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-collide] FAIL on " + on + ": the rows or the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-collide] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-collide] PASS on " + on + ": " + collisions + " collisions (" + onGround + " off the ground, "
                    + onWall + " off the wall), each debris's numbered 1 to its count; " + killed + " killed at their "
                    + KILL_AFTER + "th, each heard dying; " + checked + " dust each where Java puts it under its firing's key "
                    + "(worst share of the tolerance " + worst + "); " + rates + " rate firings, each once; none inside the solid");
        }
    }

    @Override
    public void dispose() {
        CgPlatform.provide(CgWorldQuery.SERVICE, CgWorldQuery.NONE);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /**
     * Back to where it was on entering a solid octant, its normal the distance field's gradient there. Struck at
     * {@code m.y} or faster it bounces, keeping {@code m.x} of its speed, and is a collision; slower it slides, which is
     * none. Its {@code m.z}th collision kills it.
     */
    private static final class Bounce implements CgVfxGpuModule {
        private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.WORLD_DISTANCE};
        public String gpuKind() { return "gate_bounce"; }
        public boolean afterSolve() { return true; }
        public CgVfxWorldInput[] worldInputs() { return WORLD; }
        public void writeParams(CgVfxWords out) { out.vec4(BOUNCE, SLIDE, KILL_AFTER, 0f); }
        public String gpuSource() {
            return """
                    void fx_gate_bounce(inout FxParticle p, FxStep s, vec4 m, FxWorld world) {
                        if (!fx_world_solid(world, p.position)) return;
                        vec3 g;
                        fx_world_sdf(world, p.previous, g);
                        vec3 n = length(g) > 0.0 ? normalize(g) : vec3(0.0, 1.0, 0.0);
                        float into = -dot(p.velocity, n);
                        p.position = p.previous;
                        if (into >= m.y) {
                            fx_hit(p, n);
                            p.velocity = reflect(p.velocity, n) * m.x;
                            if (p.collisions >= uint(m.z)) p.life = p.age;
                        } else {
                            p.velocity -= n * min(dot(p.velocity, n), 0.0);
                        }
                    }
                    """;
        }
    }

    /** The debris's definition: its builder's numbers, the bounce after gravity, and the events. */
    private record Colliding(CgVfxEmitter of, List<CgVfxGpuModule> modules, List<CgVfxEvent> events) implements CgVfxGpuEmitter {
        public String name() { return of.name(); }
        public CgVfxEmitter.Renderer renderer() { return of.renderer(); }
        public void writeSpawn(CgVfxWords out) { of.writeSpawn(out); }
        public void writeCurves(float[] out, int at, int texels) { of.writeCurves(out, at, texels); }
    }

    /** Solid below {@link #GROUND}, and a wall from {@link #WALL_X} east up to {@link #WALL_TOP}; full sky light. */
    private static final class World implements CgWorldQuery {

        static boolean solid(int x, int y, int z) {
            return y < GROUND || x >= WALL_X && y < WALL_TOP;
        }

        /** Whether a point is inside solid by more than rounding: each point a hair from it on every axis is too. */
        boolean inside(double x, double y, double z) {
            double e = 1e-4;
            for (int c = 0; c < 8; c++) {
                double px = x + ((c & 1) == 0 ? -e : e), py = y + ((c & 2) == 0 ? -e : e), pz = z + ((c & 4) == 0 ? -e : e);
                if (!solid((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz))) return false;
            }
            return true;
        }

        @Override
        public int collisionBoxes(int x, int y, int z, float[] out) {
            if (!solid(x, y, z)) return 0;
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

        @Override public float collisionTop(int x, int y, int z) { return solid(x, y, z) ? 1f : Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return solid(x, y, z) ? 0f : Float.NaN; }
        @Override public int light(int x, int y, int z) { return 15 << 4; }
        @Override public int surface(int x, int y, int z) { return solid(x, y, z) ? SURFACE_STONE : SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return solid(x, y, z) ? 1.5f : Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0xFF808080; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return GROUND; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public float fluidHeight(int x, int y, int z) { return Float.NaN; }
        @Override public int fluidKind(int x, int y, int z) { return FLUID_NONE; }
        @Override public int mapColor(int x, int y, int z) { return solid(x, y, z) ? 0xFF707070 : 0; }
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public int minY() { return 0; }
        @Override public int maxY() { return 256; }
        @Override public int seaLevel() { return 63; }
        @Override public int levelEpoch() { return 1; }
    }
}
