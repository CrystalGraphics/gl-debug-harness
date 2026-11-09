#version 330 core

in vec2 v_uv;

out vec4 fragColor;


void main(){
    vec2 uv = v_uv *2. - 1.;// normalize uv between -1,1 at center 0,0 (unit circle)
    float dist = length(uv); //to scale, divide UV 
    float glow = max(0, 1.-dist);
    glow = pow(glow, 4.) *3;

    float centerRadius = 0.15;
    float core = 1. - smoothstep(centerRadius, centerRadius+0.1, dist);

    vec3 white = vec3(1.0, 1.0, 1.0);
    vec3 yellow = vec3(1.0, 0.8, 0.1);
    vec3 orange = vec3(1.0, 0.3, 0.0);

    vec3 sunColor = mix(orange, yellow, glow);
    sunColor = mix(sunColor, white, core);

    float alpha = min(1.0, core + glow);
    fragColor = vec4(sunColor, alpha);
}