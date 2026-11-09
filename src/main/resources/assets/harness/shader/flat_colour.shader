#type spatial

// A flat, opaque colour from the record's custom0: the harness's floor, drawn as a host's world is.

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    struct v2f {
        vec3 localPos;
    };

    void vertex(out v2f o) {
        o.localPos = cg_Position;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(CG_OBJECT_CUSTOM0.rgb, 1.0);
    }
}
