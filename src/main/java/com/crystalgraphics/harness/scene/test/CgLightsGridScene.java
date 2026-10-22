package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The gate for the world's light list and its grid ({@code CgWorldLights}): lights added from the CPU and appended by
 * a kernel each frame, the list and the grid read back after the world fires and held to Java. The CPU's come first,
 * exactly; the kernel's follow, as a set; and every cell lists exactly the lights whose sphere reaches its view-space
 * box (Unreal's LightGridInjection), in index order, the first {@link CgWorldLights#PER_CELL} of them. A cluster of
 * lights round one point passes that cap in the cells about it. Scene id {@code lights-grid}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-grid"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-grid" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-grid --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [lights-grid] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public final class CgLightsGridScene implements InteractiveSceneLifecycle {

    private static final int CPU = 150, KERNEL = 250, CLUSTER = 50, CHECK_AT = 30, DRAIN = 60;
    /** How near a light's reach may come to a cell's box and still go either way: float rounding, not a wrong bin. */
    private static final float BORDER = 1e-3f;

    private final double[] cpuAt = new double[CPU * 3];
    private final float[] cpuLight = new float[CPU * 5];
    private final float[] placed = new float[KERNEL * 8];
    private final List<CgRequest> requests = new ArrayList<>();
    private CgGraphBuffer placedBuffer;
    private CgKernel append;
    private CgWorldLights.Appender appender;
    private ByteBuffer count, list, cells, indices;
    private double eyeX, eyeY, eyeZ;
    private int gridX, gridY, frame, checked, cellsChecked, capped;
    private float[] rays, zParams;
    private float tileU, tileV;
    private final Matrix4f toView = new Matrix4f();
    private String failure;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 6f, 16f);
        ctx.getCamera3D().setPitch(-25f);
        Random random = new Random(1201);
        for (int i = 0; i < CPU; i++) {
            cpuAt[i * 3] = random.nextDouble() * 40 - 20;
            cpuAt[i * 3 + 1] = random.nextDouble() * 3;
            cpuAt[i * 3 + 2] = random.nextDouble() * -40;
            cpuLight[i * 5] = 1f + random.nextFloat() * 5f;
            cpuLight[i * 5 + 1] = Float.intBitsToFloat(0xFF000000 | random.nextInt(0x1000000));
            cpuLight[i * 5 + 2] = 0.5f + random.nextFloat() * 3f;
            cpuLight[i * 5 + 3] = random.nextBoolean() ? 0f : 2f + random.nextInt(15);
        }
        for (int i = 0; i < KERNEL; i++) {
            boolean cluster = i < CLUSTER;
            placed[i * 8] = cluster ? (float) (random.nextGaussian() * 0.5) : random.nextFloat() * 40f - 20f;
            placed[i * 8 + 1] = cluster ? 1f : random.nextFloat() * 3f;
            placed[i * 8 + 2] = cluster ? -8f + (float) (random.nextGaussian() * 0.5) : random.nextFloat() * -40f;
            placed[i * 8 + 3] = cluster ? 3f : 1f + random.nextFloat() * 5f;
            for (int c = 4; c < 7; c++) placed[i * 8 + c] = random.nextFloat() * 4f;
            placed[i * 8 + 7] = random.nextBoolean() ? 0f : 2f + random.nextInt(15);
        }
        append = CgCompute.load("harness:shaders/lights_append.compute").kernel("Append");
        placedBuffer = CgGraphBuffer.persistent("lights-grid.placed",
                CgBufferDesc.elements(KERNEL, 32, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        ByteBuffer bytes = ByteBuffer.allocateDirect(KERNEL * 32).order(ByteOrder.nativeOrder());
        for (float f : placed) bytes.putFloat(f);
        CgRecording rec = new CgRecording();
        rec.update(placedBuffer, 0, bytes.flip());
        CgImmediate.execute(rec);
        appender = new CgWorldLights.Appender() {
            @Override
            public boolean append(CgComputePass pass, CgWorldLights lights) {
                lights.into(pass.dispatch(append, KERNEL)).bind("PLACED", placedBuffer);
                return true;
            }
        };
        CgWorldRenderer.get().lights().add(appender);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        CgWorldLights lights = CgWorldRenderer.get().lights();
        for (int i = 0; i < CPU; i++) {
            lights.add(cpuAt[i * 3], cpuAt[i * 3 + 1], cpuAt[i * 3 + 2], cpuLight[i * 5],
                    Float.floatToRawIntBits(cpuLight[i * 5 + 1]), cpuLight[i * 5 + 2], cpuLight[i * 5 + 3]);
        }
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
        if (frame == CHECK_AT) readBack(lights);
        if (frame <= CHECK_AT) return;
        boolean answered = true;
        for (CgRequest r : requests) answered &= r.status() != CgRequest.Status.PENDING;
        if (answered || frame > CHECK_AT + DRAIN) {
            if (answered && !on().startsWith("recording")) compare();
            report();
            CgWorldRenderer.get().lights().remove(appender);
            running = false;
        }
    }

    /** What the firing just listed and binned, with what placed it. */
    private void readBack(CgWorldLights lights) {
        eyeX = lights.cameraX();
        eyeY = lights.cameraY();
        eyeZ = lights.cameraZ();
        gridX = lights.gridX();
        gridY = lights.gridY();
        rays = lights.rays().clone();
        zParams = lights.zParams().clone();
        tileU = lights.tileU();
        tileV = lights.tileV();
        toView.set(lights.toView());
        int n = gridX * gridY * CgWorldLights.SLICES;
        CgRecording rec = new CgRecording();
        requests.add(rec.readback(lights.count(), 0, 4, data -> count = copy(data)));
        requests.add(rec.readback(lights.list(), 0, (long) (CPU + KERNEL) * CgWorldLights.FLOATS * 4, data -> list = copy(data)));
        requests.add(rec.readback(lights.cells(), 0, n * 4L, data -> cells = copy(data)));
        requests.add(rec.readback(lights.indices(), 0, (long) n * CgWorldLights.PER_CELL * 4, data -> indices = copy(data)));
        CgImmediate.execute(rec);
    }

    private void compare() {
        int listed = count.getInt(0);
        if (listed != CPU + KERNEL) {
            fail(listed + " lights listed, not " + (CPU + KERNEL));
            return;
        }
        // The CPU's, in order: placed from the firing's eye, their colour decoded from sRGB.
        for (int i = 0; i < CPU; i++) {
            int argb = Float.floatToRawIntBits(cpuLight[i * 5 + 1]);
            float scale = (argb >>> 24) / 255f * cpuLight[i * 5 + 2];
            float[] want = {(float) (cpuAt[i * 3] - eyeX), (float) (cpuAt[i * 3 + 1] - eyeY), (float) (cpuAt[i * 3 + 2] - eyeZ),
                    cpuLight[i * 5], srgb(argb >> 16 & 0xFF) * scale, srgb(argb >> 8 & 0xFF) * scale, srgb(argb & 0xFF) * scale,
                    cpuLight[i * 5 + 3]};
            for (int w = 0; w < 8; w++) near("CPU light " + i + " word " + w, light(i, w), want[w]);
        }
        // The kernel's, as a set: an append's order is the GPU's.
        boolean[] matched = new boolean[KERNEL];
        for (int i = CPU; i < listed; i++) {
            int found = -1;
            for (int k = 0; k < KERNEL && found < 0; k++) {
                if (!matched[k] && Math.abs(light(i, 3) - placed[k * 8 + 3]) < 1e-6f
                        && Math.abs(light(i, 0) - (placed[k * 8] - (float) eyeX)) < 1e-3f
                        && Math.abs(light(i, 2) - (placed[k * 8 + 2] - (float) eyeZ)) < 1e-3f
                        && Math.abs(light(i, 4) - placed[k * 8 + 4]) < 1e-6f) found = k;
            }
            if (found < 0) {
                fail("appended light " + i + " is none the kernel was given");
                return;
            }
            matched[found] = true;
            checked++;
        }
        bins(listed);
    }

    /** Every cell's lights against Unreal's test: the light's sphere against the cell's view-space box. */
    private void bins(int listed) {
        int per = CgWorldLights.PER_CELL;
        float[] view = new float[listed * 3];
        for (int i = 0; i < listed; i++) {
            float x = light(i, 0), y = light(i, 1), z = light(i, 2);
            view[i * 3] = toView.m00() * x + toView.m10() * y + toView.m20() * z;
            view[i * 3 + 1] = toView.m01() * x + toView.m11() * y + toView.m21() * z;
            view[i * 3 + 2] = toView.m02() * x + toView.m12() * y + toView.m22() * z;
        }
        for (int z = 0; z < CgWorldLights.SLICES; z++) {
            float d0 = sliceDepth(z), d1 = sliceDepth(z + 1);
            for (int y = 0; y < gridY; y++) {
                for (int x = 0; x < gridX; x++) {
                    int e = (z * gridY + y) * gridX + x;
                    float[] box = box(x, y, d0, d1);
                    List<Integer> must = new ArrayList<>(), may = new ArrayList<>();
                    for (int i = 0; i < listed; i++) {
                        float r = light(i, 3), gap = gap(box, view[i * 3], view[i * 3 + 1], view[i * 3 + 2]) - r * r;
                        if (gap < -BORDER * r * r) must.add(i);
                        else if (gap <= BORDER * r * r) may.add(i);
                    }
                    int got = cells.getInt(e * 4);
                    int[] indexes = new int[got];
                    for (int k = 0; k < got; k++) indexes[k] = indices.getInt((e * per + k) * 4);
                    cellsChecked++;
                    if (must.size() + may.size() > per) capped++;
                    if (!sameCell(indexes, must, may, per)) {
                        fail("cell (" + x + ", " + y + ", " + z + ") lists " + Arrays.toString(indexes) + ", not " + must
                                + (may.isEmpty() ? "" : " with any of " + may));
                        return;
                    }
                }
            }
        }
        if (capped == 0) fail("no cell passed its " + per + " lights: the cluster is not dense enough to test the cap");
    }

    /** Whether a cell's list is its lights in index order, the first PER_CELL of them, borderline ones either way. */
    private static boolean sameCell(int[] got, List<Integer> must, List<Integer> may, int per) {
        int last = -1;
        for (int g : got) {
            if (g <= last) return false;
            last = g;
            if (!must.contains(g) && !may.contains(g)) return false;
        }
        // Every light it must hold and could fit, before its list filled, is there.
        int highest = got.length == per ? got[got.length - 1] : Integer.MAX_VALUE;
        for (int m : must) {
            if (m > highest) continue;
            boolean found = false;
            for (int g : got) found |= g == m;
            if (!found) return false;
        }
        return true;
    }

    /** ComputeCellViewAABB: the tile's unit-depth rays at both depths. */
    private float[] box(int x, int y, float d0, float d1) {
        float u0 = x * tileU, u1 = (x + 1) * tileU, v0 = y * tileV, v1 = (y + 1) * tileV;
        float rx0 = rays[0] + (rays[2] - rays[0]) * u0, rx1 = rays[0] + (rays[2] - rays[0]) * u1;
        float ry0 = rays[1] + (rays[3] - rays[1]) * v0, ry1 = rays[1] + (rays[3] - rays[1]) * v1;
        float loX = Math.min(Math.min(rx0 * d0, rx1 * d0), Math.min(rx0 * d1, rx1 * d1));
        float hiX = Math.max(Math.max(rx0 * d0, rx1 * d0), Math.max(rx0 * d1, rx1 * d1));
        float loY = Math.min(Math.min(ry0 * d0, ry1 * d0), Math.min(ry0 * d1, ry1 * d1));
        float hiY = Math.max(Math.max(ry0 * d0, ry1 * d0), Math.max(ry0 * d1, ry1 * d1));
        return new float[]{(loX + hiX) * 0.5f, (loY + hiY) * 0.5f, -(d0 + d1) * 0.5f, (hiX - loX) * 0.5f, (hiY - loY) * 0.5f,
                (d1 - d0) * 0.5f};
    }

    /** ComputeSquaredDistanceFromBoxToPoint. */
    private static float gap(float[] box, float x, float y, float z) {
        float dx = Math.max(Math.abs(x - box[0]) - box[3], 0f), dy = Math.max(Math.abs(y - box[1]) - box[4], 0f);
        float dz = Math.max(Math.abs(z - box[2]) - box[5], 0f);
        return dx * dx + dy * dy + dz * dz;
    }

    private float sliceDepth(int z) {
        if (z <= 0) return 0f;
        if (z >= CgWorldLights.SLICES) return 2.0e6f;
        return (float) ((Math.pow(2, z / zParams[2]) - zParams[1]) / zParams[0]);
    }

    private float light(int i, int word) {
        return list.getFloat((i * CgWorldLights.FLOATS + word) * 4);
    }

    private static float srgb(int c) {
        float v = c / 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }

    private void near(String what, float got, float want) {
        checked++;
        if (!(Math.abs(got - want) <= 1e-4f * Math.max(1f, Math.abs(want)))) fail(what + " is " + got + ", not " + want);
    }

    private void fail(String why) {
        if (failure == null) failure = why;
    }

    private static ByteBuffer copy(ByteBuffer data) {
        ByteBuffer out = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
        out.put(data.duplicate().order(ByteOrder.nativeOrder()));
        return out.flip();
    }

    private static String on() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        return (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
    }

    private void report() {
        String on = on();
        int validation = PlatformServiceHarness.validationErrors();
        String unanswered = null;
        for (CgRequest r : requests) if (!r.done() && unanswered == null) unanswered = r + (r.failure() != null ? ": " + r.failure() : "");
        if (GlErrorChecker.checkAndLog("lights-grid")) {
            System.out.println("[lights-grid] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[lights-grid] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (unanswered != null) {
            System.out.println("[lights-grid] FAIL on " + on + ": unanswered " + unanswered);
        } else if (on.startsWith("recording")) {
            System.out.println("[lights-grid] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[lights-grid] FAIL on " + on + ": " + failure);
        } else {
            System.out.printf("[lights-grid] PASS on %s: %d light words, %d cells (%d past the cap of %d)%n", on, checked,
                    cellsChecked, capped, CgWorldLights.PER_CELL);
        }
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return true; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
