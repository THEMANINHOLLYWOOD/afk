# Shader pack and resource pack research for the AFK modpack

Target: Fabric, Minecraft 26.3, Iris + Sodium. Research date: 2026-11-14.

How to read this report:

- **[verified]** means I read it in source code, an official project page, a license file, or a changelog during this research.
- **[secondary]** means it comes from a third-party article, an aggregator, or a search-engine summary that I could not check against a primary source.
- **[unverified]** means I could not confirm it, or it is my own inference.
- Reddit could not be fetched directly: the site blocks this research tool. Reddit items below are thread titles or snippets that surfaced in search results, and they are labelled as such. Most player quotes therefore come from GitHub issues, CurseForge, Modrinth, MacRumors and Mojang's feedback site, which I could read.
- Download counts come from the Modrinth API on 2026-11-14.

---

## 0. Key context for 26.3 (read first)

1. **Iris on 26.3 runs on OpenGL only (officially).** Iris 1.11.0 (26.2) says: "Note that Vulkan is not supported." The latest Iris for 26.3 is 1.11.7 (2026-09-30). **[verified, Modrinth changelog]**
   - Minecraft 26.2+ has a Graphics API setting: Default, Prefer Vulkan (Experimental), Prefer OpenGL. A third-party article says Default currently behaves like OpenGL. **[secondary]**
   - Iris's `26.3-new` branch has recent commits titled "Vulkan improvements" (2026-10-20) and "Use manual descriptor sets to fix macOS support" (2026-11-02). Solas 3.9 (2026-11-02) says it "added support for Iris on Vulkan (Thanks to IMS)". Vulkan Iris is therefore in development but **not released**. **[verified: commits and changelog exist; release status unverified]**
   - Unofficial Vulkan shader loaders exist: Vitrail (LGPL-3.0, beta, "OptiFine-format shader packs, on Minecraft's native Vulkan renderer") and the archived `laurensanp/iris-vulkan`. **[verified, Modrinth/GitHub]**
2. **Mojang is replacing OpenGL with Vulkan.** The February 2026 minecraft.net post "Another step towards Vibrant Visuals for Java Edition" says OpenGL is deprecated on macOS, and that Vulkan will run on macOS through a translation layer. OpenGL will be removed once Vulkan is stable, with no date set. **[secondary: the official page timed out for me, and these points come from GamingOnLinux, Neowin and search summaries of the official post]**
   - Implication for AFK: write the pack in portable GLSL, avoid driver-specific tricks, and expect to retest when Iris-on-Vulkan ships. That release would also lift the macOS OpenGL 4.1 limits.
