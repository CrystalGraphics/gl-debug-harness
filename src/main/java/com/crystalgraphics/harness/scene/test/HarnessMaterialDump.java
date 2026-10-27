package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.renderer.CgVfxMeshRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Each emitter renderer's material parameters as the engine binds them, a row a declared property: the value that wins
 * and where it comes from, for comparing against a capture's live constants. A tab-separated file.
 *
 * <pre>{@code
 * HarnessMaterialDump.write(out, List.of(id), List.of(definition));
 * // system  emitter  renderer  section  material  parent  shader  keywords  parameter  x  y  z  w  source
 * }</pre>
 *
 * <ul>
 *   <li>{@code source} is the last that sets it: {@code default} (the shader's), {@code material} (the asset and its
 *       parents, merged), {@code material-form} (a value form, worked out per draw), {@code renderer} (its
 *       {@code values}), {@code bind} (a user parameter per draw, named in {@code x}), {@code texture}.</li>
 *   <li>What a particle carries (dynamic parameters, sub-UV, its colour) is read per particle and is not listed.</li>
 * </ul>
 */
final class HarnessMaterialDump {

    private static final String HEADER = "system\temitter\trenderer\tsection\tmaterial\tparent\tshader\tkeywords\tparameter"
            + "\tx\ty\tz\tw\tsource\n";

    private HarnessMaterialDump() {
    }

    /** Writes every emitter renderer of {@code systems}, {@code ids} their names, to {@code out}. */
    static void write(Path out, List<String> ids, List<CgVfxSystemDefinition> systems) throws IOException {
        StringBuilder s = new StringBuilder(HEADER);
        Map<String, List<CgMaterialProperty>> declared = new HashMap<>();
        for (int i = 0; i < systems.size(); i++) {
            for (CgVfxLook.Placed placed : systems.get(i).look().placed()) {
                CgVfxRendererProperties renderer = placed.renderer();
                if (renderer == null) continue;
                CgVfxRendererProperties.Common common = renderer.common();
                String prefix = ids.get(i) + '\t' + placed.emitter().name() + '\t' + common.id() + '\t';
                if (renderer instanceof CgVfxMeshRendererProperties mesh && !mesh.materialOverrides().isEmpty()) {
                    for (int k = 0; k < mesh.overrideDefinitions().size(); k++) {
                        rows(s, prefix + k + '\t', mesh.overrideDefinitions().get(k), common, declared);
                    }
                } else {
                    rows(s, prefix + "-\t", common.definition(), common, declared);
                }
            }
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, s.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void rows(StringBuilder s, String prefix, CgMaterialDefinition d, CgVfxRendererProperties.Common common,
                             Map<String, List<CgMaterialProperty>> declared) {
        if (d == null) {
            // A light renderer draws no material.
            s.append(prefix).append(common.material() == null ? "(no material)" : "(not compiled)")
                    .append("\t\t\t\t\t\t\t\t\t\t\n");
            return;
        }
        String shader = d.permutation().shader();
        List<String> keywords = new ArrayList<>(d.permutation().keywords());
        for (String k : common.keywords()) if (!keywords.contains(k)) keywords.add(k);
        String head = prefix + d.id() + '\t' + (d.parent() == null ? "-" : d.parent()) + '\t' + shader + '\t'
                + String.join(",", keywords) + '\t';
        List<CgMaterialProperty> properties = declared.computeIfAbsent(shader, path -> {
            String source = CgIO.loadSource(path);
            if (source == null) throw new IllegalStateException("no shader at " + path + ", which " + d.id() + " draws");
            return CgShaderParser.parseProperties(source, path);
        });
        for (CgMaterialProperty p : properties) {
            if (p.isHidden()) continue;
            String name = p.getName();
            s.append(head).append(name).append('\t');
            if (p.getType().isSampler()) {
                CgMaterialDefinition.Texture t = d.textures().get(name);
                s.append(t == null ? p.getRawDefault() : t.texture()).append("\t\t\t\t")
                        .append(t == null ? "default" : "texture").append('\n');
            } else if (common.bind().containsKey(name)) {
                s.append(common.bind().get(name)).append("\t\t\t\tbind\n");
            } else if (common.values().containsKey(name)) {
                floats(s, common.values().get(name)).append("renderer\n");
            } else if (d.forms().containsKey(name)) {
                s.append(d.forms().get(name)).append("\t\t\t\tmaterial-form\n");
            } else if (d.constants().containsKey(name)) {
                floats(s, d.constants().get(name)).append("material\n");
            } else {
                floats(s, p.getFloatValue()).append("default\n");
            }
        }
    }

    /** Four columns of {@code value}: a float array, a number or a list of numbers; blank past its components. */
    private static StringBuilder floats(StringBuilder s, Object value) {
        float[] f;
        if (value instanceof float[] a) f = a;
        else if (value instanceof Number n) f = new float[]{n.floatValue()};
        else if (value instanceof List<?> list) {
            f = new float[list.size()];
            for (int i = 0; i < f.length; i++) f[i] = list.get(i) instanceof Number n ? n.floatValue() : Float.NaN;
        } else {
            return s.append(value).append("\t\t\t\t");
        }
        for (int i = 0; i < 4; i++) {
            if (i < f.length) s.append(String.format(Locale.ROOT, "%.6g", f[i]));
            s.append('\t');
        }
        return s;
    }
}
