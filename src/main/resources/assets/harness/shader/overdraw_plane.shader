// overdraw-count's plane (CgOverdrawTestScene): transparent, depth-tested, back faces culled. CUT discards its bottom
// half, which the overdraw view must not count.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Fog Off }

Keywords {
    CUT ("CUT")
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
#ifdef CUT
    if (gl_FragCoord.y < CG_RESOLUTION.y * 0.5) discard;
#endif
    vec4 color = vec4(0.2, 0.5, 1.0, 0.2);
    s.baseColor = color.rgb;
    s.opacity = color.a;
}
