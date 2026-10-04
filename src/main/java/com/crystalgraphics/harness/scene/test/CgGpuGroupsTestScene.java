package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Map;

/**
 * The gate for GPU groups (render-distortion T1): one timed raster pass drawing three materials whose fragment loops
 * run 1 : 2 : 4, with {@code crystalgraphics.gpu.groups} on, then the same pass with it off.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=gpu-groups"
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=gpu-groups --device=vulkan"
 * </pre>
 *
 * <ul>
 *   <li>PASS needs each material's counter in every measured frame, their sum within 5% of the pass's zone, their
 *       order by cost, and with the channel off no group counter and the zone within 15% of its figure with it on.</li>
 *   <li>{@code -Dcrystalgraphics.harness.gpuGroups.quads} is how many full-target quads each material draws (6).</li>
 * </ul>
 */
public class CgGpuGroupsTestScene implements InteractiveSceneLifecycle {

    private static final int WARMUP = 30, MEASURED = 120, DRAIN = 30;
    private static final int W = 1920, H = 1080;
    private static final String ON = "gpu-groups.on", OFF = "gpu-groups.off";
    private static final String[] SHADERS = {"gpu_groups_1", "gpu_groups_2", "gpu_groups_4"};
    private static final CgFrameBufferFormat FORMAT = CgFrameBufferFormat.builder("gpu-groups")
            .color(0, CgTextureType.RGBA16F).build();

    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgRecording recording = new CgRecording();
    private final CgMaterial[] materials = new CgMaterial[SHADERS.length];
    private final int onZone = CgGpuTrace.name(ON), offZone = CgGpuTrace.name(OFF);
    private CgFrameBuffer fb;
    private CgGraphTexture target;
    private int quads, frame;
    private boolean wasGpu, wasGroups, running = true;

    @Override
    public void init(HarnessContext ctx) {
        quads = Integer.getInteger("crystalgraphics.harness.gpuGroups.quads", 6);
        for (int i = 0; i < SHADERS.length; i++) {
            materials[i] = CgMaterial.newInstance("assets/harness/shader/" + SHADERS[i] + ".shader");
        }
        fb = CgFrameBuffer.createOwned("gpu-groups", W, H, FORMAT, 1);
        target = CgGraphTexture.imported("gpu-groups", fb);
        wasGpu = CgTrace.isEnabled(CgGpuTrace.GPU);
        wasGroups = CgTrace.isEnabled(CgChannels.GPU_GROUPS);
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        CgGpuTrace.collect();
        int on = WARMUP + MEASURED, off = on + WARMUP + MEASURED;
        if (frame == WARMUP) CgGpuTrace.resetTotals();
        if (frame < on) {
            CgTrace.setEnabled(CgChannels.GPU_GROUPS, true);
            draw(onZone);
        } else if (frame < off) {
            CgTrace.setEnabled(CgChannels.GPU_GROUPS, false);
            draw(offZone);
        } else if (frame >= off + DRAIN || landed()) {
            report();
            running = false;
        }
        frame++;
    }

    private void draw(int zone) {
        recording.reset();
        CgRasterPass pass = recording.raster(target, CgLoad.clear(0, 0, 0, 0), new CgPassConstants().resolution(W, H),
                null, CgOrder.LOOKBACK).timed(zone);   // full-target draws all overlap, so stay in this order
        for (CgMaterial material : materials) {
            CgChunkBuilder c = recording.chunks().begin();
            c.draw(material.pipeline(CgInstanceKind.OBJECT), material.captureBindings(recording.bindings()), CgMesh.quads(1));
            for (int q = 0; q < quads; q++) c.instance();
            pass.add(c.end());
        }
        pass.end();
        CgFrame built = builder.build(graph.add(recording.seal()));
        graph.clear();
        CgExecutor.execute(built, true);
        builder.recycle(built);
    }

    private static boolean landed() {
        long[] off = CgGpuTrace.totals().get(OFF);
        return off != null && off[1] >= WARMUP + MEASURED;
    }

    private void report() {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? CgGL.glGetString(CgGL.GL_RENDERER) : device.name();
        Map<String, long[]> totals = CgGpuTrace.totals();
        double zoneOn = mean(totals.get(ON)), zoneOff = mean(totals.get(OFF));
        double[] group = new double[SHADERS.length];
        long fewest = Long.MAX_VALUE;
        double sum = 0;
        boolean offGroups = false;
        for (Map.Entry<String, long[]> e : totals.entrySet()) {
            if (e.getKey().startsWith(OFF + CgGpuTrace.GROUP)) offGroups = true;
            for (int i = 0; i < SHADERS.length; i++) {
                if (e.getKey().startsWith(ON + CgGpuTrace.GROUP) && e.getKey().endsWith(SHADERS[i] + ".shader")) {
                    group[i] = mean(e.getValue());
                    fewest = Math.min(fewest, e.getValue()[1]);
                }
            }
        }
        for (int i = 0; i < SHADERS.length; i++) {
            sum += group[i];
            System.out.printf("[gpu-groups]   %-14s gpu %7.3f ms (%.2fx the first)%n", SHADERS[i], group[i], group[i] / group[0]);
        }
        System.out.printf("[gpu-groups]   groups sum %.3f ms, the pass %.3f ms (%+.1f%%); the pass with groups off %.3f ms%n",
                sum, zoneOn, 100 * (sum - zoneOn) / zoneOn, zoneOff);

        String fail = null;
        if (GlErrorChecker.checkAndLog("gpu-groups")) fail = "GL errors, logged above";
        else if (PlatformServiceHarness.validationErrors() > 0) fail = "Vulkan validation errors, logged above";
        else if (fewest == Long.MAX_VALUE || fewest < MEASURED) fail = "a group missing from a measured frame (fewest " + fewest + ")";
        else if (Math.abs(sum - zoneOn) > 0.05 * zoneOn) fail = "the groups do not sum to the pass";
        else if (!(group[0] < group[1] && group[1] < group[2])) fail = "the groups are not in order of cost";
        else if (offGroups) fail = "group counters with the channel off";
        else if (Math.abs(zoneOff - zoneOn) > 0.15 * zoneOn) fail = "the pass's figure moved with the channel off";
        System.out.println(fail == null ? "[gpu-groups] PASS on " + on : "[gpu-groups] FAIL on " + on + ": " + fail);
    }

    private static double mean(long[] total) {
        return total == null || total[1] == 0 ? Double.NaN : total[0] / 1e6 / total[1];
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        CgTrace.setEnabled(CgGpuTrace.GPU, wasGpu);
        CgTrace.setEnabled(CgChannels.GPU_GROUPS, wasGroups);
        fb.delete();
    }
}
