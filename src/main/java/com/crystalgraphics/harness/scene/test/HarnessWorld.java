package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4fc;

/**
 * The harness as a host of the world stages: it fires both with its own camera into the bound framebuffer, as
 * Minecraft's hooks fire them with its own.
 *
 * <pre>{@code
 * CgWorldRenderer.get().draw(mesh, material).at(x, y, z).submit();
 * HarnessWorld.fire(width, height, camera.getViewMatrix(), projection);
 * }</pre>
 */
final class HarnessWorld {

    private HarnessWorld() {
    }

    /** Fires {@code WORLD_OPAQUE} then {@code WORLD_TRANSPARENT}: the camera at the origin, {@code view} whole. */
    static void fire(int width, int height, Matrix4fc view, Matrix4fc projection) {
        CgWorldRenderer.get().install();
        int framebuffer = CgGL.glGetInteger(CgGL.GL_FRAMEBUFFER_BINDING);
        for (CgRenderStage stage : new CgRenderStage[]{CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT}) {
            stage.host().set(0f, width, height, framebuffer).view().set(0, 0, 0, view, projection);
            stage.fire();
        }
    }
}
