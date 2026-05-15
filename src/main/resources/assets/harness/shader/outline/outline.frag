#version 330 core

in vec2 v_uv;
in vec4 v_col;

uniform sampler2D distortion_map;
uniform sampler2D noise_map;
uniform sampler2D alpha_map;
uniform sampler2D flame_map;

uniform float time;

out vec4 fragColor;

void main(){
    // 1. ANIMATION: Scroll the noise layers at different speeds
    // This creates the "boiling" effect seen in the aura
    vec2 scroll1 = vec2(time * 0.1, time * 0.15);
    vec2 scroll2 = vec2(time * -0.05, time * 0.2);

    // 2. MACRO DISTORTION (t2)
    // We sample t2 and remap it to [-1, 1]
    vec2 d1 = texture(distortion_map, v_uv + 0).rg * 2.0 - 1.0;

    // 3. MICRO DISTORTION / DOMAIN WARP (t3)
    // We use the distortion from d1 to sample the next noise layer (t3)
    // This makes the noise "push" other noise around chaotically
    float macroStrength = 0.5;
    vec2 warpedUV1 = v_uv + (d1 * macroStrength);
    vec2 d2 = texture(noise_map, warpedUV1 + scroll2).rg * 2.0 - 1.0;

    // 4. SAMPLE FLAME DETAIL (t5)
    // Combine both distortions to look up the actual flame texture
    float microStrength = 0.2;
    vec2 finalUV = v_uv + (d1 * macroStrength) + (d2.r * microStrength);
    vec4 flameDetail = texture(flame_map, finalUV);

    // 5. MASKING & SHAPING (t4)
    // Sample the radial mask to give it that "orb" shape
    // Notice we distort the mask slightly too, so the edges wobble!
    vec4 mask = texture(alpha_map, v_uv + (d1 * 0.01));

    // 6. COLORING
    // Combine everything. We use the red channel of the flame detail 
    // to drive a fiery color gradient.
    vec3 coreColor = vec3(1f, 0.13f, 0f);// Bright Orange/Red
    vec3 glowColor = vec3(1.0, 0.8, 0.2);// Yellow highlight

    vec3 finalRGB = mix(coreColor, glowColor, flameDetail.r);
    // Instead of just sampling...
    float rawNoise = texture(flame_map, finalUV).r;

    // Apply the "Game Math" (Line 28)
    float sharpNoise = clamp(rawNoise * 20.0 - 9.5, 0.0, 1.0);

    // Now your fire will have those cool "holes" and sharp licks!
    vec3 finalColor = mix(vec3(0.1, 0, 0), vec3(1, 0.8, 0), sharpNoise);
    // Apply the mask and vertex alpha
    float finalAlpha = flameDetail.r * mask.r * v_col.a;

    // Output with premultiplied alpha (common for glowing effects)
    fragColor = vec4(vec2(d1),0, 1);
}