package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.demo.anim.CgAnimDemo;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.camera.Camera3D;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;


/**
 * The engine's animation demo ({@link CgAnimDemo}) on the harness's floor: a station per animation feature, the bundled
 * humanoids beside every mounted kit character (CrystalGraphics' own {@code kk} namespace: Goku). Scene id
 * {@code anim-demo}; the same demo is {@code CgRenderDemo}'s {@code anim} in Minecraft.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=anim-demo"
 * // 1-9: the camera to a station; the station nearest the camera takes the arrow keys, ` and \ (the HUD lists them)
 * }</pre>
 */
public final class CgAnimDemoScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private CgAnimDemo demo;
    private Camera3D camera;
    private final CgCamera view = new CgCamera();
    private double seconds;
    private boolean placed;

    @Override
    public void init(HarnessContext ctx) {
        camera = ctx.getCamera3D();
        demo = new CgAnimDemo(CgAnimDemo.demoKits());
        demo.precache();
        // The loading screen holds until every character has loaded or failed
        CgLoadPlan.get().task("anim-demo:characters").render(task -> {
            if (demo.ready()) return true;
            task.nextFrame();
            return false;
        }).start();
        float[] v = new float[]{0f, 1.3f, 5f, 0f, -5f};
        camera.moveCamera(v[0], v[1], v[2]);
        camera.setYaw(v[3]);
        camera.setPitch(v[4]);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        seconds += frame.getDeltaTime();
        view.position(camera.getPosX(), camera.getPosY(), camera.getPosZ());
        demo.submit(view, 0, 0, 0, seconds);
        placed = demo.ready();
        HarnessWorld.fire(ctx, camera.getViewMatrix(), ctx.getProjection());
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed() || event.repeat()) return true;
        int key = event.key();
        if (key >= CgKeyCodes.KEY_1 && key <= CgKeyCodes.KEY_9 && placed) {
            int i = key - CgKeyCodes.KEY_1;
            if (i < demo.stations()) {
                float[] v = demo.view(i);
                camera.moveCamera(v[0], v[1], v[2]);
                camera.setYaw(v[3]);
                camera.setPitch(v[4]);
            }
            return true;
        }
        for (int k : CgAnimDemo.KEYS) if (k == key) demo.press(key, false);
        return true;
    }

    @Override
    public String hudLine() {
        return "\nStation [1-" + demo.stations() + "]" + demo.hud();
    }

    @Override
    public void dispose() {
        demo.delete();
    }

    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean editorEnvironment() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
