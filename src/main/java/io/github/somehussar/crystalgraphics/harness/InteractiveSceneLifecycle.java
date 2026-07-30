package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.CgPlatform;

/**
 * Extended lifecycle contract for interactive scenes with continuous render loops.
 *
 * <p>This sub-interface of {@link HarnessSceneLifecycle} adds the interactive
 * render-loop control hooks that the {@link InteractiveSceneRunner} needs:
 * running state, 3D camera mode, and shutdown control.</p>
 *
 * <h3>Lifecycle for interactive scenes</h3>
 * <pre>
 *   init(ctx)
 *   while (isRunning() &amp;&amp; !Display.isCloseRequested()) {
 *       // runtime: input → camera → scheduler → clear → floor
 *       render(ctx, frameInfo)
 *       // runtime: state reset → pause → HUD → capture → swap
 *   }
 *   dispose()
 * </pre>
 *
 * <h3>Key difference from managed scenes</h3>
 * <p>Managed scenes call {@code render()} exactly once. Interactive scenes
 * call it in a loop until {@link #isRunning()} returns false or the window
 * is closed. The runtime separation is preserved — interactive scenes are
 * never forced through the managed execution path.</p>
 *
 * @see HarnessSceneLifecycle
 * @see InteractiveSceneRunner
 */
public interface InteractiveSceneLifecycle extends HarnessSceneLifecycle {

    /**
     * Whether the scene's render loop should continue running.
     *
     * <p>The runtime checks this each frame. Return {@code false} to signal
     * that the scene is done and should transition to dispose.</p>
     *
     * @return true if the render loop should continue
     */
    boolean isRunning();

    /**
     * Whether this scene uses the 3D camera system.
     *
     * <p>If true, the runtime enables camera movement, floor rendering,
     * and HUD display. If false, the runtime skips these subsystems.</p>
     *
     * @return true if the 3D camera should be active
     */
    boolean uses3DCamera();

    /**
     * Whether this scene requests program shutdown when it finishes.
     *
     * <p>Default is {@code true}. Override to return {@code false} if the
     * harness should continue after this scene completes.</p>
     *
     * @return true if the program should exit after this scene
     */
    boolean shouldShutdownOnComplete();

    /**
     * Called once per iteration after the frame is <em>completely</em> finished — after overlays,
     * the post-render tick, the buffer swap and the frame-rate sync.
     *
     * <h3>Why this exists: profiling from inside {@link #render} is off by one</h3>
     * <p>A scene that ends its profiler frame inside {@code render()} is only a third of the way
     * through the loop iteration. Everything after it — HUD/overlay drawing,
     * {@code CgPlatform.lifecycle().onFrameRendered()} (which runs the glyph-commit drain), the
     * swap, and the sync sleep — is still to come, and lands in the <em>next</em> frame's report.
     * Read naively that attributes each frame's drain and swap cost to its successor.</p>
     *
     * <p>{@code frame.getDeltaTime()} has the same problem from the other direction: it is
     * computed when the frame <em>starts</em>, so it measures the previous frame. A row that
     * combines "this frame's scopes" with "last frame's duration" will happily show a 117 ms draw
     * on a frame reporting a 7 ms delta, which is how a real 40x-380x stall got attributed to the
     * wrong frame entirely.</p>
     *
     * <p>The {@link FrameInfo} passed here carries the <strong>true wall duration of the frame
     * that just ended</strong> as its delta, so a row recorded from this hook is internally
     * consistent: scopes and duration describe the same frame.</p>
     *
     * <p>Default no-op — scenes that do not profile need not implement it.</p>
     */
    default void onFrameEnd(HarnessContext ctx, FrameInfo frame) {
    }
}
