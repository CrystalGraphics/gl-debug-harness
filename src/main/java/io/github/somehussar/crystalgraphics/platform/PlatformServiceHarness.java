package io.github.somehussar.crystalgraphics.platform;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;
import io.github.somehussar.crystalgraphics.harness.util.Lwjgl2CursorService;
import io.github.somehussar.crystalgraphics.platform.gl.Lwjgl2GLBackend;
import io.github.somehussar.crystalgraphics.platform.gl.Lwjgl2GLContext;
import io.github.somehussar.crystalgraphics.platform.input.InputAdapter;
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
 *
 * <h3>Sound is a mutable field, and the input adapter carries a swappable clipboard</h3>
 * <p>{@link CgPlatform} reads services only through the registered bundle and has no per-service setter,
 * which is right for a real loader — it has everything to hand at once, and half-registration is the
 * failure mode that shape rules out. Harness scenes are the awkward case: {@code CgUiButtonScene} counts
 * sound calls, {@code CgUiTextFieldScene} wants the AWT system clipboard. Since there is exactly one
 * bundle and it is a singleton, those scenes reach in and set the piece they care about via
 * {@link #getInstance()} rather than standing up a bundle of their own.</p>
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
    public final InputAdapter inputImpl = new InputAdapter();
    public final Lwjgl2CursorService cursorImpl = new Lwjgl2CursorService();
    /**
     * Swappable — see the class javadoc. Silent unless a scene installs a counter.
     *
     * <p>The harness has no audio backend at all, so this is the "empty method body" case the platform
     * SPI expects rather than a shared no-op borrowed from it — there is deliberately no
     * {@code CgSoundService.NOOP} to reach for.</p>
     */
    public CgSoundService soundImpl = soundId -> {};

    @Override public CgGLBackend       gl()           { return glDispatchImpl; }
    @Override public CgGLContext         capabilities() { return glContextImpl; }
    @Override public CgResourceService  resources()    { return resourceImpl; }
    @Override public CgRenderingService rendering()    { return renderingImpl; }
    @Override public CgLifecycleService lifecycle()    { return lifecycleImpl; }
    @Override public CgReloadService    reload()       { return reloadImpl; }
    @Override public CgInputService     input()        { return inputImpl; }
    @Override public CgSoundService     sound()        { return soundImpl; }
    @Override public CgCursorService    cursor()       { return cursorImpl; }
    
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * FML preInit phase. Constructs and registers all platform services with {@link CgPlatform}.
     * Safe to call before any GL context exists.
     */
    public static void onPreInit() {
        CgPlatform.register(PlatformServiceHarness.getInstance());
    }

}
