// distortion's backdrop (CgDistortionTestScene): opaque, writing depth, each pixel's own coordinates as its colour
// (x, y, x) / 255, so a pixel the apply bent says where it sampled. WALL is a flat blue instead.
#domain surface
Material { ShadingModel Unlit  Faces Both  Fog Off }

Keywords {
    WALL ("WALL")
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
#ifdef WALL
    s.emissive = vec3(0.0, 0.0, 1.0);
#else
    vec2 p = floor(gl_FragCoord.xy);
    s.emissive = vec3(p.x / 255.0, p.y / 255.0, p.x / 255.0);
#endif
}
