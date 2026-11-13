// A lens over the world: what cg_SceneColor holds behind it, rippled and inverted.
#domain surface
Material { BlendMode AlphaComposite  Faces Both  SceneColorMargin 0.03 }

struct v2f {
    vec3 worldPos;
};

void vertex(inout CgVertex v, out v2f o) {
    o.worldPos = v.world;
}

void surface(in v2f i, inout CgSurface s) {
    vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
    uv.x += 0.02 * sin(uv.y * 60.0);
    s.baseColor = 1.0 - CG_SCENE_COLOR(uv).rgb;
}
