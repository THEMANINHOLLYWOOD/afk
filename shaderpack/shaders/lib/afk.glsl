/* AFK - afk.glsl
The AFK additions to MakeUp: the master quality tier, the Mac-safe path, frame-time
adaptation budgets, the brightness floor, border fog and water options.

Part of the AFK shader pack, a modified version of MakeUp Ultra Fast by Javier Garduño.
GNU Lesser General Public License v3.0 or later
*/

// ---------------------------------------------------------------------------------------------
// Master quality tier. Picking a profile (Potato ... Ultra) in the shader menu sets AFK_TIER and
// MakeUp's own quality options together; every option can still be changed by hand afterwards.
// The tier also sets the budgets below (ray-march steps, samples, volumetric resolution).
#define AFK_TIER 3 // [0 1 2 3 4] Potato, Low, Medium, High, Ultra.

// Frame-time adaptation: inside a tier, quality bends (fewer ray-march steps and samples,
// lower-resolution volumetric light) when frames get slow, before the frame rate falls below 24.
#define AFK_ADAPTIVE // Bend quality within the tier to hold the frame rate.

// Brightness floor: the least light a block can get. Caves and nights stay readable.
#define AFK_CAVE_LIGHT 0.50 // [0.00 0.10 0.20 0.30 0.40 0.50 0.60 0.70 0.80 0.90 1.00] Brightness floor in caves and other places the sky can't reach. 0 is MakeUp's original darkness.
#define AFK_NIGHT_LIGHT 0.50 // [0.00 0.10 0.20 0.30 0.40 0.50 0.60 0.70 0.80 0.90 1.00] Extra light outdoors at night. 0 is MakeUp's original night.
#define AFK_DARK_EXPOSURE 1.50 // [1.00 1.25 1.50 1.75 2.00 2.50 3.00] How far auto-exposure opens up in dark places.
#define AFK_TONEMAP 1 // [0 1] MakeUp: the original sigmoid. AFK filmic: softer highlights and a lifted toe so dark areas keep detail.

// Fog
#define AFK_BORDER_FOG // Fade terrain into the sky before the edge of render distance, so chunk borders never show.
#define AFK_BORDER_FOG_START 0.80 // [0.60 0.65 0.70 0.75 0.80 0.85 0.90] Where border fog starts, as a fraction of render distance.

// Water
#define AFK_WATER_NIGHT_TINT 0.50 // [0.00 0.25 0.50 0.75 1.00] Keeps water tinted and visible at night and in caves.

// Debug view (development only, not in the menu)
// #define AFK_DEBUG_DEPTH

#ifdef AFK_ADAPTIVE
#endif
#ifdef AFK_BORDER_FOG
#endif

// ---------------------------------------------------------------------------------------------
// Mac-safe path. Apple's OpenGL stops at 4.1: no compute shaders, no SSBOs, no image
// load/store, and at most 16 samplers per fragment shader. Nothing in this pack uses the first
// three on any platform; AFK_MAC is where per-feature clamps go if a program ever approaches the
// sampler limit (tools/count-samplers.py in the repo checks every program).
#if defined MC_OS_MAC || defined MC_GL_RENDERER_APPLE
    #define AFK_MAC
#endif

// ---------------------------------------------------------------------------------------------
// Frame-time adaptation. afkPerf comes from shaders.properties: 1.0 while frames take 22 ms or
// less (45+ fps), falling to 0.0 at 36 ms (28 fps), smoothed so it drops in about half a second
// and recovers over a few seconds, and quantized to four steps so it doesn't shimmer.
uniform float afkPerf;

#ifdef AFK_ADAPTIVE
    #define AFK_PERF afkPerf
#else
    #define AFK_PERF 1.0
#endif

// An integer budget between lo (slow frames) and hi (fast frames).
#define AFK_BUDGET(lo, hi) int(float(lo) + (float(hi) - float(lo)) * AFK_PERF + 0.5)

// Per-tier budgets: [min, max] for each adaptive loop.
#if AFK_TIER <= 0
    #define AFK_SSR_MIN 4
    #define AFK_SSR_MAX 6
    #define AFK_PCF_MIN 1
    #define AFK_PCF_MAX 1
    #define AFK_GODRAY_MIN 3
    #define AFK_GODRAY_MAX 4
    #define AFK_VL_MIN 3
    #define AFK_VL_MAX 4
    #define AFK_VL_SCALE_MIN 0.5
    #define AFK_VL_SCALE_MAX 0.5
