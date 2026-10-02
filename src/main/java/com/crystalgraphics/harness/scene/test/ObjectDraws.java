package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;

/**
 * Object records drawn now through {@link CgImmediate}: a mesh under a material and each pass of its chain, one
 * instance per record, into whatever framebuffer is bound.
 *
 * <pre>{@code
 * ObjectDraws.draw(constants, material, mesh, 1, (i, data, at) -> ObjectDraws.object(data, at, model, 1f, 1f, 1f, 1f));
 * }</pre>
 */
final class ObjectDraws {

    /** Writes instance {@code index}'s record into {@code data} at {@code at}: 48 floats, zeroed. */
    @FunctionalInterface
    interface Records {
        void write(int index, float[] data, int at);
    }

    private ObjectDraws() {
    }

    static void draw(CgPassConstants constants, CgMaterial material, CgMesh mesh, int count, Records records) {
        draw(constants, null, material, mesh, count, records);
    }

    /** As above, under {@code state} for whatever the materials leave unset: null keeps the framebuffer's. */
    static void draw(CgPassConstants constants, @Nullable CgRenderState state, CgMaterial material, CgMesh mesh,
                     int count, Records records) {
        try (CgImmediate draw = CgImmediate.begin(constants, state, CgOrder.LOOKBACK)) {
            CgChunkBuilder chunks = draw.chunks();
            for (CgMaterial link = material; link != null; link = link.getNextPass()) {
                CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
                if (pipeline == null) continue;
                chunks.draw(pipeline, link.captureBindings(draw.bindings()), mesh);
                for (int i = 0; i < count; i++) {
                    int at = chunks.instance();
                    records.write(i, chunks.data(), at);
                }
            }
        }
    }

    /** A record: {@code model}, its normal matrix taken as identity (rigid, unscaled), and custom0. */
    static void object(float[] data, int at, Matrix4fc model, float r, float g, float b, float a) {
        model.get(data, at);
        data[at + 16] = 1f;
        data[at + 21] = 1f;
        data[at + 26] = 1f;
        data[at + 31] = 1f;
        data[at + 32] = r;
        data[at + 33] = g;
        data[at + 34] = b;
        data[at + 35] = a;
    }
}
