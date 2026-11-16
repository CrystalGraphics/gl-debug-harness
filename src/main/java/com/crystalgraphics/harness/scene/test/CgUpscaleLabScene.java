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
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.upscale.CgAntiAliasing;
import com.crystalgraphics.render.post.upscale.CgUpscaleFilter;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;

/**
 * Screen percentage and TSR to play with (R14d, R14e): taa-lab's wires, far fence, spinner and dithered panes over a fine checker floor,
 * drawn at 50 to 100% and upscaled to the window. Scene id {@code upscale-lab}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=upscale-lab --rhi=vulkan"
 * // , / .: screen percentage down / up (50, 58, 67, 77, 100); the harness keeps [ ] for emission    U: the upscale filter    G: the display-based percentage
 * // F5: temporal AA or TSR    F10: the anti-aliasing quality (the harness keeps F6 for kit looks)    N: the jitter on or off    P: the spinner paused or not
 * // F7 / F8: temporal AA, its sharpening    K: the floor filtered (as a mipmapped texture is) or raw
 * }</pre>
 */
public final class CgUpscaleLabScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final String DIR = "assets/harness/shader/";
    /** Unreal's quality steps: Performance, Balanced, Quality, Ultra Quality, native. */
    private static final float[] PERCENTAGES = {50f, 58f, 67f, 77f, 100f};

    private final List<CgMeshComponent> spawned = new ArrayList<>();
    private CgMeshComponent spinner, floor;
    private Camera3D camera;
    private boolean paused;
    private int step = 2;
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
        CgMaterialInstance floorMaterial = CgMaterial.load(DIR + "upscale_lab_floor.shader").instance();
        // Its sides mid-cell: on a cell's edge a side's checker flips pixel to pixel on rounding
        floor = scene.mesh(cube, floorMaterial).at(0.25, -0.05, -20.25).scale(40f, 0.1f, 50f).customData(0, 1f, 0f, 0f, 0f).spawn();
        spawned.add(floor);
        for (int i = 0; i < 24; i++) {
            float turn = (float) Math.toRadians(2 + i * 3.6);
            spawned.add(scene.mesh(cube, line).at(-3.2, 1.6, -2).transform(new Matrix4f().rotateZ(turn).translate(1.6f, 0f, 0f)
                    .scale(3.2f, 0.012f, 0.012f)).customData(0, 1f, 0.9f, 0.6f, 3f).spawn());
        }
        for (int i = 0; i < 60; i++) {
            spawned.add(scene.mesh(cube, line).at(-6 + i * 0.2, 1, -18).scale(0.03f, 2f, 0.03f).customData(0, 0.6f, 0.85f, 1f, 2f).spawn());
        }
        spawned.add(scene.mesh(cube, line).at(2.8, 1.6, -4.2).scale(2.6f, 2.6f, 0.05f).customData(0, 0.08f, 0.08f, 0.1f, 1f).spawn());
        spinner = scene.mesh(cube, line).at(2.8, 1.6, -4).scale(2.2f, 0.14f, 0.14f).customData(0, 1f, 0.35f, 0.25f, 4f).spawn();
        spawned.add(spinner);
        float[] cover = {0.25f, 0.5f, 0.75f};
        for (int k = 0; k < 3; k++) {
            spawned.add(scene.mesh(quad, pane).at(-1.6 + k * 1.6, 1.4, 0).scale(1.2f).customData(0, 0.4f, 0.9f, 0.6f, cover[k]).spawn());
        }
        CgTemporalAA.enable(CgView.main(), true);
        CgView.main().screenPercentage(PERCENTAGES[step]);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!paused) angle += frame.getDeltaTime();
        spinner.transform(new Matrix4f().rotateZ((float) angle).scale(2.2f, 0.14f, 0.14f));
        HarnessWorld.fire(ctx, camera.getViewMatrix(), ctx.getProjection());
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed() || event.repeat()) return true;
        CgView view = CgView.main();
        switch (event.key()) {
            case CgKeyCodes.KEY_COMMA -> view.screenPercentage(PERCENTAGES[step = Math.max(0, step - 1)]);
            case CgKeyCodes.KEY_PERIOD -> view.screenPercentage(PERCENTAGES[step = Math.min(PERCENTAGES.length - 1, step + 1)]);
            case CgKeyCodes.KEY_U -> view.upscaleFilter(CgUpscaleFilter.values()[(view.upscaleFilter().ordinal() + 1) % CgUpscaleFilter.values().length]);
            case CgKeyCodes.KEY_G -> view.screenPercentageAuto(!view.screenPercentageAuto());
            case CgKeyCodes.KEY_F5 -> view.antiAliasing(view.antiAliasing() == CgAntiAliasing.TSR ? CgAntiAliasing.TAA : CgAntiAliasing.TSR);
            case CgKeyCodes.KEY_F10 -> CgGraphicsSettings.ANTI_ALIASING.set(
                    CgQuality.values()[(CgGraphicsSettings.ANTI_ALIASING.get().ordinal() + 1) % CgQuality.values().length]);
            case CgKeyCodes.KEY_N -> view.jitter(!view.jitters());
            case CgKeyCodes.KEY_P -> paused = !paused;
            case CgKeyCodes.KEY_K -> floor.customData(0, floorFiltered() ? 0f : 1f, 0f, 0f, 0f);
            default -> {
                return true;
            }
        }
        return true;
    }

    @Override
    public String hudLine() {
        CgView view = CgView.main();
        return String.format(Locale.ROOT, "Screen %% [, .] auto [G]: %s -> %dx%d of %dx%d | Filter [U]: %s | Jitter [N]: %s | Temporal [F5]: %s",
                view.screenPercentageAuto() ? String.format(Locale.ROOT, "auto %.0f%%", view.resolutionFraction() * 100f)
                        : String.format(Locale.ROOT, "%.0f%%", view.screenPercentage()),
                view.width(), view.height(), view.outputWidth(), view.outputHeight(), view.upscaleFilter(),
                view.jitters() ? "on" : "off", view.antiAliasing()) + " | Quality [F10]: " + CgGraphicsSettings.ANTI_ALIASING.get()
                + "\nSpinner [P]: " + (paused ? "paused" : "spinning") + " | Floor [K]: " + (floorFiltered() ? "filtered" : "raw");
    }

    private boolean floorFiltered() {
        return floor.customData(0, 0) > 0.5f;
    }

    @Override
    public void dispose() {
        for (CgMeshComponent c : spawned) c.remove();
        spawned.clear();
        CgView.main().screenPercentage(100f).screenPercentageAuto(false).upscaleFilter(CgUpscaleFilter.CATMULL_ROM)
                .antiAliasing(CgAntiAliasing.TSR);
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
