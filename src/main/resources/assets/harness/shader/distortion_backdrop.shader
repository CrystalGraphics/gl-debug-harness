#type spatial
// distortion's backdrop (CgDistortionTestScene): opaque, writing depth, each pixel's own coordinates as its colour
// (x, y, x) / 255, so a pixel the apply bent says where it sampled. WALL is a flat blue instead.

#pragma cg_feature WALL

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    void vertex(out v2f o) {
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.uv = cg_TexCoord0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
#ifdef WALL
        fragColor = vec4(0.0, 0.0, 1.0, 1.0);
#else
        vec2 p = floor(gl_FragCoord.xy);
        fragColor = vec4(p.x / 255.0, p.y / 255.0, p.x / 255.0, 1.0);
#endif
    }
}
