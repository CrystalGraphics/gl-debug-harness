#type spatial
// post-looks' graded ball: a translucent glow past white, premultiplied, in linear light, as a kit's effect draws. Its
// light is what a graded layer holds; what it covers, its alpha.

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
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
        fragColor = vec4(vec3(2.4, 0.9, 0.3) * 0.8, 0.8);
    }
}
