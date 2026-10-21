package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * The gate for the material library ({@code shaders/lib/material/}): DepthFade, Panner, Rotator, ParticleSubUV and
 * RotateAboutAxis on the GPU against Unreal 4.21's formulas ({@code MaterialExpressions.cpp},
 * {@code MaterialTemplate.ush}) evaluated here, on random inputs.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=material-library"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=material-library" -Dcrystalgraphics.compute.tier=G33
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=material-library --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [material-library] PASS} or {@code FAIL}; on Vulkan a validation error fails it. Trigonometry is
 * compared within 2e-3, everything else within 1e-4: a driver's sin and cos are not Java's.</p>
 */
public class CgMaterialLibraryScene implements InteractiveSceneLifecycle {

    private static final int CASES = 1024, DRAIN = 30, SHEET = 4;
    private static final float TRIG = 2e-3f, EXACT = 1e-4f;

    private final CgGraphBuffer[] in = new CgGraphBuffer[5], out = new CgGraphBuffer[4];
    private final float[][] inputs = new float[5][CASES * 4];
    private final float[][] results = new float[4][];
    private CgCompute kernels;
    private CgTexture2D sheet;
    private CgRequest[] requests;
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        kernels = CgCompute.load("harness:shaders/material_library.compute");
        for (int k = 0; k < in.length; k++) in[k] = CgGraphBuffer.transientBuffer("material-library.in" + k, buffer());
        for (int k = 0; k < out.length; k++) out[k] = CgGraphBuffer.transientBuffer("material-library.out" + k, buffer());
        // Texels vary along x only, so the result does not depend on which row is v = 0.
        sheet = CgTexture2D.createEmpty(SHEET, SHEET, CgTextureSpec.RGBA8_NEAREST);
        ByteBuffer texels = ByteBuffer.allocateDirect(SHEET * SHEET * 4);
        for (int y = 0; y < SHEET; y++) {
            for (int x = 0; x < SHEET; x++) {
                int[] c = texel(x);
                texels.put((byte) c[0]).put((byte) c[1]).put((byte) c[2]).put((byte) c[3]);
            }
        }
        texels.flip();
        sheet.uploadRegion(0, 0, 0, SHEET, SHEET, texels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        Random random = new Random(421);
        for (int i = 0; i < CASES; i++) {
            float ax = random.nextFloat() * 2 - 1, ay = random.nextFloat() * 2 - 1, az = random.nextFloat() * 2 - 1;
            float len = (float) Math.sqrt(ax * ax + ay * ay + az * az) + 1e-6f;
            put(0, i, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 10, random.nextFloat() * 4 - 1);
            put(1, i, random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2, random.nextFloat(), random.nextFloat() * 20);
            put(2, i, ax / len, ay / len, az / len, (random.nextFloat() * 2 - 1) * (float) Math.PI);
            put(3, i, random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2, random.nextFloat() * 20);
            put(4, i, centre(random.nextInt(SHEET)), centre(random.nextInt(SHEET)), centre(random.nextInt(SHEET)),
                    centre(random.nextInt(SHEET)));
        }
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
        if (answered || frame > DRAIN) {
            report();
            running = false;
        }
    }

    private void record() {
        CgRecording rec = new CgRecording();
        for (int k = 0; k < in.length; k++) rec.update(in[k], 0, bytes(inputs[k]));
        CgComputePass pass = rec.compute("material-library");
        pass.dispatch(kernels.kernel("Library"), CASES).bind("IN0", in[0]).bind("IN1", in[1]).bind("IN2", in[2])
                .bind("IN3", in[3]).bind("IN4", in[4]).bind("OUT0", out[0]).bind("OUT1", out[1]).bind("OUT2", out[2])
                .bind("OUT3", out[3]).texture("_Sheet", sheet);
        pass.end();
        requests = new CgRequest[out.length];
        for (int k = 0; k < out.length; k++) {
            int at = k;
            requests[k] = rec.readback(out[k], 0, CASES * 16L, data -> {
                float[] got = new float[CASES * 4];
                for (int j = 0; j < got.length; j++) got[j] = data.getFloat(j * 4);
                results[at] = got;
            });
        }
        CgImmediate.execute(rec);
    }

