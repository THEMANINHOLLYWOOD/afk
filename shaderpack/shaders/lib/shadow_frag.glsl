/* MakeUp - shadow_frag.glsl
Fragment shadow function.

Modified for the AFK shader pack: soft shadows take one or more rings of MakeUp's four
rotated taps (AFK_PCF_RINGS, set by the tier and frame time) for smoother penumbras.

Javier Garduño - GNU Lesser General Public License v3.0
*/

float afk_shadow_ring(vec3 the_shadow_pos, float dither) {
    float shadow_sample = 1.0;

    #if SHADOW_TYPE == 0  // Pixelated
        shadow_sample = shadow2D(shadowtex1, the_shadow_pos).r;
    #elif SHADOW_TYPE == 1  // Soft
        float current_radius = dither;
        dither *= 6.283185307179586;
        float dither_2 = dither + 1.5707963267948966;

        shadow_sample = 0.0;

        vec2 offset = (vec2(cos(dither), sin(dither)) * current_radius * SHADOW_BLUR) / shadowMapResolution;
        vec2 offset2 = (vec2(cos(dither_2), sin(dither_2)) * (1.0 - current_radius) * SHADOW_BLUR) / shadowMapResolution;

        float z_bias = dither * 0.00002;

        shadow_sample += shadow2D(shadowtex1, vec3(the_shadow_pos.xy + offset, the_shadow_pos.z - z_bias)).r;
        shadow_sample += shadow2D(shadowtex1, vec3(the_shadow_pos.xy - offset, the_shadow_pos.z - z_bias)).r;
        shadow_sample += shadow2D(shadowtex1, vec3(the_shadow_pos.xy + offset2, the_shadow_pos.z - z_bias)).r;
        shadow_sample += shadow2D(shadowtex1, vec3(the_shadow_pos.xy - offset2, the_shadow_pos.z - z_bias)).r;

        // shadow_sample *= 0.5;
        shadow_sample *= 0.25;
    #endif

    return shadow_sample;
}

