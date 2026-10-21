package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.api.material.CgShadingModel;
import com.crystalgraphics.api.material.CgShadingModels;
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
import java.util.List;
import java.util.Map;

/**
 * A kit's shading models drawing: its {@code shading.yaml} registered under a namespace, one of its materials compiled
 * through its parent chain from the kit's files, and drawn for {@link #FRAMES} frames. PASS when the material names a
 * model the kit registered, its program compiles with the model's keyword on, and the frames raise no GL error. Scene id
 * {@code kit-shading}.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=kit-shading" -Pharness.assetRoots=<root holding assets/<kit>> \
 *     -Dcrystalgraphics.harness.kit=<kit dir> -Dcrystalgraphics.harness.kit.namespace=kakarot \
 *     -Dcrystalgraphics.harness.kit.material=kakarot_kit:Art/MDL/Cpl/Cpl001/Material/Cpl001p1c01s1_body_Mt_inst
 * ... --args="--mode=kit-shading --device=vulkan" ...
 * }</pre>
 */
public final class CgKitShadingScene implements InteractiveSceneLifecycle {

    private static final int FRAMES = 60;

    private CgMaterial material;
    private String failure;
    private String described;
    private int frames;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 1.5f, 4f);
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
        } catch (RuntimeException e) {
            failure = e.toString();
        }
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
            world.draw(CgMeshShapes.cube(CgVertexFormat.SPATIAL), material).at(0f, 1f, 0f).submit();
            HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
            if (++frames < FRAMES) return;
            if (material.lastCompileError() != null) failure = material.lastCompileError();
            else if (GlErrorChecker.checkAndLog("kit-shading")) failure = "GL errors, logged above";
        }
        System.out.println(failure == null ? "[kit-shading] PASS: " + described : "[kit-shading] FAIL: " + failure);
        running = false;
    }

    @Override
    public void dispose() {
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return true; }
}
