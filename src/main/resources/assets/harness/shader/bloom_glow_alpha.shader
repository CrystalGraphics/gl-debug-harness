#type spatial
// bloom-occlusion's merged glow: a transparent ball blended by alpha, its codeless Emissive pass on the same blend, so
// the world renderer draws both in one draw. Its emission must be the same byte for byte drawn either way.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _EmissionStrength ("Emission strength", float) = 5.0
}

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
        fragColor = vec4(0.3, 0.8, 1.0, 0.25 + 0.5 * i.uv.x);
    }
}

Pass { Tags { "LightMode" = "Emissive" } }
