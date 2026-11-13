// post-capture's live view (CgPostCaptureScene): one of its pictures in _Rect of the window, (x0, y0, x1, y1) in 0..1
// from the bottom left, its codes written as they are.
#domain global
Material { ColorSpace sRGB  Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Image ("The picture shown", sampler2D) = "black"
    _Rect  ("x0, y0, x1, y1", vec4) = (0, 0, 1, 1)
}

struct v2f {
    vec2 uv;
};

void vertex(out v2f o) {
    o.uv = CG_VERTEX_CORNER;
    gl_Position = vec4(mix(_Rect.xy, _Rect.zw, CG_VERTEX_CORNER) * 2.0 - 1.0, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = vec4(texture(_Image, i.uv).rgb, 1.0);
}
