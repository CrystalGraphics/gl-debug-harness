// A "Surface"-lit pane for lights-volume: lit by the world's lights at the normal its draw's custom1 states.
#domain surface
Material { BlendMode Translucent  TranslucencyLighting Surface }

struct v2f {
    vec3 n;
};

void vertex(inout CgVertex v, out v2f o) {
    o.n = CG_OBJECT_CUSTOM1.xyz;
}

void surface(in v2f i, inout CgSurface s) {
    s.normal = normalize(i.n);
    s.baseColor = CG_OBJECT_CUSTOM0.rgb;
    s.opacity = CG_OBJECT_CUSTOM0.a;
}
