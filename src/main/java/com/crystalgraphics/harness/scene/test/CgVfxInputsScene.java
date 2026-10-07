package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
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
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for what a module kind samples besides the voxel window (plan vfx-gpu X6): a vector field, a heightfield
 * and the scene's depth. Three pools, each a kind given as GLSL text: a 3D texture of velocities sets each particle's
 * velocity where it stood (Unity's Vector Field Force, in its velocity mode); a 2D texture of heights stops particles
 * falling onto it (Godot's heightfield collider); and particles thrown at a wall and dropped onto a floor collide with a
 * depth pyramid of them (Niagara's and Unity's depth buffer collision), their impacts reported with the surface's normal
 * as the depth reconstructs it. The camera is turned and the pools' origins are not the camera's, so every transform
 * between them is crossed. Each is checked against the same sums in Java.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-inputs"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-inputs" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-inputs --device=vulkan" -Dcrystalgraphics.vulkan.syncValidation=true
 * }</pre>
 *
 * <p>Prints {@code [vfx-inputs] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxInputsScene implements InteractiveSceneLifecycle {

    private static final int STEPS = 90, DRAIN = 40, PER_POOL = 600;
    private static final float DT = 1f / 60f;
    /** The field's and the heightfield's texels, and the box both span from -BOX to BOX about their origins. */
    private static final int FIELD = 8, HEIGHTS = 16;
    private static final float BOX = 4f, FALL = 2f;
    /** Filtering's weights are 8-bit on some GPUs: what a sample may stray by. */
    private static final float FILTERED = 0.03f;
    /** The depth: its size, the wall's eye depth over its left half, the floor below the camera over its right. */
    private static final int DEPTH = 128;
    private static final float WALL = 12f, FLOOR = -2f, FAR = 1e9f, THICKNESS = 3f, YAW = 0.3f;
    private static final double[] CAMERA = {100.5, 70.25, -40.75}, DEPTH_ORIGIN = {100.8, 69.65, -40.3},
            ORIGIN = {-300.25, 64.5, 900.75};

    private final CgVfxEmitter base = CgVfxEmitter.builder("inputs").capacity(PER_POOL).burst(0f, 1)
            .life(100f, 100f).size(0.1f, 0.2f, 1f).build();
    private final float[] field = new float[FIELD * FIELD * FIELD * 4], heights = new float[HEIGHTS * HEIGHTS];
    private final float[] depths = new float[DEPTH * DEPTH];
    private final Matrix4f view = new Matrix4f().rotationY(YAW), inverse = new Matrix4f().rotationY(-YAW);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 1f, 0.05f, 300f);
    private final CgHostView host = new CgHostView();
    private CgTexture3D fieldTexture;
    private CgTexture2D heightTexture;
    private CgVfxGpuEmitter fielded, heightened, depthed;
    private final List<CgRequest> requests = new ArrayList<>();
    private final ByteBuffer[] records = new ByteBuffer[3];
    private final int[] live = {-1, -1, -1};
    private final CgVfxParticlePool[] pools = new CgVfxParticlePool[3];
    private final int[] slots = new int[3];
    /** Each depth particle's collisions heard: (x, y, z, nx, ny, nz) of its last, and how many. */
    private final Map<Integer, double[]> hits = new HashMap<>();
    private final Map<Integer, Integer> hitCounts = new HashMap<>();
    private final CgVfxEventListener listener = this::heard;
    private int frame, fieldChecked, clamped, falling, wallHits, floorHits;
    private float worstField, worstHeight, worstNormal;
    private String failure;
    private boolean compared, running = true;

    @Override
    public void init(HarnessContext ctx) {
        for (int k = 0, at = 0; k < FIELD; k++) {
            for (int j = 0; j < FIELD; j++) {
                for (int i = 0; i < FIELD; i++, at += 4) {
                    field[at] = 2f * (float) Math.sin(0.9 * i + 0.4 * k);
                    field[at + 1] = 2f * (float) Math.cos(0.7 * j);
                    field[at + 2] = 2f * (float) Math.sin(0.5 * k - 0.3 * i);
                    field[at + 3] = 0f;
                }
            }
        }
        for (int j = 0; j < HEIGHTS; j++) {
            for (int i = 0; i < HEIGHTS; i++) heights[j * HEIGHTS + i] = 0.75f * (float) (Math.sin(0.7 * i) * Math.cos(0.5 * j));
        }
        fieldTexture = CgTexture3D.createEmpty(FIELD, FIELD, FIELD, CgTextureType.RGBA32F.toTextureSpec());
        fieldTexture.uploadRegion(0, 0, 0, 0, FIELD, FIELD, FIELD, floats(field), CgGL.GL_RGBA, CgGL.GL_FLOAT);
        heightTexture = CgTexture2D.createEmpty(HEIGHTS, HEIGHTS, CgTextureType.R32F.toTextureSpec());
        heightTexture.uploadRegion(0, 0, 0, HEIGHTS, HEIGHTS, floats(heights), CgGL.GL_RED, CgGL.GL_FLOAT);
        fielded = new Inputs(base, List.of(new Kind("gate_field", false, CgVfxWorldInput.NONE, new CgTexture[]{fieldTexture}, -BOX, -BOX, -BOX, 1f / (2f * BOX), """
                void fx_gate_field(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, sampler3D field) {
                    p.velocity = texture(field, (p.position - m.xyz) * m.w).xyz;
                }
                """)), List.of());
        heightened = new Inputs(base, List.of(new Kind("gate_height", true, CgVfxWorldInput.NONE, new CgTexture[]{heightTexture}, -BOX, -BOX, 1f / (2f * BOX), 0f, """
                void fx_gate_height(inout FxParticle p, FxStep s, vec4 m, sampler2D heights) {
                    float h = texture(heights, (p.position.xz - m.xy) * m.z).r;
                    if (p.position.y < h) {
                        p.position.y = h;
                        p.velocity.y = 0.0;
                    }
                }
                """)), List.of());
        depthed = new Inputs(base, List.of(new Kind("gate_depth", true, new CgVfxWorldInput[]{CgVfxWorldInput.DEPTH}, CgVfxGpuModule.NO_TEXTURES, THICKNESS, 0f, 0f, 0f, """
                void fx_gate_depth(inout FxParticle p, FxStep s, vec4 m, FxDepth depth) {
                    vec3 n;
                    if (!fx_depth_behind(depth, p.position, m.x, n)) return;
                    fx_hit(p, n);
                    p.position = p.previous;
                    p.velocity = vec3(0.0);
                }
                """)), List.of(CgVfxEvent.onCollision().readback(PER_POOL)));
        host.set(CAMERA[0], CAMERA[1], CAMERA[2], view, projection);
        fillDepth();
        for (CgVfxGpuEmitter d : List.of(fielded, heightened, depthed)) CgVfxParticlePool.prepare(d);
        CgVfxParticlePool.listen(listener);
    }

    /** The wall at {@link #WALL} over the left half, the floor at {@link #FLOOR} below the camera over the right. */
    private void fillDepth() {
        for (int y = 0; y < DEPTH; y++) {
            for (int x = 0; x < DEPTH; x++) {
                float[] ray = ray(x, y);
                float depth = x < DEPTH / 2 ? WALL : ray[1] < 0f ? FLOOR / ray[1] : FAR;
                depths[y * DEPTH + x] = depth;
            }
        }
    }

    /** Pixel (x, y)'s centre at eye depth 1, in view space. */
    private float[] ray(int x, int y) {
        float nx = (x + 0.5f) / DEPTH * 2f - 1f, ny = (y + 0.5f) / DEPTH * 2f - 1f;
        return new float[]{(nx + projection.m20()) / projection.m00(), (ny + projection.m21()) / projection.m11(), -1f};
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame <= STEPS + 1) {
            CgRecording rec = new CgRecording();
            if (frame == 1) seed(rec);
            else step(rec);
            if (frame == STEPS + 1) {
                for (int p = 0; p < 3; p++) {
                    int at = p;
                    requests.add(rec.readback(pools[p].live(), 0, 4, data -> live[at] = data.getInt(0)));
                    requests.add(rec.readback(pools[p].records(), 0, (long) pools[p].storage() * CgVfxRecord.BYTES,
                            data -> records[at] = copy(data)));
                }
            }
            CgImmediate.execute(rec);
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && records[2] != null && frame > STEPS + 10) {
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
        Random random = new Random(1201);
        CgVfxGpuEmitter[] definitions = {fielded, heightened, depthed};
        for (int p = 0; p < 3; p++) {
            pools[p] = CgVfxParticlePool.of(this, definitions[p]);
            slots[p] = pools[p].open(definitions[p], PER_POOL);
            ByteBuffer packed = ByteBuffer.allocateDirect(PER_POOL * CgVfxRecord.BYTES).order(ByteOrder.nativeOrder());
            for (int r = 0; r < PER_POOL; r++) {
                float[] at = new float[3], v = new float[3];
                if (p == 0) {
                    for (int a = 0; a < 3; a++) at[a] = (random.nextFloat() * 2f - 1f) * 3f;
                } else if (p == 1) {
                    at[0] = (random.nextFloat() * 2f - 1f) * 3.5f;
                    at[1] = 2f + random.nextFloat();
                    at[2] = (random.nextFloat() * 2f - 1f) * 3.5f;
                    v[1] = -FALL;
                } else {
                    thrown(r, random, at, v);
                }
                int b = r * CgVfxRecord.BYTES;
                packed.putFloat(b, at[0]).putFloat(b + 4, at[1]).putFloat(b + 8, at[2]).putFloat(b + 12, 0f);
                packed.putFloat(b + 16, at[0]).putFloat(b + 20, at[1]).putFloat(b + 24, at[2]).putFloat(b + 28, 100f);
                packed.putFloat(b + 32, v[0]).putFloat(b + 36, v[1]).putFloat(b + 40, v[2]).putFloat(b + 44, 0.1f);
                packed.putFloat(b + 48, (r + 0.5f) / PER_POOL).putFloat(b + 52, 0f).putFloat(b + 56, 0f).putFloat(b + 60, 0f);
                packed.putInt(b + 64, r).putInt(b + 68, slots[p]).putInt(b + 72, 0).putInt(b + 76, 0);
            }
            pools[p].seed(rec, packed, PER_POOL);
        }
    }

    /** Depth particle {@code r}: half thrown at the wall, half dropped onto the floor; from the depth pool's origin. */
    private void thrown(int r, Random random, float[] at, float[] velocity) {
        Vector3f v0, v;
        if (r % 2 == 0) {
            v0 = new Vector3f(-2.5f + random.nextFloat() * 1.7f, -1f + random.nextFloat() * 2f, -5f - random.nextFloat() * 3f);
            v = new Vector3f(0f, 0f, -6f);
        } else {
            v0 = new Vector3f(0.8f + random.nextFloat() * 1.7f, -0.5f + random.nextFloat(), -5f - random.nextFloat() * 4f);
            v = new Vector3f(0f, -5f, 0f);
        }
        inverse.transformDirection(v0);
        inverse.transformDirection(v);
        at[0] = (float) (CAMERA[0] + v0.x - DEPTH_ORIGIN[0]);
        at[1] = (float) (CAMERA[1] + v0.y - DEPTH_ORIGIN[1]);
        at[2] = (float) (CAMERA[2] + v0.z - DEPTH_ORIGIN[2]);
        velocity[0] = v.x;
        velocity[1] = v.y;
        velocity[2] = v.z;
    }

    private void step(CgRecording rec) {
        CgGraphTexture pyramid = CgGraphTexture.transientTexture("vfx-inputs.depth", new CgTextureDesc(DEPTH, DEPTH, CgGpuOps.PYRAMID_FORMAT));
        rec.update(pyramid, 0, 0, 0, DEPTH, DEPTH, floats(depths));
        CgVfxParticlePool.depth(host, pyramid);
        for (int p = 0; p < 3; p++) {
            pools[p].beginStep(DT, 0f, 0f, 0f);
            pools[p].instance(slots[p], 0x5EED + p, 1f, Float.NaN, origin(p == 2 ? DEPTH_ORIGIN : ORIGIN));
            pools[p].endStep();
        }
        CgVfxParticlePool.record(rec, List.of(pools));
    }

    private void heard(CgVfxGpuEmitter definition, int event, CgVfxEventRows heard) {
        if (definition != depthed) {
            fail("rows heard for " + definition.name());
            return;
        }
        if (heard.dropped() > 0) fail(heard.dropped() + " collision rows dropped");
        for (int i = 0; i < heard.count(); i++) {
            hits.put(heard.parentId(i), new double[]{heard.x(i), heard.y(i), heard.z(i), heard.nx(i), heard.ny(i), heard.nz(i)});
            hitCounts.merge(heard.parentId(i), 1, Integer::sum);
        }
    }

    private void compare() {
        for (int p = 0; p < 3; p++) {
            if (live[p] != PER_POOL) fail("pool " + p + " has " + live[p] + " alive, not " + PER_POOL);
        }
        if (failure != null) return;
        for (int r = 0; r < PER_POOL; r++) checkField(r);
        for (int r = 0; r < PER_POOL; r++) checkHeight(r);
        checkDepth();
    }

    /** Its velocity the field where it stood at the step's start, and where that took it. */
    private void checkField(int r) {
        ByteBuffer b = records[0];
        float[] was = new float[3];
        for (int a = 0; a < 3; a++) was[a] = (CgVfxRecord.f(b, r, CgVfxRecord.PREVIOUS + a) + BOX) / (2f * BOX);
        for (int a = 0; a < 3; a++) {
            float want = sample3(was, a), v = CgVfxRecord.f(b, r, CgVfxRecord.VELOCITY + a);
            float moved = CgVfxRecord.f(b, r, CgVfxRecord.PREVIOUS + a) + v * DT, at = CgVfxRecord.f(b, r, CgVfxRecord.POSITION + a);
            worstField = Math.max(worstField, Math.abs(v - want));
            if (Math.abs(v - want) > FILTERED || Math.abs(at - moved) > 1e-4f) {
                fail("particle " + r + " in the field moves " + v + " on axis " + a + ", not " + want + ", to " + at);
                return;
            }
        }
        fieldChecked++;
    }

    /** At the height it fell to, or on the heightfield where that is above it, never moving across. */
    private void checkHeight(int r) {
        ByteBuffer b = records[1];
        float x = CgVfxRecord.f(b, r, CgVfxRecord.POSITION), z = CgVfxRecord.f(b, r, CgVfxRecord.POSITION + 2);
        float y = CgVfxRecord.f(b, r, CgVfxRecord.POSITION + 1);
        float h = sample2((x + BOX) / (2f * BOX), (z + BOX) / (2f * BOX));
        // Its last step: falling on from where it was, or stopped on the ground there.
        float free = CgVfxRecord.f(b, r, CgVfxRecord.PREVIOUS + 1) - FALL * DT;
        boolean stopped = CgVfxRecord.f(b, r, CgVfxRecord.VELOCITY + 1) == 0f;
        float off = stopped ? Math.abs(y - h) : Math.abs(y - free);
        worstHeight = Math.max(worstHeight, off);
        if (stopped ? off > FILTERED : off > 1e-4f || y < h - FILTERED) {
            fail("particle " + r + " on the heightfield is at " + y + (stopped ? ", stopped; its ground is " : ", falling; its ground is ") + h);
            return;
        }
        if (stopped) clamped++;
        else falling++;
    }

    /** Every wall and floor particle struck once, at the surface, with the surface's normal, and rests where it struck. */
    private void checkDepth() {
        Vector3f wall = inverse.transformDirection(new Vector3f(0f, 0f, 1f)), floor = new Vector3f(0f, 1f, 0f);
        ByteBuffer b = records[2];
        for (int r = 0; r < PER_POOL; r++) {
            int id = CgVfxRecord.i(b, r, CgVfxRecord.ID);
            double[] hit = hits.get(id);
            int count = hitCounts.getOrDefault(id, 0);
            if (hit == null || count != 1) {
                fail("depth particle " + id + " collided " + count + " times");
                return;
            }
            Vector3f want = id % 2 == 0 ? wall : floor;
            float off = (float) Math.max(Math.abs(hit[3] - want.x), Math.max(Math.abs(hit[4] - want.y), Math.abs(hit[5] - want.z)));
            worstNormal = Math.max(worstNormal, off);
            if (off > 2e-3f) {
                fail("depth particle " + id + " struck along (" + hit[3] + ", " + hit[4] + ", " + hit[5] + "), not " + want);
                return;
            }
            // Where it struck: the depth test passes there and fails a step on, as the GPU tests it, by its pixel's
            // centre (within a pixel either way of rounding).
            Vector3f at = new Vector3f((float) (hit[0] - CAMERA[0]), (float) (hit[1] - CAMERA[1]), (float) (hit[2] - CAMERA[2]));
            Vector3f velocity = inverse.transformDirection(id % 2 == 0 ? new Vector3f(0f, 0f, -6f) : new Vector3f(0f, -5f, 0f));
            Vector3f next = new Vector3f(velocity).mul(DT).add(at);
            if (behind(at, true) || !behind(next, false)) {
                fail("depth particle " + id + " struck at " + at + " (camera-relative), where the depth "
                        + (behind(at, true) ? "already had it behind" : "has it in front a step on"));
                return;
            }
            for (int a = 0; a < 3; a++) {
                double now = DEPTH_ORIGIN[a] + CgVfxRecord.f(b, r, CgVfxRecord.POSITION + a);
                if (Math.abs(now - hit[a]) > 1e-3) fail("depth particle " + id + " moved after it struck");
            }
            if (id % 2 == 0) wallHits++;
            else floorHits++;
        }
    }

    /**
     * {@code fx_depth_behind}'s test for a camera-relative point: its eye depth past the scene's at its pixel. Surely
     * (every pixel round it has it behind) or possibly (any does).
     */
    private boolean behind(Vector3f camera, boolean surely) {
        Vector3f v = view.transformDirection(new Vector3f(camera));
        float own = -v.z;
        float px = ((projection.m00() * v.x + projection.m20() * v.z) / own * 0.5f + 0.5f) * DEPTH;
        float py = ((projection.m11() * v.y + projection.m21() * v.z) / own * 0.5f + 0.5f) * DEPTH;
        int x = (int) Math.floor(px), y = (int) Math.floor(py);
        boolean any = false, all = true;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int sx = Math.max(0, Math.min(x + dx, DEPTH - 1)), sy = Math.max(0, Math.min(y + dy, DEPTH - 1));
                float scene = depths[sy * DEPTH + sx];
                boolean b = own > scene && own <= scene + THICKNESS;
                any |= b;
                all &= b;
            }
        }
        return surely ? all : any;
    }

    /** The field's component {@code c} at {@code uvw}, filtered as GL filters it: clamped to its edge. */
    private float sample3(float[] uvw, int c) {
        float[] f = new float[3];
        int[] i0 = new int[3], i1 = new int[3];
        for (int a = 0; a < 3; a++) {
            float x = uvw[a] * FIELD - 0.5f;
            int i = (int) Math.floor(x);
            f[a] = x - i;
            i0[a] = Math.max(0, Math.min(i, FIELD - 1));
            i1[a] = Math.max(0, Math.min(i + 1, FIELD - 1));
        }
        float sum = 0f;
        for (int corner = 0; corner < 8; corner++) {
            int x = (corner & 1) == 0 ? i0[0] : i1[0], y = (corner & 2) == 0 ? i0[1] : i1[1], z = (corner & 4) == 0 ? i0[2] : i1[2];
            float w = ((corner & 1) == 0 ? 1f - f[0] : f[0]) * ((corner & 2) == 0 ? 1f - f[1] : f[1]) * ((corner & 4) == 0 ? 1f - f[2] : f[2]);
            sum += w * field[((z * FIELD + y) * FIELD + x) * 4 + c];
        }
        return sum;
    }

    /** The heightfield at {@code (u, v)}, filtered as GL filters it. */
    private float sample2(float u, float v) {
        float x = u * HEIGHTS - 0.5f, y = v * HEIGHTS - 0.5f;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        int x0 = Math.max(0, Math.min(ix, HEIGHTS - 1)), x1 = Math.max(0, Math.min(ix + 1, HEIGHTS - 1));
        int y0 = Math.max(0, Math.min(iy, HEIGHTS - 1)), y1 = Math.max(0, Math.min(iy + 1, HEIGHTS - 1));
        float a = heights[y0 * HEIGHTS + x0] * (1f - fx) + heights[y0 * HEIGHTS + x1] * fx;
        float c = heights[y1 * HEIGHTS + x0] * (1f - fx) + heights[y1 * HEIGHTS + x1] * fx;
        return a * (1f - fy) + c * fy;
    }

    private static ByteBuffer floats(float[] values) {
        ByteBuffer out = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        for (float v : values) out.putFloat(v);
        return out.flip();
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
        if (failure == null && compared && (clamped == 0 || falling == 0)) failure = clamped + " on the heightfield, " + falling + " above it: both are the test";
        if (GlErrorChecker.checkAndLog("vfx-inputs")) {
            System.out.println("[vfx-inputs] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-inputs] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-inputs] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-inputs] FAIL on " + on + ": the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-inputs] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-inputs] PASS on " + on + ": " + fieldChecked + " particles moved by a 3D field (worst "
                    + worstField + "); " + clamped + " stopped on a heightfield and " + falling + " still above it (worst "
                    + worstHeight + "); " + wallHits + " struck a wall and " + floorHits + " a floor in the depth, once each, "
                    + "at the surface, along its normal (worst " + worstNormal + ")");
        }
    }

    @Override
    public void dispose() {
        if (fieldTexture != null) fieldTexture.delete();
        if (heightTexture != null) heightTexture.delete();
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }

    /** A kind given as text: its numbers one vec4, what it samples one texture or the depth. */
    private record Kind(String gpuKind, boolean afterSolve, CgVfxWorldInput[] worldInputs, CgTexture[] textures,
                        float x, float y, float z, float w, String gpuSource) implements CgVfxGpuModule {
        public void writeParams(CgVfxWords out) { out.vec4(x, y, z, w); }
    }

    private record Inputs(CgVfxEmitter of, List<CgVfxGpuModule> modules, List<CgVfxEvent> events) implements CgVfxGpuEmitter {
        public String name() { return of.name() + "-" + modules.get(0).gpuKind(); }
        public CgVfxEmitter.Renderer renderer() { return of.renderer(); }
        public void writeSpawn(CgVfxWords out) { of.writeSpawn(out); }
        public void writeCurves(float[] out, int at, int texels) { of.writeCurves(out, at, texels); }
    }
}
