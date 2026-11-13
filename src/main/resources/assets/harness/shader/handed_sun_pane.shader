// A pane for lights-sun showing the sun's visibility it is handed at each fragment: white lit, black shadowed.
#domain surface
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  Fog Off  Media Off }

struct v2f {
    vec3 n;
};

void vertex(inout CgVertex v, out v2f o) {
    o.n = cg_Normal;
}

void surface(in v2f i, inout CgSurface s) {
    s.baseColor = vec3(CG_DIRECTIONAL_SHADOW(cg_FragmentPosition()));
    s.opacity = 1.0;
}
