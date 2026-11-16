// upscale-lab's floor: a checker in world space, 50 cm squares, unfiltered: near, edges each upscale filter draws its
// own way; far, squares below a texel that alias, which the filter carries up.
#domain surface
Material { ShadingModel Unlit  Fog Off }

struct v2f { vec3 world; };

void vertex(inout CgVertex v, out v2f o) {
    o.world = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    ivec2 cell = ivec2(floor(CG_ABSOLUTE_WORLD_POS(i.world).xz / 0.5));
    float on = float((cell.x + cell.y) & 1);
    s.emissive = mix(vec3(0.08), vec3(0.75, 0.72, 0.65), on);
}
