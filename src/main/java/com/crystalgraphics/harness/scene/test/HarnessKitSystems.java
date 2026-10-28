package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgShadingModels;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadHandle;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemPreparer;

import javax.annotation.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A kit's systems and materials loaded from its files by id for a scene to play: the kit scanned under its namespace,
 * one bundle loaded to {@code READY} at move priority, each system prepared against the scene's {@link CgVfxSystem}.
 *
 * <pre>{@code
 * HarnessKitSystems kit = HarnessKitSystems.load(vfx, "kakarot_kit", folder,
 *         List.of("Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_cpl001_kameha_shot_01_00"));
 * // each frame, until ready() or failed():
 * if (kit.ready()) vfx.spawn(kit.ref(0), x, y, z);
 *
 * // a character's materials and its moves' sequences too, its shading models registered first under their own namespace
 * HarnessKitSystems kit = HarnessKitSystems.load(vfx, "kakarot_kit", folder, List.of(), materialIds,
 *         List.of("kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_01"), "kakarot");
 * CgMaterial body = CgMaterial.of(kit.material(4).get());
 * sequencer.play(kit.sequence(0).get(), Map.of("caster", goku));   // the systems it spawns came with it
 * }</pre>
 *
 * <ul>
 *   <li>The kit's textures resolve as {@code <namespace>:textures/...} only through a resource root holding
 *       {@code assets/<namespace>}: the harness's build adds the plan's {@code local/kitroot} when it is there.</li>
 *   <li>A system's id is its path under the kit's {@code vfx/systems}, without {@code .yaml}; a material's and a
 *       sequence's are whole, as a prefab names them ({@code kakarot_kit:Art/MDL/...}).</li>
 * </ul>
 */
final class HarnessKitSystems {

    private final List<String> ids;
    private final CgAssetRef<CgVfxSystemDefinition>[] refs;
    private final CgAssetRef<CgMaterialDefinition>[] materials;
    private final CgAssetRef<CgSequenceDefinition>[] sequences;
    private final CgLoadHandle handle;

    @SuppressWarnings("unchecked")
    private HarnessKitSystems(CgVfxSystem vfx, String namespace, Path kit, List<String> ids, List<String> materialIds,
                              List<String> sequenceIds, @Nullable String shading) {
        if (!Files.isDirectory(kit)) throw new IllegalStateException("no " + namespace + " at " + kit.toAbsolutePath());
        this.ids = List.copyOf(ids);
        if (shading != null) CgShadingModels.registerKit(shading, kit);
        CgVfxSystemAssets.register();
        CgVfxPrefabAssets.register();
        CgAssetRegistry registry = new CgAssetRegistry();
        registry.registerEngine();
        registry.scan(namespace, kit, CgAssetSource.DEV);
        CgVfxAssets assets = new CgVfxAssets(registry);
        assets.preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(() -> vfx));
        CgVfxAssets.install(assets);   // the frame boundary drains and publishes the process's assets
        refs = new CgAssetRef[ids.size()];
        for (int i = 0; i < refs.length; i++) refs[i] = assets.declare(CgParticleKinds.SYSTEM, namespace + ":" + ids.get(i));
        materials = new CgAssetRef[materialIds.size()];
        for (int i = 0; i < materials.length; i++) materials[i] = assets.declare(CgLookKinds.MATERIAL, materialIds.get(i));
        sequences = new CgAssetRef[sequenceIds.size()];
        for (int i = 0; i < sequences.length; i++) sequences[i] = assets.declare(CgSequenceKinds.SEQUENCE, sequenceIds.get(i));
        List<CgAssetRef<?>> all = new ArrayList<>(List.of(refs));
        all.addAll(List.of(materials));
        all.addAll(List.of(sequences));
        handle = assets.load(all, CgLoadPriority.MOVE);
    }

    /** Starts loading {@code ids} of the kit at {@code kit} (its folder, holding {@code vfx/systems}). */
    static HarnessKitSystems load(CgVfxSystem vfx, String namespace, Path kit, List<String> ids) {
        return new HarnessKitSystems(vfx, namespace, kit, ids, List.of(), List.of(), null);
    }

    /**
     * As {@link #load(CgVfxSystem, String, Path, List)}, and {@code materialIds} and {@code sequenceIds} too, after
     * registering the kit's {@code shading.yaml} under {@code shading} (the namespace its materials name their models by).
     */
    static HarnessKitSystems load(CgVfxSystem vfx, String namespace, Path kit, List<String> ids, List<String> materialIds,
                                  List<String> sequenceIds, String shading) {
        return new HarnessKitSystems(vfx, namespace, kit, ids, materialIds, sequenceIds, shading);
    }

    boolean ready() {
        return handle.ready();
    }

    boolean failed() {
        return handle.failed();
    }

    /** Each asset that failed, with why: what a scene prints when {@link #failed()}. */
    String failures() {
        List<String> why = new ArrayList<>();
        for (CgAssetRef<?> r : refs) if (r.failure() != null) why.add(String.valueOf(r.failure()));
        for (CgAssetRef<?> r : materials) if (r.failure() != null) why.add(String.valueOf(r.failure()));
        for (CgAssetRef<?> r : sequences) if (r.failure() != null) why.add(String.valueOf(r.failure()));
        return String.join("\n  ", why);
    }

    int size() {
        return refs.length;
    }

    String id(int i) {
        return ids.get(i);
    }

    CgAssetRef<CgVfxSystemDefinition> ref(int i) {
        return refs[i];
    }

    /** Material {@code i} of those asked for, in their order. */
    CgAssetRef<CgMaterialDefinition> material(int i) {
        return materials[i];
    }

    /** Sequence {@code i} of those asked for, in their order. */
    CgAssetRef<CgSequenceDefinition> sequence(int i) {
        return sequences[i];
    }
}
