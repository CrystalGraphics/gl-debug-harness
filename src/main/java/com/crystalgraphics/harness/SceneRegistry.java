package com.crystalgraphics.harness;

import com.crystalgraphics.harness.config.SceneDescriptor;
import com.crystalgraphics.harness.scene.*;
import com.crystalgraphics.harness.scene.test.ImageScene;
import com.crystalgraphics.harness.scene.test.CgMeshBackendTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeGraphTestScene;
import com.crystalgraphics.harness.scene.test.CgHdrSceneTestScene;
import com.crystalgraphics.harness.scene.test.CgMrtEmissionTestScene;
import com.crystalgraphics.harness.scene.test.CgRasterLayersTestScene;
import com.crystalgraphics.harness.scene.test.CgPostLooksTestScene;
import com.crystalgraphics.harness.scene.test.CgPostEffectsTestScene;
import com.crystalgraphics.harness.scene.test.CgAsyncComputeTestScene;
import com.crystalgraphics.harness.scene.test.CgUploadStressScene;
import com.crystalgraphics.harness.scene.test.CgGpuGroupsTestScene;
import com.crystalgraphics.harness.scene.test.CgOverdrawTestScene;
import com.crystalgraphics.harness.scene.test.CgCustomDepthTestScene;
import com.crystalgraphics.harness.scene.test.CgDistortionTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeCheckTestScene;
import com.crystalgraphics.harness.scene.test.CgGpuBudgetTestScene;
import com.crystalgraphics.harness.scene.test.CgReadbackTestScene;
import com.crystalgraphics.harness.scene.test.CgVfxCascadeCollideScene;
import com.crystalgraphics.harness.scene.test.CgVfxRibbonsScene;
import com.crystalgraphics.harness.scene.test.CgVfxTrailHeadsScene;
import com.crystalgraphics.harness.scene.test.CgVfxSpawnFromScene;
import com.crystalgraphics.harness.scene.test.CgVfxMeshFacingScene;
import com.crystalgraphics.harness.scene.test.CgVfxMeshSectionsScene;
import com.crystalgraphics.harness.scene.test.CgVfxCollideScene;
import com.crystalgraphics.harness.scene.test.CgVfxEventsScene;
import com.crystalgraphics.harness.scene.test.CgVfxSequenceScene;
import com.crystalgraphics.harness.scene.test.CgVfxInputsScene;
import com.crystalgraphics.harness.scene.test.CgVfxRangeScene;
import com.crystalgraphics.harness.scene.test.CgVfxWorldScene;
import com.crystalgraphics.harness.scene.test.CgMaterialLibraryScene;
import com.crystalgraphics.harness.scene.test.CgLightsBenchScene;
import com.crystalgraphics.harness.scene.test.CgLightsGridScene;
import com.crystalgraphics.harness.scene.test.CgVfxBeamsScene;
import com.crystalgraphics.harness.scene.test.CgVfxFormsScene;
import com.crystalgraphics.harness.scene.test.CgVfxHashesScene;
import com.crystalgraphics.harness.scene.test.CgVolumeTestScene;
import com.crystalgraphics.harness.scene.test.CgComputeTiersTestScene;
import com.crystalgraphics.harness.scene.test.CgGpuOpsCostScene;
import com.crystalgraphics.harness.scene.test.CgGpuOpsTestScene;
import com.crystalgraphics.harness.scene.test.CgGpuCullTestScene;
import com.crystalgraphics.harness.scene.test.CgIndirectDrawTestScene;
import com.crystalgraphics.harness.scene.test.CgSkinnedBenchScene;
import com.crystalgraphics.harness.scene.test.CgSkinnedMeshTestScene;
import com.crystalgraphics.harness.scene.test.CgSkinnedPlayScene;
import com.crystalgraphics.harness.scene.test.CgMultiDrawTestScene;
import com.crystalgraphics.harness.scene.test.CgCookedTextureTestScene;
import com.crystalgraphics.harness.scene.test.CgSamplerBindingTestScene;
import com.crystalgraphics.harness.scene.test.CgFlipbookTestScene;
import com.crystalgraphics.harness.scene.test.CgGlobalParametersTestScene;
import com.crystalgraphics.harness.scene.test.CgPropertyTableScene;
import com.crystalgraphics.harness.scene.test.CgTextureCubeScene;
import com.crystalgraphics.harness.scene.test.CgPostTransitionsScene;
import com.crystalgraphics.harness.scene.test.CgVfxPrefabScene;
import com.crystalgraphics.harness.scene.test.CgVfxBooksScene;
import com.crystalgraphics.harness.scene.test.CgVfxPrepareScene;
import com.crystalgraphics.harness.scene.test.CgVfxKamehamehaScene;
import com.crystalgraphics.harness.scene.test.CgVfxKitPlayScene;
import com.crystalgraphics.harness.scene.test.CgSequenceSpawnScene;
import com.crystalgraphics.harness.scene.test.CgVfxNovaScene;
import com.crystalgraphics.harness.scene.test.CgVfxBenchArenaScene;
import com.crystalgraphics.harness.scene.test.CgKitShadingScene;
import com.crystalgraphics.harness.scene.test.CgVfxLightsScene;
import com.crystalgraphics.harness.scene.test.CgVfxRenderersScene;
import com.crystalgraphics.harness.scene.test.CgVfxShapesScene;
import com.crystalgraphics.harness.scene.test.CgVfxSortGroupScene;
import com.crystalgraphics.harness.scene.test.CgSecondLayerTestScene;
import com.crystalgraphics.harness.scene.test.CgBloomOcclusionTestScene;
import com.crystalgraphics.harness.scene.test.CgLightsCastersScene;
import com.crystalgraphics.harness.scene.test.CgLightsShadowsBenchScene;
import com.crystalgraphics.harness.scene.test.CgLightsShadowScene;
import com.crystalgraphics.harness.scene.test.CgLightsSunScene;
import com.crystalgraphics.harness.scene.test.CgLightsTorchScene;
import com.crystalgraphics.harness.scene.test.CgLightsVolumeScene;
import com.crystalgraphics.harness.scene.test.CgLightsAirScene;
import com.crystalgraphics.harness.scene.test.CgLightsAirQualityScene;
import com.crystalgraphics.harness.scene.test.CgLightsDemoBisectScene;
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
import com.crystalgraphics.harness.scene.test.CgVfxFluidScene;
import com.crystalgraphics.harness.scene.test.CgVfxModulesScene;
import com.crystalgraphics.harness.scene.test.CgWorldLabelsScene;
import com.crystalgraphics.harness.scene.test.CgVfxBlastsScene;
import com.crystalgraphics.harness.scene.test.CgVfxParticlesScene;
import com.crystalgraphics.harness.scene.test.CgVfxBlastFlashScene;
import com.crystalgraphics.harness.scene.test.CgVfxShowcaseScene;
import com.crystalgraphics.harness.scene.test.CgVfxTrailsScene;
import com.crystalgraphics.harness.scene.test.CgCollisionShapesScene;
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
            SceneDescriptor.builder("skinned-mesh")
                .description("B2: a bar of three bones bent at both joints, skinned on the GPU from a palette beside its CPU-skinned twin (CgSkinning); positions and normals must match")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(640)
                .defaultHeight(320)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgSkinnedMeshTestScene()
        );

        reg.register(SceneDescriptor.builder("skinned-bench")
                .description("B2: what skinning costs a pass: 200 dense bars bending from palettes each frame, or (skinned.static=true) their CPU-skinned twins as static meshes")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsDepthBuffer(true)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgSkinnedBenchScene()
        );

        reg.register(SceneDescriptor.builder("skinned-play")
                .description("Goku and Kurama playing their clips, posed by CgAnimation and skinned on the GPU: Tab actor, N/B clip, P pause, , . frame, I/O rate, R restart")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsDepthBuffer(true)
                .build(),
            () -> new CgSkinnedPlayScene()
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
            SceneDescriptor.builder("sampler-binding")
                .description("G0.2: one 4x4 texture, one upload, drawn on four quads in one pass through a binding's sampler each (nearest wrapped, clamped, mirrored, and the texture's own); every quad must read the colour its address mode gives at u -0.25")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(424)
                .defaultHeight(112)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgSamplerBindingTestScene()
        );

        reg.register(
            SceneDescriptor.builder("cooked-texture")
                .description("G0.3: one image from its PNG and from its BC7 .cgtex, colour and alpha, at 3x and at a quarter (a lower mip); every cooked quad must match its PNG twin. Blocks where BPTC is, decoded to RGBA8 on mac41")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(808)
                .defaultHeight(184)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgCookedTextureTestScene()
        );

        reg.register(
            SceneDescriptor.builder("second-layer")
                .description("G0.4: a glTF quad's second UV set and vertex colour loaded into SPATIAL, drawn by one shader at its first UV and, under SECOND_LAYER, at its second; each quad must read the texel and tint due")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(216)
                .defaultHeight(112)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgSecondLayerTestScene()
        );

        reg.register(
            SceneDescriptor.builder("flipbook")
                .description("G0.5: a flipbook's frames through fx_particle.glsl, as sprite.shader draws them: a rect table looped at 30 a second, the grid over a life, a range looped, random frames and a blend; every particle must show the frame due")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(264)
                .defaultHeight(168)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgFlipbookTestScene()
        );

        reg.register(
            SceneDescriptor.builder("global-parameters")
                .description("G0.6: a parameter collection's value set once, read by two materials through the world renderer and ignored by a third; each quad must read the colour due")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(176)
                .defaultHeight(64)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgGlobalParametersTestScene()
        );

        reg.register(
            SceneDescriptor.builder("texture-cube")
                .description("A stacked-faces cube sampled along each axis, and a .hdr read as half floats: each face i the device's face i")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(116)
                .defaultHeight(20)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgTextureCubeScene()
        );

        reg.register(
            SceneDescriptor.builder("property-table")
                .description("A material's vec4[64] read on the GPU: 64 quads, each its own element's colour, across the table's four chunks")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(132)
                .defaultHeight(132)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgPropertyTableScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-sort-group")
                .description("G0.7: two effects in one sort group keep their layers' authored order whichever is nearer; an ungrouped pair sorts by distance")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .defaultWidth(240)
                .defaultHeight(80)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgVfxSortGroupScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-mesh-sections")
                .description("A mesh renderer's material_overrides: each section with its override, one past them with the renderer's own")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(120)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgVfxMeshSectionsScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-prefab")
                .description("G0.9: a 458-node prefab drawn a ball a node, a subtree cut by opacity 0 and a chain dimmed by 0.5; evaluating it takes a few microseconds")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgVfxPrefabScene()
        );

        reg.register(
            SceneDescriptor.builder("post-transitions")
                .description("G0.10: a post material by its weight, a fade in both composite forms, a cut's held frame crossfaded out; each firing's pixel checked")
                .lifecycleMode(SceneDescriptor.LifecycleMode.MANAGED)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(true)
                .needsDepthBuffer(false)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgPostTransitionsScene()
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
            SceneDescriptor.builder("hdr-scene")
                .description("render-hdr-scene H0: an 8-bit host decoded into a linear scene and encoded back, timed on the GPU at 1080p and 4K, RGBA16F and R11G11B10F; the RGBA16F round trip must be byte-identical; prints a table and exits")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(180)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgHdrSceneTestScene()
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
                .description("gpu-compute C5/C6: one kernel per shape, every buffer and image checked against Java's arithmetic; run at each forced tier (-Dcrystalgraphics.compute.tier=G43|G40|G33) and under the CPU oracle (-Dcrystalgraphics.harness.oracle=true), all must agree")
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
            SceneDescriptor.builder("material-library")
                .description("The material library: DepthFade, Panner, Rotator, ParticleSubUV, RotateAboutAxis against Unreal's formulas")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgMaterialLibraryScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-hashes")
                .description("The spawn hash: fx_rand on the GPU bit for bit against the Java an emitter's books use")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxHashesScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-forms")
                .description("Module inputs in forms: each form's lanes on the GPU against its Java evaluation")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxFormsScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-beams")
                .description("Beams: each beam's ends, noise and drawn points on the GPU against a Java simulation of Cascade's")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxBeamsScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-bench")
                .description("Effect lights' cost: n point lights over the floor (crystalgraphics.harness.lights, 1,000)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .build(),
            () -> new CgLightsBenchScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-grid")
                .description("The world's light list and grid read back against Java: CPU and appended lights, Unreal's binning")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .build(),
            () -> new CgLightsGridScene()
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
            SceneDescriptor.builder("lights-shadow")
                .description("The world lights' shadows: a light beside a wall of blocks, its light behind the wall withheld and in the open kept")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgLightsShadowScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-volume")
                .description("The world lights on transparent surfaces: two glass panes by a light, the one in the open lit by the translucency lighting volume, the one behind a wall not")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgLightsVolumeScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-air")
                .description("The world lights in the air: a light in a haze beside a tall wall, the air by it glowing and the air in the wall's shadow not")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgLightsAirScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-air-quality")
                .description("How the lit air looks, as numbers: the lights demo's searchlight in its haze, its base's frame-to-frame jitter and its beam side-on from 10 and 40 blocks")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(640)
                .defaultHeight(360)
                .clearColor(0.01f, 0.01f, 0.02f, 1.0f)
                .build(),
            () -> new CgLightsAirQualityScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-demo-bisect")
                .description("The lights demo at midnight, captured with the lit air, the shadows and the transparent volume left out in turn")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.01f, 0.01f, 0.03f, 1.0f)
                .build(),
            () -> new CgLightsDemoBisectScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-demo")
                .description("The lights demo at midnight, every pass on, held at one moment until closed: to profile")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.01f, 0.01f, 0.03f, 1.0f)
                .build(),
            () -> new CgLightsDemoBisectScene(true)
        );

        reg.register(
            SceneDescriptor.builder("lights-sun")
                .description("The sun's shadows: our box shadows the host's ground, blocks the host never draws shadow our box, at noon against no sun")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgLightsSunScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-casters")
                .description("Our meshes casting through their distance fields: a box over the host's ground and a ball over our slab under a low sun, against no sun")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgLightsCastersScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-shadows-bench")
                .description("The world shadows' cost: the shadows demo on the host's ground under the sun, the camera over a shadow")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.55f, 0.7f, 1.0f, 1.0f)
                .build(),
            () -> new CgLightsShadowsBenchScene()
        );

        reg.register(
            SceneDescriptor.builder("lights-torch")
                .description("Minecraft's block light: a torch on the host's ground, our box beside it shadowing the ground behind it, against no box")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgLightsTorchScene()
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
            SceneDescriptor.builder("vfx-sequence")
                .description("G2.1: a particle playing a clip, its part drawn where its node is and gone once faded; an event at the node, the clip's burst and stream spawning children and theirs grandchildren, every row checked against Java")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0f, 0f, 0f, 1.0f)
                .build(),
            () -> new CgVfxSequenceScene()
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
            SceneDescriptor.builder("vfx-cascade-collide")
                .description("A collider's Cascade response: per-axis damping rolled per particle, a count with freeze, halt and kill, and a delay, each group checked against Java")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxCascadeCollideScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-spawn-from")
                .description("Particles born on another emitter's: random on still sources, sequential inheriting moving sources' velocity, an empty source's at the origin, checked against the seeded sources")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxSpawnFromScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-ribbons")
                .description("The ribbon generation: Niagara's ribbon, a shaped one broken by its link gap and Cascade's trail in one pool, the packed run and draw counts held to a Java twin of every stage")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxRibbonsScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-trail-heads")
                .description("Trails behind another emitter's particles, their heads worked out on the GPU: three trails on three moving particles held to a Java twin of Cascade's trail spawning")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxTrailHeadsScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-mesh-facing")
                .description("Mesh particles facing: Niagara's modes and locked axis, Cascade's ups, rolls and pre-rotations, each mesh's turn against Java")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .defaultWidth(160)
                .defaultHeight(120)
                .clearColor(0.08f, 0.08f, 0.1f, 1.0f)
                .build(),
            () -> new CgVfxMeshFacingScene()
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
            SceneDescriptor.builder("custom-depth")
                .description("aaa-milestones OL2: CustomDepth and CustomStencil, a hidden cube still written, a partial mask over a full one, a skinned quad, each stencil byte read back exactly")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .defaultWidth(320)
                .defaultHeight(240)
                .clearColor(0.05f, 0.05f, 0.07f, 1.0f)
                .build(),
            () -> new CgCustomDepthTestScene()
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
            SceneDescriptor.builder("vfx-blast-flash")
                .description("The energy wave's blast every second, for judging its flash: F double/single/off, I impact frame, T slow motion, , . period")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxBlastFlashScene()
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
            SceneDescriptor.builder("vfx-fluid")
                .description("Gas and smoke: Flow's and Mantaflow's scenes, a CS2 grenade, particles pushing fluids, grids, a mushroom cloud; N/M tour")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxFluidScene()
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
                .description("Many ki streaks: Cascade ribbon trails behind sources their effects move, each strip generated on the GPU")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxTrailsScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-prepare")
                .description("G0.8: a move's looks prepared together, then played: their first 1200 frames must compile nothing")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxPrepareScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-nova")
                .description("A3: the Nova Cannon played from its files once its bundle is READY: no compile, sync load or texture load after")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxNovaScene()
        );

        reg.register(
            SceneDescriptor.builder("sequence-spawn")
                .description("A sequence played from its files, its systems, prefabs and post volumes spawned by the engine (the Nova Cannon by default): each system on its ticks, nothing loaded while playing, the sequencer's worst frame")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgSequenceSpawnScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-kit-play")
                .description("A kit's systems played from its files by id once their bundle is READY (Kakarot's Kamehameha by default): no compile, sync load or texture load after, each holding particles")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxKitPlayScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-kamehameha")
                .description("Kakarot's Kamehameha from its kit: the battle path's eight systems at stations, N/Shift+N to fly between them, M the row, R replay; the HUD names each")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxKamehamehaScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-books")
                .description("The books against the GPU: under a 200 ms frame no slot closes while its particles live")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxBooksScene()
        );

        reg.register(
            SceneDescriptor.builder("bench-arena")
                .description("The arena's frame-thread load: 8 generated moves (180 emitters, 106 shapes, 163 layers each) playing, 30 palettes prepared; EFFECTS and SUBMIT per frame")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxBenchArenaScene()
        );

        reg.register(
            SceneDescriptor.builder("bench-duel")
                .description("Two ultimates at once: 67 shapes alive in each, starting together; every frame under the target")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> CgVfxBenchArenaScene.duel()
        );

        reg.register(
            SceneDescriptor.builder("bench-brawl")
                .description("The arena plus 300 one-off puffs a second: no allocation, no compile")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> CgVfxBenchArenaScene.brawl()
        );

        reg.register(
            SceneDescriptor.builder("vfx-shapes")
                .description("G0.11: 30 emitters each its own shape, so 30 pools of few particles: the particle frame's fixed cost per pool, on gl and vulkan")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxShapesScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-renderers")
                .description("An emitter's renderers drawing from material definitions: four kinds over 100 effects, each one merged draw")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxRenderersScene()
        );

        reg.register(
            SceneDescriptor.builder("vfx-lights")
                .description("Particles as lights: a light module's embers over the floor, the firing's light list against a Java gather")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgVfxLightsScene()
        );

        reg.register(
            SceneDescriptor.builder("kit-shading")
                .description("A kit's shading models drawing: its shading.yaml registered, one of its materials compiled from the kit and drawn")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgKitShadingScene()
        );

        reg.register(
            SceneDescriptor.builder("collision-shapes")
                .description("CgCollisionDemo on the floor: every collision shape as debug lines, a cast, rays and a ring hitbox")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(true)
                .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                .build(),
            () -> new CgCollisionShapesScene()
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
