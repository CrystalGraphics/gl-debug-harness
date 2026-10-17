#type spatial

// sampler-binding's quads (CgSamplerBindingTestScene): the unit square placed at CG_OBJECT_CUSTOM0.xy and scaled by
// CG_OBJECT_CUSTOM0.z, sampling _MainTex at u and v from -1 to 2, so how the binding's sampler wraps shows.

Tags { "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Geometry"

Properties {
    _MainTex ("Texture", sampler2D) = "white"
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
        vec2 uv;
    };

    void vertex(out v2f o) {
        vec2 p = CG_OBJECT_CUSTOM0.xy + cg_Position.xy * CG_OBJECT_CUSTOM0.z;
        o.uv = cg_Position.xy * 3.0 - 1.0;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(texture(_MainTex, i.uv).rgb, 1.0);
    }
}
