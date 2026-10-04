#type spatial
// overdraw-count's plane (CgOverdrawTestScene): transparent, depth-tested, back faces culled. CUT discards its bottom
// half, which the overdraw view must not count.

#pragma cg_feature CUT

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
#ifdef CUT
        if (gl_FragCoord.y < CG_RESOLUTION.y * 0.5) discard;
#endif
        fragColor = vec4(0.2, 0.5, 1.0, 0.2);
    }
}
