package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.compute.ops.CgGpuOpsCheck;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;

/**
 * gpu-compute C7's gate: {@link CgGpuOpsCheck}, every {@code CgGpuOps} op at counts from none to 70,000, fixed and read
 * from the GPU, each answer checked bit for bit against Java. Run it at each tier a context can be forced to,
 * {@code -Dcrystalgraphics.compute.tier=G43|G40|G33|CPU}, and on the downlevel contexts.
 *
 * <p>Prints {@code [gpu-ops] PASS} or {@code FAIL} with every answer that differs; on the Vulkan device a validation
 * error fails it too.</p>
 */
public class CgGpuOpsTestScene implements HarnessSceneLifecycle {

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        long start = System.nanoTime();
        CgGpuOpsCheck.Result result = CgGpuOpsCheck.run();
        long ms = (System.nanoTime() - start) / 1_000_000;
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + result.tier();
        int validation = PlatformServiceHarness.validationErrors();
        if (result.passed() && validation == 0) {
            System.out.println("[gpu-ops] PASS on " + on + ": every op as worked out (" + ms + " ms)");
            return;
        }
        System.out.println("[gpu-ops] FAIL on " + on + ": " + result.failures().size() + " differences");
        for (String f : result.failures()) System.out.println("[gpu-ops]   " + f);
        if (validation > 0) System.out.println("[gpu-ops]   " + validation + " Vulkan validation errors, logged above");
    }

    @Override
    public void dispose() {
    }
}
