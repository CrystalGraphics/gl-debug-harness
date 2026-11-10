package com.crystalgraphics.platform;

import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.world.CgWorldBegin;
import com.crystalgraphics.harness.HarnessExtensions;
import com.crystalgui.lifecycle.CgUiStart;
import com.crystalgraphics.harness.runtime.HarnessWindow;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lwjgl3.ClipboardGlfw;
import com.crystalgraphics.lwjgl3.InputGlfw;
import com.crystalgraphics.platform.input.CgClipboard;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.lwjgl3.Lwjgl3GLBackend;
import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.lwjgl3.Lwjgl3GLContext;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGLDevice;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.gl.state.CgGlGetProvider;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.host.CgVulkanHost;
import com.crystalgraphics.vulkan.host.CgVulkanImage;
import com.crystalgraphics.vulkan.host.OwnedVulkanHost;
import com.crystalgraphics.platform.gl.pipeline.GLNativeGlslCompiler;
import com.crystalgraphics.platform.gl.pipeline.GLNativeTranslator;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * The harness as a host: its platform services, the device it runs ({@code --rhi=gl|vulkan}) and the host section
 * round each frame ({@link CgHostPaint}). The one layer of the harness that speaks to GL and the tracked backend
 * directly, as a Minecraft host's platform classes do.
 *
 * <pre>{@code
 * PlatformServiceHarness.onPreInit("vulkan", w, h);   // after the window exists
 * CgHostPaint.enter();                                // each frame: the host section
 * try { frame(); } finally { CgHostPaint.leave(); }
 * PlatformServiceHarness.endFrame();                  // the engine's frame tick, then the device's frame
 * PlatformServiceHarness.shutdown();                  // before the window goes
 * }</pre>
 */
public final class PlatformServiceHarness implements CgPlatformService {
    
    // ── Singleton ─────────────────────────────────────────────────────────────
    private static PlatformServiceHarness INSTANCE;
    
    public static void init() {
        if (INSTANCE != null) return;
        INSTANCE = new PlatformServiceHarness();
        // No camera hook: the world's frame runs at the first opaque firing, after the scene's own logic, as before.
        CgWorldBegin.atStage();
    }
    public static PlatformServiceHarness getInstance() { if (INSTANCE == null) init(); return INSTANCE;}
    
    // ── Services ─────────────────────────────────────────────────────────────

    public final Lwjgl3GLBackend glDispatchImpl = new Lwjgl3GLBackend();
    public final Lwjgl3GLContext glContextImpl = new Lwjgl3GLContext();

    // ── --rhi=gl and --rhi=vulkan ─────────────────────────────────────────────
    private CgRHI device;
    private OwnedVulkanHost vulkanHost;
    private boolean vulkanValidated;
    private CgVulkanDevice vulkanDevice;
    private CgTrackedGLBackend tracked;
    private CgGLDevice glDevice;

    @Override public CgGLBackend       gl()           { return tracked != null ? tracked : glDispatchImpl; }
    @Override public CgGLContext         capabilities() { return tracked != null ? new CgTrackedGLContext() : glContextImpl; }
    
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * FML preInit phase. Constructs and registers all platform services with {@link CgPlatform}.
     * Safe to call before any GL context exists.
     */
    public static void onPreInit() {
        onPreInit("gl", 0, 0);
    }

