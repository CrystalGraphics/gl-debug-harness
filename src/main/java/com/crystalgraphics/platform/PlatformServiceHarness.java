package com.crystalgraphics.platform;

import com.crystalgraphics.harness.runtime.HarnessWindow;
import com.crystalgraphics.lwjgl3.GlfwInputService;
import com.crystalgraphics.lwjgl3.Lwjgl3GLBackend;
import com.crystalgraphics.lwjgl3.Lwjgl3GLContext;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;
import com.crystalgraphics.platform.service.LifecycleServiceHarness;
import com.crystalgraphics.platform.service.ReloadServiceHarness;
import com.crystalgraphics.platform.service.RenderingServiceHarness;
import com.crystalgraphics.platform.service.ResourceServiceHarness;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.host.OwnedVulkanHost;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;

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

    // ── --device=tracked and --device=vulkan ──────────────────────────────────
    private CgDevice device;
    private CgRecordingDevice recordingDevice;
    private OwnedVulkanHost vulkanHost;
    private CgVulkanDevice vulkanDevice;
    private CgTrackedGLBackend tracked;

    @Override public CgGLBackend       gl()           { return tracked != null ? tracked : glDispatchImpl; }
    @Override public CgGLContext         capabilities() { return tracked != null ? new CgTrackedGLContext() : glContextImpl; }
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
        onPreInit("gl", 0, 0);
    }

    /**
     * @param device {@code gl}; {@code tracked}: {@code CgGL} on the tracked backend over a recording device the size
     *               of the window, which validates every command and presents nothing; {@code vulkan}: the tracked
     *               backend over CrystalGraphics' Vulkan device, presenting to the window, which must already exist
     */
    public static void onPreInit(String device, int width, int height) {
        PlatformServiceHarness p = getInstance();
        if (device.equals("tracked")) {
            p.recordingDevice = new CgRecordingDevice(width, height).withoutLog();
            p.device = p.recordingDevice;
        } else if (device.equals("vulkan")) {
            // -Dcrystalgraphics.harness.vulkanValidation=false for a timing run: the layer checks every command.
            boolean validate = !"false".equals(System.getProperty("crystalgraphics.harness.vulkanValidation"));
            p.vulkanHost = new OwnedVulkanHost(HarnessWindow.handle(), validate);
            p.vulkanDevice = new CgVulkanDevice(p.vulkanHost, width, height);
            p.device = p.vulkanDevice;
        }
        if (p.device != null) p.tracked = new CgTrackedGLBackend(p.device, new ShadercGlslCompiler(), true);
        CgPlatform.register(p);
        if (p.tracked != null) CgGlState.setProvider(new CgTrackedStateProvider(p.tracked));
        // NATIVE CONTENT IS NOT DECLARED HERE, and it used to be.
        //
        // `com.crystalgui.ui.elements.slot` lives only on core's `native-content-slots` branch, which
        // was never merged to master -- so this branch of the harness has not compiled against master
        // core since it was written, and `cgui-slot` went with it. The declaration is worth restoring
        // the moment that branch lands: a native-content slot refuses to paint on a platform that
        // never said whether it renders items, and the harness is genuinely such a platform, so
        // saying so out loud is what separates it from a loader that forgot.
    }

    /** The tracked backend under {@code --device=tracked}, else null. */
    public static CgTrackedGLBackend tracked() {
        return INSTANCE == null ? null : INSTANCE.tracked;
    }

    /** The device's surface follows the window's framebuffer. Nothing on GL. */
    public static void resizeSurface(int width, int height) {
        if (tracked() == null) return;
        if (INSTANCE.recordingDevice != null) INSTANCE.recordingDevice.resize(width, height);
        else INSTANCE.vulkanDevice.resize(width, height);
    }

    /** Closes the Vulkan device and its host; before the window goes. Nothing otherwise. */
    public static void shutdown() {
        if (INSTANCE == null || INSTANCE.vulkanDevice == null) return;
        INSTANCE.vulkanDevice.close();
        INSTANCE.vulkanHost.close();
        INSTANCE.vulkanDevice = null;
    }

    /** Ends the tracked backend's frame; once a frame, after everything drawn in it. Nothing on GL. */
    public static void endTrackedFrame() {
        if (tracked() != null) INSTANCE.tracked.endFrame();
    }

    /** What the tracked backend did over the run, for the log; null on GL. */
    public static String trackedReport() {
        if (tracked() == null) return null;
        String device = INSTANCE.recordingDevice != null
                ? "deviceDraws=" + INSTANCE.recordingDevice.draws() + " liveObjects=" + INSTANCE.recordingDevice.liveObjects()
                : INSTANCE.vulkanDevice.info().name() + " barriers=" + INSTANCE.vulkanDevice.barriers()
                        + " validationErrors=" + INSTANCE.vulkanDevice.validationErrors();
        return "frames=" + INSTANCE.device.frameIndex() + " " + device + " " + INSTANCE.tracked.stats();
    }
}
