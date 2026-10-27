package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.mesh.CgSkinning;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgBonePalettes;
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
 * B2's gate: a bar of three bones, bent at both joints, drawn twice through the world renderer: on the left skinned on
 * the GPU from a palette ({@code bones(base)}), on the right posed on the CPU by {@link CgSkinning} and drawn as a
 * static mesh. Coloured by its normal, so the halves differ wherever a skinned position or normal does.
 *
 * <ul>
 *   <li>{@code skinned-mesh.png}: the two halves.</li>
 *   <li>PASS when every covered pixel of either half is matched within a pixel by the other, to 2 of 255 a channel
 *       (a skinned float is summed in another order on each side), and the bent tip is drawn where the CPU puts it.</li>
 * </ul>
 *
 * <p>Prints {@code [skinned-mesh] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgSkinnedMeshTestScene implements HarnessSceneLifecycle {

    /** The bar: {@code SEGMENTS} quads along x from -LENGTH/2, {@code HEIGHT} tall, in pixels. */
    private static final int SEGMENTS = 30;
    private static final float LENGTH = 240f, HEIGHT = 24f;
    /** Each bone's joint along x, and its bend in degrees: a chain, each about its own joint. */
    private static final float[] JOINTS = {-120f, -40f, 40f};
    private static final float[] BENDS = {15f, 35f, -50f};
    private static final int BACKGROUND = 0xFF141419;

    private CgMaterial material;
    private CgMesh bar;
    private final Matrix4f[] skinning = new Matrix4f[JOINTS.length];

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/skinned_mesh_test.shader");
        bar = CgMesh.build(CgVertexFormat.SKINNED, m -> {
            for (int s = 0; s <= SEGMENTS; s++) {
                float x = -LENGTH / 2f + LENGTH * s / SEGMENTS;
                for (int row = 0; row < 2; row++) {
                    float y = row == 0 ? -HEIGHT / 2f : HEIGHT / 2f;
                    float nx = x / LENGTH, ny = row == 0 ? -0.4f : 0.4f, nz = 1f;
                    float n = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    m.vertex().position(x, y, 0f).normal(nx / n, ny / n, nz / n).uv(s / (float) SEGMENTS, row);
                    weigh(m, x);
                    m.end();
                }
            }
            for (int s = 0; s < SEGMENTS; s++) m.quad(s * 2, s * 2 + 2, s * 2 + 3, s * 2 + 1);
        });
        Matrix4f parent = new Matrix4f();
        for (int b = 0; b < JOINTS.length; b++) {
            skinning[b] = new Matrix4f(parent).translate(JOINTS[b], 0f, 0f)
                    .rotateZ((float) Math.toRadians(BENDS[b])).translate(-JOINTS[b], 0f, 0f);
            parent = skinning[b];
        }
    }

    /** Bone weights along the bar: one bone between joints, blended linearly over 30 pixels round each joint. */
    private static void weigh(CgMeshWriter m, float x) {
        int bone = 0;
        while (bone + 1 < JOINTS.length && x >= JOINTS[bone + 1]) bone++;
        float toNext = bone + 1 < JOINTS.length ? JOINTS[bone + 1] - x : Float.MAX_VALUE;
        float fromThis = bone > 0 ? x - JOINTS[bone] : Float.MAX_VALUE;
        if (toNext < 15f) {
            float t = 0.5f - toNext / 30f;
            m.bone(bone, 1f - t).bone(bone + 1, t);
        } else if (fromThis < 15f) {
            float t = 0.5f + fromThis / 30f;
            m.bone(bone - 1, 1f - t).bone(bone, t);
        } else {
            m.bone(bone, 1f);
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        Matrix4f projection = new Matrix4f().setOrtho(0, w, h, 0, -1, 1);
        CgMesh posed = CgSkinning.skin(bar, skinning);

        HarnessFboHelper target = HarnessFboHelper.create(w, h, false);
        target.bind();
        target.clear((BACKGROUND >> 16 & 0xFF) / 255f, (BACKGROUND >> 8 & 0xFF) / 255f, (BACKGROUND & 0xFF) / 255f, 1f);
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();
        int base = CgBonePalettes.write(skinning, skinning.length);
        world.draw(bar, material).bones(base).at(w / 4.0, h / 2.0, 0).submit();
        world.draw(posed, material).at(w * 3 / 4.0, h / 2.0, 0).submit();
        Matrix4f view = new Matrix4f();
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, view, projection);
            stage.fire();
        }
        target.captureToFile(ctx.getOutputDir(), "skinned-mesh.png");
        target.unbind();
        target.delete();
        posed.release();

        compare(ctx, GlErrorChecker.checkAndLog("skinned-mesh"));
    }

    private void compare(HarnessContext ctx, boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        if (glErrors) {
            System.out.println("[skinned-mesh] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (validation > 0) {
            System.out.println("[skinned-mesh] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[skinned-mesh] PASS on recording: every command validated; a recording device draws nothing to compare");
            return;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new File(ctx.getOutputDir(), "skinned-mesh.png"));
        } catch (IOException e) {
            System.out.println("[skinned-mesh] FAIL: skinned-mesh.png could not be read: " + e);
            return;
        }
        int half = image.getWidth() / 2, covered = 0, unmatched = 0, firstX = -1, firstY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < half; x++) {
                for (int side = 0; side < 2; side++) {
                    int at = x + side * half, other = x + (1 - side) * half;
                    int rgb = image.getRGB(at, y);
                    if (rgb == BACKGROUND) continue;
                    if (side == 0) covered++;
                    if (!matchedNear(image, other, y, rgb, (1 - side) * half)) {
                        if (unmatched++ == 0) {
                            firstX = at;
                            firstY = y;
                        }
                    }
                }
            }
        }
        // The tip's last vertex, posed on the CPU, must be drawn on both halves; unbent it would be elsewhere.
        float[] tip = {LENGTH / 2f - 2f, 0f};
        Matrix4f last = skinning[skinning.length - 1];
        float tx = last.m00() * tip[0] + last.m10() * tip[1] + last.m30();
        float ty = last.m01() * tip[0] + last.m11() * tip[1] + last.m31();
        int lx = Math.round(image.getWidth() / 4f + tx), ly = Math.round(image.getHeight() / 2f + ty);
        boolean tipDrawn = lx >= 0 && lx < half && ly >= 0 && ly < image.getHeight()
                && image.getRGB(lx, ly) != BACKGROUND && image.getRGB(lx + half, ly) != BACKGROUND;
        if (covered < 1000) {
            System.out.println("[skinned-mesh] FAIL on " + on + ": the skinned half drew " + covered + " pixels");
        } else if (unmatched > 0) {
            System.out.println("[skinned-mesh] FAIL on " + on + ": " + unmatched + " pixels unmatched by the other half, first at ("
                    + firstX + ", " + firstY + ") 0x" + Integer.toHexString(image.getRGB(firstX, firstY)));
        } else if (!tipDrawn) {
            System.out.println("[skinned-mesh] FAIL on " + on + ": the bent tip is not drawn at (" + lx + ", " + ly + ")");
        } else {
            System.out.println("[skinned-mesh] PASS on " + on + ": " + covered + " pixels skinned on the GPU match the CPU's twin, "
                    + "positions and normals, the tip bent to (" + lx + ", " + ly + ")");
        }
    }

    /** Whether a pixel within one of {@code (x, y)} in the half from {@code from} has {@code rgb} to 2 a channel. */
    private static boolean matchedNear(BufferedImage image, int x, int y, int rgb, int from) {
        int half = image.getWidth() / 2;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int px = x + dx, py = y + dy;
                if (px < from || px >= from + half || py < 0 || py >= image.getHeight()) continue;
                int other = image.getRGB(px, py);
                if (Math.abs((other >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) <= 2
                        && Math.abs((other >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) <= 2
                        && Math.abs((other & 0xFF) - (rgb & 0xFF)) <= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void dispose() {
        if (bar != null) bar.release();
    }
}
