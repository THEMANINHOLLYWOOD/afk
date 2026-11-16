#include "/lib/config.glsl"

// Do not remove comments. It works!
/*

noisetex - Water normals
colortex0 - Bloom auxiliar
colortex1 - Antialiasing auxiliar
colortex2 - Selective TAA Mask (current frame)
colortex3 - TAA Averages history
gaux1 - Screen-Space-Reflection
gaux2 - Clouds texture
gaux3 - Exposure auxiliar
gaux4 - Fog auxiliar

const int noisetexFormat = RG8;
const int colortex0Format = R11F_G11F_B10F;
*/
#ifdef DOF
/*
const int colortex1Format = RGBA16F;
*/
#else
/*
const int colortex1Format = R11F_G11F_B10F;
*/
#endif
/*
const int colortex2Format = R8;
*/
#ifdef DOF
/*
const int colortex3Format = RGBA16F;
*/
#else
/*
const int colortex3Format = R11F_G11F_B10F;
*/
#endif
/*
const int gaux1Format = R11F_G11F_B10F;
const int gaux2Format = R8;
const int gaux3Format = R16F;
const int gaux4Format = R11F_G11F_B10F;
const int colortex8Format = R11F_G11F_B10F;

const int shadowcolor0Format = RGBA8;
*/

// Buffers clear
const bool colortex0Clear = false;
const bool colortex1Clear = false;
const bool colortex2Clear = true;
const bool colortex3Clear = false;
const bool gaux1Clear = false;
const bool gaux2Clear = false;
const bool gaux3Clear = false;
const bool gaux4Clear = false;
const bool colortex8Clear = false;  // AFK: dynamic-resolution volumetric light

/* Uniforms */

#ifdef DEBUG_MODE
    uniform sampler2D shadowtex1;
    uniform sampler2D shadowcolor0;
    uniform sampler2D colortex3;
#endif

uniform sampler2D gaux3;

#ifdef AFK_DEBUG_DEPTH
    uniform sampler2D depthtex0;
    uniform mat4 gbufferProjection;
#endif
uniform sampler2D colortex1;
#ifdef SELECTIVE_TAA_V2
uniform sampler2D colortex2;
#endif
uniform float viewWidth;

#if AA_TYPE == 3 || defined IMAGE_SHARPENING
    uniform float pixelSizeX;
    uniform float pixelSizeY;
#endif

/* Ins / Outs */

varying vec2 texcoord;
varying float exposure;

/* Utility functions */

#if AA_TYPE == 3
    // #include "/lib/post.glsl"
    #include "/lib/luma.glsl"
    #include "/lib/fxaa.glsl"
#endif

#include "/lib/basic_utils.glsl"
#include "/lib/tone_maps.glsl"

#ifdef COLOR_BLINDNESS
    #include "/lib/color_blindness.glsl"
#endif

#if CHROMA_ABER == 1
    #include "/lib/aberration.glsl"
#endif

#ifdef IMAGE_SHARPENING
    #include "/lib/cas.glsl"
#endif


// MAIN FUNCTION ------------------

void main() {
    #if CHROMA_ABER == 1
        vec3 blockColor = color_aberration();
    #else
        vec3 blockColor;

        #ifdef IMAGE_SHARPENING
            blockColor = CAS(texcoord);
        #else
            blockColor = texture2D(colortex1, texcoord).rgb;
        #endif

        #if AA_TYPE == 3 && !defined DOF
            #ifdef SELECTIVE_TAA_V2
                if(texture2DLod(colortex2, texcoord,0.0).r<=0.5) {
                    blockColor = fxaa311(blockColor, 5);
                }
            #else
                blockColor=fxaa311(blockColor,5);
            #endif
        #endif
    #endif

    // Exposure correction
    blockColor *= vec3(exposure);
    #if AFK_TONEMAP == 1
        blockColor = afk_filmic(blockColor);
    #else
        blockColor = custom_sigmoid(blockColor);
    #endif

    // Color-grading -----
    // DEVELOPER: If your post processing effect only involves the current pixel,
    // it can be placed here. For example:

    // Saturation:
    // float actual_luma = luma(blockColor);
    // blockColor = mix(vec3(actual_luma), blockColor, 1.5);

    // Color-blindness correction
    #ifdef COLOR_BLINDNESS
        blockColor = color_blindness(blockColor);
    #endif

    #ifdef AFK_DEBUG_STRESS
        // AFK debug: burn GPU time so frames slow down and the adaptation has something to react to
        float afkStress = 0.0;
        for (int i = 0; i < 160; i++) {
            afkStress += texture2D(colortex1, texcoord + vec2(float(i) * 0.0007, 0.0)).r;
        }
        blockColor += vec3(afkStress * 1e-7);
    #endif

    #ifdef AFK_DEBUG_PERF
        // AFK debug, bottom left: afkPerf (green bar) and the volumetric light resolution (blue bar)
        if(texcoord.x < 0.3 && texcoord.y < 0.03) {
            blockColor = (texcoord.x / 0.3 < AFK_PERF) ? vec3(0.1, 1.0, 0.2) : vec3(0.12);
        } else if(texcoord.x < 0.3 && texcoord.y < 0.06) {
            blockColor = (texcoord.x / 0.3 < AFK_VL_SCALE) ? vec3(0.2, 0.45, 1.0) : vec3(0.12);
        }
    #endif

    #ifdef AFK_DEBUG_DEPTH
        // AFK: which depth convention does the pack see? Left half: raw depth, so the sky is white
        // with forward-Z and black with reverse-Z. Right half: red where the projection matrix
        // looks reversed, green where it looks forward.
        float rawDepth = texture2D(depthtex0, texcoord).r;
        if(texcoord.x < 0.5) {
            blockColor = vec3(pow(rawDepth, 64.0));
        } else {
            blockColor = AFK_DEPTH_IS_REVERSED(gbufferProjection) ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
            blockColor *= 0.25 + 0.75 * pow(rawDepth, 64.0);
        }
        if(texcoord.y > 0.95) {  // top strip: what the runtime guard decided (red = reversed)
            blockColor = afkDepthReversed > 0.5 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);
        }
    #endif

    #ifdef DEBUG_MODE
        if(texcoord.x < 0.5 && texcoord.y < 0.5) {
            blockColor = texture2D(shadowtex1, texcoord * 2.0).rrr;
        } else if(texcoord.x >= 0.5 && texcoord.y >= 0.5) {
            blockColor = vec3(texture2D(gaux3, vec2(0.5)).r * 0.25);
        } else if(texcoord.x < 0.5 && texcoord.y >= 0.5) {
            blockColor = texture2D(colortex1, ((texcoord - vec2(0.0, 0.5)) * 2.0)).rgb;
        } else if(texcoord.x >= 0.5 && texcoord.y < 0.5) {
            blockColor = texture2D(shadowcolor0, ((texcoord - vec2(0.5, 0.0)) * 2.0)).rgb;
        } else {
            blockColor = vec3(0.5);
        }

        gl_FragData[0] = vec4(blockColor, 1.0);

    #else
        gl_FragData[0] = vec4(blockColor, 1.0);
    #endif
}