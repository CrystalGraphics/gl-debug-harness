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
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxRecord;
import com.crystalgraphics.vfx.particle.renderer.CgVfxMeshFacing;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The gate for mesh particles' facing ({@code CgVfxMeshFacing}): seven emitters in one pool, a slot each, each facing
 * otherwise (Niagara's camera position, velocity, camera plane and locked axis; Cascade's shortest arc, camera-turned
 * up and world up, rolls about the facing and the camera, a mesh rotation and scale, and pre-rotations constant and
 * rolled per channel), stepped once and ranged for one view. Each mesh's object record, as Range writes it, against
 * {@code CgVfxMeshFacing.model}'s Java; and, needing no Java twin, the camera-position mesh's x axis on the eye and the
 * locked mesh's up on its axis.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-mesh-facing"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-mesh-facing" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-mesh-facing --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-mesh-facing] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgVfxMeshFacingScene implements InteractiveSceneLifecycle {

    private static final int N = 64, DRAIN = 40, OBJECT_FLOATS = 48, DRAWN_FLOATS = 24;
    private static final float DT = 1f / 60f, TOLERANCE = 2e-3f;
    private static final double CAMERA_X = 40.5, CAMERA_Y = 70.25, CAMERA_Z = -12.75;
    private static final String MESH = "crystalgraphics:models/facing_check.glb";

    private final List<CgVfxEmitter> emitters = new ArrayList<>();
    /** Each station's origin from the camera: all ahead of it, inside the view. */
    private final List<float[]> origins = new ArrayList<>();
    private final Map<CgVfxParticlePool, ByteBuffer> records = new IdentityHashMap<>();
    private final List<CgVfxParticlePool> pools = new ArrayList<>();
    private final int[] slots = new int[7];
    private final CgHostView view = new CgHostView();
    private final Matrix4f look = new Matrix4f();
    private final List<CgRequest> requests = new ArrayList<>();
    private ByteBuffer drawnParts, visible, order, objects;
    private final Map<CgVfxParticlePool, ByteBuffer> drawnByPool = new IdentityHashMap<>();
    private int frame, checked;
    private float worst;
    private String failure;
    private boolean compared, running = true;

    public CgVfxMeshFacingScene() {
        station("camera-position", Map.of("facing", "camera_position", "roll_about", "facing"));
        station("shortest-arc", Map.of("facing", "camera_position", "facing_up", "none", "roll_about", "facing",
                "pre_rotation", Map.of("range", List.of(List.of(0.0, -0.5, 0.0), List.of(1.0, 0.5, 3.0)), "per_channel", true)));
        station("velocity-camera-up", Map.of("facing", "velocity", "facing_up", "camera", "roll_about", "camera"),
                List.of(0.3, 1.5708, -0.2), null);
        station("camera-plane", Map.of("facing", "camera_plane",
                "pre_rotation", Map.of("pick", List.of(List.of(0.4, 0.0, 0.0), List.of(0.0, 1.2, 0.3))))); 
        station("camera-plane-world-up", Map.of("facing", "camera_plane", "facing_up", "world"), null, List.of(2.0, 1.0, 0.5));
        station("locked-axis", Map.of("facing", "camera_position", "roll_about", "facing",
                "locked_axis", Map.of("axis", List.of(0.3, 1.0, 0.2), "space", "world")));
        station("default", Map.of("pre_rotation", List.of(0.4, -0.3, 1.1)), List.of(0.0, 0.0, 1.2), null);
    }

    private void station(String name, Map<String, Object> fields) {
        station(name, fields, null, null);
    }

    private void station(String name, Map<String, Object> fields, List<Double> rotation, List<Double> scale) {
        Map<String, Object> mesh = new LinkedHashMap<>();
        mesh.put("mesh", MESH);
        if (rotation != null) mesh.put("rotation", rotation);
        if (scale != null) mesh.put("scale", scale);
        CgVfxRenderers.Builder r = CgVfxRenderers.mesh("m").material("crystalgraphics:materials/facing_check").field("meshes", List.of(mesh));
        for (Map.Entry<String, Object> f : fields.entrySet()) r.field(f.getKey(), f.getValue());
        int i = emitters.size();
        emitters.add(CgVfxEmitter.builder("facing-" + name).state(CgVfxEmitterState.ONCE).capacity(N).life(100f, 100f)
                .size(0.3f, 0.6f).renderer(r.build()).build());
        origins.add(new float[]{(i - 3) * 2.5f, (i % 2) * 1.5f - 0.5f, -12f - i});
    }

    @Override
    public void init(HarnessContext ctx) {
        look.lookAt(0f, 0f, 0f, 0.1f, -0.05f, -1f, 0f, 1f, 0f);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(80), 16f / 9f, 0.05f, 300f);
        view.set(CAMERA_X, CAMERA_Y, CAMERA_Z, look, projection);
        for (CgVfxEmitter e : emitters) CgVfxParticlePool.prepare(e);
        CgVfxRange.prepare();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        if (frame == 1) {
            record();
            return;
        }
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (!compared && answered && objects != null && visible != null && order != null && records.size() == pools.size()
                && drawnByPool.size() == pools.size()) {
            compare();
            compared = true;
        }
        if (compared || frame > DRAIN) {
            report();
            CgVfxParticlePool.release(this);
            running = false;
        }
    }

    private void record() {
        CgRecording rec = new CgRecording();
        Random random = new Random(1301);
        Map<CgVfxParticlePool, List<Integer>> stations = new LinkedHashMap<>();
        for (int i = 0; i < emitters.size(); i++) {
            CgVfxParticlePool pool = CgVfxParticlePool.of(this, emitters.get(i));
            if (!pools.contains(pool)) pools.add(pool);
            slots[i] = pool.open(emitters.get(i), N);
            stations.computeIfAbsent(pool, p -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<CgVfxParticlePool, List<Integer>> e : stations.entrySet()) {
            CgVfxParticlePool pool = e.getKey();
            CgVfxRecord layout = pool.record();
            int total = e.getValue().size() * N, r = 0;
            ByteBuffer packed = ByteBuffer.allocateDirect(total * layout.bytes()).order(ByteOrder.nativeOrder());
            for (int i : e.getValue()) {
                for (int k = 0; k < N; k++, r++) {
                    float x = spread(random, 1.5f), y = spread(random, 1.5f), z = spread(random, 1.5f);
                    // A few still: a velocity facing then faces world up.
                    float speed = k % 8 == 0 ? 0f : 1f;
                    layout.put(packed, r, x, y, z, spread(random, 3f) * speed, spread(random, 3f) * speed,
                            spread(random, 3f) * speed, random.nextFloat() * 5f, 100f, 0.3f + random.nextFloat() * 0.3f,
                            (r + 0.5f) / total, k, slots[i]);
                    layout.putF(packed, r, CgVfxRecord.SPIN, random.nextFloat() * 6.28f);
                }
            }
            pool.seed(rec, packed, total);
            pool.beginStep(DT, 0f, 0f, 0f);
            for (int i : e.getValue()) pool.instance(slots[i], 0x4ACE + i, 1f, Float.NaN, origin(origins.get(i)));
            pool.endStep();
            pool.record(rec);
        }
        CgVfxRange first = null;
        for (CgVfxParticlePool pool : pools) {
            CgVfxRange range = CgVfxRange.of(pool);
            range.objects();
            if (first == null) first = range;
        }
        CgVfxRange.record(rec, view, null);
        int end = 0, words = 0;
        for (CgVfxParticlePool pool : pools) {
            CgVfxRange range = CgVfxRange.of(pool);
            for (int s = 0; s < pool.slotCount(); s++) {
                if (!pool.isOpen(s)) continue;
                end = Math.max(end, range.base(s) + pool.capacity(s));
                words = Math.max(words, range.visibleWord(s) + 1);
            }
            requests.add(rec.readback(pool.records(), 0, (long) pool.storage() * pool.record().bytes(), data -> records.put(pool, copy(data))));
            requests.add(rec.readback(pool.drawn(), 0, (long) pool.storage() * pool.shape().drawnStride() * CgVfxRecord.DRAWN_BYTES,
                    data -> drawnByPool.put(pool, copy(data))));
        }
        requests.add(rec.readback(first.visible(), 0, words * 4L, data -> visible = copy(data)));
        requests.add(rec.readback(first.order(), 0, (long) end * 4, data -> order = copy(data)));
        requests.add(rec.readback(first.objects(), 0, (long) end * OBJECT_FLOATS * 4, data -> objects = copy(data)));
        CgImmediate.execute(rec);
    }

    private void compare() {
        Vector3f forward = new Vector3f(-look.m02(), -look.m12(), -look.m22()), up = new Vector3f(look.m01(), look.m11(), look.m21());
        Matrix3f want = new Matrix3f();
        for (int i = 0; i < emitters.size(); i++) {
            CgVfxParticlePool pool = CgVfxParticlePool.of(this, emitters.get(i));
            CgVfxRange range = CgVfxRange.of(pool);
            CgVfxRecord layout = pool.record();
            ByteBuffer drawn = drawnByPool.get(pool), recs = records.get(pool);
            int stride = pool.shape().drawnStride(), count = visible.getInt(range.visibleWord(slots[i]) * 4);
            CgVfxMeshFacing facing = emitters.get(i).meshFacing();
            if (count != N) fail(emitters.get(i).name() + " has " + count + " of " + N + " visible");
            for (int k = 0; k < count; k++) {
                int d = range.base(slots[i]) + k, e = (int) order.getFloat(d * 4), r = e / stride, at = e * DRAWN_FLOATS * 4;
                float[] place = vec(drawn, at), motion = vec(drawn, at + 16), state = vec(drawn, at + 32), scale = vec(drawn, at + 80);
                float[] o = origins.get(i);
                Vector3f toCamera = new Vector3f(-(o[0] + place[0]), -(o[1] + place[1]), -(o[2] + place[2]));
                Quaternionf own = new Quaternionf();
                if (!facing.faces()) {
                    float turn = state[0] * 6.2831853f + state[1];
                    own.rotationXYZ(turn * 1.7f, turn * 2.3f, turn);
                }
                int bits = Float.floatToRawIntBits(layout.f(recs, r, CgVfxRecord.SEED));
                float[] rolls = {(float) CgVfxEmitterInstance.rand(bits, 0, 61), (float) CgVfxEmitterInstance.rand(bits, 0, 62),
                        (float) CgVfxEmitterInstance.rand(bits, 0, 63)};
                facing.model(toCamera, forward, up, new Vector3f(motion[0], motion[1], motion[2]), state[1], new Quaternionf(), own,
                        new Vector3f(place[3] * scale[0], place[3] * scale[1], place[3] * scale[2]), rolls, want);
                for (int c = 0; c < 3; c++) {
                    Vector3f column = want.getColumn(c, new Vector3f());
                    float axis = Math.max(column.length(), 1e-6f);
                    for (int a = 0; a < 3; a++) {
                        float got = objects.getFloat((d * OBJECT_FLOATS + c * 4 + a) * 4);
                        float off = Math.abs(got - column.get(a)) / (TOLERANCE * axis);
                        worst = Math.max(worst, off);
                        if (!(off <= 1f)) {
                            fail(emitters.get(i).name() + ": particle " + r + "'s axis " + c + " lane " + a + " is " + got
                                    + ", Java gives " + column.get(a));
                            return;
                        }
                    }
                }
                if (i == 0) along(emitters.get(i).name() + "'s x axis", d, 0, toCamera);
                if (i == 5) along(emitters.get(i).name() + "'s up", d, 1, new Vector3f(0.3f, 1f, 0.2f));
                checked++;
            }
        }
    }

    /** Object {@code d}'s axis {@code c} along {@code want}, whatever its length. */
    private void along(String what, int d, int c, Vector3f want) {
        Vector3f got = new Vector3f(objects.getFloat((d * OBJECT_FLOATS + c * 4) * 4), objects.getFloat((d * OBJECT_FLOATS + c * 4 + 1) * 4),
                objects.getFloat((d * OBJECT_FLOATS + c * 4 + 2) * 4)).normalize();
        float off = (1f - got.dot(new Vector3f(want).normalize())) / 1e-4f;
        if (!(off <= 1f)) fail(what + " points " + got + ", not along " + want);
    }

    private static float[] vec(ByteBuffer b, int at) {
        return new float[]{b.getFloat(at), b.getFloat(at + 4), b.getFloat(at + 8), b.getFloat(at + 12)};
    }

    private static float spread(Random random, float half) {
        return (random.nextFloat() * 2f - 1f) * half;
    }

    private static CgVfxInstanceView origin(float[] offset) {
        return new CgVfxInstanceView() {
            public double originX() { return CAMERA_X + offset[0]; }
            public double originY() { return CAMERA_Y + offset[1]; }
            public double originZ() { return CAMERA_Z + offset[2]; }
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
        if (GlErrorChecker.checkAndLog("vfx-mesh-facing")) {
            System.out.println("[vfx-mesh-facing] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[vfx-mesh-facing] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (on.startsWith("recording")) {
            System.out.println("[vfx-mesh-facing] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (!compared) {
            System.out.println("[vfx-mesh-facing] FAIL on " + on + ": the records never arrived");
        } else if (failure != null) {
            System.out.println("[vfx-mesh-facing] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[vfx-mesh-facing] PASS on " + on + ": " + emitters.size() + " facings, " + checked
                    + " mesh particles, each axis Java's (worst share of the tolerance " + worst + ")");
        }
    }

    @Override public void dispose() {}

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
