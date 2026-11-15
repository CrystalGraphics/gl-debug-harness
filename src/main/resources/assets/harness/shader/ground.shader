// The harness world's ground: a white block's top as Minecraft draws it, albedo 1 times the lightmap at the draw's
// light, so the world lights' apply divides back to white. Opaque, writing depth.
#domain surface
Material { ShadingModel Unlit  Faces Both  Fog Off }

struct v2f { vec2 light; };

void surface(in v2f i, inout CgSurface s) {
    s.emissive = CG_LIGHTMAP(i.light);
}
