#type none

// The gates' CustomStencil probe (StencilProbe): each pixel's stencil read as a post material reads it, a usampler2D of
// the depth-stencil itself, written as value / 255 into an R8 the gate reads back.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Target" "Media" = "Off" }
Queue = "Transparent"

Properties {
    _Stencil ("The stencil read", usampler2D) = "zero"
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(float(texelFetch(_Stencil, ivec2(gl_FragCoord.xy), 0).r) / 255.0, 0.0, 0.0, 0.0);
    }
}
