#type spatial
// post-looks' opaque graded ball: a kit's masked or opaque effect draw, saturated and past white, in linear light. Its
// alpha is below 1 on purpose: an opaque draw covers what is behind it whatever alpha it writes.
#pragma cg_use rows

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Geometry"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(1.8, 0.5, 0.15, 0.4);
    }
}
