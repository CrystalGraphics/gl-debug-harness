#version 330 core

in vec3 v_pos;
in vec3 v_worldPos;
in vec3 v_normal;
in vec2 v_uv;
in vec4 v_col;

uniform mat4 u_model;
uniform mat4 u_invModel;
uniform mat4 u_projection;
uniform mat4 u_view;
uniform vec3 u_lightPos;
uniform vec3 u_cameraPos;

// NEW: Time uniform for animation (pass this in seconds or ticks from your Java code)
uniform float u_time; 

out vec4 fragColor;

// --- 3D NOISE FUNCTION ---
// A compact pseudo-random hash and value noise function
float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.1, 0.1));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f); // Smoothstep interpolation
    
    return mix(mix(mix(hash(i + vec3(0,0,0)), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}
// -------------------------

void main() {
    // 1. Transform Camera to Local Space
    vec3 rayOrigin = (u_invModel * vec4(u_cameraPos, 1.0)).xyz;
    vec3 rayDir = normalize(v_pos - rayOrigin);

    // 2. Simplified Intersection
    float a = dot(rayDir, rayDir);
    float b = 2.0 * dot(rayDir, rayOrigin);
    float c = dot(rayOrigin, rayOrigin) - 0.25;

    float disc = b * b - 4.0 * a * c;
    if (disc < 0.0) discard;

    float t = (-b - sqrt(disc)) / (2.0 * a);
    if (t < 0.0) t = (-b + sqrt(disc)) / (2.0 * a);
    if (t < 0.0) discard;

    // 3. The Hit Point
    vec3 hitLocalPos = rayOrigin + rayDir * t;

    // 4. World Space Depth Reconstruction
    vec4 hitWorldPos = u_model * vec4(hitLocalPos, 1.0);
    vec4 clipPos = u_projection * (u_view * hitWorldPos);
    gl_FragDepth = (clipPos.z / clipPos.w + 1.0) * 0.5;

    // 5. VISUALS: Base Math
    vec3 normal = normalize(hitLocalPos);
    vec3 viewDir = normalize(u_cameraPos - hitWorldPos.xyz);
    
    // 6. VISUALS: The Noise Magic
    // Scale the coordinates so the noise isn't too large, and scroll it over time
    float noiseScale = 4.0;
    float timeSpeed = 1.0;
    vec3 noiseCoords = hitLocalPos * noiseScale + vec3(0.0, u_time * timeSpeed, 0.0);
    
    // Get a noise value between 0.0 and 1.0
    float n = noise(noiseCoords); 
    
    // Add a second layer of noise (fractal) for more detail
    n += noise(noiseCoords * 2.0 - vec3(u_time)) * 0.5; 
    
    // 7. VISUALS: The Glow (Fresnel)
    // Calculates how close the normal is to the edge of the sphere from our view
    float fresnel = max(0.0, 1.0 - dot(normal, viewDir));
    fresnel = pow(fresnel, 2.0); // Sharpen the edge glow

    // 8. VISUALS: Color Mixing
    vec3 darkEnergy = vec3(0.0, 0.1, 0.6); // Deep blue inside
    vec3 coreEnergy = vec3(0.1, 0.8, 1.0); // Bright cyan hot-spots
    vec3 edgeGlow   = vec3(0.8, 0.9, 1.0); // White/cyan rim
    
    // Mix the dark and core energy based on our noise pattern
    vec3 finalColor = mix(darkEnergy, coreEnergy, n);
    
    // Add the bright glowing rim to the edges
    finalColor += edgeGlow * fresnel * 1.5; 

    // We keep a tiny bit of actual scene lighting so it feels grounded, but mostly it glows.
    vec3 lightDir = normalize(u_lightPos - hitWorldPos.xyz);
    float lighting = max(dot(normal, lightDir), 0.1);
    
    vec3 reflectDir = reflect(-lightDir, normal);
    float specular = max(0,dot(viewDir,reflectDir));
    specular = pow(specular, 32) * 5;
    

    // Final output (set alpha to 0.9 if you have GL_BLEND enabled in your engine)
    fragColor = vec4(normal * (lighting + 0.2+ specular), 0.9);
} 