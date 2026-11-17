// The harness world's ground: a white block's top as Minecraft draws it, WorldLit at albedo 1, so the lightmap at the
// draw's light is its colour, the world lights' apply divides back to white, and the screen's ambient occlusion
// darkens its sky light as it does every WorldLit surface's. Opaque, writing depth.
#domain surface
Material { Faces Both  Fog Off }

struct v2f { vec2 uv0; };

void surface(in v2f i, inout CgSurface s) {
    s.baseColor = vec3(1.0);
}
