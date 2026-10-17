#type none
#pragma cg_use globals

// global-parameters' second shader (CgGlobalParametersTestScene): another material reading the same collection, in
// both stages: its quad tinted by the tint vector in the vertex stage and darkened in the fragment stage.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Properties {
    _Color ("Its colour", color) = (1, 1, 1, 1)
}

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        vec3 colour;
    };

    void vertex(out v2f o) {
        vec2 p = CG_OBJECT_CUSTOM0.xy + CG_VERTEX_CORNER * CG_OBJECT_CUSTOM0.z;
        o.colour = _Color.rgb * CG_GLOBAL_harnessGate_tint.rgb;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(i.colour * (1.0 - CG_GLOBAL_harnessGate_darken), 1.0);
    }
}
