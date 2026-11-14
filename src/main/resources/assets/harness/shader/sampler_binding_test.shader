// sampler-binding's quads (CgSamplerBindingTestScene): the unit square placed at CG_OBJECT_CUSTOM0.xy and scaled by
// CG_OBJECT_CUSTOM0.z, sampling _MainTex at u and v from -1 to 2, so how the binding's sampler wraps shows.
#domain surface
Material { ShadingModel Unlit  Faces Both  DepthTest Off  DepthWrite Off  Fog Off }

Properties {
    _MainTex ("Texture", sampler2D) = "white"
}

struct v2f {
    vec2 uv;
};

void vertex(inout CgVertex v, out v2f o) {
    vec2 p = CG_OBJECT_CUSTOM0.xy + cg_Position.xy * CG_OBJECT_CUSTOM0.z;
    o.uv = cg_Position.xy * 3.0 - 1.0;
    v.world = (CG_OBJECT_TO_WORLD * vec4(p, 0.0, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = texture(_MainTex, i.uv).rgb;
}
