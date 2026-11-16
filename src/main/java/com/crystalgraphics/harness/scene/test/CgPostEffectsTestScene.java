package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPost;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.component.CgPostVolume;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The post chain's effects, used as a mod would: an effect at each {@link CgPostPoint} and a full vignette from a volume.
 * {@code SCENE} draws a red rectangle into the top left corner, in place; {@code HDR} copies the picture into a texture
 * of its own, draws a yellow rectangle into its bottom left corner and answers that texture; {@code DISPLAY} draws a
 * green rectangle into the top right corner and, at a higher order, a blue one over half of it. From the pixels: the
 * red and the yellow are darkened (the composite's vignette ran after them, and the yellow's texture was carried on),
 * the green and blue are exact (they ran after the composite), blue covers green where they overlap (order), and
 * removing them restores the plain picture.
 *
 * <p>Prints {@code [post-effects] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgPostEffectsTestScene implements HarnessSceneLifecycle {

    private static final int W = 320, H = 240;

    private CgMaterial wall;

    /** A mod's effect: a solid rectangle drawn over the picture, through a raster pass of its own. */
    private static final class Rect implements CgPostEffect {
        private final CgMaterialInstance material = CgMaterial.load("assets/harness/shader/post_rect.shader").instance();

        Rect(float x0, float y0, float x1, float y1, float r, float g, float b) {
            material.vec4("_Rect", x0, y0, x1, y1).vec4("_Color", r, g, b, 1f);
        }

        @Override
        public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
            CgRasterPass pass = ctx.recording().raster("postEffectsTestScene.rect").color(input.texture(), CgLoad.load())
                    .constants(ctx.constants());
            pass.draw(CgMesh.quads(1), material);
            pass.end();
            return input;
        }
    }

    /** A mod's effect answering a texture of its own: the picture copied, a rectangle drawn into the copy. */
    private static final class Elsewhere implements CgPostEffect {
        private final CgMaterialInstance copy = CgMaterial.load("crystalgraphics:shaders/post/hold.shader").instance();
        private final Rect rect = new Rect(0f, 0f, 0.3f, 0.3f, 1f, 1f, 0f);

        @Override
        public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
            CgScreenTexture mine = ctx.screen(copy).into(ctx.texture("postEffectsTestScene.mine", input));
            return rect.apply(ctx, mine);
        }
    }

    @Override
    public void init(HarnessContext ctx) {
        wall = CgMaterial.load("assets/harness/shader/bloom_wall.shader");
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgScene scene = CgScene.client();
        CgPost post = CgPost.client();
        CgPostRenderer.install();
        List<String> failures = new ArrayList<>();

        BufferedImage plain = draw(ctx, scene, "plain");
        List<CgPost.Registration> effects = new ArrayList<>();
        effects.add(post.add(CgPostPoint.DISPLAY, 1, new Rect(0.85f, 0.85f, 1f, 1f, 0f, 0f, 1f)));   // blue, later
        effects.add(post.add(CgPostPoint.SCENE, new Rect(0f, 0.7f, 0.3f, 1f, 1f, 0f, 0f)));          // red
        effects.add(post.add(CgPostPoint.HDR, new Elsewhere()));                                     // yellow, its own
        effects.add(post.add(CgPostPoint.DISPLAY, new Rect(0.7f, 0.7f, 1f, 1f, 0f, 1f, 0f)));        // green
        CgPostVolume vignette = scene.postVolume(new CgPostSettings().vignette(1f)).spawn();
        BufferedImage with = draw(ctx, scene, "effects");
        for (CgPost.Registration r : effects) r.close();
        vignette.remove();
        BufferedImage after = draw(ctx, scene, "closed");

        int red = with.getRGB(1, 1), yellow = with.getRGB(1, H - 2);
        int green = with.getRGB(W * 3 / 4, H / 4 - 4), blue = with.getRGB(W - 2, 1);
        if (((red >> 16) & 0xFF) > 200 || (red & 0xFFFF) != 0) {
            failures.add("SCENE's red corner is 0x" + Integer.toHexString(red) + ": not darkened by the composite's vignette");
        }
        if (((yellow >> 16) & 0xFF) > 200 || ((yellow >> 8) & 0xFF) > 200 || ((yellow >> 16) & 0xFF) < 8 || (yellow & 0xFF) != 0) {
            failures.add("HDR's yellow corner, in a texture of its own, is 0x" + Integer.toHexString(yellow)
                    + ": not carried on into the composite and darkened by its vignette");
        }
        if ((green & 0xFFFFFF) != 0x00FF00) {
            failures.add("DISPLAY's green is 0x" + Integer.toHexString(green) + ", not exact: the composite ran after it");
        }
        if ((blue & 0xFFFFFF) != 0x0000FF) {
            failures.add("order 1's blue is 0x" + Integer.toHexString(blue) + " where it overlaps order 0's green");
        }
        int changed = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (plain.getRGB(x, y) != after.getRGB(x, y)) changed++;
        if (changed > 0) failures.add("removing the effects and the volume left " + changed + " pixels changed");
        report(failures, DeviceErrors.checkAndLog("post-effects"));
    }

    /** The wall, through both world stages, captured. */
    private BufferedImage draw(HarnessContext ctx, CgScene scene, String name) {
        HarnessTarget target = HarnessTarget.create(W, H, true);
        target.bind();
        target.clear(0.25f, 0.3f, 0.4f, 1f);
        CgMesh cube = CgMeshShapes.cube();
        scene.once().mesh(cube, wall).at(0, 0, -5).transform(new Matrix4f().scale(6f, 6f, 0.2f)).add();
        Matrix4f projection = CgCamera.perspectiveMatrix(new Matrix4f(), 60f, (float) W / H, 0.05f, 100f);
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, new Matrix4f(), projection);
        HarnessWorld.fireCallbacks();
        CgFrameRing.endFrame();
        target.captureToFile(ctx.getOutputDir(), name + ".png");
        target.unbind();
        target.delete();
        try {
            return ImageIO.read(new File(ctx.getOutputDir(), name + ".png"));
        } catch (IOException e) {
            throw new IllegalStateException("capture " + name + " could not be read", e);
        }
    }

    private static void report(List<String> failures, boolean glErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[post-effects] FAIL on " + on + ": GL errors, logged above");
        } else if (validation > 0) {
            System.out.println("[post-effects] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
        } else if (!failures.isEmpty()) {
            for (String f : failures) System.out.println("[post-effects] FAIL on " + on + ": " + f);
        } else {
            System.out.println("[post-effects] PASS on " + on + ": an effect at each point runs in order around the "
                    + "composite, one answering a texture of its own is carried on, orders hold within a point, and "
                    + "removing them restores the picture");
        }
    }

    @Override
    public void dispose() {
    }
}
