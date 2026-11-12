package com.crystalgraphics.harness.scene.test;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.stage.CgStageFrame;

import javax.annotation.Nullable;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * A gate's view of a graph texture's stencil, read as a post material reads CustomStencil
 * ({@link CgGraphTexture#stencil()}): a pass sampling it into an R8 of its size, value / 255, to read back.
 *
 * <pre>{@code
 * CgGraphTexture bytes = probe.record(stage, stage.resources().get(CgFrameKeys.CUSTOM_DEPTH));
 * if (bytes != null) stage.recording().readback(bytes, 0, 0, 0, w, h, data -> stencil = copy(data));
 * }</pre>
 */
final class StencilProbe {

    private static final CgFormat R8 = CgFormat.R8_UNORM;
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);

    private final CgMaterial copy = CgMaterial.load("assets/harness/shader/stencil_probe.shader");
    private CgGraphTexture into;

    /** Records the copy of {@code from}'s stencil, returning the R8 it lands in; null while its program builds. */
    @Nullable
    CgGraphTexture record(CgStageFrame stage, CgGraphTexture from) {
        CgPipeline pipeline = CgMaterialProxy.of(copy).pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return null;
        int w = from.getWidth(), h = from.getHeight();
        if (into == null || into.getWidth() != w || into.getHeight() != h) {
            into = CgGraphTexture.transientTexture("stencil_probe", new CgTextureDesc(w, h, R8));
        }
        CgRecording recording = stage.recording();
        CgRasterPass pass = recording.raster("stencilProbe.into").color(into, CgLoad.CLEAR).constants(stage.constants());
        int reads = recording.bindings().withTexture(CgMaterialProxy.of(copy).captureBindings(recording.bindings()), 0, from.stencil());
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, reads, FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
        return into;
    }
}
