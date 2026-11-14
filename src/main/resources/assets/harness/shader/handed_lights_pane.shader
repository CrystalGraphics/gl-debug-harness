// A pane for lights-volume showing the light nearest its draw as handed to a program carrying its own lighting:
// red 1 where one is found, green its distance over 16 blocks, blue its radius over 32.
#domain surface
Material { BlendMode AlphaComposite  ShadingModel Unlit  Fog Off  Media Off }

struct v2f {
    vec3 shown;
};

void vertex(inout CgVertex v, out v2f o) {
    vec3 to = CG_DRAW_POINT_LIGHT_POSITION(0) - CG_OBJECT_TO_WORLD[3].xyz;
    vec4 params = CG_DRAW_POINT_LIGHT_PARAMS(0);
    o.shown = vec3(params.x, params.x * clamp(length(to) / 16.0, 0.0, 1.0), params.z / 32.0);
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = i.shown;
    s.opacity = 1.0;
}
