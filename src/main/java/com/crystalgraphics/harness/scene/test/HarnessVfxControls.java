package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;

/**
 * A VFX scene's keyboard into {@link CgVfxDemoControls}, Shift tracked for Shift+Y.
 *
 * <pre>{@code
 * private final HarnessVfxControls controls = new HarnessVfxControls();
 *
 * @Override public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) { return controls.consume(event); }
 * @Override public String hudLine() { return CgVfxDemoControls.get().hudLines(true); }
 * }</pre>
 */
final class HarnessVfxControls {

    private boolean shift;

    /** Presses the controls' key, if it is one; true always, so the runner's pause handler still sees the event. */
    boolean consume(CgSystemInput.Keyboard.Event event) {
        int key = event.key();
        if (key == CgKeyCodes.KEY_LSHIFT || key == CgKeyCodes.KEY_RSHIFT) shift = event.pressed();
        else if (event.pressed() && !event.repeat()) CgVfxDemoControls.get().press(key, shift);
        return true;
    }
}
