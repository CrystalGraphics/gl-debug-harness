#type none

// texture-cube's material (CgTextureCubeScene): a quad at CG_OBJECT_CUSTOM0.xy, CG_OBJECT_CUSTOM0.z across, drawing
// the stacked cube along axis CG_OBJECT_CUSTOM0.w (0 to 5: +X, -X, +Y, -Y, +Z, -Z), or the float ramp at 6.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Geometry"

Properties {
    _Cube ("Six faces, a grey each", samplerCube) = "white"
    _Ramp ("Half floats: 0.5, 0.25, 0.125", sampler2D) = "white"
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
        float which;
    };

    void vertex(out v2f o) {
        vec2 p = CG_OBJECT_CUSTOM0.xy + CG_VERTEX_CORNER * CG_OBJECT_CUSTOM0.z;
        o.which = CG_OBJECT_CUSTOM0.w;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        int which = int(i.which + 0.5);
        if (which == 6) {
            fragColor = vec4(texture(_Ramp, vec2(0.5)).rgb, 1.0);
            return;
        }
        vec3 axis = vec3(0.0);
        axis[which / 2] = (which & 1) == 0 ? 1.0 : -1.0;
        fragColor = vec4(texture(_Cube, axis).rgb, 1.0);
    }
}
