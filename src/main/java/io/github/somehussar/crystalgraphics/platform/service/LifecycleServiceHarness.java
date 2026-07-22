package io.github.somehussar.crystalgraphics.platform.service;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.service.CgLifecycleService;


public final class LifecycleServiceHarness implements CgLifecycleService {

    @Override public void onContextInit(int w, int h) { CgGraphicsLifecycle.initContext(w, h); }
    @Override public void onContextDestroy()          { CgGraphicsLifecycle.destroyContext(); }
    @Override public void onResize(int w, int h)      { CgGraphicsLifecycle.onResize(w, h); }
    @Override public void onFrameRendered()           { CgGraphicsLifecycle.tickFrame(); }
}
