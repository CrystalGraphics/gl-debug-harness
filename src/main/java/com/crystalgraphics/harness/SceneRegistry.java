package com.crystalgraphics.harness;

import com.crystalgraphics.harness.config.SceneDescriptor;
import com.crystalgraphics.harness.scene.*;
import com.crystalgraphics.harness.scene.test.ImageScene;
import com.crystalgraphics.harness.scene.test.CgMeshBackendTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeGraphTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeTiersTestScene;
import com.crystalgraphics.harness.scene.test.CgGpuOpsCostScene;
import com.crystalgraphics.harness.scene.test.CgGpuOpsTestScene;
import com.crystalgraphics.harness.scene.test.CgIndirectDrawTestScene;
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
                .description("gpu-compute C4: a kernel writes counts four indirect draws read in the same frame, one per CgIndirect mode and one past its mesh; each must match a direct draw of what its count means, in a graph, executed again, and through the world renderer")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(160)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgIndirectDrawTestScene()
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
