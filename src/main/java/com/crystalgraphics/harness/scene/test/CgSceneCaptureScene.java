package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgShadingModels;
import com.crystalgraphics.api.material.CgSurfaceMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgSceneCaptureComponent;
import com.crystalgraphics.render.target.CgRenderTarget;
import com.crystalgraphics.render.view.CgView;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Render targets and scene capture, in a scene of the gate's own drawn into its view's picture. A monitor cube faces the
 * eye; its unlit material samples the render target asset {@code harness:textures/capture/monitor}, which a capture
 * standing off to the side fills with what it sees: a subject cube, red, then swapped for a green one.
 *
 * <ul>
 *   <li>On demand: asked once, the monitor shows red; the subject swapped, it still shows red; every frame, green.</li>
 *   <li>Copying the camera ({@code BP_CopyCameraCapture}'s shape): a second capture copies the view's camera and
 *       captures once, final colour, into a target of the view's size: its pixels are the view's own.</li>
 *   <li>No scene: a target cleared to blue reads blue; a material drawn over it reads that material's yellow.</li>
 * </ul>
 *
 * <p>Every read is an async readback; PASS or FAIL on stdout. The scene's steps count from the frame the precache has
 * nothing left and the three surfaces are built (a fixed frame raced the precache once the post chain's grew).</p>
 */
public class CgSceneCaptureScene implements InteractiveSceneLifecycle {

    private static final int W = 160, H = 120;
    /** The scene's steps, in frames from the first ready one; the target-only check at a fixed frame; when to give up. */
    private static final int CAPTURE_AT = 0, SWAP_AT = 30, EVERY_AT = 60, COPY_AT = 90, REPORT_AT = 120, DRAWN_AT = 20,
            READY_FROM = 50, GIVE_UP = 600;
    private static final Matrix4f PROJECTION = CgCamera.perspectiveMatrix(new Matrix4f(), 60f, (float) W / H, 0.05f, 100f);
    private static final String MONITOR = "harness:textures/capture/monitor";
    private static final int BACKGROUND = 0xFF0D0D12;
    private static final String[] NAMES = {"on demand", "held after the swap", "every frame", "the view's centre",
            "the copy's centre", "the view's corner", "the copy's corner", "the cleared target", "the drawn material"};
    private static final int DEMAND = 0, HELD = 1, EVERY = 2, VIEW_CENTRE = 3, COPY_CENTRE = 4, VIEW_CORNER = 5,
            COPY_CORNER = 6, CLEARED = 7, DRAWN = 8;

    private final CgScene scene = new CgScene();
    private final CgView view = CgView.of(scene);
    private CgMeshComponent monitor, red, green;
    private CgMaterial monitorMaterial, redMaterial, greenMaterial;
    /** The first frame the precache had emptied and the surfaces were built; -1 until then. */
    private int ready = -1;
    private CgSceneCaptureComponent capture, copy;
    private CgRenderTarget picture, copied, drawn;
    private CgTexture2D yellow;
    private CgMaterial yellowUi;
    private HarnessTarget main;
    private final int[][] pixels = new int[NAMES.length][];
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        CgMesh cube = CgMeshShapes.cube();
        monitorMaterial = CgSurfaceMaterial.of("harness:capture_monitor", MONITOR).shading(CgShadingModels.UNLIT).build();
        redMaterial = CgSurfaceMaterial.of("harness:capture_red").shading(CgShadingModels.UNLIT).tint(0xFFFF0000).build();
        greenMaterial = CgSurfaceMaterial.of("harness:capture_green").shading(CgShadingModels.UNLIT).tint(0xFF00FF00).build();
        monitor = scene.mesh(cube, monitorMaterial).at(0, 0, -6).scale(2f).spawn();
        red = scene.mesh(cube, redMaterial).at(20, 0, -4).spawn();
        green = scene.mesh(cube, greenMaterial).at(20, 0, -4).spawn();
        green.visible(false);
        picture = CgRenderTarget.of(MONITOR);
        // Its +x is where it looks: turned to look along -z, at the subject 4 blocks ahead, out of the view's sight
        capture = scene.capture().target(picture).at(20, 0, 0).rotation(new Quaternionf().rotateY((float) Math.PI / 2)).spawn();
        // Where nothing draws a target shows its clear colour (this scene has no sky): the view's picture's, to compare
        copied = CgRenderTarget.create("harness:capture_copy", W, H, CgRenderTarget.Format.RGBA8).clearColor(BACKGROUND);
        copy = scene.capture().target(copied).source(CgSceneCaptureComponent.Source.FINAL_COLOR).spawn();
        drawn = CgRenderTarget.create("harness:capture_drawn", 16, 16, CgRenderTarget.Format.RGBA8).clearColor(0xFF0000FF);
        yellow = CgTextures.of("harness:capture_yellow", CgTextureDesc.of(1, 1, CgFormat.RGBA8_UNORM));
        yellow.write(0, 0, 0, 1, 1, CgFormat.RGBA8_UNORM, ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                .put((byte) 255).put((byte) 255).put((byte) 0).put((byte) 255).flip());
        yellowUi = CgMaterial.of(CgMaterialDefinition.of("harness:capture_yellow_ui", CgCanvasMaterials.IMAGE_SHADER)
                .texture("_MainTex", yellow).build());
        main = HarnessTarget.create(W, H, true);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        if (ready < 0 && frame >= READY_FROM && CgPipelinePrecache.remaining() == 0 && monitorMaterial.precache()
                && redMaterial.precache() && greenMaterial.precache()) {
            ready = frame;
        }
        if (ready >= 0 && frame - ready >= REPORT_AT || ready < 0 && frame >= GIVE_UP) {
            report();
            running = false;
            return;
        }
        int step = ready < 0 ? -1 : frame - ready;
        main.bind();
        main.clear(0.05f, 0.05f, 0.07f, 1f);
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, main.external()).camera().set(0, 0, 0, new Matrix4f(), PROJECTION);
        HarnessWorld.fireCallbacks();
        main.unbind();

        if (frame == DRAWN_AT) {
            drawn.clear();
            drawn.readback(d -> pixels[CLEARED] = pixel(d, 16, 8, 8));
            drawn.drawMaterial(yellowUi);
            drawn.readback(d -> pixels[DRAWN] = pixel(d, 16, 8, 8));
        }
        if (step == CAPTURE_AT) capture.captureScene();
        if (step == COPY_AT) copy.copyCamera(view).captureScene();   // at the next frame, before the view draws

        scene.environment().set(CgView.main().environment());
        scene.tick(1.0 / 60.0);
        view.camera().set(0, 0, 0, new Matrix4f(), PROJECTION);
        CgTexture2D shown = view.renderPicture(W, H, BACKGROUND);

        if (step == SWAP_AT) {
            read(shown, DEMAND, W / 2, H / 2);
            red.visible(false);
            green.visible(true);
        } else if (step == EVERY_AT) {
            read(shown, HELD, W / 2, H / 2);
            capture.everyFrame(true);
        } else if (step == COPY_AT) {
            read(shown, EVERY, W / 2, H / 2);
        } else if (step == COPY_AT + 1) {
            read(shown, VIEW_CENTRE, W / 2, H / 2);
            read(shown, VIEW_CORNER, 5, 5);
            copied.readback(d -> {
                pixels[COPY_CENTRE] = pixel(d, W, W / 2, H / 2);
                pixels[COPY_CORNER] = pixel(d, W, 5, 5);
            });
        }
        frame++;
    }

    private void read(CgTexture2D texture, int which, int x, int y) {
        CgReadback.texture(texture, 0, x, y, 1, 1, d -> pixels[which] = pixel(d, 1, 0, 0));
    }

    /** Pixel {@code (x, y)} of an RGBA8 readback {@code width} wide, rows bottom first. */
    private static int[] pixel(ByteBuffer d, int width, int x, int y) {
        int at = (y * width + x) * 4;
        return new int[]{d.get(at) & 0xFF, d.get(at + 1) & 0xFF, d.get(at + 2) & 0xFF};
    }

    private void report() {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        String fail = null;
        if (DeviceErrors.checkAndLog("scene-capture")) fail = "GL errors, logged above";
        else if (ready < 0) fail = "the precache never emptied in " + GIVE_UP + " frames";
        else if (PlatformServiceHarness.validationErrors() > 0) fail = "Vulkan validation errors, logged above";
        for (int i = 0; i < pixels.length && fail == null; i++) if (pixels[i] == null) fail = NAMES[i] + "'s readback never came back";
        if (fail == null) {
            for (int i = 0; i < pixels.length; i++) {
                System.out.printf("[scene-capture]   %s: (%d, %d, %d)%n", NAMES[i], pixels[i][0], pixels[i][1], pixels[i][2]);
            }
            fail = lead(DEMAND, 0, fail);
            fail = lead(HELD, 0, fail);
            fail = lead(EVERY, 1, fail);
            fail = lead(CLEARED, 2, fail);
            int[] y = pixels[DRAWN];
            if (fail == null && (y[0] < 200 || y[1] < 200 || y[2] > 60)) fail = "the material drawn into the target is not its yellow";
            fail = same(VIEW_CENTRE, COPY_CENTRE, fail);
            fail = same(VIEW_CORNER, COPY_CORNER, fail);
            if (fail == null && capture.captures() < REPORT_AT - EVERY_AT - 5) fail = "every frame captured " + capture.captures() + " times";
            if (fail == null && copy.captures() != 1) fail = "the copy captured " + copy.captures() + " times, not once";
        }
        System.out.println(fail == null ? "[scene-capture] PASS on " + on + ": a capture on demand held, every frame followed, "
                + "a copied camera matched its view, a material drew into a target with no scene"
                : "[scene-capture] FAIL on " + on + ": " + fail);
    }

    /** Whether {@code channel} leads pixel {@code which} by a clear margin. */
    private String lead(int which, int channel, String fail) {
        int[] p = pixels[which];
        int lead = p[channel], other = Math.max(p[(channel + 1) % 3], p[(channel + 2) % 3]);
        return fail == null && lead < other + 60 ? NAMES[which] + " is not the colour it should be" : fail;
    }

    /** Whether two pixels match within a step of rounding. */
    private String same(int a, int b, String fail) {
        for (int c = 0; c < 3 && fail == null; c++) {
            if (Math.abs(pixels[a][c] - pixels[b][c]) > 6) return NAMES[a] + " and " + NAMES[b] + " differ";
        }
        return fail;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        capture.remove();
        copy.remove();
        monitor.remove();
        red.remove();
        green.remove();
        picture.release();
        copied.release();
        drawn.release();
        yellow.release();
        view.releasePicture();
        main.delete();
    }
}
