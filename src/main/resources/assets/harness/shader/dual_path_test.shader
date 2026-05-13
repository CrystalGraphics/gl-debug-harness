#type spatial

Queue = "Transparent"

Properties {
    // Samplers — remain as individual uniform sampler* declarations
    // Default values are quoted strings: "white" | "black" | "normal" | "transparent"
    _MainTex    ("Main Texture",    sampler2D)     = "white"
    _NormalMap  ("Normal Map",      sampler2D)     = "normal"
    _TexArray   ("Texture Array",   sampler2DArray)
    _Volume     ("Volume Texture",  sampler3D)
    _Skybox     ("Cubemap",         samplerCube)
    // Non-samplers — all go into layout(std140) uniform CgMaterialBlock { ... }
    _Color      ("Tint Color",      color)         = (1.0, 1.0, 1.0, 1.0)
    _BaseColor  ("Base Color",      vec4)          = (0.2, 0.4, 0.8, 1.0)
    _Offset     ("UV Offset",       vec2)          = (0.0, 0.0)
    _Roughness  ("Roughness",       float)         = 0.5
    _Metallic   ("Metallic",        float)         = 0.0
    _Count      ("Instance Count",  boolean)           = 0
    _Speed      ("Speed",           Range(0, 10))  = 1.0
}

Pass {
    Tags { "LightMode" = "Forward" "Name" = "Main" }

    RenderState {
        // ── Blend ──────────────────────────────────────────────────────────────
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        BlendEquation ADD
        // ── Depth ─────────────────────────────────────────────────────────────
        DepthTest LEQUAL
        DepthWrite OFF
        // ── Cull ──────────────────────────────────────────────────────────────
        Cull BACK
        // ── AlphaTest ─────────────────────────────────────────────────────────
        AlphaTest GREATER 0.5
        // ── ColorMask ─────────────────────────────────────────────────────────
        ColorMask RGBA
        // ── Stencil ───────────────────────────────────────────────────────────
        Stencil {
            Ref 1
            ReadMask 255
            WriteMask 255
            Comp ALWAYS
            Pass REPLACE
            Fail KEEP
            ZFail KEEP
        }
    }

    struct v2f {
        vec2 uv;
        vec3 worldPos;
        vec3 normalWs;
        vec4 tint;
    };

    void vertex(out v2f o) {
        vec4 worldPos4 = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        o.worldPos = worldPos4.xyz;
        o.normalWs = normalize(CG_NORMAL_MATRIX * cg_Normal);
        o.uv = cg_TexCoord0;
        o.tint = CG_OBJECT_CUSTOM0;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = _Color - 0.04 * CG_INSTANCE_ID;
    }
}
