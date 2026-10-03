package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.compute.CgComputeSelfTest;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;

/**
 * gpu-compute's tier gate: the engine's compute self-test ({@link CgComputeSelfTest}), a kernel of every shape with every
 * result checked against values worked out in Java. Run it at each tier a context can be forced to,
 * {@code -Dcrystalgraphics.compute.tier=G43|G40|G33|CPU}, and on the downlevel contexts, which pick their own.
 *
 * <p>Prints {@code [compute-tiers] PASS} or {@code FAIL} with the tier, each kernel's form, and every value that
 * differs; on the Vulkan device a validation error fails it too.</p>
 */
public class CgComputeTiersTestScene implements HarnessSceneLifecycle {

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgComputeSelfTest.Result result = CgComputeSelfTest.run();
        System.out.println("[compute-tiers] tier " + result.tier() + ":" + result.forms());
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
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
