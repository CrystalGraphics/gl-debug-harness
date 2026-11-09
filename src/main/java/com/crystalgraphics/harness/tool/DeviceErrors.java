package com.crystalgraphics.harness.tool;

import com.crystalgraphics.harness.runtime.HarnessWindow;

import java.util.List;
import java.util.logging.Logger;

/**
 * Whether the device reported an error since the last ask: on GL its context's error queue, drained by the host's
 * window; on Vulkan nothing here, since its validation messages are counted as they arrive
 * ({@code PlatformServiceHarness.validationErrors()}), which a gate reads beside this.
 *
 * <pre>{@code
 * boolean errors = DeviceErrors.checkAndLog("lights-sun");
 * if (errors) failure = "device errors, logged above";
 * }</pre>
 */
public final class DeviceErrors {

    private static final Logger LOGGER = Logger.getLogger(DeviceErrors.class.getName());

    private DeviceErrors() {}

    /** Logs each error pending, named after {@code context}; true when there was one. */
    public static boolean checkAndLog(String context) {
        List<String> errors = HarnessWindow.drainGlErrors();
        for (String error : errors) LOGGER.warning("[DeviceErrors] " + context + ": " + error);
        return !errors.isEmpty();
    }
}
