package com.crystalgraphics.harness.util;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.CgRenderer;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Object records drawn now into the current target and its depth: a mesh under a material, one instance per record.
 *
 * <pre>{@code
 * ObjectDraws.draw(camera, material, mesh, 1, (i, data, at) -> ObjectDraws.object(data, at, model, 1f, 1f, 1f, 1f));
 * }</pre>
 */
public final class ObjectDraws {

    /** Writes instance {@code index}'s record into {@code data} at {@code at}: 48 floats, zeroed. */
    @FunctionalInterface
    public interface Records {
        void write(int index, float[] data, int at);
    }

    private static final CgRecording RECORDING = new CgRecording();
    private static float[] records = new float[CgPrimitiveTable.FLOATS * 8];

    private ObjectDraws() {
    }

    public static void draw(CgCamera camera, CgMaterial material, CgMesh mesh, int count, Records records) {
        draw(camera, null, material, mesh, count, records);
    }

    /** As above, under {@code state} for whatever the materials leave unset: null keeps the target's. */
    public static void draw(CgCamera camera, @Nullable CgRenderState state, CgMaterial material, CgMesh mesh,
                            int count, Records writer) {
        int floats = count * CgPrimitiveTable.FLOATS;
        if (records.length < floats) records = new float[floats];
        Arrays.fill(records, 0, floats, 0f);
        for (int i = 0; i < count; i++) writer.write(i, records, i * CgPrimitiveTable.FLOATS);
        RECORDING.reset();
        CgRasterPass pass = RECORDING.raster("objects").color(CgGraphTexture.current(), CgLoad.load())
                .depth(CgGraphTexture.current(), CgLoad.load(), CgDepthAccess.WRITE).camera(camera).state(state)
                .order(CgOrder.LOOKBACK);
        pass.draw(mesh, material).records(records, 0, count);
        pass.end();
        CgRenderer.executeNow(RECORDING);
    }

    /** A record: {@code model}, its normal matrix taken as identity (rigid, unscaled), and custom0. */
    public static void object(float[] data, int at, Matrix4fc model, float r, float g, float b, float a) {
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