#if defined COLORED_SHADOW

    vec3 afk_colored_shadow_ring(vec3 the_shadow_pos, float dither) {
        #if SHADOW_TYPE == 0  // Pixelated
            float shadow_detector = 1.0;
            float shadow_black = 1.0;
            vec4 shadowColor = vec4(1.0);

            float alpha_complement;

            shadow_detector = shadow2D(shadowtex0, vec3(the_shadow_pos.xy, the_shadow_pos.z)).r;
            if (shadow_detector < 1.0) {
                shadow_black = shadow2D(shadowtex1, vec3(the_shadow_pos.xy, the_shadow_pos.z)).r;
                if (shadow_black != shadow_detector) {
                shadowColor = texture2D(shadowcolor0, the_shadow_pos.xy);
                alpha_complement = 1.0 - shadowColor.a;
                shadowColor.rgb = mix(shadowColor.rgb, vec3(1.0), alpha_complement);
                shadowColor.rgb *= alpha_complement;
                }
            }
            
            shadowColor *= shadow_black;
            shadowColor.rgb = clamp(shadowColor.rgb * (1.0 - shadow_detector) + shadow_detector, vec3(0.0), vec3(1.0));

            return shadowColor.rgb;

        #elif SHADOW_TYPE == 1  // Soft
            float shadow_detector_a = 1.0;
            float shadow_black_a = 1.0;
            vec4 shadow_color_a = vec4(1.0);

            float shadow_detector_b = 1.0;
            float shadow_black_b = 1.0;
            vec4 shadow_color_b = vec4(1.0);

            float shadow_detector_c = 1.0;
            float shadow_black_c = 1.0;
            vec4 shadow_color_c = vec4(1.0);

            float shadow_detector_d = 1.0;
            float shadow_black_d = 1.0;
            vec4 shadow_color_d = vec4(1.0);

            float alpha_complement;

            float current_radius = dither;
            dither *= 6.283185307179586;
            float dither_2 = dither + 1.5707963267948966;

            vec2 offset = (vec2(cos(dither), sin(dither)) * current_radius * SHADOW_BLUR) / shadowMapResolution;
            vec2 offset2 = (vec2(cos(dither_2), sin(dither_2)) * (1.0 - current_radius) * SHADOW_BLUR) / shadowMapResolution;
            // vec2 offset2 = vec2(-offset.y, offset.x);

            float z_bias = dither * 0.00002;

            shadow_detector_a = shadow2D(shadowtex0, vec3(the_shadow_pos.xy + offset, the_shadow_pos.z - z_bias)).r;
            shadow_detector_b = shadow2D(shadowtex0, vec3(the_shadow_pos.xy - offset, the_shadow_pos.z - z_bias)).r;
            shadow_detector_c = shadow2D(shadowtex0, vec3(the_shadow_pos.xy + offset2, the_shadow_pos.z - z_bias)).r;
            shadow_detector_d = shadow2D(shadowtex0, vec3(the_shadow_pos.xy - offset2, the_shadow_pos.z - z_bias)).r;

            if (shadow_detector_a < 1.0) {
                shadow_black_a = shadow2D(shadowtex1, vec3(the_shadow_pos.xy + offset, the_shadow_pos.z - z_bias)).r;
                if (shadow_black_a != shadow_detector_a) {
                shadow_color_a = texture2D(shadowcolor0, the_shadow_pos.xy + offset);
                alpha_complement = 1.0 - shadow_color_a.a;
                shadow_color_a.rgb = mix(shadow_color_a.rgb, vec3(1.0), alpha_complement);
                shadow_color_a.rgb *= alpha_complement;
                }
            }
            
            shadow_color_a *= shadow_black_a;

            if (shadow_detector_b < 1.0) {
                shadow_black_b = shadow2D(shadowtex1, vec3(the_shadow_pos.xy - offset, the_shadow_pos.z - z_bias)).r;
                if (shadow_black_b != shadow_detector_b) {
                shadow_color_b = texture2D(shadowcolor0, the_shadow_pos.xy - offset);
                alpha_complement = 1.0 - shadow_color_b.a;
                shadow_color_b.rgb = mix(shadow_color_b.rgb, vec3(1.0), alpha_complement);
                shadow_color_b.rgb *= alpha_complement;
                }
            }
            
            shadow_color_b *= shadow_black_b;

            if (shadow_detector_c < 1.0) {
                shadow_black_c = shadow2D(shadowtex1, vec3(the_shadow_pos.xy + offset2, the_shadow_pos.z - z_bias)).r;
                if (shadow_black_c != shadow_detector_c) {
                shadow_color_c = texture2D(shadowcolor0, the_shadow_pos.xy + offset2);
                alpha_complement = 1.0 - shadow_color_c.a;
                shadow_color_c.rgb = mix(shadow_color_c.rgb, vec3(1.0), alpha_complement);
                shadow_color_c.rgb *= alpha_complement;
                }
            }
            
            shadow_color_c *= shadow_black_c;

            if (shadow_detector_d < 1.0) {
                shadow_black_d = shadow2D(shadowtex1, vec3(the_shadow_pos.xy - offset2, the_shadow_pos.z - z_bias)).r;
                if (shadow_black_d != shadow_detector_d) {
                shadow_color_d = texture2D(shadowcolor0, the_shadow_pos.xy - offset2);
                alpha_complement = 1.0 - shadow_color_d.a;
                shadow_color_d.rgb = mix(shadow_color_d.rgb, vec3(1.0), alpha_complement);
                shadow_color_d.rgb *= alpha_complement;
                }
            }
            
            shadow_color_d *= shadow_black_d;

            shadow_detector_a = (shadow_detector_a + shadow_detector_b + shadow_detector_c + shadow_detector_d);
            shadow_detector_a *= 0.25;

            shadow_color_a.rgb = (shadow_color_a.rgb + shadow_color_b.rgb + shadow_color_c.rgb + shadow_color_d.rgb) * 0.25;
            shadow_color_a.rgb = mix(shadow_color_a.rgb, vec3(1.0), shadow_detector_a);

            return shadow_color_a.rgb;
        #endif
    }

#endif

// AFK: average AFK_PCF_RINGS rings, each rotated by the golden ratio.
float get_shadow(vec3 the_shadow_pos, float dither) {
    #if SHADOW_TYPE == 1
        int rings = AFK_PCF_RINGS;
        float sum = 0.0;
        for (int r = 0; r < rings; r++) {
            sum += afk_shadow_ring(the_shadow_pos, fract(dither + float(r) * 0.6180339887));
        }
        return sum / float(rings);
    #else
        return afk_shadow_ring(the_shadow_pos, dither);
    #endif
}

#if defined COLORED_SHADOW
    vec3 get_colored_shadow(vec3 the_shadow_pos, float dither) {
        #if SHADOW_TYPE == 1
            int rings = AFK_PCF_RINGS;
            vec3 sum = vec3(0.0);
            for (int r = 0; r < rings; r++) {
                sum += afk_colored_shadow_ring(the_shadow_pos, fract(dither + float(r) * 0.6180339887));
            }
            return sum / float(rings);
        #else
            return afk_colored_shadow_ring(the_shadow_pos, dither);
        #endif
    }
#endif