#elif AFK_TIER == 1
    #define AFK_SSR_MIN 5
    #define AFK_SSR_MAX 8
    #define AFK_PCF_MIN 1
    #define AFK_PCF_MAX 2
    #define AFK_GODRAY_MIN 3
    #define AFK_GODRAY_MAX 5
    #define AFK_VL_MIN 4
    #define AFK_VL_MAX 5
    #define AFK_VL_SCALE_MIN 0.5
    #define AFK_VL_SCALE_MAX 0.5
#elif AFK_TIER == 2
    #define AFK_SSR_MIN 6
    #define AFK_SSR_MAX 10
    #define AFK_PCF_MIN 1
    #define AFK_PCF_MAX 2
    #define AFK_GODRAY_MIN 4
    #define AFK_GODRAY_MAX 6
    #define AFK_VL_MIN 4
    #define AFK_VL_MAX 6
    #define AFK_VL_SCALE_MIN 0.5
    #define AFK_VL_SCALE_MAX 0.5
#elif AFK_TIER == 3
    #define AFK_SSR_MIN 7
    #define AFK_SSR_MAX 12
    #define AFK_PCF_MIN 1
    #define AFK_PCF_MAX 3
    #define AFK_GODRAY_MIN 4
    #define AFK_GODRAY_MAX 8
    #define AFK_VL_MIN 4
    #define AFK_VL_MAX 8
    #define AFK_VL_SCALE_MIN 0.5
    #define AFK_VL_SCALE_MAX 0.75
#else
    #define AFK_SSR_MIN 8
    #define AFK_SSR_MAX 14
    #define AFK_PCF_MIN 2
    #define AFK_PCF_MAX 4
    #define AFK_GODRAY_MIN 5
    #define AFK_GODRAY_MAX 10
    #define AFK_VL_MIN 5
    #define AFK_VL_MAX 10
    #define AFK_VL_SCALE_MIN 0.5
    #define AFK_VL_SCALE_MAX 1.0
#endif

// Budgets as used by the code. PCF is in rings of 4 taps (1 ring = MakeUp's original 4 taps).
#define AFK_SSR_STEPS AFK_BUDGET(AFK_SSR_MIN, AFK_SSR_MAX)
#define AFK_PCF_RINGS AFK_BUDGET(AFK_PCF_MIN, AFK_PCF_MAX)
#define AFK_GODRAY_STEPS AFK_BUDGET(AFK_GODRAY_MIN, AFK_GODRAY_MAX)
#define AFK_VL_STEPS AFK_BUDGET(AFK_VL_MIN, AFK_VL_MAX)
// Volumetric clouds and AO keep MakeUp's own sliders as the maximum and drop to 60% / 2 steps.
#define AFK_CLOUD_STEPS AFK_BUDGET(int(float(CLOUD_STEPS_AVG) * 0.6 + 0.5), CLOUD_STEPS_AVG)
#define AFK_AO_STEPS AFK_BUDGET(2, AOSTEPS)
// Volumetric light resolution (fraction of the screen on each axis), quantized to quarters.
#define AFK_VL_SCALE (floor((AFK_VL_SCALE_MIN + (AFK_VL_SCALE_MAX - AFK_VL_SCALE_MIN) * AFK_PERF) * 4.0 + 0.5) * 0.25)

// The volumetric light runs in its own dynamic-resolution pass (deferred1) when it is the
// shadow-map kind and the dimension has a sun or the End's fixed light.
#if VOL_LIGHT == 2 && defined SHADOW_CASTING && !defined NETHER
    #define AFK_VL_PASS
#endif

// ---------------------------------------------------------------------------------------------
// Depth. Minecraft 26.x renders with reverse-Z; Iris 1.11.4+ hands packs depth that behaves like
// classic forward-Z in [0, 1] (1 = far plane) and keeps the projection matrices consistent with
// it. The pack never assumes either: every depth read goes through afk_depth(), which flips
// reverse-Z values when the projection matrix says depth is reversed (gbufferProjection[2][2]
// is about -1 for forward-Z and about 0 or positive for reverse-Z).
#define AFK_DEPTH_IS_REVERSED(p) ((p)[2][2] > -0.5)
