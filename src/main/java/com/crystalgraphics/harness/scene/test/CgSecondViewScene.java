package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.view.CgView;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/**
 * Views of one's own: the game's view, a second view of the client scene from its own camera, and a view of a scene
 * of the lab's own, each drawn into a target of its own in one frame. A red cube stands in the client scene ahead of
 * the main camera and beside the second; a green ball stands in the lab's scene alone.
 *
 * <ul>
 *   <li>The main view sees the red cube, never the ball: another scene's primitives are not the client's.</li>
 *   <li>The second view sees the same cube from its side: a view of the client scene, drawn by {@code renderInto}
 *       after the main view, leaves the main view's picture as it was.</li>
 *   <li>The lab's view sees the ball and never the cube.</li>
 * </ul>
 *
 * <p>Read back after {@link #CHECK_AT} frames, so every pipeline has built; PASS or FAIL on stdout.</p>
 */
public class CgSecondViewScene implements InteractiveSceneLifecycle {

    private static final int W = 160, H = 120;
    private static final int CHECK_AT = 90, GIVE_UP = 600;
    private static final Matrix4f PROJECTION = CgCamera.perspectiveMatrix(new Matrix4f(), 60f, (float) W / H, 0.05f, 100f);
    /** The cube's centre, 5 ahead of the main eye; the second eye stands 5 to its right, looking at it. */
    private static final double CUBE_Z = -5.0, SIDE_X = 5.0;

    private CgMesh cube, ball;
    private CgMaterial solid;
    private HarnessTarget main, second, lab;
    private final CgScene labScene = new CgScene();
    private CgMeshComponent labBall;
    private final CgView eye = CgView.create("second-view.eye");
    private final CgView labView = CgView.of(labScene);
    private final Matrix4f sideLook = new Matrix4f();
    private byte[] mainPixel, secondPixel, labPixel, mainAfter;
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        cube = CgMeshShapes.cube();
        ball = CgMeshShapes.sphere(16, 24);
        solid = CgMaterial.load("assets/harness/shader/dual_path_test.shader");
        main = HarnessTarget.create(W, H, true);
        second = HarnessTarget.create(W, H, true);
        lab = HarnessTarget.create(W, H, true);
        labBall = labScene.mesh(ball, solid).at(0, 0, CUBE_Z).customData(0, 0.1f, 1f, 0.1f, 1f).spawn();
        // From +x looking along -x at the cube
        sideLook.setLookAt(0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 0f);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        if (mainPixel != null && secondPixel != null && labPixel != null && mainAfter != null || frame > GIVE_UP) {
            report();
            running = false;
            return;
        }
        boolean check = frame == CHECK_AT;
        CgScene.client().once().mesh(cube, solid).at(0, 0, CUBE_Z).customData(0, 1f, 0.1f, 0.1f, 1f).add();

        main.bind();
        main.clear(0.05f, 0.05f, 0.07f, 1f);
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, main.external()).camera().set(0, 0, 0, new Matrix4f(), PROJECTION);
        HarnessWorld.fireCallbacks();
        main.unbind();
        if (check) CgReadback.texture(main.getColor(), 0, W / 2, H / 2, 1, 1, d -> mainPixel = bytes(d));

        second.clear(0.05f, 0.05f, 0.07f, 1f);
        eye.camera().set(SIDE_X, 0, CUBE_Z, sideLook, PROJECTION);
        eye.renderInto(second.external());
        if (check) CgReadback.texture(second.getColor(), 0, W / 2, H / 2, 1, 1, d -> secondPixel = bytes(d));

        lab.clear(0.05f, 0.05f, 0.07f, 1f);
        labView.camera().set(0, 0, 0, new Matrix4f(), PROJECTION);
        labView.renderInto(lab.external());
        if (check) {
            CgReadback.texture(lab.getColor(), 0, W / 2, H / 2, 1, 1, d -> labPixel = bytes(d));
            // The main view's picture, read again after both others drew: theirs never land in it
            CgReadback.texture(main.getColor(), 0, W / 2, H / 2, 1, 1, d -> mainAfter = bytes(d));
        }
        frame++;
    }

    private void report() {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        String fail = null;
        if (DeviceErrors.checkAndLog("second-view")) fail = "GL errors, logged above";
        else if (PlatformServiceHarness.validationErrors() > 0) fail = "Vulkan validation errors, logged above";
        else if (mainPixel == null || secondPixel == null || labPixel == null || mainAfter == null) fail = "a readback never came back";
        else {
            fail = mostly("main view", mainPixel, 0, fail);
            fail = mostly("second view", secondPixel, 0, fail);
            fail = mostly("lab view", labPixel, 1, fail);
            for (int c = 0; c < 4; c++) {
                if (fail == null && mainPixel[c] != mainAfter[c]) fail = "the main view's picture changed after the other views drew";
            }
        }
        System.out.println(fail == null ? "[second-view] PASS on " + on : "[second-view] FAIL on " + on + ": " + fail);
    }

    /** Whether {@code channel} (0 red, 1 green) leads the pixel by a clear margin: that view's own object. */
    private static String mostly(String what, byte[] p, int channel, String fail) {
        int r = p[0] & 0xFF, g = p[1] & 0xFF, b = p[2] & 0xFF;
        System.out.printf("[second-view]   %s centre: (%d, %d, %d), expected %s%n", what, r, g, b, channel == 0 ? "red" : "green");
        int lead = channel == 0 ? r : g, other = Math.max(channel == 0 ? g : r, b);
        return fail == null && lead < other + 40 ? what + " shows " + (channel == 0 ? "no red cube" : "no green ball") : fail;
    }

    private static byte[] bytes(ByteBuffer d) {
        byte[] out = new byte[4];
        d.get(out);
        return out;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return false; }
    @Override public boolean shouldShutdownOnComplete() { return true; }

    @Override
    public void dispose() {
        labBall.remove();
        main.delete();
        second.delete();
        lab.delete();
    }
}
