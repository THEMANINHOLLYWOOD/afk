/* MakeUp - tone_maps.glsl
Tonemap functions.

Javier Garduño - GNU Lesser General Public License v3.0
*/

// vec3 custom_sigmoid(vec3 color) {
//     color = 1.4 * color;
//     color = color / pow(pow(color, vec3(3.0)) + 1.0, vec3(0.3333333333333));

//     return pow(color, vec3(1.15));
// }

vec3 custom_sigmoid(vec3 color) {
    color = 1.4 * color;
    color = color / pow(pow(color, vec3(2.5)) + 1.0, vec3(0.4));

    return pow(color, vec3(1.15));
}

// AFK filmic (AFK shader pack). Below 0.6 the image passes through unchanged; above, an
// exponential shoulder rolls highlights off towards 1 on the brightest channel, so colours keep
// their hue instead of clipping to yellow or cyan, and the brightest highlights drift to white.
// A small lift in the darkest tones keeps detail in caves and at night, then a mild contrast curve.
vec3 afk_filmic(vec3 color) {
    color = max(color, vec3(0.0));
    float peak = max(max(color.r, color.g), color.b);
    const float knee = 0.6;
    float mapped = peak;
    if (peak > knee) {
        mapped = knee + (1.0 - knee) * (1.0 - exp(-(peak - knee) / (1.0 - knee)));
    }
    vec3 result = color * (mapped / max(peak, 0.0001));
    result = mix(result, vec3(mapped), smoothstep(1.5, 6.0, peak) * 0.6);
    result *= 1.0 + 0.35 * (1.0 - smoothstep(0.0, 0.25, result));
    return pow(clamp(result, 0.0, 1.0), vec3(1.08));
}

