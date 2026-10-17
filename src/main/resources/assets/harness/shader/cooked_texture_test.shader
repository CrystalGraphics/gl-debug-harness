#type spatial

// cooked-texture's quads (CgCookedTextureTestScene): the unit square placed at CG_OBJECT_CUSTOM0.xy, CG_OBJECT_CUSTOM0.z
// wide and three quarters of that high, showing _MainTex's colour, or its alpha as grey where CG_OBJECT_CUSTOM0.w is 1.

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
        vec2 p = CG_OBJECT_CUSTOM0.xy + cg_Position.xy * vec2(CG_OBJECT_CUSTOM0.z, CG_OBJECT_CUSTOM0.z * 0.75);
        o.uv = cg_Position.xy;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 t = texture(_MainTex, i.uv);
        fragColor = CG_OBJECT_CUSTOM0.w > 0.5 ? vec4(vec3(t.a), 1.0) : vec4(t.rgb, 1.0);
    }
}
