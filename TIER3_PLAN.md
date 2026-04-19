# TIER 3 PLAN — Musical Dynamics, Motion Expression, Glute Deformation

Drafted at the end of Tier 2 (dynamic pivots + anatomy marks + foot anchor + face mark) for execution in a fresh session.

## Mission

Take the dance engine from *"rhythmically accurate but flat"* to *"breathes with the music."* Four feature families:

1. **Musical Amplitude** — BPM stays locked, but every downstream magnitude (dance amp, strobe, haptic, impact kick) breathes with the track's actual loudness. Quiet parts whisper, drops explode.
2. **Intensity Range Expansion** — user needs more than 2× headroom for occasional "go wild" moments.
3. **Motion Expression Sliders** — per-axis *sharpness* and *complexity* knobs so the user can dial in "how sharp the hips hit" and "how syncopated the groove" without knowing ease-curve names.
4. **Glute Deformation** — vertex-shader push-out with beat-synced pop, so the butt can actually move *outward* (not just angularly) on bass kicks.

## What's in scope this tier, what's not

**In scope.** All four features above. Existing engine is the substrate; no structural refactor required. Every feature layers cleanly on top of Tier 2.

**Out of scope.** Full rigging, IK, blendshapes, per-vertex mesh skinning, ML motion matching, auto face-rigging. Those are Tier 4+.

---

## FEATURE 1 — Musical Amplitude (the "breath" for everything)

### Problem
`AudioReactor.beatCounter` ticks at fixed BPM intervals. Every consumer downstream (strobe, vibrator, impact kick, beat-wash shadows) fires at **full magnitude on every beat** regardless of how loud the track actually is. During a breakdown — kick drums silent, melody alone — her hips still pop like it's the drop. The clock is right; the dynamics are broken.

### Concept
Add a smooth, always-available `musicalLevel: Float` in `0..1` that tracks overall loudness. BPM clock is unchanged — only the **magnitude** of each beat-triggered event scales with it.

### Signal design
```
rms_instant = sqrt((bass² + mid² + high²) / 3)
musicalLevel = envFollow(rms_instant,
                         attack = 50ms,   // hits are punchy, don't smear the initial spike
                         release = 800ms) // fades smooth so breakdowns don't flicker
```

Optionally expose:
- `silenceFloor: Float = 0.0..1.0` — lowest value `musicalLevel` can reach. User sets via slider. At 0.0 = dead silent during quiet parts; at 0.3 = motion always hints along. Default 0.15.
- `dynamicRange: Float` — scalar multiplier on the (1 - floor) range. At 0 = fully gated (no dynamics), at 1 = full dynamics.

### Data model changes

**`AudioReactor.kt`**
```kotlin
@Volatile var musicalLevel: Float = 0f; private set
@Volatile var silenceFloor: Float = 0.15f
@Volatile var musicalDynamics: Float = 1.0f  // 0 = constant, 1 = full breathe
private var musicalLevelSmooth: Float = 0f

// In processFft, after rawBass/mid/high computed:
val rms = sqrt((rawBass*rawBass + rawMid*rawMid + rawHigh*rawHigh) / 3f)
val atk = 1f - exp(-dt / 0.05f)
val rel = 1f - exp(-dt / 0.8f)
val alpha = if (rms > musicalLevelSmooth) atk else rel
musicalLevelSmooth += (rms - musicalLevelSmooth) * alpha
// Apply floor + dynamic range:
musicalLevel = silenceFloor + (musicalLevelSmooth - silenceFloor).coerceAtLeast(0f) * musicalDynamics
```

**`FrameSnapshot`** — add `musicalLevel: Float`.

### Consumers to update

