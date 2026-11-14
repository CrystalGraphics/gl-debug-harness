// bloom-occlusion's cover under the HDR scene: a transparent pane that glows nothing, in front of a glowing ball. At
// _Alpha 1 the ball must leave no trace, glow and bloom included; below it the ball shows dimmed.
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit  Fog Off }

Properties {
    _Alpha ("Coverage", float) = 1.0
}

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 color = vec4(0.2, 0.3, 0.4, _Alpha);
    s.emissive = color.rgb;
    s.opacity = color.a;
}
