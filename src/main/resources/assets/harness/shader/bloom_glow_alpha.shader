// bloom-occlusion's merged glow: a transparent ball blended by alpha, its codeless Emissive pass on the same blend, so
// the world renderer draws both in one draw. Its emission must be the same byte for byte drawn either way.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  ColorSpace Linear  Fog Off }

Properties {
    _EmissionStrength ("Emission strength", float) = 5.0
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 glowLight = vec3(0.0);
    vec4 color = vec4(0.3, 0.8, 1.0, 0.25 + 0.5 * i.uv.x);
    s.emissive = color.rgb;
    s.opacity = color.a;
    glowLight = s.emissive;
    // The glow it drew beside its colour is light it gives off: an unlit surface draws one colour
    s.emissive = cg_SceneDecode(s.emissive);
    s.emissive += glowLight * CG_EMISSION * CG_SCENE_GLOW;
}
