package com.crystalgraphics.harness.util;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.harness.config.HarnessContext;

import java.util.logging.Logger;

public  class HarnessDiagnostics {

    private static final Logger LOGGER = Logger.getLogger(HarnessDiagnostics.class.getName());

    public static void logStartup(HarnessContext ctx) {
        LOGGER.info("=== CrystalGraphics Debug Harness ===");
        LOGGER.info("[Harness] GL Version:  " + ctx.getGlVersion());
        LOGGER.info("[Harness] GL Vendor:   " + ctx.getGlVendor());
        LOGGER.info("[Harness] GL Renderer: " + ctx.getGlRenderer());

        try {
            CgCapabilities caps = CgCapabilities.detectUncached();
            LOGGER.info("[Harness] ShaderBufs:  " + caps.shaderBufferPath());
            LOGGER.info("[Harness] StreamBufferTier:  " + caps.vertexStreamTier());
            LOGGER.info("[Harness] MaxTexSize:  " + caps.getMaxTextureSize());
            LOGGER.info("[Harness] MaxDrawBuf:  " + caps.getMaxDrawBuffers());
        } catch (Exception e) {
            LOGGER.warning("[Harness] Could not detect CgCapabilities: " + e.getMessage());
        }

        String nativeLibPath = System.getProperty("java.library.path", "(not set)");
        LOGGER.info("[Harness] java.library.path: " + nativeLibPath);

        String ftNativePath = System.getProperty("freetype.harfbuzz.native.path", "(not set)");
        LOGGER.info("[Harness] freetype.harfbuzz.native.path: " + ftNativePath);
    }

    private HarnessDiagnostics() { }
}
