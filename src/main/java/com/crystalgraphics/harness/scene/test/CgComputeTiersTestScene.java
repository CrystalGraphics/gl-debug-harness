package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.compute.CgComputeSelfTest;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;

/**
 * gpu-compute's gate: the engine's compute self-test ({@link CgComputeSelfTest}), a kernel of every shape with every
 * result checked against values worked out in Java, on GL and on the Vulkan device.
 *
 * <p>Prints {@code [compute-tiers] PASS} or {@code FAIL} with the tier and every value that differs; on the Vulkan
 * device a validation error fails it too.</p>
 */
public class CgComputeTiersTestScene implements HarnessSceneLifecycle {

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgComputeSelfTest.Result result = CgComputeSelfTest.run();
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + result.tier();
        int validation = PlatformServiceHarness.validationErrors();
        if (result.passed() && validation == 0) {
            System.out.println("[compute-tiers] PASS on " + on + ": every buffer and image as worked out");
            return;
        }
        System.out.println("[compute-tiers] FAIL on " + on + ": " + result.failures().size() + " differences");
        for (String f : result.failures()) System.out.println("[compute-tiers]   " + f);
        if (validation > 0) System.out.println("[compute-tiers]   " + validation + " Vulkan validation errors, logged above");
    }

    @Override
    public void dispose() {
    }
}
