package io.github.somehussar.crystalgraphics.platform;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;
import com.crystalgraphics.lwjgl3.GlfwInputService;
import com.crystalgraphics.lwjgl3.Lwjgl3GLBackend;
import com.crystalgraphics.lwjgl3.Lwjgl3GLContext;
import io.github.somehussar.crystalgraphics.harness.runtime.HarnessWindow;
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
    public final Lwjgl3GLBackend glDispatchImpl = new Lwjgl3GLBackend();
    public final Lwjgl3GLContext glContextImpl = new Lwjgl3GLContext();
    public final GlfwInputService inputImpl = new GlfwInputService(HarnessWindow::handle);
    /**
     * The harness has no audio backend, so this is the "empty method body" case the platform SPI expects
     * rather than a shared no-op borrowed from it — there is deliberately no {@code CgSoundService.NOOP}.
     */
    public final CgSoundService soundImpl = soundId -> {};

    @Override public CgGLBackend       gl()           { return glDispatchImpl; }
    @Override public CgGLContext         capabilities() { return glContextImpl; }
    @Override public CgResourceService  resources()    { return resourceImpl; }
    @Override public CgRenderingService rendering()    { return renderingImpl; }
    @Override public CgLifecycleService lifecycle()    { return lifecycleImpl; }
    @Override public CgReloadService    reload()       { return reloadImpl; }
    @Override public CgInputService     input()        { return inputImpl; }
    @Override public CgSoundService     sound()        { return soundImpl; }
    
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * FML preInit phase. Constructs and registers all platform services with {@link CgPlatform}.
     * Safe to call before any GL context exists.
     */
    public static void onPreInit() {
        CgPlatform.register(PlatformServiceHarness.getInstance());
        // NATIVE CONTENT IS NOT DECLARED HERE, and it used to be.
        //
        // `com.crystalgui.ui.elements.slot` lives only on core's `native-content-slots` branch, which
        // was never merged to master -- so this branch of the harness has not compiled against master
        // core since it was written, and `cgui-slot` went with it. The declaration is worth restoring
        // the moment that branch lands: a native-content slot refuses to paint on a platform that
        // never said whether it renders items, and the harness is genuinely such a platform, so
        // saying so out loud is what separates it from a loader that forgot.
    }

}
