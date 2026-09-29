package com.crystalgraphics.harness;

import com.crystalgraphics.harness.config.SceneDescriptor;
import com.crystalgraphics.harness.scene.*;
import com.crystalgraphics.harness.scene.test.ImageScene;
import com.crystalgraphics.harness.scene.test.CgHostSectionScene;
import com.crystalgraphics.harness.scene.test.InstancingTestScene;
import com.crystalgraphics.harness.scene.test.ReviewScene;
import com.crystalgraphics.harness.scene.test.ShaderLibTestScene;
import com.crystalgraphics.harness.scene.test.CgMaterialDualPathScene;
import com.crystalgraphics.harness.scene.test.CgAttachedBufferStressScene;
import com.crystalgraphics.harness.scene.test.CgVectorRendererTestScene;
import com.crystalgraphics.harness.scene.test.CgQuadRendererTestScene;
import com.crystalgraphics.harness.scene.test.CgForwardRendererScene;
import com.crystalgraphics.harness.scene.ui.CgGpuTraceProbeScene;
import com.crystalgraphics.harness.scene.ui.CgUiTextGammaScene;
import com.crystalgraphics.harness.scene.ui.CgUiTimelineScene;
import com.crystalgraphics.harness.scene.ui.CgUiGalleryScene;
import com.crystalgraphics.harness.scene.ui.CgUiDesktopScene;
import com.crystalgraphics.harness.scene.ui.CgUiNewEngineGalleryScene;
import com.crystalgraphics.harness.scene.ui.CgUiSpriteStressScene;
import com.crystalgraphics.harness.scene.ui.CgUiStylingScene;
import com.crystalgraphics.harness.scene.ui.CgUiSvgIconScene;
import com.crystalgraphics.harness.scene.ui.CgUiTextScene;
import com.crystalgraphics.harness.scene.ui.CgUiTextStressScene;
import com.crystalgraphics.harness.scene.ui.CgUiVisualLayersScene;
import com.crystalgraphics.harness.scene.ui.RpgConsoleScene;
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
 * <p>No reflection, no annotation scanning. All entries are registered explicitly
 * in {@link #createDefault()}.</p>
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
            SceneDescriptor.builder("instancing-test")
                .description("Instancing backend diagnostics: base VAO isolation, instanced draw, GL error checks")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.05f, 0.05f, 0.08f, 1.0f)
                .build(),
            () -> new InstancingTestScene()
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
            SceneDescriptor.builder("cgui-styling")
                .description("CrystalGUI stylesheet test: selectors, combinators, pseudo-classes, transitions")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiStylingScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-visual-layers")
                .description("CrystalGUI Visual Layers: opacity isolation + overflow:hidden mask/scissor, minimal side-by-side on/off comparisons")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiVisualLayersScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-text")
                .description("CrystalGUI UIText: auto-sizing, wrapping, font-family fallback, live bindTextTo (SPACE to cycle)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiTextScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-text-stress")
                .description("CrystalGUI UIText load benchmark: 100 labels, three update patterns (static / same-length / varying-length), prints a summary table")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiTextStressScene()
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

        reg.register(
            SceneDescriptor.builder("cgui-text-gamma")
                .description("Text gamma and contrast: off, Chromium, strong, heavy, and the default fading strong into heavy by size, on the UI faces, dark and light panels, UI scale 1 and 2 (G cycles, S toggles scale)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiTextGammaScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-new-gallery")
                .description("M6 NEW ENGINE: every ported widget in one scrolling column, over UIDocument + the box tree -- the counterpart to cgui-gallery, and the only thing that can see whether a ported widget actually DRAWS")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiNewEngineGalleryScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-desktop")
                .description("M6 NEW ENGINE: CrystalOS -- stacking windows, drag, resize, cascade, the taskbar, per-window modality, maximise, and CrystalEditor running as a window. The counterpart to cgui-new-gallery: that one answers whether a ported WIDGET draws, this one whether a ported WINDOW behaves")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.06f, 0.06f, 0.08f, 1.0f)
                .build(),
            () -> new CgUiDesktopScene()
        );

        reg.register(
            SceneDescriptor.builder("rpg-console")
                .description("RPG-Core's Status screen as a STYLESHEET FIXTURE: authors rpgcore:console and rpgcore:menu with no Minecraft client. Needs -Pharness.assetRoots pointing at the mod's src/main/resources; Ctrl+R re-reads the theme AND the sheets")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.02f, 0.10f, 0.14f, 1.0f)
                .build(),
            () -> new RpgConsoleScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-sprite-stress")
                .description("CrystalGUI 9-slice sprite stress: N sprite-backed cells in a grid, for measuring what a sprite costs to draw. -Dcrystalgui.spritestress.count / .cell / .rotate")
                .defaultWidth(1920)
                .defaultHeight(1080)
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiSpriteStressScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-svg-icon")
                .description("Every shipped icon in a labelled grid -- red = failed to load, amber = drew nothing. Scroll to scale.")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiSvgIconScene()
        );

        reg.register(
            SceneDescriptor.builder("gpu-trace-probe")
                .description("DIAGNOSTIC, exits on its own: GPU timer queries land in their frame within 3 frames and match a fence-waited control within 5%")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.09f, 0.09f, 0.11f, 1.0f)
                .build(),
            () -> new CgGpuTraceProbeScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-timeline")
                .description("CrystalGUI timeline primitives: 10,000 spans and 600 frame bars — wheel zoom, drag pan, frame stepping, range selection")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.09f, 0.09f, 0.11f, 1.0f)
                .build(),
            () -> new CgUiTimelineScene()
        );

        // The front door: every widget, one page each, with a live Ore <-> default theme toggle.
        // Deliberately no defaultWidth/defaultHeight — nothing reads SceneDescriptor's, and the
        // gallery's root is `width: 100%`, so `--width=1000 --height=700` gives it more room.
        reg.register(
            SceneDescriptor.builder("cgui-gallery")
                .description("CrystalGUI gallery: every widget, one page each, with a live theme toggle")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiGalleryScene()
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
                .description("3D mesh test: CgMeshBuilder shapes + OBJ + GLTF")
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

        return reg;
    }
}
