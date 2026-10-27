#type spatial

// skinned-mesh's colour (CgSkinnedMeshTestScene): the normal as a colour, so the GPU's skinning and the CPU's twin
// differ wherever a position or a normal does. Unlit and unfogged: the two halves compare as drawn.

Tags { "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ZERO
        DepthTest ALWAYS
        DepthWrite OFF
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
        fragColor = vec4(normalize(i.normal) * 0.5 + 0.5, 1.0);
    }
}
