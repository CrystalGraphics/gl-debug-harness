#type spatial
// bloom-occlusion's glowing ball: an opaque surface that writes depth, with an Emissive pass of its own. The Emissive
// pass must survive the depth its own Forward pass wrote, and nothing of it may bloom from behind the wall.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(1.0, 0.7, 0.3, 1.0);
    }
}

Pass {
    Tags { "LightMode" = "Emissive" }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(6.0, 4.0, 1.5, 0.0);
    }
}
