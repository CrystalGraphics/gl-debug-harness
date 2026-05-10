#type spatial

Queue = "Transparent"
RenderState {
    Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
    DepthTest LEQUAL
    DepthWrite OFF
    Cull FRONT
}

Properties {
    _OutlineColor ("Outline Color", color) = (1.0, 0.5, 0.0, 1)
    _OutlineWidth ("Outline Width", float) = 0.05
}

struct v2f {
    vec4 color;
};

void vertex(out v2f o) {
    vec3 extruded = cg_Position + cg_Normal * _OutlineWidth;
    gl_Position = CG_MATRIX_MVP * vec4(extruded, 1.0);
    o.color = _OutlineColor;
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = i.color;
}
