package com.crystalgraphics.harness;

import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgSystemInput;

/**
 * The desktop the harness runtime keeps over every 3D scene, offered by an extension (CrystalGUI's): windows over the
 * world, drawn over the HUD, given input ahead of the scene. The harness names no UI type, so
 * apps are opened by id.
 *
 * <pre>{@code
 * // a scene:
 * HarnessDesktop desktop = ctx.getRuntimeServices().desktop();      // null with no extension offering one
 * if (desktop != null) desktop.open("vfx-lab");
 *
 * // an extension:
 * @Override public HarnessDesktop desktop() { return new MyDesktop(); }   // fresh for each run
 * }</pre>
 *
 * <ul>
 *   <li>Build nothing in the constructor: a run that never opens a window should cost nothing.</li>
 *   <li>Its own keys (an app's shortcut) are its to handle in {@code consumeKeyboardEvent}; return true to pass an
 *       event on to the scene and the pause handler, false to keep it.</li>
 * </ul>
 */
public interface HarnessDesktop extends CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** The run's context, before the scene's {@code init}: keep it, build nothing yet. */
    void attach(HarnessContext ctx);

    /** Shows the desktop with that app's window, opened or brought back; false for an id it does not know. */
    boolean open(String app);

    /** Each frame over the HUD, open or not. */
    void render(HarnessContext ctx, FrameInfo frame);

    /**
     * Whether it is up, as a game's screen is: the runtime then frees the cursor and holds the camera without pausing,
     * and the runner's single-letter bindings stand down. While shown it should keep every key and click.
     */
    boolean shown();

    void dispose();
}
