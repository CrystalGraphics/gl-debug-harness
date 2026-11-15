package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgShadingModels;
import com.crystalgraphics.api.material.CgSurfaceMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgDecalComponent;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.prefab.CgPrefab;
import com.crystalgraphics.render.scene.prefab.CgPrefabInstance;
import com.crystalgraphics.render.view.CgView;
import org.joml.Matrix4f;
import org.joml.Quaternionf;


/**
 * A prefab spawned into a scene as components, and the engine's base surface material in each of its forms, in a scene
 * of the gate's own drawn into its view's picture. On one row, from the left:
 *
 * <ul>
 *   <li>The prefab: node {@code base} an unlit red cube, and node {@code tip}, its child a block and a half to its right,
 *       a masked green one: the child stands where its node's rest puts it, riding its parent.</li>
 *   <li>A two-sided translucent blue cube at half its alpha: blue over the background.</li>
 *   <li>A grey cube lit by the world, full bright: brighter than the background.</li>
 *   <li>An unlit yellow decal on the grey cube's face: yellow at its centre, grey around it.</li>
 * </ul>
 *
 * <p>Read back after {@link #CHECK_AT} frames, so every pipeline has built; PASS or FAIL on stdout.</p>
 */
public class CgScenePrefabScene implements InteractiveSceneLifecycle {

    private static final int W = 160, H = 120;
    private static final int CHECK_AT = 90, GIVE_UP = 600;
    private static final Matrix4f PROJECTION = CgCamera.perspectiveMatrix(new Matrix4f(), 60f, (float) W / H, 0.05f, 100f);
    private static final double Z = -6.0, FACE_Z = Z + 0.45;
    /** Where each stands along the row, 6 blocks ahead; their faces toward the eye half a cube nearer. */
    private static final float BASE_X = -3.2f, TIP_OFFSET = 1.6f, GLASS_X = 1.0f, LIT_X = 2.8f;
    private static final String[] NAMES = {"red base", "green tip", "blue glass", "lit grey", "yellow decal"};

    private final CgScene scene = new CgScene();
    private final CgView view = CgView.of(scene);
    private CgPrefabInstance prefab;
    private CgMeshComponent glass, lit;
    private CgDecalComponent decal;
    private HarnessTarget main;
    private final byte[][] pixels = new byte[NAMES.length][];
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgMesh cube = CgMeshShapes.cube();
        CgPrefab model = CgPrefab.builder("harness:scene_prefab")
                .node("base").scale(0.9f)
                .part(CgSurfaceMaterial.of("harness:prefab_red").shading(CgShadingModels.UNLIT).tint(0xFFFF0000).definition(), cube)
                .node("tip").parent("base").at(TIP_OFFSET / 0.9f, 0f, 0f)
                .part(CgSurfaceMaterial.of("harness:prefab_green").shading(CgShadingModels.UNLIT).masked(0.5f).tint(0xFF00FF00)
                        .definition(), cube)
                .build();
        prefab = scene.spawn(model).at(BASE_X, 0, Z).spawn();
        glass = scene.mesh(cube, CgSurfaceMaterial.of("harness:glass").blend(CgBlendMode.ALPHA).twoSided()
                .shading(CgShadingModels.UNLIT).tint(0x800000FF).build()).at(GLASS_X, 0, Z).scale(0.9f).spawn();
        lit = scene.mesh(cube, CgSurfaceMaterial.of("harness:lit").tint(0xFFC0C0C0).build()).at(LIT_X, 0, Z).scale(0.9f)
                .fullBright().spawn();
        // Its X looks down onto what it lands on: turned to look along -z, onto the base's front face
        decal = scene.decal(CgSurfaceMaterial.decal("harness:yellow_decal").shading(CgShadingModels.UNLIT).tint(0xFFFFFF00).build())
                .at(LIT_X, 0, FACE_Z).rotation(new Quaternionf().rotateY((float) Math.PI / 2)).size(0.3f, 0.12f, 0.12f).spawn();
        main = HarnessTarget.create(W, H, true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        if (frame > CHECK_AT + 30 || frame > GIVE_UP) {
            report();
            running = false;
            return;
        }
        main.bind();
        main.clear(0.05f, 0.05f, 0.07f, 1f);
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, main.external()).camera().set(0, 0, 0, new Matrix4f(), PROJECTION);
        HarnessWorld.fireCallbacks();
        main.unbind();

        scene.environment().set(CgView.main().environment());
        scene.tick(1.0 / 60.0);
        view.camera().set(0, 0, 0, new Matrix4f(), PROJECTION);
        CgTexture2D picture = view.renderPicture(W, H, 0xFF0D0D12);
        if (frame == CHECK_AT) {
            int row = H / 2;
            read(picture, 0, column(BASE_X, FACE_Z), row);
            read(picture, 1, column(BASE_X + TIP_OFFSET, FACE_Z), row);
            read(picture, 2, column(GLASS_X, FACE_Z), row);
            read(picture, 3, column(LIT_X, FACE_Z), row + 5);
            read(picture, 4, column(LIT_X, FACE_Z), row);
        }
        frame++;
    }

    /** The pixel column a point {@code x} blocks right of centre and {@code z} ahead lands on. */
    private static int column(float x, double z) {
        float halfWidth = (float) (-z * Math.tan(Math.toRadians(30))) * W / H;
        return Math.round(W / 2f + x / halfWidth * (W / 2f));
    }

    private void read(CgTexture2D picture, int which, int x, int y) {
        CgReadback.texture(picture, 0, x, y, 1, 1, d -> {
            byte[] out = new byte[4];
            d.get(out);
            pixels[which] = out;
        });
    }

    private void report() {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        String fail = null;
        if (DeviceErrors.checkAndLog("scene-prefab")) fail = "GL errors, logged above";
        else if (PlatformServiceHarness.validationErrors() > 0) fail = "Vulkan validation errors, logged above";
        for (int i = 0; i < pixels.length && fail == null; i++) if (pixels[i] == null) fail = "the " + NAMES[i] + "'s readback never came back";
        if (fail == null) {
            for (int i = 0; i < pixels.length; i++) {
                System.out.printf("[scene-prefab]   %s: (%d, %d, %d)%n", NAMES[i], pixels[i][0] & 0xFF, pixels[i][1] & 0xFF, pixels[i][2] & 0xFF);
            }
            fail = lead(0, 0, fail);
            fail = lead(1, 1, fail);
            fail = lead(2, 2, fail);
            int grey = (pixels[3][0] & 0xFF) + (pixels[3][1] & 0xFF) + (pixels[3][2] & 0xFF);
            if (fail == null && grey < 3 * 60) fail = "the lit grey cube is no brighter than the background: " + grey / 3;
            int r = pixels[4][0] & 0xFF, g = pixels[4][1] & 0xFF, b = pixels[4][2] & 0xFF;
            if (fail == null && (g < 150 || r < 150 || b > 100)) fail = "the decal's centre is not yellow";
        }
        System.out.println(fail == null ? "[scene-prefab] PASS on " + on : "[scene-prefab] FAIL on " + on + ": " + fail);
    }

    /** Whether {@code channel} leads pixel {@code which} by a clear margin. */
    private String lead(int which, int channel, String fail) {
        byte[] p = pixels[which];
        int lead = p[channel] & 0xFF, other = Math.max(p[(channel + 1) % 3] & 0xFF, p[(channel + 2) % 3] & 0xFF);
        return fail == null && lead < other + 40 ? "the " + NAMES[which] + " is not where it stands" : fail;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        prefab.remove();
        glass.remove();
        lit.remove();
        decal.remove();
        view.releasePicture();
        main.delete();
    }
}