    /**
     * @param rhi {@code gl}: the GL device beside the driver's context; {@code vulkan}: CrystalGraphics' Vulkan device,
     *            presenting to the window, which must already exist, with the engine's GL on the tracked backend over it
     */
    public static void onPreInit(String rhi, int width, int height) {
        PlatformServiceHarness p = getInstance();
        if (rhi.equals("vulkan")) {
            // -Dcrystalgraphics.harness.vulkanValidation=false for a timing run: the layer checks every command.
            boolean validate = !"false".equals(System.getProperty("crystalgraphics.harness.vulkanValidation"));
            p.vulkanValidated = validate;
            p.vulkanHost = new OwnedVulkanHost(HarnessWindow.handle(), validate);
            p.vulkanDevice = new CgVulkanDevice(new PresentTimed(p.vulkanHost), width, height, CgCacheDirectory.of("vulkan"));
            p.device = p.vulkanDevice;
        }
        if (rhi.equals("gl") && !"false".equals(System.getProperty("crystalgraphics.harness.glDevice"))) {
            // CgRHI on GL, owning the window's context: nothing else draws in it. Under crystalgraphics.shader.native
            // the driver compiles, as a GL-era Minecraft's first start does
            boolean nativeGlsl = Boolean.getBoolean("crystalgraphics.shader.native");
            try {
                GLNativeTranslator translator = new GLNativeTranslator();
                if (!nativeGlsl) translator.spirv(new SpvcGlslTranslator());
                p.glDevice = new CgGLDevice(new Lwjgl3GLDriver(), translator, width, height, false);
                p.glDevice.installBesideCgGL(nativeGlsl ? new GLNativeGlslCompiler(p.glDevice.driver())
                        : new ShadercGlslCompiler(CgCacheDirectory.of("spirv")));
            } catch (IllegalStateException e) {
                System.out.println("[Harness] no GL device: " + e.getMessage());
            }
        }
        if (p.device != null) {
            p.tracked = new CgTrackedGLBackend(p.device, new ShadercGlslCompiler(CgCacheDirectory.of("spirv")), true)
                    .compileInBackground().install();
        }
        CgPlatform.register(p);
        CgPlatform.provide(CgHostPaint.HOST, new HostSection());
        CgPlatform.provide(CgInput.HOST, new InputGlfw(HarnessWindow::handle));
        CgPlatform.provide(CgClipboard.HOST, new ClipboardGlfw(HarnessWindow::handle));
        // No resource packs: CgIO reads the classpath and the asset roots
        // Always a client: the graphics start's GL-free work begins beside the rest of the harness's start
        CgEngineLifecycle.clientStarting();
        CgUiStart.ahead();           // and CrystalGUI's: its registries and sheets
        HarnessExtensions.start();   // and each project's own
        // Scenes use CgGL from the start, before any host section would install it.
        CgGL.init(p.gl());
        // The tracked backend's state is CgGlState's already (its install). Every draw here is the engine's, so a stage
        // entry reads nothing. -Dcrystalgraphics.harness.keepShadow=false measures or rules that out.
        if (p.tracked == null && !"false".equals(System.getProperty("crystalgraphics.harness.keepShadow"))) {
            CgGlState.setProvider(new KeptShadow());
        }
        // NATIVE CONTENT IS NOT DECLARED HERE, and it used to be.
        //
        // `com.crystalgui.ui.elements.slot` lives only on core's `native-content-slots` branch, which
        // was never merged to master -- so this branch of the harness has not compiled against master
        // core since it was written, and `cgui-slot` went with it. The declaration is worth restoring
        // the moment that branch lands: a native-content slot refuses to paint on a platform that
        // never said whether it renders items, and the harness is genuinely such a platform, so
        // saying so out loud is what separates it from a loader that forgot.
    }

    /** The harness's host section: everything a frame draws is the engine's, so the section's close repairs nothing. */
    private static final class HostSection implements CgHostPaint.Host {
        @Override
        public void enter() {
            CgDeviceSection.begin();
        }

        @Override
        public void leave() {
            CgDeviceSection.end();
        }
    }

    /** {@code glGet} where the shadow does not know, and a host that never draws behind it. */
    private static final class KeptShadow extends CgGlGetProvider {
        @Override
        public boolean hostKeepsShadow() {
            return true;
        }
    }

    private static CgTrackedGLBackend tracked() {
        return INSTANCE == null ? null : INSTANCE.tracked;
    }

    /** The device: its name, vendor and driver; null where none runs (GL with no 4.5 context). */
    public static CgRHI.Globals deviceInfo() {
        if (INSTANCE != null && INSTANCE.glDevice != null) return INSTANCE.glDevice.globals();
        return tracked() == null ? null : INSTANCE.device.globals();
    }


    private static long presentNanos;

    /**
     * Time spent presenting since the last call, and reset: the owned host's {@code endFrame} — acquire, the copy
     * onto the swapchain image, submit, present and the frame fence. 0 on any other device.
     */
    public static long takePresentNanos() {
        long n = presentNanos;
        presentNanos = 0;
        return n;
    }

