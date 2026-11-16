package com.crystalgraphics.harness.scene.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;

/**
 * Temporal anti-aliasing to play with (R14a): a fan of thin wires and a far fence of posts that stair-step and crawl
 * without it, a spinning bar that must leave no ghost behind it, and three dithered panes (DitherOpacityMask at a
 * quarter, half and three quarters) that settle to smooth translucency with it. Scene id {@code taa-lab}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=taa-lab --rhi=vulkan"
 * // K: temporal AA on or off       N: the jitter on or off (off, the resolve settles the dither and leaves edges)
 * // P: the spinner paused or not   Up / Down: it spins faster / slower
 * }</pre>
 */
public final class CgTemporalAALabScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final String DIR = "assets/harness/shader/";
    private static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f, 8f};

    private final List<CgMeshComponent> spawned = new ArrayList<>();
    private CgMeshComponent spinner;
    private Camera3D camera;
    private boolean taa = true, jitter = true, paused;
    private int speed = 2;
    private double angle;

    @Override
    public void init(HarnessContext ctx) {
        camera = ctx.getCamera3D();
        camera.moveCamera(0f, 1.6f, 6f);
        camera.setYaw(0f);
        camera.setPitch(0f);
        camera.setMoveSpeed(4f);
        CgScene scene = CgScene.client();
        CgMesh cube = CgMeshShapes.cube(), quad = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 1f, 1f);
        CgMaterialInstance line = CgMaterial.load(DIR + "taa_lab_line.shader").instance();
        CgMaterialInstance pane = CgMaterial.load(DIR + "taa_lab_pane.shader").instance();
        // A fan of wires, each a few degrees off the last: every slope a stair steps at
        for (int i = 0; i < 24; i++) {
            float turn = (float) Math.toRadians(2 + i * 3.6);
            spawned.add(scene.mesh(cube, line).at(-3.2, 1.6, -2).transform(new Matrix4f().rotateZ(turn).translate(1.6f, 0f, 0f)
                    .scale(3.2f, 0.012f, 0.012f)).customData(0, 1f, 0.9f, 0.6f, 3f).spawn());
        }
        // A far fence: posts thinner than a texel apart, which crawl as the camera moves
        for (int i = 0; i < 60; i++) {
            spawned.add(scene.mesh(cube, line).at(-6 + i * 0.2, 1, -18).scale(0.03f, 2f, 0.03f).customData(0, 0.6f, 0.85f, 1f, 2f).spawn());
        }
        // The spinner: a bright bar over a dark board, whose trail is a ghost if the history is not clipped
        spawned.add(scene.mesh(cube, line).at(2.8, 1.6, -4.2).scale(2.6f, 2.6f, 0.05f).customData(0, 0.08f, 0.08f, 0.1f, 1f).spawn());
        spinner = scene.mesh(cube, line).at(2.8, 1.6, -4).scale(2.2f, 0.14f, 0.14f).customData(0, 1f, 0.35f, 0.25f, 4f).spawn();
        spawned.add(spinner);
        // Dithered panes at three coverages, in front of the fence
        float[] cover = {0.25f, 0.5f, 0.75f};
        for (int k = 0; k < 3; k++) {
            spawned.add(scene.mesh(quad, pane).at(-1.6 + k * 1.6, 1.4, 0).scale(1.2f).customData(0, 0.4f, 0.9f, 0.6f, cover[k]).spawn());
        }
        apply();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!paused) angle += frame.getDeltaTime() * SPEEDS[speed];
        spinner.transform(new Matrix4f().rotateZ((float) angle).scale(2.2f, 0.14f, 0.14f));
        HarnessWorld.fire(ctx, camera.getViewMatrix(), ctx.getProjection());
    }

    private void apply() {
        CgView.main().showFlags().temporalAA(taa);
        CgView.main().jitter(jitter);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed() || event.repeat()) return true;
        switch (event.key()) {
            case CgKeyCodes.KEY_K -> taa = !taa;
            case CgKeyCodes.KEY_N -> jitter = !jitter;
            case CgKeyCodes.KEY_P -> paused = !paused;
            case CgKeyCodes.KEY_UP -> speed = Math.min(SPEEDS.length - 1, speed + 1);
            case CgKeyCodes.KEY_DOWN -> speed = Math.max(0, speed - 1);
            default -> {
                return true;
            }
        }
        apply();
        return true;
    }

    @Override
    public String hudLine() {
        return String.format(Locale.ROOT, "Temporal AA [K]: %s | Jitter [N]: %s | Spinner [P, Up/Down]: %s %sx",
                taa ? "on" : "off", jitter ? "on" : "off", paused ? "paused" : "spinning", SPEEDS[speed])
                + "\nPanes, left to right: a quarter, half and three quarters dithered (DitherOpacityMask)";
    }

    @Override
    public void dispose() {
        for (CgMeshComponent c : spawned) c.remove();
        spawned.clear();
        CgView.main().showFlags().temporalAA(false);
        CgView.main().jitter(false);
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
