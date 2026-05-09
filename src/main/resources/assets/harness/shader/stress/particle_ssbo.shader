#type spatial

struct v2f {
    vec4 color;
    float age;
};

void vertex(out v2f o) {
    // PARTICLE(n) is injected by the attached buffer
    ParticleData p = PARTICLE(CG_INSTANCE_ID);
    vec4 worldPos = vec4(cg_Position + p.worldOffset.xyz, 1.0);
    gl_Position = cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD * worldPos;
    o.color = p.color;
    o.age = p.age;
}

void fragment(in v2f i, out vec4 fragColor) {
    float fade = clamp(1.0 - i.age, 0.0, 1.0);
    fragColor = vec4(i.color.rgb * fade, i.color.a * fade);
}
