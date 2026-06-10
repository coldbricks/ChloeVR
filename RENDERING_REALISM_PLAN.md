# ChloeVR Rendering Realism Plan

**Target:** "Realism AR" — AI-generated GLB dancers performing in the user's real room via passthrough. Samsung Galaxy XR (flavor `galaxyxr`) + Meta Quest 3 (flavor `quest`).
**Source:** 29-agent rendering audit, 2026-06-10. 16 recommendations, each adversarially verified against the actual codebase (file:line evidence) — verdicts: 15 confirmed, 1 partially-implemented (R3).
**Methodology:** One recommendation at a time. Build after every change. Verify on hardware via adb logcat. Each broken APK costs 5+ minutes of sideloading.
**Input model:** Controllers only. No hand-tracking features anywhere in this plan.

---

## Current State (audited 2026-06-10)

The renderer is a hand-rolled GLES 3.0 forward pipeline (`GlesModelRenderer.kt`, ~3100 lines, single uber-PBR shader) fed by native OpenXR (`xr_renderer.cpp`). Key audited facts:

- **Color:** OpenXR swapchain is GL_SRGB8_ALPHA8 (xr_renderer.cpp:504-516, :1235) — correct — but ALL textures upload with default linear internalformat (`GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)` at GlesModelRenderer.kt:980, :1005) and the shader decodes with approximate `pow(2.2)` (:2842, :2893). Mips are generated and filtered in gamma space — minified skin darkens at VR viewing distances.
- **BRDF:** Cook-Torrance GGX, but the fill light's specular reuses the KEY light's F and G terms with an ad-hoc `* 0.5` fudge (:2873-2875) — a real bug, not a stylistic choice.
- **IBL:** L2 spherical harmonics only. Live SH from XR_ANDROID_light_estimation on Galaxy XR; on Quest the SH path never activates (no light estimation extension) so ambient falls to a hard-coded hemisphere (0.02-0.08 constants near :2888) with **literally zero env specular** — no prefiltered specular env map exists anywhere. Eye catchlights are currently impossible.
- **Shadows:** 2048×2048 R32F color-texture shadow map rendered every frame with skinning (FilamentModelActivity.kt:3994) — but ONLY the floor grid and room planes sample it (GRID/SP shaders). The main model fragment shader (:2799-2913) samples no shadow map. **The dancer neither receives nor self-casts shadows.** No occlusion under chin/breast/arms = flat game-character look.
- **Normal mapping:** Rigged (Tripo) dancers have normal mapping FORCE-DISABLED at load (GlesModelRenderer.kt:1310-1314 sets `model.hasNormalMap = false` for skinned meshes) because the tangent-generating unweld path never replicated JOINTS_0/WEIGHTS_0. The shader-side skinned-TBN support ALREADY EXISTS (:2759-2766); a complete CPU tangent generator exists as dead code (`computeTangents()` :2496-2537, zero call sites).
- **Anti-aliasing:** Zero. swapchainCI.sampleCount = 1 (xr_renderer.cpp:546), no MSAA FBO, no roughness floor, no geometric specular AA. Skinned animated normals shimmer.
- **Dead weight:** Filament 1.71.0 (3 AARs) ships in BOTH flavors and is System.loadLibrary'd at activity start (FilamentModelActivity.kt:25-27) though FilamentRenderer.kt is never instantiated.
- **Light direction:** XR light-estimation DIRECTION is polled successfully in native (xr_renderer.cpp:1482-1535, buffer floats [10-12]) and then **discarded in Kotlin** (FilamentModelActivity.kt:2164-2179: "XR direction estimation is unreliable — don't override user's setting" — a comment that almost certainly dates from the swapped-enum era). `smoothLightDir` (:80) and `aD=0.12` (:2151) are dead code waiting for this fix.
- **GLB loader:** Parses `meshes[0].primitives[0]` ONLY (:773). Eyes/lashes/hair/teeth shipped as separate primitives are silently dropped. alphaMode/alphaCutoff/occlusionTexture/baseColorFactor unparsed; `fragColor.a` hardcoded 1.0 (:2911).
- **Stereo:** Two arraySize=1 swapchains, all geometry drawn twice, joint palette uploaded per eye with a fresh direct ByteBuffer per model per eye per frame (:1759-1760 — violates the repo's own zero-alloc invariant #6).

---

## Session Status

| Item | Status |
|------|--------|
| **R1** (4x MSAA + specular AA) | **DONE — LOGCAT-VERIFIED ON GALAXY XR 2026-06-10 (evening): `MSAA EXT ready: max 4 samples` → `MSAA 4x enabled` → `MSAA 4x eye FBO complete 1856x2160 (glGetError=0x0)` with session FOCUSED — the sRGB swapchain + implicit resolve + foveated SCALED_BIN combination composes cleanly, no fallback fired. Visual verdict + Quest 3 check still pending.** |
| **R6** (color hygiene) | **DONE — landed + device-verified on Quest 3 (2026-06-10)** |
| **R2** (consume light direction) | **DONE — VERIFIED ON GALAXY XR 2026-06-10.** Two hardware corrections: direction convention is INVERTED from docs (runtime reports light-travel direction; negated at consumption), and AMBIENT-kind SH works natively (no fallback needed in practice). See NOTES.md for the session's bonus fixes (boundary-interceptor task demotion, occlusion un-stick) + discoveries (light_estimation_cubemap v1 enumerated → R5 Tier A viable; recommended_resolution v1 present → R7). |

### Already landed this session (3 fixes, both flavors compile-verified)

1. **Emitter↔slider bidirectional sync** — `GlesModelRenderer.syncEmitterToAngles()` + InputHandler live menu refresh. Dragging the emitter gizmo and moving the azimuth/elevation sliders now stay consistent in both directions. (Directly relevant to R2 — see its trap notes: per-frame angle writes will now visibly move the emitter gizmo.)
2. **AudioReactor failure surfacing** — `lastError`/`lastCaptureMs` fields + status text on the beat-settings panel. Root cause found: RECORD_AUDIO runtime permission was never granted, so `Visualizer` died silently with no UI feedback. Grant via:
   `adb shell pm grant com.ashairfoil.prism android.permission.RECORD_AUDIO`
3. **Both flavors compile-verified** (`gradlew assembleDebug` builds galaxyxr + quest debug variants).

---

# Recommendations (priority order)

Impact tiers as ranked by the audit: R1-R5 transformative, R6-R13 high, R14-R16 medium.

---

## R1: 4x MSAA via GL_EXT_multisampled_render_to_texture (tile-memory implicit resolve) + shader specular AA

**STATUS: IMPLEMENTED 2026-06-10 — build-verified both flavors; on-head verification pending.** Implementation deviations: renderUiOverlay turned out to be DEAD CODE (zero call sites — the panel renders via the native UI quad composition layer), but was made attach-consistent anyway; bloom pass 4 was the only live mid-frame re-attach. Depth invalidate placed in finishEyePass AND before the bloom lazy-init/pass-1 FBO switch. Fallback: FBO-incomplete with MSAA on → drop to single-sample (depth reallocs via the samples-mismatch gate), one skipped eye, mirrors the two-attempt pattern.
`category=display-pipeline · devices=both · impact=transformative · effort=days · verdict=confirmed`
**Files:** `GlesModelRenderer.kt`, `cpp/renderer_jni_bridge.cpp`, `cpp/xr_renderer.cpp`

### Spec

- Keep the XR swapchain at `sampleCount=1` (implicit-resolve path requires a single-sample target).
- GLES30 Kotlin bindings lack the EXT entry points, so add a ~30-line JNI shim in `renderer_jni_bridge.cpp`: `eglGetProcAddress` for `glFramebufferTexture2DMultisampleEXT` and `glRenderbufferStorageMultisampleEXT` at init, cache pointers, expose `nativeFramebufferTexture2DMultisample` / `nativeRenderbufferStorageMultisample`, gated on `GL_EXT_multisampled_render_to_texture` in the extension string (present on Adreno 740 and Galaxy XR XR2+ Gen 2) with clean fallback.
- In `renderEye` (GlesModelRenderer.kt:1632-1641):
  - `glFramebufferTexture2DMultisampleEXT(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, swapchainTexId, 0, 4)`
  - `glRenderbufferStorageMultisampleEXT(GL_RENDERBUFFER, 4, GL_DEPTH_COMPONENT24, w, h)`
- Samples resolve in GMEM on tile store — no resolve blit. Cap at 4 samples (Meta: never more). Budget 0.5-1.5 ms.
- Add `GLES30.glInvalidateFramebuffer` on GL_DEPTH_ATTACHMENT before unbinding each eye.
- In FRAGMENT_SHADER after the MR fetch (:2845-2851):
  1. `roughness = max(roughness, 0.089)` (Filament fp16-safe floor)
  2. Vlachos GDC2015 geometric AA: `dx=dFdx(vNormal); dy=dFdy(vNormal); gr=pow(clamp(max(dot(dx,dx),dot(dy,dy)),0.0,1.0),0.333); roughness=max(roughness,gr)` — ~10 ALU, kills highlight crawl exactly where skinned animated normals shimmer.
- Interactions: bloom (default OFF) sampling the swapchain mid-frame forces an early resolve — order the bright-pass read after all eye-FBO draws; MSAA fractional silhouette alpha actually IMPROVES passthrough edge blending under premultiplied SOURCE_ALPHA; unlocks GL_SAMPLE_ALPHA_TO_COVERAGE for hair (R12).
- VERIFY ON DEVICE on both headsets: EXT enumerates, and MSAA composes with the XR_FB_foveation SCALED_BIN swapchain bit (historical silent-failure interactions reported).

### Verifier corrections & device traps

1. **renderEye is NOT the only site attaching the swapchain texture** to the shared `fbo` with plain `glFramebufferTexture2D` — `renderUiOverlay` does it at GlesModelRenderer.kt:2098-2100 and renderBloom composite pass 4 at :2227-2229. With the implicit-resolve path, any mid-frame re-attach via the non-EXT call demotes the attachment to single-sample; ALL attach sites on `fbo` must be made consistent (bloom pass 4 drawing post-resolve single-sample is acceptable since bloom is the last draw, but make it a deliberate choice, not an accident). renderGrid (:1781, comment "FBO should be bound from renderEye"), renderLaser, renderColorWash, renderShadowPlanes, renderFacingMarker all inherit the bound FBO and will correctly render into the MSAA attachment.
2. The bloom-ordering instruction is ALREADY satisfied: per-eye order in FilamentModelActivity.kt is renderEye → grid/markers/laser/wash → renderBloom (:4137, :4242) → finishEyePass (:4138, :4243). Note the bright pass samples `swapchainTexId` while it is still the FBO color attachment (:2202) — a feedback-loop hazard that exists today and will force an early implicit resolve; tolerable because bloom is last and default-off.
3. `glInvalidateFramebuffer` on GL_DEPTH_ATTACHMENT belongs in `finishEyePass` (:2089, currently just binds FBO 0) — but finishEyePass runs AFTER bloom re-attached the texture; invalidate depth before the bloom passes too or it buys nothing.
4. Do NOT touch the shadow-map FBO (separate DEPTH_COMPONENT24 renderbuffer at :594) — shadow pass needs no MSAA.
5. Depth realloc is gated on size change (:1635) — keep the same gating with `glRenderbufferStorageMultisampleEXT`, and reset `lastDepthW/H` if the sample-count path changes at runtime.
6. CLAUDE.md traps: Samsung Galaxy XR runtime diverges from Khronos spec — the foveated-swapchain creation already uses a two-attempt clean-fallback pattern (xr_renderer.cpp:528-589); MSAA-EXT init should mirror it, and the MSAA × SCALED_BIN foveation combination must be verified via adb logcat on hardware (CLAUDE.md forbids claiming "platform limitation" without trying). New JNI externals must follow Architecture Invariant 8: `private external fun` with public wrapper, NOT `internal external fun` (debug-build name mangling). Build after every change.
7. GlesModelRenderer.kt lives only in src/main — galaxyxr flavor overrides only MainActivity.kt and quest only AndroidManifest.xml, so one edit covers both device flavors.
8. Swapchain format is GL_SRGB8_ALPHA8 (xr_renderer.cpp:504-516); implicit MSAA + sRGB resolve is supported on Adreno but is exactly the kind of combo to verify on the Samsung runtime.
9. The Vlachos AA snippet uses vNormal (interpolated vertex normal) — correct and standard even when the normal-mapped branch (:2832-2834) is taken, since the geometric term targets skinned-geometry curvature. Ordering: roughness floor first, then max with geometric term, both BEFORE `alpha = roughness * roughness` at :2856.

---

## R2: Galaxy XR — consume the estimated light DIRECTION to align key light + shadows with the real room; fix wasted SH colorCorrection, sticky useSH, and TOTAL-vs-AMBIENT double counting

**STATUS: DONE — VERIFIED ON GALAXY XR 2026-06-10.** Hardware findings: direction convention INVERTED from docs (negated at consumption, marked in code); AMBIENT-kind SH accepted natively; emitter-drag flip-off verified live. Outstanding minor verifications in NOTES.md. Deviations from spec: (a) the Follow Room Light toggle lives on param 24 as a 3-state cycle OFF→ON+DIR→ON — the main panel has no room for a new row (rows ≥25 collide with the action-button band); (b) hysteresis compares a CLAMPED-candidate direction (elev [5..85], azimuth frozen near zenith) against the applied dir — comparing the raw estimate would deadlock >5° for out-of-clamp lights and write angles forever (emitter/shadow crawl); (c) the AMBIENT-kind fallback covers all three rejection shapes (XR_FAILED / root-INVALID / sh-INVALID) with a warm-up-guarded 30-poll latch that is cleared on Follow re-engage; pre-latch fallback polls suppress SH so TOTAL coefficients never blend into the AMBIENT-basis EMA; (d) un-stick is 270 frames (~90 native polls) and native ages out the cached estimate after 10 consecutive failed polls so the un-stick can actually fire on hard outages.
`category=lighting · devices=galaxyxr · impact=transformative · effort=hours · verdict=confirmed`
**Files:** `FilamentModelActivity.kt`, `GlesModelRenderer.kt`, `scene/InputHandler.kt`, `settings/SettingsManager.kt`, `cpp/xr_renderer.cpp`

### Spec

The native chain already polls direction successfully (xr_renderer.cpp:1482-1535, with corrected struct enums per xr_light_estimation.h:14-16); the "unreliable" discard at FilamentModelActivity.kt:2164-2179 almost certainly dates from the swapped-enum era.

1. **Consume direction in the autoAmbient branch:** when `mean(dirRGB) > 0.3` AND `length(dirXYZ) > 0.5`, normalize and EMA-smooth into the existing dead `smoothLightDir` (field at :80) with the already-computed alpha `aD=0.12` (:2151). Gate on ~20 consecutive valid polls before first apply; add 5-deg angular-delta hysteresis to avoid shadow crawl. Convert `az = atan2(x, z)`, `elev = asin(clamp(y, -1, 1))` clamped to [5..85] deg, write `gr.lightAngleDeg`/`gr.lightElevDeg` via `gr.updateLightDirFromAngles()` — same write-back pattern as `updateLightFromEmitter` so sliders/emitter stay in sync. Add a SettingsManager-persisted **"Follow Room Light"** toggle (default ON when Auto Light param 24 is on); touching azimuth/elevation sliders (InputHandler params 8/9) flips it off, mirroring the ambient-slider/autoAmbient contract.
2. **Wire colorCorrection into the SH path:** multiply BOTH the shIrrad diffuse term and the specEnv term (GlesModelRenderer.kt:2880/:2886) by `uAmbientColor` — the EMA-smoothed XR colorCorrection (FilamentModelActivity.kt:2160-2162) currently never reaches pixels when SH is valid.
3. **Un-stick useSH:** reset `gr.useSH = false` when shValid stays false ~90 polls or autoAmbient is toggled off (:2183-2188) so stale frozen room lighting can't persist.
4. **Stop double counting:** when Follow Room Light is active, poll SH with `kind = XR_SPHERICAL_HARMONICS_KIND_AMBIENT_ANDROID` instead of TOTAL (xr_renderer.cpp:1480) so the explicit key light isn't double-counted; fall back to TOTAL if state returns INVALID.
5. Delete the dead duplicate struct chain at xr_renderer.cpp:1449-1463.

VERIFY ON DEVICE: adb logcat the existing `'XR Light: ... dir=(...)'` log (:1551) while moving a bright lamp; confirm sign convention and stability on the current (possibly OTA-updated) runtime.

### Verifier corrections & device traps

All four current-code claims verified accurate. Must-knows:

1. **Wrong line cite:** `updateLightFromEmitter` is at GlesModelRenderer.kt:1504-1520, not 1481-1492 (1481 is a glVertexAttrib3f inside renderEmitter). Its az convention `atan2(dx, dz)` matches the proposed `atan2(x, z)`; `updateLightDirFromAngles` is at :332-345.
2. **Emitter-gizmo interplay (now sharpened by this session's sync fix):** `updateLightDirFromAngles` calls `syncEmitterToAngles` (:344, no-op only when angles unchanged per :351-352). Today the per-frame call at FilamentModelActivity.kt:2179 no-ops; once room-light EMA changes angles per-frame, **the emitter gizmo will visibly track/drift** — the 5-deg hysteresis is load-bearing, and emitter drag (`updateLightFromEmitter`) must ALSO flip Follow Room Light off. (This session landed bidirectional emitter↔slider sync, so the gizmo now follows angle writes even more directly — test the hysteresis on hardware.)
3. **Params 8/9 are NOT the only manual-angle paths:** thumbstick nudges (InputHandler.kt:2431-2437), double-click resets (:2551-2552), `LightingPresets.applyPreset` (LightingPresets.kt:122-127), and SceneManager scene load (SceneManager.kt:723-725) all write angles — decide flip-off vs exempt for EACH.
4. `autoAmbient` is NOT SettingsManager-persisted today; it lives in per-scene JSON (SceneManager.kt:602/729) and presets — define scene-load interplay for the new persisted toggle.
5. Existing elevation clamp is [5..90] (InputHandler.kt:2436); the rec's [5..85] is a deliberate tightening, fine.
6. **No dirValid flag exists in the 41-float layout.** When dir.state is INVALID, JNI emits struct defaults `dirXYZ=(0,1,0)`, `dirIntensity=(0,0,0)` (xr_renderer.h:55-56) — the `mean(dirRGB) > 0.3` gate correctly filters this; the `length(dirXYZ) > 0.5` gate alone would NOT (the default has length 1.0). **Keep both gates.**
7. **Device-verify gotcha:** the 'XR Light' log (xr_renderer.cpp:1549-1555) only prints the FIRST 5 valid estimates (`logCount < 5`) — moving a lamp later logs nothing; temporarily raise the gate or use the sensor HUD debug string (FilamentModelActivity.kt:2191-2194 shows dirXYZ).
8. **CLAUDE.md trap applies directly:** Samsung runtime diverged from developer.android.com docs (swapped struct-type enums, already corrected per xr_light_estimation.h:14). AMBIENT-kind SH behavior and the direction sign convention ("direction toward light" per header :73) MUST be validated on hardware via logcat before trusting. Kind-fallback to TOTAL requires a re-query since `kind` is an input field set before `xrGetLightEstimate` (:1480) — slightly more code than "fall back" implies.
9. **41-float buffer warning:** `lightEstimateBuffer` is 41 floats (FilamentModelActivity.kt:60, xr_renderer.h:59) — coincidentally the same size as the sacred 41-float ControllerState but a DIFFERENT buffer; layout referenced in ControllerStateContractTest.kt:497. No layout change is proposed, so no contract edit needed — but do NOT add a dirValid float without updating JNI + Kotlin + test together.
10. FilamentModelActivity/GlesModelRenderer are in the shared main source set, so changes also build into the Quest flavor — all new behavior must stay gated behind `nativePollLightEstimate`/hasXrLight (it already is, in the autoAmbient branch). Dead-chain deletion at xr_renderer.cpp:1449-1463 is safe (zero other references; the ":1457 SH disabled until padding issue resolved" comment is stale history).

---

## R3: Restore normal mapping on rigged dancers via indexed per-vertex tangent averaging + skinned TBN

`category=materials · devices=both · impact=transformative · effort=days (verifier: much less — see below) · verdict=partially-implemented`
**Files:** `GlesModelRenderer.kt` (only)

### Spec

Every rigged (Tripo) dancer with a normal map is force-downgraded to flat vertex normals by the gate at GlesModelRenderer.kt:1310-1314 (`if (model.isSkinned && model.normalMapTexId != 0) { ... model.hasNormalMap = false }`) because the tangent-generating unweld path (:1315, unweldWithTangents) never replicated JOINTS_0/WEIGHTS_0.

- **Fix (A), strictly better for skinned meshes:** indexed per-vertex tangent averaging with NO unweld — iterate the existing index buffer, compute per-triangle (T, B) from UV deltas (Lengyel method), accumulate per vertex, then orthonormalize T against N and set `w = sign(dot(cross(N,T), B))`. The UV-seam cancellation that motivated the unweld (comment :1283-1296) only affects rare seam vertices; averaged tangents are the industry norm for organic meshes (Filament/three.js absent MikkTSpace). Upload as the existing aTangent vec4 attribute (location already parsed for baked tangents).
- **Alternative (B)** — replicating JOINTS_0/WEIGHTS_0 through the 3x unweld — works but triples vertex cost on a mesh skinned per-eye; **avoid**.
- In VERTEX_SHADER: skin the tangent like the normal — `T_skinned = normalize(mat3(S) * aTangent.xyz)` before the existing Gram-Schmidt re-orthonormalization; w passes through. Delete the `hasNormalMap = false` gate.
- One-liner caveat: the glute-push deformation (:2770-2783) displaces along nrm without recomputing normals — at most damp the normal-map contribution inside the influence sphere; do not block on it.
- Restores skin pores, navel, knuckle/collarbone detail on exactly the asset class the app exists for.

### Verifier corrections & device traps

Diagnosis fully correct, but the rec **overstates the work remaining** — most of it already exists:

1. **The entire shader-side change is ALREADY in VERTEX_SHADER** at :2759-2766 (`tan = normalize(S3 * tan)` gated by `uHasNormalMap > 0.5`; Gram-Schmidt vs N and w-handedness passthrough at :2790-2795). **No shader edit is needed at all** — only delete the Kotlin gate and supply real tangent data.
2. **The exact fix-A algorithm already exists as dead code:** `computeTangents()` at :2496-2537 (indexed per-triangle Lengyel T/B from UV deltas :2508-2520, per-vertex accumulation, Gram-Schmidt :2525-2529, `w = sign(dot(cross(N,T),B))` :2530-2533) — ZERO call sites. The minimal change: call it for skinned meshes (e.g. `tangents = computeTangents(positions, normals, texCoords, indices, posCnt, idxCnt)` when `model.isSkinned && model.hasNormalMap && tangents == null && texCoords != null`), let the existing :1346-1350 block upload it, then delete the :1310-1314 gate. The codebase's own comment :1307-1309 states this identical plan.
3. **Latent NaN trap in computeTangents** the unweld version fixed: on degenerate-UV triangles it `continue`s (:2513) and the orthonormalize fallback (:2529) does NOT substitute a unit tangent when `len <= 0.0001f`, leaving near-zero tangents that become NaN after shader `normalize`. Port the fallback from unweldWithTangents :2473-2474 (else → `(1,0,0)`).
4. **The current gate is over-broad:** it fires even when a skinned GLB ships baked TANGENT (it checks normalMapTexId, not tangents==null), while :1346 would upload those tangents anyway — keep normal mapping when baked tangents exist (the shader comment :2759-2763 assumes Tripo rigs never bake TANGENT; don't rely on that).
5. Corrected line cites: unweldWithTangents is :2397-2494 (not :2369-2466); the baked-TANGENT parse starts :810 (not :786-801, which is the NORMAL parse); the seam-motivation comment spans :1283-1309.
6. Skinned meshes keep their original welded index buffer, so averaged tangents stay 1:1 with the JOINTS_0/WEIGHTS_0 VBOs uploaded at :1353+ — fix A is fully compatible.
7. CLAUDE.md: load-time CPU work, zero-alloc render-loop invariant untouched. `createVBO` (:2539-2545) binds attributes into the currently bound VAO — ensure `model.vao` is bound (existing :1347 pattern). Single-file edit in src/main covers both flavors. The glute-push caveat is real but pre-existing and equally affects vertex normals; do not block on it.

---

## R4: Make the dancer receive and self-cast shadows; convert shadow map to depth texture + hardware PCF with normal-offset bias and tight frustum

`category=shadows · devices=both · impact=transformative · effort=days · verdict=confirmed`
**Files:** `GlesModelRenderer.kt`, `FilamentModelActivity.kt`

### Spec

The main model FRAGMENT_SHADER (GlesModelRenderer.kt:2799-2913) samples no shadow map — the 2048×2048 skinning-aware map (rendered every frame, FilamentModelActivity.kt:3994) lands only on the floor grid and room planes. No self-shadow under chin/breast/arms = flat game-character look.

1. **Convert the R32F-color shadow target** (:581-584 — float32 LINEAR isn't filterable in core ES3.0 anyway) **to a real GL_DEPTH_COMPONENT24 depth texture:** no color attachment, `glDrawBuffers(GL_NONE)`, `TEXTURE_COMPARE_MODE = COMPARE_REF_TO_TEXTURE`, `COMPARE_FUNC = LEQUAL`, GL_LINEAR — `sampler2DShadow` gives free 2x2 hardware PCF per tap (Adreno HW depth-compare since 3xx). Delete the SHADOW_FRAGMENT_SHADER color write (:2937-2941), making the depth-only pass faster. Keep the PCSS look on grid/planes by layering 4-8 Poisson taps over the hardware compare.
2. **Model shader:** add `uShadowMatrix` (light VP × model), 4-tap PCF, multiply ONLY the key-light term — `color = (diffuse + spec) * NdotL * shadow * uLightColor * uLightIntensity`; ambient/SH/fill stay unshadowed.
3. **Replace the constant 0.001 bias** with normal-offset bias (offset world pos by `N * texelWorld * 1.5`, `texelWorld = 2 * shadowSpread / 2048`) plus slope term `clamp(0.0005 * tan(acos(NdotL)), 0.0, 0.002)`.
4. **Tighten the ortho frustum** from the ~8 m shadowSpread default (~7.8 mm texels) by auto-fitting to the skinned model AABB + 0.5 m — ~1.5 mm texels makes finger/chin self-shadow resolvable. **Texel density, not map size, is the limiter; leave it at 2048.**
5. **Cheap grounding bonus:** 8-12 per-bone capsule AO (Iwanicki/UE math) evaluated in grid/plane shaders — joint transforms are already on the Kotlin side every frame.

### Verifier corrections & device traps

Nothing already implemented, nothing infeasible (depth textures, sampler2DShadow, TEXTURE_COMPARE_MODE, glDrawBuffers, sampler objects all core ES 3.0; project already uses `#version 300 es` + GLES30 API).

1. **Line-number fixes:** bias constants are at :2988 (grid) and :3107 (SP), not :2960/:3079; grid frag shader spans :2975-3040, SP frag :3092-3139; the XZ-recenter logic is GlesModelRenderer.kt:1565-1572 inside renderShadowMap, NOT FilamentModelActivity.
2. **PCSS-over-hardware-compare trap:** a depth texture with COMPARE_REF_TO_TEXTURE cannot be read as raw depth — the PCSS blocker search (:2990-3003, :3109-3121) needs raw values. Use ES 3.0 **sampler objects**: bind the same depth texture to two units, one sampler with compare+LINEAR (sampler2DShadow hw PCF) and one with compare NONE + NEAREST (raw reads). LINEAR on a non-compare depth texture is undefined in ES 3.0.
3. **Model shader has no world-position varying** — vPosition is VIEW-space (:2788), only vWorldY exists (:2789). Add a vShadowCoord/world-pos varying (`uShadowMatrix * uModel * skinnedPos`) in VERTEX_SHADER. `shadowLightMatrix` (:1572) is exactly the needed light VP.
4. Shadow pass front-face culls (:1592-1593) as acne mitigation; combined with normal-offset bias this risks **peter-panning** — re-test on device.
5. **shadowSpread is user-facing state:** persisted in scenes (SceneManager.kt:601/728), presets (LightingPresets.kt:23/55/109/126), UI slider clamped 2-30 m (InputHandler.kt:2441, reset :2555, UiRenderer.kt:1924) — auto-fit frustum will fight/obsolete it; decide override-vs-scale and keep the persistence schema compatible.
6. **Skinned-AABB fit must respect Invariant 6** (zero-alloc render loop): no per-frame allocation, no per-vertex CPU skinning — derive bounds from joint world translations (already uploaded to UBO per frame, :1607-1608) + margin, using scratch FloatArrays.
7. Keep the `shadowEnabled = false` FBO-incomplete fallback (:606-608) for the new depth-only FBO. The current R32F target silently depends on EXT_color_buffer_float — depth-only actually REMOVES an extension dependency.
8. Keep out-of-frustum = no-shadow behavior (:2986 `return 0.0`, :3104 `discard`) when tightening the frustum, and keep the +0.5 m margin or shadows pop at AABB edges.
9. Renderer shared with the Quest 3 port (quest source set is manifest-only) — both devices are Adreno XR2-class with hw depth-compare, but every change needs a real build and on-hardware validation. Samsung runtime divergence traps don't apply here (pure GLES, no OpenXR API changes).
10. Functions must stay under 50 lines (CLAUDE.md) — renderShadowMap is already 62 lines; AABB-fit logic goes in a helper or new file per Invariant 9.

---

## R5: Real specular IBL via split-sum: baked cmgen-prefiltered cubemaps now (rescues Quest), XR_ANDROID_light_estimation_cubemap probe as follow-on; keep live SH for diffuse

`category=lighting · devices=both · impact=transformative · effort=days · verdict=confirmed`
**Files:** `GlesModelRenderer.kt`, `cpp/xr_light_estimation.h`, `cpp/xr_renderer.cpp`, `cpp/renderer_jni_bridge.cpp`, `FilamentModelActivity.kt`

### Spec

Specular "IBL" today is `evalSH(reflect(-V, N)) * Schlick * (1 - roughness)^2` (GlesModelRenderer.kt:2881-2886) — L2 SH cannot represent specular, so glossy/wet skin, eyes, and metals read as blurry diffuse tint. On Quest the SH path never activates (no light estimation), so env specular is **literally zero** (the :2888 fallback has NO specular term whatsoever). The eye catchlight — the #1 "alive" cue at VR close range — is currently impossible.

- **SHADER (do once):** replace `evalSH(R)` with split-sum:
  `textureLod(uEnvCube, R_world, perceptualRoughness * uEnvMaxLod)` where `R_world = uViewToWorld * reflect(-V, N)` (lighting is view-space — pass a mat3 inverse view rotation), times the Karis/Lazarov analytic EnvBRDF:
  ```glsl
  const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
  const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
  vec4 r = roughness * c0 + c1;
  float a004 = min(r.x * r.x, exp2(-9.28 * NdotV)) * r.x + r.y;
  vec2 AB = vec2(-1.04, 1.04) * a004 + r.zw;
  vec3 specIBL = env * (F0 * AB.x + AB.y);
  ```
  No LUT texture, ~8 ALU.
- **TIER B (days, both devices, DO FIRST):** bake 2-3 HDR studio environments offline with Filament's cmgen (`--format=ktx --size=256 --ibl-samples=1024`), load as RGB16F cubemap (Adreno/XR2 filter half-float) or RGBM-in-RGBA8; selectable per lighting preset. On Galaxy XR scale by (live SH DC luminance / baked DC luminance) so reflections track real room brightness; diffuse stays on live SH exactly as today. **This alone transforms Quest**, whose ambient is near-black.
- **TIER A (week-plus follow-on, galaxyxr):** the dismissal at xr_light_estimation.h:19 ("Revision 2 only") is an unverified platform-limitation claim — XR_ANDROID_light_estimation_cubemap is now documented (HDR R16G16B16A16_SFLOAT faces, reproject flag). **CONFIRMED 2026-06-10: `XR_ANDROID_light_estimation_cubemap v1` IS enumerated on the (OTA-updated) Galaxy XR runtime — Tier A is viable.** Chain `XrCubemapLightEstimatorCreateInfoANDROID` (256px, reproject=true) behind the Samsung-trap protocol (struct-size logging, guarded first call), prefiltering one face-mip per frame amortized over ~30 frames. Note the R2 lesson: validate every doc claim on hardware — the light-estimation DIRECTION convention shipped inverted from developer.android.com.
- **TIER C optional:** coarse live cubemap from the Camera2 passthrough feed.

### Verifier corrections & device traps

1. **PRE-EXISTING SPACE BUG the fix must also address:** SH coefficients are queried in appSpace_/world space (`getInfo.space = appSpace_`, xr_renderer.cpp:1445) and uploaded unrotated (FilamentModelActivity.kt:2184-2186 straight arraycopy; renderer_jni_bridge.cpp:219-226 raw), yet `evalSH()` is fed VIEW-space N and R. **The current diffuse SH rotates with the user's head.** Apply the new `uViewToWorld` mat3 to the diffuse `evalSH(N)` path too, not only the new specular lookup.
2. **CLAUDE.md traps:** Samsung runtime diverges from Khronos/docs — the existing light-estimation struct type constants had to be corrected because developer.android.com had them SWAPPED (xr_light_estimation.h:14-16). Treat any `XR_TYPE_ENVIRONMENT_LIGHTING_CUBEMAP_ANDROID` constant from docs as suspect; validate via logcat. The struct-size-logging + guarded-first-call protocol already exists as a pattern at xr_renderer.cpp:1496-1505 — reuse it. The Iron Rule (no unverified "platform limitation" claims) directly endorses re-testing the :19 dismissal.
3. **Zero-alloc invariant:** cubemap upload at init/preset-change only; Tier A per-face-mip prefiltering must use preallocated scratch buffers.
4. **GLES note:** ES 3.0 context — sampling + linear-filtering RGBA16F is core in ES 3.0 (fine for baked KTX, Tier B), but RENDERING to half-float mips (Tier A runtime prefilter) requires `EXT_color_buffer_half_float` (present on both XR2 Adreno and Galaxy XR, but add a guard). Prefer RGBA16F over RGB16F for upload alignment.
5. **Flavors:** quest/ contains only an AndroidManifest.xml and galaxyxr/ only a MainActivity.kt — GlesModelRenderer.kt and all C++ are shared main-source, so the shader change is automatically [both]; gate Tier A behind the existing lightEstimationSupported_/hasExt mechanism, NOT flavor.
6. Shader strings are Kotlin consts (companion FRAGMENT_SHADER pattern per CLAUDE.md); new uniforms need location lookups near :373-397 and per-frame binds near :1684-1690.
7. FilamentRenderer.kt is NOT needed (its iblEntity is dead code; the active model path is the custom GLES pipeline per CLAUDE.md "NOT Filament's material system").

---

## R6: Color hygiene: SRGB8_ALPHA8 texture uploads, Khronos PBR Neutral tone map, correct fill-light BRDF, 4x anisotropy (ASTC/KTX2 as follow-on)

**STATUS: DONE — items 1-4 landed and device-verified on Quest 3 (2026-06-10). Item 5 (ASTC/KTX2 transcode) remains a separate follow-on.**
`category=display-pipeline · devices=both · impact=high · effort=hours · verdict=confirmed`
**Files:** `GlesModelRenderer.kt` (only, for items 1-4)

### Spec

1. **sRGB uploads:** upload baseColor + emissive as GL_SRGB8_ALPHA8 (normal/MR stay linear RGBA8 — `loadGltfTex` needs an isSrgb param) and delete both approximate `pow(2.2)` decodes (:2842, :2893). Hardware sRGB decode is exact and, critically, `glGenerateMipmap` (:981) then filters in LINEAR light — currently mips are averaged in gamma space, darkening minified skin texture at VR viewing distances. **(See trap A below — the naive GLUtils call is a hardware trap; use the copyPixelsToBuffer path.)**
2. **Tone map:** replace the ACES Narkowicz tone map (:2901-2902) — which desaturates and hue-shifts skin reds toward orange — with Khronos PBR Neutral (~10 ALU):
   ```glsl
   float x = min(min(c.r, c.g), c.b);
   float f = x <= 0.08 ? x - 6.25 * x * x : 0.04;
   float p = max(max(c.r, c.g), c.b) - f;
   if (p <= 0.76) { out = c - f; }
   else {
       float pn = 1.0 - 0.5776 / (p + 0.48);
       float g = 1.0 / (0.15 * (p - pn) + 1.0);
       out = (c - f) * (pn / p) * g + vec3(pn * (1.0 - g));
   }
   ```
   Colors map 1:1 up to 0.76 so authored skin tone survives to the panel. Keep `exp2(uExposure)` before, contrast/saturation after; optionally keep ACES as a menu "Filmic" mode.
3. **Fix the fill-light specular bug at :2873-2875** — fSpec currently reuses the KEY light's F (Schlick of key H·V) and G (contains key NdotL) with an ad-hoc 0.5 multiplier. Compute fF from fillH and fG with fNdotL in the Smith terms; drop the fudge.
4. **Anisotropy:** after checking `GL_EXT_texture_filter_anisotropic`, set `glTexParameterf(GL_TEXTURE_MAX_ANISOTROPY_EXT, 4f)` on baseColor — skin at grazing angles (thighs/arms in profile) currently blurs under trilinear; Meta guidance allows one aniso lookup per fragment.
5. **Follow-on (days, separate): KTX2/BasisU → ASTC transcode path** (6x6 albedo / 5x5 normal+MR, COMPRESSED_SRGB8_ALPHA8_ASTC variants) — ~4x VRAM/bandwidth so the 128MB auto-budget heuristic (:926-933) stops downscaling high-res texture sets. **NOTE: the user's Tripo GLBs now ship 8K textures — the 128MB auto-budget currently downscales them on load. ASTC/KTX2 is what makes 8K sources actually usable at full resolution; until then the budget heuristic is the quality ceiling on every new asset.**

### Verifier corrections & device traps

- **(A) DEVICE TRAP — the naive call is hazardous.** `GLUtils.texImage2D(GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, bitmap, 0)` (the 5-arg internalformat overload): AOSP's native `util_texImage2D` passes the `internalformat` argument as BOTH internalformat AND format to glTexImage2D, and GL_SRGB8_ALPHA8 (0x8C43) is not a legal `format` enum in ES 3.0 — on a conformant Adreno (Snapdragon XR2+ in Galaxy XR) this yields GL_INVALID_ENUM, an empty level 0, and a **black model** after glGenerateMipmap; some AOSP versions' checkFormat instead throw `IllegalArgumentException("invalid Bitmap format")`. **Use the robust path:** confirm ARGB_8888 config, `bitmap.copyPixelsToBuffer(byteBuffer)`, then `GLES30.glTexImage2D(GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)`. Verify with glGetError + logcat on hardware before trusting any upload path.
- **(B) baseColor is NOT loaded through loadGltfTex** — it is a duplicated inline block at :988-1012; adding isSrgb to loadGltfTex covers only emissive (:1017), so the inline block at :1005 must be changed too (normal :1015 and MR :1016 stay linear).
- **(C) GL_TEXTURE_MAX_ANISOTROPY_EXT is not in android.opengl.GLES30 bindings** — define const `0x84FE` (and `0x84FF` for the max query), gate on `glGetString(GL_EXTENSIONS).contains("GL_EXT_texture_filter_anisotropic")`, set while the texture is bound in the existing load path (already on GL thread).
- **(D)** Removing the `* 0.5` fudge and using proper fF (Schlick of dot(fillH, V)) + fG (Smith with fNdotL) will **brighten fill speculars**; uFillLightIntensity is user-tunable so expect a visual retune pass on hardware.
- **(E)** Sample-time sRGB decode-before-filter is guaranteed by ES 3.0; linear-space mipmap GENERATION for sRGB textures is the practical Adreno behavior but the weaker of the two guarantees — benefit claim still holds.
- **(F)** The exp2(uExposure)-before / contrast-saturation-after structure already exists exactly in that order (:2900, :2905-2907), so the PBR Neutral swap is a clean drop-in replacement of :2901-2902 only.
- **(G)** ASTC follow-on is feasible (Adreno supports GL_KHR_texture_compression_astc_ldr; glCompressedTexImage2D per mip; BasisU transcoder is a new C++/NDK dependency under cpp/ + CMakeLists.txt) — correctly scoped as separate/days.
- **(H)** Items 1-4 are single-file, load-time or shader-constant changes — no per-frame allocations; build after every change.

---

## R7: Bank GPU headroom into resolution: supersampled swapchains + per-device quality tiers, SUSTAINED_HIGH perf levels, 90Hz refresh cap, XR_ANDROID_recommended_resolution

`category=performance-headroom · devices=both · impact=high · effort=days · verdict=confirmed`
**Files:** `cpp/xr_renderer.cpp`, `cpp/xr_renderer.h`, `prism/app/build.gradle.kts`, `FilamentModelActivity.kt`, `settings/SettingsManager.kt`

### Spec

Swapchains are pinned to recommendedImageRectWidth/Height with maxImageRectWidth/Height logged then ignored (xr_renderer.cpp:490-495, 544-545) and renderScale_ clamped <=1.0 (:2382-2383) — rendering above recommended is structurally impossible on a single-character scene feeding Galaxy XR's 3552×3840 micro-OLED.

1. Allocate eye swapchains at `min(recommended * ssScale, maxImageRect)` and keep renderScale_ as a sub-rect fraction of swapchain size (reuse the existing imageRect mechanism :905-913), raising the clamp to [0.5, 1.4]. Expose a persisted **"Render Resolution"** setting (1.0 / 1.15 / 1.3) via SettingsManager + quality menu so the thermal ladder's 0.75 stage lands near recommended instead of below it.
2. **Per-device tier table** via flavor buildConfigFields in build.gradle.kts: `quest { SS_SCALE=1.2, MSAA=4, FFR_DEFAULT=1, REFRESH=90 }` (1680×1760 → ~2016×2112/eye, near panel-native), `galaxyxr { SS_SCALE=1.15, MSAA=4, FFR_DEFAULT=dynamic, REFRESH=90 }`.
3. At session READY call `xrPerfSettingsSetPerformanceLevelEXT(SUSTAINED_HIGH)` for BOTH CPU and GPU domains — the extension is already enabled but consumed read-only for thermal events (:242-249).
4. Change primeDisplayRefreshRate auto policy (:2011-2015) from highest-enumerated to "highest <= 90Hz" and default the pref from 0/auto to 90.0f (SettingsManager.kt:251-254) — on Quest 3 auto can pick 120Hz, cutting the GPU budget 33% for a beauty-shot app; keep 120 as explicit opt-in.
5. Request **XR_ANDROID_recommended_resolution** (#462, published) when enumerated; on RECOMMENDED_RESOLUTION_CHANGED re-query views and drive renderScale from the OS instead of the blind 3-stage thermal ladder (FilamentModelActivity.kt:1760-1863). XR_META_recommended_layer_resolution is the Quest analog.
- **Budget math at 90Hz/11.1ms:** current pass ~4-6 ms; 1.2x SS +~2-2.5 ms, 4x MSAA +0.5-1.5 ms, Quest passthrough ~17% — lands ~9-10.5 ms with FFR-LOW (-6-10%) as margin. Validate with XR_ANDROID_performance_metrics GPU-time on Galaxy XR and ovrgpuprofiler on Quest.
- VERIFY ON DEVICE: Samsung compositor sampling an oversized swapchain correctly; extension presence in the startup dump (xr_renderer.cpp:118-121).

### Verifier corrections & device traps

- (a) "Zero per-device config" is overstated — build.gradle.kts:30-42 ALREADY defines product flavors `galaxyxr`/`quest` (dimension "vendor") with per-flavor source sets and dependencies; only the buildConfigFields are missing. `buildConfig=true` is already set (:89), so adding buildConfigField is trivial.
- (b) renderScale clamp is at xr_renderer.cpp:2382-2383 (2384-2385 are the assignment); swapchain dims at :544-545 (:546 is sampleCount).
- (c) **The auto-highest refresh policy exists in TWO places** — primeDisplayRefreshRate (:2011-2015) AND requestDisplayRefreshRate (:2052-2060). Both must change or the Kotlin-driven path will still pick 120Hz.
- (d) **MSAA=4 is NOT a swapchain-level knob here:** swapchainCI.sampleCount=1 and the swapchain GL texture is handed to the Kotlin GLES renderer, so MSAA means R1's EXT_multisampled_render_to_texture work in GlesModelRenderer — a real GLES change, not a buildConfigField.
- (e) The comment at xr_renderer.cpp:905-908 asserts "renderScale_ stays exactly 1.0 outside the downgrade path" — making it user/SS-driven invalidates that invariant. Also stage-3 thermal (0.75) becomes 0.75 × ssScale of recommended (intended), but the save/restore logic at FilamentModelActivity.kt:1828 hardcodes restore-to-1.0f and must restore to the user setting instead.
- (f) Foveated swapchain creation attaches XrSwapchainCreateInfoFoveationFB with SCALED_BIN flag (:551-556) — verify oversized swapchain + foveation bin scaling coexist on both runtimes.
- **CLAUDE.md traps:** Samsung Galaxy XR runtime DIVERGES from Khronos spec drafts (struct layouts, enum values, XrStructureType constants — see xr_sensors.h hardcoding Android values like 1000465000), so XR_ANDROID_recommended_resolution structs/signatures must be validated against actual runtime logcat, never the spec draft. Extensions have hidden dependency chains — read full xrCreateInstance errors. New JNI externals: `private external fun` + public wrapper (Invariant 8). Zero-alloc invariant applies to any per-frame re-query. Trace realistic values (Galaxy XR recommended vs max imageRect) before building.
- **Unverifiable statically (mandatory on-device checks):** Quest 3 auto enumerating 120Hz, actual maxImageRect values, XR_ANDROID_recommended_resolution presence on the Galaxy XR runtime — the code path correctly gates on enumeration (hasExt) so absence degrades gracefully.

---

## R8: Fix Quest foveated rendering via apply-once-at-init profile, and persist the foveation level across launches

`category=performance-headroom · devices=both · impact=high · effort=days · verdict=confirmed`
**Files:** `cpp/xr_renderer.cpp`, `FilamentModelActivity.kt`, `settings/SettingsManager.kt`

### Spec

Quest has ZERO effective FFR: `setFoveationLevelSafe()` early-returns on FLAVOR=='quest', records the level, and returns true — **faking success to the UI** (FilamentModelActivity.kt:1724-1747, a workaround for a Meta xrUpdateSwapchainFB SIGSEGV) — while the create-time XR_SWAPCHAIN_CREATE_FOVEATION_SCALED_BIN_BIT_FB flag alone (xr_renderer.cpp:551-556) applies no profile; thermal stage-1's "foveation→3" escalation is a silent no-op there. The original SIGSEGV root cause was chaining XrFoveationEyeTrackedProfileCreateInfoMETA when the META extension wasn't loaded, not the update itself.

1. In createSwapchains (:528-605), immediately after foveated swapchain creation and BEFORE the first xrWaitFrame: `xrCreateFoveationProfileFB` with `XrFoveationLevelProfileCreateInfoFB{ level=XR_FOVEATION_LEVEL_LOW_FB, verticalOffset=0, dynamic=XR_FOVEATION_DYNAMIC_LEVEL_ENABLED_FB }`, NO META struct chained, and call `xrUpdateSwapchainFB` exactly once — the documented Meta apply-once pattern, protected by the existing retry-without-foveation fallback (:595-601). On the quest flavor lock the level after init (keep blocking mid-session updates). Test on-device; if the driver still faults, fall back cleanly.
2. **Persist foveationLevel in SettingsManager** and apply at startup — it is currently reset to 0 every launch (FilamentModelActivity.kt:490), so even Galaxy XR users must re-enable FFR each session.
3. **Per-device defaults:** LOW + dynamic on Quest (center-fixated character viewer hides peripheral binning; avoid HIGH — artifacts visible over passthrough); keep user levels 0-3 on Galaxy XR where runtime updates already work.
- Payoff: 6.5-21% GPU recovered (Meta's measured scaled-bin numbers) — the cheapest funding source for R7's supersampling tier.

### Verifier corrections & device traps

- (a) The rec cites ":320-338" as the META-chain guard — that range is the system-properties probe; the actual guard is xr_renderer.cpp:2274-2283 (`if (eyeTrackedFoveation_ && eyeTrackedFoveationExtEnabled_)`, comment naming the Quest null-deref/SIGSEGV). Substance right, citation off.
- (b) The root-cause theory (META chain, not the update) is consistent with code comments and the :228 gating, but is **unproven on hardware** — the on-device-test + clean-fallback requirement is mandatory. The existing retry-without-foveation loop (:531-601) gives the natural hook: inside the `!anyFailed` success branch before `return true` at :592, disabling foveation and retrying on profile-create/update failure.
1. The C++ layer is deliberately flavor-agnostic (Quest passthrough self-gates via hasExt, :251) — the quest "lock level after init" policy belongs in Kotlin where the BuildConfig.FLAVOR check already lives (FilamentModelActivity.kt:1730), or pass a flag through JNI.
2. Invariant 8: any NEW foveation JNI must be `private external fun` with a public wrapper.
3. The new init-time profile code executes on BOTH flavors' native path — validate on Galaxy XR via adb logcat too, not just Quest; never trust spec drafts.
4. Persistence has an exact in-file pattern to copy: SettingsManager.kt:259-261 (eyeTrackedFoveation); apply the saved level at FilamentModelActivity.kt:489-490 instead of hardcoding 0. (CLAUDE.md already lists the analogous `screenCurvature` non-persistence as a known gap — this fits an acknowledged pattern.)
5. **Fix the UI honesty problem** once quest apply-once lands with a locked level: setFoveationLevelSafe currently returns true and updates the OFF/LOW/MED/HIGH label (UiRenderer.kt:1930) for changes that do nothing on quest — either grey out the control on quest or return false for locked mid-session changes.
6. Thermal stage-0 restore (:1834) reads savedFoveationLevel which is only set when stage 1 fires (:1839) — once startup-applied levels land, ensure savedFoveationLevel initializes from the persisted value to avoid restoring to 0.
7. Other foveationLevel writers: user menu actions at InputHandler.kt:2070-2072, 2462-2466, 2560 — no startup application path exists today.

---

## R9: Galaxy XR presence: enable eye tracking for gaze-reactive eye contact now; face-tracking expression reactions as phase 2

`category=platform-data · devices=galaxyxr · impact=high · effort=days · verdict=confirmed`
**Files:** `scene/XrSensorPoller.kt`, `input/GazeController.kt`, `GlesModelRenderer.kt`, `FilamentModelActivity.kt`, `scene/InputHandler.kt` (+ `settings/SettingsManager.kt` if the toggle persists)

*(Eye/face tracking only — no hand-tracking features; controllers remain the only input device.)*

### Spec

The entire native path exists and is dead: lazy tracker create with Samsung fallback proc `xrGetCoarseTrackingEyesInfoANDROID`, per-eye + combined gaze poses, 25-float JNI bridge (xr_renderer.cpp:1646-1746), and a complete 68-blendshape face pipeline (:1752-1820, Samsung-verified count, 69-float JNI) — but `XrSensorPoller.eyeTrackingEnabled` and `faceTrackingEnabled` (XrSensorPoller.kt:35-36) default false with ZERO writers; polled data at best paints a debug HUD. GazeController.kt is uninstantiated dead code checking the WRONG permission namespace (`com.google.android.xr.permission.EYE_TRACKING_*` vs the manifest's `android.permission.EYE_TRACKING_COARSE/FINE`).

**EYE CONTACT (days):**
1. **"She Notices You"** menu toggle gated on `android.permission.EYE_TRACKING_COARSE`, setting `eyeTrackingEnabled = true`; fix or delete GazeController's namespace. **(See trap 1 — do NOT requestPermissions from the unmanaged activity.)**
2. Per poll (already every 3 frames): gaze ray from the combined pose, ray-sphere test against the head-bone world position (available from the skeleton palette).
3. State machine: gaze dwell on her face >300 ms with eye state == GAZING → slerp a clamped aim-rotation onto head/neck joints (yaw ±35 deg, pitch ±20 deg, 250-400 ms ease-in) toward the user's head position (free from the per-eye view poses in nativeWaitFrame's 69-float buffer — no new JNI); hold 3-5 s, glance away; relax to animation pose over ~1 s on gaze-miss. Implement as a post-animation pre-palette joint override hook; preallocate quaternion/vector scratch (zero-alloc invariant).

**FACE PHASE 1 (cheap add-on):** smile = mean(LIP_CORNER_PULLER_L/R), surprise = UPPER_LID_RAISER + JAW_DROP as triggers for existing systems (wink/wave preset, beat-wash pulse).
**FACE PHASE 2 (week-plus, blocked on R12-adjacent morph-target loader work):** glTF targets parsing + Samsung-68→ARKit-52 remap to mirror expressions.

VERIFY ON DEVICE FIRST: CLAUDE.md warns eye/face struct-type constants diverge on the Samsung runtime and this path has plausibly never run enabled — first test is HUD-only validation of sane gaze vectors and the 68 weight indices.

### Verifier corrections & device traps

1. **DEVICE TRAP — runtime permission requests kill OpenXR.** FilamentModelActivity.kt:353-355: "DON'T requestPermissions here — it restarts the activity and kills OpenXR. Grant permissions via Settings > Apps > ChloeVR > Permissions or adb pm grant". Either request in MainActivity (managed space) before launch, or have the toggle detect-and-instruct rather than call requestPermissions.
2. The "eye state == GAZING" check is already folded into the native validity flags (XR_EYE_STATE_GAZING_ANDROID at xr_renderer.cpp:1703/1714/1725), surfaced in Kotlin as `eyeTrackingBuffer[17]` (combined-valid) — no extra plumbing needed.
3. Gaze poses are located in appSpace_ (xr_renderer.cpp:1694), i.e. world space, but `SkeletonRuntime.jointWorldPos` is model-root space — the ray-sphere test must compose the model's placement transform (grab/scale/rotate state) with the head-joint position.
4. The user-head position from nativeWaitFrame needs no new JNI — correct — but those are VIEW matrices (inverse head pose) at floats [35..66]; invert/extract translation.
5. ProUpgrade.kt:150 maps "eye_tracking"/"gaze"/"autofocus" to a billing feature flag — the new toggle may need to respect Pro gating.
6. CLAUDE-BUILD-GUIDE.md:253-254 documents the com.google.android.xr.permission namespace that GazeController copied — when deleting/fixing GazeController, fix that doc too. Per CLAUDE.md "Eye/face tracking type constants differ from Khronos spec on Samsung runtime", the HUD-first on-device validation step is mandatory. **The lazy-create error path disables itself after 3 failures (xr_renderer.cpp:1683, 1783)** — a wrong permission or struct constant will silently latch the feature off for the session.
7. Zero-alloc invariant and the every-3-frames poll cadence (FilamentModelActivity.kt:4276-4279) are real constraints the rec already respects. >5 files changed requires stating the plan first (CLAUDE.md Workflow Protocol).
8. Useful hooks verified: SkeletonRuntime.kt `indexOf(name)`:64, `jointWorldPos`:73, `composeJointEulerXYZ`:163, `update()`:186; manifest already declares android.permission.EYE_TRACKING_COARSE/FINE + FACE_TRACKING (AndroidManifest.xml:15-17).

---

## R10: Skin-specific shading: pre-integrated SSS LUT with per-vertex curvature, dual-lobe GGX, F0=0.028

`category=materials · devices=both · impact=high · effort=days · verdict=confirmed`
**Files:** `GlesModelRenderer.kt`, `scene/InputHandler.kt` (+ FilamentModelActivity.kt, UiRenderer.kt, SceneManager.kt per trap 2)

### Spec

The single uber-PBR shader uses plain Lambert diffuse (GlesModelRenderer.kt:2865) and dielectric F0=0.04 (:2860) — at 30 cm in stereo this is the gray-plastic look: no red terminator on ears/fingers/nose, no soft bleed at the shadow edge. Penner pre-integrated SSS is the canonical mobile-budget fix (single forward pass, zero extra render targets — exactly what this tiler-friendly renderer wants).

1. Bake a 128×128 RGB8 Penner LUT offline (d'Eon/Luebke sum-of-Gaussians diffusion over `u = NdotL*0.5+0.5`, `v = curvature 1/r`; standard Penner reference code); ship in assets, bind to texture unit 5.
2. **Per-vertex curvature at GLB load** (Tripo models ship no authored maps): `curvature ≈ mean over incident edges of |n_i - n_j| / |p_i - p_j|` clamped [0,1], uploaded as a 1-float attribute (~30 lines, next to the R3 tangent work).
3. Gate on a per-model skin-mode uniform (new toggle in the slider system — GLBs here are single-material): replace the key+fill diffuse NdotL factor with `texture(uSssLut, vec2(dot(N,L)*0.5+0.5, vCurvature)).rgb`; specular keeps hard NdotL.
4. **Dual-lobe spec:** evaluate the existing GGX D twice — lobe A with alpha, lobe B with `min(alpha*2.0, 1.0)`, `spec = mix(specA, specB, 0.15)` (~8 ALU; Epic Siren values) — oily sheen over broad gloss.
5. `F0 = vec3(0.028)` (IOR 1.4) when skin mode is on.
6. **Cheap fallback if the LUT slips:** wrap diffuse `saturate((NdotL + 0.35) / (1.35 * 1.35))` tinted `vec3(1.0, 0.85, 0.8)` in the wrap zone — 3 lines, half the benefit.

### Verifier corrections & device traps

1. **"Add as param 13" is wrong:** param 13 is already the BeatReactor toggle. The panel spans indices 0-26 (PARAM_NAMES, FilamentModelActivity.kt:115-122); continuous sliders are 0-12 (PARAM_RANGES :129-133, setParamValue InputHandler.kt:478-497); toggles/actions 13-26 dispatch in InputHandler.kt:2051-2150 with long-press-reset branches at :2442+ and :2556+. **A skin-mode toggle must be appended as index 27.**
2. **Files-touched list is incomplete:** the toggle also requires FilamentModelActivity.kt (PARAM_NAMES), UiRenderer.kt (params display array :1911-1964 and toggle rendering branch :2131), and InputHandler.kt dispatch; SceneManager.kt:620-621 serializes per-model material params in scene saves — add skin mode there if it should persist.
3. **Naming trap:** a uniform `uSkinned` already exists meaning skeletal-skinning LBS gate (vertex shader :2721, also SHADOW_VERTEX_SHADER :2923) — the proposed `uSkinMode` is dangerously similar; **use uSssEnable/uSkinShading**.
4. **Curvature must be computed BEFORE the unweld:** non-skinned normal-mapped GLBs are unwelded to triangle soup (:1315-1341), destroying shared-vertex adjacency — compute curvature on the welded mesh and carry it through the unweld remap; skinned Tripo rigs skip unweld and keep shared indices. Curvature is bind-pose only (won't track LBS deformation — acceptable). Attribute location 6 is free (locations 0-5 in use: aPosition/aNormal/aTexCoord/aTangent/aJoints/aWeights, :2709-2714).
5. No assets/ dir exists yet under prism/app/src/main/ — create it, or **bake the 128×128 LUT procedurally at GL init** to skip asset plumbing.
6. Shader outputs LINEAR (sRGB swapchain does the encode, comment :2908-2910) — bake/sample the LUT in linear space, upload with no mips + CLAMP_TO_EDGE.
7. Pre-existing quirk the dual-lobe work collides with: fill-light spec reuses key-light F and G (:2874) — R6 item 3 fixes this; sequence R6 first or decide here.
8. CLAUDE.md: build after every change; LUT creation at load time only (zero-alloc); GLES 300 es shader style; functions under 50 lines; Kotlin-only change (no JNI/41-float involvement). Texture units 0-3 in use by the model pass (:1709-1734) — units 4/5 free.

---

## R11: Per-pixel real-world occlusion via XR_ANDROID_depth_texture, with XR_ANDROID_scene_meshing as mesh-occluder + real-furniture shadow-receiver follow-on

`category=platform-data · devices=galaxyxr · impact=high · effort=days · verdict=confirmed`
**Files:** `cpp/xr_scene_occlusion.h`, `cpp/xr_scene_occlusion.cpp`, `cpp/xr_renderer.cpp`, `cpp/CMakeLists.txt`, `cpp/renderer_jni_bridge.cpp`, `GlesModelRenderer.kt`, `scene/SceneOcclusionManager.kt` (+ FilamentModelActivity.kt per trap 3 → 8 files, requires stated plan per CLAUDE.md)

### Spec

Occlusion today is flat detected-plane polygon fans in a depth-only pre-pass (xr_scene_occlusion.cpp; plane-only limitation recorded at xr_scene_occlusion.h:9-16) — a person/pet/curved sofa in front of the dancer will not occlude her, and her shadow clips to invisible flat quads.

**CORE (days):** new module `xr_depth_occlusion.{h,cpp}` mirroring the SceneOcclusion pattern:
- Probe **XR_ANDROID_depth_texture** in the already-logged startup extension dump (xr_renderer.cpp:118-121); query `XrSystemDepthTrackingPropertiesANDROID`; create a depth swapchain with smooth-depth + confidence flags at 320×320; per frame acquire `XrDepthViewANDROID` (FOV + pose) per eye between xrBeginFrame/xrEndFrame.
- Integration: a fullscreen depth-WRITE pre-pass hooked exactly where sceneOcclusionHook runs in renderEye (after clear, before models) — one quad sampling the 320px depth texture, reprojecting through the depth-view pose/FOV into the current eye, writing gl_FragDepth, discarding where confidence < threshold. The dancer's existing depth test then occludes for free — anything physical in front of her (people, pets, furniture, the user's own body) occludes correctly.
- Keep the plane pass for crisp wall/table edges; add a confidence-weighted dither band (~10 cm) at discontinuities to soften 320px stair-stepping. Budget: well under 1 ms. Permission SCENE_UNDERSTANDING_FINE is already declared (AndroidManifest.xml:13-17).

**FOLLOW-ON (days):** extend xr_scene_occlusion.cpp with **XR_ANDROID_scene_meshing** (#719, Khronos-published, shipped in Godot OpenXR Vendors 5.1) — snapshot the labeled room mesh every ~10 s into a preallocated max-size VBO/IBO; use it (a) as curved-occluder replacement for the polygon fans, and (b) rendered as a shadow RECEIVER via the existing SP_FRAGMENT_SHADER PCSS path so her shadow drapes over real furniture; filter ceiling submeshes by semantic label.

VERIFY ON DEVICE: both extensions' presence on the current Samsung runtime build (depth_texture is registry-pending Preview, so struct-layout drift is real — apply the Samsung-trap protocol: struct-size logging, guarded first call, confirm acquire-inside-frame timing matches the synchronous nativeWaitFrame/nativeSubmitFrame loop).

### Verifier corrections & device traps

1. **There are TWO flat-plane occlusion passes, not one** — a Kotlin coarse rectangular-quad depth pass (GlesModelRenderer.renderOcclusionPlanes, :1528, called at :1659, driven by shadowPlanes) AND the native polygon-fan pass (sceneOcclusionHook at :1664). The new depth-write pre-pass slots after BOTH. The comment at :1661-1663 calling the native pass "scene-mesh occlusion" is misleading naming, not an actual mesh.
2. xr_scene_occlusion.h:9-11 cites XR_MSFT_scene_understanding / XR_META_scene_mesh as the unavailable tier-1 (not the XR_ANDROID names); SceneOcclusionManager.kt:13-16 records that "XR_ANDROID_scene_meshes" was checked and ABSENT on the Samsung runtime at the time of writing — the FOLLOW-ON hinges entirely on the current runtime build now exposing it. The startup extension dump at xr_renderer.cpp:118-121 is the ground truth; CLAUDE.md's Iron Rule (never declare platform limitation without enumerating every extension) supports re-checking rather than trusting that old comment. The CORE depth_texture path has the same on-device existence risk.
3. **FILES omission:** FilamentModelActivity.kt will also be touched (owns the sceneOcclusionHook wiring at :539-540, the SceneOcclusionManager instance at :96, and the nativeWaitFrame/nativeSubmitFrame loop at :2039/:4270) — that brings the change to 8 files; CLAUDE.md Workflow Protocol requires stating the plan and waiting for approval when >5 files change.
4. **[galaxyxr] tag caveat:** all occlusion/native code lives in main and is shared with the quest flavor — gate via the existing runtime hasExt() probe (xr_renderer.cpp:131-136), not source-set placement; on Quest the extension simply won't enumerate.
5. **Samsung device traps:** struct layouts/enum values/XrStructureType constants differ from spec drafts — depth_texture being registry-pending makes struct-size logging and a guarded first call mandatory. xrGetAllTrackablesANDROID-style signature drift is precedent (4-param vs 5-param Khronos). Output-count pointers must be valid even at capacity=0. xrCreateInstance failures hide extension dependency chains — read the FULL error. Zero-alloc render loop (preallocate depth-view structs + reprojection scratch). Native OpenXR inits before Jetpack XR. New features in new files (xr_depth_occlusion.{h,cpp} complies with Invariant 9).

---

## R12: Parse all GLB primitives + alphaMode/alphaCutoff + occlusionTexture/baseColorFactor; alpha-to-coverage hair; per-primitive culling

`category=materials · devices=both · impact=high · effort=week-plus · verdict=confirmed`
**Files:** `GlesModelRenderer.kt`

### Spec

loadGlb parses `meshes[0].primitives[0]` ONLY (GlesModelRenderer.kt:773) — quality avatars ship eyes/lashes/hair/teeth as separate primitives with separate materials, all silently dropped, capping the asset ceiling regardless of shader work. alphaMode/alphaCutoff are unparsed and `fragColor.a` is hardcoded 1.0 (:2911) so hair cards/lashes are impossible; occlusionTexture and baseColorFactor are ignored, discarding baked crease AO (nostrils, under-chin, between fingers) and giving untextured tinted materials wrong albedo.

1. **Loader:** iterate ALL primitives of the node's mesh (and ideally all mesh nodes under the skinned root); per primitive a VAO/EBO + material record `{ baseColorFactor (multiply after sRGB decode — the factor is linear per spec), metallic/roughnessFactor, occlusionTexture → texture unit 6, alphaMode, alphaCutoff, doubleSided }`; skinned primitives share the existing 64-joint UBO. Draw order: opaque front-to-back, MASK next, BLEND last (depth-write off, back-to-front).
2. **AO:** multiply ONLY ambient/IBL terms (SH diffuse + env specular via Filament-style `computeSpecularAO ≈ saturate(pow(NdotV + ao, alpha) - 1.0 + ao)`) — never direct light.
3. **Hair/lashes:** alphaMode=MASK + `glEnable(GL_SAMPLE_ALPHA_TO_COVERAGE)` under R1's 4x MSAA — order-independent, early-Z-friendly, no sorting; write `fragColor.a = baseColor.a`; draw hair last to confine the early-Z loss from discard.
4. **Optional polish:** Kajiya-Kay dual shifted highlights for hair-tagged materials (Scheuermann 2004: `spec = pow(sqrt(1 - dot(T', H)^2), exp)`, `T' = normalize(T + s*N)`, lobes s = +0.1/-0.1, secondary tinted by hair color).
5. **Re-enable backface culling** for primitives not marked doubleSided — cull is currently disabled for the whole color pass — free fill-rate on the Adreno.

### Verifier corrections & device traps

1. **Line cites:** single-primitive parse block is :772-835 (not 750-812); material block is :883-914 (not 851-891). The culling citation ~:1564-1595 actually points at the SHADOW pass where front-face culling IS enabled — the real gap is that the color pass (drawModels :1692-1772) never enables backface culling, and :1623 explicitly disables cull after every shadow pass, so **per-primitive cull must be set inside the color draw loop each frame**.
2. **Alpha-to-coverage depends on MSAA that does NOT currently exist** (swapchains sampleCount=1, xr_renderer.cpp:546/:1238). Without R1, GL_SAMPLE_ALPHA_TO_COVERAGE degrades to a hard binary threshold. Fallback for MASK without MSAA: shader `if (a < uAlphaCutoff) discard;`.
3. **Per-primitive split touches more than the color pass:** the shadow pass (:1598-1615) draws m.vao/m.indexCount per model and must iterate the new primitive list too, or hair/eyes vanish from shadows; bounding-sphere computation (:840-851) and anything using model.indexCount likewise. Skinned primitives each need their own JOINTS_0/WEIGHTS_0 VBOs in their VAOs (parse exists at :1032+ for the single primitive).
4. **"All mesh nodes under the skinned root" requires node-hierarchy parsing that doesn't exist at all** — loadGlb never reads nodes/scenes; multi-node avatars need node-local transforms baked into vertices or a per-primitive model matrix, else parts misalign.
5. CLAUDE.md: zero-alloc invariant — the existing skinned-palette upload already allocates a direct ByteBuffer per frame at :1759; do NOT copy that pattern for per-primitive uploads, hoist scratch buffers (R15 fixes the existing instance). Functions under 50 lines — loadGlb is already ~600 lines; consider decomposing the loader per Invariant 9. GLES 3.0 only. Test on hardware.

---

## R13: Single-pass stereo via GL_OVR_multiview2 (arraySize=2 swapchain, gl_ViewID_OVR shaders, one renderStereo pass)

`category=performance-headroom · devices=both · impact=high · effort=week-plus · verdict=confirmed`
**Files:** `cpp/xr_renderer.cpp`, `cpp/renderer_jni_bridge.cpp`, `GlesModelRenderer.kt`, `FilamentModelActivity.kt` (+ `cpp/xr_scene_occlusion.cpp` per trap 4)

### Spec

Two independent per-eye swapchains with arraySize=1 mean all geometry is drawn twice, every vertex/skinning shader runs twice, and the joint palette uploads per eye (xr_renderer.cpp:538-548; repo grep for 'multiview' returns nothing). For a skinned 100-200k-tri dancer plus grid/planes/markers/gizmos (~15+ draws per eye), multiview roughly halves CPU submit and vertex/skinning GPU cost — permanent headroom that funds the R7 supersampling+MSAA tier under thermals.

- **Native:** ONE color swapchain (and one depth swapchain per R16) with arraySize=2; both projection views reference the same swapchain with imageArrayIndex 0/1; acquire once per frame.
- **Kotlin GL:** attach via `glFramebufferTextureMultiviewOVR(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, tex, 0, 0, 2)` — or `glFramebufferTextureMultisampleMultiviewOVR(..., samples=4, ...)` to combine with R1 in one extension call (both need small JNI shims like R1's).
- **Shaders:** `#extension GL_OVR_multiview2 : require` + `layout(num_views=2) in;` in the vertex shaders; per-eye matrices become mat4 arrays indexed by `gl_ViewID_OVR` (view-space lighting uniforms likewise become per-view).
- Collapse renderEye(left)+renderEye(right) into renderStereo(); the shadow pass is already shared; skeleton.update() and palette upload naturally become once-per-frame.
- **SEQUENCE AFTER R1 and R8**, and validate on Quest with ovrgpuprofiler that scaled-bin foveation actually engages with a multiview FBO (Khronos forum reports of GLES FFR silently no-opping with multiview on older OS versions).

### Verifier corrections & device traps

1. The GlesModelRenderer.kt:1716-1741 citation is off — those lines are texture binds; the skinning block is :1744-1769 (skel.update() :1752, UBO upload :1753-1765). Same function, substance correct.
2. **There are NO uViewMatrix/uProjection uniforms to replace:** shaders consume CPU-premultiplied uMVP + uModelView per draw (:1694-1700, shader source :2718). Multiview conversion must make uMVP/uModelView/uNormalMatrix per-view arrays (or restructure to uModel + uViewProj[2]); view-space light dirs (:1671-1683) become per-view as claimed.
3. Program count is 12 in GlesModelRenderer.kt, not 13 — and several (shadowDepth light-space pass, bloom fullscreen passes) don't need num_views.
4. **MISSING FILE: xr_scene_occlusion.cpp** — its native depth-only occlusion program (kVertexSrc :30, uMVP) draws into the eye FBO via sceneOcclusionHook from inside renderEye (:1664) and must also be multiview-converted, plus renderOcclusionPlanes (:1659).
5. **Post-process complication understated:** renderBloom (:2188) samples the per-eye swapchain texture as sampler2D into quarter-res ping-pong FBOs and composites back; with arraySize=2 the color image is a TEXTURE_2D_ARRAY, so bloom/colorWash/finishEyePass need sampler2DArray or per-layer reruns — geometry pass halves, bloom chain stays ~per-eye.
6. Preserve two existing behaviors in the single stereo swapchain: the foveation create-retry fallback (xr_renderer.cpp:528-605, XR_SWAPCHAIN_CREATE_FOVEATION_SCALED_BIN_BIT_FB) and the thermal-downgrade sub-rect reporting (:905-913 + imageRect.extent :985-987).
7. **Samsung trap:** arraySize=2 swapchain + FB foveation bin behavior must be validated via adb logcat on the actual device, not assumed from spec; the Quest ovrgpuprofiler FFR check is good but the Samsung divergence is the larger risk. Invariant 8 applies to the new glFramebufferTextureMultiviewOVR JNI shims.
8. **Allocation bug worth fixing in the same pass:** the palette upload allocates a fresh direct ByteBuffer per model per eye per frame (:1759-1760) — hoist it regardless of multiview (R15 does this earlier).
9. FilamentRenderer.kt's renderEye is dead for this activity — correctly out of scope.
10. The UI quad swapchain (xr_renderer.cpp:1240, arraySize=1) is a separate XrCompositionLayerQuad with eyeVisibility BOTH — unaffected.

---

## R14: Quest output correctness: set XR_FB_color_space to Rec709 and chain XR_FB_composition_layer_settings sharpening

`category=display-pipeline · devices=quest · impact=medium · effort=hours · verdict=confirmed`
**Files:** `cpp/xr_renderer.cpp`, `cpp/xr_renderer.h`

### Spec

Neither extension is requested (xr_renderer.cpp:112-256) and no XrCompositionLayerSettingsFB is chained in the submit path (:960-1042).

1. **Color space (~25 lines):** request `XR_FB_color_space` at instance creation (runtime-gated via the existing hasExt() pattern — Samsung simply won't enumerate it); after session create, `xrEnumerateColorSpacesFB` then `xrSetColorSpaceFB(session, XR_COLOR_SPACE_REC709_FB)`. The pipeline is correctly sRGB end-to-end (GL_SRGB8_ALPHA8 swapchain, linear shader output, :504-516), so declaring Rec709 lets Meta's compositor gamut-map correctly instead of stretching primaries in the legacy Rift-era default — which **oversaturates skin**, a direct parity failure vs Galaxy XR's ~P3-managed micro-OLED for a product whose literal content is skin tone. Verify with a skin-tone test GLB side-by-side on both headsets.
2. **Sharpening (~40 lines):** request `XR_FB_composition_layer_settings`; chain XrCompositionLayerSettingsFB on the projection layer in xrEndFrame with a per-frame policy — if `effectiveScale = renderScale_ * ssScale < 1.0` (the thermal 0.75 stage currently relies on naive bilinear compositor upscale, :905-913, :2379-2390) set `QUALITY_SHARPENING_BIT_FB` (Meta Quest Super Resolution, OS v55+; consider NORMAL_SHARPENING/RCAS for mild deficits); at/above recommended res set no sharpening bits or `AUTO_LAYER_FILTER_BIT_META` (0x20) — Meta explicitly warns QUALITY_SHARPENING at near-native res causes temporal shimmer on fine detail (skin pores, fabric), so gate it off when R7 supersampling is active; when rendering above display res the SUPER_SAMPLING bits (0x1/0x2) improve the downfilter instead. No Kotlin surface needed.

### Verifier corrections & device traps

1. **The formula references an `ssScale` that does NOT exist in current code** — only renderScale_ exists (default 1.0, thermal stages 0.75/0.5 via nativeSetRenderScale from FilamentModelActivity.kt:1756/:1859). "R7 supersampling" is a sibling rec, not current code; if R7 hasn't landed, write the policy against renderScale_ alone.
2. **AUTO_LAYER_FILTER_BIT_META (0x20) belongs to XR_META_automatic_layer_filter**, a separate extension layered on XR_FB_composition_layer_settings — it must ALSO be requested (hasExt-gated) before setting that bit.
3. The only "sharpening" anywhere in the repo is a GLSL unsharp-mask in the Kotlin video-effects pipeline (ColorGradingEffect.kt) — irrelevant to this unmanaged model-viewer path.
4. **FB functions must be resolved via xrGetInstanceProcAddr** (see the initFbPassthrough pattern at xr_renderer.cpp:924-929) — the loader does not statically export xrEnumerateColorSpacesFB/xrSetColorSpaceFB. xrSetColorSpaceFB goes after session creation (around :451).
5. Chain XrCompositionLayerSettingsFB only inside the `frame.shouldRender` branch (layerCount is 0 otherwise) and keep the struct alive until xrEndFrame returns (stack-local in submitFrame is fine).
6. "If xrCreateInstance fails, read the FULL error" — extensions have hidden dependency chains on this runtime. Quest OS v55+ requirement and the shimmer-at-native-res warning are Meta-doc claims not verifiable from the repo; the side-by-side skin-tone GLB test on real hardware is the right validation. Check adb logcat OpenXR:* for the new extension log lines after building.

---

## R15: Tiled-GPU hygiene: glInvalidateFramebuffer on depth/bloom targets, cached joint-palette buffer + once-per-frame skeleton update, drop dead Filament libraries

`category=performance-headroom · devices=both · impact=medium · effort=hours · verdict=confirmed`
**Files:** `GlesModelRenderer.kt`, `FilamentModelActivity.kt`, `prism/app/build.gradle.kts`

### Spec

1. **glInvalidateFramebuffer (zero calls exist, repo-verified):** the eye FBO's DEPTH_COMPONENT24 renderbuffer, the shadow pass's depth RB, and bloom ping-pong contents are stored to DRAM at every tile flush with no consumer. After the last draw per eye: `GLES30.glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, [GL_DEPTH_ATTACHMENT])` before unbinding/releasing the swapchain image; same for the shadow FBO's depth RB (the shadow payload is the color texture today, the depth texture per R4) and bloom FBOs after composite. On a ~2016×2112 supersampled buffer this saves ~16MB depth write+read per eye per frame — roughly the 1-1.5 ms/pass Meta attributes to needless tile stores. **EXEMPTION: if R16 lands, the submitted depth attachment must NOT be invalidated.**
2. **Joint palette:** a fresh direct ByteBuffer is allocated per skinned model PER EYE and skeleton.update() runs twice per frame — violating the repo's own zero-alloc invariant (#6). Cache one direct ByteBuffer per model at load; hoist skeleton.update() out of renderEye to once per frame.
3. **Drop Filament:** Filament 1.71.0 (3 AARs) ships in BOTH flavors and is System.loadLibrary'd at activity start though FilamentRenderer.kt is never instantiated — delete the loadLibrary calls, the three AAR deps, dead FilamentRenderer.kt and renderUiOverlay. Startup time + APK size, zero behavioral risk.

### Verifier corrections & device traps

- (a) **Line cites:** joint-palette code is at GlesModelRenderer.kt:1747-1769, not 1716-1741. Filament loadLibrary is FilamentModelActivity.kt lines 25-27 — **THREE calls to delete, keep line 24** (openxr_input).
- (b) **Sizes:** MAX_JOINTS_SHADER=64 (:25), so the per-draw direct ByteBuffer is 4KB, not 16KB; garbage is ~8KB/frame per skinned model (not 32KB). skeleton.update() runs twice per frame PER SKINNED MODEL. The "~2016×2112 / ~16MB depth" figures are runtime estimates, not repo-verifiable.
- (c) **INVALIDATION PLACEMENT:** renderEye returns with the FBO deliberately left bound (:1776 "// FBO stays bound") and later per-eye passes (renderGrid, renderShadowPlanes, renderGizmo, renderLaser, renderColorWash, renderBloom) draw into the same FBO, some with depth test on — **the depth invalidate MUST go inside finishEyePass (:2089) before the unbind, NOT at the end of renderEye.** Invalidate ONLY GL_DEPTH_ATTACHMENT — the color attachment is the OpenXR swapchain texture. Bloom A/B color can be invalidated after composite (each pass fully overwrites). Shadow: invalidate only the depth RB; the R32F color texture is sampled by the main pass (until R4 converts it). The R16 exemption is moot today — no depth composition layer exists in cpp yet.
- (d) **SKELETON HOIST BONUS/TRAP:** renderShadowMap (FilamentModelActivity.kt:3994) runs BEFORE both renderEye calls and binds the joint UBO relying on the COLOR pass's glBufferSubData (comment GlesModelRenderer.kt:1602-1608) — i.e., **the shadow silhouette currently uses the PREVIOUS frame's palette.** The hoisted once-per-frame update+upload must run before renderShadowMap; this also fixes that latent one-frame-stale shadow skinning.
- (e) **FILAMENT REMOVAL: do NOT rename FilamentModelActivity** — 40+ JNI symbols in renderer_jni_bridge.cpp are mangled from that class name (`Java_com_ashairfoil_prism_FilamentModelActivity_*`). Only delete the 3 loadLibrary lines, the 3 AAR deps (build.gradle.kts:108-110), FilamentRenderer.kt, and the uncalled renderUiOverlay (GlesModelRenderer.kt:2095, zero callers — menu quad is composited natively via nativeInitUiQuad/nativeSetUiVisible, renderer_jni_bridge.cpp:157-196) plus its uiProgramId/uiVao init/cleanup if now orphaned (check destroy() at :2288-2289). Also update stale comments: build.gradle.kts:39-40 quest-flavor comment says "native OpenXR + Filament", and CLAUDE.md:17/:73 still lists Filament 1.69.5 + FilamentRenderer.kt in the file map (gradle actually has 1.71.0).
- (f) glInvalidateFramebuffer is core GLES 3.0 so no extension risk, but hardware validation is still required; **quest flavor must also still compile** since Filament deps are currently in BOTH flavors.

---

## R16: Submit scene depth via XR_KHR_composition_layer_depth for positional timewarp stability at close range

`category=display-pipeline · devices=both · impact=medium · effort=days · verdict=confirmed`
**Files:** `cpp/xr_renderer.cpp`, `GlesModelRenderer.kt`, `cpp/renderer_jni_bridge.cpp`

### Spec

No depth swapchain exists (color swapchain usage flags are COLOR_ATTACHMENT|SAMPLED only) and projection views carry no XrCompositionLayerDepthInfoKHR (xr_renderer.cpp:540-549, :972-995) — depth lives only in the private EGL renderbuffer (GlesModelRenderer.kt:1636-1641). With near plane 0.05 m (xr_renderer.cpp:917) and a use case of leaning INTO a close-range character, Quest gets rotational-only timewarp: every dropped/late frame produces positional judder on the nearest geometry, exactly where the user is looking — quality the panel never shows in screenshots but every user feels.

- Create a second swapchain per eye, format GL_DEPTH_COMPONENT24 (or D32F if enumerated), usage `XR_SWAPCHAIN_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | SAMPLED`, acquired/released in lockstep with color.
- In renderEye, attach the acquired depth image via glFramebufferTexture2D replacing the private depthRb — if combined with R1, use glFramebufferTexture2DMultisampleEXT on the depth attachment too (same EXT supports it).
- Chain `XrCompositionLayerDepthInfoKHR` onto each XrCompositionLayerProjectionView with `minDepth=0, maxDepth=1, nearZ=0.05, farZ=100` matching the projection (standard non-reversed depth as today, mapping verified at xr_renderer.cpp:1199-1218).
- Galaxy XR can consume KHR depth if it enumerates the extension — runtime-gate as usual.
- Interaction: once depth is a submitted attachment, **exempt it from R15's end-of-frame invalidation**. Also the prerequisite for XR_FB_space_warp eligibility (not recommended for this content, but kept open).

### Verifier corrections & device traps

- **Citation correction:** the "(:917)" near-plane cite is xr_renderer.cpp:917, NOT GlesModelRenderer.kt (line 917 there is a texture-cap comment).
1. **Foveation interaction:** color swapchains are optionally created with XR_SWAPCHAIN_CREATE_FOVEATION_SCALED_BIN_BIT_FB via a two-attempt fallback (xr_renderer.cpp:528-605, foveationSwapchainConfigured_). A foveated color attachment combined with a non-foveated depth attachment in the same FBO is invalid on Quest — **the depth swapchain must be created with the SAME foveation create-info inside the same attempt loop so both chains fall back together.**
2. **Thermal downgrade:** acquire computes a sub-rect (renderScale_, :909-913) and projViews subImage extent uses it (:985-987) — XrCompositionLayerDepthInfoKHR.subImage.imageRect must mirror the same downscaled extent.
3. The acquire failure path (~:890-898) releases previously acquired eye images and sets shouldRender=false — depth images must be included in that unwind to stay in lockstep.
4. The extension must be ADDED to the instance create list (xr_renderer.cpp:124-253 region) gated on enumeration via the existing hasExt pattern — it is currently never requested.
5. **Samsung trap:** even though KHR depth is core-reserved, validate on hardware via adb logcat and runtime-gate exactly as specced — do not assume spec behavior. Never reverse init order (native OpenXR first, then Jetpack XR).
6. R15 interaction is consistent: no glInvalidateFramebuffer exists today, so the exemption targets the sibling rec, not current code.
7. No MSAA today, so the R1-combination clause is conditional/future — correct as stated.
8. Scoping correct for [both]: C++ renderer + GlesModelRenderer live in shared main; only the unmanaged FilamentModelActivity path is touched; the managed SurfaceEntity video path is unaffected.
9. **Behavior note:** renderEye writes real-world occluder depth (renderOcclusionPlanes + sceneOcclusionHook, GlesModelRenderer.kt:1657-1664) into the same depth attachment before model draws — once submitted, occluder depth becomes compositor-visible timewarp depth. Generally desirable, but make it a conscious decision.
10. renderer_jni_bridge.cpp needs the depth texture id plumbed through the per-eye JNI call alongside frame.eyes[eye].textureId (xr_renderer.cpp:901).

---

## Execution Order

```
Phase 0 (DONE this session):
  Emitter↔slider sync, AudioReactor failure surfacing, both flavors compiling.

Phase 1 — Quick wins (hours each):
  R6  Color hygiene                ← IN PROGRESS
  R2  Consume light direction      ← NEXT UP (galaxyxr; needs hardware verify of sign convention)
  R14 Quest Rec709 + sharpening    (quest; pure native, ~65 lines)
  R15 Tile hygiene + drop Filament (both; also fixes 1-frame-stale shadow skinning)
  BUILD + SIDELOAD + VERIFY after each.

Phase 2 — Transformative tentpoles (days each):
  R3  Restore normal mapping       (verifier says: mostly call-the-dead-code; cheapest tentpole — do first)
  R4  Dancer shadows               (depth-texture conversion + model-shader PCF)
  R5  Specular IBL Tier B          (baked cmgen cubemaps; rescues Quest ambient)
  R1  4x MSAA + specular AA        (JNI shim; verify MSAA × foveation on hardware)
  BUILD + SIDELOAD + VERIFY after each.

Phase 3 — Remaining high-impact + medium:
  R8  Quest FFR apply-once + persistence   (funds R7)
  R7  Supersampling + perf levels + 90Hz   (consumes R8's headroom)
  R10 Skin SSS + dual-lobe GGX             (after R6's fill-BRDF fix)
  R12 Full GLB primitive parsing           (hair needs R1's MSAA for alpha-to-coverage)
  R9  Eye-contact (galaxyxr)               (HUD-validate gaze data first)
  R11 Depth-texture occlusion (galaxyxr)   (extension presence check first; >5 files → state plan)
  R16 Composition layer depth              (after R1; foveation-matched swapchain)
  R13 Multiview single-pass stereo         (LAST — biggest refactor, sequence after R1 + R8)
  R5-Tier A  Live cubemap probe (galaxyxr) (after R5 Tier B proves the shader path)
```

Rationale: Phase 1 items are hours-effort, single-digit-file changes with immediate visible payoff. Phase 2 is where the dancer stops looking like a game character (normals, shadows, reflections, AA). Phase 3 sequences by dependency: R8 before R7 (headroom before spending it), R6 before R10 (fill-BRDF fix), R1 before R12/R16 (MSAA + EXT shim prerequisites), R13 absolutely last (touches every shader and the frame loop).

---

## Standing Rules (non-negotiable, per repo CLAUDE.md)

1. **Build after every change.** Both flavors (`gradlew assembleDebug` builds galaxyxr + quest debug variants). Fix before continuing. Each broken APK costs 5+ minutes of sideloading.
2. **Verify Samsung-runtime behavior on hardware via `adb logcat`** (`adb logcat -s OpenXR:* ChloeVR:* AndroidRuntime:*`). Static reasoning about XR runtime behavior is not verification.
3. **NEVER trust Khronos spec drafts for XR_ANDROID extensions.** The Samsung Galaxy XR runtime diverges: API signatures, struct layouts, enum values, and XrStructureType constants differ (precedents: 4-param xrGetAllTrackablesANDROID, swapped light-estimation enums, divergent eye/face constants). Validate every new struct/constant via struct-size logging + guarded first call + logcat.
4. **Never claim "platform limitation"** without enumerating every extension, checking what the code IS vs ISN'T using, and actually trying it (Iron Rule #6 — re-testing old dismissals is how R5 Tier A and R11's follow-on get unblocked).
5. **Zero-alloc render loop** (Invariant 6), **41-float contracts touched sequentially only**, **new JNI = `private external fun` + public wrapper** (Invariant 8), **new features in new files** (Invariant 9), **functions under 50 lines**.
6. **Controllers only.** No hand-tracking features, ever.