| Site | File : Line | Current | New |
|---|---|---|---|
| Impact kick amp | FilamentModelActivity:1948 | `3.0 * physicsAmount * effInt * ks * gazeGain` | `... * snap.musicalLevel` |
| Beat wash shadow | FilamentModelActivity:~1298 | `shadowDarkness * (1 - pct * 0.4 * bi)` | `... * (1 - pct * 0.4 * bi * musicalLevel)` |
| Haptic output | InputHandler BLE amp path | constant during beats | `× snap.musicalLevel` |
| Dance yaw/pitch/bob | FilamentModelActivity | `ampGain = accentGain * moodGain * fillAmp` | add `* snap.musicalLevel` *optionally* (gated by user toggle) |
| Strobe/bloom | `gr.bloomIntensity = 0.3f + pct * bi * 0.5f` | | `× musicalLevel` |

### UI
One new main-menu row: **"Silence Floor"** slider `0..1` (default 0.15). Optional: **"Music Breathe"** toggle per model — defaults on; when off, dance doesn't breathe, only strobe/haptic do. (Because some users want steady dance with breathing lights.)

### Effort
Moderate. AudioReactor ~50 lines. Each consumer 1-3 lines. UI ~20 lines. **Estimate: 1 session.**

### Gotchas
- Pre-existing `boxFillPct` is already amplitude-derived via the box/threshold system — don't double-gate. Musical level is a **separate** raw loudness measure, used where boxFillPct is NOT.
- During BPM search phase (before lock), `musicalLevel` still computes fine from raw band RMS — no dependency.

---

## FEATURE 2 — Intensity Range Expansion

### Problem
User currently cycles 0.25 → 0.5 → 1 → 1.5 → 2. They want headroom up to 5× for occasional blowouts.

### Change
Extend cycle to `0.25 → 0.5 → 1.0 → 2.0 → 3.0 → 5.0 → 0.25`. Display label updates. Internal `effInt = danceIntensity * 0.25f` **stays** so the subjective "1.0x" remains the calm default.

- Label "5.0x" → effInt = 1.25 (twice old "1x")
- Label "3.0x" → effInt = 0.75
- Label "0.25x" → effInt = 0.0625 (tiny)

### Effort
Trivial. **Estimate: 10 lines in one file.** Worth doing in the same session as Feature 1 for zero overhead.

---

## FEATURE 3 — Motion Expression Sliders

### Problem
Per-axis motion currently has three dials: `amp` (degrees), `rate` (musical subdivision), `ease` (enum: SINE / LINEAR / CUBIC / etc.). User said:

> *"in yaw / hip sway — can there be an option for both intensity or jerkiness and / or range of motion?"*

They want **continuous** control over the *feel* of the motion, not just the magnitude and tempo. Existing ease is a coarse choice.

### Concept
Add two new continuous per-axis params:
- **Sharpness** (0..1): blends SINE (round peaks) ↔ TRIANGLE (sharp corners, constant velocity). At 0 everything's round and smooth; at 1 the hips change direction on a dime.
- **Complexity** (0..1): layers a secondary wave at 2× the primary rate, scaled by `complexity * 0.4`. Creates 1/8 ghosts over a 1/4 main. Gives syncopated groove without manual rate-picking.

### Signal math
```kotlin
// Existing:
val primarySig = SceneManager.waveAt(phase, ease)

// New — Sharpness (blend SINE <-> TRIANGLE):
val sineSig = sin(2π * phase)                          // round
val triSig = sign(sineSig) * (1 - abs(1 - 2*phase))    // sharp corners
val shapedSig = lerp(sineSig, triSig, sharpness)

// New — Complexity (ghost layer at 2× rate):
val ghostPhase = (phase * 2f) % 1f
val ghostSig = sin(2π * ghostPhase)
val compositeSig = shapedSig + complexity * 0.4f * ghostSig

// Use compositeSig in place of yawSig / pitchSig / bob.
```

### Data model

**`SceneManager.PlacedModel`** — 6 new fields:
```kotlin
var yawSharpness: Float = 0f,     var yawComplexity: Float = 0f,
var pitchSharpness: Float = 0f,   var pitchComplexity: Float = 0f,
var bobSharpness: Float = 0f,     var bobComplexity: Float = 0f,
```

