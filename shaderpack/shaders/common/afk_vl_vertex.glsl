/* AFK - afk_vl_vertex.glsl
Dynamic-resolution volumetric light (deferred1), vertex stage.

Part of the AFK shader pack, a modified version of MakeUp Ultra Fast by Javier Garduño.
GNU Lesser General Public License v3.0 or later
*/

#include "/lib/config.glsl"

/* Uniforms */

uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;

/* Ins / Outs */

varying vec2 texcoord;
varying mat4 modeli_times_projectioni;

// MAIN FUNCTION ------------------

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    texcoord = gl_MultiTexCoord0.xy;
    modeli_times_projectioni = gbufferModelViewInverse * gbufferProjectionInverse;
}
