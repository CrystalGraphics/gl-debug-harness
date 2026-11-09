#version 330 core

in vec2 v_uv;
in vec4 v_col;

out vec4 fragColor;

void main(){
    vec2 pos = v_uv *2 - 1;

    float dist = length(pos);
    float glow = pow(max(0,1-dist),4) * 3;

    float coreR = 0.15;
    float core = 1- smoothstep(coreR, coreR+0.02, dist);

    vec3 white = vec3(1.0, 1.0, 1.0);
    vec3 yellow = vec3(1.0, 0.8, 0.1);
    vec3 orange = vec3(1.0, 0.3, 0.0);
    
    vec3 color = mix(orange,yellow,glow);
    color = mix(color,white,core);

    fragColor = vec4(color, glow);
}