**`DancePreset`** — per-preset defaults:
```kotlin
val yawSharpness: Float = 0f,     val yawComplexity: Float = 0f,
...
```

### Per-preset tuning (recommended defaults)
| Preset | yawSharp | yawCmplx | pitSharp | pitCmplx | bobSharp | bobCmplx |
|---|---|---|---|---|---|---|
| SLOW WIND | 0.0 | 0.2 | 0.0 | 0.2 | 0.0 | 0.0 |
| SWAY | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| BODY ROLL | 0.0 | 0.3 | 0.3 | 0.3 | 0.2 | 0.0 |
| BOUNCE | 0.8 | 0.0 | 0.6 | 0.0 | 0.9 | 0.0 |
| GRIND | 0.3 | 0.4 | 0.6 | 0.2 | 0.2 | 0.0 |
| TWERK | 0.7 | 0.5 | 0.9 | 0.4 | 0.6 | 0.3 |
| SQUAT PULSE | 0.7 | 0.0 | 0.6 | 0.0 | 1.0 | 0.0 |

### UI
Add a new **"CHARACTER"** sub-mode in the beat panel (similar to how scenePickerMode toggles). Opens a 3×2 grid:

```
          SHARPNESS     COMPLEXITY
YAW       [slider ▢]    [slider ▢]
PITCH     [slider ▢]    [slider ▢]
BOB       [slider ▢]    [slider ▢]
```

Plus a **"BACK"** button. Add a **"CHARACTER"** button to Row C (replacing FOOT, which moves to main menu).

### Effort
Moderate-high. 6 new fields, 3 new math sites, new sub-panel UI. **Estimate: 1 focused session.**

### Gotchas
- The triangle wave `sign(sineSig) * (1 - abs(1 - 2*phase))` has a cusp at zero-crossings — verify it doesn't cause artifact pops with physics jiggle spring. If so, use a softer "square-ish" wave like `tanh(k * sin(2π * phase))` with k = 1 + 4*sharpness.
- Complexity ghost at 2× primary rate may exceed sweep-speed K ceiling. Re-check the amp×rate law against `sharpness + complexity` composite output.

---

## FEATURE 4 — Glute Deformation ("The Shaker")

### Problem
Currently she can twerk *angularly* (pelvis tilts up-down). But her butt can't *pop outward* — the mesh is rigid. Real twerk involves visible outward thrust of the glutes.

### Concept
Mark two points on her mesh (left glute, right glute) in model-local coordinates. A vertex shader displaces nearby vertices *outward along their surface normal* by a per-glute push amount. Beat-synced oscillation creates the "shake."

### Shader changes

**Vertex shader uniforms** (adds ~16 floats):
```glsl
uniform vec3 uGluteAPos;   // model-space position
uniform float uGluteARadius;   // influence radius
uniform float uGluteAPush;     // current push amount (beat-modulated on CPU)
uniform vec3 uGluteBPos;
uniform float uGluteBRadius;
uniform float uGluteBPush;
```

**Vertex shader math**:
```glsl
vec3 pos = aPosition;
vec3 displacement = vec3(0.0);

float dA = distance(aPosition, uGluteAPos);
if (dA < uGluteARadius) {
    float falloff = 1.0 - smoothstep(uGluteARadius * 0.3, uGluteARadius, dA);
    displacement += aNormal * uGluteAPush * falloff;
}

float dB = distance(aPosition, uGluteBPos);
if (dB < uGluteBRadius) {
    float falloff = 1.0 - smoothstep(uGluteBRadius * 0.3, uGluteBRadius, dB);
    displacement += aNormal * uGluteBPush * falloff;
}

pos += displacement;
gl_Position = uMVP * vec4(pos, 1.0);
// TBN for normal mapping: normals don't need re-derivation for small displacements
```

