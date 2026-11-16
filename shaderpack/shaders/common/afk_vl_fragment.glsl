/* AFK - afk_vl_fragment.glsl
Dynamic-resolution volumetric light (deferred1).

MakeUp computes shadow-map volumetric light at full resolution inside composite. AFK moves it
here and renders it into colortex8 at AFK_VL_SCALE of the screen on each axis (0.5 to 1.0
depending on the tier and on afkPerf): pixels outside that corner are skipped, the rest
ray-march the scene for the matching full-screen position. composite then upsamples it.

Part of the AFK shader pack, a modified version of MakeUp Ultra Fast by Javier Garduño.
GNU Lesser General Public License v3.0 or later
*/

#include "/lib/config.glsl"

/* Uniforms */

uniform sampler2D depthtex0;
uniform float far;
uniform float near;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform sampler2DShadow shadowtex1;
#if defined COLORED_SHADOW
    uniform sampler2DShadow shadowtex0;
    uniform sampler2D shadowcolor0;
#endif

/* Ins / Outs */

varying vec2 texcoord;
varying mat4 modeli_times_projectioni;

/* Utility functions */

#include "/lib/depth.glsl"
#include "/lib/dither.glsl"
#ifdef AFK_VL_PASS
    #include "/lib/volumetric_light.glsl"
#endif

// MAIN FUNCTION ------------------

void main() {
    #ifndef AFK_VL_PASS
        discard;  // shaders.properties disables this program when there's nothing to do
    #else
    float scale = AFK_VL_SCALE;
    if (texcoord.x > scale || texcoord.y > scale) {
        discard;
    }
    vec2 uv = texcoord / scale;

    float d = texture2DLod(depthtex0, uv, 0.0).r;
    float screen_distance = ld(d) * far * 0.5;

    #if AA_TYPE > 0
        float dither = shiftedDither17(gl_FragCoord.xy);
    #else
        float dither = rDither(gl_FragCoord.xy);
    #endif

    #if defined COLORED_SHADOW
        vec3 volumetricLight = get_volumetric_color_light(uv, dither, screen_distance, modeli_times_projectioni);
    #else
        vec3 volumetricLight = vec3(get_volumetric_light(uv, dither, screen_distance, modeli_times_projectioni));
    #endif

    /* RENDERTARGETS: 8 */
    gl_FragData[0] = vec4(volumetricLight, 1.0);
    #endif
}
