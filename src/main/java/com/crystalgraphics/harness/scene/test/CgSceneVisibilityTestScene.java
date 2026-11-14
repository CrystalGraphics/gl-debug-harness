package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gpu.resource.CgBufferReadback;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.DeviceErrors;
import com.crystalgraphics.harness.util.HarnessTarget;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.render.CgRenderer;

import com.crystalgraphics.render.graph.CgGraphTexture;


import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.component.CgInstancedMeshComponent;
import com.crystalgraphics.render.scene.visibility.CgInstanceCulling;
import com.crystalgraphics.render.scene.visibility.CgViewVisibility;
import com.crystalgraphics.render.stage.CgRenderStage;

import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;


import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R10's proof: a scene of its own, an instanced grid of spheres in rows at four distances, the left half of the nearer
 * rows behind a wall, and three meshes, one in view. Both world stages fire for {@link #FRAMES} frames, each asking for
 * the view's visibility: it must be made once a frame, not once a firing, and its CPU frustum must test four primitives
 * and cull two. Each frame the opaque firing culls the instances on the GPU ({@code CgInstanceCulling}) against a depth
 * pyramid of the wall; a last frame culls them by the frustum alone. The frustum's count must be the CPU's, the
 * pyramid's within the wall's bounds, and every kept id one of the component's. Prints {@code [scene-visibility] PASS}
 * or {@code FAIL}; on Vulkan a validation error fails it.
 */
public class CgSceneVisibilityTestScene implements HarnessSceneLifecycle {

    private static final int W = 480, H = 270, FRAMES = 8;
    private static final float[] DISTANCES = {6f, 12f, 25f, 50f};
    private static final int COLUMNS = 25, ROWS = 5, N = DISTANCES.length * COLUMNS * ROWS;
    /** The wall: from x -200 to 0 at z -9, hiding the left half of every row behind it. */
    private static final float WALL_Z = -9f;

    private CgMaterialInstance sphereMaterial, wallMaterial;
    private CgMesh sphere, wallMesh;
    private final float[] positions = new float[N * 3];
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = CgCamera.perspectiveMatrix(new Matrix4f(), 60f, (float) W / H, 0.1f, 200f);


    @Override
    public void init(HarnessContext ctx) {
        sphereMaterial = CgMaterial.load("assets/harness/shader/gpu_cull_test.shader").instance();
        wallMaterial = CgMaterial.load("assets/harness/shader/gpu_cull_test.shader").instance();
        sphere = CgMeshShapes.sphere(CgVertexFormat.SPATIAL, 12, 24);

        wallMesh = CgMeshShapes.quad(CgVertexFormat.SPATIAL, 100f, 100f);
        int i = 0;
        for (float d : DISTANCES) {
            float spacing = Math.max(2.5f, 0.2f * d);
            for (int row = 0; row < ROWS; row++) {
                for (int column = 0; column < COLUMNS; column++, i++) {
                    positions[i * 3] = (column - COLUMNS / 2) * spacing;
                    positions[i * 3 + 1] = (row - ROWS / 2) * spacing;
                    positions[i * 3 + 2] = -d;
                }
            }
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        HarnessTarget target = HarnessTarget.create(W, H, true);
        target.bind();
        CgScene client = CgScene.client();

        CgScene scene = new CgScene();
        CgInstancedMeshComponent grid = scene.instanced(sphere, sphereMaterial).spawn();
        Quaternionf none = new Quaternionf();
        for (int i = 0; i < N; i++) grid.add(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2], none, 1f);
        scene.mesh(sphere, sphereMaterial).at(0, 0, -20).spawn();    // in view
        scene.mesh(sphere, sphereMaterial).at(0, 0, 20).spawn();     // behind the eye
        scene.mesh(sphere, sphereMaterial).at(400, 0, -20).spawn();  // far to the side
        // The wall compiled at its draw, not skipped while its program builds off the frame: every counted frame's
        // pyramid holds it
        boolean skipped = CgView.main().flag(CgSceneRenderer.SKIP_UNREADY);
        CgView.main().override(CgSceneRenderer.SKIP_UNREADY, false);

        CgInstanceCulling culling = new CgInstanceCulling("scene-visibility.culling");
        boolean[] pyramidOn = {true};
        int[] asks = {0};
        boolean[] mismatch = {false};
        CgViewVisibility[] seen = {null};
        // After the world renderer has drawn the wall: the pyramid is built from the depth it left, as gpu-cull's is
        CgRenderStage.Registration opaque = CgRenderStage.OPAQUE.register(1, stage -> {
            CgViewVisibility v = CgViewVisibility.of(stage.view(), scene, stage.camera());
            asks[0]++;
            seen[0] = v;
            CgGraphTexture pyramid = null;
            if (pyramidOn[0]) {
                pyramid = CgGraphTexture.transientTexture("scene-visibility.pyramid",
                        new CgTextureDesc(W, H, CgGpuOps.PYRAMID_FORMAT).withMips());
                CgGpuOps.depthPyramid(stage.recording(), stage.depth(), stage.constants(), pyramid);
            }
            culling.cull(stage.recording(), scene, v, stage.camera(), pyramid);
        });
        CgRenderStage.Registration transparent = CgRenderStage.SURFACES.register(0, stage -> {
            CgViewVisibility again = CgViewVisibility.of(stage.view(), scene, stage.camera());
            asks[0]++;
            if (again != seen[0] || again.frame() != CgFrameRing.frame()) mismatch[0] = true;   // the frame's first, as it was
        });

        for (int f = 0; f < FRAMES; f++) {
            fire(target, client);
            CgFrameRing.endFrame();
        }
        int occluded = readCount(culling);
        String strange = strangeIds(culling, occluded, seen[0]);
        pyramidOn[0] = false;
        fire(target, client);
        CgFrameRing.endFrame();
        int frustum = readCount(culling);
        if (strange == null) strange = strangeIds(culling, frustum, seen[0]);
        opaque.close();
        transparent.close();
        CgView.main().override(CgSceneRenderer.SKIP_UNREADY, skipped);

        CgViewVisibility v = seen[0];
        int computations = v == null ? -1 : v.computations(), tested = v == null ? -1 : v.tested(), culled = v == null ? -1 : v.culled();
        CgRecording release = new CgRecording();
        if (culling.ids() != null) release.release(culling.ids());
        if (culling.counts() != null) release.release(culling.counts());
        CgRenderer.executeNow(release);
        scene.clear();
        scene.gpu().release();
        target.unbind();
        target.delete();

        report(computations, asks[0], tested, culled, occluded, frustum, strange, mismatch[0],
                DeviceErrors.checkAndLog("scene-visibility"));
    }

    /** The count the last cull kept of its one row. */
    private static int readCount(CgInstanceCulling culling) {
        if (culling.rows() != 1 || culling.counts() == null) return -1;
        int[] word = new int[1];
        CgBufferReadback.readWords(culling.counts().bufferId(), culling.word(0) * 4L, word, 0, 1);
        return word[0];
    }

    /** The first kept id that is not one of the culled row's instances, or a repeat; null when each is its own once. */
    private static String strangeIds(CgInstanceCulling culling, int kept, CgViewVisibility seenBy) {
        if (seenBy == null) return "a firing's visibility was not the frame's first";
        if (kept <= 0 || culling.ids() == null) return null;
        int[] entries = new int[kept * 2];
        CgBufferReadback.readWords(culling.ids().bufferId(), culling.first(0) * 8L, entries, 0, kept * 2);
        int[] got = new int[kept];
        for (int k = 0; k < kept; k++) got[k] = entries[k * 2];
        CgMeshBatches.Instances instances = seenBy.batches().instances(culling.batch(0));
        Set<Integer> live = new HashSet<>();
        Set<Integer> seen = new HashSet<>();
        for (int k = 0; k < instances.count(); k++) live.add(instances.ids()[k]);
        for (int id : got) {
            if (!live.contains(id)) return "kept id 0x" + Integer.toHexString(id) + " is none of the grid's";
            if (!seen.add(id)) return "kept id 0x" + Integer.toHexString(id) + " twice";
        }
        return null;
    }

    /** A frame: the wall submitted to the world renderer, which draws it ahead of the cull, then both world stages. */
    private void fire(HarnessTarget target, CgScene scene) {
        scene.once().mesh(wallMesh, wallMaterial).at(-100.0, 0.0, WALL_Z).customData(0, 0.5f, 0.5f, 0.5f, 1f).add();
        target.clear(0.08f, 0.08f, 0.1f, 1f);
        CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(0f, W, H, target.external()).camera().set(0, 0, 0, view, projection);
        HarnessWorld.fireCallbacks();
    }

    /** In view, behind the wall at all, and deep behind it: what the frustum keeps, and what any pyramid hides. */
    private int[] expected() {
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(projection).mul(view));
        CgAABB box = sphere.bounds(new CgAABB());
        int[] out = new int[3];
        Vector3f min = new Vector3f(), max = new Vector3f();
        for (int i = 0; i < N; i++) {
            new Matrix4f().translation(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
                    .transformAab((float) box.minX, (float) box.minY, (float) box.minZ, (float) box.maxX, (float) box.maxY,
                            (float) box.maxZ, min, max);
            if (!frustum.testAab(min.x, min.y, min.z, max.x, max.y, max.z)) continue;
            out[0]++;
            if (max.z < WALL_Z && max.x < 0f) out[1]++;
            if (max.z < WALL_Z && max.x < 0.2f * min.z) out[2]++;
        }
        return out;
    }

    private void report(int computations, int asks, int tested, int culled, int occluded, int frustum, String strange,
                        boolean mismatch, boolean glErrors) {
        CgRHI.Globals device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        int[] want = expected();
        if (strange == null && mismatch) strange = "a later firing's visibility was not the frame's first";
        System.out.println("[scene-visibility] made " + computations + " times for " + asks + " asks over " + (FRAMES + 1)
                + " frames; CPU tested " + tested + ", culled " + culled + "; GPU kept " + frustum + " by the frustum of "
                + want[0] + " in view, " + occluded + " with the pyramid; behind the wall " + want[1] + ", deep behind it "
                + want[2]);
        String fail = null;
        if (glErrors) fail = "GL errors, logged above";
        else if (validation > 0) fail = validation + " Vulkan validation errors, logged above";
        else if (computations != FRAMES + 1) fail = "the visibility was made " + computations + " times in " + (FRAMES + 1) + " frames";
        else if (asks != 2 * (FRAMES + 1)) fail = asks + " asks, not two a frame: a firing did not ask";
        else if (tested != 4 || culled != 2) fail = "the CPU frustum tested " + tested + " and culled " + culled + ", not 4 and 2";
        else if (strange != null) fail = strange;
        else if (frustum != want[0]) fail = "the frustum alone kept " + frustum + ", the CPU's frustum " + want[0];
        else if (occluded < want[0] - want[1] || occluded > want[0] - want[2]) {
            fail = "the pyramid kept " + occluded + ", not between " + (want[0] - want[1]) + " and " + (want[0] - want[2]);
        }
        if (fail != null) {
            System.out.println("[scene-visibility] FAIL on " + on + ": " + fail);
            return;
        }
        System.out.println("[scene-visibility] PASS on " + on + ": made once a frame over two firings, the CPU frustum's "
                + "counts, the GPU's instance cull the CPU's frustum and within the wall's bounds, every kept id the grid's");
    }


    @Override
    public void dispose() {
    }
}
