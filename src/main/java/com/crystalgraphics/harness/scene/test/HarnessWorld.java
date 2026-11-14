package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.harness.camera.HarnessCameraShake;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.runtime.HarnessEnvironment;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.view.CgView;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The harness as a host of the world callbacks: it fills the host frame with its own camera and target and fires both,
 * as Minecraft's hooks fire them with Minecraft's, shaken while {@link HarnessCameraShake} is on. The scene renderer
 * draws the client scene's main view inside them.
 *
 * <pre>{@code
 * CgScene.client().once().mesh(mesh, material).at(x, y, z).add();       // or a component spawned once
 * HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
 * }</pre>
 *
 * <pre>{@code
 * // A target and camera of the scene's own
 * CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, w, h, target).camera().set(0, 0, 0, view, projection);
 * HarnessWorld.fireCallbacks();
 * }</pre>
 */
public final class HarnessWorld {

    private static final Matrix4f VIEW = new Matrix4f(), PROJECTION = new Matrix4f();

    private HarnessWorld() {
    }

    /** Both world callbacks into the harness's target: the camera at the origin, {@code view} whole. */
    public static void fire(HarnessContext ctx, Matrix4fc view, Matrix4fc projection) {
        HarnessEnvironment.INSTANCE.submit(ctx.getCamera3D());
        HarnessCameraShake.INSTANCE.apply(VIEW.set(view), PROJECTION.set(projection));
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, ctx.getScreenWidth(), ctx.getScreenHeight(), ctx.getTarget())
                .camera().set(0, 0, 0, VIEW, PROJECTION);
        fireCallbacks();
    }

    /** Fires the opaque then the transparent callback under the host frame as it stands, for the main view. */
    public static void fireCallbacks() {
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).view(CgView.main());
        CgStageHost.fire(CgStageHost.WORLD_OPAQUE);
        CgStageHost.fire(CgStageHost.WORLD_TRANSPARENT);
    }
}
