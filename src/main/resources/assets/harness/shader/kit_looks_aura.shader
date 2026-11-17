// kit-looks' aura: an additive glow, grey so a look's tint shows in it, soft to its quad's edge as a kit's aura is.
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  Fog Off  ColorSpace Linear  Faces Both }

struct v2f { vec2 uv; };

void vertex(inout CgVertex v, out v2f o) {
    o.uv = cg_TexCoord0;
}

void surface(in v2f i, inout CgSurface s) {
    s.emissive = vec3(0.35) * (1.0 - smoothstep(0.1, 0.5, length(i.uv - 0.5)));
}
