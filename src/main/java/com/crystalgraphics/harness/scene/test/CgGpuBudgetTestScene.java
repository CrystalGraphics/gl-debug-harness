package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * The gate for {@code CgGpuBudget} (gpu-compute C11): each frame a compute pass of fills charged to a budget. The pass is
 * measured at its full size and at a tenth, which gives its fixed cost and the part that scales, as any effect has both;
 * then the budget becomes the fixed cost and a third of the rest, the fills take as many words as the budget's scale
 * allows, and the scale must bring the pass inside the budget and hold it there.
 *
 * <p>Prints {@code [gpu-budget] PASS} with the cost, the budget and where it held, or {@code FAIL}. Without timer
 * queries there is nothing to hold, and it says so. On a downlevel run llvmpipe's timings are the CPU's and swing by
 * half from run to run, so only that the scale followed the budget down is judged there.</p>
 */
public class CgGpuBudgetTestScene implements InteractiveSceneLifecycle {

    private static final int MEASURE = 80, HOLD = 420, JUDGE = 120;
    private static CgGpuBudget budget;

    private CgGraphBuffer words;
    private int fullWords, fills, frame, changes;
    private float full, low, target, lastScale = -1f;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        if (budget == null) budget = CgGpuBudget.define("harness.gpu-budget", 1000f);
        CgCapabilities.ComputeTier tier = CgCapabilities.detect().computeTier();
        boolean compute = tier == CgCapabilities.ComputeTier.V || tier == CgCapabilities.ComputeTier.G43;
        fullWords = compute ? 4 << 20 : 256 << 10;
        fills = compute ? 16 : 4;
        words = CgGraphBuffer.persistent("budget.words", CgBufferDesc.elements(fullWords, 4, CgBufferUsage.STORAGE));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        float scale = budget.scale();
        CgRecording rec = new CgRecording();
        CgComputePass pass = rec.compute("budget.work").timed(budget);
        int n = frame <= MEASURE ? fullWords : frame <= 2 * MEASURE ? fullWords / 10 : Math.max(1, Math.round(fullWords * scale));
        for (int k = 0; k < fills; k++) CgGpuOps.fill(pass, words, k, CgGpuCount.of(n));
        pass.end();
        CgImmediate.execute(rec);

        if (frame == MEASURE) {
            full = budget.spent();
        } else if (frame == 2 * MEASURE) {
            low = budget.spent();
            float scaled = (full - low) * 10f / 9f;
            target = full - scaled + scaled / 3f;
            budget.millis(target);
        } else if (frame > 2 * MEASURE + HOLD - JUDGE) {
            if (lastScale >= 0f && scale != lastScale) changes++;
            lastScale = scale;
        }
        if (frame == 2 * MEASURE + HOLD) {
            report(scale);
            running = false;
        }
    }

    private void report(float scale) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        float spent = budget.spent();
        String figures = String.format("%.3f ms at full size and %.3f at a tenth, budget %.3f ms, held at %.3f ms at"
                + " scale %.2f, %d changes in the last %d frames", full, low, target, spent, scale, changes, JUDGE);
        if (CgGpuTrace.support() == CgGpuTrace.Support.UNSUPPORTED) {
            System.out.println("[gpu-budget] PASS on " + on + ": no timer queries, so the scale stays at its start");
        } else if (!(full > 0f) || !(low > 0f)) {
            System.out.println("[gpu-budget] FAIL on " + on + ": nothing measured in " + MEASURE + " frames");
        } else if (System.getProperty("crystalgraphics.harness.downlevel") != null) {
            boolean followed = scale >= 0.15f && scale <= 0.7f;
            System.out.println("[gpu-budget] " + (followed ? "PASS" : "FAIL") + " on " + on + ": the scale followed the"
                    + " budget down; llvmpipe times the CPU, so holding is not judged: " + figures);
        } else if (spent > target * 1.10f || spent < target * 0.5f || changes > 2) {
            System.out.println("[gpu-budget] FAIL on " + on + ": " + figures);
        } else {
            System.out.println("[gpu-budget] PASS on " + on + ": " + figures);
        }
    }

    @Override
    public void dispose() {
        budget.millis(1000f);
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
