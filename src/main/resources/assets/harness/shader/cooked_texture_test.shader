// cooked-texture's quads (CgCookedTextureTestScene): the unit square placed at CG_OBJECT_CUSTOM0.xy, CG_OBJECT_CUSTOM0.z
// wide and three quarters of that high, showing _MainTex's colour, or its alpha as grey where CG_OBJECT_CUSTOM0.w is 1.
#domain surface
Material { ShadingModel Unlit  Faces Both  DepthTest Off  DepthWrite Off  Fog Off }

Properties {
    _MainTex ("Texture", sampler2D) = "white"
}

struct v2f {
    vec2 uv;
};

void vertex(inout CgVertex v, out v2f o) {
    vec2 p = CG_OBJECT_CUSTOM0.xy + cg_Position.xy * vec2(CG_OBJECT_CUSTOM0.z, CG_OBJECT_CUSTOM0.z * 0.75);
    o.uv = cg_Position.xy;
    v.world = (CG_OBJECT_TO_WORLD * vec4(p, 0.0, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 t = texture(_MainTex, i.uv);
    vec4 color = CG_OBJECT_CUSTOM0.w > 0.5 ? vec4(vec3(t.a), 1.0) : vec4(t.rgb, 1.0);
    s.baseColor = color.rgb;
}
