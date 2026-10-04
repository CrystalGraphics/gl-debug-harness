#type none

// mrt-emission's read of the emission attachment (CgMrtEmissionScene): _Source texel for texel, so the copy shows that
// a later pass samples attachment 1 through CgGraphTexture.attachment(1), after the pass that wrote it.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Properties {
    _Source ("The emission attachment", sampler2D) = "black"
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
        vec2 unused;
    };

    void vertex(out v2f o) {
        o.unused = CG_VERTEX_CORNER;
        gl_Position = vec4(CG_VERTEX_CORNER * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = texelFetch(_Source, ivec2(gl_FragCoord.xy), 0);
    }
}
