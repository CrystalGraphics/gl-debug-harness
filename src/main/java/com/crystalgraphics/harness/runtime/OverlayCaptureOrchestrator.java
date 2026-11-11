package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * What follows the scene each interactive frame, in this order: the HUD stage (a 3D scene's), what draws over it (the
 * harness's desktop), then the capture point, where a one-shot callback photographs the finished frame before the
 * window is given it. Every draw here is the engine's, under its pipelines' own state.
 *
 * <pre>{@code
 * orchestrator.setPostRenderCallback(() -> artifacts.capture("final"));   // this frame, after the HUD
 * orchestrator.executePostSceneSequence(scene.uses3DCamera(), screenUp, () -> ui.afterFrame(info));
 * }</pre>
 *
 * <p>Render thread.</p>
 */
public final class OverlayCaptureOrchestrator {

    private final HarnessContext ctx;
    private final OverlayPipeline overlayPipeline;
    /** Fires once after the frame is drawn, its HUD included, before the window gets it; then cleared. */
    private Runnable postRenderCallback;

    public OverlayCaptureOrchestrator(HarnessContext ctx, OverlayPipeline overlayPipeline) {
        this.ctx = ctx;
        this.overlayPipeline = overlayPipeline;
    }

    /**
     * The GUI stages, then {@code afterOverlays} (or nothing), then the capture callback. Once a frame, after the scene.
     *
     * @param uses3DCamera whether the scene has the 3D camera, which is what the HUD reads out
     * @param screenUp     whether a screen is up, which {@code SCREEN} draws over: a 2D scene, or the desktop
     */
    public void executePostSceneSequence(boolean uses3DCamera, boolean screenUp, Runnable afterOverlays) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "overlay.render")) {
            overlayPipeline.renderOverlays(ctx, uses3DCamera, screenUp);
        }
        if (afterOverlays != null) afterOverlays.run();
        if (postRenderCallback != null) {
            Runnable callback = postRenderCallback;
            postRenderCallback = null;
            callback.run();
        }
    }

    /** {@code callback} at this frame's capture point, replacing one not yet fired; null clears it. */
    public void setPostRenderCallback(Runnable callback) {
        this.postRenderCallback = callback;
    }

    /** The HUD's resources: at the runner's shutdown. */
    public void deletePipeline() {
        overlayPipeline.delete();
    }

    @Override
    public String toString() {
        return "OverlayCaptureOrchestrator[hasPendingCallback=" + (postRenderCallback != null) + "]";
    }
}
