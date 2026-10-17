#type spatial
// SECOND_LAYER samples at the mesh's second UV set, as a CC2 two-texture layer does.
#pragma cg_feature SECOND_LAYER

// second-layer's quads (CgSecondLayerTestScene): the mesh placed at CG_OBJECT_CUSTOM0.xy, CG_OBJECT_CUSTOM0.z across,
// showing _MainTex at its first UV set, or its second under SECOND_LAYER, times its vertex colour.

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
        vec3 colour;
    };

    void vertex(out v2f o) {
        vec2 p = CG_OBJECT_CUSTOM0.xy + cg_Position.xy * CG_OBJECT_CUSTOM0.z;
#ifdef SECOND_LAYER
        o.uv = cg_TexCoord1;
#else
        o.uv = cg_TexCoord0;
#endif
        o.colour = cg_Color.rgb;
        gl_Position = CG_MATRIX_MVP * vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(texture(_MainTex, i.uv).rgb * i.colour, 1.0);
    }
}
