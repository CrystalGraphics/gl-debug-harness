package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.api.material.CgShadingModel;
import com.crystalgraphics.api.material.CgShadingModels;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.tool.GlErrorChecker;
import com.crystalgraphics.render.world.CgWorldRenderer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A kit's shading models drawing: its {@code shading.yaml} registered under a namespace, one of its materials compiled
 * through its parent chain from the kit's files, and drawn. With {@code .kit.mesh}, a kit model is drawn instead of a
 * cube, each submesh with its own kit material beside {@code .kit.material}'s folder where that compiles (a skinned
 * model in its bind pose). PASS after {@link #FRAMES} frames when the material names a model the kit registered, its
 * program compiles with the model's keyword on, and nothing raised a GL error; {@code .kit.hold=true} then keeps
 * drawing until the window closes. Scene id {@code kit-shading}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=kit-shading" -Pharness.assetRoots=<root holding assets/<kit>> \
 *     -Dcrystalgraphics.harness.kit=<kit dir> -Dcrystalgraphics.harness.kit.namespace=kakarot \
 *     -Dcrystalgraphics.harness.kit.material=kakarot_kit:Art/MDL/Cpl/Cpl001/Material/Cpl001p1c01s1_body_Mt_inst
 * // Goku whole, held open
 * ... -Dcrystalgraphics.harness.kit.mesh=kakarot_kit:models/Art/MDL/Cpl/Cpl001/Model/Cpl001p1c01s1.glb \
 *     -Dcrystalgraphics.harness.kit.hold=true
 * }</pre>
 */
public final class CgKitShadingScene implements InteractiveSceneLifecycle {

    private static final int FRAMES = 60;
    private static final boolean HOLD = Boolean.getBoolean("crystalgraphics.harness.kit.hold");

    private final List<CgMaterial> parts = new ArrayList<>();
    private final List<Integer> submeshes = new ArrayList<>();
    private CgMaterial material;
    private CgMesh mesh;
    private String failure;
    private String described;
    private int frames;
    private boolean reported, running = true;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 1.1f, 2.6f);
        try {
            CgAssetKinds.register();
            Path kit = Paths.get(System.getProperty("crystalgraphics.harness.kit"));
            String namespace = System.getProperty("crystalgraphics.harness.kit.namespace");
            String id = System.getProperty("crystalgraphics.harness.kit.material");
            List<CgShadingModel> models = CgShadingModels.registerKit(namespace, kit);
            CgMaterialDefinition d = CgMaterialDefinitions.compile(material(id, kit));
            if (d.shading() == null || !d.shading().id().startsWith(namespace + ":")) {
                failure = id + " names no shading model of " + namespace + ": " + d.shading();
                return;
            }
            String keyword = d.shading().keyword();
            if (keyword != null && !d.permutation().keywords().contains(keyword)) {
                failure = id + ": " + d.shading().id() + "'s keyword " + keyword + " is not on";
                return;
            }
            material = CgMaterial.of(d);
            described = models.size() + " models registered; " + id + " draws as " + d.shading().id() + " (index "
                    + d.shading().index() + ") through " + d.permutation().shader() + " " + d.permutation().keywords();
            String meshPath = System.getProperty("crystalgraphics.harness.kit.mesh");
            if (meshPath != null) model(meshPath, id, kit);
        } catch (RuntimeException e) {
            failure = e.toString();
        }
    }

    /** {@code path}'s submeshes, each with the kit material of its name in {@code id}'s folder where that compiles. */
    private void model(String path, String id, Path kit) {
        CgMeshLoader.Model model = CgMeshLoader.model(path);
        mesh = model.mesh();
        String folder = id.substring(0, id.lastIndexOf('/') + 1);
        List<String> drawn = new ArrayList<>(), skipped = new ArrayList<>();
        for (int i = 0; i < model.materials().size(); i++) {
            String name = model.material(i);
            if (name == null) continue;
            try {
                CgMaterial m = CgMaterial.of(CgMaterialDefinitions.compile(material(folder + name, kit)));
                if (m.lastCompileError() != null) throw new IllegalStateException(m.lastCompileError());
                parts.add(m);
                submeshes.add(i);
                drawn.add(name);
            } catch (RuntimeException e) {
                skipped.add(name + " (" + e.getMessage() + ")");
            }
        }
        System.out.println("[kit-shading] " + path + ": drawn " + drawn + "; skipped " + skipped);
    }

    /** The kit material {@code id}, resolved over its parent chain from the kit's {@code materials/}. */
    private static CgAssetData material(String id, Path kit) {
        String namespace = id.substring(0, id.indexOf(':'));
        Path file = kit.resolve("materials").resolve(id.substring(namespace.length() + 1) + ".yaml");
        try {
            Map<?, ?> tree = (Map<?, ?>) CgAssetYaml.read(Files.readString(file));
            CgAssetData parent = tree.get("parent") instanceof String p ? material(p, kit) : null;
            return CgAssetData.of(id, CgLookKinds.MATERIAL, tree, parent);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (!running) return;
        if (failure == null) {
            CgWorldRenderer world = CgWorldRenderer.get();
            if (mesh == null) {
                world.draw(CgMeshShapes.cube(CgVertexFormat.SPATIAL), material).at(0f, 1f, 0f).submit();
            } else {
                for (int i = 0; i < parts.size(); i++) world.draw(mesh, parts.get(i)).submesh(submeshes.get(i)).at(0f, 0f, 0f).submit();
            }
            HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
            if (++frames < FRAMES || reported) return;
            if (material.lastCompileError() != null) failure = material.lastCompileError();
            else if (GlErrorChecker.checkAndLog("kit-shading")) failure = "GL errors, logged above";
        }
        System.out.println(failure == null ? "[kit-shading] PASS: " + described : "[kit-shading] FAIL: " + failure);
        reported = true;
        running = HOLD && failure == null;
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return !HOLD; }
}
