package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.harness.config.SceneDescriptor;
import io.github.somehussar.crystalgraphics.harness.scene.*;
import io.github.somehussar.crystalgraphics.harness.scene.test.ImageScene;
import io.github.somehussar.crystalgraphics.harness.scene.test.InstancingTestScene;
import io.github.somehussar.crystalgraphics.harness.scene.test.ReviewScene;
import io.github.somehussar.crystalgraphics.harness.scene.test.ShaderLibTestScene;
import io.github.somehussar.crystalgraphics.harness.scene.test.CgMaterialDualPathScene;
import io.github.somehussar.crystalgraphics.harness.scene.test.CgAttachedBufferStressScene;
import io.github.somehussar.crystalgraphics.harness.scene.test.CgQuadRendererTestScene;
import io.github.somehussar.crystalgraphics.harness.scene.test.CgForwardRendererScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiButtonScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiCheckboxScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiNineSliceScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiOreThemeScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiSliderScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiSplitViewScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiStylingScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiSwitchScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiTestScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiTextScene;
import io.github.somehussar.crystalgraphics.harness.scene.ui.CgUiVisualLayersScene;
import io.github.somehussar.crystalgraphics.harness.tool.CapabilityReport;
import io.github.somehussar.crystalgraphics.harness.tool.GlStateDumper;

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
            SceneDescriptor.builder("cgui-test")
                .description("CrystalGUI UI test: DOM tree with Taffy layout, sprites, and quads")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiTestScene()
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
            SceneDescriptor.builder("cgui-button")
                .description("CrystalGUI Button: press/release-over-same-element activation, Space/Enter keyboard activation, sound-hook logging")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiButtonScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-checkbox")
                .description("CrystalGUI Checkbox/CheckboxGroup: click/keyboard toggle, :checked-driven mark, group exclusivity (allowEmpty vs required)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiCheckboxScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-ore-theme")
                .description("CrystalGUI ported LDLib2 'Ore' theme: 9-slice button/checkbox/panel sprites via StyleSheetRegistry.of(\"crystalgui:ore\")")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiOreThemeScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-nineslice")
                .description("CrystalGUI 9-slice tiling: stretch/repeat/round/space, CPU quad path vs SDF shader path side by side")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiNineSliceScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-switch")
                .description("CrystalGUI Switch: knob slides via animated flex-grow, timing declared in ore.css")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiSwitchScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-slider")
                .description("CrystalGUI Slider: continuous + stepped, drag/click/keyboard, thumb hover/active/focus states")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiSliderScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-splitview")
                .description("CrystalGUI SplitView: draggable divider, both orientations, nested, oversized-pane content")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiSplitViewScene()
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
