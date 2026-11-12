package com.crystalgraphics.harness.runtime;

import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.harness.camera.FloorRenderer;
import com.crystalgraphics.harness.config.WorldConfig;
import com.crystalgraphics.harness.config.WorldSettings;
import org.joml.Matrix4fc;

/**
 * The world pass, first in each interactive frame: the frame's target cleared to the sky colour, then the floor drawn
 * under a 3D scene, so its objects and the world stages composite over the ground rather than the sky.
 *
 * <pre>{@code
 * WorldPassCoordinator world = new WorldPassCoordinator(new FloorRenderer(), settings);
 * world.init();
 * world.executeWorldPass(ctx.getTargetFramebuffer(), w, h, view, projection, scene.uses3DCamera());   // each frame
 * }</pre>
 */
public final class WorldPassCoordinator {

    private final FloorRenderer floorRenderer;
    private final WorldSettings worldSettings;

    public WorldPassCoordinator(FloorRenderer floorRenderer, WorldSettings worldSettings) {
        if (floorRenderer == null) throw new IllegalArgumentException("floorRenderer must not be null");
        if (worldSettings == null) throw new IllegalArgumentException("worldSettings must not be null");
        this.floorRenderer = floorRenderer;
        this.worldSettings = worldSettings;
    }

    /** Loads the floor: once, before any frame. */
    public void init() {
        floorRenderer.init(worldSettings);
    }

    /**
     * Clears {@code target} ({@code width} x {@code height}: the main target, or the window) to the sky, and draws
     * the floor when the scene has a 3D camera.
     *
     * @param view       the view the scene draws its world with this frame: the 3D camera's, or the scene's own
     *                   ({@code InteractiveSceneLifecycle.worldView}), so the floor's depth matches the world's
     * @param projection that view's projection
     */
    public void executeWorldPass(CgExternalTarget target, int width, int height, Matrix4fc view, Matrix4fc projection,
                                 boolean uses3DCamera) {
        CgRenderer.clear(target, true, worldSettings.getSkyR(), worldSettings.getSkyG(), worldSettings.getSkyB(), 1f,
                true, 1.0, true, 0);
        if (uses3DCamera && WorldConfig.RENDER_FLOOR && !HarnessEnvironment.INSTANCE.enabled()) {
            floorRenderer.render(view, projection, width, height);
        }
    }

    @Override
    public String toString() {
        return "WorldPassCoordinator[worldSettings=" + worldSettings + "]";
    }
}
