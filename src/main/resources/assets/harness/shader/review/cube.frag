#version 330 core

in vec3 v_pos;
in vec4 v_col;

uniform vec4 u_localCameraPos;

uniform mat4 u_model;
uniform mat4 u_view;
uniform mat4 u_projection;

uniform vec3 u_lightPos;

out vec4 fragColor;

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
    float specular = pow(max(dot(halfway,normal),0),64);
    
    //Fresnel
    float fresnel = pow(1 - max(dot(cameraDir,normal),0),4) * 1;
    
    float light = diffuse + specular + fresnel;
    vec3 color = pow(light*vec3(1), vec3(1./2.2));
    float alpha = smoothstep(0,0.01,d);
    
    fragColor = vec4(color,alpha);
}