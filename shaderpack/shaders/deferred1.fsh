#version 120
#extension GL_ARB_shader_texture_lod : enable
/* AFK - deferred1.fsh
Render: dynamic-resolution volumetric light

Part of the AFK shader pack, a modified version of MakeUp Ultra Fast.
GNU Lesser General Public License v3.0 or later
*/

#define USE_BASIC_SH // Sets the use of a "basic" or "generic" shader for custom dimensions, instead of the default overworld shader. This can solve some rendering issues as the shader is closer to vanilla rendering.
#ifdef USE_BASIC_SH
    #define UNKNOWN_DIM
#endif

#define DEFERRED1_SHADER

#include "/common/afk_vl_fragment.glsl"
