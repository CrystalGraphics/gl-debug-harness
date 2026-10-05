#type none

// raster-layers' check (CgRasterLayersTestScene): the first _Count layers of _Fields summed, texel for texel, onto a
// target cleared to zero.

Queue = "Geometry"

Properties {
    _Fields ("The layers", sampler2DArray) = "black"
    _Count  ("Layers to sum", int) = 1
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
        ivec2 p = ivec2(gl_FragCoord.xy);
        vec4 sum = vec4(0.0);
        for (int k = 0; k < _Count; k++) sum += texelFetch(_Fields, ivec3(p, k), 0);
        fragColor = sum;
    }
}
