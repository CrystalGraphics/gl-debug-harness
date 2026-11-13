// post-effects' mod effects (CgPostEffectsTestScene): a solid rectangle over the target, _Rect (x0, y0, x1, y1) in
// 0..1 from the bottom left, in _Color.
#domain global
Material { ColorSpace sRGB  Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Rect  ("x0, y0, x1, y1", vec4) = (0, 0, 1, 1)
    _Color ("Its colour", color) = (1, 1, 1, 1)
}

struct v2f {
    vec2 unused;
};

void vertex(out v2f o) {
    vec2 p = mix(_Rect.xy, _Rect.zw, CG_VERTEX_CORNER);
    o.unused = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = _Color;
}
