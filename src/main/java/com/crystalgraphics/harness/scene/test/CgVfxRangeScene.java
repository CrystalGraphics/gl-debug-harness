package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.element.CgVfxExplosion;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxCurveDomain;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for Range (plan vfx-gpu §13.4): a pool's particles culled against a view, grouped by slot and written as the
 * records a look reads, checked against the same cull and {@code CgVfxSystem.writeRecords}' maths in Java; and as the
 * object records a mesh per particle reads, against {@code CgVfxFrame.particleMeshes}' transform. A depth pyramid
 * holds a wall over the left half of the view, so what hides behind it is culled too, and slot 0 sorts far to near. Four slots,
 * one closed between them; one in front of the camera, one across the frustum's edge, one behind it. The pool steps once,
 * so the records Range reads are the Step kernel's; the reference is worked from those records, read back. A pool of
 * another shape ranges ahead of it, so its slots, keys and records sit past that pool's in the shared buffers. Slot 0's
 * definition samples its curves by speed, the others' over life, so one pool holds rows of both domains.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-range"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-range" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-range --device=vulkan" -Dcrystalgraphics.vulkan.syncValidation=true
 * # Range's GPU time, its vfx.pool.range zone over 300 frames, at 25 times the particles (about 100,000)
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-range" -Dcrystalgraphics.harness.vfxRange.timed=300  *     -Dcrystalgraphics.harness.vfxRange.scale=25
 * }</pre>
 *
 * <p>Prints {@code [vfx-range] PASS} or {@code FAIL}, then {@code [vfx-range] cost} when timed; on Vulkan a validation
 * error fails it.</p>
 */
public class CgVfxRangeScene implements InteractiveSceneLifecycle {

    private static final int DRAIN = 30;
    private static final int TIMED = Integer.getInteger("crystalgraphics.harness.vfxRange.timed", 0);
    private static final int SCALE = Integer.getInteger("crystalgraphics.harness.vfxRange.scale", 1);
    /** What the timed frames take on besides the frustum: {@code sort} (slot 0's view depth), {@code pyramid}. */
    private static final String TIMED_WITH = System.getProperty("crystalgraphics.harness.vfxRange.timedWith", "");
    private static final String RANGE_ZONE = "vfx.pool.range";
    private static final float DT = 1f / 60f, ALPHA = 0.37f, AHEAD = ALPHA * DT;
    private static final double CAMERA_X = 105.5, CAMERA_Y = 64.25, CAMERA_Z = -30.75;
    /** Per slot: capacity, particles seeded, origin from the camera, spread; slot 1 closes before the step. */
    private static final int[] CAPACITY = {2000, 500, 1500, 800}, SEEDED = {1800, 0, 1400, 700};
    private static final float[][] ORIGIN = {{0f, 0f, -20f}, {0f, 0f, -5f}, {0f, 0f, 15f}, {-16f, 2f, -15f}};
    private static final float[] SPREAD = {12f, 1f, 5f, 7f};
    /** Words of a drawn record: place, motion, state, light. */
    private static final int DRAWN_FLOATS = 16, OBJECT_FLOATS = 48;
    /** Within this share of a plane's reach, a particle may fall either side: float rounding, not a wrong cull. */
    private static final float BORDER = 1e-4f;
    /** The pyramid: its size (a power of two, so each texel folds a square of the one below), and the wall's depth. */
    private static final int PYRAMID = 64;
    private static final float WALL = 25f, FAR = 1e9f;
    /** The slot that sorts far to near; the slot culled as one sphere about its source, and that sphere's radius. */
    private static final int SORTED = 0, SOURCE = 3;
    private static final float SOURCE_REACH = 3f;
    /** The pool ranged ahead: another shape, a capacity no multiple of Range's alignment, and its particles seeded. */
    private static final CgVfxEmitter LEAD = CgVfxExplosion.INK;
    private static final int LEAD_CAPACITY = 901, LEAD_SEEDED = 700;
    /** The slot whose curves go by speed, and its domain: about the middle of the seeded speeds. */
    private static final int BY_SPEED = 0;
    private static final CgVfxCurveDomain SPEEDS = CgVfxCurveDomain.speed(1.5f, 4f);

