package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.compute.ops.CgCull;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.buffer.CgBufferReadback;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.harness.util.HarnessFboHelper;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * gpu-compute C9b's gate: a set of spheres in rows at four distances, half of the nearer rows behind a wall, culled
 * twice. {@code gpu-cull-cpu.png} is {@code CgWorldRenderer} drawing every sphere, each culled and given its level on
 * the CPU; {@code gpu-cull.png} is the world renderer drawing the wall alone and a renderer after it building a depth
 * pyramid from the target, culling the set with {@code CgGpuOps.cull} and drawing each level's kept spheres by one
 * indirect draw, the levels joined into one multi-draw where the executor joins them; {@code gpu-cull-world.png} is
 * the same set as one world draw of {@code instances()}, the wall drawn ahead of the world renderer as a host's world
 * is. All three must be the same picture, byte for byte, and each level's count must lie between what the frustum and
 * the levels keep less every sphere behind the wall, and that less the ones deep behind it.
 *
 * <p>Every path draws twice: with multi-draw off ({@code -separate.png}), and on. A joined draw takes the
 * {@code CG_MULTI_DRAW} variant, whose rounding differs from the plain one's by a unit at a pixel on some devices, so
 * each picture is compared with the CPU's drawn the same way, and the wall has a material of its own to keep it out
 * of the spheres' run. Where the levels must join, the joined frame must take fewer calls.</p>
 *
 * <p>Each path is fired {@link #TIMED} times and its median CPU time printed: the draw cost the cull saves.
 * {@code -Dcrystalgraphics.harness.gpuCull.rows=<n>} stacks more rows, most of them off screen, for the cost at a
 * larger set; {@code .timed=<n>} (30) and {@code .warmup=<n>} (0 untimed fires first) make it a warm figure, which a
 * comparison with Vulkan needs ({@code .warmup=600}). Prints {@code [gpu-cull] PASS} or {@code FAIL}; on Vulkan a
 * validation error fails it.</p>
 */
public class CgGpuCullTestScene implements HarnessSceneLifecycle {

    private static final int W = 480, H = 270;
    /** Fires timed per path, after untimed ones: the tracked backend's Java wants hundreds before C2 has it. */
    private static final int TIMED = Integer.getInteger("crystalgraphics.harness.gpuCull.timed", 30),
            WARMUP = Integer.getInteger("crystalgraphics.harness.gpuCull.warmup", 0);
    /** The rows' distances, their colours, and each level's screen height: every row well inside one level's band. */
    private static final float[] DISTANCES = {6f, 12f, 25f, 50f};
    private static final int[] COLOURS = {0xE04040, 0x40C040, 0x4060E0, 0xE0C040};
    private static final float[] HEIGHTS = {0.35f, 0.18f, 0.09f};
    private static final int COLUMNS = 25, ROWS = Integer.getInteger("crystalgraphics.harness.gpuCull.rows", 5);
    private static final int N = DISTANCES.length * COLUMNS * ROWS;
    /** The wall: from x -200 to 0 at z -9, hiding the left half of every row behind it. */
    private static final float WALL_Z = -9f;
    /** The stamped comparison's scale and colour. */
    private static final float STAMP_SCALE = 0.6f;
    private static final float[] STAMP = {0.9f, 0.3f, 0.8f};

    private CgMaterial material, wallMaterial;
    private CgMeshLods lods;
    private CgMesh wallMesh;
    private final Matrix4f wallPlace = new Matrix4f().translation(-100f, 0f, WALL_Z), identity = new Matrix4f();
    private final float[] positions = new float[N * 3];
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(60), (float) W / H, 0.1f, 200f);

    @Override
    public void init(HarnessContext ctx) {
        material = CgMaterial.load("assets/harness/shader/gpu_cull_test.shader");
        wallMaterial = CgMaterial.newInstance("assets/harness/shader/gpu_cull_test.shader");
        lods = CgMeshLods.builder()
                .level(CgMeshShapes.sphere(CgVertexFormat.SPATIAL, 24, 48), HEIGHTS[0])
                .level(CgMeshShapes.sphere(CgVertexFormat.SPATIAL, 12, 24), HEIGHTS[1])
                .level(CgMeshShapes.sphere(CgVertexFormat.SPATIAL, 6, 12), HEIGHTS[2])
                .build();
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
        HarnessFboHelper target = HarnessFboHelper.create(W, H, true);
        target.bind();
        CgWorldRenderer world = CgWorldRenderer.get();
        world.install();

        CgMeshStore store = CgMeshStore.get();
        boolean joins = store.multiDraw();
        boolean levelsJoin = levelsJoin();

        world.release();
        wall(world);
        for (int i = 0; i < N; i++) {
            float[] rgb = colour(i);
            world.draw(lods, material).at(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
                    .custom(0, rgb[0], rgb[1], rgb[2], 1f).submit();
        }
        store.multiDraw(false);
        fire(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull-cpu-separate.png");
        store.multiDraw(joins);
        long cpu = timed(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull-cpu.png");

        CgGraphBuffer instances = CgGraphBuffer.persistent("gpu-cull.instances",
                CgBufferDesc.elements(N, CgGpuOps.cullRecordBytes(), CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        CgGraphBuffer counts = CgGraphBuffer.persistent("gpu-cull.counts",
                CgBufferDesc.of(16, CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT));
        CgCull cull = new CgCull().mesh(lods);
        boolean[] uploaded = {false};
        world.release();
        wall(world);
        CgRenderStage.Registration culling = CgRenderStage.WORLD_OPAQUE.register(CgWorldRenderer.ORDER + 1,
                stage -> recordCull(stage, cull, instances, counts, uploaded));
        store.multiDraw(false);
        long separateCalls = calls(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull-separate.png");
        store.multiDraw(joins);
        long joinedCalls = calls(target);
        long gpu = timed(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull.png");
        culling.close();

        world.release();
        CgRenderStage.Registration walling = CgRenderStage.WORLD_OPAQUE.register(CgWorldRenderer.ORDER - 1, this::recordWall);
        world.draw(lods, material).instances(instances, CgGpuCount.of(N)).submit();
        store.multiDraw(false);
        long setSeparateCalls = calls(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull-world-separate.png");
        store.multiDraw(joins);
        long setJoinedCalls = calls(target);
        long set = timed(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull-world.png");
        walling.close();
        world.release();

        // The same set as two world draws of ranges of one buffer, split where no level's region is aligned.
        CgRenderStage.Registration rangeWall = CgRenderStage.WORLD_OPAQUE.register(CgWorldRenderer.ORDER - 1, this::recordWall);
        int split = N / 2 + 7;
        world.draw(lods, material).instances(instances, 0, CgGpuCount.of(split)).submit();
        world.draw(lods, material).instances(instances, split, CgGpuCount.of(N - split)).submit();
        store.multiDraw(false);
        fire(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull-world-ranges.png");
        store.multiDraw(joins);
        rangeWall.close();
        world.release();

        // Stamped: every sphere at STAMP_SCALE its size in one colour, by the CPU's transform and custom, and as the
        // set's instance scale and a custom its cull stamps over each record's own.
        world.release();
        wall(world);
        Matrix4f smaller = new Matrix4f().scaling(STAMP_SCALE);
        for (int i = 0; i < N; i++) {
            world.draw(lods, material).at(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]).transform(smaller)
                    .custom(0, STAMP[0], STAMP[1], STAMP[2], 1f).submit();
        }
        store.multiDraw(false);
        fire(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull-cpu-stamped.png");
        world.release();
        CgRenderStage.Registration stampWall = CgRenderStage.WORLD_OPAQUE.register(CgWorldRenderer.ORDER - 1, this::recordWall);
        world.draw(lods, material).instances(instances, CgGpuCount.of(N)).instanceScale(STAMP_SCALE)
                .custom(0, STAMP[0], STAMP[1], STAMP[2], 1f).submit();
        fire(target);
        target.captureToFile(ctx.getOutputDir(), "gpu-cull-world-stamped.png");
        store.multiDraw(joins);
        stampWall.close();
        world.release();

        int[] kept = new int[4];
        CgBufferReadback.readWords(counts.bufferId(), 0, kept, 0, HEIGHTS.length);
        CgRecording release = new CgRecording();
        release.release(instances);
        release.release(counts);
        CgImmediate.execute(release);
        target.unbind();
        target.delete();

        System.out.printf("[gpu-cull] draw cost of %d spheres, median of %d frames: %.3f ms culled on the CPU, %.3f ms "
                + "on the GPU in a pass of its own, %.3f ms as one world draw of instances%n", N, TIMED, cpu / 1e6,
                gpu / 1e6, set / 1e6);
        System.out.println("[gpu-cull] draw calls separate and joined: the GPU's pass " + separateCalls + " and "
                + joinedCalls + ", the world draw " + setSeparateCalls + " and " + setJoinedCalls + "; its levels "
                + (levelsJoin ? "join" : "do not join") + " here");
        report(ctx, kept, levelsJoin, separateCalls - joinedCalls, setSeparateCalls - setJoinedCalls,
                GlErrorChecker.checkAndLog("gpu-cull"));
    }

    /** Whether the executor joins the culled levels: where draws join, and it takes their counts from the GPU. */
    private static boolean levelsJoin() {
        CgCapabilities caps = CgCapabilities.detect();
        ComputeTier tier = caps.computeTier();
        boolean gpuCounts = tier == ComputeTier.V || tier == ComputeTier.G43 || tier == ComputeTier.G40 && caps.drawIndirect();
        return gpuCounts && caps.multiDraw() && CgMeshStore.get().multiDraw();
    }

    /** The pyramid from what the world renderer drew, the set culled against it, and each level drawn by its count. */
    private void recordCull(CgStageFrame stage, CgCull cull, CgGraphBuffer instances, CgGraphBuffer counts,
                            boolean[] uploaded) {
        CgRecording rec = stage.recording();
        if (!uploaded[0]) {
            rec.update(instances, 0, records());
            uploaded[0] = true;
        }
        CgGraphTexture pyramid = CgGraphTexture.transientTexture("gpu-cull.pyramid",
                new CgTextureDesc(W, H, CgGpuOps.PYRAMID_FORMAT).withMips());
        CgGraphBuffer visible = CgGraphBuffer.transientBuffer("gpu-cull.visible",
                CgBufferDesc.elements(CgGpuOps.cullRecords(cull, N), CgGpuOps.cullRecordBytes(), CgBufferUsage.STORAGE));
        CgGpuOps.depthPyramid(rec, stage.target(), stage.constants(), pyramid);
        CgComputePass pass = rec.compute("gpu-cull");
        CgGpuOps.cull(pass, cull.view(view, projection).place(new Matrix4f()).pyramid(pyramid), instances,
                CgGpuCount.of(N), visible, counts, 0);
        pass.end();

        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        int bindings = material.captureBindings(rec.bindings());
        CgRasterPass draw = stage.pass(stage.constants(), CgOrder.LOOKBACK);
        CgChunkBuilder c = rec.chunks().begin();
        for (int l = 0; l < cull.levels(); l++) {
            c.draw(pipeline, bindings, lods.level(l)).objects(visible, CgGpuOps.cullFirst(l, N), N)
                    .indirect(counts, l * 4L, CgIndirect.INSTANCES, 1);
        }
        draw.add(c.end());
        draw.end();
    }

    /** The wall in a pass ahead of the world renderer, as a host's world is drawn: what its depth pyramid holds. */
    private void recordWall(CgStageFrame stage) {
        CgRecording rec = stage.recording();
        CgRasterPass pass = stage.pass(stage.constants(), CgOrder.LOOKBACK);
        CgChunkBuilder c = rec.chunks().begin();
        c.draw(wallMaterial.pipeline(CgInstanceKind.OBJECT), wallMaterial.captureBindings(rec.bindings()), wallMesh);
        int at = c.instance();
        float[] data = c.data();
        Arrays.fill(data, at, at + 48, 0f);
        wallPlace.get(data, at);
        identity.get(data, at + 16);
        data[at + 32] = data[at + 33] = data[at + 34] = 0.5f;
        data[at + 35] = 1f;
        pass.add(c.end());
        pass.end();
    }

    /** Each sphere's object record: its translation, the identity normal, its row's colour. */
    private ByteBuffer records() {
        ByteBuffer data = ByteBuffer.allocateDirect(N * CgGpuOps.cullRecordBytes()).order(ByteOrder.nativeOrder());
        float[] r = new float[48];
        for (int i = 0; i < N; i++) {
            Arrays.fill(r, 0f);
            for (int axis = 0; axis < 4; axis++) {
                r[axis * 5] = 1f;
                r[16 + axis * 5] = 1f;
            }
            r[12] = positions[i * 3];
            r[13] = positions[i * 3 + 1];
            r[14] = positions[i * 3 + 2];
            float[] rgb = colour(i);
            r[32] = rgb[0];
            r[33] = rgb[1];
            r[34] = rgb[2];
            r[35] = 1f;
            for (float f : r) data.putFloat(f);
        }
        data.flip();
        return data;
    }

    private void wall(CgWorldRenderer world) {
        world.draw(wallMesh, wallMaterial).at(-100.0, 0.0, WALL_Z)
                .custom(0, 0.5f, 0.5f, 0.5f, 1f).submit();
    }

    private static float[] colour(int i) {
        int rgb = COLOURS[i / (COLUMNS * ROWS)];
        return new float[]{(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
    }

    /** Fires both world stages {@link #TIMED} times onto a cleared target, answering the median CPU nanoseconds. */
    private long timed(HarnessFboHelper target) {
        for (int t = 0; t < WARMUP; t++) fire(target);
        long[] times = new long[TIMED];
        for (int t = 0; t < TIMED; t++) {
            long start = System.nanoTime();
            fire(target);
            times[t] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        return times[TIMED / 2];
    }

    /** Fires both world stages once onto a cleared target, answering the draw calls the mesh store made. */
    private long calls(HarnessFboHelper target) {
        long before = CgMeshStore.get().drawCalls();
        fire(target);
        return CgMeshStore.get().drawCalls() - before;
    }

    private void fire(HarnessFboHelper target) {
        target.clear(0.08f, 0.08f, 0.1f, 1f);
        for (CgRenderStage stage : List.of(CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT)) {
            stage.host().set(0f, W, H, target.getFboId()).view().set(0, 0, 0, view, projection);
            stage.fire();
        }
    }

    /**
     * Per level: what the frustum and the levels keep, as the world renderer decides it; the spheres among them
     * behind the wall at all; and those deep behind it, which any pyramid hides.
     */
    private int[][] expected() {
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(projection).mul(view));
        float[] box = lods.finest().bounds(new float[6]);
        int[][] out = new int[3][HEIGHTS.length];
        Vector3f min = new Vector3f(), max = new Vector3f();
        for (int i = 0; i < N; i++) {
            Matrix4f model = new Matrix4f().translation(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            model.transformAab(box[0], box[1], box[2], box[3], box[4], box[5], min, max);
            if (!frustum.testAab(min.x, min.y, min.z, max.x, max.y, max.z)) continue;
            float cx = (min.x + max.x) * 0.5f, cy = (min.y + max.y) * 0.5f, cz = (min.z + max.z) * 0.5f;
            float r = 0.5f * max.distance(min);
            Matrix4f vp = new Matrix4f(projection).mul(view);
            float w = vp.m03() * cx + vp.m13() * cy + vp.m23() * cz + vp.m33();
            float screen = w <= r ? Float.MAX_VALUE : r * Math.abs(projection.m11()) / w;
            CgMesh level = lods.pick(screen);
            if (level == null) continue;
            int l = 0;
            while (lods.level(l) != level) l++;
            out[0][l]++;
            if (max.z < WALL_Z && max.x < 0f) out[1][l]++;
            if (max.z < WALL_Z && max.x < 0.2f * min.z) out[2][l]++;
        }
        return out;
    }

    private void report(HarnessContext ctx, int[] kept, boolean levelsJoin, long callsSaved, long setCallsSaved,
                        boolean glErrors) {
        CgDeviceInfo device = PlatformServiceHarness.deviceInfo();
        String on = device == null ? "gl" : device.name();
        int validation = PlatformServiceHarness.validationErrors();
        int[][] expected = expected();
        System.out.println("[gpu-cull] kept per level " + Arrays.toString(Arrays.copyOf(kept, HEIGHTS.length))
                + " of " + Arrays.toString(expected[0]) + " in view; behind the wall " + Arrays.toString(expected[1])
                + ", deep behind it " + Arrays.toString(expected[2]));
        if (glErrors) {
            System.out.println("[gpu-cull] FAIL on " + on + ": GL errors, logged above");
            return;
        }
        if (validation > 0) {
            System.out.println("[gpu-cull] FAIL on " + on + ": " + validation + " Vulkan validation errors, logged above");
            return;
        }
        if ("recording".equals(on)) {
            System.out.println("[gpu-cull] PASS on recording: every command validated; a recording device draws nothing to compare");
            return;
        }
        for (int l = 0; l < HEIGHTS.length; l++) {
            int most = expected[0][l] - expected[2][l], least = expected[0][l] - expected[1][l];
            if (kept[l] < least || kept[l] > most) {
                System.out.println("[gpu-cull] FAIL on " + on + ": level " + l + " kept " + kept[l] + ", not between "
                        + least + " and " + most);
                return;
            }
        }
        long saved = levelsJoin ? HEIGHTS.length - 1 : 0;
        if (callsSaved != saved) {
            System.out.println("[gpu-cull] FAIL on " + on + ": joining saved " + callsSaved + " draw calls, not " + saved);
            return;
        }
        // The world draws the set's levels in each pass that draws it: its prepass too, where the material has one.
        if (levelsJoin ? setCallsSaved <= 0 || setCallsSaved % saved != 0 : setCallsSaved != 0) {
            System.out.println("[gpu-cull] FAIL on " + on + ": joining saved the world draw " + setCallsSaved + " draw calls");
            return;
        }
        // Where the levels do not join, the CPU's joined spheres are no reference for them.
        String reference = levelsJoin ? "gpu-cull-cpu.png" : "gpu-cull-cpu-separate.png";
        for (String[] pair : new String[][]{{"gpu-cull-cpu-separate.png", "gpu-cull-separate.png"},
                {reference, "gpu-cull.png"}, {"gpu-cull-cpu-separate.png", "gpu-cull-world-separate.png"},
                {reference, "gpu-cull-world.png"}, {"gpu-cull-cpu-separate.png", "gpu-cull-world-ranges.png"},
                {"gpu-cull-cpu-stamped.png", "gpu-cull-world-stamped.png"}}) {
            String differs = differs(ctx, pair[0], pair[1]);
            if (differs != null) {
                System.out.println("[gpu-cull] FAIL on " + on + ": " + differs);
                return;
            }
        }
        System.out.println("[gpu-cull] PASS on " + on + ": the GPU's cull draws the CPU's picture, in a pass of its own "
                + "and as a world draw, separate and " + (levelsJoin ? "its levels in one multi-draw" : "with multi-draw on")
                + ", as two world draws of ranges of one buffer, and with a scale and a custom stamped"
                + ", each level's count within the wall's bounds");
    }

    /** Where the GPU's picture {@code gpu} first differs from the CPU's {@code cpu}; null when they are the same. */
    private static String differs(HarnessContext ctx, String cpu, String gpu) {
        try {
            BufferedImage a = ImageIO.read(new File(ctx.getOutputDir(), cpu));
            BufferedImage b = ImageIO.read(new File(ctx.getOutputDir(), gpu));
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    if (a.getRGB(x, y) != b.getRGB(x, y)) {
                        return gpu + " differs from " + cpu + " first at (" + x + ", " + y + "): 0x"
                                + Integer.toHexString(b.getRGB(x, y)) + " where the CPU's has 0x"
                                + Integer.toHexString(a.getRGB(x, y));
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return "a capture could not be read: " + e;
        }
    }

    @Override
    public void dispose() {
    }
}
