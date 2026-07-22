package io.github.somehussar.crystalgraphics.platform;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import io.github.somehussar.crystalgraphics.platform.gl.Lwjgl2GLBackend;
import io.github.somehussar.crystalgraphics.platform.gl.Lwjgl2GLContext;
import io.github.somehussar.crystalgraphics.platform.service.LifecycleServiceHarness;
import io.github.somehussar.crystalgraphics.platform.service.ReloadServiceHarness;
import io.github.somehussar.crystalgraphics.platform.service.RenderingServiceHarness;
import io.github.somehussar.crystalgraphics.platform.service.ResourceServiceHarness;

/**
 * Complete MC 1.7.10 platform bundle. Implements {@link CgPlatformService} by composing
 * the six mc1710 service adapters. Register via {@code CgPlatform.register(new PlatformService1710())}.
 *
 * <p>{@link RenderingServiceHarness} and {@link LifecycleServiceHarness} instances are exposed
 * via package-visible accessors if needed.</p>
 */
public final class PlatformServiceHarness implements CgPlatformService {
    
    // ── Singleton ─────────────────────────────────────────────────────────────
    private static PlatformServiceHarness INSTANCE;
    
    public static void init() { if (INSTANCE != null) return; INSTANCE = new PlatformServiceHarness();}
    public static PlatformServiceHarness getInstance() { if (INSTANCE == null) init(); return INSTANCE;}
    
    // ── Services ─────────────────────────────────────────────────────────────

    public final RenderingServiceHarness renderingImpl = new RenderingServiceHarness();
    public final LifecycleServiceHarness lifecycleImpl = new LifecycleServiceHarness();
    public final ResourceServiceHarness resourceImpl = new ResourceServiceHarness();
    public final ReloadServiceHarness reloadImpl = new ReloadServiceHarness();
    public final Lwjgl2GLBackend glDispatchImpl = new Lwjgl2GLBackend();
    public final Lwjgl2GLContext glContextImpl = new Lwjgl2GLContext();

    @Override public CgGLBackend       gl()           { return glDispatchImpl; }
    @Override public CgGLContext         capabilities() { return glContextImpl; }
    @Override public CgResourceService  resources()    { return resourceImpl; }
    @Override public CgRenderingService rendering()    { return renderingImpl; }
    @Override public CgLifecycleService lifecycle()    { return lifecycleImpl; }
    @Override public CgReloadService    reload()       { return reloadImpl; }
    
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * FML preInit phase. Constructs and registers all platform services with {@link CgPlatform}.
     * Safe to call before any GL context exists.
     */
    public static void onPreInit() {
        CgPlatform.register(PlatformServiceHarness.getInstance());
    }

}
