package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgMipmapConfig;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A texture asset's two new images on the GPU (aaa-design 2.64): a cube of six faces stacked in one image, each face
 * a grey of its own, sampled along each axis, so face i must be the device's face i; and a {@code .hdr} read as half
 * floats. Both with their mips generated, as a material's textures are.
 *
 * <p>Prints {@code [texture-cube] PASS} or {@code FAIL}; run it on {@code gl} at each forced tier and on
 * {@code vulkan}.</p>
 */
public class CgTextureCubeScene implements HarnessSceneLifecycle {

    private static final int QUADS = 7, SIZE = 12, MARGIN = 4, TOLERANCE = 1;
    /** What each quad reads: face i's grey, then the ramp's 0.5, 0.25, 0.125. */
    private static final int[][] DUE = {{40, 40, 40}, {80, 80, 80}, {120, 120, 120}, {160, 160, 160}, {200, 200, 200},
            {240, 240, 240}, {128, 64, 32}};

    private CgMaterial material;

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.newInstance("assets/harness/shader/texture_cube.shader");
        CgTextureSpec cube = CgTextureSpec.RGBA8_LINEAR.toBuilder().mipmaps(CgMipmapConfig.TRILINEAR).build();
        CgTextureSpec ramp = CgTextureSpec.RGBA16F_LINEAR.toBuilder().mipmaps(CgMipmapConfig.TRILINEAR).build();
        material.applyProperties(b -> {
            b.sampler("_Cube", 0, CgTextures.stackedCube("harness:textures/cube_stacked.png", cube));
            b.sampler("_Ramp", 1, CgTextures.of("harness:textures/half_ramp.hdr", ramp));
        });
    }

    private static int x(int i) {
        return MARGIN + i * (SIZE + MARGIN);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            CgWorldRenderer world = CgWorldRenderer.get();
            world.install();
            for (int i = 0; i < QUADS; i++) world.draw(CgMesh.quads(1), material).custom(0, x(i), MARGIN, SIZE, i).submit();
            Matrix4f view = new Matrix4f(), projection = new Matrix4f().setOrtho(0, w, h, 0, -1, 1);
            for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
                stage.fire();
            }
            target.captureToFile(ctx.getOutputDir(), "texture-cube.png");
        } finally {
            target.unbind();
            target.delete();
        }
        report(ctx, GlErrorChecker.checkAndLog("texture-cube"));
    }

    private void report(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) {
            System.out.println("[texture-cube] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        int validation = PlatformServiceHarness.validationErrors();
        if (validation > 0) {
            System.out.println("[texture-cube] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[texture-cube] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "texture-cube.png"));
        } catch (IOException e) {
            System.out.println("[texture-cube] FAIL: the capture could not be read: " + e);
            return;
        }
        for (int i = 0; i < QUADS; i++) {
            int rgb = picture.getRGB(x(i) + SIZE / 2, MARGIN + SIZE / 2);
            int[] got = {rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF};
            for (int c = 0; c < 3; c++) {
                if (Math.abs(got[c] - DUE[i][c]) > TOLERANCE) {
                    System.out.println("[texture-cube] FAIL on " + on + ": " + (i < 6 ? "cube face " + i : "the .hdr")
                            + " reads (" + got[0] + ", " + got[1] + ", " + got[2] + "), where (" + DUE[i][0] + ", "
                            + DUE[i][1] + ", " + DUE[i][2] + ") is due");
                    return;
                }
            }
        }
        System.out.println("[texture-cube] PASS on " + on + ": each stacked face is its cube face, and the .hdr reads as half floats");
    }

    @Override
    public void dispose() {
        if (material != null) material.delete();
    }
}
