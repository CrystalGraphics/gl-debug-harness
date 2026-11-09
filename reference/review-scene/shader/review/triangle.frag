#version 330 core

in vec3 v_pos;
in vec4 v_col;

uniform float time;
uniform vec3 u_cameraPos;

out vec4 fragColor;

mat2 rot2(float a){
    float s = sin(a), c = cos(a);
    return mat2(c, -s, s, c);
}

vec3 hash33(vec3 p) {
    p = vec3(dot(p, vec3(127.1, 311.7, 74.7)),
    dot(p, vec3(269.5, 183.3, 246.1)),
    dot(p, vec3(113.5, 271.9, 124.6)));

    // Scale and shift into -1.0 to 1.0 range, then normalize
    return -1.0 + 2.0 * fract(sin(p) * 43758.5453123);
}

float pnoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);

    // Quintic interpolation curve
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);

    // Calculate dot products for the 8 corners of the cube
    float g000 = dot(hash33(i + vec3(0, 0, 0)), f - vec3(0, 0, 0));
    float g100 = dot(hash33(i + vec3(1, 0, 0)), f - vec3(1, 0, 0));
    float g010 = dot(hash33(i + vec3(0, 1, 0)), f - vec3(0, 1, 0));
    float g110 = dot(hash33(i + vec3(1, 1, 0)), f - vec3(1, 1, 0));
    float g001 = dot(hash33(i + vec3(0, 0, 1)), f - vec3(0, 0, 1));
    float g101 = dot(hash33(i + vec3(1, 0, 1)), f - vec3(1, 0, 1));
    float g011 = dot(hash33(i + vec3(0, 1, 1)), f - vec3(0, 1, 1));
    float g111 = dot(hash33(i + vec3(1, 1, 1)), f - vec3(1, 1, 1));

    // Interpolate along X
    float lx0 = mix(g000, g100, u.x);
    float lx1 = mix(g010, g110, u.x);
    float lx2 = mix(g001, g101, u.x);
    float lx3 = mix(g011, g111, u.x);

    // Interpolate along Y
    float ly0 = mix(lx0, lx1, u.y);
    float ly1 = mix(lx2, lx3, u.y);

    // Interpolate along Z (Time)
    return mix(ly0, ly1, u.z);
}

float turbulence(vec3 p){
    float sum = 0;
    float lacunarity = 2, freq = 1;
    float gain = 0.5, amp = 1;
    mat2 R = rot2(0.5);

    for (int i = 0; i<4;i++){
        float n =abs(pnoise(p));
        sum+= amp * n;
        p= p*freq + vec3(10.2, 42.3, 20.1);

        amp*=gain;
        freq*=lacunarity;
    }

    return sum;
}
float fbmRotated(vec3 p){
    float sum = 0;
    float lacunarity = 2, freq = 1;
    float gain = 0.5, amp = 1;
    mat2 R = rot2(0.5);

    for (int i = 0; i<4;i++){
        float n =1-abs(pnoise(p));
        sum+= amp * pow(n, 4);
        p=  p*freq;

        amp*=gain;
        freq*=lacunarity;
    }

    return sum;
}
float ridged(vec3 p){
    float sum = 0;
    float lacunarity = 2, freq = 1;
    float gain = 0.5, amp = 1;
    mat2 R = rot2(0.5);

    for (int i = 0; i<4;i++){
        float n =1-abs(pnoise(p));
        sum+= amp * pow(n, 4);
        p= p*freq + vec3(10.2, 42.3, 20.1);

        amp*=gain;
        freq*=lacunarity;
    }

    return sum;
}

void main(){
    vec2 uv = v_pos.xy;
    float t = time;
    float r = length(uv) *2;
    float a = atan(uv.y, uv.x);


    //Core
    vec2 surf = vec2(uv*15.5) + vec2(-t*0.1);
    float turb = turbulence(vec3(surf, t*0.3));
    float core = smoothstep(0.27, 0.23, r);
    core = core* (0.5+0.5*turb);
    vec3 coreCol = vec3(1., 0.95, 0.8)*core;

    //Halo
    //float ridge = ridge(vec3(surf, t*0.3));
    float halo = pow(clamp(1-r*0.8, 0, 1), 2);
    vec3 haloCol = vec3(1.0, 0.5, 0.15)*halo*0.7;

    //Angular rays
    vec2 noiseUV = vec2(cos(a), sin(a));
    float ray =abs(pnoise(vec3(noiseUV*3, t*.2)))*1;
    ray = pow(ray, 3);

    float rayDonut = smoothstep(0.5, 0.22, r)*smoothstep(0.22, 0.27, r)*4;
    ray = ray * rayDonut;
    vec3 rayCol = vec3(1.0, 0.6, 0.2) * ray;
    
    vec3 col = coreCol + haloCol +rayCol ;
//    fragColor = vec4(vec3(col), 1);
    fragColor = v_col;
}