package io.github.somehussar.crystalgraphics.harness.debug;

import com.crystalgui.core.event.CgUiDebug;
import com.crystalgui.core.event.CgUiKeyCodes;
import com.crystalgui.core.event.UiKeyEvent;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.input.FocusManager;
import com.crystalgui.core.input.UiInputManager;
import com.crystalgui.ui.UIContainer;

import java.util.logging.Logger;

/**
 * Harness-side synthetic UI event injection for deterministic testing
 * of the CrystalGUI event/input/focus frameworks.
 *
 * <p>All methods log through {@link CgUiDebug} when enabled, producing
 * structured verbose output covering hit-testing, event routing, signal
 * emission, property changes, and focus transitions.</p>
 *
 * <p>Usage from harness scenes or validation choreography:</p>
 * <pre>{@code
 * UiEventInjector injector = new UiEventInjector(testUi);
 * injector.mouseMove(150, 50);
 * injector.mouseClick(150, 50, 0);
 * injector.tabFocus(false);
 * }</pre>
 */
public final class UiEventInjector {

    private static final Logger LOGGER = Logger.getLogger(UiEventInjector.class.getName());

    private final UIContainer container;

    public UiEventInjector(UIContainer container) {
        if (container == null) throw new IllegalArgumentException("container must not be null");
        this.container = container;
    }

    public void mouseMove(float x, float y) {
        LOGGER.fine("[UiEventInjector] mouseMove(" + x + ", " + y + ")");
        UiInputManager input = container.getInputManager();
        if (input != null) {
            input.processMouseMove(x, y, 0);
        }
    }

    public void mouseClick(float x, float y, int button) {
        LOGGER.fine("[UiEventInjector] mouseClick(" + x + ", " + y + ", button=" + button + ")");
        UiInputManager input = container.getInputManager();
        if (input != null) {
            input.processMouseDown(x, y, button, 0);
            input.processMouseUp(x, y, button, 0);
        }
    }

    public void mouseDown(float x, float y, int button) {
        LOGGER.fine("[UiEventInjector] mouseDown(" + x + ", " + y + ", button=" + button + ")");
        UiInputManager input = container.getInputManager();
        if (input != null) {
            input.processMouseDown(x, y, button, 0);
        }
    }

    public void mouseUp(float x, float y, int button) {
        LOGGER.fine("[UiEventInjector] mouseUp(" + x + ", " + y + ", button=" + button + ")");
        UiInputManager input = container.getInputManager();
        if (input != null) {
            input.processMouseUp(x, y, button, 0);
        }
    }

    public void mouseWheel(float x, float y, float scrollDelta) {
        LOGGER.fine("[UiEventInjector] mouseWheel(" + x + ", " + y + ", delta=" + scrollDelta + ")");
        UiInputManager input = container.getInputManager();
        if (input != null) {
            input.processMouseWheel(x, y, scrollDelta, 0);
        }
    }

    public void tabFocus(boolean reverse) {
        LOGGER.fine("[UiEventInjector] tabFocus(reverse=" + reverse + ")");
        FocusManager focus = container.getFocusManager();
        if (focus != null) {
            int modifiers = reverse ? com.crystalgui.core.event.Modifiers.SHIFT : 0;
            UiKeyEvent tabDown = UiKeyEvent.key(UiEventType.KEY_DOWN,
                    com.crystalgui.core.event.CgUiKeyCodes.KEY_TAB, modifiers);
            focus.dispatchKeyEvent(tabDown);
        }
    }

    public void keyDown(int keyCode, int modifiers) {
        LOGGER.fine("[UiEventInjector] keyDown(key=" + keyCode + ", mods=" + modifiers + ")");
        FocusManager focus = container.getFocusManager();
        if (focus != null) {
            UiKeyEvent event = UiKeyEvent.key(UiEventType.KEY_DOWN, keyCode, modifiers);
            focus.dispatchKeyEvent(event);
        }
    }

    public void keyUp(int keyCode, int modifiers) {
        LOGGER.fine("[UiEventInjector] keyUp(key=" + keyCode + ", mods=" + modifiers + ")");
        FocusManager focus = container.getFocusManager();
        if (focus != null) {
            UiKeyEvent event = UiKeyEvent.key(UiEventType.KEY_UP, keyCode, modifiers);
            focus.dispatchKeyEvent(event);
        }
    }

    public void typeString(String text) {
        LOGGER.fine("[UiEventInjector] typeString(\"" + text + "\")");
        FocusManager focus = container.getFocusManager();
        if (focus == null) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            UiKeyEvent keyDown = UiKeyEvent.key(UiEventType.KEY_DOWN, 0, 0);
            focus.dispatchKeyEvent(keyDown);
            UiKeyEvent typed = UiKeyEvent.typed(c, 0);
            focus.dispatchKeyEvent(typed);
        }
    }

    public void backspace() {
        keyDown(CgUiKeyCodes.KEY_BACKSPACE, 0);
    }

    public static void enableDebugLogging() {
        CgUiDebug.setEnabled(true);
        LOGGER.info("[UiEventInjector] CrystalGUI debug logging ENABLED");
    }

    public static void disableDebugLogging() {
        CgUiDebug.setEnabled(false);
        LOGGER.info("[UiEventInjector] CrystalGUI debug logging DISABLED");
    }
}