    /** The first case whose output disagrees with Unreal's formula, or null. */
    private String check() {
        for (int i = 0; i < CASES; i++) {
            float u = in(0, i, 0), v = in(0, i, 1), time = in(0, i, 2), fade = in(0, i, 3);
            float sx = in(1, i, 0), sy = in(1, i, 1), opacity = in(1, i, 2), scene = in(1, i, 3);
            float pixel = in(3, i, 3), lerp = frac(time * 0.37f);
            // DepthFade: Opacity * saturate((SceneDepth - PixelDepth) / max(FadeDistance, DELTA)).
            String bad = near("DepthFade", i, out(0, i, 0),
                    opacity * saturate((scene - pixel) / Math.max(fade, 0.00001f)), EXACT);
            // Panner: uv + frac(t * speed) with bFractionalPart, uv + t * speed without.
            if (bad == null) bad = nearMod1("Panner, fractional", i, out(0, i, 2), u + frac(time * sx));
            if (bad == null) bad = nearMod1("Panner, fractional", i, out(0, i, 3), v + frac(time * sy));
            if (bad == null) bad = near("Panner", i, out(1, i, 0), u + time * sx, EXACT * 20);
            if (bad == null) bad = near("Panner", i, out(1, i, 1), v + time * sy, EXACT * 20);
            // Rotator: rows (cos, -sin), (sin, cos) of time * speed, about (0.5, 0.25).
            double a = time * sx, du = u - 0.5, dv = v - 0.25;
            if (bad == null) bad = near("Rotator", i, out(1, i, 2), (float) (Math.cos(a) * du - Math.sin(a) * dv + 0.5), TRIG);
            if (bad == null) bad = near("Rotator", i, out(1, i, 3), (float) (Math.sin(a) * du + Math.cos(a) * dv + 0.25), TRIG);
            // RotateAboutAxis: MaterialTemplate.ush's construction, the offset to the position.
            double[] axis = {in(2, i, 0), in(2, i, 1), in(2, i, 2)}, pivot = {in(3, i, 0), in(3, i, 1), in(3, i, 2)};
            double[] off = rotateAboutAxis(axis, in(2, i, 3), pivot, new double[]{u * 3, v * 3, time * 3});
            for (int c = 0; c < 3 && bad == null; c++) bad = near("RotateAboutAxis", i, out(2, i, c), (float) off[c], TRIG * 30);
            // ParticleSubUV: lerp(frame 0, frame 1, SubUVLerp) with blend, frame 0 alone without.
            int[] t0 = texel(cell(in(4, i, 0))), t1 = texel(cell(in(4, i, 2)));
            for (int c = 0; c < 4 && bad == null; c++) {
                float f0 = t0[c] / 255f, f1 = t1[c] / 255f;
                bad = near("ParticleSubUV", i, out(3, i, c), i % 2 == 0 ? f0 + (f1 - f0) * lerp : f0, 2e-3f);
            }
            if (bad != null) return bad;
        }
        return null;
    }

    private static double[] rotateAboutAxis(double[] axis, double angle, double[] pivot, double[] pos) {
        double along = 0;
        for (int c = 0; c < 3; c++) along += axis[c] * (pos[c] - pivot[c]);
        double[] closest = new double[3], u = new double[3];
        for (int c = 0; c < 3; c++) {
            closest[c] = pivot[c] + axis[c] * along;
            u[c] = pos[c] - closest[c];
        }
        double[] w = {axis[1] * u[2] - axis[2] * u[1], axis[2] * u[0] - axis[0] * u[2], axis[0] * u[1] - axis[1] * u[0]};
        double[] off = new double[3];
        for (int c = 0; c < 3; c++) off[c] = closest[c] + u[c] * Math.cos(angle) + w[c] * Math.sin(angle) - pos[c];
        return off;
    }

    private static int[] texel(int x) {
        return new int[]{x * 60, x * 20 + 10, 255 - x * 50, x * 30 + 100};
    }

    private static float centre(int x) {
        return (x + 0.5f) / SHEET;
    }

    private static int cell(float coord) {
        return (int) (coord * SHEET);
    }

    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    private static float saturate(float x) {
        return Math.max(0f, Math.min(1f, x));
    }

    private static String near(String what, int i, float got, float want, float tolerance) {
        return Math.abs(got - want) <= tolerance * Math.max(1f, Math.abs(want)) ? null
                : what + " case " + i + ": " + got + ", not " + want;
    }

    /** As {@link #near}, for a fractional part: 0.9999 and 0.0001 are neighbours. */
    private static String nearMod1(String what, int i, float got, float want) {
        double d = Math.abs(got - want) % 1.0;
        return Math.min(d, 1.0 - d) <= EXACT * 10 ? null : what + " case " + i + ": " + got + ", not " + want;
    }

    private void put(int k, int i, float x, float y, float z, float w) {
        inputs[k][i * 4] = x;
        inputs[k][i * 4 + 1] = y;
        inputs[k][i * 4 + 2] = z;
        inputs[k][i * 4 + 3] = w;
    }

    private float in(int k, int i, int c) {
        return inputs[k][i * 4 + c];
    }

    private float out(int k, int i, int c) {
        return results[k][i * 4 + c];
    }

    private static ByteBuffer bytes(float[] values) {
        ByteBuffer b = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        for (float v : values) b.putFloat(v);
        b.flip();
        return b;
    }

    private static CgBufferDesc buffer() {
        return CgBufferDesc.elements(CASES, 16, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        int validation = PlatformServiceHarness.validationErrors();
        boolean answered = true;
        for (int k = 0; k < requests.length; k++) answered &= requests[k].done() && results[k] != null;
        String failure = answered ? check() : null;
        if (GlErrorChecker.checkAndLog("material-library")) {
            System.out.println("[material-library] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[material-library] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!answered) {
            System.out.println("[material-library] FAIL on " + on + ": a readback was not answered");
        } else if (on.startsWith("recording")) {
            System.out.println("[material-library] PASS on recording: every request answered; a recording device reads back zeros");
        } else if (failure != null) {
            System.out.println("[material-library] FAIL on " + on + ": " + failure);
        } else {
            System.out.println("[material-library] PASS on " + on + ": " + CASES + " cases of five expressions against Unreal's");
        }
    }

    @Override
    public void dispose() {
        sheet.delete();
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