### Beat-sync oscillation (CPU side)
On each bass kick, set `gluteAPush = baseAmp * (1 + shakeIntensity)` then decay back to `baseAmp` over 250ms. Beat independent of phase means the shake is "sharp pop, soft decay" — reads as twerk-pop.

### Data model

**`PlacedModel`** — 2 new groups:
```kotlin
// Left glute
var gluteAPosLocal: FloatArray = FloatArray(3)  // zeros = unmarked
var gluteARadius: Float = 0.15f
var gluteABasePush: Float = 0f   // user-set push amount
var gluteACurrentPush: Float = 0f  // beat-modulated (CPU writes per frame)

// Right glute — same fields

// Shake control
var gluteShakeIntensity: Float = 0f  // 0..1, how much the kick spikes the push
var gluteShakeBpmSync: Boolean = true
```

### UI flow
1. User selects model → opens main menu → navigates to new entry **"Mark Glute L"**
2. Captures: aim laser at left glute, trigger-click → captures hit point as `gluteAPosLocal`. Same for R.
3. Row C gets a **"GLUTE"** sub-mode (or param slider) for:
   - Base push amount (0..10cm)
   - Radius (0..30cm)
   - Shake intensity (0..1)
   - BPM sync toggle

Alternative: dedicated GLUTE sub-panel like CHARACTER, with sliders for each field.

### Rendering hook
CPU side per frame, per model:
```kotlin
if (dancing) {
    // Beat kick modulation
    val shakeBoost = if (beatKickFresh) 1f + m.gluteShakeIntensity else 1f
    val decay = timeSinceLastKick / 250f  // ms
    val boost = 1f + m.gluteShakeIntensity * exp(-decay * 3f)
    m.gluteACurrentPush = m.gluteABasePush * boost
    m.gluteBCurrentPush = m.gluteBBasePush * boost
}
// On render: pass uniforms to glute shader
```

### Effort
High — touches vertex shader, rendering pipeline, marking flow, UI. **Estimate: 1.5-2 sessions.**

### Gotchas
- **Post-unweld meshes** (with normal-map tangent generation) have triangle-soup vertices. Each physical glute location may correspond to MANY unwelded vertices. The distance check still works per-vertex independently. Radius-based falloff is symmetric, so no tangent corrections needed.
- **Normal recomputation**: displacement along normal preserves smoothness for spherical bumps but not for concave areas. At small push amounts (<5cm on human-sized meshes) it looks fine. Larger pushes need normal rederivation for correct lighting — defer unless visibly wrong.
- **Self-collision**: glute A and B with overlapping radii will double-displace in the overlap region. Usually desirable (butt-crack narrows in reality). Clamp if pathological.
- **Masking**: some GLBs have hair/clothing vertices inside the glute radius that shouldn't push. For now, accept this; if problematic, add a "Y-threshold" per glute so only vertices below a certain Y are affected (skips hair).

### Stretch: "HEAVE" point — same system on the chest for bosom physics.

---

## Implementation sequence (ordered by ROI)

**Session N+1 (1 session):**
- ✅ Feature 1: Musical Amplitude (core signal + consumer updates) — high ROI for all users
- ✅ Feature 2: Intensity cycle expansion (trivial, bundled)

**Session N+2 (1 session):**
- ✅ Feature 3: Motion Expression Sliders (6 fields, sub-panel UI)

**Session N+3 (1.5-2 sessions):**
- ✅ Feature 4: Glute Deformation (shader + marking + beat-sync)

Total: **3-4 sessions of focused work.**

---

## Open design questions (decide in Session N+1 start)

1. **Musical level consumers** — should dance amp itself breathe with `musicalLevel`? Argument for: more dynamic. Argument against: conflicts with existing `bassGate`/`midGate` per-axis logic (which already band-gates). Probably apply `musicalLevel` as a global multiplier on TOP of the band gates.

