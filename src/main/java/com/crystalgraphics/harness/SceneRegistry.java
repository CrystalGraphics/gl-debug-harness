package com.crystalgraphics.harness;

import com.crystalgraphics.harness.config.SceneDescriptor;
import com.crystalgraphics.harness.scene.*;
import com.crystalgraphics.harness.scene.test.ImageScene;
import com.crystalgraphics.harness.scene.test.CgMeshBackendTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeGraphTestScene;
import com.crystalgraphics.harness.scene.test.CgMrtEmissionTestScene;
import com.crystalgraphics.harness.scene.test.CgRasterLayersTestScene;
import com.crystalgraphics.harness.scene.test.CgPostLooksTestScene;
import com.crystalgraphics.harness.scene.test.CgPostEffectsTestScene;
import com.crystalgraphics.harness.scene.test.CgAsyncComputeTestScene;
import com.crystalgraphics.harness.scene.test.CgUploadStressScene;
import com.crystalgraphics.harness.scene.test.CgGpuGroupsTestScene;
import com.crystalgraphics.harness.scene.test.CgOverdrawTestScene;
import com.crystalgraphics.harness.scene.test.CgDistortionTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeCheckTestScene;
import com.crystalgraphics.harness.scene.test.CgGpuBudgetTestScene;
import com.crystalgraphics.harness.scene.test.CgReadbackTestScene;
import com.crystalgraphics.harness.scene.test.CgVfxCollideScene;
import com.crystalgraphics.harness.scene.test.CgVfxEventsScene;
import com.crystalgraphics.harness.scene.test.CgVfxInputsScene;
import com.crystalgraphics.harness.scene.test.CgVfxRangeScene;
import com.crystalgraphics.harness.scene.test.CgVfxWorldScene;
import com.crystalgraphics.harness.scene.test.CgVfxSimEquivalenceScene;
import com.crystalgraphics.harness.scene.test.CgVolumeTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeTiersTestScene;
import com.crystalgraphics.harness.scene.test.CgGpuOpsCostScene;
import com.crystalgraphics.harness.scene.test.CgGpuOpsTestScene;
import com.crystalgraphics.harness.scene.test.CgGpuCullTestScene;
import com.crystalgraphics.harness.scene.test.CgIndirectDrawTestScene;
import com.crystalgraphics.harness.scene.test.CgMultiDrawTestScene;
import com.crystalgraphics.harness.scene.test.CgBloomOcclusionTestScene;
import com.crystalgraphics.harness.scene.test.CgMaterialBufferTestScene;
import com.crystalgraphics.harness.scene.test.CgRasterLevelsTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeSeamTestScene;
import com.crystalgraphics.harness.scene.test.CgHostSectionScene;
import com.crystalgraphics.harness.scene.test.ReviewScene;
import com.crystalgraphics.harness.scene.test.ShaderLibTestScene;
import com.crystalgraphics.harness.scene.test.CgMaterialDualPathScene;
import com.crystalgraphics.harness.scene.test.CgAttachedBufferStressScene;
import com.crystalgraphics.harness.scene.test.CgVectorRendererTestScene;
import com.crystalgraphics.harness.scene.test.CgTextThreadedTestScene;
import com.crystalgraphics.harness.scene.test.CgGraphExecutorTestScene;
import com.crystalgraphics.harness.scene.test.CgQuadRendererTestScene;
import com.crystalgraphics.harness.scene.test.CgForwardRendererScene;
import com.crystalgraphics.harness.scene.test.CgSceneColorScene;
import com.crystalgraphics.harness.scene.test.CgMeshDrawsScene;
import com.crystalgraphics.harness.scene.test.CgMeshLodsScene;
import com.crystalgraphics.harness.scene.test.CgMeshFrameStressScene;
import com.crystalgraphics.harness.scene.test.CgVfxModulesScene;
import com.crystalgraphics.harness.scene.test.CgWorldLabelsScene;
import com.crystalgraphics.harness.scene.test.CgVfxBlastsScene;
import com.crystalgraphics.harness.scene.test.CgVfxParticlesScene;
import com.crystalgraphics.harness.scene.test.CgVfxShowcaseScene;
import com.crystalgraphics.harness.scene.test.CgVfxTrailsScene;
import com.crystalgraphics.harness.tool.CapabilityReport;
import com.crystalgraphics.harness.tool.GlStateDumper;
import com.crystalgraphics.harness.tool.ShaderCompileAuditScene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit, registration-order-preserving registry of harness scenes and diagnostic modes.
 *
 * <p>No reflection, no annotation scanning. The harness's own entries are registered explicitly
 * in {@link #createDefault()}; a {@link HarnessExtension} adds its own after them.</p>
 */
public final class SceneRegistry {

    /**
     * A registered entry: descriptor + scene factory.
     */
    public static final class Entry {
        private final SceneDescriptor descriptor;
        private final HarnessSceneFactory factory;

        public Entry(SceneDescriptor descriptor, HarnessSceneFactory factory) {
            this.descriptor = descriptor;
            this.factory = factory;
        }

        public SceneDescriptor getDescriptor() { return descriptor; }
        public HarnessSceneFactory getFactory() { return factory; }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    public void register(SceneDescriptor descriptor, HarnessSceneFactory factory) {
        if (entries.containsKey(descriptor.getId())) {
            throw new IllegalStateException("Duplicate scene id: " + descriptor.getId());
        }
        entries.put(descriptor.getId(), new Entry(descriptor, factory));
    }

    public Entry lookup(String modeId) {
        return entries.get(modeId);
    }

    public List<Entry> allEntries() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries.values()));
    }

    public List<String> allIds() {
        return Collections.unmodifiableList(new ArrayList<String>(entries.keySet()));
    }

    public boolean contains(String modeId) {
        return entries.containsKey(modeId);
    }

    public int size() {
        return entries.size();
    }

    /**
     * Creates the default registry with all maintained scenes and diagnostic modes.
     */
    public static SceneRegistry createDefault() {
        SceneRegistry reg = new SceneRegistry();

        reg.register(SceneDescriptor.builder("light")
                .description("Light")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsDepthBuffer(true)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new ReviewScene()
        );
        reg.register(
            SceneDescriptor.builder("image")
                .description("Image")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsDepthBuffer(true)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new ImageScene()
        );
        // ── Rendering scenes ──
        reg.register(
            SceneDescriptor.builder("triangle-2d")
                .description("Render hello triangle via FBO to triangle.png")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(true)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new TriangleScene2D()
        );
        
        reg.register(
            SceneDescriptor.builder("text-2d")
                .description("Full text scene to text-scene.png + atlas/atlas-dump-<size>px.png")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .clearColor(0.15f, 0.15f, 0.2f, 1.0f)
                .build(),
                () -> new TextScene2D()
        );

        reg.register(
            SceneDescriptor.builder("text-3d")
                .description("Interactive 3D world-space text with camera controls, floor plane, and task scheduler")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(true)
                .clearColor(0.1f, 0.1f, 0.15f, 1.0f)
                .build(),
                () -> new TextScene3D()
        );
        
        reg.register(
            SceneDescriptor.builder("shader-lib-test")
                .description("Tests GLSL library #include chain: noise, color, UV operations")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.05f, 0.05f, 0.05f, 1.0f)
                .build(),
            () -> new ShaderLibTestScene()
        );

        reg.register(
            SceneDescriptor.builder("host-section")
                .description("A fake host drawing raw GL inside hostForeign between engine draws, one target, painter's order")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.05f, 0.05f, 0.08f, 1.0f)
                .build(),
            () -> new CgHostSectionScene()
        );

        reg.register(
            SceneDescriptor.builder("material-dual-path")
                .description("CrystalShader MVP: single .shader file drawn with drawDirect() and drawInstanced(8)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.08f, 0.08f, 0.12f, 1.0f)
                .build(),
            () -> new CgMaterialDualPathScene()
        );

        reg.register(
            SceneDescriptor.builder("attached-buffer-stress")
                .description("Stress-tests attached buffer GLSL injection: 5 materials × mixed SSBO/UBO combos, 703 instanced draws")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.05f, 0.05f, 0.08f, 1.0f)
                .build(),
            () -> new CgAttachedBufferStressScene()
        );

        reg.register(
            SceneDescriptor.builder("quad-renderer-test")
                .description("CgQuadRenderer: SSBO/TBO-backed instanced quad batching — grid + rotating spinners in one draw call")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(900)
                .defaultHeight(700)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgQuadRendererTestScene()
        );

        reg.register(
            SceneDescriptor.builder("graph-executor-test")
                .description("render-graph G1: one picture drawn by CgQuadRenderer, by CgImmediate, and by a frame built on a worker thread -- three PNGs that must match")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(900)
                .defaultHeight(700)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgGraphExecutorTestScene()
        );

        reg.register(
            SceneDescriptor.builder("mesh-backend-test")
                .description("mesh rewrite M1: base-vertex draws, integer and half-float attributes, attribute-less draws and buffer copies, beside the same picture drawn plainly -- two PNGs that must match on every device")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(900)
                .defaultHeight(700)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgMeshBackendTestScene()
        );

        reg.register(
            SceneDescriptor.builder("compute-seam")
                .description("gpu-compute C1: kernels write a buffer drawn as quads and an image a material samples, beside an indirect draw; the picture from the kernels must match one from CPU-written data on every device")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(900)
                .defaultHeight(700)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgComputeSeamTestScene()
        );

        reg.register(
            SceneDescriptor.builder("compute-graph")
                .description("gpu-compute C3: compute and raster passes in one frame graph built on a worker, a history stepped twice and an image painted through an indirect dispatch, every barrier derived; the picture, and the frame executed again, must match the CPU's")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(160)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgComputeGraphTestScene()
        );

        reg.register(
            SceneDescriptor.builder("indirect-draw")
                .description("gpu-compute C4: a kernel writes counts four indirect draws read in the same frame, one per CgIndirect mode and one past its mesh; each must match a direct draw of what its count means, in a graph, executed again, and through the world renderer. C9b: a draw of object records a kernel wrote, its count held to the records given")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgIndirectDrawTestScene()
        );

        reg.register(
            SceneDescriptor.builder("gpu-cull")
                .description("gpu-compute C9b: spheres in rows at four distances, half behind a wall, culled by the world renderer on the CPU and by CgGpuOps.cull against a depth pyramid on the GPU; the pictures must match byte for byte and each level's count lie within the wall's bounds; prints each path's draw cost")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(true)
                .defaultWidth(480)
                .defaultHeight(270)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgGpuCullTestScene()
        );

        reg.register(
            SceneDescriptor.builder("multi-draw")
                .description("gpu-compute C9: 76 draws of distinct meshes under one pipeline and bindings, indexed and not, in slabs and on the frame ring, drawn with multi-draw on and off; the pictures must match byte for byte, and joined they must take 4 calls where separate take 77")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(416)
                .defaultHeight(336)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgMultiDrawTestScene()
        );

        reg.register(
            SceneDescriptor.builder("bloom-occlusion")
                .description("The world bloom's gate: an emissive ball behind a wall must bloom nowhere and one in front must bloom, at two target sizes and bloom scales 1 and 0.5")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(true)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgBloomOcclusionTestScene()
        );

        reg.register(
            SceneDescriptor.builder("material-buffer")
                .description("A material reads a kernel's buffers: records written and compacted on the GPU, a quad per live cell placed and coloured from them through one indirect draw, in a graph and through the world renderer; every cell must match Java's")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(96)
                .defaultHeight(96)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgMaterialBufferTestScene()
        );

        reg.register(
            SceneDescriptor.builder("raster-levels")
                .description("Raster passes into mip levels: a chain drawn level by level in one texture, each level reading the one above through a level view; every texel of every level must match Java's")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(64)
                .defaultHeight(64)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgRasterLevelsTestScene()
        );

        reg.register(
            SceneDescriptor.builder("raster-layers")
                .description("Raster passes into array layers: 2 to 5 layers of one array drawn each frame and summed through a sampler2DArray; every layer and the sum must match Java's, on one storage throughout")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(64)
                .defaultHeight(64)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgRasterLayersTestScene()
        );

        reg.register(
            SceneDescriptor.builder("mrt-emission")
                .description("MRT emission's proof: colour and emission drawn in one pass into two attachments under one blend, against each drawn alone; smoke must dim the glow, a wall hide it")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(64)
                .defaultHeight(64)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgMrtEmissionTestScene()
        );

        reg.register(
            SceneDescriptor.builder("post-looks")
                .description("The post stack's volumes and looks: flash, vignette, impact frames, aberration, each through a volume; weight 0, out of reach and flashes 0 change nothing, priority overrides")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgPostLooksTestScene()
        );

        reg.register(
            SceneDescriptor.builder("post-effects")
                .description("The post stack's mod SPI: an effect at each point, checked to run in order round the composite, by order within a point, and to leave nothing once closed")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgPostEffectsTestScene()
        );

        reg.register(
            SceneDescriptor.builder("compute-tiers")
                .description("gpu-compute C5/C6: one kernel per shape, every buffer and image checked against Java's arithmetic; run at each forced tier (-Dcrystalgraphics.compute.tier=G43|G40|G33|CPU), all must agree")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(64)
                .defaultHeight(64)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgComputeTiersTestScene()
        );

        reg.register(
            SceneDescriptor.builder("gpu-ops")
                .description("gpu-compute C7: every CgGpuOps op at counts 0 to 70000, fixed and read from the GPU, checked bit for bit against Java; run at each forced tier, all must agree")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(64)
                .defaultHeight(64)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgGpuOpsTestScene()
        );

        reg.register(
            SceneDescriptor.builder("gpu-ops-cost")
                .description("gpu-compute C7: what each CgGpuOps op costs on the GPU and the CPU, at a million elements and a 1920x1080 chain; prints a table and exits")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(180)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgGpuOpsCostScene()
        );

        reg.register(
            SceneDescriptor.builder("compute-check")
                .description("gpu-compute C10: checked mode (-Dcrystalgraphics.compute.checked=true) names a write past a buffer, an add past it and a texel past an image, each at its line, and not a kernel inside its buffer")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(64)
                .defaultHeight(64)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgComputeCheckTestScene()
        );

        reg.register(
            SceneDescriptor.builder("readback")
                .description("gpu-compute C8: buffer words, float and unaligned byte targets and a mip level read back through the frame graph each frame, every delivery checked against the frame that recorded it")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgReadbackTestScene()
        );

        reg.register(
            SceneDescriptor.builder("volumes")
                .description("gpu-compute E3: 3D textures in the frame graph, filled and spread by 3d image kernels, sampled as sampler3D by a kernel and a material, updated and read back by boxes, every texel checked against Java")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVolumeTestScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-sim-equivalence")
                .description("vfx-gpu §13.9: the GPU particle simulation against the CPU path; first its GLSL libraries, fx_rand bit for bit and fx_curl within rounding of the Java")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxSimEquivalenceScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-range")
                .description("vfx-gpu §13.4: Range culls a pool's particles against a view and writes the records a look reads, checked against the CPU's cull and writeRecords")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxRangeScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-world")
                .description("vfx-gpu X4: a world of its own in the voxel window; GPU debris rests on its stairs and overhang, and Range lights a blast at a cave's mouth from it")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxWorldScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-events")
                .description("vfx-gpu X5: debris's landings, an age and its deaths as events; a child pool spawning from them in the same step, every child checked against Java from the rows the CPU heard")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxEventsScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-collide")
                .description("vfx-gpu X6: debris bounced off a ground and a wall by a GLSL kind given as text over the window's distance field; its collisions and a rate as repeating events, every row and child checked against Java")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxCollideScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-inputs")
                .description("vfx-gpu X6: module kinds sampling a 3D vector field, a 2D heightfield and the scene's depth, each pool checked against Java")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxInputsScene()
        );

        reg.register(
            SceneDescriptor.builder("gpu-budget")
                .description("gpu-compute C11: a pass of fills charged to a CgGpuBudget and sized by its scale, held under a budget a third of its full cost")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgGpuBudgetTestScene()
        );

        reg.register(
            SceneDescriptor.builder("async-compute")
                .description("gpu-compute C8: blurs in a compute pass beside fill-bound drawing, timed alone, in order and async(); the outputs of both orders compared byte for byte")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(180)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgAsyncComputeTestScene()
        );

        reg.register(
            SceneDescriptor.builder("upload-stress")
                .description("render-async-uploads U0: bursts of textures and a volume uploaded from workers and from the render thread, for what an upload costs each thread and the GPU")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(640)
                .defaultHeight(360)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgUploadStressScene()
        );

        reg.register(
            SceneDescriptor.builder("gpu-groups")
                .description("render-distortion T1: a timed pass of three materials costing 1 : 2 : 4, split by crystalgraphics.gpu.groups, the groups summing to the pass; then the channel off")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(180)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgGpuGroupsTestScene()
        );

        reg.register(
            SceneDescriptor.builder("overdraw-count")
                .description("render-distortion T3: transparent planes in front of and behind a wall, one discarding half, counted by the overdraw view and read back exactly")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgOverdrawTestScene()
        );

        reg.register(
            SceneDescriptor.builder("distortion")
                .description("render-distortion D1-D2: quads bending a coordinate backdrop, checked pixel by pixel: adding, hidden by a wall, mirrored, the leak guard, the split, and the after-distortion queue")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .defaultWidth(256)
                .defaultHeight(192)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgDistortionTestScene()
        );

        reg.register(
            SceneDescriptor.builder("text-threaded")
                .description("render-graph G2.2a: text recorded on a worker while the render thread draws text in the same faces; a converged text drawn both ways must match")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(1200)
                .defaultHeight(800)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgTextThreadedTestScene()
        );

        reg.register(
            SceneDescriptor.builder("curve-renderer-test")
                .description("CgVectorRenderer: instanced quadratic Bezier strokes — lines, taper, gradient, caps, split cubics, posed fan")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(980)
                .defaultHeight(760)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVectorRendererTestScene()
        );

        reg.register(
            SceneDescriptor.builder("forward-renderer")
                .description("Phase 1 render pipeline: depth prepass + opaque auto-instancing + transparent depth order")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.05f, 0.05f, 0.08f, 1.0f)
                .build(),
            () -> new CgForwardRendererScene()
        );

        reg.register(
            SceneDescriptor.builder("mesh-draws-test")
                .description("mesh rewrite M4: quads and strips with no vertex data, index ranges, submeshes, stated cull bounds")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.05f, 0.05f, 0.08f, 1.0f)
                .build(),
            () -> new CgMeshDrawsScene()
        );

        reg.register(
            SceneDescriptor.builder("mesh-lods-test")
                .description("mesh rewrite M6: 720 spheres, the finest level then levels per screen height; logs vertices a frame")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.05f, 0.05f, 0.08f, 1.0f)
                .build(),
            () -> new CgMeshLodsScene()
        );

        reg.register(
            SceneDescriptor.builder("mesh-frame-stress")
                .description("64 FRAME meshes rewritten every frame: the frame ring against slabs (-Dcrystalgraphics.mesh.frameRing=false)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.05f, 0.05f, 0.08f, 1.0f)
                .build(),
            () -> new CgMeshFrameStressScene()
        );

        reg.register(
            SceneDescriptor.builder("scene-color-test")
                .description("cg_SceneColor: a lens over a row of cubes, showing the scene behind it rippled and inverted")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.05f, 0.05f, 0.08f, 1.0f)
                .build(),
            () -> new CgSceneColorScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-spheres")
                .description("VFX showcase: sixteen effect spheres (PBR metals, glass, plasma, a black hole...) on a neon floor")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxShowcaseScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-spheres-stress")
                .description("vfx-spheres with 30 beams firing at once: the baseline for a frame full of effects")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            CgVfxShowcaseScene::stress
        );

        reg.register(
            SceneDescriptor.builder("vfx-blasts")
                .description("Blasts as particles alone (CgVfxBlasts), no billows or rings: about 360k particles (-Dcrystalgraphics.harness.vfx.blasts=<n>)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxBlastsScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-particles")
                .description("Particles alone: the explosion kit bursting on the showcase floor; P switches 60 Hz and 120 Hz steps")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxParticlesScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-modules")
                .description("X6's particle modules, a station each: forces, colliders, speed, flipbooks and facing; V switches CPU and GPU")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxModulesScene()
        );

        reg.register(
            SceneDescriptor.builder("world-labels")
                .description("CgWorldRenderer.text under load: a grid of outlined, shadowed labels (-Dcrystalgraphics.harness.labels=<n>)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgWorldLabelsScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-trails")
                .description("Trails rewritten every frame: the per-frame mesh path (CgMesh.Usage.FRAME)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxTrailsScene()
        );

        reg.register(
            SceneDescriptor.builder("text-stress")
                .description("CrystalGraphics text engine benchmark: 1000 shaped labels, no CrystalGUI — pure CgTextRenderer + FreeType/HarfBuzz")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgTextStressScene()
        );

        reg.register(
            SceneDescriptor.builder("text-feature-stress")
                .description("Text paths no other benchmark exercises: RTL/BiDi, font fallback chains, synthetic bold/italic, decorations")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgTextFeatureStressScene()
        );

        // ── Diagnostic modes ──
        reg.register(
            SceneDescriptor.builder("atlas-dump")
                .description("Generate glyph atlas dump to atlas/ subdir (atlas-dump-<size>px.png)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .build(),
                () -> new AtlasDumpScene()
        );
        
        reg.register(
            SceneDescriptor.builder("camera-3d")
                .description("3D camera validation: renders cube + floor, captures 4 angle screenshots")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.1f, 0.1f, 0.15f, 1.0f)
                .build(),
                () -> new CameraScene3D()
        );

        reg.register(
            SceneDescriptor.builder("mesh-test")
                .description("3D mesh test: CgMeshShapes, OBJ, GLB, and a two-part glTF drawn a material per part")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.08f, 0.08f, 0.12f, 1.0f)
                .build(),
            new HarnessSceneFactory() {
                public HarnessSceneLifecycle create() { return new MeshTestScene(); }
            }
        );

        reg.register(
            SceneDescriptor.builder("gl-state-dump")
                .description("Dump current GL state to a structured report file")
                .lifecycleMode(SceneDescriptor.LifecycleMode.DIAGNOSTIC)
                .category(SceneDescriptor.Category.DIAGNOSTIC_TOOL)
                .needsFbo(false)
                .build(),
                () -> new GlStateDumper()
        );

        reg.register(
            SceneDescriptor.builder("shader-compile-audit")
                .description("Compile every shipped .shader + keyword variant on this driver; one report, no crash on first failure")
                .lifecycleMode(SceneDescriptor.LifecycleMode.DIAGNOSTIC)
                .category(SceneDescriptor.Category.DIAGNOSTIC_TOOL)
                .needsFbo(false)
                .build(),
                () -> new ShaderCompileAuditScene()
        );

        reg.register(
            SceneDescriptor.builder("capability-report")
                .description("Write a capability summary report")
                .lifecycleMode(SceneDescriptor.LifecycleMode.DIAGNOSTIC)
                .category(SceneDescriptor.Category.DIAGNOSTIC_TOOL)
                .needsFbo(false)
                .build(),
                () -> new CapabilityReport()
        );

        for (HarnessExtension extension : HarnessExtensions.all()) {
            extension.registerScenes(reg);
        }

        return reg;
    }
}
