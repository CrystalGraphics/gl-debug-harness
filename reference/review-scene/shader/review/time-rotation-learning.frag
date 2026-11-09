#version 330 core

in vec3 v_pos;
in vec4 v_col;

uniform float time;

out vec4 fragColor;

float hash12(vec2 p){
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 435785);
}

//vec2 hash22(vec2 p) {
//    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
//    p3 += dot(p3, p3.yzx + 33.33);
//    return fract((p3.xx + p3.yz) * p3.zy);
//}

// A simple hash that returns a direction (gradient vector)
vec2 hash22(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return -1.0 + 2.0 * fract(sin(p) * 43758.5453123);
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


float pnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);

    // Quintic interpolation curve (smoother than smoothstep)
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);

    // Get gradients for 4 corners and calculate dot products
    float a = dot(hash22(i + vec2(0, 0)), f - vec2(0, 0));
    float b = dot(hash22(i + vec2(1, 0)), f - vec2(1, 0));
    float c = dot(hash22(i + vec2(0, 1)), f - vec2(0, 1));
    float d = dot(hash22(i + vec2(1, 1)), f - vec2(1, 1));

    // Blend the results
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Simplified 2D Simplex Noise logic
float simplex_noise(vec2 p) {
    const float K1 = 0.366025404;// (sqrt(3)-1)/2
    const float K2 = 0.211324865;// (3-sqrt(3))/6

    // 1. Skew the input space to find which "triangle" we are in
    vec2 i = floor(p + (p.x + p.y) * K1);
    vec2 a = p - i + (i.x + i.y) * K2;

    // 2. Determine which of the two triangles in the cell we are in
    vec2 o = (a.x > a.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    vec2 b = a - o + K2;
    vec2 c = a - 1.0 + 2.0 * K2;

    // 3. Calculate the contribution from the three corners
    // We use a "kernel" (power of 4) to ensure the edges are smooth
    vec3 h = max(0.5 - vec3(dot(a, a), dot(b, b), dot(c, c)), 0.0);
    vec3 n = h * h * h * h * vec3(dot(a, hash22(i)),
    dot(b, hash22(i + o)),
    dot(c, hash22(i + 1.0)));

    // 4. Sum them up and scale the result to [-1, 1]
    return dot(n, vec3(70.0));
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);// which cell are we in?
    vec2 f = fract(p);// where inside the cell are we (0..1)?

    // Random heights at the four corners of the cell.
    float a = hash12(i + vec2(0.0, 0.0));
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));

    // Smoothstep ease curve: f -> 3f^2 - 2f^3.
    // Linear blending makes the result look like quilted squares;
    // the smooth curve hides cell boundaries.
    vec2 u = f*  f * (3.0 - 2.0 * f);

    // Bilinear interpolation between the four corners.
    return mix(mix(a, b, u.x),
    mix(c, d, u.x),
    u.y);
}
mat2 rot(float a){
    float s = sin(a), c = cos(a);
    return mat2(c, -s, s, c);
}

float fbm3d(vec3 p){
    float sum = 0;
    float gain =0.5;
    float languarity =3.0;
    float amp = 1;
    float freq = 1;

    for (int i = 0; i <4; i++){
        float n = pnoise(p+0) * 1;
        n =1-abs(n);
        sum+= amp * pow(n, 40);
        p =p*freq +vec3(12.1, 10.22, 62);

        amp*=gain;
        freq*=languarity;
    }
    return sum;

}


float fbm(vec2 p){
    float sum = 0;
    float gain =0.5;
    float languarity =3.0;
    float amp = 1;
    float freq = 1;

    for (int i = 0; i <4; i++){
        float n = pnoise(vec3(p, 0))* 1;
        n =1-abs(n);
        sum+= amp * pow(n, 40);
        p =rot(1)*p*freq;

        amp*=gain;
        freq*=languarity;
    }
    return sum;

}


void main(){
    float t = time;
    vec2 uv = v_pos.xy;
    vec2 p = uv;

    // Axis
    vec2 axisC = vec2(0.);
    float thick = 0.01;
    float xsmooth = smoothstep(axisC.x-thick, axisC.x+thick, uv.x);
    float xmask = xsmooth * (1-xsmooth);

    float ysmooth = smoothstep(axisC.y-thick, axisC.y+thick, uv.y);
    float ymask = ysmooth * (1-ysmooth);

    float axismask = (xmask + ymask)*3;

    float centerMask = ymask*xmask *20;
    vec3 centerCol = vec3(0, 0, 1);
    vec3 axisCol = vec3(1, 0, 0) * xmask+vec3(0, 1, 0) * ymask;
    vec3 col = mix(axisCol, centerCol, centerMask);


    //Noise
    float dist = length(uv)*2;
    float radialMask = 1-smoothstep(0.95, 1, dist);
    float pi = 3.1412;

    float r = dist;
    float a = atan(uv.y, uv.x);

    float falloffR = 14;
    float falloff = exp(-r*r/(falloffR*falloffR));
    float angle = falloff*4*r + 1 +0;
    uv*=rot(angle);
    float s = 5;
    float noise = fbm3d(vec3(uv*5+0, t*0.1));

    //    float noise = fbm(vec3(r*5,a*1 + t*0.2,t*0.1));

    float mask = (max(0, noise)+0 + 0 + 0) * 2 * radialMask;

    col =mix(col, vec3(1), mask);

    fragColor = vec4(col, 1);
    //    fragColor = vec4(vec3(step(0.5,fract(uv.y*8))), 1);
}