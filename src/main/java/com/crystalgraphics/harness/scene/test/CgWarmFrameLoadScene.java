package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.demo.CgVfxModules;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * The loading screen's warm frame under the heaviest load the engine ships: every {@link CgVfxModules} station playing
 * at the warm view's origin, a warm frame fired each frame, as the screen fires one behind itself while pipelines build.
 * Once nothing is built ({@link CgLoadingScreen#firstUseWork} still for {@value #SETTLE_FRAMES} frames), it passes when
 * no zone of the next {@value #MEASURE} s (past every station's replay) ran {@value #LIMIT_MILLIS} ms on the GPU: a
 * quarter of the two seconds past which Windows resets the driver. Its slowest zone is printed either way, so a cost
 * creeping up shows before it fails. Scene id {@code warm-frame-load}.
 *
 * <p>A warm frame is 256x256 from a fixed view: what is sized from the target is coarsest there. Its light grid once put
 * thousands of lights in a cell and the sample pass walked each a pixel, past the driver's timeout: every monitor reset
 * (26.2 Vulkan, the {@code modules} demo).</p>
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=warm-frame-load --device=vulkan"
 * // [warm-frame-load] PASS on <device>: 412 warm frames once settled, the slowest zone gpu:vfx.pool.range 14 ms
 * }</pre>
 */
public final class CgWarmFrameLoadScene implements InteractiveSceneLifecycle {

    private static final int SETTLE_FRAMES = 120;
    /** The most one zone may run on the GPU. */
    private static final long LIMIT_MILLIS = 500L;
    /** Seconds measured once settled, and the most it waits to settle. */
    private static final float MEASURE = 8f, MOST_SETTLING = 20f;

    private CgVfxModules modules;
    private CgFont font;
    private int fired;
    private boolean settled;
    private long work = -1L;
    private int still;
    private double measuredFrom;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 9f, 34f);
        ctx.getCamera3D().setPitch(-14f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        modules = new CgVfxModules(font);
        // Not precached: the screen warms while pipelines still build, as the game's first world frames do
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!running) return;
        modules.submit(CgWorldRenderer.get(), 0.0, 0.0, 0.0, CgVfxDemoControls.get().time(frame.getElapsedTime()));
        CgLoadingScreen.fireWarmFrame();
        if (!settled) {
            // A zone split across submissions while a pipeline built counts the GPU's wait for it: measured once built
            long now = CgLoadingScreen.firstUseWork();
            still = now == work ? still + 1 : 0;
            work = now;
            if (still >= SETTLE_FRAMES) {
                settled = true;
                measuredFrom = frame.getElapsedTime();
                CgGpuTrace.forgetSlowZones();
                System.out.println("[warm-frame-load] settled at " + String.format("%.1f", measuredFrom) + " s: measuring");
            } else if (frame.getElapsedTime() > MOST_SETTLING) {
                running = false;
                System.out.println("[warm-frame-load] FAIL: first-use work had not stopped after " + MOST_SETTLING + " s");
            }
            return;
        }
        fired++;
        if (frame.getElapsedTime() < measuredFrom + MEASURE) return;
        running = false;
        report();
    }

    private void report() {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = (device == null ? "gl" : device.name()) + " at " + CgCapabilities.detect().computeTier();
        int validation = PlatformServiceHarness.validationErrors();
        long slowest = CgGpuTrace.slowestNanos() / 1_000_000L;
        String zone = CgGpuTrace.slowestZone();
        if (GlErrorChecker.checkAndLog("warm-frame-load")) {
            System.out.println("[warm-frame-load] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[warm-frame-load] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (CgGpuTrace.support() != CgGpuTrace.Support.SUPPORTED || CgGpuTrace.budgetNanos(CgGpuTrace.WATCH) <= 0L) {
            System.out.println("[warm-frame-load] FAIL on " + on + ": no stage firing was timed (" + CgGpuTrace.support()
                    + "), so it checked nothing");
        } else if (slowest >= LIMIT_MILLIS) {
            System.out.println("[warm-frame-load] FAIL on " + on + ": " + zone + " ran " + slowest + " ms on the GPU, the limit "
                    + LIMIT_MILLIS + " ms; turn the gpu trace channel on to name the pass inside it");
        } else {
            System.out.println("[warm-frame-load] PASS on " + on + ": " + fired + " warm frames once settled, the slowest zone "
                    + zone + " " + slowest + " ms");
        }
    }

    @Override
    public void dispose() {
        if (modules != null) modules.delete();
        if (font != null) font.dispose();
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
