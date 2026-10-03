package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.harness.camera.HarnessCameraShake;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The harness as a host of the world stages: it fires both with its own camera into its own target, as Minecraft's
 * hooks fire them with Minecraft's, shaken while {@link HarnessCameraShake} is on.
 *
 * <pre>{@code
 * CgWorldRenderer.get().draw(mesh, material).at(x, y, z).submit();
 * HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
 * }</pre>
 */
public final class HarnessWorld {

    private static final CgRenderStage[] WORLD = {CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT};
    private static final Matrix4f VIEW = new Matrix4f(), PROJECTION = new Matrix4f();

    private HarnessWorld() {
    }

    /** Fires {@code WORLD_OPAQUE} then {@code WORLD_TRANSPARENT}: the camera at the origin, {@code view} whole. */
    public static void fire(HarnessContext ctx, Matrix4fc view, Matrix4fc projection) {
        CgWorldRenderer.get().install();
        HarnessCameraShake.INSTANCE.apply(VIEW.set(view), PROJECTION.set(projection));
        for (CgRenderStage stage : WORLD) {
            stage.host().set(0f, ctx.getScreenWidth(), ctx.getScreenHeight(), ctx.getTargetFramebuffer())
                    .view().set(0, 0, 0, VIEW, PROJECTION);
            stage.fire();
        }
    }
}
