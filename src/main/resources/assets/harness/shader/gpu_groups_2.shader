#type none

// gpu-groups' middle group (CgGpuGroupsTestScene): gpu_groups_1's body at twice its loop.

Queue = "Geometry"

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ONE
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    struct v2f {
        vec2 uv;
    };

    void vertex(out v2f o) {
        o.uv = CG_VERTEX_CORNER;
        gl_Position = vec4(CG_VERTEX_CORNER * 2.0 - 1.0, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        const int ITERATIONS = 128;
        vec2 p = i.uv + float(CG_INSTANCE_ID) * 0.001;
        for (int k = 0; k < ITERATIONS; k++) p = fract(vec2(sin(dot(p, vec2(12.9898, 78.233))), cos(p.x * 3.1 + p.y)) * 43.17);
        fragColor = vec4(p * 1e-4, 0.0, 0.0);
    }
}
