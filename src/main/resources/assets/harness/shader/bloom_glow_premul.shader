#type spatial
// bloom-occlusion's merged premultiplied glow: blended ONE ONE_MINUS_SRC_ALPHA, its codeless Emissive pass adding
// (ONE ONE), which the merged draw writes with an alpha of 0. Its emission must be the same byte for byte either way.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float a = 0.3 + 0.6 * i.uv.y;
        fragColor = vec4(vec3(1.0, 0.5, 0.9) * a * 3.0, a);
    }
}

Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState { Blend ONE ONE }
}