    /** The owned host, its {@code endFrame} timed: presentation, which a host inside Minecraft does not do. */
    private record PresentTimed(CgVulkanHost host) implements CgVulkanHost {
        @Override public VkInstance instance() { return host.instance(); }
        @Override public VkPhysicalDevice physicalDevice() { return host.physicalDevice(); }
        @Override public VkDevice device() { return host.device(); }
        @Override public VkQueue queue() { return host.queue(); }
        @Override public int queueFamily() { return host.queueFamily(); }
        @Override public int apiVersion() { return host.apiVersion(); }
        @Override public int framesInFlight() { return host.framesInFlight(); }
        @Override public VkCommandBuffer commandBuffer() { return host.commandBuffer(); }
        @Override public VkCommandBuffer setupCommandBuffer() { return host.setupCommandBuffer(); }
        @Override public long frameIndex() { return host.frameIndex(); }
        @Override public long retiredFrame() { return host.retiredFrame(); }
        @Override public void whenFrameRetired(long frame, Runnable action) { host.whenFrameRetired(frame, action); }
        @Override public boolean ownsSubmission() { return host.ownsSubmission(); }
        @Override public void submitAndWait() { host.submitAndWait(); }
        @Override public void waitRetired(long frame) { host.waitRetired(frame); }
        @Override public int validationErrors() { return host.validationErrors(); }
        @Override public boolean namesObjects() { return host.namesObjects(); }
        @Override public boolean bresenhamLines() { return host.bresenhamLines(); }
        @Override public boolean multiDrawIndirect() { return host.multiDrawIndirect(); }
        @Override public boolean indirectCount() { return host.indirectCount(); }
        @Override public boolean indirectFirstInstance() { return host.indirectFirstInstance(); }
        @Override public boolean drawParameters() { return host.drawParameters(); }
        @Override public boolean independentBlend() { return host.independentBlend(); }
        @Override public boolean bindless() { return host.bindless(); }
        @Override public boolean textureCompressionBC() { return host.textureCompressionBC(); }
        @Override public boolean asyncCompute() { return host.asyncCompute(); }
        @Override public int asyncFamily() { return host.asyncFamily(); }
        @Override public void beginAsync() { host.beginAsync(); }
        @Override public long endAsync() { return host.endAsync(); }
        @Override public void waitAsync(long point) { host.waitAsync(point); }
        @Override public boolean asyncTransfer() { return host.asyncTransfer(); }
        @Override public int transferFamily() { return host.transferFamily(); }
        @Override public VkCommandBuffer transferCommandBuffer() { return host.transferCommandBuffer(); }
        @Override public long submitTransfers() { return host.submitTransfers(); }
        @Override public void waitTransfers(long point) { host.waitTransfers(point); }

        @Override
        public void endFrame(CgVulkanImage output) {
            long t0 = System.nanoTime();
            host.endFrame(output);
            presentNanos += System.nanoTime() - t0;
        }
    }

    /** Validation errors the Vulkan device has seen so far; 0 on any other device. */
    public static int validationErrors() {
        return INSTANCE == null || INSTANCE.vulkanDevice == null ? 0 : INSTANCE.vulkanDevice.validationErrors();
    }

    // ── GPU fences, for a probe timing the GPU against the CPU ────────────────