3. **Reverse-Z depth is now common.** Iris 1.11.4 (26.2) added a "depth transformer" so it no longer has to switch to forward-Z during shader rendering, and warns that "this may cause some packs to behave unexpectedly". `IrisApi` gained `isReverseZDuringShaders()`. Distant Horizons 3.3 reportedly switched to reverse-Z by default (Photon issue #667). Iris 26.3 declares `distanthorizons <= 3.2.0` as "breaks". **[verified: Iris changelog, IrisApi.java, fabric.mod.json diff, Photon #667]**
   - Implication: write the pack's depth handling so it is convention-agnostic, and test it with DH 3.3+ and Voxy.
4. **macOS is a second-class target** (OpenGL 4.1: no compute shaders, no SSBOs, no image load/store, and limits of 16 samplers per fragment shader). Details are in §3.2.

---

## 1. Major shader packs: techniques, performance, reception

### 1.1 Popularity snapshot (Modrinth, 2026-11-14) [verified]

| Pack | Author | Downloads | Follows | License (Modrinth id) | Last update | 26.3 tag |
|---|---|---|---|---|---|---|
| Complementary Reimagined | EminGT | 72.8M | 11.6k | Custom (Complementary License Agreement) | 2026-09-15 (r5.9.3) | yes |
| Complementary Unbound | EminGT | 47.3M | 6.2k | Custom (same) | 2026-09-15 | yes |
| BSL | CaptTatsu | 31.9M | 6.4k | All Rights Reserved (+ agreement) | 2026-09-21 (10.1.8) | yes |
| Photon | sixthsurge | 29.3M | 4.4k | Photon Shaders License Agreement | 2026-10-19 (v1.4) | yes |
| Solas | Septonious | 18.8M | 3.6k | ARR | 2026-11-02 (3.9) | yes |
| Bliss | Xonk | 15.8M | 3.0k | ARR ("© 2020 Chocapic13") | 2025-11-23 (newer on GitHub) | yes |
| MakeUp Ultra Fast | KDXavier | 14.3M | 2.5k | **LGPL-3.0-or-later** | 2026-10-19 | yes |
| Rethinking Voxels | gri573 | 13.8M | 4.4k | Complementary Agreement | 2025-06-19 | no (to 1.21.10) |
| Super Duper Vanilla | Eldeston | 8.3M | 2.7k | FlameRender Studios License | 2025-07-08 | no (to 1.21.8) |
| Mellow | TheCMK | 8.1M | 1.4k | **MIT** | 2026-10-31 | yes |
| Insanity | ElocinDev | 8.1M | 1.3k | AGNYA License | 2024-06-13 | no |
| Miniature | ukrech | 5.2M | 1.9k | **MIT** | 2026-07-29 | yes |
| Nostalgia | RRe36 | 4.6M | 1.2k | ARR | 2025-02-28 | no (to 1.21.4) |
| Kappa | RRe36 | 3.4M | 1.3k | ARR | 2025-02-28 | no (to 1.21.4) |
| I Like Vanilla | What42 | 3.0M | 0.6k | What42's Shader License 2.3 | 2026-10-07 | yes |
| Noble | BelmuTM | 2.5M | 0.8k | **GPL-3.0-only** | 2026-09-28 | yes |
| RenderPearl | Luracasmus | 1.4M | 0.5k | **MIT** | 2026-07-21 | yes |
| Euphoria Patches (Complementary add-on, a mod) | SpacEagle17 | 29.9M | n/a | MPL-2.0 (patcher) | 26.3 | yes |

Shader packs mostly work across Minecraft versions as long as Iris supports the version. Photon's FAQ says so directly. A missing 26.3 tag therefore does not mean the pack is broken, but it has not been tested. **[verified, Photon page]**

### 1.2 Per-pack notes

**Complementary Reimagined / Unbound (EminGT)** [verified unless marked]
- **Origin and style.** It started in 2018 as an edit of BSL and was later rewritten. Reimagined and Unbound share one codebase: "The only difference between these two styles is the default settings." Reimagined keeps vanilla elements (blocky clouds, square sun). Unbound is more realistic.
- **Profiles.** There are 8 `profile.*` entries in `shaders.properties`: POTATO, VERYLOW, LOW, MEDIUM, HIGH, VERYHIGH, ULTRA, COMPLEMENTARY. They scale shadow quality and distance (64 to 256 blocks), water and block reflection quality, light shaft quality, SSAO, FXAA, detail and cloud quality, anisotropic filtering, and the colored-lighting volume (`COLORED_LIGHTING` = 0 / 192 / 256 / 512).
- **Shadows.**
  - The "Real-Time Shadows" tiers run from OFF (`program.world0/shadow.enabled=false`) to Very Low (simpler filtering, no colored shadows) up to High.
  - Shadow smoothing and a leaf-shadow optimisation option disable leaf and vine shadows in dark areas.
  - Solas credits EminGT and gri573 for "improved shadow sampling" and advertises "shadows without light leaking in caves", which comes from this lineage.
- **Colored lighting ("Advanced Color Tracing", r5.2+).**
  - It voxelizes light blocks during the shadow pass into a 3D `r16ui` image, then flood-fills light in `shadowcomp` passes into two ping-pong `rgba16f` 3D images (`floodfill_img`, `floodfill_img_copy`). It requires Iris and real-time shadows.
  - The menu text warns: "each increment can cost more than twice the performance of the previous tier".
  - My calculation from the declared image sizes:
    - 8 chunks (256×128×256): about 144 MiB.
    - 16 chunks (512×256×512): about 1.1 GiB.
    - World-Space Reflections add a buffer object of about 773 MiB at 16 chunks.
  - It is disabled on macOS via `#ifdef MC_OS_MAC #define COLORED_LIGHTING 0`.
- **Reflections.**
  - SSR is used for water and blocks, with a separate reflection buffer resolution.
  - "World-Space Reflections" (r5.6) reuse the voxel data for off-screen reflections. They are capped at 16 chunks, and the menu warns they "can severely impact performance".
  - Player reflections arrived in r5.7. End volumetric reflections arrived in r5.9.
- **Water.** Several water styles, caustic styles, refraction, foam, color and fog sliders, waving water vertices, and a "bright cave water" option.
- **Materials.** `RP_MODE` selects Basic, **Integrated PBR+** (hand-authored per-block materials, generated normals, "coated textures" and emissive ores, with no resource pack needed), seuspbr, or labPBR. It also offers POM, a normal-map strength setting, and directional blocklight.
- **Atmosphere.**
  - Scene-aware light shafts (rewritten in r5.8 to be cheaper), aurora, nebulae, night stars, and per-biome Nether fog via smoothed custom uniforms.
  - A Nether storm, End beams (world-space since r5.8), cave fog and border fog.
  - Rain puddles, which are stored in an image.
- **Anti-aliasing.** The TAA option text says it "improves performance by reducing the sample counts of some heavier effects". r5.5 improved temporal filtering. FXAA is also available.
- **Gameplay comfort options.** Cave lighting ("Adds extra lighting to areas that don't have skylight"), no waving indoors, light-level display, reduced close particles and less lava fog.
- **Mod support.**
  - Distant Horizons since r5.2. Voxy since r5.8 ("extensive Voxy support with working IntegratedPBR"), which also brought DH shadows and water reflections.
  - Colorwheel (Create/Flywheel) since r5.7, and chunk fade-in since r5.7.
- **Performance.** In I Like Vanilla's benchmark (GTX 1070, 1080p, 16 chunks, DH, Linux), Complementary Reimagined ran at 136 FPS (Potato), 98 (Low), 76 (High), 54 (Very High) and 41 (Ultra). It is the heaviest of the mainstream packs at equal tiers in that test. **[verified as published by a competing pack author; one GPU only]**
- **Praise.** Guides often call it the default safe pick: one says it is "hardest to get wrong". **[secondary]** It is praised for polish, mod coverage and wide profile range.

**BSL (CaptTatsu)**
- Bright, warm, "colorful, and distinct". It has realtime shadows, volumetric light, AO, bloom, customizable clouds and water, built-in AA, and optional DOF, motion blur, specular/normal mapping, cel-shading and world curvature. **[verified, Modrinth]**
- v10.0 (May 2025) reportedly reworked multi-colored blocklight to a voxel approach, crediting sixthsurge and Emin, and kept the old version as "Screen Space MCBL". **[secondary]** v10.1 fixed an error with Distant Horizons, so DH is supported. **[verified]**
- The Photonics mod patches in support for BSL 10.1+. **[verified]**
- Performance: 147 FPS (Minimum), 120 (Medium), 106 (High), 104 (Ultra) in I Like Vanilla's test. It is the lightest of the "big four". **[verified, single test]**
- Many popular packs started as BSL forks: Complementary, Insanity, Lux V1, and others.

**Photon (sixthsurge)** [verified, README and `shaders.properties`]
- A semi-realistic, gameplay-focused pack. It has a revamped sky with atmospheric scattering, multi-layer clouds with many cloud types, and an "immersive weather system providing different skies each day".
- **Shadows.** Soft shadows with variable-size penumbras (PCSS-style `SHADOW_VPS`) and colored shadows.
- **AO.** GTAO on High and above, SSAO on Low and Medium.
- **Lighting.** Voxel flood-fill colored lighting on the Ultra profile (Iris only). It uses `setup` and `shadowcomp` programs, an `r8ui` voxel image, and two `rgba16f` light volumes from 64³ to 256³. Photon's flood-fill design was later credited by Solas, BSL and Complementary.
- **Other effects.** Volumetric fog, SSR, environment reflections, bloom, DOF and motion blur.
- **Anti-aliasing and upscaling.** TAA, FXAA and CAS, plus **TAAU temporal upscaling** (`TAAU_RENDER_SCALE`, disabled by default, aimed at low-end devices).
- **Profiles.** low, medium, high, ultra. Low uses a 1024 shadow map and no VL. Ultra turns on colored light shafts, caustics and colored lights.
- **Mod support.** "Full labPBR resource pack support". Distant Horizons, Voxy (v1.3 also added screen-space shadows and SSS for distant terrain), Photonics (v1.4) and Colorwheel (v1.4).
- **License.** "**NOT** compatible with Apple GPUs (Metal)". See §5 for license terms.
- **Performance.** 112 (Low), 96 (Medium), 90 (High), 70 (Ultra) in I Like Vanilla's test. Articles say it "costs noticeably more than Complementary at comparable settings" **[secondary]**, which conflicts with that test.
- **Praise.** Its sky, clouds and natural lighting. Open issues include DH border fog (#265), water disappearing with Voxy (#463), and the "IMMERSION BREAKING" blocky clouds during rain (#435).

**Solas (Septonious)** [verified, Modrinth and changelog]
- A fantasy stylized look "at a moderate performance cost".
- **Lighting.** Colored block lighting (voxelization and light propagation credited to SixthSurge), realtime shadows "without light leaking in caves", cloud shadows and SSS.
- **Volumetrics.** 3D clouds, crepuscular rays, fireflies, Nether smoke and an Ender "protoplanetary disk".
- **Sky.** Aurora you can fly through, Milky Way, Ender nebula, black hole and rainbows.
- **Water.** Procedural waves, caustics, absorption and refraction.
- **Materials.** An integrated PBR with procedural normals, plus LabPBR and old SEUS PBR support. PBR was reworked in 3.7 to cover entities and held items.
- Supports Voxy, DH and Colorwheel. OptiFine lacks colored lighting and custom skyboxes.
- Reported broken on Apple Silicon (#168, "Dead on Mac", 2026-05). It does not render moving Create parts in a modpack (#172).
- First pack to ship support for the unreleased Iris on Vulkan (3.9).

**Bliss (Xonk)** [verified, README and Modrinth]
- A large edit of Chocapic13 V9: "a well performing fantasy styled shaderpack with emphasis on scene variation and customization".
- Voxel flood-fill colored lighting (credited to Null), a DOF overhaul (WoMspace), and an AgX tonemap.
- Strong DH support: `dhShadow.enabled` is toggleable. A fade-in for DH chunks was requested in #418.
- `iris.features.optional` includes COMPUTE_SHADERS and CUSTOM_IMAGES.
- Releases on Modrinth lag behind the Stable and Unstable branches on GitHub.
- Apple: "Distant Horizons + Bliss will not work on Apple devices", and some settings fail to compile (#181).

**Rethinking Voxels (gri573)** [verified]
- An edit of Complementary Reimagined in which block lights cast sharp, ray-traced shadows. It uses voxelization, ray-traced occlusion and colored flood-fill.
- The author notes "Occasional sudden brightness changes" as a side effect of the method. It is Iris-only (beta 18d and older also work on OptiFine).
- Very heavy. It has not been updated since June 2025.

**SEUS PTGI / PTGI HRR (Sonic Ether)**
- Path-traced global illumination on rasterizing GPUs. HRR ("half resolution rendering") renders effects at half resolution and reconstructs them with filtering and AA. HRR 3 was published on Patreon in Dec 2021, targets 1.19.4, and lists OptiFine as a requirement. **[secondary]**
- One article says it "loads, but has problems when motion blur is enabled" under Iris on 26.3. **[secondary]**
- SEUS Renewed is not maintained on the current schedule. **[secondary]**
- The technique is generally described as voxelizing the scene into the shadow-map pass and tracing rays against it with temporal accumulation. **[unverified: general knowledge, not checked against source]**
- Ran at 56 FPS in I Like Vanilla's test.

**Kappa and Nostalgia (RRe36)** [verified, Modrinth]
- **Kappa:** "realistic, but also authentic". It has soft shadows, screen-space path tracing (SSPT), TAA **and upscaling (TAAU)**, a procedural sky with multiple cloud layers, SSR plus an optional reflection capture, volumetric fog, and random per-day weather. Needs a GTX 1050/RX 560 or better, and does not support Intel HD 5000 or older.
- **Nostalgia:** reproduces old shadersmod-era looks with modern performance. Soft shadows, optional SSPT, TAA, "vanilla style volumetric clouds with multiple cloud layers" that use the resource pack's cloud texture, SSR plus reflection capture, and labPBR. Requires OpenGL 4.3, so no Mac.
- Both are tagged up to 1.21.4 and were last updated Feb 2025. The author's 2026 blog posts mention personal delays. **[verified, rre36.com blog list]**

**MakeUp Ultra Fast (KDXavier / javiergcim)** [verified]
- "Best quality / performance ratio". Every effect is optional: shadows, volumetric clouds, TAA, DOF, AO, water reflection and refraction, motion blur, bloom, chromatic aberration, auto-exposure, and color schemes. Tested on Windows and Linux.
- The fastest of the mainstream packs: 235 FPS with no effects and 137 on Extreme in I Like Vanilla's test.
- **LGPL-3.0-or-later**: "You can modify this shader for any purpose", with credit requested. Forks include E-LITE (also LGPL).

**Sildur's Vibrant / Enhanced Default**
- Vibrant ships separate downloads from Lite to Extreme, plus Extreme-VL with volumetric light. Lite targets Intel iGPUs and Macs. Enhanced Default keeps the vanilla look and adds shadows, reflections and god rays, with Fast/Fancy variants. Version 2.02 "Added support for MC 26.3". **[secondary]**
- Vibrant Extreme ran at 105 FPS in I Like Vanilla's test.
- Players complain about handheld light in caves: an r/minecraftshaders thread is titled "Using Sildur's enhanced default and not liking how [handheld lighting looks]". **[thread title from search index]**

**Chocapic13**
- A classic base; its author said no "premium only" raytraced stuff is planned. Its main relevance today is as the base for Bliss and RedHat. **[secondary]**
- I found no V10, and current feature status is **[unverified]**.

**Super Duper Vanilla (Eldeston)** [verified]
- Recreates the cancelled "Super Duper Graphics Pack" look. Its README marks Iris on Apple M1 as "Supported", which is unusual.
- License: modified versions may be redistributed under the same license, with credit, and the name may not contain "Super Duper Vanilla". It is tagged only to 1.21.8.

**Insanity / Hysteria (ElocinDev)**
- BSL-based horror packs with a "quick options menu". The description says: "Not only the night is dark, the Nether and End are even worse!"
- License: "Just don't distribute the files." Not updated since 2024. **[verified, Modrinth]**

**Other notable 2025-26 packs** [verified, Modrinth]
- **Mellow** (MIT): painterly look. Supports DH, Voxy and Colorwheel. Lightmap- or shadow-map-based shadows. A user in Solas #168 says it works on Apple Silicon.
- **I Like Vanilla:** vanilla style with a fantasy option, DH, Voxy and Colorwheel support. It publishes the benchmark used above.
- **RenderPearl** (MIT): very light, Iris-only. It uses the latest Iris features and is LabPBR 1.3 compliant.
- **Miniature** (MIT): blocky shadows and reflections, extremely light.
- **Noble** (GPL-3.0): realism with "clever lighting". NVIDIA/AMD only, no macOS.
- **Visual Vibrance:** a recreation of Vibrant Visuals. "No longer actively developed".
- **Euphoria Patches:** an add-on for Complementary with optional features, a "Popular Settings" tab, modded-block support and Photonics support.
- Small fantasy and horror Complementary edits (Spooklementary, Aetheria, Crystal) show demand for richer Nether and End skies.

### 1.3 Technique map (who does what best)

| Technique | How it is done in Iris packs | Best reference packs |
|---|---|---|
| Sun shadows | Shadow pass (re-renders geometry from the sun) into a distorted shadow map. PCF, or PCSS for variable penumbra. Temporal filtering hides noise. | Complementary (no cave leaks, wide tiering), Photon (VPS), Solas |
| Volumetric light and fog | Ray-march the shadow map per pixel at reduced resolution with dithering, then denoise with TAA | Complementary (scene-aware light shafts), Solas, Photon |
| SSR | Screen-space ray march, with sky or reflection-capture fallback | Complementary, Photon, Kappa/Nostalgia (capture) |
| Off-screen reflections | Voxel or world-space tracing using flood-fill voxel data | Complementary WSR |
| Water | Procedural normals, refraction, absorption, caustics, SSR | Complementary, Photon, Solas |
| Clouds | 2D/blocky (vanilla texture) or ray-marched 3D layers with weather | Photon, Bliss, Solas (3D); Nostalgia, Complementary Reimagined (vanilla-style volumetric) |
| Colored block light | Voxelize light sources in the shadow pass, then flood-fill in `shadowcomp`/compute into 3D images. Needs CUSTOM_IMAGES (GL 4.2+) or compute (4.3+), so no macOS | Complementary ACT, Photon, Solas, Bliss, BSL 10 |
| Path tracing / GI | SSPT (screen-space) or voxel tracing, with temporal accumulation | Kappa (SSPT), SEUS PTGI, Photonics mod (voxel RT, ReSTIR) |
| AA and upscaling | TAA with jitter. TAAU renders a fraction of pixels. CAS sharpening | Photon (TAAU + CAS), Kappa (TAAU), Complementary (TAA + FXAA) |
| Materials | Hardcoded per-block "integrated PBR", or LabPBR `_n`/`_s` maps | Complementary IPBR+, Solas; Photon/RenderPearl (LabPBR) |
| LOD mods | `dh_*` programs for Distant Horizons; explicit Voxy support | Complementary r5.8, Photon v1.3+, Solas, Mellow, I Like Vanilla |

---

## 2. Resource packs and related mods

### 2.1 Packs [verified unless marked]

- **Faithful 32x/64x.**
  - Double or quadruple-resolution reimagining of vanilla.
  - The license allows use and modification in other projects with credit and a link to faithfulpack.net. It bans paywalls and monetized links, and it bans AI training.
  - Faithful has no official LabPBR maps: it is an albedo-only pack. **[unverified]**
- **Fresh Animations.**
  - Animation overhaul for mobs. Requires **Entity Model Features (EMF)** plus **Entity Texture Features (ETF)** on Fabric. EMF recommends Entity Culling for "animation heavy packs like Fresh Animations".
  - Custom terms of use are in its description. It has 52M downloads.
- **Stay True.**
  - Vanilla-style 16x with connected textures and random grass variations. Hosted on CurseForge.
  - On Fabric it needs **Continuity** for CTM. **[secondary]**
- **Patrix 32x.**
  - "Made to be used with shaders". Realistic textures color-matched to vanilla, with large-scale tiling and randomization through OptiFine-format features.
  - Heavier than other packs at the same resolution. It includes FreshLX mob animations.
  - Higher resolutions are on Patreon. All 26.3 blocks and items are done.
- **Mizuno's 16 Craft.**
  - Vanilla-friendly 16x by Mizuno, Java and Bedrock, with a separate CIT pack.
  - Whether it needs EMF or Continuity for its extras is **[unverified]**; sources disagree on the OptiFine requirement. Community add-ons exist: Modern Mizuno's, Mizuno Better Leaves, Mizuno Connected Texture, and Mizuno x Fresh Animations.
- **LabPBR-native packs.**
  - Roundista, Stylista and Simplista (all "full LabPBR support").
  - Vanilla Mashup PBR, Seraphic PBR, PrettyRealistic.
  - Small helpers: Glass Normals (MIT), Water Normals (CC-BY), Emissive Ores LabPBR.
- **Related mods.**
  - **Continuity:** OptiFine CTM, emissive and custom block layers format.
  - **Polytone:** custom lightmaps, colormaps and sky colors. Its "PolyLight" adds colored and dynamic block lights *without shaders*, which may be useful for a no-shader fallback tier.
  - **LambDynamicLights / Sodium Dynamic Lights:** held-item light.
  - **CIT:** CIT Resewn has been stale since 2024. Alternatives: CIT Resewn Continuation (26.1), CIT Reborn, InexactCIT, and vanilla item-model `select` on `custom_name` (1.21.4+), which is "far from true CIT". **[secondary]**

### 2.2 How resource packs combine with shaders (LabPBR 1.3) [verified, shaderLABS wiki]

- **`_n` texture (normal map).**
  - R and G hold the normal's X and Y in DirectX (Y−) convention. Z is reconstructed by the shader.
  - B holds AO, linear (0 = fully occluded).
  - A holds height for POM (0 = 25% depth, 255 = surface). Artists should use a minimum of 1.
- **`_s` texture (specular map).**
  - R is perceptual smoothness; roughness = (1 − s)².
  - G is F0 for values 0 to 229 (linear since 1.3). Values 230 to 237 select predefined metals (Iron, Gold, Aluminum, Chrome, Copper, Lead, Platinum, Silver); 255 means albedo is used as F0.
  - B is porosity for 0 to 64 and SSS for 65 to 255.
  - A is emission for 0 to 254; 255 means no emission.
- **Shader side.**
  - Iris exposes `normals`/`specular` samplers and the `MC_NORMAL_MAP`/`MC_SPECULAR_MAP` macros.
  - Packs usually offer a mode switch. Complementary's `RP_MODE` is Basic, Integrated PBR+, seuspbr or labPBR. Solas's "PBR Resourcepack" toggle accepts LabPBR or OldPBR.
  - Without a PBR pack, the best packs fall back to **hardcoded materials by block ID** (`block.properties`), such as glowing ores, metal blocks and water.
- **Practical combos players use.**
  - Patrix or LabPBR packs with Photon, Complementary (labPBR mode) or Solas.
  - Fresh Animations (EMF+ETF) with any shader. ETF emissive textures work, but "support varies depending on the shader".
  - Iris fixed a bug where the PBR atlas fill was skipped on first load, which broke normals until F3+T (2026-10-03 commit). Retest normal maps on Iris 1.11.7+. **[verified, commit]**

---

## 3. What players say is missing or broken

### 3.1 Caves and nights too dark (the most common visual complaint)

- CurseForge pack "Slightly Brighter Caves": "Many Shader Packs are extremely dark at the low end of the brightness spectrum". The fix is a custom lightmap. [link](https://www.curseforge.com/minecraft/texture-packs/slightly-brighter-caves-optifine) **[verified]**
- Mojang feedback titles: "Dark Areas being too Dark with Vibrant Visuals On", "The Nether and End Dimensions need more Ambient Lighting", "Darkness especially in caves". These are Bedrock Vibrant Visuals, but they show the same preference. **[titles verified via URLs; pages returned 403]**
- Reddit thread titles surfaced by search: "Why my world seems dark with shaders" (r/Minecraft, Jun 2025) and "Help with light sources configuration" (r/minecraftshaders). **[search index only]**
- A 2019 SEUS PTGI write-up found underground building hard because SEUS lacked handheld torch lighting. **[verified, realtimerendering.com]**
- Counter-signal: "More Darkness" is a popular mod that makes unlit caves pitch black. **Players want a choice**, so expose a cave-brightness floor slider (as Complementary's `CAVE_LIGHTING` does) and keep handheld light on by default.
- Minecraft 1.21.9 changed night vision to always use "moody" lighting, and fix packs appeared. **[secondary]**

### 3.2 macOS / Apple Silicon

- Bliss: "Apple hates openGL." DH + Bliss "will not work on Apple devices" (#181). [link](https://github.com/X0nk/Bliss-Shader/issues/181) **[verified]**
- Photon on Mac (#325): "Implementation limit of 16 active fragment shader samplers… exceeded". A later report crashes on `glClearTexImage` (GL 4.4) when Iris creates custom images. The Photon page states "NOT compatible with Apple GPUs". [link](https://github.com/sixthsurge/photon/issues/325) **[verified]**
- Solas (#168, M5 MacBook Pro): "Solas seems to be broken on Apple Silicon", while "Mellow work[s]". [link](https://github.com/Septonious/Solas-Shader/issues/168) **[verified]**
- Complementary hard-disables colored lighting on Mac (`MC_OS_MAC`). Voxy requires OpenGL 4.6, and Photonics says "MacOS is not supported!" **[verified]**
- MacRumors (2023): about 75 FPS without shaders, but 10 to 20 with shaders "as the macbook gets hot." Another user reports "115 fps at 1440p with shaders on MBP M1 Max!" [link](https://forums.macrumors.com/threads/do-you-think-macbooks-will-ever-be-able-to-play-minecraft-with-shaders-on.2389864/) **[verified]**
- Reddit thread "Shaders lower FPS substantially (Mac Air)". Search snippets say commenters recommended Mellow, and one said Potato Shaders "runs worse than many other popular shaderpacks". **[search index only]**
- Iris 1.11.6+26.3: "Fixes a bug stopping macOS from loading." **[verified]**
- **Design constraints for a Mac-safe path:**
  - No compute shaders, no SSBOs, no `image*` load/store (so no flood-fill colored light or voxel reflections).
  - At most 16 samplers per fragment program.
  - Gate features with `#ifdef MC_OS_MAC` and `MC_GL_RENDERER_APPLE`, both of which Iris defines.
  - Test that the pack compiles with all options on the Mac profile.

### 3.3 Low-end PCs and laptops: heat, battery, iGPUs

- Common advice in threads: cap FPS (uncapped makes a laptop "work at 100%"), make sure the game uses the dGPU rather than the iGPU, and lower render distance. **[secondary]**
- Packs players recommend for weak hardware: MakeUp Ultra Fast (tiny cost per effect), Miniature, RenderPearl, Mellow, Sildur's Lite / Enhanced Default, Complementary Potato and I Like Vanilla Potato. Potato Shaders gets mixed reviews. **[verified / search index]**
- Missing feature people ask for: a pack that **picks settings for you** and holds a frame-rate target. No mainstream shader pack does this today. Separate mods (LoadShift, Adaptive Graphics, Addoners Optimizer, Swift) adjust settings around the pack instead (§4.4). **[verified via Modrinth listings]**

### 3.4 Stutter and VRAM

- Iris #2172: after toggling and reloading packs, "my gpu memory fills up", and FPS drops to 30-60 until restart. Avoid frequent reloads in an auto-quality system. [link](https://github.com/IrisShaders/Iris/issues/2172) **[verified]**
- Shader reloads cause a multi-second compile hitch. LoadShift claims background compilation reduces a profile switch to about 0.4 s. **[vendor claim, unverified]**
- Distant Horizons' FAQ section "My game is stuttering, help!" recommends ZGC, more RAM, and lowering DH CPU load. **[verified]**
- Linux report: GPU utilization drops to 30-40% when chunks load with Sodium + Iris. **[secondary, cachyos forum]**

### 3.5 Distant Horizons / Voxy integration

- Photon #265: "Border Fog does not work in Distant Horizons LOD chunks".
- Photon #463: "Water is disappearing with voxy".
- Solas #178: water cut-off with Voxy.
- Bliss #418: "Add Fade-in support for DH chunks to prevent pop-in."
- Photon #667: black screen with DH 3.3.2 (reverse-Z) on 26.3.
- **[all verified, GitHub]**
- Forum threads also report shadows and lighting cutting off at a distance, fog on LODs vanishing after opening a menu, LOD water "hall of mirrors", and over-bright leaves at sunrise with DH. **[secondary, answeroverflow/GitLab]**
- Takeaway: LOD water, fog continuity and the transition band are where packs fail. Make these first-class, tested features.

### 3.6 Modded content (relevant for a modpack)

- Create/Flywheel: "severe and unplayable FPS hit with the create mod running" (Iris #1414, 2022). Solas #172 (2026): "Not rendering moving create parts". **[verified]**
- The current fix is **Colorwheel**, which officially supports Complementary r5.7+, Photon v1.4, Solas 3.2+, I Like Vanilla, RenderPearl, Euphoria and others. Its page says Iris Flywheel Compat 2.0 "is invasive and broken". **[verified, Colorwheel page]**
- Emissive and colored light for modded blocks needs `block.properties` entries. Recent Solas PRs add GeOre and MC Braziers support one by one. Euphoria lets users add modded blocks.

### 3.7 Weather, water, foliage, Nether/End

- Rain realism:
  - Photon #435: "Blocky clouds during rain (no sky coverage) *IMMERSION BREAKING*".
  - Bliss #515: "its weird when the sky is clearer when its raining".
  - **[verified]**
- Water readability: Bedrock Vibrant Visuals feedback says water is "way too transparent from above". A search snippet from r/minecraftshaders reads "you'll never know if there's water or not". **[secondary / search index]**
  - Lesson: keep water clearly visible and tinted, especially at night.
- Foliage: Complementary's "No Waving Indoors" option suggests players dislike plants waving inside enclosed rooms. LOD foliage brightness with DH is a known problem. **[verified option text; secondary DH]**
- Nether/End: horror packs deliberately make them darker. Edits such as Aetheria, Crystal, Spooklementary and Solas compete on richer Nether fog, End nebulae and black holes. This suggests players find the default Nether and End visuals the least developed. **[inference, unverified]**

---

## 4. Adaptive quality through Iris

### 4.1 What packs can expose (OptiFine/Iris format) [verified]

- **Options.** Options are `#define` lines in GLSL. Boolean, enum and slider options are parsed from comments.
- **`shaders.properties` keys.**
  - `profile.NAME = OPT=value !BOOL …`: one-click presets. A profile can include another profile. The current profile is detected from the selected values and shown as "Custom" otherwise.
  - `screen=` and `screen.NAME=`: menu trees, with `<profile>` and `<empty>` placeholders and `screen.columns`.
  - `sliders=`: which options render as sliders.
  - `program.<world>/<program>.enabled = OPTION`: compile out whole passes. Complementary disables the shadow pass at shadow quality −1. Photon disables `composite3` unless TAAU is on.
  - `size.buffer.colortexN = 0.5 0.5`: fixed resolution scale per buffer.
  - `iris.features.required/optional`: CUSTOM_IMAGES, SSBO, COMPUTE_SHADERS and others. Each enabled feature defines an `IRIS_FEATURE_*` macro.
  - `uniform.*` / `variable.*`: CPU-evaluated custom uniforms with `smooth()`, `if()`, `clamp()` and similar.
- **Hardware macros Iris defines.** `MC_OS_MAC/WINDOWS/LINUX`, `MC_GL_VENDOR_NVIDIA/AMD/INTEL/…`, `MC_GL_RENDERER_APPLE/INTEL/RADEON/GEFORCE/MESA/…`, `MC_GL_VERSION`, `MC_GLSL_VERSION`, `IS_IRIS`, `IRIS_VERSION`, `DISTANT_HORIZONS`. `MC_RENDER_QUALITY` and `MC_SHADOW_QUALITY` are hard-coded to 1.0 in Iris, so internal resolution scaling is the pack's job, as in Photon's TAAU. **[verified, StandardMacros.java]**
- **In-game menu.** Iris's shader options screen has **import/export settings buttons**, and Iris's own video settings include a shadow render distance slider (0 to 32 chunks, unless the pack forces it). **[verified, source]**

### 4.2 What mods can change at runtime

**Public API: `net.irisshaders.iris.api.v0.IrisApi`** (26.3 branch) **[verified]**

| Method | Since | Use |
|---|---|---|
| `getInstance()` | v0.0 | Entry point |
| `getMinorApiRevision()` | v0.0 | The doc comment says "revision 2", but methods marked v0.3/v0.4 exist. Check at runtime |
| `isShaderPackInUse()` | v0.0 | False if no pack is loaded or the pack failed to compile |
| `isRenderingShadowPass()` | v0.0 | Culling workarounds |
| `openMainIrisScreenObj(parent)`, `getMainScreenLanguageKey()` | v0.0 | Open the shader picker |
| `getConfig().areShadersEnabled()`, `getConfig().setShadersEnabledAndApply(boolean)` | v0.0 | **The only official runtime toggle.** Turns shaders on or off and reloads |
| `createTextVertexSink(...)` | v0.1 | Text rendering |
| `getSunPathRotation()` | v0.2 | Read the pack's sun angle |
| `assignPipeline(RenderPipeline, IrisProgram)` | v0.3 | Map a mod's render pipeline to an Iris program |
| `assignPipelineShadow(...)`, `registerShadowRenderCallback(...)` | v0.4 | Shadow pass integration |
| `isReverseZDuringShaders()` | (26.x) | Depth convention |
| `IrisItemLightProvider` (api/v0/item) | n/a | Held-item light per item |

**There is no public API to set individual shader options or switch packs.** The available mechanisms, all **[verified in `Iris.java` / `IrisConfig.java`, 26.3 branch]**, are internal and may change between versions:

- **Options file.** Iris reads `shaderpacks/<exact pack file or folder name>.txt`, for example `AFK-Shaders.zip.txt`. It is a Java `Properties` file of option overrides. On load, Iris merges any queued overrides, compiles the pack, then **rewrites the file with only non-default values, deleting it if none remain**.
- **Global config: `config/iris.properties`.** Keys:
  - `shaderPack` and `enableShaders`
  - `allowUnknownShaders`, `enableDebugOptions`, `disableUpdateMessage`
  - `maxShadowRenderDistance` and `colorSpace`
- **Internal static calls.**
  - `Iris.getShaderPackOptionQueue().put(k, v)` and `Iris.queueShaderPackOptionsFromProfile(profile)` / `queueShaderPackOptionsFromProperties(props)`.
  - `Iris.resetShaderPackOptionsOnNextReload()`.
  - `Iris.getIrisConfig().setShaderPackName(name)` followed by `save()`.
  - `Iris.reload()`, which re-reads the config, destroys all GL resources, reloads the pack and re-creates the pipeline. **Any option change needs a full reload and recompile.**
- **Load timing.** Iris loads the pack at render-system init. A companion mod can therefore write the `.txt` in its client initializer so the first load already uses the chosen tier. **[inference: check entrypoint ordering on 26.3]**
  - GPU detection needs a GL context, so on first run either pick a safe default or do one reload at the title screen and cache the result.
- **Existing mods that use these hooks:** Shader Profiles (MIT, save and switch option sets), Dimension Based Shaders (GPL, one pack per dimension, reloads), and LoadShift (switches a pack's quality profiles toward an FPS target). **[verified listings]**

### 4.3 Practical strategy to stay at or above 24 FPS automatically

This is a layered design. Each layer is cheaper and faster than the one after it.

1. **Compile-time defaults (no runtime cost).**
   - Ship conservative defaults.
   - Clamp tiers using `MC_OS_MAC`, `MC_GL_RENDERER_INTEL` and `MC_GL_VENDOR_*` inside the pack, so a Mac never compiles the voxel or compute paths.
   - Design the pack around **one master option**, e.g. `AFK_TIER = 0…5`, from which most defines derive. Expert overrides should stay possible. A switch then means changing one key in the `.txt`, not dozens.
2. **Per-frame continuous scaling inside the shader (no reload).**
   - Define a custom uniform such as `uniform.float.afkPerf = smooth(10, clamp(0.0333 / max(frameTime, 0.001), 0.25, 1.0), 2, 8)`. This uses the same pattern as Complementary's `frameTimeSmooth = smooth(5, frameTime, 5, 5)`. Pick asymmetric halflives so quality drops fast and recovers slowly.
   - Use `afkPerf` to scale:
     - ray-march step counts (volumetric light, fog, clouds, SSR)
     - AO and GI sample counts
     - effect distances
     - the active viewport fraction of half-res effect buffers (dynamic resolution for expensive passes, upsampled with TAA)
   - Quantize it to 3 or 4 levels to avoid visible shimmer.
   - This is the only way to get continuous adaptation under Iris without hitches. **[design inference; `smooth()` and frameTime usage verified in Complementary]**
   - The main gbuffer and shadow-map resolution cannot change without a reload. The scalable budget is in composite passes, which is where volumetrics, clouds, SSR and AO live.
3. **Seconds-scale, non-shader levers (no reload), from a companion mod.**
   - Adjust render distance, entity distance, particles, cloud range, Iris shadow render distance and DH/Voxy LOD distance.
   - Whether the Iris shadow distance applies live without a reload is **[unverified]**.
   - Skip changes during combat or elytra flight. LoadShift's "never at a dangerous moment" rules are a good model.
4. **Minutes-scale tier switching (reload).**
   - Only when 1% lows stay below target for about 10 s (drop a tier) or comfortably above for about 60 s (raise a tier). Rate-limit switches.
   - Only at safe moments: pause menu, dimension change, death or loading screens.
   - Persist the last stable tier per machine.
   - Minimize reloads because of Iris #2172 VRAM growth.
5. **Thermal and AFK mode.**
   - Cap FPS by default, e.g. 60.
   - Add **Dynamic FPS** (72M downloads), which reduces frame rate when the window is unfocused, idle or on battery. That fits a modpack literally named AFK.
   - Optionally drop the shader to a cheap tier when the player has been idle for N minutes.

### 4.4 Existing auto-quality mods (for reference, not dependencies) [verified listings]

- **LoadShift** (ARR, 26.3, created Nov 2026, 9 downloads).
  - Sets an FPS target and detects whether the CPU or GPU is the bottleneck.
  - Switches shader profiles with "Seamless shader reload" (background compile) and adjusts render distance and more.
  - Avoids changes during combat.
  - Very new, so treat its claims as **[unverified]**.
- **Adaptive Graphics:** adjusts render, simulation and entity distance, clouds and biome blend by priority.
- **Lag:** adaptive render distance plus an FPS HUD.
- **Swift** and **Addoners Optimizer** do similar adjustments.
- **Upscalers.** FSR Reloaded (MIT, FSR 1-4) offers whole-frame upscaling, and its compatibility with Iris packs is unverified. A NeoForge project (`minizyz/superesrmod`) claims DLSS/FSR with Sodium and Iris. Inside a pack, TAAU (Photon, Kappa) is the proven route.

---

## 5. Recommendation: the "best of all" pack for AFK

### 5.1 Features ranked by impact versus cost

Scores are from 1 to 5. Impact is visual and gameplay value; cost is the GPU cost on mid-range hardware; dev effort is engineering time. The order favours high impact and low cost first.

| # | Feature | Impact | GPU cost | Dev effort | Mac-safe | Best existing reference |
|---|---|---|---|---|---|---|
| 1 | Tonemap, exposure and color grading (AgX-like), with a brightness floor for caves and night (slider) | 5 | 1 | 1 | yes | Complementary r5.6 tonemap + `CAVE_LIGHTING`; Bliss AgX |
| 2 | Sun shadows: distorted shadow map, soft PCF, cave light-leak prevention, entity/leaf shadow toggles | 5 | 3 | 3 | yes | Complementary; Solas; Photon (VPS for high tier) |
| 3 | Sky, atmosphere and distance fog that hides chunk borders and DH/Voxy seams, plus cave and underwater fog | 5 | 1 | 2 | yes | Photon (sky); Complementary (border/cave fog) |
| 4 | Water: waves (normals + vertex), refraction, absorption tint that stays visible, SSR with sky fallback, caustics on high tier | 5 | 2 | 3 | yes | Complementary; Photon; Solas |
| 5 | Waving foliage, leaves and lava; no waving indoors | 4 | 1 | 1 | yes | Complementary |
| 6 | Hardcoded materials (integrated PBR: emissive ores and lamps, metals, glass) + bloom | 4 | 1 | 2 | yes | Complementary IPBR+; Solas |
| 7 | TAA (+ optional TAAU / dynamic resolution for composite passes) + CAS | 4 | 1 | 3 | yes | Photon (TAAU+CAS); Kappa |
| 8 | Volumetric light shafts and fog (half-res, dithered, step count driven by perf uniform) | 4 | 3 | 3 | yes | Complementary; Solas |
| 9 | Clouds: vanilla-style volumetric (cheap) and realistic 3D layered (high tier), with rain that actually covers the sky | 4 | 3 | 3 | yes | Nostalgia/Complementary Reimagined; Photon/Bliss/Solas |
| 10 | SSAO or GTAO | 3 | 2 | 2 | yes | Photon GTAO |
| 11 | Distant Horizons + Voxy support (LOD shading, fog continuity, LOD water, fade-in, reverse-Z) | 4 (DH users) | 2 | 4 | DH yes / Voxy no (GL 4.6) | Complementary r5.8; Photon v1.3+ |
| 12 | LabPBR 1.3 support (normals, specular, emission, optional POM) | 3 | 2 | 2 | yes | Photon; RenderPearl; Solas |
| 13 | Voxel flood-fill colored block light (non-Mac, high tier only, small volume) | 4 | 3 (VRAM heavy) | 4 | **no** | Complementary ACT; Photon; Solas |
| 14 | Nether and End atmospheres (biome-tinted Nether fog/smoke, End nebula/beams) | 3 | 2 | 2 | yes | Solas; Complementary |
| 15 | Weather variety (per-day cloud and fog parameters) | 3 | 1 | 2 | yes | Photon; Kappa |
| 16 | Mod compatibility: Colorwheel (Create), modded block IDs, ETF emissives, held-item light (`IrisItemLightProvider`) | 4 for a modpack | 0 | 2 | yes | Complementary/Photon (Colorwheel); Euphoria (modded blocks) |
| 17 | Off-screen / world-space reflections | 2 | 5 | 5 | no | Complementary WSR |
| 18 | Path-traced GI / SSPT, or Photonics integration | 3 | 5 | 5 | no | Kappa SSPT; SEUS PTGI; Photonics |

Supporting systems (not visual, but required):

- Tiered profiles from Potato to Ultra plus a master `AFK_TIER` option.
- A `MC_OS_MAC` clamp.
- The `afkPerf` uniform.
- A companion Fabric mod that writes `shaderpacks/<pack>.txt`, picks the tier on first run, and handles tier changes at safe moments (§4.3).

### 5.2 Licensing notes (not legal advice)

| Pack | Can a modpack ship it? | Can AFK modify or redistribute it? |
|---|---|---|
| **Complementary** (License Agreement 1.7) **[verified]** | Yes, if all of these hold: (a) credited on the modpack page if enabled by default, (b) the modpack author takes responsibility, (c) "contents are not modified in any way, including simple variable changes", (d) added through Modrinth/CurseForge's normal system, not uploaded as a file | Modified packs need: a credit plus link, the unchanged license file, a name without "Complementary" that differs by at least 3 letters, a **noticeably different look** in common scenes, no other license, and "significant" new human-written code. Anything else is All Rights Reserved |
| **Photon** (Photon Shaders License Agreement, © 2021-2025 Benjamin Stott) **[verified]** | Yes, including in a "(commercial) modpack" | Allowed: use, modify "for personal use", study, and redistribute modified or unmodified portions. Not allowed without written permission: posting it or derivatives on platforms that pay creators (Modrinth, CurseForge named), and selling copies. The license text must be included. Whether a public modified fork on GitHub is allowed is ambiguous, so ask sixthsurge first |
| **BSL** (ARR + agreement) **[verified]** | Not addressed. A modpack manifest that references the official Modrinth file is not a redistribution of the file **[interpretation]** | Modify for personal use. Publish a "heavily modified version" only with permission. No redistribution without permission. Do not claim or rename it |
| Solas, Bliss, Kappa, Nostalgia, AstraLex, Shrimple | ARR: reference only | Ask the authors |
| Rethinking Voxels | Complementary Agreement | Same rules as Complementary |
| Super Duper Vanilla (FlameRender 1.6) | Yes, with a license copy and credit | Modified versions allowed under the same license, without "Super Duper Vanilla" in the name, with credit. Settings-only changes do not count as a "modified version" |
| Insanity / Hysteria (AGNYA) | Link only ("don't distribute the files") | Ask |
| I Like Vanilla (What42's License 2.3) | Allowed with restrictions | Public modifications allowed with restrictions. Significant default-setting changes count as an altered version |
| **MakeUp Ultra Fast** | Yes | **LGPL-3.0-or-later**. Forking and modifying are allowed; modified LGPL code stays LGPL. Credit requested |
| **Mellow**, **Miniature**, **RenderPearl**, Arc | Yes | **MIT**: keep the copyright notice |
| **Noble** | Yes | **GPL-3.0**: a derivative must be GPL-3.0 |
| Bloop | Yes, non-commercial | CC-BY-NC-SA-4.0 |

Notes on licensing:

- **Distributing Complementary with custom default settings.** Shipping a separate `shaderpacks/<Complementary>.zip.txt` options file leaves the zip unchanged, but the license's broad "simple variable changes" wording might still be read to cover it. **[interpretation: get written confirmation from Complementary Development before relying on it]**
- **Euphoria Patches** shows a sanctioned add-on model: a separate MPL-2.0 patcher mod adds features to Complementary. How it handles redistribution is **[unverified]**.
- **Practical path for AFK.**
  - Use Complementary (unmodified, via Modrinth reference) or Photon as the default bundled packs.
  - Build the custom AFK pack either from scratch or on top of permissively licensed code (MakeUp LGPL, Mellow/Miniature/RenderPearl MIT, Noble GPL).
  - Study Complementary, Photon and BSL techniques without copying their code.
- **Resource packs.** Faithful allows use with credit and a link. Patrix, Stay True, Mizuno and Fresh Animations have their own terms, so reference them through the modpack manifest rather than re-hosting them.

---

## 6. Sources

### Shader packs
- Modrinth API search (downloads, licenses): https://api.modrinth.com/v2/search?facets=[["project_type:shader"]]&index=downloads
- Complementary Reimagined: https://modrinth.com/shader/complementary-reimagined
- Complementary Unbound: https://modrinth.com/shader/complementary-unbound
- Complementary site and changelogs: https://www.complementary.dev/shaders/ , https://www.complementary.dev/changelogs/
- Complementary source, license and options: https://github.com/ComplementaryDevelopment/ComplementaryReimagined , https://raw.githubusercontent.com/ComplementaryDevelopment/ComplementaryReimagined/main/License.txt , https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/shaders.properties , https://github.com/ComplementaryDevelopment/ComplementaryReimagined/blob/main/shaders/lang/en_US.lang
- Photon: https://modrinth.com/shader/photon-shader , https://github.com/sixthsurge/photon , https://github.com/sixthsurge/photon/blob/main/LICENSE , https://github.com/sixthsurge/photon/blob/main/shaders/shaders.properties
- BSL: https://modrinth.com/shader/bsl-shaders , https://capttatsu.com/bslshaders/ , https://www.curseforge.com/minecraft/shaders/bsl-shaders/files/6514910 , https://minecraftshader.com/?p=15042
- Solas: https://modrinth.com/shader/solas-shader , https://github.com/Septonious/Solas-Shader
- Bliss: https://modrinth.com/shader/bliss-shader , https://github.com/X0nk/Bliss-Shader
- Rethinking Voxels: https://modrinth.com/shader/rethinking-voxels , https://github.com/gri573/rethinking-voxels
- MakeUp Ultra Fast: https://modrinth.com/shader/makeup-ultra-fast-shaders , https://github.com/javiergcim/MakeUpUltraFast
- Super Duper Vanilla: https://modrinth.com/shader/super-duper-vanilla , https://github.com/Eldeston/Super-Duper-Vanilla
- Kappa / Nostalgia / RRe36 blog: https://modrinth.com/shader/kappa-shader , https://modrinth.com/shader/nostalgia-shader , https://rre36.com/blog , https://rre36.com/blog/development-update-august-2024
- Insanity / Hysteria: https://modrinth.com/shader/insanity-shader , https://modrinth.com/shader/hysteria-shaders
- Mellow, Noble, Miniature, RenderPearl, I Like Vanilla (incl. benchmark), Visual Vibrance: https://modrinth.com/shader/mellow , https://modrinth.com/shader/noble , https://modrinth.com/shader/miniature-shader , https://modrinth.com/shader/renderpearl , https://modrinth.com/shader/i-like-vanilla , https://github.com/What42Pizza/I-Like-Vanilla/blob/main/LICENSE , https://modrinth.com/shader/visual-vibrance
- Euphoria Patches: https://modrinth.com/mod/euphoria-patches
- SEUS PTGI HRR: https://www.patreon.com/posts/60268558 , https://shaderpacks.net/seus-ptgi-hrr , https://www.realtimerendering.com/blog/?p=5083
- Sildur's: https://sildurs-shaders.github.io/ , https://texture-packs.com/shaders/sildurs-vibrant/
- Chocapic13: https://www.curseforge.com/minecraft/shaders/chocapic13-shaders
- Pack roundups (secondary): https://space-node.net/blog/best-shader-packs-sodium-iris-2026 , https://gdlauncher.com/blog/best-shaders-2026/ , https://beebom.com/best-minecraft-shaders/ , https://nutnut17.github.io/nut17-notes/minecraft/technotes/shaders

### Iris, Sodium, Vulkan, LOD
- Iris API (26.3 branch): https://github.com/IrisShaders/Iris/blob/26.3/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java , https://github.com/IrisShaders/Iris/blob/26.3/common/src/api/java/net/irisshaders/iris/api/v0/IrisApiConfig.java
- Iris internals: https://github.com/IrisShaders/Iris/blob/26.3/common/src/main/java/net/irisshaders/iris/Iris.java , https://github.com/IrisShaders/Iris/blob/26.3/common/src/main/java/net/irisshaders/iris/config/IrisConfig.java , https://github.com/IrisShaders/Iris/blob/26.3/common/src/main/java/net/irisshaders/iris/features/FeatureFlags.java , https://github.com/IrisShaders/Iris/blob/26.3/common/src/main/java/net/irisshaders/iris/gl/shader/StandardMacros.java , https://github.com/IrisShaders/Iris/blob/26.3/common/src/main/java/net/irisshaders/iris/gui/element/ShaderPackOptionList.java
- Iris branches and commits (Vulkan work): https://github.com/IrisShaders/Iris/commits/26.3-new , https://github.com/IrisShaders/Iris/commits/26.3
- Iris on Modrinth (changelogs): https://modrinth.com/mod/iris
- Iris limitations doc: https://github.com/IrisShaders/Iris/blob/1.20.3/docs/usage/limitations.md
- Iris macOS discussion: https://github.com/orgs/IrisShaders/discussions/2366
- Custom uniforms reference: https://shaders.properties/current/reference/shadersproperties/custom_uniforms/
- OptiFine options screen and profiles docs: https://optifine.readthedocs.io/shaders_dev/options.html
- Sodium: https://modrinth.com/mod/sodium
- Vitrail: https://modrinth.com/mod/vitrail-shaders ; iris-vulkan (archived): https://github.com/laurensanp/iris-vulkan
- Mojang Vulkan post: https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition ; coverage: https://www.gamingonlinux.com/2026/02/minecraft-java-is-switching-from-opengl-to-vulkan-for-the-vibrant-visuals-update , https://www.neowin.net/news/minecraft-java-edition-is-upgrading-to-vulkan-but-its-not-great-news-for-mods/ ; Vibrant Visuals on Java: https://www.minecraft.net/en-us/article/vibrant-visuals-java-edition
- Distant Horizons: https://modrinth.com/mod/distanthorizons , https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/972 ; Voxy: https://modrinth.com/mod/voxy ; Photonics: https://modrinth.com/mod/photonics

### Resource packs and related mods
- LabPBR: https://shaderlabs.org/wiki/LabPBR_Material_Standard
- Fresh Animations, EMF, ETF: https://modrinth.com/resourcepack/fresh-animations , https://modrinth.com/mod/entity-model-features , https://modrinth.com/mod/entitytexturefeatures
- Faithful: https://modrinth.com/resourcepack/faithful-32x , https://faithfulpack.net/license
- Patrix: https://modrinth.com/resourcepack/patrix-32x
- Stay True: https://www.curseforge.com/minecraft/texture-packs/stay-true , https://anvilpacks.com/resourcepacks/stay-true/
- Mizuno's 16 Craft: https://resourcepack.net/mizunos-16-craft-resource-pack/ , https://modrinth.com/resourcepack/modern-mizunos
- LabPBR packs: https://modrinth.com/resourcepack/roundista , https://modrinth.com/resourcepack/vanilla-mashup-pbr
- Continuity, Polytone, CIT: https://modrinth.com/mod/continuity , https://modrinth.com/mod/polytone , https://modrinth.com/mod/cit-resewn , https://modrinth.com/project/8auYYPlH , https://modrinth.com/mod/inexactcit
- Colorwheel: https://modrinth.com/mod/colorwheel

### Player complaints and wishlists
- Slightly Brighter Caves: https://www.curseforge.com/minecraft/texture-packs/slightly-brighter-caves-optifine
- More Darkness: https://modrinth.com/mod/more-darkness
- MacRumors thread: https://forums.macrumors.com/threads/do-you-think-macbooks-will-ever-be-able-to-play-minecraft-with-shaders-on.2389864/
- GitHub issues:
  - https://github.com/X0nk/Bliss-Shader/issues/181
  - https://github.com/X0nk/Bliss-Shader/issues/418
  - https://github.com/X0nk/Bliss-Shader/issues/515
  - https://github.com/sixthsurge/photon/issues/325
  - https://github.com/sixthsurge/photon/issues/265
  - https://github.com/sixthsurge/photon/issues/435
  - https://github.com/sixthsurge/photon/issues/463
  - https://github.com/sixthsurge/photon/issues/667
  - https://github.com/Septonious/Solas-Shader/issues/168
  - https://github.com/Septonious/Solas-Shader/issues/172
  - https://github.com/Septonious/Solas-Shader/issues/178
  - https://github.com/IrisShaders/Iris/issues/1414
  - https://github.com/IrisShaders/Iris/issues/2172
- Mojang feedback (titles):
  - https://feedback.minecraft.net/hc/en-us/community/posts/35621987051405-Dark-Areas-being-too-Dark-with-Vibrant-Visuals-On
  - https://feedback.minecraft.net/hc/en-us/community/posts/37374632677901-Vibrant-Visuals-The-Nether-and-End-Dimensions-need-more-Ambient-Lighting
  - https://feedback.minecraft.net/hc/en-us/community/posts/360053923371-Darkness-especially-in-caves
  - https://feedback.minecraft.net/hc/en-us/community/posts/35551829624077
- Reddit threads (seen only through the search index; not fetched):
  - https://www.reddit.com/r/minecraftshaders/comments/1l6itkq/shaders_lower_fps_substantially_mac_air/
  - https://www.reddit.com/r/minecraftshaders/comments/1kb69mx/using_sildurs_enhanced_default_and_not_liking_how/
  - https://www.reddit.com/r/Minecraft/comments/1l2cm98/why_my_world_seems_dark_with_shaders/
  - https://www.reddit.com/r/minecraftshaders/comments/1l6um2z/help_with_light_sources_configuration/
- DH/shader forum threads (secondary): https://www.answeroverflow.com/m/1303848947244204227 , https://www.answeroverflow.com/m/1409104410461864016 ; Linux stutter: https://discuss.cachyos.org/t/minecraft-with-shaders-bad-performance/1317

### Auto-quality and related mods
- https://modrinth.com/mod/loadshift
- https://modrinth.com/mod/adaptive-graphics
- https://modrinth.com/mod/lag
- https://modrinth.com/mod/addoners-optimizers
- https://www.curseforge.com/minecraft/mc-mods/swift
- https://modrinth.com/mod/dynamic-fps
- https://modrinth.com/mod/shader-profiles
- https://modrinth.com/mod/dimension-based-shaders
- https://modrinth.com/mod/fsr-reloaded
- https://github.com/minizyz/superesrmod
