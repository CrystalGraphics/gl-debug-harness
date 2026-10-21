#type none

// property-table's material (CgPropertyTableScene): a quad at CG_OBJECT_CUSTOM0.xy, CG_OBJECT_CUSTOM0.z across, drawn
// in element CG_OBJECT_CUSTOM0.w of a 64-element table.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Geometry"

Properties {
    _Table ("Sixty-four colours, one a quad", vec4[64]) = (0, 0, 0, 1)
}

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        float element;
    };

    void vertex(out v2f o) {
        vec2 p = CG_OBJECT_CUSTOM0.xy + CG_VERTEX_CORNER * CG_OBJECT_CUSTOM0.z;
        o.element = CG_OBJECT_CUSTOM0.w;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(_Table(int(i.element + 0.5)).rgb, 1.0);
    }
}
