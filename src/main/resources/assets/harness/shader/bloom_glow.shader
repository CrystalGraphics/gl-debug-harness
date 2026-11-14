// bloom-occlusion's glowing ball: an opaque surface that writes depth and glows. The glow must survive the depth its
// own surface wrote, and nothing of it may bloom from behind the wall.
#domain surface
Material { ShadingModel Unlit  ColorSpace Linear  Fog Off }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 glowLight = vec3(0.0);
    s.emissive = vec3(1.0, 0.7, 0.3);
    glowLight = vec3(6.0, 4.0, 1.5);
    // The glow it drew beside its colour is light it gives off: an unlit surface draws one colour
    s.emissive = cg_SceneDecode(s.emissive);
    s.emissive += glowLight * CG_EMISSION * CG_SCENE_GLOW;
}
