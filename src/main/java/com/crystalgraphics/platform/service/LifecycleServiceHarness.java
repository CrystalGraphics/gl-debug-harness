package com.crystalgraphics.platform.service;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;


public final class LifecycleServiceHarness implements CgLifecycleService {

    private static final int TRACKED_END = CgTrace.name("frame.trackedEnd");

    @Override public void onContextInit(int w, int h) { CgGraphicsLifecycle.initContext(w, h); }
    @Override public void onContextDestroy()          { CgGraphicsLifecycle.destroyContext(); }
    @Override public void onResize(int w, int h)      { CgGraphicsLifecycle.onResize(w, h); }
    @Override public void onFrameRendered() {
        CgGraphicsLifecycle.tickFrame();
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, TRACKED_END)) {
            PlatformServiceHarness.endTrackedFrame();
        }
    }
}
