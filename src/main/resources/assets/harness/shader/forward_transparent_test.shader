#domain surface
Material { BlendMode Translucent }

Properties {
    _Color ("Color", vec4) = (1.0, 1.0, 1.0, 0.5)
}

struct v2f {
    vec3 worldPos;
};

void vertex(inout CgVertex v, out v2f o) {
    o.worldPos = v.world;
}

void surface(in v2f i, inout CgSurface s) {
    // Material color modulated by per-instance custom0 tint; _Color.a carries the base transparency (default 0.5).
    vec4 c = _Color * CG_OBJECT_CUSTOM0;
    s.baseColor = c.rgb;
    s.opacity = c.a;
}
