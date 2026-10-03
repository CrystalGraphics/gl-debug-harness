#type none

// indirect-draw's cells (CgIndirectDrawTestScene): an 8 by 8 grid of 8-pixel cells from CG_OBJECT_CUSTOM0.xy, a cell
// per quad, per instance or per triangle as CG_OBJECT_CUSTOM0.z says (0, 1, 2), coloured CG_OBJECT_CUSTOM1.

Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ZERO
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        vec4 color;
    };

    void vertex(out v2f o) {
        int by = int(CG_OBJECT_CUSTOM0.z);
        int cell;
        vec2 corner;
        if (by == 2) {
            cell = CG_VERTEX_ID / 3;
            int k = CG_VERTEX_ID - cell * 3;
            corner = vec2(k == 1 ? 1.0 : 0.0, k == 2 ? 1.0 : 0.0);
        } else {
            cell = by == 1 ? CG_DRAW_INSTANCE : CG_VERTEX_ID >> 2;
            corner = CG_VERTEX_CORNER;
        }
        vec2 p = CG_OBJECT_CUSTOM0.xy + (vec2(float(cell % 8), float(cell / 8)) + 0.125 + corner * 0.75) * 8.0;
        o.color = CG_OBJECT_CUSTOM1;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = i.color;
    }
}
