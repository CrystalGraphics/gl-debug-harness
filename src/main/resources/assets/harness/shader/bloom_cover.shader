#type spatial
// bloom-occlusion's cover under the HDR scene: a transparent pane that glows nothing, in front of a glowing ball. At
// _Alpha 1 the ball must leave no trace, glow and bloom included; below it the ball shows dimmed.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Alpha ("Coverage", float) = 1.0
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
        fragColor = vec4(0.2, 0.3, 0.4, _Alpha);
    }
}