    private final CgVfxEmitter emitter = CgVfxExplosion.SPARKLES;
    private final CgVfxGpuEmitter bySpeed = new BySpeed(emitter);
    private final CgHostView view = new CgHostView();
    private final Matrix4f viewProjection = new Matrix4f();
    private final float[][] planes = new float[6][4];
    private final float[][] pyramidLevels = new float[7][];
    private final Matrix4f projection = new Matrix4f();
    private final float[] curves = new float[2 * CgVfxGpuEmitter.CURVE_TEXELS];
    private final List<CgRequest> requests = new ArrayList<>();
    private CgVfxParticlePool pool, lead;
    private CgVfxRange range;
    private int slotCount, slotFirst, allSlots, live = -1, leadLive = -1;
    private int[] bases;
    private float[] radius;
    private ByteBuffer records, visible, drawn, objects;
    private final Matrix4f model = new Matrix4f(), turned = new Matrix4f(), normal = new Matrix4f();
    private int frame, checkedDrawn, culled, occluded, sourceKept, timedFrom = -1, slowest, between, fastest;
    private float worst, worstRow, worstObject;
    private String failure;
    private boolean compared, running = true;

    @Override
    public void init(HarnessContext ctx) {
        Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, -0.2f, -0.1f, -1f, 0f, 1f, 0f);
        projection.perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 300f);
        view.set(CAMERA_X, CAMERA_Y, CAMERA_Z, look, projection);
        viewProjection.set(projection).mul(look);
        Vector4f plane = new Vector4f();
        for (int p = 0; p < 6; p++) {
            viewProjection.frustumPlane(p, plane);
            planes[p][0] = plane.x;
            planes[p][1] = plane.y;
            planes[p][2] = plane.z;
            planes[p][3] = plane.w;
        }
        emitter.writeCurves(curves, 0, CgVfxGpuEmitter.CURVE_TEXELS);
        // The wall over the left half of level 0; each level above the farthest of the square it folds.
        for (int l = 0, size = PYRAMID; l < pyramidLevels.length; l++, size >>= 1) {
            float[] level = pyramidLevels[l] = new float[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) level[y * size + x] = ((x + 1) << l) <= PYRAMID / 2 ? WALL : FAR;
            }
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame == 1) {
            record();
            return;
        }
        if (!compared && records != null && visible != null && drawn != null && objects != null && live >= 0 && leadLive >= 0) {
            compare();
            compared = true;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (timedFrom < 0 && (answered || frame > DRAIN)) {
            report();
            if (TIMED <= 0) {
                finish();
                return;
            }
            timedFrom = frame;
            CgTrace.setEnabled(CgGpuTrace.GPU, true);
            CgGpuTrace.resetTotals();
        }
        if (timedFrom >= 0) time();
    }

    /** Range alone each frame, inside its own GPU zone, until {@link #TIMED} of them have landed. */
    private void time() {
        CgGpuTrace.collect();
        long[] t = CgGpuTrace.totals().get(RANGE_ZONE);
        if ((t != null && t[1] >= TIMED) || frame > timedFrom + TIMED + DRAIN) {
            System.out.printf("[vfx-range] cost at %s: %d alive, %d frames, gpu %.3f ms a view%n",
                    CgCapabilities.detect().computeTier(), live, t == null ? 0 : t[1], t == null || t[1] == 0 ? 0.0 : t[0] / 1e6 / t[1]);
            finish();
            return;
        }
        if (frame < timedFrom + TIMED) {
            CgRecording rec = new CgRecording();
            if (TIMED_WITH.contains("sort")) range.sorted(SORTED);
            CgVfxRange.record(rec, view, TIMED_WITH.contains("pyramid") ? pyramid(rec) : null);
            CgImmediate.execute(rec);
        }
    }

    private void finish() {
        CgVfxParticlePool.release(this);
        running = false;
    }

    private void record() {
        CgVfxParticlePool.prepare(emitter);
        CgVfxParticlePool.prepare(bySpeed);
        CgVfxParticlePool.prepare(LEAD);
        CgVfxRange.prepare();
        CgRecording rec = new CgRecording();
        Random random = new Random(1201);
        lead = CgVfxParticlePool.of(this, LEAD);
        int leadSlot = lead.open(LEAD, LEAD_CAPACITY * SCALE), leadTotal = LEAD_SEEDED * SCALE;
        ByteBuffer leadPacked = ByteBuffer.allocateDirect(leadTotal * CgVfxRecord.BYTES).order(ByteOrder.nativeOrder());
        for (int r = 0; r < leadTotal; r++) seedOne(leadPacked, r, leadTotal, leadSlot, random);
        lead.seed(rec, leadPacked, leadTotal);
        lead.beginStep(DT, 0.4f, 0f, 0.2f);
        lead.instance(leadSlot, 0x1EAD, 1f, Float.NaN, origin(ORIGIN[0]));
        lead.endStep();
        lead.record(rec);
        CgVfxRange.of(lead).frame(ALPHA, AHEAD);

        pool = CgVfxParticlePool.of(this, emitter);
        if (pool == lead) fail("the lead pool is the checked one: give it another shape");
        int[] slots = new int[CAPACITY.length];
        for (int s = 0; s < CAPACITY.length; s++) slots[s] = pool.open(s == BY_SPEED ? bySpeed : emitter, CAPACITY[s] * SCALE);
        pool.close(slots[1]);
        pool.cullAbout(slots[SOURCE], SOURCE_REACH);
        slotCount = pool.slotCount();

        int total = 0;
        for (int n : SEEDED) total += n * SCALE;
        ByteBuffer packed = ByteBuffer.allocateDirect(total * CgVfxRecord.BYTES).order(ByteOrder.nativeOrder());
        for (int s = 0, r = 0; s < CAPACITY.length; s++) {
            for (int i = 0; i < SEEDED[s] * SCALE; i++, r++) seedOne(packed, r, total, slots[s], random);
        }
        pool.seed(rec, packed, total);

        pool.beginStep(DT, 0.4f, 0f, 0.2f);
        for (int s = 0; s < CAPACITY.length; s++) {
            if (!pool.isOpen(slots[s])) continue;
            pool.instance(slots[s], 0x5EED + s, 1f, Float.NaN, origin(ORIGIN[s]));
        }
        pool.endStep();
        pool.record(rec);

        CgGraphTexture pyramid = pyramid(rec);
        range = CgVfxRange.of(pool).frame(ALPHA, AHEAD).sorted(SORTED);
        range.objects();
        CgVfxRange.record(rec, view, pyramid);
        slotFirst = range.visibleWord(0);
        allSlots = lead.slotCount() + slotCount;
        bases = new int[slotCount];
        radius = new float[slotCount];
        for (int s = 0; s < slotCount; s++) {
            if (!pool.isOpen(s)) continue;
            bases[s] = range.base(s);
            radius[s] = pool.cullRadius(s);
        }
        // Slot 0 is open and first in its pool's list: its base is where the pool's records start.
        int storage = pool.storage(), end = range.base(0) + pool.capacity();
        requests.add(rec.readback(pool.live(), 0, 4, data -> live = data.getInt(0)));
        requests.add(rec.readback(lead.live(), 0, 4, data -> leadLive = data.getInt(0)));
        requests.add(rec.readback(pool.records(), 0, (long) storage * CgVfxRecord.BYTES, data -> records = copy(data)));
        requests.add(rec.readback(range.visible(), 0, (allSlots + 1) * 4L, data -> visible = copy(data)));
        requests.add(rec.readback(range.drawn(), 0, (long) end * DRAWN_FLOATS * 4, data -> drawn = copy(data)));
        requests.add(rec.readback(range.objects(), 0, (long) end * OBJECT_FLOATS * 4, data -> objects = copy(data)));
        CgImmediate.execute(rec);
    }

    /** A particle near its slot's origin, moving, part way through its life; its seed unique among them all. */
    private static void seedOne(ByteBuffer out, int r, int total, int slot, Random random) {
        int at = r * CgVfxRecord.BYTES;
        float spread = SPREAD[slot];   // slots open in order on a fresh pool: a slot is its index here
        float x = spread(random, spread), y = spread(random, spread), z = spread(random, spread);
        float vx = spread(random, 3f), vy = spread(random, 3f), vz = spread(random, 3f);
        float life = 0.5f + random.nextFloat() * 2f;
        out.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, random.nextFloat() * life * 0.98f);
        out.putFloat(at + 16, x - vx * DT).putFloat(at + 20, y - vy * DT).putFloat(at + 24, z - vz * DT).putFloat(at + 28, life);
        out.putFloat(at + 32, vx).putFloat(at + 36, vy).putFloat(at + 40, vz).putFloat(at + 44, 0.05f + random.nextFloat() * 0.5f);
        out.putFloat(at + 48, (r + 0.5f) / total).putFloat(at + 52, random.nextFloat() * 6.28f)
                .putFloat(at + 56, spread(random, 4f)).putFloat(at + 60, random.nextFloat());
        out.putInt(at + 64, r).putInt(at + 68, slot).putInt(at + 72, 0).putInt(at + 76, 0);
    }

    /** The wall's pyramid, uploaded into {@code rec}. */
    private CgGraphTexture pyramid(CgRecording rec) {
        CgGraphTexture pyramid = CgGraphTexture.transientTexture("vfx-range.pyramid",
                new CgTextureDesc(PYRAMID, PYRAMID, CgGpuOps.PYRAMID_FORMAT).withMips());
        for (int l = 0, size = PYRAMID; l < pyramidLevels.length; l++, size >>= 1) {
            ByteBuffer level = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
            for (float depth : pyramidLevels[l]) level.putFloat(depth);
            rec.update(pyramid, l, 0, 0, size, size, level.flip());
        }
        return pyramid;
    }

    private CgVfxInstanceView origin(float[] offset) {
        return new CgVfxInstanceView() {
            public double originX() { return CAMERA_X + offset[0]; }
            public double originY() { return CAMERA_Y + offset[1]; }
            public double originZ() { return CAMERA_Z + offset[2]; }
            public float time() { return 0.5f; }
            public float sourceX() { return 0f; }
            public float sourceY() { return 0f; }
            public float sourceZ() { return 0f; }
        };
    }

    private void compare() {
        // Each slot's particles by seed, and which of them a cull must keep, may keep, or must drop.
        List<Map<Integer, Integer>> bySeed = new ArrayList<>();
        int[] inside = new int[slotCount], border = new int[slotCount];
        boolean[] mayDraw = new boolean[live], mustDraw = new boolean[live], drawnOnce = new boolean[live];
        for (int s = 0; s < slotCount; s++) bySeed.add(new HashMap<>());
        for (int r = 0; r < live; r++) {
            int slot = CgVfxRecord.i(records, r, CgVfxRecord.SLOT);
            if (slot < 0 || slot >= slotCount || !pool.isOpen(slot)) {
                fail("record " + r + " names slot " + slot);
                return;
            }
            bySeed.get(slot).put(Float.floatToRawIntBits(CgVfxRecord.f(records, r, CgVfxRecord.SEED)), r);
            int cull = cull(r, slot);
            mustDraw[r] = cull > 0;
            mayDraw[r] = cull >= 0;
            if (cull > 0) inside[slot]++;
            else if (cull == 0) border[slot]++;
        }
        // The last word counts dead records too: every pool's storage is keyed, and no draw reads that word.
        int leadSum = 0, sum = 0;
        for (int s = 0; s < slotFirst; s++) leadSum += visible.getInt(s * 4);
        for (int s = 0; s < slotCount; s++) sum += visible.getInt((slotFirst + s) * 4);
        if (leadSum > leadLive) fail("the lead pool has " + leadSum + " visible of " + leadLive + " alive");
        if (sum > live) fail("the visible counts sum to " + sum + ", past the " + live + " alive");
        if (slotFirst != lead.slotCount()) fail("the checked pool's slots start at " + slotFirst + ", not " + lead.slotCount());
        culled = live - sum;
        for (int s = 0; s < slotCount; s++) {
            int count = visible.getInt((slotFirst + s) * 4);
            if (!pool.isOpen(s)) {
                if (count != 0) fail("closed slot " + s + " has " + count + " visible");
                continue;
            }
            if (s == SOURCE && count != 0 && count != bySeed.get(s).size()) {
                fail("slot " + s + ", culled about its source, has " + count + " of " + bySeed.get(s).size() + " visible");
            }
            if (count < inside[s] || count > inside[s] + border[s]) {
                fail("slot " + s + " has " + count + " visible, not " + inside[s] + " to " + (inside[s] + border[s]));
            }
            int capacity = pool.capacity(s), lastDepth = Integer.MAX_VALUE;
            for (int k = 0; k < capacity; k++) {
                int d = bases[s] + k;
                if (k >= count) {
                    checkEmpty(d, s, k);
                    continue;
                }
                Integer r = bySeed.get(s).get(Float.floatToRawIntBits(drawn.getFloat(d * 64 + 32)));
                if (r == null) {
                    fail("slot " + s + "'s drawn record " + k + " has seed " + drawn.getFloat(d * 64 + 32) + ", no particle's");
                    return;
                }
                if (drawnOnce[r]) fail("slot " + s + " draws particle " + r + " twice");
                if (!mayDraw[r]) fail("slot " + s + " draws particle " + r + ", which is outside the view");
                drawnOnce[r] = true;
                checkPlaced(d, r);
                if (s == BY_SPEED) {
                    float u = curveAt(r, 0f);
                    if (u == 0f) slowest++;
                    else if (u == 1f) fastest++;
                    else between++;
                }
                checkObject(d, r);
                if (s == SORTED) {
                    int depth = depthKey(r, s);
                    if (k > 0 && depth > lastDepth + 1) fail("slot " + s + " draws particle " + r + " at depth " + depth
                            + " after one at " + lastDepth + ": not far to near");
                    lastDepth = depth;
                }
                checkedDrawn++;
            }
        }
        for (int r = 0; r < live; r++) {
            if (mustDraw[r] && !drawnOnce[r]) fail("particle " + r + " is inside the view and not drawn");
        }
    }

    /** 1 inside the view, -1 outside, 0 within rounding of a plane: Range's test, in Java. */
    private int cull(int r, int slot) {
        float cx = ORIGIN[slot][0] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION);
        float cy = ORIGIN[slot][1] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 1);
        float cz = ORIGIN[slot][2] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 2);
        float dx = CgVfxRecord.f(records, r, CgVfxRecord.POSITION) - CgVfxRecord.f(records, r, CgVfxRecord.PREVIOUS);
        float dy = CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 1) - CgVfxRecord.f(records, r, CgVfxRecord.PREVIOUS + 1);
        float dz = CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 2) - CgVfxRecord.f(records, r, CgVfxRecord.PREVIOUS + 2);
        float reach = CgVfxRecord.f(records, r, CgVfxRecord.SIZE) * radius[slot] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (slot == SOURCE) {
            // Kept by its source though outside the view itself: what tells the source's cull from each particle's.
            for (float[] p : planes) {
                if (p[0] * cx + p[1] * cy + p[2] * cz + p[3] + reach < -BORDER * 100f) {
                    sourceKept++;
                    break;
                }
            }
            cx = ORIGIN[slot][0];
            cy = ORIGIN[slot][1];
            cz = ORIGIN[slot][2];
            reach = SOURCE_REACH;
        }
        int result = 1;
        for (float[] p : planes) {
            float margin = p[0] * cx + p[1] * cy + p[2] * cz + p[3] + reach;
            float edge = BORDER * (1f + Math.abs(cx) + Math.abs(cy) + Math.abs(cz) + Math.abs(p[3]));
            if (margin < -edge) return -1;
            if (margin < edge) result = 0;
        }
        // Behind the wall: either way within rounding of its depth or of a texel's edge.
        float grown = reach * (1f + BORDER) + BORDER, shrunk = reach * (1f - BORDER) - BORDER;
        boolean hidden = occluded(cx, cy, cz, grown), hiddenSmaller = occluded(cx, cy, cz, Math.max(shrunk, 0f));
        if (hidden && hiddenSmaller) {
            occluded++;
            return -1;
        }
        if (hidden != hiddenSmaller) result = 0;
        return result;
    }

    /** {@code lib/occlusion.glsl}'s cg_occluded over this scene's pyramid, for a cube of half-size {@code reach}. */
    private boolean occluded(float cx, float cy, float cz, float reach) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, nearest = Float.MAX_VALUE;
        Matrix4f vp = viewProjection;
        for (int c = 0; c < 8; c++) {
            float px = (c & 1) == 0 ? cx - reach : cx + reach, py = (c & 2) == 0 ? cy - reach : cy + reach;
            float pz = (c & 4) == 0 ? cz - reach : cz + reach;
            float w = vp.m03() * px + vp.m13() * py + vp.m23() * pz + vp.m33();
            if (w < 0.01f) return false;
            float nx = (vp.m00() * px + vp.m10() * py + vp.m20() * pz + vp.m30()) / w;
            float ny = (vp.m01() * px + vp.m11() * py + vp.m21() * pz + vp.m31()) / w;
            float nz = (vp.m02() * px + vp.m12() * py + vp.m22() * pz + vp.m32()) / w;
            x0 = Math.min(x0, nx);
            x1 = Math.max(x1, nx);
            y0 = Math.min(y0, ny);
            y1 = Math.max(y1, ny);
            nearest = Math.min(nearest, eyeDepth(nz));
        }
        int tx0 = texel(x0), tx1 = texel(x1), ty0 = texel(y0), ty1 = texel(y1), level = 0;
        while (level < pyramidLevels.length - 1 && ((tx1 >> level) - (tx0 >> level) > 1 || (ty1 >> level) - (ty0 >> level) > 1)) level++;
        int size = PYRAMID >> level, last = size - 1;
        int ax = Math.min(tx0 >> level, last), bx = Math.min(tx1 >> level, last);
        int ay = Math.min(ty0 >> level, last), by = Math.min(ty1 >> level, last);
        float[] l = pyramidLevels[level];
        float farthest = Math.max(Math.max(l[ay * size + ax], l[ay * size + bx]), Math.max(l[by * size + ax], l[by * size + bx]));
        return nearest > farthest;
    }

    private float eyeDepth(float ndc) {
        float e0 = projection.m22(), e1 = projection.m23(), e2 = projection.m32(), e3 = projection.m33();
        if (e1 == 0f) return (e2 - ndc) / e0;
        float p22 = -e0 / e1, p32 = e2 + p22 * e3;
        return p32 / (ndc + p22);
    }

    private static int texel(float ndc) {
        return (int) Math.max(0f, Math.min((float) Math.floor((ndc * 0.5f + 0.5f) * PYRAMID), PYRAMID - 1));
    }

    /** Range's view depth key: 16 bits, logarithmic from 1/64 to 1024 blocks. */
    private int depthKey(int r, int slot) {
        float cx = ORIGIN[slot][0] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION);
        float cy = ORIGIN[slot][1] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 1);
        float cz = ORIGIN[slot][2] + CgVfxRecord.f(records, r, CgVfxRecord.POSITION + 2);
        Matrix4f vp = viewProjection;
        float w = Math.max(vp.m03() * cx + vp.m13() * cy + vp.m23() * cz + vp.m33(), 1f / 64f);
        float log = (float) (Math.log(w) / Math.log(2));
        return (int) (Math.max(0f, Math.min((log + 6f) / 16f, 1f)) * 65535f);
    }

    /** Drawn record {@code d} against particle {@code r} as {@code CgVfxSystem.writeRecords} writes it. */
    private void checkPlaced(int d, int r) {
        float age = CgVfxRecord.f(records, r, CgVfxRecord.AGE), life = CgVfxRecord.f(records, r, CgVfxRecord.LIFE);
        float t = Math.min(age / life, 1f), u = curveAt(r, t);
        float size = curve(u, 0), opacity = curve(u, 1);
        worstRow = Math.max(worstRow, Math.max(Math.abs(size - emitter.sizeAt(u)), Math.abs(opacity - emitter.opacityAt(u))));
        float[] want = new float[DRAWN_FLOATS];
        for (int a = 0; a < 3; a++) {
            float now = CgVfxRecord.f(records, r, CgVfxRecord.POSITION + a), was = CgVfxRecord.f(records, r, CgVfxRecord.PREVIOUS + a);
            want[a] = was + (now - was) * ALPHA;
            want[4 + a] = CgVfxRecord.f(records, r, CgVfxRecord.VELOCITY + a);
        }
        want[3] = CgVfxRecord.f(records, r, CgVfxRecord.SIZE) * size;
        want[7] = t;
        want[8] = CgVfxRecord.f(records, r, CgVfxRecord.SEED);
        want[9] = CgVfxRecord.f(records, r, CgVfxRecord.SPIN) + CgVfxRecord.f(records, r, CgVfxRecord.SPIN_RATE) * AHEAD;
        want[10] = CgVfxRecord.f(records, r, CgVfxRecord.HEAT);
        want[11] = opacity;
        want[12] = 15f;
        want[13] = 15f;
        for (int w = 0; w < DRAWN_FLOATS; w++) {
            float got = drawn.getFloat(d * 64 + w * 4), off = Math.abs(got - want[w]) / (1e-5f * (1f + Math.abs(want[w])));
            worst = Math.max(worst, off);
            if (!(off <= 1f)) fail("particle " + r + " drawn word " + w + " is " + got + ", not " + want[w]);
        }
    }

    /** Object record {@code d} against particle {@code r} as {@code CgVfxFrame.particleMeshes} places its mesh. */
    private void checkObject(int d, int r) {
        float age = CgVfxRecord.f(records, r, CgVfxRecord.AGE), life = CgVfxRecord.f(records, r, CgVfxRecord.LIFE);
        float t = Math.min(age / life, 1f), seed = CgVfxRecord.f(records, r, CgVfxRecord.SEED);
        float turn = seed * 6.2831853f + CgVfxRecord.f(records, r, CgVfxRecord.SPIN);
        float[] at = new float[3];
        for (int a = 0; a < 3; a++) {
            float now = CgVfxRecord.f(records, r, CgVfxRecord.POSITION + a), was = CgVfxRecord.f(records, r, CgVfxRecord.PREVIOUS + a);
            at[a] = was + (now - was) * ALPHA;
        }
        float u = curveAt(r, t), size = CgVfxRecord.f(records, r, CgVfxRecord.SIZE) * curve(u, 0);
        turned.rotationXYZ(turn * 1.7f, turn * 2.3f, turn).scale(size);
        model.translation(at[0], at[1], at[2]).mul(turned).normal(normal);
        float[] want = new float[OBJECT_FLOATS];
        model.get(want, 0);
        normal.get(want, 16);
        want[28] = 15f;
        want[29] = 15f;
        want[30] = 0f;
        want[31] = 1f;
        want[36] = t;
        want[37] = seed;
        want[38] = curve(u, 1);
        want[39] = CgVfxRecord.f(records, r, CgVfxRecord.HEAT);
        for (int w = 0; w < OBJECT_FLOATS; w++) {
            float got = objects.getFloat(d * OBJECT_FLOATS * 4 + w * 4), off;
            boolean rotation = w % 4 != 3 && (w < 12 || (w >= 16 && w < 28));
            if (rotation) {
                // The rotation alone: JOML takes each cosine as sqrt(1 - sin^2) in float, about 1e-3 off near a
                // quarter turn, where the GPU's cos is exact to rounding.
                float unit = w < 12 ? 1f / size : size;
                off = Math.abs(got - want[w]) * unit / 2e-3f;
            } else {
                off = Math.abs(got - want[w]) / (1e-5f * (1f + Math.abs(want[w])));
            }
            worstObject = Math.max(worstObject, off);
            if (!(off <= 1f)) fail("particle " + r + " object word " + w + " is " + got + ", not " + want[w]);
        }
    }

    /** A slot's record past its visible count: nothing, fully lit. */
    private void checkEmpty(int d, int s, int k) {
        for (int w = 0; w < DRAWN_FLOATS; w++) {
            float want = w == 12 || w == 13 ? 15f : 0f;
            if (drawn.getFloat(d * 64 + w * 4) != want) {
                fail("slot " + s + "'s unused record " + k + " word " + w + " is " + drawn.getFloat(d * 64 + w * 4));
                return;
            }
        }
    }

    /** Where particle {@code r} samples its curves: by speed in slot {@link #BY_SPEED}, else its life progress {@code t}. */
    private float curveAt(int r, float t) {
        if (CgVfxRecord.i(records, r, CgVfxRecord.SLOT) != BY_SPEED) return t;
        return SPEEDS.at(0f, 1f, CgVfxRecord.f(records, r, CgVfxRecord.VELOCITY),
                CgVfxRecord.f(records, r, CgVfxRecord.VELOCITY + 1), CgVfxRecord.f(records, r, CgVfxRecord.VELOCITY + 2));
    }

    /** The curve row at progress {@code t}, between its two nearest samples, as Place reads it. */
    private float curve(float t, int lane) {
        int texels = CgVfxGpuEmitter.CURVE_TEXELS;
        float x = Math.max(0f, Math.min(t, 1f)) * (texels - 1);
        int i0 = (int) Math.floor(x), i1 = Math.min(i0 + 1, texels - 1);
        float f = x - i0, a = curves[2 * i0 + lane], b = curves[2 * i1 + lane];
        return a * (1f - f) + b * f;
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
        out.put(data.duplicate()).flip();
        return out;
    }

    private static float spread(Random random, float half) {
        return (random.nextFloat() * 2f - 1f) * half;
    }

    private void fail(String what) {
        if (failure == null) failure = what;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        int validation = PlatformServiceHarness.validationErrors();
        int done = 0;
        String unanswered = null;
        for (CgRequest r : requests) {
            if (r.done()) done++;
            else if (unanswered == null) unanswered = r + (r.failure() != null ? ": " + r.failure() : "");
        }
        if (failure == null && compared && checkedDrawn == 0) failure = "no particle was drawn: it checked nothing";
        if (failure == null && compared && culled == 0) failure = "no particle was culled: the cull went unchecked";
        if (failure == null && compared && (slowest == 0 || between == 0 || fastest == 0)) {
            failure = "slot " + BY_SPEED + " drew " + slowest + " below its speeds, " + between + " between and " + fastest
                    + " above: each is the test";
        }
        if (failure == null && compared && occluded == 0) failure = "no particle hid behind the wall: occlusion went unchecked";
        if (failure == null && compared && sourceKept == 0) {
            failure = "no particle of slot " + SOURCE + " lies outside the view: its source's cull went unchecked";
        }
        if (GlErrorChecker.checkAndLog("vfx-range")) {
            System.out.println("[vfx-range] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-range] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[vfx-range] FAIL on " + on + ": " + done + " of " + requests.size() + " answered; " + unanswered);
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-range] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[vfx-range] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-range] PASS on " + on + ": " + live + " alive in " + slotCount + " slots after "
                    + leadLive + " in another pool's, from slot " + slotFirst + " and record " + bases[0] + ", " + checkedDrawn
                    + " drawn and checked, " + culled + " culled (" + occluded + " behind the wall), slot " + SORTED
                    + " far to near, slot " + SOURCE + " culled about its source (" + sourceKept + " outside the view kept)"
                    + "; worst share of the tolerance " + worst
                    + ", objects " + worstObject + "; the curve row strays from the curves by at most " + worstRow
                    + "; slot " + BY_SPEED + "'s curves by speed (" + slowest + " below, " + between + " between, " + fastest + " above)");
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** The checked emitter with its curves by speed: another row of the same pool. */
    private record BySpeed(CgVfxEmitter of) implements CgVfxGpuEmitter {
        public String name() { return of.name() + "-by-speed"; }
        public CgVfxEmitter.Renderer renderer() { return of.renderer(); }
        public List<? extends CgVfxGpuModule> modules() { return of.modules(); }
        public void writeSpawn(CgVfxWords out) { of.writeSpawn(out); }
        public void writeCurves(float[] out, int at, int texels) { of.writeCurves(out, at, texels); }
        public CgVfxCurveDomain curveDomain() { return SPEEDS; }
    }
}
