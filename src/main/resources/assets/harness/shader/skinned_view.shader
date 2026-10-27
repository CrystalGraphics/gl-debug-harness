#type spatial

// skinned-play's surface (CgSkinnedPlayScene): CG_OBJECT_CUSTOM0's colour, half-Lambert lit from a fixed sun so a
// pose reads by its shading, then the world's lightmap as any material.

Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        DepthTest LEQUAL
        Cull OFF
    }

    struct v2f {
        vec3 normal;
    };

    void vertex(out v2f o) {
        o.normal = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 n = normalize(i.normal);
        float lambert = dot(n, normalize(vec3(0.4, 0.8, 0.45))) * 0.5 + 0.5;
        fragColor = vec4(CG_OBJECT_CUSTOM0.rgb * (0.25 + 0.75 * lambert * lambert), 1.0);
    }
}
