package com.crystalgraphics.harness.util;

import com.crystalgraphics.harness.InteractiveSceneRunner;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * Shared GL state boundary helpers for the harness render pipeline.
 *
 * <p>Each interactive frame in the harness follows a fixed render pass order:</p>
 * <ol>
 *   <li><b>World pass</b> — Floor plane (depth ON, blend OFF, depth writes ON)</li>
 *   <li><b>Scene pass</b> — Scene content (state varies per scene; pre-set to safe defaults)</li>
 *   <li><b>Post-scene reset</b> — {@link GlStateResetHelper#resetAfterScene()} cleans up scene leftovers</li>
 *   <li><b>Overlay pass</b> — Pause overlay, HUD text (depth OFF, blend ON with src-alpha)</li>
 *   <li><b>Capture point</b> — Post-render callback fires for screenshot capture</li>
 * </ol>
 *
 * <p>Each pass <b>declares the GL state it needs</b> via a {@code beginXxxPass()} method
 * rather than relying on folklore cleanup or bespoke save/restore blocks in individual
 * renderers. This centralizes GL state ownership so that:</p>
 * <ul>
 *   <li>State assumptions are documented in one place, not scattered across renderer classes</li>
 *   <li>Adding a new pass or renderer requires only calling the right boundary method</li>
 *   <li>State leakage between passes is caught by explicit setup rather than masked by redundant restores</li>
 * </ul>
 *
 * <p><b>Important</b>: This is NOT a render graph or dependency system. The pass ordering is
 * hardcoded in {@link InteractiveSceneRunner}
 * and these helpers simply set/restore the GL state each pass requires. The runner controls
 * <i>when</i> each pass runs; these helpers control <i>what state</i> each pass needs.</p>
 *
 * @see GlStateResetHelper#resetAfterScene()
 */
public final class RenderPassState {
    // Through CgGL, never raw GL: CrystalGraphics' state shadow has to see these writes or it elides the
    // next CgGL write that disagrees with them.

    private RenderPassState() { }

    /**
     * Sets up GL state for the <b>world pass</b> (floor rendering).
     *
     * <p>The floor plane requires:</p>
     * <ul>
     *   <li>Depth test ON — floor must participate in depth sorting with scene objects</li>
     *   <li>Depth writes ON — floor must write to the depth buffer so objects behind it are occluded</li>
     *   <li>Blend OFF — floor is fully opaque; blending would cause artifacts with the sky clear color</li>
     * </ul>
     *
     * <p>Call this before rendering the floor plane. The floor renderer no longer needs
     * to save/restore GL state itself — the subsequent {@code beginScenePass()} call
     * sets the correct state for the scene.</p>
     */
    public static void beginWorldPass() {
        CgGL.glEnable(CgGL.GL_DEPTH_TEST);
        CgGL.glDepthMask(true);
        CgGL.glDisable(CgGL.GL_BLEND);
    }

    /**
     * Sets up GL state for the <b>scene pass</b> (scene content rendering).
     *
     * <p>Pre-sets the same safe defaults that the world pass uses, since most
     * scenes expect depth testing enabled and blending disabled as a baseline.
     * Individual scenes may modify state as needed during their
     * {@code renderFrame()} call; the post-scene reset
     * ({@link GlStateResetHelper#resetAfterScene()}) will clean up afterwards.</p>
     *
     * <p>This replaces the ad-hoc state reset that was previously inlined in the
     * runner's render loop before the scene call.</p>
     */
    public static void beginScenePass() {
        CgGL.glEnable(CgGL.GL_DEPTH_TEST);
        CgGL.glDepthMask(true);
        CgGL.glDisable(CgGL.GL_BLEND);
    }

    /**
     * Sets up GL state for the <b>overlay pass</b> (pause overlay, HUD text).
     *
     * <p>Overlays are screen-aligned 2D quads rendered on top of the 3D scene.
     * They require:</p>
     * <ul>
     *   <li>Depth test OFF — overlays always render on top regardless of depth buffer content</li>
     *   <li>Blend ON with standard alpha blending (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) — pause overlay
     *       is semi-transparent; HUD text has transparent backgrounds</li>
     * </ul>
     *
     * <p>Both PauseScreenRenderer and HUDRenderer should call this at the start of their
     * render methods instead of managing their own save/restore blocks.</p>
     */
    public static void beginOverlayPass() {
        // No glGet to save the state first: it waits for the driver to drain the whole frame queued so far.
        // Overlays render on top of the 3D scene — disable depth test
        CgGL.glDisable(CgGL.GL_DEPTH_TEST);

        // Enable alpha blending for semi-transparent overlays and text
        CgGL.glEnable(CgGL.GL_BLEND);
        CgGL.glBlendFunc(CgGL.GL_SRC_ALPHA, CgGL.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Ends the <b>overlay pass</b>, leaving the scene pass's baseline (depth test on, blend off) for whatever
     * follows; every pass after it declares its own state.
     */
    public static void endOverlayPass() {
        CgGL.glEnable(CgGL.GL_DEPTH_TEST);
        CgGL.glDisable(CgGL.GL_BLEND);
    }

    /**
     * Marker method documenting the <b>capture point</b> in the render pipeline.
     *
     * <p>The capture point is where post-render callbacks fire to take screenshots.
     * It occurs after all render passes (world, scene, overlays) are complete but
     * BEFORE the buffer swap ({@code HarnessWindow.swapBuffers()}). No special GL state setup
     * is needed — the backbuffer contains the fully composited frame.</p>
     *
     * <p>This method is intentionally a no-op. Its purpose is to make the pipeline
     * ordering explicit in the runner code and provide a documentation anchor for
     * the capture timing contract.</p>
     */
    public static void beginCapturePoint() {
        // No-op — capture reads from the backbuffer in whatever state is current.
        // The backbuffer already contains the fully composited frame at this point.
    }
}
