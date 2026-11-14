// distortion's quads (CgDistortionTestScene): transparent, drawing _Color, and bending what is behind by _Offset: xy in
// pixels, z the chromatic split.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Fog Off }

Properties {
    _Color  ("Colour", vec4) = (0, 0, 0, 0)
    _Offset ("Offset in pixels, split", vec4) = (0, 0, 0, 0)
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = _Color.rgb;
    s.opacity = _Color.a;
    s.refraction = _Offset.xy / CG_RESOLUTION;
    s.refractionSplit = _Offset.z;
}
