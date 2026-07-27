package io.github.somehussar.crystalgraphics.harness.runtime;

import com.crystalgui.core.input.SystemInput;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.logging.Logger;

/**
 * Input and pause-state service for the interactive render loop.
 *
 * <p>Manages keyboard polling for the pause toggle (ESCAPE/T keys) and
 * the associated mouse grab/ungrab behavior. Extracted from
 * {@code InteractiveSceneRunner} to isolate input handling from
 * rendering, timing, and overlay concerns.</p>
 *
 * <p><b>Pause toggle semantics</b>: Uses the LWJGL keyboard event queue
 * (not {@code Keyboard.isKeyDown()}) to detect key-down events, ensuring
 * a single press produces exactly one toggle regardless of how many frames
 * the key is held.</p>
 *
 * <p><b>Mouse cursor contract</b>:</p>
 * <ul>
 *   <li>When toggling to paused: releases the mouse cursor</li>
 *   <li>When toggling to unpaused: grabs the mouse cursor and drains
 *       accumulated mouse delta to prevent a camera jump on resume</li>
 * </ul>
 *
 * <p><b>Thread safety</b>: Only used on the LWJGL render thread.</p>
 *
 * <p><b>Frame ordering contract</b>: {@link #pollPauseToggle()} must be
 * called once per frame BEFORE camera input processing, so the camera
 * skips updates while paused.</p>
 */
public final class InputPauseHandler implements SystemInput.Keyboard, SystemInput.Mouse {

    private static final Logger LOGGER = Logger.getLogger(InputPauseHandler.class.getName());
    private final boolean grabCursorOnUnpause;

    private boolean paused = false;

    public InputPauseHandler() {
        this(true);
    }

    public InputPauseHandler(boolean grabCursorOnUnpause) {
        this.grabCursorOnUnpause = grabCursorOnUnpause;
    }

    /**
     * Returns whether the runner is currently in paused state.
     *
     * @return true if paused
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Programmatically sets the paused state. Used by test scenes to
     * trigger pause without keyboard input.
     *
     * <p>Handles mouse grab/ungrab and drains accumulated mouse delta
     * to prevent camera jumps, mirroring the behavior of keyboard-driven
     * pause toggling.</p>
     *
     * @param paused true to pause, false to resume
     */
    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        this.paused = paused;
        if (paused) {
            Mouse.setGrabbed(false);
            LOGGER.info("[InputPauseHandler] PAUSED \u2014 cursor released");
        } else {
            Mouse.setGrabbed(grabCursorOnUnpause);
            Mouse.getDX();
            Mouse.getDY();
            LOGGER.info("[InputPauseHandler] RESUMED \u2014 cursor locked");
        }
    }

    /**
     * Releases the mouse cursor. Should be called during runner shutdown
     * to ensure the cursor is not trapped after the window closes.
     */
    public void releaseCursor() {
        Mouse.setGrabbed(false);
    }

    @Override
    public String toString() {
        return "InputPauseHandler[paused=" + paused + "]";
    }

    @Override
    public boolean consumeKeyboardEvent(SystemInput.Keyboard.Event event) {
        if (event.repeat())
            return !isPaused();

        int key = event.key();
        boolean pressed = event.pressed();

        if (pressed && (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_T)) {
            setPaused(!isPaused());
            return false;
        }

        return true;
    }

    @Override
    public boolean consumeMouseEvent(SystemInput.Mouse.Event event) {
        return true;
    }
}
