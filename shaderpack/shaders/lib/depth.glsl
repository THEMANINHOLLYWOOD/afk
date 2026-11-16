/* MakeUp - depth.glsl
Depth utilities.

Javier Garduño - GNU Lesser General Public License v3.0
Modified for the AFK shader pack: reverse-Z guard.
*/

float ld(float depth) {
    depth = AFK_FORWARD_DEPTH(depth);  // AFK: reverse-Z guard, see afk.glsl
    return (2.0 * near) / (far + near - depth * (far - near));
}
