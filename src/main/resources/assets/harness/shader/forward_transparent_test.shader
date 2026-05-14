#type spatial

Queue = "Transparent"

Properties {
    _Color ("Color", vec4) = (1.0, 1.0, 1.0, 0.5)
}

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
        ColorMask RGBA
    }

    struct v2f {
        vec3 worldPos;
    };

    void vertex(out v2f o) {
        o.worldPos = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // Modulate material color by per-instance custom0 tint.
        // _Color.a carries the base transparency (default 0.5).
        fragColor = _Color * CG_OBJECT_CUSTOM0;
    }
}
