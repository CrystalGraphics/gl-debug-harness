package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.shader.CgShader;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.PlatformServiceHarness;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDeviceSets;
import com.crystalgraphics.render.draw.CgDeviceStates;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.shader.CgBindingModel;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Every shipped material's set 2 made on the device from its recorded bindings ({@code CgDeviceSets}), against its
 * device pipeline's layout: no slot left to a default, a second frame making no set, a changed block making one, or,
 * where the material's values live in its shader's arena, none and a new slot. On
 * {@code --rhi=vulkan} and {@code --rhi=gl}.
 *
 * <pre>
 *   ./gradlew :gl-debug-harness:runHarness --args="--mode=device-sets --rhi=vulkan"
 * </pre>
 *
 * <p>Prints {@code [device-sets] PASS} or {@code FAIL}; on Vulkan a validation error fails it.</p>
 */
public class CgDeviceSetsTestScene implements HarnessSceneLifecycle {

    private static final String DOCUMENTATION_ONLY = "example.shader";
    private boolean done;

    @Override
    public void init(HarnessContext ctx) {
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        if (done) return;
        done = true;
        String on = PlatformServiceHarness.deviceInfo() != null ? PlatformServiceHarness.deviceInfo().name() : "gl";
        try {
            System.out.println("[device-sets] " + check(on));
        } catch (Exception e) {
            System.out.println("[device-sets] FAIL on " + on + ": " + e);
            e.printStackTrace();
        }
    }

    private record Drawn(String path, CgMaterial material, CgPipelineLayout layout) {}

    private static String check(String on) throws Exception {
        CgRHI rhi = CgRHI.get();
        CgBindingTable table = new CgBindingTable();
        CgStreamBuffer ring = CgStreamBuffer.frameLocalUniforms(1 << 20);
        CgDeviceSets sets = new CgDeviceSets();
        List<Drawn> drawn = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int skipped = 0;
        try {
            for (String path : materials()) {
                CgMaterial m = CgMaterial.newInstance(path);
                CgPipeline p = m.pipeline(CgInstanceKind.OBJECT);
                CgShader linked = p == null ? null : p.program();
                if (linked == null) {
                    failures.add(path + ": no program");
                    m.delete();
                    continue;
                }
                CgGlslCompiler.Explicit explicit = linked.explicit(p.toString());
                if (explicit.sets().size() <= CgBindingModel.MATERIAL || explicit.sets().get(CgBindingModel.MATERIAL).isEmpty()) {
                    skipped++;   // reads nothing of its own
                    m.delete();
                    continue;
                }
                drawn.add(new Drawn(path, m, CgDeviceStates.layout(rhi, explicit)));
            }
            int[] ids = capture(table, ring, drawn, failures);
            sets.prepare(table);
            long defaultedBefore = sets.defaulted();
            CgDeviceSection.begin();
            try {
                for (int i = 0; i < drawn.size(); i++) {
                    if (ids[i] < 0) continue;
                    long before = sets.defaulted();
                    sets.set(rhi, table, ids[i], drawn.get(i).layout(), CgBindingModel.MATERIAL, null);
                    if (sets.defaulted() != before) failures.add(drawn.get(i).path() + ": " + sets.lastDefaulted() + " defaulted");
                }
            } finally {
                CgDeviceSection.end();
            }
            long firstMade = sets.made();

            // A second frame: the same captures make no set.
            table.reset();
            ids = capture(table, ring, drawn, failures);
            sets.prepare(table);
            CgDeviceSection.begin();
            try {
                for (int i = 0; i < drawn.size(); i++) {
                    if (ids[i] >= 0) sets.set(rhi, table, ids[i], drawn.get(i).layout(), CgBindingModel.MATERIAL, null);
                }
            } finally {
                CgDeviceSection.end();
            }
            long secondMade = sets.made() - firstMade;
            if (secondMade != 0) failures.add("a second frame of the same captures made " + secondMade + " sets");

            // A changed block: one new set, or in an arena none, the material taking another slot of the arena's one set.
            int[] slotsBefore = new int[drawn.size()];
            for (int i = 0; i < drawn.size(); i++) slotsBefore[i] = ids[i] < 0 ? 0 : table.slotOf(ids[i]);
            String changed = changeOne(drawn);
            long changeMade = -1;
            boolean moved = false;
            if (changed != null) {
                table.reset();
                ids = capture(table, ring, drawn, failures);
                sets.prepare(table);
                long before = sets.made();
                CgDeviceSection.begin();
                try {
                    for (int i = 0; i < drawn.size(); i++) {
                        if (ids[i] >= 0) sets.set(rhi, table, ids[i], drawn.get(i).layout(), CgBindingModel.MATERIAL, null);
                    }
                } finally {
                    CgDeviceSection.end();
                }
                changeMade = sets.made() - before;
                for (int i = 0; i < drawn.size(); i++) moved |= ids[i] >= 0 && table.slotOf(ids[i]) != slotsBefore[i];
                long expected = moved ? 0 : 1;
                if (changeMade != expected) {
                    failures.add("changing " + changed + " made " + changeMade + " sets, not " + expected
                            + (moved ? " (its arena's set holds every slot)" : ""));
                }
            }
            int validation = PlatformServiceHarness.validationErrors();
            if (validation > 0) failures.add(validation + " Vulkan validation errors, logged above");
            if (!failures.isEmpty()) {
                Map<String, Integer> kinds = new TreeMap<>();
                for (String f : failures) kinds.merge(f.substring(f.indexOf(": ") + 2), 1, Integer::sum);
                return "FAIL on " + on + ": " + failures.size() + " failures, by kind " + kinds + "; the first: "
                        + String.join(" | ", failures.subList(0, Math.min(4, failures.size())));
            }
            return "PASS on " + on + ": " + drawn.size() + " materials' sets made against their device pipelines ("
                    + firstMade + " sets, " + skipped + " materials reading nothing of their own), no slot defaulted ("
                    + (sets.defaulted() - defaultedBefore) + "); a second frame made none; changing " + changed
                    + " made " + changeMade + (moved ? ", its values moving to another slot of its arena" : "");
        } finally {
            CgDeviceSection.begin();
            sets.release();
            CgDeviceSection.end();
            for (Drawn d : drawn) d.material().delete();
            ring.delete();
        }
    }

