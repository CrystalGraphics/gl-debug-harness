// motion-blur's card: drawn after motion blur, so a camera pan never smears it.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Fog Off  ColorSpace Linear  Faces Both  TranslucencyPass AfterMotionBlur }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = vec3(1.0, 0.85, 0.1);
    s.opacity = 1.0;
}
