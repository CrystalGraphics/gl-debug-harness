package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The gate for a mesh renderer's {@code material_overrides}: a mesh of two sections, one particle a station, each
 * section drawn with its override and a section past them with the renderer's own material (Cascade's mesh material
 * module over its required material). The left station overrides both sections, red and green; the right only the
 * first, so its second is the renderer's blue. Each mesh faces the camera, so its sections stand side by side.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-mesh-sections"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-mesh-sections --device=vulkan"
 * }</pre>
 *
 * <p>Prints {@code [vfx-mesh-sections] PASS} or {@code FAIL}.</p>
 */
public class CgVfxMeshSectionsScene implements InteractiveSceneLifecycle {

    private static final String MESH = "harness:models/vfx_two_sections";
    private static final float[] RED = {0.9f, 0.1f, 0.1f}, GREEN = {0.1f, 0.9f, 0.1f}, BLUE = {0.1f, 0.2f, 0.9f};
    /** Each station's x and the colours its two halves must show: facing the eye, the mesh's section 0 is on the left. */
    private static final float[] STATIONS = {-2.4f, 2.4f};
    private static final float[][][] DUE = {{RED, GREEN}, {RED, BLUE}};
    private static final float DISTANCE = 8f, SIZE = 1.5f;
    private static final int FRAMES = 12;

    private static final float FOV = (float) Math.toRadians(70);
    private static final Matrix4f VIEW = new Matrix4f(), PROJECTION = new Matrix4f();

    private final CgVfxSystem vfx = new CgVfxSystem();
    private HarnessFboHelper target;
    private int frame;
    private boolean running = true;

    private static CgMaterialDefinition flat(String name, float[] c) {
        return CgMaterialDefinition.of("harness:materials/vfx-mesh-sections-" + name, "harness:shader/vfx_flat_mesh.shader")
                .usage(CgMaterialUsage.MESH_PARTICLES).value("_Color", c[0], c[1], c[2], 1f).build();
    }

    /** Two unit squares in the mesh's x = 0 plane facing +x, section 0 over z 0 to 1, section 1 over z -1 to 0. */
    private static CgMesh twoSections() {
        return CgMesh.build(CgVertexFormat.SPATIAL, m -> {
            for (int s = 0; s < 2; s++) {
                if (s > 0) m.submesh();
                float z0 = s == 0 ? 0f : -1f, z1 = z0 + 1f;
                int a = m.vertex().position(0f, -1f, z0).normal(1f, 0f, 0f).uv(0f, 0f).end();
                int b = m.vertex().position(0f, -1f, z1).normal(1f, 0f, 0f).uv(1f, 0f).end();
                int c = m.vertex().position(0f, 1f, z1).normal(1f, 0f, 0f).uv(1f, 1f).end();
                int d = m.vertex().position(0f, 1f, z0).normal(1f, 0f, 0f).uv(0f, 1f).end();
                m.quad(a, b, c, d);
            }
        });
    }

    private static CgVfxLook look(String name, List<CgMaterialDefinition> overrides) {
        CgVfxRenderers.Builder r = CgVfxRenderers.mesh("sections").material(flat(name + "-own", BLUE))
                .field("meshes", List.of(Map.of("mesh", MESH))).field("facing", "camera_position")
                .materialOverrides(overrides.toArray(new CgMaterialDefinition[0]));
        CgVfxEmitter emitter = CgVfxEmitter.builder(name).state(CgVfxEmitterState.ONCE).capacity(1).burst(0f, 1)
                .speed(0f, 0f).life(100f, 100f).size(SIZE, SIZE).renderer(r.build()).build();
        return CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(emitter).build();
    }

    @Override
    public void init(HarnessContext ctx) {
        CgMeshLoader.provide(MESH, () -> CgMeshLods.of(twoSections()));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo info) {
        frame++;
        int w = ctx.getScreenWidth(), h = ctx.getScreenHeight();
        if (frame == 1) {
            target = HarnessFboHelper.create(w, h, true);   // with depth: the GPU cull of its particles reads it
            vfx.play(new CgVfxSystemInstance<>(look("both", List.of(flat("red", RED), flat("green", GREEN))), STATIONS[0], 0, -DISTANCE));
            vfx.play(new CgVfxSystemInstance<>(look("first", List.of(flat("red1", RED))), STATIONS[1], 0, -DISTANCE));
        }
        target.bind();
        try {
            target.clear(0f, 0f, 0f, 1f);
            vfx.update(frame / 60.0);
            vfx.submit(CgWorldRenderer.get());
            for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
                stage.host().set(0f, w, h, target.getFboId()).view().set(0, 0, 0, VIEW, PROJECTION.setPerspective(FOV, (float) w / h, 0.1f, 100f));
                stage.fire();
            }
            if (frame == FRAMES) target.captureToFile(ctx.getOutputDir(), "vfx-mesh-sections.png");
        } finally {
            target.unbind();
        }
        if (frame == FRAMES) {
            report(ctx, GlErrorChecker.checkAndLog("vfx-mesh-sections"), PROJECTION, w, h);
            target.delete();
            running = false;
        }
    }

    private void report(HarnessContext ctx, boolean glErrors, Matrix4f projection, int w, int h) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        if (glErrors) {
            System.out.println("[vfx-mesh-sections] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (PlatformServiceHarness.validationErrors() > 0) {
            System.out.println("[vfx-mesh-sections] FAIL on " + on + ": Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[vfx-mesh-sections] PASS on recording: every command validated; nothing drawn to compare");
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new File(ctx.getOutputDir(), "vfx-mesh-sections.png"));
        } catch (IOException e) {
            System.out.println("[vfx-mesh-sections] FAIL: the capture could not be read: " + e);
            return;
        }
        for (int s = 0; s < STATIONS.length; s++) {
            for (int half = 0; half < 2; half++) {
                // Half a section's width either side of the particle, in the picture.
                float x = STATIONS[s] + (half == 0 ? -0.5f : 0.5f) * SIZE;
                float ndc = (projection.m00() * x) / DISTANCE;
                int px = Math.round((ndc * 0.5f + 0.5f) * picture.getWidth()), py = picture.getHeight() / 2;
                int rgb = picture.getRGB(px, py);
                float[] want = DUE[s][half];
                float[] got = {(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
                int wantMost = most(want), gotMost = most(got);
                if (got[gotMost] < 0.3f || gotMost != wantMost) {
                    System.out.println("[vfx-mesh-sections] FAIL on " + on + ": station " + s + "'s " + (half == 0 ? "left" : "right")
                            + " half is (" + got[0] + ", " + got[1] + ", " + got[2] + "), not " + name(wantMost));
                    return;
                }
            }
        }
        System.out.println("[vfx-mesh-sections] PASS on " + on + ": each section drawn with its override, and a section past"
                + " them with the renderer's own material");
    }

    private static int most(float[] c) {
        return c[0] >= c[1] && c[0] >= c[2] ? 0 : c[1] >= c[2] ? 1 : 2;
    }

    private static String name(int channel) {
        return channel == 0 ? "red" : channel == 1 ? "green" : "blue";
    }

    @Override
    public void dispose() {
        vfx.delete();
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean uses3DCamera() { return false; }

    @Override public boolean shouldShutdownOnComplete() { return true; }
}