2. **Sharpness at 100%** — does TRIANGLE look right, or should max sharpness go further into square-wave territory? Test with BOUNCE preset; if triangle reads as too soft still, bump to `tanh(4*sin)`.

3. **Per-axis vs global Character panel** — per-axis gives finer control (our current plan), but maybe one global "groove character" slider suffices for most users. Design tradeoff: ship both? start with per-axis, add global convenience later.

4. **Glute position capture** — model-local coordinates are easy for stored assets but complicated for user-rotated models. Store as model-local **before any stick-rotate**, so even if user spins her, glute stays on the right side.

5. **Glute shake pattern** — just bass-kick trigger, or phase-locked (1/8 continuous shake)? Real twerk has both styles. Let user pick via a mode: "POP" (single-impact per kick) vs "SHAKE" (continuous 1/8 oscillation).

---

## Risk list

- **Musical level too reactive** → breakdowns where the dance completely STOPS. Mitigation: `silenceFloor` default 0.15 (not 0). Verify on a slow R&B track with breakdowns.

- **Sharpness phase discontinuity** → pop artifacts when sharpness changes during live adjust. Mitigation: sharpness field only reads at frame start; already continuous output signal as long as triangle wave is zero-crossing-aligned with sine.

- **Complexity × ghost amplitude** → may exceed amp×rate ceiling. Mitigation: derive effective amp = max(primary, complexity * ghost_peak) and pass through existing clamp.

- **Glute shader uniform flood** — adding 12 uniforms to vertex shader is fine on GLES 3.0 (well under limit) but doubles the per-model uniform binding calls. Batch if >4 models animated simultaneously. Unlikely issue in practice.

- **Glute over-push on low-res meshes** — Tripo meshes at 2000-5000 polys have limited vertex density in glute area. Radius too small = only a few verts move, looks weird. Radius too large = hip/thigh displace too. Sweet spot likely 0.10-0.20 m for a 1.6m-tall model.

---

## Files to touch (summary)

**`AudioReactor.kt`** — musicalLevel signal, silenceFloor, FrameSnapshot additions.
**`FilamentModelActivity.kt`** — consumer updates (impact kick, ampGain, beatwash, maybe breath), Character sub-mode render, glute CPU modulation, uniform-bind.
**`scene/SceneManager.kt`** — 6 character fields + 8 glute fields on `PlacedModel`.
**`scene/InputHandler.kt`** — Character sub-mode input, glute mark flow.
**`scene/UiRenderer.kt`** — Character sub-panel render, glute sub-panel, Silence Floor slider.
**`GlesModelRenderer.kt`** — vertex shader uniforms for glute, new uniform location vars.
**(new)** `VERTEX_SHADER_WITH_GLUTE.glsl` or modify existing.

---

## Next-session bootstrap instructions

When the next session starts:

1. `git log --oneline -5` to confirm starting from the Tier 2 commit.
2. Read this plan (`TIER3_PLAN.md`).
3. Begin with **Feature 1** (Musical Amplitude). Design question #1 above needs user input — ask whether dance amps should also breathe or stay flat.
4. Implement AudioReactor changes, test build, then wire consumers one at a time.
5. Ship Feature 1 + Feature 2 together, get user feedback, then move to Feature 3.

The existing code conventions are well-established now: zero-alloc render loop, scratch buffers, right-multiply for local-frame rotations, snapshot-per-frame for audio. Preserve those.

---

## Epilogue

We've been building toward this: the first three tiers gave the engine physical correctness (pivots, foot anchor, anatomy marks, face mark, correct tangents, correct gamma). Tier 3 is where the *musicality* lives — the difference between "dancing to the beat" and "dancing with the music." All four features here are emotional amplifiers for work already done.

Glute shaker is last because it's the frontier tech: actual mesh deformation on a rigid hull. It's also the most fun feature by a wide margin.
