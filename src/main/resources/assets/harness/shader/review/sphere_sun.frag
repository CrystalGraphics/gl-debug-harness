#version 330 core

in vec3 v_pos;
in vec4 v_col;

uniform vec4 u_localCameraPos;

uniform mat4 u_model;
uniform mat4 u_view;
uniform mat4 u_projection;

uniform vec3 u_lightPos;
uniform float u_time;

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
float fbm3(vec3 p){
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
    vec3 O = u_localCameraPos.xyz;
    vec3 D = normalize(v_pos - O);

    float r = 0.5;
    vec3 C = vec3(0);

    vec3 L = O - C;

    // Ray equation:     P(t) = O + t*D
    // Sphere equation:  x^2 + y^2 + z^2 = r^2

    // Point on sphere:  |P - C|^2 = r^2
    // Subtitute point:  |t*D + O - C|^2 = r^2
    // Let L = O - C:    |t*D + L|^2 = r^2
    // Expand: (D.D)t^2 + 2(D.L)t + L.L = r^2

    // Ray sphere interesection equation
    // (D.D)t^2 + 2(D.L)t + L.L - r^2 = 0

    float a = dot(D, D);
    float b = 2 * dot(D, L);
    float c = dot(L, L) - r*r;

    float d = b*b - 4*a*c;
    if (d<=0)discard;

    float sqrtd = sqrt(d);
    float t = (-b-sqrtd)/2.*a;
    if (t<=0) t = (-b+sqrtd)/2.*a;
    if (t<=0)discard;

    vec3 hitLocal = O + t*D;
    vec4 hitWorld = u_model * vec4(hitLocal, 1.);

    // New depth
    vec4 hitClip = u_projection * u_view * hitWorld;
    float depthNDC = hitClip.z/hitClip.w;
    // gl_FragDepth = (depthNDC + 1.) / 2.;

    // Lighting
    vec3 normal = normalize(hitLocal);
    vec3 lightDir = normalize(u_lightPos - hitWorld.xyz);
    float diffuse = max(dot(lightDir, normal), 0.15);

    // Specular 
    vec3 cameraDir = normalize(O - hitLocal);
    vec3 halfway = normalize(lightDir+cameraDir);
    float specular = pow(max(dot(halfway, normal), 0), 64);

    //Fresnel
    float fresnel = pow(1 - max(dot(cameraDir, normal), 0), 4) * 1;

    float light = diffuse + specular + fresnel;
    vec3 color = pow(light*vec3(1), vec3(1./2.2));
    float alpha = smoothstep(0, 0.01, d);

    ////////////////////////////////////////////
    ////////////////////////////////////////////
    float ti = u_time;
    vec3 N = normal;
    // Sample 3D noise on the unit sphere -- granulation cells
    float plasma = 0.25+0.25*ridged(N * 25.0 + vec3(0.0, 0.0, ti * 0.5)) * 1;
     plasma = mix(plasma, ridged(N * 1.0 + vec3(0,0,ti)), 0.4);

    vec3 hot  = vec3(0);
    vec3 warm = vec3(1.0, 0.55, 0.15);
    
    float ndv = max(dot(N, -D), 0.0);// 1 at center, 0 at limb

    // Limb darkening: subtle dimming near edge
    float limb = mix(0.7, 1.0, ndv);
    vec3 centerCol = pow(1-ndv,1.0) * vec3(0.53f, 0.07f, 0.04f)*1;
    // Chromosphere: a thin red-orange band right at the silhouette
    float chromo = pow(1.0 - ndv, 3.0)*2;// sharp peak only at very edge

    vec3 col = mix(hot, warm, plasma) * limb;
    col += vec3(1.0, 0.4, 0.2) * chromo * 1.5;
    col+=centerCol;
    fragColor = vec4(col, alpha);
    //fragColor = vec4(color, alpha);
}