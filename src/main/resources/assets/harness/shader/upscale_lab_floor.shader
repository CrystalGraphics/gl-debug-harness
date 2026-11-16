// upscale-lab's floor: a fine checker in world space, 5 cm squares, unfiltered, so the far floor aliases and each upscale
// filter shows how it carries the aliasing up.
#domain surface
Material { ShadingModel Unlit  Fog Off }

struct v2f { vec3 world; };

void vertex(inout CgVertex v, out v2f o) {
    o.world = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    ivec2 cell = ivec2(floor(CG_ABSOLUTE_WORLD_POS(i.world).xz / 0.05));
    float on = float((cell.x + cell.y) & 1);
    s.emissive = mix(vec3(0.08), vec3(0.75, 0.72, 0.65), on);
}
