package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.demo.CgLightsDemo;
import com.crystalgraphics.demo.CgShadowsDemo;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.runtime.HarnessEnvironment;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.vfx.CgVfx;

/**
 * The engine's lights demo ({@link CgLightsDemo}) on the harness world's white ground, playing live, at night: every
 * kind of world light, the particle lights and the lightning, to fly round and look at. Scene id {@code lights-demo};
 * the same demo is {@code CgRenderDemo}'s {@code lights} in Minecraft. Stone fills the world below the ground, open
 * sky above it, so the lights read the blocks round them as they would over a flat world.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lights-demo"
 * // O shadows, R MegaLights' resolution, Z quality, F9 day and night
 * // Y / Shift+Y time faster / slower (0 to 1), Tab pause or play
 * }</pre>
 */
public final class CgLightsDemoScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private final CgLightsDemo demo = new CgLightsDemo(CgVfx.owner("harness"));
    private Camera3D camera;
    /** The world's time dilation a press of Y climbs, from held to full speed: kit-cues' ladder. */
    private static final float[] SPEEDS = {0f, 0.01f, 0.05f, 0.1f, 0.25f, 0.5f, 1f};
    private double seconds;
    private int speed = SPEEDS.length - 1, resumeSpeed = speed;

    @Override
    public void init(HarnessContext ctx) {
        camera = ctx.getCamera3D();
        HarnessFlatWorld.provide(0, 15);
        if (!HarnessEnvironment.INSTANCE.night()) HarnessEnvironment.INSTANCE.toggleNight();
        demo.precache();
        CgLoadPlan.get().task("lights-demo:show").render(task -> {
            if (demo.ready()) return true;
            task.nextFrame();
            return false;
        }).start();
        // In front of the arena, level, at the height the bisect scene frames it from
        camera.moveCamera(0f, 4f, 24f);
        camera.setYaw(0f);
        camera.setPitch(0f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // The world's dilation slows the particles; the show's own lights keep to the same clock
        CgWorld.client().timeDilation(SPEEDS[speed]);
        seconds += frame.getDeltaTime() * SPEEDS[speed];
        if (demo.ready()) demo.submit(CgScene.client(), 0, 0, 0, seconds);
        HarnessWorld.fire(ctx, camera.getViewMatrix(), ctx.getProjection());
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed() || event.repeat()) return true;
        switch (event.key()) {
            case CgKeyCodes.KEY_O -> CgShadowsDemo.toggleShadows();
            case CgKeyCodes.KEY_R -> CgShadowsDemo.cycleShadeScale();
            case CgKeyCodes.KEY_Z -> CgShadowsDemo.cycleQuality();
            case CgKeyCodes.KEY_Y -> {
                speed = shift() ? Math.max(0, speed - 1) : Math.min(SPEEDS.length - 1, speed + 1);
                if (speed > 0) resumeSpeed = speed;
            }
            case CgKeyCodes.KEY_TAB -> speed = speed == 0 ? resumeSpeed : 0;
            default -> { }
        }
        return true;
    }

    private static boolean shift() {
        return CgInput.isKeyDown(CgKeyCodes.KEY_LSHIFT) || CgInput.isKeyDown(CgKeyCodes.KEY_RSHIFT);
    }

    @Override
    public String hudLine() {
        return CgShadowsDemo.lightingLine() + "\nNight [F9]: " + (HarnessEnvironment.INSTANCE.night() ? "on" : "off")
                + " | Time [Y/Shift+Y]: " + (speed == 0 ? "paused [Tab]" : SPEEDS[speed] + "x");
    }

    @Override
    public void dispose() {
        demo.delete();
        CgWorld.client().timeDilation(1f);
        CgPlatform.provide(CgLevels.HOST, null);
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean editorEnvironment() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
