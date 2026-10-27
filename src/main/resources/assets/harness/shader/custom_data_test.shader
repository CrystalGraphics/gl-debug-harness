#type spatial
// custom-depth's writer (CgCustomDepthTestScene): flat grey, opaque, and Unreal's custom data and shading model, known
// values the probe reads back.

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
        CG_CUSTOM_DATA_OUT(vec4(0.2, 0.4, 0.6, 0.5));
        CG_SHADING_MODEL_OUT(13u, 2u);
        fragColor = vec4(0.3, 0.3, 0.32, 1.0);
    }
}
