package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
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
import com.crystalgraphics.render.host.CgHostFrame;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgPointLight;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * DefaultLit under the scene's lights: a ball lit as Unreal's Default Lit (translucent at full opacity, so the world's
 * deferred apply never adds over it and only its own walk of its grid cell lights it), eight point light components
 * clustered on its east side, as far from it each. Drawn with none of them shown, then four, then all eight: eight must
 * add clearly more than four, as a surface lit from every light its cell lists is (Unreal's forward lighting), where
 * one lit from the four nearest would add the same; its west side, facing away, stays dark. Night under a black
 * lightmap: no sun, no sky, so the lights alone light it.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=lit-spheres --rhi=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [lit-spheres] PASS} or {@code FAIL}; on Vulkan a validation error fails it. Its captures land in its
 * output directory.</p>
 */
public class CgLitSpheresScene implements InteractiveSceneLifecycle {

    private static final int PHASE = 60, W = 320, H = 240, LIGHTS = 8;
    private static final double BX = 0, BY = 70, BZ = 0;
    private static final double EYE_X = BX, EYE_Y = BY, EYE_Z = BZ + 5;
    /** The ball's east and west faces as the eye sees them. */
    private static final double[] EAST = {BX + 0.75, BY, BZ + 0.6}, WEST = {BX - 0.75, BY, BZ + 0.6};
    private static final String[] NAMES = {"none", "four", "eight"};

    private final Matrix4f look = new Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);
    private final Matrix4f projection = CgCamera.perspectiveMatrix(new Matrix4f(), 50f, (float) W / H, 0.05f, 100f);
    private final CgPointLight[] lights = new CgPointLight[LIGHTS];
    private HarnessTarget target;
    /** Black: the sky and the sun the ball would decode from it give nothing. */
    private CgTexture2D lightmap;
    private CgMeshComponent ball;
    private int frame;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        target = HarnessTarget.create(W, H, true);
        ByteBuffer black = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16 * 16; i++) black.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 255);
        lightmap = CgTextures.of("harness.litSpheres", CgTextureDesc.of(16, 16, CgFormat.RGBA8_UNORM));
        lightmap.uploadRegion(0, 0, 0, 16, 16, black.flip());
        CgScene scene = CgScene.client();
        CgMesh sphere = CgMeshShapes.sphere(24, 32);
        ball = scene.mesh(sphere, CgMaterial.load("assets/harness/shader/lit_sphere.shader")).at(BX, BY, BZ).spawn();
        // A ring of eight round the ball's east axis, 2.5 blocks from its centre each: no four of them nearer the rest
        for (int i = 0; i < LIGHTS; i++) {
            double a = i * 2 * Math.PI / LIGHTS;
            lights[i] = scene.pointLight().at(BX + 2.4, BY + 0.7 * Math.sin(a), BZ + 0.7 * Math.cos(a)).radius(8f)
                    .intensity(0.6f).castsShadow(false).sampled(false).visible(false).spawn();
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        int phase = (frame - 1) / PHASE;
        int shown = phase == 0 ? 0 : phase == 1 ? 4 : LIGHTS;
        // Four alternate ones, so the four and the eight light the face from the same middle
        for (int i = 0; i < LIGHTS; i++) lights[i].visible(i < shown * 2 && (shown == LIGHTS || i % 2 == 0));
        target.bind();
        target.clear(0f, 0f, 0f, 1f);
        CgHostFrame host = CgStageHost.frame(CgStageHost.WORLD_OPAQUE);
        host.set(0f, W, H, target.external()).camera().set(EYE_X, EYE_Y, EYE_Z, look, projection);
        host.environment().sun(Float.NaN, 0, 0f, 1f);
        host.textures().importedLightmap(lightmap.rhiTexture(), 16, 16);
        HarnessWorld.fireCallbacks();
        CgFrameRing.endFrame();
        if (frame % PHASE == 0) target.captureToFile(ctx.getOutputDir(), NAMES[phase] + ".png");
        target.unbind();
        if (frame == 3 * PHASE) {
            report(ctx);
            running = false;
        }
    }

    private void report(HarnessContext ctx) {
        String failure = null;
        float[][] at = new float[3][2];
        try {
            for (int k = 0; k < 3; k++) {
                BufferedImage image = ImageIO.read(new File(ctx.getOutputDir(), NAMES[k] + ".png"));
                at[k][0] = patch(image, EAST);
                at[k][1] = patch(image, WEST);
            }
        } catch (IOException e) {
            failure = "the captures did not read back: " + e.getMessage();
        }
        float four = at[1][0] - at[0][0], eight = at[2][0] - at[0][0], away = at[2][1] - at[0][1];
        String readings = String.format("the east face %.1f dark, %.1f under four, %.1f under eight; the west %.1f, %.1f",
                at[0][0], at[1][0], at[2][0], at[0][1], at[2][1]);
        if (failure == null && four < 8f) failure = "four lights add too little to judge: " + readings;
        else if (failure == null && eight < four * 1.25f + 3f) failure = "eight lights add no more than four: " + readings;
        else if (failure == null && away > 0.25f * eight) failure = "the face away from the lights is lit: " + readings;
        boolean glErrors = DeviceErrors.checkAndLog("lit-spheres");
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) failure = "GL errors, logged above";
        else if (validation > 0) failure = validation + " Vulkan validation errors, logged above";
        if (failure != null) System.out.println("[lit-spheres] FAIL on " + on + ": " + failure);
        else System.out.println("[lit-spheres] PASS on " + on + ": " + readings);
    }

    /** The mean of a 5x5 patch's channels round where {@code p} lands. */
    private float patch(BufferedImage image, double[] p) {
        Vector4f clip = new Vector4f((float) (p[0] - EYE_X), (float) (p[1] - EYE_Y), (float) (p[2] - EYE_Z), 1f);
        look.transform(clip);
        projection.transform(clip);
        int x = (int) ((clip.x / clip.w * 0.5f + 0.5f) * W), y = H - 1 - (int) ((clip.y / clip.w * 0.5f + 0.5f) * H);
        float sum = 0f;
        int n = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int rgb = image.getRGB(Math.max(0, Math.min(W - 1, x + dx)), Math.max(0, Math.min(H - 1, y + dy)));
                sum += ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3f;
                n++;
            }
        }
        return sum / n;
    }

    @Override
    public void dispose() {
        for (CgPointLight l : lights) if (l != null) l.remove();
        if (ball != null) ball.remove();
        if (target != null) target.delete();
        if (lightmap != null) lightmap.delete();
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
