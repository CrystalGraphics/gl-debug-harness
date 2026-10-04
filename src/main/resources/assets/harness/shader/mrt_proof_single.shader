#type none

// mrt-emission's reference (CgMrtEmissionScene): mrt_proof.shader's draw into one attachment at a time, its colour, or
// with GLOW its glow, under the same blend and depth.

#pragma cg_feature GLOW

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    struct v2f {
        vec2 unused;
    };

    void vertex(out v2f o) {
        vec4 r = CG_OBJECT_CUSTOM0;
        vec2 p = mix(r.xy, r.zw, CG_VERTEX_CORNER);
        o.unused = p;
        gl_Position = vec4(p * 2.0 - 1.0, CG_OBJECT_CUSTOM1.x * 2.0 - 1.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
#ifdef GLOW
        fragColor = vec4(CG_OBJECT_CUSTOM3.rgb, CG_OBJECT_CUSTOM2.a);
#else
        fragColor = CG_OBJECT_CUSTOM2;
#endif
    }
}
