// SECOND_LAYER samples at the mesh's second UV set, as a CC2 two-texture layer does.
#domain surface
Material { ShadingModel Unlit  Faces Both  DepthTest Off  DepthWrite Off  Fog Off }

Keywords {
    SECOND_LAYER ("SECOND_LAYER")
}

Properties {
    _MainTex ("Texture", sampler2D) = "white"
}

struct v2f {
    vec2 uv;
    vec3 colour;
};

void vertex(inout CgVertex v, out v2f o) {
    vec2 p = CG_OBJECT_CUSTOM0.xy + cg_Position.xy * CG_OBJECT_CUSTOM0.z;
#ifdef SECOND_LAYER
    o.uv = cg_TexCoord1;
#else
    o.uv = cg_TexCoord0;
#endif
    o.colour = cg_Color.rgb;
    v.world = (CG_OBJECT_TO_WORLD * vec4(p, 0.0, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = texture(_MainTex, i.uv).rgb * i.colour;
}