    /** Each material's bindings captured into {@code table}, uploaded; -1 for one whose capture threw. */
    private static int[] capture(CgBindingTable table, CgStreamBuffer ring, List<Drawn> drawn, List<String> failures) {
        int[] ids = new int[drawn.size()];
        for (int i = 0; i < drawn.size(); i++) {
            try {
                ids[i] = drawn.get(i).material().captureBindings(table);
            } catch (IllegalStateException e) {
                ids[i] = -1;   // reads a buffer only its owner binds: not a material set this scene can make
            }
        }
        table.upload(ring);
        return ids;
    }

    /** Sets a float property of the first material that has one to a new value; its name, or null if none has. */
    private static String changeOne(List<Drawn> drawn) {
        for (Drawn d : drawn) {
            for (CgMaterialProperty p : d.material().properties()) {
                if (p.getType() != CgMaterialProperty.Type.FLOAT || p.getName().startsWith("_cg")) continue;
                d.material().applyProperties(b -> b.set1f(p.getName(), 0.123f));
                return d.path() + " " + p.getName();
            }
        }
        return null;
    }

    private static List<String> materials() throws Exception {
        URI dir = CgMaterial.class.getResource("/assets/crystalgraphics/shaders/").toURI();
        try (FileSystem jar = "jar".equals(dir.getScheme()) ? FileSystems.newFileSystem(dir, Map.of()) : null;
             Stream<Path> files = Files.walk(Path.of(dir))) {
            Path root = Path.of(dir);
            return files.map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .filter(n -> n.endsWith(".shader") && !n.equals(DOCUMENTATION_ONLY))
                    .sorted().map(n -> "crystalgraphics:shaders/" + n).toList();
        }
    }

    @Override
    public void dispose() {
    }
}
