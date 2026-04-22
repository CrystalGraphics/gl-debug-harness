#version 130
in vec2 v_uv;
in vec4 v_color;

uniform sampler2DArray u_frames;
uniform int u_frameCount;
uniform int u_frameIdx;
uniform float u_normalized;
uniform int u_lerp;
out vec4 fragColor;



void main(){
    vec4 colA = texture(u_frames, vec3(v_uv, float(u_frameIdx)));
    vec4 colB = texture(u_frames, vec3(v_uv, float((u_frameIdx+1) % u_frameCount)));

    float t = smoothstep(0.0, 1.0, u_normalized);// ← eased, not raw
    fragColor = vec4(mix(colA, colB, u_lerp == 1? t : 0));
}

