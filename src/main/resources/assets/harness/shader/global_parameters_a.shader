#type none
#pragma cg_use globals

// global-parameters' first shader (CgGlobalParametersTestScene): a quad at CG_OBJECT_CUSTOM0.xy, CG_OBJECT_CUSTOM0.z
// across, _Color darkened by the harnessGate collection's darken where _Reads is 1, as itself where it is 0.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Properties {
    _Color ("Its colour", color) = (1, 1, 1, 1)
    _Reads ("1 reads the global, 0 ignores it", float) = 1
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
        float unused;
    };

    void vertex(out v2f o) {
        vec2 p = CG_OBJECT_CUSTOM0.xy + CG_VERTEX_CORNER * CG_OBJECT_CUSTOM0.z;
        o.unused = 0.0;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(_Color.rgb * (1.0 - CG_GLOBAL_harnessGate_darken * _Reads), 1.0);
    }
}
