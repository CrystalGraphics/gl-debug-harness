// upscale-lab's floor: a checker in world space, 50 cm squares. custom0.x 1 box-filters it over each pixel's footprint
// (Inigo Quilez, filterable procedurals), as a mipmapped texture is; 0 leaves it unfiltered, squares below a texel
// aliasing near and far, the worst case for the upscale filters and TSR.
#domain surface
Material { ShadingModel Unlit  Fog Off }

struct v2f { vec3 world; };

void vertex(inout CgVertex v, out v2f o) {
    o.world = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec2 p = CG_ABSOLUTE_WORLD_POS(i.world).xz / 0.5;
    vec2 w = fwidth(p) + 1e-4;   // outside the branch: derivatives need every lane of the quad
    float on;
    if (CG_OBJECT_CUSTOM0.x > 0.5) {
        // The integral of the square wave over the footprint, on each axis; their product is the checker's
        vec2 k = 2.0 * (abs(fract((p - 0.5 * w) * 0.5) - 0.5) - abs(fract((p + 0.5 * w) * 0.5) - 0.5)) / w;
        on = 0.5 - 0.5 * k.x * k.y;
    } else {
        ivec2 cell = ivec2(floor(p));
        on = float((cell.x + cell.y) & 1);
    }
    s.emissive = mix(vec3(0.08), vec3(0.75, 0.72, 0.65), on);
}
