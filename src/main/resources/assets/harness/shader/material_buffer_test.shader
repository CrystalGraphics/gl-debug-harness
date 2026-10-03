#type none

// material-buffer's cells (CgMaterialBufferTestScene): a quad per live cell, drawn as many times as the GPU counted.
// The vertex stage reads which cell from LIVE and its rect from CELLS, the fragment stage its colour from CELLS; a
// kernel wrote both in the same frame. CG_OBJECT_CUSTOM0.xy is the grid's origin.

Queue = "Geometry"

struct Cell { vec4 rect; vec4 color; };

Buffers {
    CELLS ("Cells",      Cell, readonly)
    LIVE  ("Live cells", uint, readonly)
}

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ZERO
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        float cell;
    };

    void vertex(out v2f o) {
        uint cell = LIVE(CG_VERTEX_ID >> 2);
        vec4 rect = CELLS(cell).rect;
        vec2 p = CG_OBJECT_CUSTOM0.xy + rect.xy + CG_VERTEX_CORNER * rect.zw;
        o.cell = float(cell);
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = CELLS(int(i.cell + 0.5)).color;
    }
}
