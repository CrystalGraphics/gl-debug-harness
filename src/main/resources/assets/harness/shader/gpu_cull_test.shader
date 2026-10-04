#type spatial

// gpu-cull's instances and wall (CgGpuCullTestScene): CG_OBJECT_CUSTOM0's colour, shaded by the normal so each level's
// facets show; unlit and unfogged, so the CPU's cull and the GPU's draw the same pixels.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

struct v2f {
    vec3 normal;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ZERO
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    void vertex(out v2f o) {
        o.normal = CG_NORMAL_MATRIX * cg_Normal;
        o.color = CG_OBJECT_CUSTOM0;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(i.color.rgb * (0.55 + 0.45 * normalize(i.normal).z), 1.0);
    }
}
