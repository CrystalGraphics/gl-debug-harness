package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.harness.config.HarnessContext;

/** The harness's {@link CgRenderingService}: the window's viewport. */
public final class RenderingServiceHarness implements CgRenderingService {


    @Override public int getDisplayWidth()  { return HarnessContext.getInstance().getScreenWidth(); }
    @Override public int getDisplayHeight() { return HarnessContext.getInstance().getScreenHeight(); }
}
