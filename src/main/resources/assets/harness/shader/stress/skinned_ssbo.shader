#type spatial

Pass {
    Tags { "LightMode" = "Forward" "Name" = "Skinned" }

    struct v2f {
        vec4 color;
        vec3 worldPos;
    };

    void vertex(out v2f o) {
        SkinData skin = SKIN_DATA(CG_INSTANCE_ID);
        // Apply bind pose transform on top of model matrix
        vec4 skinnedPos = skin.bindPose * vec4(cg_Position, 1.0);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD * skinnedPos;
        o.worldPos = (CG_OBJECT_TO_WORLD * skinnedPos).xyz;
        // Tint by the bone weight x component
        float w = skin.weights.x;
        o.color = vec4(w, 1.0 - w, 0.5, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = i.color;
    }
}