    /** A fence over everything issued so far; poll it with {@link #fenceSignalled}, then {@link #deleteFence}. */
    public static long fence() {
        return CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** Whether {@code fence}'s work has finished on the GPU; never waits. */
    public static boolean fenceSignalled(long fence) {
        return CgGL.glClientWaitSync(fence, 0, 0L) != CgGL.GL_TIMEOUT_EXPIRED;
    }

    public static void deleteFence(long fence) {
        CgGL.glDeleteSync(fence);
    }

    /** Whether this run's device is Vulkan, where the fences above signal at the frame's submit and not before. */
    public static boolean vulkan() {
        return INSTANCE != null && INSTANCE.vulkanDevice != null;
    }

    /**
     * Waits until the GPU has finished everything issued so far, spinning: a Windows sleep wakes up to 15.6 ms late. GL
     * only: on Vulkan nothing is submitted mid-frame, so it would never return.
     */
    public static void finishGpu() {
        long sync = CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        int flags = CgGL.GL_SYNC_FLUSH_COMMANDS_BIT;
        while (CgGL.glClientWaitSync(sync, flags, 0L) == CgGL.GL_TIMEOUT_EXPIRED) flags = 0;
        CgGL.glDeleteSync(sync);
    }

    /** Whole-texture uploads the tracked backend renamed into a new image so far; 0 on GL. */
    public static long textureRenames() {
        return tracked() == null ? 0 : INSTANCE.tracked.tracker().stats().textureRenames;
    }

    /** Copies the Vulkan device has recorded on its transfer queue so far; 0 on any other device or without one. */
    public static int transferCopies() {
        return INSTANCE == null || INSTANCE.vulkanDevice == null ? 0 : INSTANCE.vulkanDevice.transferCopies;
    }

    /** The device's surface follows the window's framebuffer. Nothing on GL. */
    public static void resizeSurface(int width, int height) {
        if (INSTANCE != null && INSTANCE.glDevice != null) INSTANCE.glDevice.resize(width, height);
        if (INSTANCE != null && INSTANCE.vulkanDevice != null) INSTANCE.vulkanDevice.resize(width, height);
    }

    /** Closes the Vulkan device and its host; before the window goes. Nothing otherwise. */
    public static void shutdown() {
        if (INSTANCE != null && INSTANCE.glDevice != null) {
            INSTANCE.glDevice.fromHost();
            INSTANCE.glDevice.close();
            INSTANCE.glDevice.toHost();
            INSTANCE.glDevice = null;
        }
        if (INSTANCE == null || INSTANCE.vulkanDevice == null) return;
        INSTANCE.vulkanDevice.close();
        // After the device, so what its teardown raised is counted; the tracked: line came before it.
        System.out.println("[Harness] vulkan after teardown: validationErrors=" + INSTANCE.vulkanHost.validationErrors());
        INSTANCE.vulkanHost.close();
        INSTANCE.vulkanDevice = null;
    }

    private static final int TRACKED_END = CgTrace.name("frame.trackedEnd");

    /** The frame's end, once a frame after everything drawn in it: the engine's frame tick, then the device's frame. */
    public static void endFrame() {
        CgGraphicsLifecycle.tickFrame();
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, TRACKED_END)) {
            endDeviceFrame();
        }
    }

    /**
     * Ends the device's frame, once a frame after everything drawn in it: the tracked backend's on Vulkan, the GL
     * device's on GL, whose frames retire what its draws used (its rings, views, framebuffers) by fence.
     */
    public static void endDeviceFrame() {
        if (tracked() != null) {
            INSTANCE.tracked.endFrame();
        } else if (INSTANCE != null && INSTANCE.glDevice != null) {
            // No section: a frame's end fences, flushes and retires, touching nothing CgGL keeps; a section would drop
            // CgGL's shadow every frame, and a scope restoring the viewport would restore nothing.
            INSTANCE.glDevice.endFrame();
        }
    }

    /** Whether this run's Vulkan device asked for the validation layer: its CPU times are the layer's, not ours. */
    public static boolean vulkanValidated() {
        return INSTANCE.vulkanHost != null && INSTANCE.vulkanValidated;
    }

    /** What the Vulkan device and the tracked backend over it did over the run, for the log; null on GL. */
    public static String deviceReport() {
        if (tracked() == null) return null;
        String device = INSTANCE.vulkanDevice.globals().name() + " barriers=" + INSTANCE.vulkanDevice.barriers()
                        + " pushes=" + INSTANCE.vulkanDevice.pushes + " pushWrites=" + INSTANCE.vulkanDevice.pushWrites + " pushesSkipped=" + INSTANCE.vulkanDevice.pushesSkipped
                        + " validationErrors=" + INSTANCE.vulkanDevice.validationErrors()
                        + " transferCopies=" + INSTANCE.vulkanDevice.transferCopies
                        + " transferBufferCopies=" + INSTANCE.vulkanDevice.transferBufferCopies
                        + " frameCopies=" + INSTANCE.vulkanDevice.frameCopies
                        + " frameCopyCalls=" + INSTANCE.vulkanDevice.frameCopyCalls;
        return "frames=" + INSTANCE.device.frameIndex() + " " + device + " " + INSTANCE.tracked.stats();
    }
}
