package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.demo.CgVfxFluidDemo;
import com.crystalgraphics.demo.CgVfxShowcase;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.util.HarnessFontUtil;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;

import java.util.List;

/**
 * {@link CgVfxFluidDemo}, the engine's gas and smoke: a grid of stations with a mushroom cloud behind it, on the
 * showcase's floor under its sky, replayed every {@link CgVfxFluidDemo#PERIOD} seconds. N flies to the next station,
 * Shift+N the one before, M back to the overview. It plays at Ultra, whose pool of fluid tiles holds every station at
 * once, and puts the tier back when it closes. Scene id {@code vfx-fluid}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-fluid"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-fluid --device=vulkan"
 * }</pre>
 */
public final class CgVfxFluidScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    /** How far a shot's camera rises for each block it stands back: a look down of about ten degrees. */
    private static final float RISE = 0.18f;

    private final CgVfxShowcase stage = new CgVfxShowcase();
    private CgVfxFluidDemo fluid;
    private CgFont font;
    private Camera3D camera;
    private final HarnessVfxControls controls = new HarnessVfxControls();
    private boolean shift;
    /** The station framed, -1 for the overview. */
    private int station = -1;
    private CgQuality quality;

    @Override
    public void init(HarnessContext ctx) {
        quality = CgGraphicsSettings.QUALITY.get();
        CgGraphicsSettings.QUALITY.set(CgQuality.ULTRA);
        camera = ctx.getCamera3D();
        camera.setMoveSpeed(20f);
        font = CgFont.load(HarnessFontUtil.LATIN_FONT, CgFontStyle.REGULAR, 48);
        fluid = new CgVfxFluidDemo(font);
        overview();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgWorldRenderer world = CgWorldRenderer.get();
        fluid.submit(world, 0.0, 0.0, 0.0, CgVfxDemoControls.get().time(frame.getElapsedTime()));
        stage.submitStage(world, 0.0, 0.0, 0.0, camera.getPosX(), camera.getPosY(), camera.getPosZ());
        HarnessWorld.fire(ctx, camera.getViewMatrix(), ctx.getProjection());
    }

    /** The whole grid from in front, the mushroom cloud towering behind it. */
    private void overview() {
        station = -1;
        camera.moveCamera(0f, 26f, CgVfxFluidDemo.REACH + 40f);
        camera.setYaw(0f);
        camera.setPitch(-9f);
    }

    private void frame(int i) {
        List<CgVfxFluidDemo.Shot> shots = fluid.shots();
        station = Math.floorMod(i, shots.size());
        CgVfxFluidDemo.Shot s = shots.get(station);
        camera.moveCamera(s.x(), s.y() + s.distance() * RISE, s.z() + s.distance());
        camera.setYaw(0f);
        camera.setPitch((float) -Math.toDegrees(Math.atan(RISE)));
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        int key = event.key();
        if (key == CgKeyCodes.KEY_LSHIFT || key == CgKeyCodes.KEY_RSHIFT) shift = event.pressed();
        if (event.pressed() && !event.repeat()) {
            if (key == CgKeyCodes.KEY_N) frame(station < 0 ? (shift ? -1 : 0) : station + (shift ? -1 : 1));
            else if (key == CgKeyCodes.KEY_M) overview();
        }
        return controls.consume(event);
    }

    @Override
    public String hudLine() {
        String at = station < 0 ? "overview" : (station + 1) + "/" + fluid.shots().size() + " " + fluid.shots().get(station).title();
        return CgVfxDemoControls.get().hudLines(false) + "\nN / Shift+N: station (" + at + ")   M: overview";
    }

    @Override
    public void dispose() {
        if (fluid != null) fluid.delete();
        stage.delete();
        if (font != null) font.dispose();
        if (quality != null) CgGraphicsSettings.QUALITY.set(quality);
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
