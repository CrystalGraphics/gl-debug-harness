#type spatial

Pass {
    Tags { "LightMode" = "Forward" "Name" = "MultiCombined" }

    struct v2f {
        vec4 albedo;
        vec4 emissive;
        vec3 worldPos;
    };

    void vertex(out v2f o) {
        InstanceProps inst = INST_PROPS(CG_INSTANCE_ID);
        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
        vec4 worldPos4 = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = worldPos4.xyz;
        o.albedo   = inst.albedo;
        o.emissive = inst.emissive;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // SceneParams UBO fields (ambientColor, exposure) are in direct scope
        vec3 lit = i.albedo.rgb * ambientColor.rgb * exposure;
        vec3 emit = i.emissive.rgb;
        fragColor = vec4(lit + emit, i.albedo.a);
    }
}
