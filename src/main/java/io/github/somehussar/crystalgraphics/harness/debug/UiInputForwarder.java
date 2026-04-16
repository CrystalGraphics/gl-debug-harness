package io.github.somehussar.crystalgraphics.harness.debug;

import com.crystalgui.mc.CgUiInputAdapter;
import com.crystalgui.ui.UIContainer;

import org.lwjgl.opengl.Display;

/**
 * Harness-specific thin wrapper around {@link CgUiInputAdapter} that holds
 * a container reference and supplies the harness coordinate transform
 * (raw pixels, Y-flipped, no GUI scale).
 *
 * <p>The actual input translation logic lives in {@link CgUiInputAdapter}.
 * This class only provides the container binding and harness-specific
 * coordinate transform that {@link CgUiInputAdapter} needs.</p>
 */
public final class UiInputForwarder {

    private static final CgUiInputAdapter.CoordinateTransform HARNESS_TRANSFORM =
            (rawX, rawY) -> new float[]{ rawX, Display.getHeight() - rawY };

    private UIContainer container;

    public void setContainer(UIContainer container) {
        this.container = container;
    }

    /**
     * Drains LWJGL Mouse queue via {@link CgUiInputAdapter#drainMouseEvents}.
     */
    public void drainMouseEvents() {
        if (container == null) return;
        CgUiInputAdapter.drainMouseEvents(container, HARNESS_TRANSFORM);
    }

    /**
     * Forwards a single already-extracted keyboard event via
     * {@link CgUiInputAdapter#forwardKeyEvent}.
     */
    public void forwardKeyEvent(int lwjglKey, char character, boolean pressed) {
        if (container == null) return;
        CgUiInputAdapter.forwardKeyEvent(container, lwjglKey, character, pressed);
    }
}
