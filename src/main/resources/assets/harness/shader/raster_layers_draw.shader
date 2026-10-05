#type none

// raster-layers' layer pattern (CgRasterLayersTestScene): drawn twice, adding, into layer _Layer of one array, so a
// layer holds x / 64 * (_Layer + 1) in red, y / 64 in green, and its clear plus 0.125 in blue.

Queue = "Geometry"

Properties {
    _Layer ("Which layer this draws", int) = 0
}

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ONE
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        vec2 unused;
    };

    void vertex(out v2f o) {
        o.unused = CG_VERTEX_CORNER;
        gl_Position = vec4(CG_VERTEX_CORNER * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(gl_FragCoord.x / 128.0 * float(_Layer + 1), gl_FragCoord.y / 128.0, 0.0625, 0.125);
    }
}
