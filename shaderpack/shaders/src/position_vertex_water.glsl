vec4 viewSpacePos4D = gl_ModelViewMatrix * gl_Vertex;
fragposition = viewSpacePos4D.xyz;
vec4 position = gbufferModelViewInverse * viewSpacePos4D;
worldposition = position + vec4(cameraPosition.xyz, 0.0);
gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;

#if WAVING == 1
    // AFK: gentle vertex waves on water surfaces (top faces only). The surface only ever moves
    // down, so it never pokes through the shore; waves grow a little in rain.
    if (mc_Entity.x == ENTITY_WATER && gl_Normal.y > 0.5) {
        vec2 wavePos = worldposition.xz;
        float waveTime = frameTimeCounter;
        float wave = sin(wavePos.x * 0.7 + waveTime * 1.6) * cos(wavePos.y * 0.55 + waveTime * 1.25)
            + sin((wavePos.x + wavePos.y) * 0.35 + waveTime * 0.9) * 0.5;
        float waveDrop = (wave * 0.333 - 1.0) * 0.035 * (1.0 + rainStrength);
        gl_Position += gl_ModelViewProjectionMatrix * vec4(0.0, waveDrop, 0.0, 0.0);
    }
#endif
gl_FogFragCoord = length(viewSpacePos4D.xyz);


#if AA_TYPE > 1
    gl_Position.xy += taaOffset * gl_Position.w;
#endif

#if defined SHADOW_CASTING && SHADOW_LOCK > 0 && !defined NETHER
    vWorldPos = position.xyz;
#endif
