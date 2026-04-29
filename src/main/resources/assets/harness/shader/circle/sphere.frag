#version 330 core

in vec3 v_pos;
in vec4 v_col;

uniform vec4 u_localCameraPos;

uniform mat4 u_model;
uniform mat4 u_view;
uniform mat4 u_projection;

uniform vec3 u_lightPos;
uniform vec3 u_cameraPos;

out vec4 fragColor;

void main(){
    vec3 O =  u_localCameraPos.xyz;// ray origin, camera local to cube
    vec3 D = normalize(v_pos - O);// ray direction

    vec3 C = vec3(0);// sphere center
    float r = 0.5;

    vec3 L = O - C;

    // Ray equation:     P(t) = O + t*D;
    // Sphere equation:  x² + y² + z² = r² (if center is origin, else subtract center components)

    // Point on sphere:  |P - C|² = r²
    // Substitute point: |t*D + O - C|² = r²
    // Let L = O - C:    |t*D + L|² = r²

    // Sphere ray intersection equation:
    // (D·D)t² + 2(D·L)t + L·L - r² = 0

    float a = dot(D, D);
    float b = 2. * dot(D, L);
    float c = dot(L, L) - r*r;

    // quadratic formula discriminant.
    // if < 0? no roots. = 0? one root. >0? two roots
    float disc = b*b - 4.*a*c;

    if (disc < 0) discard;// no sphere ray intersection 

    float t = (-b - sqrt(disc)) / 2.*a;//tNear
    if (t <= 0) t = (-b + sqrt(disc)) / 2.*a;//tFar (for if camera inside sphere)
    if (t <= 0) discard;//behind origin

    vec3 hitLocal = O + t*D;// pos of point on sphere and ray in local space
    vec4 hitWorld = u_model * vec4(hitLocal, 1.);

    // New depth of hit point
    vec4 hitClip = u_projection * u_view * hitWorld;
    float depthNDC = hitClip.z / hitClip.w;// clip -> NDC
    gl_FragDepth = (depthNDC + 1) / 2.;// [-1,1] -> [0,1]

    //Lighting
    vec3 normal = normalize(hitLocal);
    vec3 lightDir = normalize(u_lightPos - hitWorld.xyz);
    float diffuse = max(dot(lightDir, normal), 0.15);

    //Specular
    vec3 viewDir = normalize(O - hitLocal);
    vec3 halfDir = normalize(lightDir + viewDir);
    float specular = max(dot(halfDir, normal), 0);
    specular = pow(specular, 3);

    //Fresnel
    float fresnel =  pow(1 - max(dot(viewDir, normal), 0), 3) * 2;
    fresnel = smoothstep(0.03, 0.98, fresnel); 
    vec3 fresCol = vec3(1f, 0.57f, 0.64f) * fresnel;
    
    vec3 color = (specular + diffuse) * vec3(1);
    color = mix(color, fresCol, fresnel);

    float smoothAlpha = smoothstep(0, 0.02, disc);
    
    fragColor = vec4(color, smoothAlpha);
}