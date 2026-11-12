package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.demo.CgTextDemo;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.util.io.CgIO;
import org.joml.Matrix4f;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link CgTextDemo}'s text styles under VFX Lab's sky and floor (F9 day and night): the 4x4 grid of stations and
 * Kakarot's "Critical!" beside its damage number, facing the camera's start. Scene id {@code text-demo}; the same
 * stations as the game's demo scene {@code text}, to tune a style before it goes into a mod's assets. The critical's
 * style is the asset {@code crystalgraphics:demo/critical}, read from its source folder: save it, Ctrl+R, and it draws.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=text-demo"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=text-demo --rhi=vulkan"
 * }</pre>
 */
public final class CgTextDemoScene implements InteractiveSceneLifecycle {

    /** Where the camera starts: ahead of the grid, a little above its front row. */
    private static final float CAMERA_X = 0f, CAMERA_Y = 6f, CAMERA_Z = 24f;
    /** The grid's anchor: its front row's left station, so the grid and the critical's word centre on x 0. */
    private static final double ANCHOR_X = 3.75;
    private static final String CRITICAL = "crystalgraphics:demo/critical";

    private final CgTextDemo text = new CgTextDemo(CgDefaultFont.regular(48));
    private final CgCamera start = new CgCamera();

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(CAMERA_X, CAMERA_Y, CAMERA_Z);
        ctx.getCamera3D().setPitch(-8f);
        ctx.getCamera3D().setMoveSpeed(8f);
        Matrix4f identity = new Matrix4f();
        start.set(CAMERA_X, CAMERA_Y, CAMERA_Z, identity, identity);
        mountEngineAssets();
        text.precache();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (text.ready()) text.submit(CgWorldRenderer.get(), start, ANCHOR_X, 0.0, 0.0, frame.getElapsedTime());
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    /**
     * Registers the engine's own namespace from its source folder, as a host's mount does at pre-init: the harness
     * mounts no namespace, so the critical's style would never be registered.
     */
    private static void mountEngineAssets() {
        CgAssetRegistry registry = CgVfxAssets.instance().registry();
        if (registry.entry(CgLookKinds.TEXT_STYLE, CRITICAL) != null) return;
        for (File root : CgIO.overrideRoots()) {
            Path assets = root.toPath().resolve("assets/crystalgraphics");
            if (Files.isDirectory(assets.resolve("text_styles"))) {
                registry.scan("crystalgraphics", assets, CgAssetSource.DEV);
                return;
            }
        }
        throw new IllegalStateException("text-demo: no source folder holds assets/crystalgraphics/text_styles");
    }

    @Override
    public void dispose() {
        text.delete();
    }

    @Override public boolean editorEnvironment() { return true; }
    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
