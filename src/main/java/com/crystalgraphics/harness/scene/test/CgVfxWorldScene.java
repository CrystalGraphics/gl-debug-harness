package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.world.CgVfxVoxelWindow;
import com.crystalgraphics.vfx.world.CgVfxVoxels;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The gate for the world on the GPU (plan vfx-gpu X4): a world of its own behind {@code CgWorldQuery}, packed into the
 * voxel window, then debris stepped on the GPU onto it and lit from it by Range. Debris over a row of stairs rests on
 * the step it fell on, half a block or a whole one up; debris under an overhang rests on the ground beneath it, and
 * debris over it on its roof; a blast astride a cave's mouth is lit by sky outside and dark within. Once the debris
 * rests, part of the roof breaks, and the distance field, flooded again only around it, must match each block's
 * distance to the nearest solid octant, worked out by brute force, both there and beyond, where it kept what it had.
 * The world sits far from 0 at negative x, so the window's toroidal addressing wraps.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-world"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-world" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-world --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-world] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxWorldScene implements InteractiveSceneLifecycle {

    /** Frames the window fills before anything falls, then steps, then frames for the answers. */
    private static final int FILL = 120, STEPS = 150, DRAIN = 30;
    private static final float DT = 1f / 60f;
    /** The world: solid below GROUND, stairs on it, an overhang's roof at ROOF, and the cave's dark west of CAVE_X. */
    private static final int CX = -1000, CZ = 2000, GROUND = 64, ROOF = 70, CAVE_X = CX - 20;
    /** Per slot, its origin: over the stairs, under the roof, over the roof, astride the cave's mouth. */
    private static final double[][] ORIGIN = {{CX + 2, 67, CZ + 2}, {CX - 6, 66, CZ + 2}, {CX - 6, 74, CZ + 2},
            {CAVE_X, 66, CZ + 2}};
    private static final float[] SPREAD = {1.9f, 1.9f, 1.9f, 4f};
    private static final int PER_SLOT = 400, DRAWN_FLOATS = 16;
    /** A particle this near a block's or a half block's edge may fall either side of it: rounding, not a wrong floor. */
    private static final float EDGE = 0.02f;
    /** The frame the roof breaks: after the debris on it rests, and time enough for a refill and a flood after. */
    private static final int BREAK = FILL + STEPS - 20;
    /**
     * The distance field's checked blocks, from and to, inclusive: the second flood covers those within 8 blocks of the
     * broken roof's section, and the first those west of it. How far it may stray: its R8 step, rounded.
     */
    private static final int[] CHECKED = {CX - 24, GROUND - 6, CZ - 4, CX + 7, ROOF + 8, CZ + 8};
    private static final float DISTANCE_TOLERANCE = 0.5f * 8f / 255f + 1e-3f;

    private final World world = new World();
    private final CgHostView view = new CgHostView();
    private final List<CgRequest> requests = new ArrayList<>();
    private final CgVfxEmitter debris = CgVfxEmitter.builder("world-debris").capacity(PER_SLOT).burst(0f, 1)
            .life(100f, 100f).size(0.1f, 0.2f, 1f).module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Ground(0f, 1f, 100f)).build();
    private final int[] slots = new int[ORIGIN.length], bases = new int[ORIGIN.length], words = new int[ORIGIN.length];
    private CgVfxParticlePool pool;
    private CgVfxRange range;
    private ByteBuffer records, visible, drawn, distances;
    private int frame, live = -1, landed, onStep, onTop, underRoof, onRoof, lit, dark, distancesChecked;
    private float worstDistance;
    private String failure;
    private boolean compared, running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgPlatform.provide(CgWorldQuery.SERVICE, world);
        Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, 0.1f, -0.45f, -1f, 0f, 1f, 0f);
        view.set(CX - 8.5, 82.25, CZ + 30.5, look, new Matrix4f().perspective((float) Math.toRadians(80), 16f / 9f, 0.05f, 300f));
        CgVfxParticlePool.prepare(debris);
        CgVfxRange.prepare();
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
        CgGpuTrace.resetTotals();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= FILL + STEPS + 1) {
            CgRecording rec = new CgRecording();
            if (frame == BREAK) {
                world.roofBroken = true;
                CgGpuTrace.collect();
                CgGpuTrace.resetTotals();
                CgWorldEvents.blockBroken(CX - 5, ROOF, CZ + 1, CgWorldQuery.SURFACE_STONE, 0xFF707070);
            }
            CgVfxVoxelWindow.get().useDistance().record(rec, view);
            if (frame == FILL) seed(rec);
            if (frame > FILL) step(rec);
            if (frame == FILL + STEPS + 1) read(rec);
            CgImmediate.execute(rec);
            return;
        }
        if (!compared && records != null && visible != null && drawn != null && distances != null && live >= 0) {
            compare();
            compared = true;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (answered || frame > FILL + STEPS + DRAIN) {
            report();
            CgVfxParticlePool.release(this);
            running = false;
        }
    }

    /** Each slot's debris at rest in a box above its floor, its seed unique. */
    private void seed(CgRecording rec) {
        if (!CgVfxVoxelWindow.get().live()) fail("the window has no level after " + FILL + " frames");
        pool = CgVfxParticlePool.of(this, debris);
        Random random = new Random(1201);
        ByteBuffer packed = ByteBuffer.allocateDirect(ORIGIN.length * PER_SLOT * CgVfxRecord.BYTES).order(ByteOrder.nativeOrder());
        for (int s = 0, r = 0; s < ORIGIN.length; s++) {
            slots[s] = pool.open(debris, PER_SLOT);
            pool.cullAbout(slots[s], 8f);
            for (int i = 0; i < PER_SLOT; i++, r++) {
                float x, z;
                do {
                    x = (random.nextFloat() * 2f - 1f) * SPREAD[s];
                    z = (random.nextFloat() * 2f - 1f) * SPREAD[s];
                } while (nearEdge(ORIGIN[s][0] + x, 1.0) || nearEdge(ORIGIN[s][2] + z, 0.5));
                float y = random.nextFloat() * 2f;
                int at = r * CgVfxRecord.BYTES;
                packed.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, 0f);
                packed.putFloat(at + 16, x).putFloat(at + 20, y).putFloat(at + 24, z).putFloat(at + 28, 100f);
                packed.putFloat(at + 32, 0f).putFloat(at + 36, 0f).putFloat(at + 40, 0f).putFloat(at + 44, 0.1f);
                packed.putFloat(at + 48, (r + 0.5f) / (ORIGIN.length * PER_SLOT)).putFloat(at + 52, 0f)
                        .putFloat(at + 56, 0f).putFloat(at + 60, 0f);
                packed.putInt(at + 64, r).putInt(at + 68, slots[s]).putInt(at + 72, 0).putInt(at + 76, 0);
            }
        }
        pool.seed(rec, packed, ORIGIN.length * PER_SLOT);
    }

    private void step(CgRecording rec) {
        pool.beginStep(DT, 0f, 0f, 0f);
        for (int s = 0; s < ORIGIN.length; s++) pool.instance(slots[s], 0x5EED + s, 1f, Float.NaN, origin(ORIGIN[s]));
        pool.endStep();
        pool.record(rec);
    }

    /** Range over the settled debris, and what it and the pool hold, read back. */
    private void read(CgRecording rec) {
        range = CgVfxRange.of(pool).frame(1f, 0f);
        CgVfxRange.record(rec, view);
        int end = 0;
        for (int s = 0; s < ORIGIN.length; s++) {
            bases[s] = range.base(slots[s]);
            words[s] = range.visibleWord(slots[s]);
            end = Math.max(end, bases[s] + PER_SLOT);
        }
        int allSlots = 0;
        for (int w : words) allSlots = Math.max(allSlots, w + 1);
        requests.add(rec.readback(pool.live(), 0, 4, data -> live = data.getInt(0)));
        requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * CgVfxRecord.BYTES, data -> records = copy(data)));
        requests.add(rec.readback(range.visible(), 0, allSlots * 4L, data -> visible = copy(data)));
        requests.add(rec.readback(range.drawn(), 0, (long) end * DRAWN_FLOATS * 4, data -> drawn = copy(data)));
        requests.add(rec.readback(CgVfxVoxelWindow.get().distance(), 0, 0, 0, 0, CgVfxVoxelWindow.WIDTH,
                CgVfxVoxelWindow.HEIGHT, CgVfxVoxelWindow.DEPTH, data -> distances = copy(data)));
    }

    private void compare() {
        if (live != ORIGIN.length * PER_SLOT) {
            fail(live + " alive, not " + ORIGIN.length * PER_SLOT);
            return;
        }
        for (int r = 0; r < live; r++) {
            int s = CgVfxRecord.i(records, r, CgVfxRecord.SLOT);
            double x = ORIGIN[s][0] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION);
            double y = ORIGIN[s][1] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 1);
            double z = ORIGIN[s][2] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 2);
            double want = s == 0 ? (z - Math.floor(z) < 0.5 ? GROUND + 0.5 : GROUND + 1) : s == 2 ? ROOF + 1 : GROUND;
            boolean resting = (CgVfxRecord.i(records, r, CgVfxRecord.FLAGS) & CgVfxRecord.RESTING) != 0;
            if (!resting || Math.abs(y - want) > 1e-4) {
                fail("debris " + CgVfxRecord.i(records, r, CgVfxRecord.ID) + " of slot " + s + " is at y " + y
                        + (resting ? ", resting" : ", still falling") + "; its floor is " + want);
                return;
            }
            landed++;
            if (s == 0 && want == GROUND + 0.5) onStep++;
            if (s == 0 && want == GROUND + 1) onTop++;
            if (s == 1) underRoof++;
            if (s == 2) onRoof++;
        }
        for (int s = 0; s < ORIGIN.length; s++) {
            int count = visible.getInt(words[s] * 4);
            if (count != PER_SLOT) {
                fail("Range drew " + count + " of slot " + s + "'s " + PER_SLOT + ": each is one sphere in view");
                return;
            }
            for (int k = 0; k < count; k++) {
                int at = (bases[s] + k) * DRAWN_FLOATS * 4;
                double x = ORIGIN[s][0] + drawn.getFloat(at);
                if (nearEdge(x, 1.0)) continue;
                int bx = (int) Math.floor(x);
                int light = world.light(bx, 0, 0);
                float block = drawn.getFloat(at + 48), sky = drawn.getFloat(at + 52);
                if (block != CgWorldQuery.blockLight(light) || sky != CgWorldQuery.skyLight(light)) {
                    fail("slot " + s + "'s particle at x " + x + " is lit " + block + ", " + sky + ", not "
                            + CgWorldQuery.blockLight(light) + ", " + CgWorldQuery.skyLight(light));
                    return;
                }
                if (CgWorldQuery.skyLight(light) == 0) dark++;
                else lit++;
            }
        }
        compareDistances();
        if (onStep == 0 || onTop == 0) fail("no debris tested the stairs' " + (onStep == 0 ? "step" : "top"));
        if (dark < 50 || lit < 50) fail("the blast checked " + dark + " particles in the cave and " + lit + " outside");
    }

    /** Each checked block's distance against the nearest solid octant's, found by trying every one within reach. */
    private void compareDistances() {
        int reach = 9, x0 = CHECKED[0] - reach, y0 = CHECKED[1] - reach, z0 = CHECKED[2] - reach;
        int sx = CHECKED[3] + reach - x0 + 1, sy = CHECKED[4] + reach - y0 + 1, sz = CHECKED[5] + reach - z0 + 1;
        int[] octants = new int[sx * sy * sz];
        float[] boxes = new float[CgVfxVoxels.BOXES * 6];
        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) octants[x + sx * (y + sy * z)] = CgVfxVoxels.octants(world, x0 + x, y0 + y, z0 + z, boxes);
            }
        }
        int w = CgVfxVoxelWindow.WIDTH, h = CgVfxVoxelWindow.HEIGHT, d = CgVfxVoxelWindow.DEPTH;
        for (int bz = CHECKED[2]; bz <= CHECKED[5]; bz++) {
            for (int by = CHECKED[1]; by <= CHECKED[4]; by++) {
                for (int bx = CHECKED[0]; bx <= CHECKED[3]; bx++) {
                    double nearest = 8.0;
                    for (int nz = bz - reach; nz <= bz + reach; nz++) {
                        for (int ny = by - reach; ny <= by + reach; ny++) {
                            for (int nx = bx - reach; nx <= bx + reach; nx++) {
                                int bits = octants[(nx - x0) + sx * ((ny - y0) + sy * (nz - z0))];
                                for (int o = 0; bits != 0; o++, bits >>= 1) {
                                    if ((bits & 1) == 0) continue;
                                    double lx = nx + 0.5 * (o & 1), ly = ny + 0.5 * (o >> 1 & 1), lz = nz + 0.5 * (o >> 2 & 1);
                                    double ex = Math.max(0, Math.max(lx - (bx + 0.5), bx + 0.5 - lx - 0.5));
                                    double ey = Math.max(0, Math.max(ly - (by + 0.5), by + 0.5 - ly - 0.5));
                                    double ez = Math.max(0, Math.max(lz - (bz + 0.5), bz + 0.5 - lz - 0.5));
                                    nearest = Math.min(nearest, Math.sqrt(ex * ex + ey * ey + ez * ez));
                                }
                            }
                        }
                    }
                    int texel = Math.floorMod(bx, w) + w * (Math.floorMod(by, h) + h * Math.floorMod(bz, d));
                    float got = (distances.get(texel) & 0xFF) / 255f * 8f, off = (float) Math.abs(got - nearest);
                    worstDistance = Math.max(worstDistance, off);
                    if (off > DISTANCE_TOLERANCE) {
                        fail("block (" + bx + ", " + by + ", " + bz + ") is " + got + " from solid in the field, not " + nearest);
                        return;
                    }
                    distancesChecked++;
                }
            }
        }
    }

    /** Whether {@code v} is within {@link #EDGE} of a multiple of {@code cell}. */
    private static boolean nearEdge(double v, double cell) {
        double f = v / cell - Math.floor(v / cell);
        return f < EDGE / cell || f > 1.0 - EDGE / cell;
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
        String unanswered = null;
        for (CgRequest r : requests) {
            if (!r.done() && unanswered == null) unanswered = r + (r.failure() != null ? ": " + r.failure() : "");
        }
        if (GlErrorChecker.checkAndLog("vfx-world")) {
            System.out.println("[vfx-world] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-world] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[vfx-world] FAIL on " + on + ": " + unanswered);
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-world] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[vfx-world] FAIL on " + on + ": " + failure);
        } else {
            CgGpuTrace.collect();
            long[] flood = CgGpuTrace.totals().get("vfx.world.distance");
            System.out.printf("[vfx-world] after the roof broke, the distance field flooded %d times, %.3f ms of GPU each%n",
                    flood == null ? 0 : flood[1], flood == null || flood[1] == 0 ? 0.0 : flood[0] / 1e6 / flood[1]);
            System.out.println("[vfx-world] PASS on " + on + ": " + landed + " debris at rest on their floors (" + onStep
                    + " on a stair's step, " + onTop + " on its top, " + underRoof + " under the overhang, " + onRoof
                    + " on it); the blast lit from the window, " + dark + " in the cave dark and " + lit + " outside lit; "
                    + distancesChecked + " blocks' distance to solid within " + worstDistance);
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
     * Solid below {@link #GROUND}; a row of stairs on it, the low half toward -z; an overhang's roof a block thick at
     * {@link #ROOF}; sky light west of {@link #CAVE_X} none, and block light in stripes along x, so both bytes vary.
     */
    private static final class World implements CgWorldQuery {

        /** Whether the roof's two eastern columns are gone. */
        boolean roofBroken;

        private static boolean stairs(int x, int y, int z) {
            return y == GROUND && x >= CX && x < CX + 4 && z >= CZ && z < CZ + 4;
        }

        private boolean solid(int x, int y, int z) {
            return y < GROUND || y == ROOF && x >= CX - 8 && x < (roofBroken ? CX - 6 : CX - 4) && z >= CZ && z < CZ + 4;
        }

        @Override
        public int collisionBoxes(int x, int y, int z, float[] out) {
            if (stairs(x, y, z)) {
                put(out, 0, 0f, 0f, 0f, 1f, 0.5f, 1f);
                put(out, 1, 0f, 0.5f, 0.5f, 1f, 1f, 1f);
                return 2;
            }
            if (!solid(x, y, z)) return 0;
            put(out, 0, 0f, 0f, 0f, 1f, 1f, 1f);
            return 1;
        }

        private static void put(float[] out, int box, float x0, float y0, float z0, float x1, float y1, float z1) {
            if (out.length < box * 6 + 6) return;
            out[box * 6] = x0;
            out[box * 6 + 1] = y0;
            out[box * 6 + 2] = z0;
            out[box * 6 + 3] = x1;
            out[box * 6 + 4] = y1;
            out[box * 6 + 5] = z1;
        }

        @Override
        public float collisionTop(int x, int y, int z) {
            return stairs(x, y, z) || solid(x, y, z) ? 1f : Float.NaN;
        }

        @Override
        public float collisionBottom(int x, int y, int z) {
            return stairs(x, y, z) || solid(x, y, z) ? 0f : Float.NaN;
        }

        @Override
        public int light(int x, int y, int z) {
            return Math.floorMod(x, 4) * 4 | (x >= CAVE_X ? 15 : 0) << 4;
        }

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
