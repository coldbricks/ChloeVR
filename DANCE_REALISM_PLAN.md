# ChloeVR Dance Realism Plan

**Target:** "Realism AR" — AI-generated Tripo GLB dancers performing to music in the user's real room via passthrough. Samsung Galaxy XR (flavor `galaxyxr`) + Meta Quest 3 (flavor `quest`).
**Source:** 20-agent dance-motion audit, 2026-06-10. 12 recommendations (D1–D12), each adversarially verified against the actual codebase (file:line evidence) — verdicts: 12 confirmed.
**Methodology:** One recommendation at a time. Build after every change. Verify on hardware via adb logcat. Each broken APK costs 5+ minutes of sideloading.
**Input model:** Controllers only. No hand-tracking features anywhere in this plan.
**Companion doc:** `RENDERING_REALISM_PLAN.md` (R1–R16). D11 overlaps R3 — implement once (see Cross-References). Line numbers were verified against the working tree at audit time but the rendering work is landing in parallel — re-verify cites before editing.

Unqualified `:NNNN` cites refer to `FilamentModelActivity.kt`. All Kotlin paths relative to `prism/app/src/main/kotlin/com/ashairfoil/prism/`.

---

## Current State (audited 2026-06-10)

- **Motion is ~95% procedural:** beat-phase oscillators (stateless trig of AudioReactor beat phases, recomputed every frame) + Tier-4 hardcoded biomechanics writing Tripo-named bones — stance/waist (:2930-2956), arm sway/reach/elbows (:3036-3119), groove block head/neck/spine/clavicles/arms (:3378-3479), squat (:3551-3613). No temporal state anywhere: no overshoot, no settle, no follow-through.
- **JointDriveLayer is FULLY BUILT but dead:** the only real-mocap articulation in the app — Mixamo-extracted two-harmonic Fourier joint drives, 627 `JointDrive(` entries across 31 of 40 presets (~20 drives each, 1.5–30° amplitudes on Spine01/02, clavicles, upper arms, forearms, head) — ships in the APK as dead data. `layer.evaluate()` is COMMENTED OUT at FilamentModelActivity.kt:3638-3646: the drives are in Mixamo bone-local frames and read as "spinning bent-over" on Tripo rigs (forearm Z = elbow flex on Mixamo, gull-wing on Tripo). Needs per-bone axis-correction quats baked in the extractor — the repo's own roadmap at `tools/mixamo_extract/README.md:115-125` says the same. `m.jointLayer` is assigned (:1609, :1647) but never evaluated.
- **Stale gate comments:** :3057-3062 and :3100-3101 claim Tier-4 arm/elbow writes are "SKIPPED when a JointDriveLayer is present" — **no such gate exists in code** (zero conditional reads of `jointLayer` anywhere). Un-commenting evaluate() without writing the gate first reproduces the documented spazzing-arms double-drive (D9).
- **Foot planting is avoidance heuristics, not IK:** pelvis Z sway hard-zeroed (`val pelvisSwayZ = 0f`, :2930, under a "Pelvis Z sway KILLED" comment), all hip motion actually lives at the WAIST above the legs (:2953-2956 — "Waist carries EVERYTHING now"), the thigh counter-rotation path runs every frame fed 0f (:2959-2981), and **no Foot or Toe joint is ever driven** (rig ships L/R_Foot, NOTES.md:352). The removed footAnchor system (SceneManager.kt:241-249) "jelly-skated"; accepted residuals: ±17mm rudder arc (:3844-3845), ±1cm thrust drift (:2907).
- **Everything is periodic at ≤4-beat cycles** — any preset visibly loops within ~2 bars. The only variation is fBm amplitude 0.4–1.1 (:1506-1513), ±5% phase slop (:2494), and the CHOREO accent/mood/fill layer (:2750-2770), which is identical every bar / 16-bar cycle.
- **IMPROV cycles presets in list order** (:1571-1572 — same sequence every session) on a wall-clock interval (:2738-2744), swapping rhythm parameters **instantly mid-phase** with no blending (phase pops).
- **Rig mapping** is case-insensitive bone-name matching hardcoded to the Tripo convention with `mixamorig:` aliases and **silent per-subsystem fallback** (lookups :2802-2818, :3005-3018, :3337-3367; `setJointEuler*` no-ops on idx < 0 — a missing bone silently drops that subsystem with no log).
- **Beat-grid Float bug (worse than misalignment):** the three glute trigger grids divide absolute wall-clock ms (:2567, :2588, :2617). Kotlin `Long / Float` promotes to Float; at 2026 epoch magnitudes (~1.78e12 ms) Float ULP is 131,072 ms, so grid indices only change every ~2.2 MINUTES, jumping thousands of sub-beats at once. The shaker burst, syncopation hash, and locked-clock tick currently fire in rare bursts, not per beat (D1).
- **Idle = statue:** breath is a whole-model 3mm Y bob at 0.25Hz *inside the dancing branch* (:3701-3704) — feet sink/hover against the real passthrough floor twice every 8s while dancing, and a non-dancing model gets **zero per-frame writes of any kind** (no final else after the dancing/A-B chain, :2734-2736 + :3928).
- **Audio feed:** the dance hears music through a fixed-0.30 bass-RMS edge (AudioReactor.kt:159-174) + a loudness envelope, on `android.media.audiofx.Visualizer`. The genuinely good spectral-flux `haptics/BeatDetector.kt` (adaptive threshold, onset strength, tempo confidence, predictedNextOnsetMs) feeds ONLY the Lovense path; `musicalLevel` is hard-floored at 0.5 whenever BPM is locked (AudioReactor.kt:618-620). See the audio-feed dependency note in Cross-References — the Visualizer itself is a 20Hz 8-bit tap behind a never-requested runtime permission.

---

# Recommendations (priority order)

Quick wins D1–D3 (hours each), D4–D8 (days each), D9/D10 (transformative, week-plus), D11/D12 (deformation cleanups).

---

## D1: Snap all beat grids to the BPM epoch and add a real ~80ms anticipation lead so weight-arrival lands ON the beat

`category=musicality · impact=high · effort=hours · verdict=confirmed`
**Files:** `AudioReactor.kt`, `FilamentModelActivity.kt`

### Spec

Two timing fixes that share the same clock plumbing.

**(A) GRID SNAP (one-line-class):** all three glute trigger grids compute sub-beat index as `(snap.nowMs / subBeatMs)` where nowMs is absolute `System.currentTimeMillis()`, while every body oscillator computes phase as `(now - bpmEpochMs)` — so the marquee glute pops, the shaker's last-beat-of-bar burst window (positions 24-31/32), and the bar-locked syncopation hash sit at a constant random offset of up to a full beat from both the music and the body. Fix:

- Add `var epochMs: Long = 0L` to `AudioReactor.FrameSnapshot`, populate from `bpmEpochMs` in `snapshot()`. (FrameSnapshot at AudioReactor.kt:883-900 has no epochMs field today; `bpmEpochMs` at :116 is @Volatile PRIVATE with no accessor — FilamentModelActivity cannot reference the epoch at all.)
- Change the three grid computations at FilamentModelActivity.kt:2567, :2588, :2617 to `((snap.nowMs - snap.epochMs) / subBeatMs).toLong()`, guarding `epochMs != 0L`.
- Unify clock sources: `phaseAt` uses System.currentTimeMillis (AudioReactor.kt:137) but kick timing uses SystemClock.uptimeMillis (:163) — migrate both to uptimeMillis so an NTP step can't jolt every oscillator.

**(B) ANTICIPATION:** trained dancers lead the beat by ~45–90ms; the perceived kinematic beat is the deceleration/lowest-CoM arrival, which must land ON the beat. Currently the only lead is `leadPh = -(45ms × physicsAmount)` applied to YAW ONLY (FilamentModelActivity.kt:3130-3131) — ~16ms at default physicsAmount 0.35, perceptually nothing — and bob/squat/pitch (the weight axes) get zero lead, while impact kicks fire on detectBassKick detections (~50ms capture quantization, 180ms min interval — always late). Fix:

- Per-axis `leadMs = 60 + 60*physicsAmount` (~80ms typical) applied as a phase lead to yaw, pitch, AND bob phase lookups (bob is the weight-drop axis, its lead matters most). **SIGN TRAP: see verifier note 2 — the source rec's literal `leadPh = -leadMs/cycleMs` is sign-inverted; use a POSITIVE phase offset.**
- Audit the bob bump so its minimum (lowest CoM; fast-down kSkew already gives the right asymmetry) lands exactly at beatPhase 0, with descent beginning ~0.75 of the prior beat.
- Reschedule impact kicks off the epoch beat grid instead of beatCounter detections — trigger the 200ms raised-sine at (next epoch beat boundary − 100ms) so its peak lands ON the beat (the grid is free-running, pure arithmetic, no prediction model).

Together these are the documented difference between "dancing with" and "reacting to" music, at hours of cost.

### Verifier corrections & device traps

1. **STRENGTHENING — the grid bug is far worse than "constant offset up to a beat":** Kotlin `Long / Float` promotes the Long to Float, and at 2026 wall-clock magnitudes (~1.78e12 ms) Float ULP is 131,072 ms. All three grid indices therefore only change when nowMs crosses a Float-representable boundary — roughly every 2.2 MINUTES, jumping thousands of sub-beats at once. The shaker burst, syncopation hash, and locked-clock tick currently fire in rare bursts, not per beat. The proposed `(snap.nowMs - snap.epochMs)` fix also cures this precision failure since elapsed-since-epoch stays small; this makes Part A more urgent, not less.
2. **SIGN TRAP:** by the code's own phase-shear semantics (bob shear 0 = "hips lead", pitch −0.04 and yaw −0.09 = segments that LAG), a negative phase offset shifts waveform arrival LATER in time. The existing "Anticipation" comment at :3127 is sign-inverted (it lags yaw by 45ms×physics), and the rec's literal formula `leadPh = -leadMs/cycleMs` copies that inversion — implemented verbatim it would DELAY weight arrival by ~80ms instead of leading it. The diagnosis ("no real anticipation exists") survives and is strengthened, but use a POSITIVE phase offset (or equivalently subtract lead from the time argument before phase computation) and **verify direction on device**.
3. `bpmEpochMs` is only approximately anchored to a real kick onset (set in update() on the detection frame, AudioReactor.kt:634; halveBpm/doubleBpm reset it to arbitrary "now" at :243/:249), so "lands ON the beat" is bounded by epoch anchoring accuracy — consider re-anchoring the epoch to lastBassRiseMs at lock.
4. The kick envelope `sin(pi*k)*(1-k)` peaks at k≈0.36 (~72ms into the 200ms window), not 100ms — tune the proposed −100ms pre-trigger to the actual envelope peak.
5. Clock migration to uptimeMillis must move snapshot nowMs AND all its consumers together (tSecFrame fBm at :2493, improv interval :2741, kick elapsed :3757 are all relative uses, so this is safe).
6. Per CLAUDE.md, validate on real Galaxy XR hardware — grid-firing cadence is exactly the kind of thing the user tests on device.

---

## D2: Fix the glute shaker: pose-space anchors via transformPointByJoint, skin-weight masking, and lighting-visible push

`category=deformation · impact=high · effort=hours · verdict=confirmed`
**Files:** `FilamentModelActivity.kt`, `GlesModelRenderer.kt`, `scene/SkeletonRuntime.kt`

### Spec

The marquee deformation feature currently fails exactly when the body moves. Three fixes, all in existing infrastructure (~40 lines total).

1. **POSED ANCHORS** — each frame after `skel.update()`, run `SkeletonRuntime.transformPointByJoint(pelvisIdx, bindAnchor, posedAnchor)` (exists at SkeletonRuntime.kt:208-228, palette-based, zero call sites today) and upload posedAnchor as uGluteA/BPos. The palette is `globalPose*invBind`, so the input MUST be the bind-space anchor (the auto-anchor at hipFrac 0.52 already is). The previous attempt's "no shake even at rest" failure is almost certainly a coordinate-space mismatch between the loader's model-normalization transform and the glTF node space the palette lives in — before re-enabling, add a 5-minute debug gizmo (2cm unlit sphere rendered at the posed anchor) so divergence is visible instead of guessed. Use Pelvis as parent joint for both cheeks first (stable under squat since Root drop flows through it); optionally switch per-cheek to L/R_Thigh for better tracking during leg flexion.
2. **MASKING** — in the vertex shader compute `gluteMask = sum of aWeights[i] where aJoints[i] is in {pelvisIdx, lThighIdx, rThighIdx}` (pass the 3 indices as a uniform ivec3) and multiply the push by it; kills the documented hair/clothing inflation inside the sphere (TIER3_PLAN.md:275 accepted debt).
3. **MAKE IT LIGHT** — after displacement, bend the normal: `nrm = normalize(mix(nrm, normalize(posedPos - anchor), 0.35 * (pushMeters / 0.08)))`; and copy the same 6-line push block into the shadow VS so the bulge silhouettes. (Source rec cited GlesModelRenderer.kt:2926-2932; the actual SHADOW_VERTEX_SHADER is ~30 lines later at :2956-2976.) Without (3) the bulge reads as texture warp, not flesh.

Complements D10: shader spheres do beat-synced pops, spring bones do physical inertia.

### Verifier corrections & device traps

1. **The previous attempt at fix (1) was tried ON DEVICE and reverted** — the code comment at FilamentModelActivity.kt:2707-2711 says even with the palette math corrected the user saw NO shake at all, even at rest. This is a re-attempt of an empirically failed approach; the debug-gizmo-first step is **mandatory**, not optional (also mandated by CLAUDE.md's Iron Rule against guessing).
2. **The rec's root-cause hypothesis ("loader's model-normalization transform" mismatch) is shaky:** loadGlb (GlesModelRenderer.kt:818-821, :900) uploads POSITION data raw into the VBO with no normalization transform; the only space-bending at load is the non-joint-ancestor fold into bindLocal (:1227-1286), which affects shader palette and transformPointByJoint identically since both consume the same palette array. The actual cause of the prior at-rest failure is still unexplained — possibly the radius/falloff interaction when both bind anchor and posed anchor coincide at rest, or the gizmo will reveal something else.
3. Minor: the rec's "documented hands/skirt inflation" is documented as hair/clothing inflation (TIER3_PLAN.md:275), and the shadow-VS fix needs uniform declarations + location fetch + per-model uploads in the shadow pass, not just the 6-line push block (~15 lines, still small). The ~40-lines-total estimate is roughly right.

---

## D3: Move breathing into the skeleton and add an always-on idle layer (statue when not dancing; feet bob off the real floor when dancing)

`category=variation · impact=high · effort=hours · verdict=confirmed`
**Files:** `FilamentModelActivity.kt`, `scene/SkeletonRuntime.kt`

### Spec

Two artifacts, one fix. (a) Breath today is a whole-model 3mm Y translation @0.25Hz (FilamentModelActivity.kt:3701-3728) — her FEET sink/hover 3mm against the real passthrough floor twice every 8 seconds, a grounding tell stereo vision catches at 1–3m. (b) When not dancing she is a perfect statue — the classic dead-mannequin AR moment (mechanism corrected in verifier note 1). Implement a small idle layer that runs UNCONDITIONALLY (dancing or not), writing joints via the existing `composeJointEulerXYZ` so it stacks under dance motion:

- **BREATH:** Spine01 X += 0.6° × sin(2π·0.22Hz·t), Spine02 X += 0.5° in phase, L/R_Clavicle lift 0.4° at +90° phase lag (shoulders rise after chest on inhale), Head X −0.25° counterphase; delete (or cap at 1mm) the whole-model breath bob so feet stay planted; keep the 0.3° breath pitch but move it from model space to Waist X.
- **WEIGHT SWAY:** Waist Z += 0.8° × (sin(2π·0.07t) + 0.6·sin(2π·0.13t)) — two incommensurate sines, effectively never-repeating, mirroring the existing fBm trick — plus ±5mm Root X CoM wander at 0.05Hz.
- **HEAD LIFE:** Head Y micro-yaw ±1.5° re-targeted every 4–8s with a 0.4s eased move.
- All amplitudes ×(1 + 0.5·musicalLevel) when audio is live so idle "listens."

Cost: ~8 joint writes/frame, zero alloc. Cheapest fix for the research finding that perfect stillness and whole-body rigid oscillation are top robotic tells, and it removes a real grounding artifact.

### Verifier corrections & device traps

1. **Fix the citation:** the dead-mannequin behavior comes from the absence of any else after the dancing/A-B chain at FilamentModelActivity.kt:2734-2736 + :3928, not from a "hard reset" at :3922-3926 (that is the else of `if (m.physicsAmount > 0.001f)` INSIDE the dancing branch). Also breath currently runs ONLY while dancing, so a statue model today gets no breath at all — strengthening the recommendation. An idle model gets zero per-frame writes (no transform, no joints, no breath), and the whole block is further gated on `ar != null` (:2485).
2. **Ordering is load-bearing:** the dance groove writes to Neck/Head/Clavicles use `setJointEulerXYZ` (REPLACE semantics, bind×rot — SkeletonRuntime.kt:136, vs compose at :163), so the idle layer's Head/Clavicle composes must run AFTER those writes (and after the spine yaw resetJointToBind at :3319, :3400-3401) or they will be wiped. Safest insertion: at the end of the per-model loop, unconditionally, before GlesModelRenderer uploads the palette.
3. **The idle layer cannot depend on snap.nowMs/audioReactor** — the whole current block is gated on `ar != null` (:2485); it needs its own monotonic time base for the no-audio case.
4. **Cautionary precedent:** the Mixamo JointDriveLayer was disabled because per-bone local-axis conventions differ on Tripo rigs (forearm Z = gull-wing, :3638-3644); however, the proposed joints/axes (Spine01 X, Spine02 X, Clavicle lift, Head X/Y, Waist X/Z) are exactly the axes the Tier-4 groove code already drives successfully on these rigs (:3403-3454), so the axis knowledge is proven in-tree.
5. Head micro-yaw retarget every 4–8s needs a few new per-model state fields (floats/longs on `PlacedModel`), consistent with existing zero-alloc per-model state like gazeCurrentBiasRad.
6. Pelvis-anchored presets pivot the whole-model rotation via pivot corrections; **capping rather than deleting the 3mm bob (rec offers both) is the lower-risk first step**, since proxMicro already amplifies it to ~3.9mm close-up where stereo acuity is highest.
7. Unrelated but observed: GlesModelRenderer.kt:1802 allocates a direct ByteBuffer per skinned model per frame — a zero-alloc-invariant violation worth flagging separately (fixed by D11 fix 3).

---

## D4: Make her hear THIS song: pipe the spectral-flux BeatDetector into the dance (onset accents + soft PLL), vote the downbeat onto the real '1', and replace the synthetic 16-bar mood arc with build/drop detection

`category=musicality · impact=high · effort=days · verdict=confirmed`
**Files:** `AudioReactor.kt`, `haptics/BeatDetector.kt`, `FilamentModelActivity.kt`

### Spec

One plumbing investment, three compounding payoffs — all share the same onset infrastructure and should land together. A genuinely good onset detector (adaptive mean+k·std threshold, onset strength, tempo confidence, predictedNextOnsetMs) already exists in `haptics/BeatDetector.kt` but ONLY the Lovense path consumes it; predictedNextOnsetMs has no consumer at all. The dance hears music through a fixed-0.30-threshold bass RMS edge (AudioReactor.kt:159-174) and a loudness envelope.

- **PLUMBING:** instantiate a BeatDetector inside AudioReactor's FFT callback (`computeFluxFrom` already takes Visualizer mags), independent of useVibesEngine; add `onsetStrength` (~150ms decay), `lastOnsetMs`, `tempoConfidence` to FrameSnapshot.
- **(1) ACCENTS:** scale impact-kick amplitude by onset strength — `kickDeg = 3° * clamp(onsetStrength, 0.5, 2.0)` — hard hits get visibly bigger responses; drive the groove layer's fastAccent from onsets instead of pure phase.
- **(2) SOFT PLL:** per high-confidence onset (tempoConfidence > 0.6), `phaseErrMs = wrap((onsetMs - bpmEpochMs) mod beatPeriod)` to [−period/2, +period/2]; if |phaseErrMs| < 0.15·period, nudge `bpmEpochMs += clamp(0.08*phaseErrMs, -8ms, +8ms)`. The 50ms interval bucketing means a 0.5 BPM error at 120 BPM drifts a full beat in ~2 minutes with no recovery today; max 8ms/onset correction is invisible and preserves free-run smoothness.
- **(3) DOWNBEAT VOTE:** 4 accumulators acc[b], `b = floor(((onsetMs - bpmEpochMs)/beatPeriod) mod 4)`; per bass-band onset add `onsetStrength * bandBass`; decay ×0.95 per bar. After ≥ 8 bars, if argmax(acc) != 0 AND acc[max] > 1.25·acc[0] (hysteresis, never flip-flops), rotate `bpmEpochMs -= argmax * beatPeriod`; keep voting continuously. This aligns the accent pulse (1+0.22·cos(phase1bar)), 4-bar fill (last 25%, rates forced to 8), 16-bar mood arc, IMPROV bar reshuffle, and shaker last-beat burst onto the actual '1' — the already-built choreography layer starts reading as intentional musicianship. No ML needed (kicks sit on 1/3, strongest on 1).
- **(4) DYNAMICS:** musicalLevel is hard-floored at 0.5 whenever BPM is locked (AudioReactor.kt:613-620), halving dynamic breathing — apply the floor only if rmsInst < 0.03 sustained > 2s (the metronome-without-music case), otherwise let breakdowns hush her to silenceFloor.
- **(5) BUILD/DROP:** emaShort (tau 3s) / emaLong (tau 15s) of rmsInst + flux; buildScore > 1.25 for > 2s enters BUILD (ramp moodGain to 1.35, amp ×0.85, add groove rate — coiled); bandBass emaShort < 0.15 for > 1.5s then bandBass jump > 0.5 with onsetStrength > 1.3 fires DROP (2 bars amp ×1.4, doubled impact kicks, fill-style rate burst on bar 1). Keep the 16-bar moodGain sinusoid only as fallback after 32 bars with no detected events.

### Verifier corrections & device traps

1. `computeFluxFrom` is stateful (prevMagnitude buffer) — AudioReactor needs its OWN SpectralAnalyzer instance (or inline flux loop); do not share ChloeVibesEngine's analyzer, since double-feeding computeFluxFrom would corrupt the vibes path's flux.
2. When useVibesEngine is ON, processFft rescales vibesMagBuf IN-PLACE (AudioReactor.kt:529-536) — feed the new detector at a consistent scaling point (before the /128·sensitivity rewrite) or flux statistics will jump when the vibes toggle changes.
3. `BeatDetector.process()` returns a Pair (heap alloc per FFT callback) and updateTempoPrediction allocates a small FloatArray per onset; this runs on the Visualizer thread, not the render loop, so the zero-alloc invariant holds — and the @Volatile lastIsOnset/lastOnsetStrength fields exist precisely so consumers can skip the Pair; **use them**.
4. BeatDetector's 43-slot history assumes ~43 Hz; Visualizer capture is typically ~20 Hz (window ~2s) — already true in the shipped vibes path so proven workable, but confidence constants may need tuning. (A Media3 PCM tap — see Cross-References — removes this limitation.)
5. The shaker burst and glute subdivision derive bar position from raw snap.nowMs (FilamentModelActivity.kt:2567-2571), NOT bpmEpochMs — the downbeat-vote rotation must also rebase these or they stay misaligned after the '1' is found. (D1's epoch rebase makes them epoch-relative, so **land D1 first** and the rotation covers them for free.)
6. "Fixed 0.30" is technically a @Volatile var (`bassKickThreshold`) but has no writers anywhere — fixed in practice.
7. All line ranges cited (159-174, 613-620, 897-923) land on the described code.

---

## D5: Kill the 2-bar loop tell: shuffle-bag IMPROV with bar-boundary blended transitions plus per-bar amplitude and structural variation

`category=variation · impact=high · effort=days · verdict=confirmed`
**Files:** `FilamentModelActivity.kt`

### Spec

Every oscillator is strictly periodic at ≤ 1 bar; variation is only fBm amplitude (0.4–1.1) and ±5% phase slop, so any preset visibly loops within ~2 bars — the classic AI-loop tell (humans carry 1/f variability and never repeat a cycle identically). IMPROV makes it worse: shuffleDance cycles dancePresets IN LIST ORDER (FilamentModelActivity.kt:1571-1572 — same sequence every session) and swaps rhythm parameters INSTANTLY mid-phase (phase pops). Four cheap layers:

1. **SHUFFLE BAG** — replace `(currentIdx+1) % size` with a seeded random permutation refilled when exhausted (no repeats, different order per session; ~10 lines).
2. **TRANSITION TIMING + MASKING** — defer the IMPROV swap until phase1bar wraps (track previous phase1bar, fire on wrap), and mask the parameter pop with a 250ms eased global amplitude dip to 0.6 and back (a human "resets" between moves; reads as intention, not glitch).
3. **PER-BAR AMPLITUDE JITTER** — reuse the existing golden-ratio bar hash (`barIdx * 2654435761`) to scale each axis 0.85–1.15 per bar, re-rolled on the bar boundary, so no two bars are identical even within one preset.
4. **STRUCTURAL VARIANTS** — on hash-chosen bars: every 2nd bar flip the arm-reach phase offset; every 8th bar play half-time (yaw/pitch rates halved for that bar — sitting in the pocket); add fixed per-side asymmetry scalars (L arm 0.92 / R arm 1.08, swap every 8 bars) so left/right never mirror.

Items 3–4 preserve inter-joint PHASE relationships (only amplitudes/structure vary), which the point-light literature identifies as the safe way to add variation — independent per-joint jitter is what looks wrong.

### Verifier corrections & device traps

Core evidence, all in `FilamentModelActivity.kt`:

1. **LIST-ORDER CYCLE** — verbatim at :1571-1572: `val currentIdx = dancePresets.indexOfFirst { it.name == m.currentPresetName }` / `val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % dancePresets.size`. Comment at :1567-1570 documents this as a deliberate change FROM uniform random ("Deterministic cycle... so the user can walk the library"). No Random/shuffle/bag anywhere in the prism Kotlin tree.
2. **INSTANT MID-PHASE SWAP, NO DEFERRAL** — trigger at :2738-2744 is a wall-clock interval test (`snap.nowMs - m.lastImprovMs >= barMs * improvBars`), anchored to whatever frame IMPROV was toggled (scene/InputHandler.kt:1513 sets lastImprovMs=0 → fires next frame, mid-bar) and re-anchored to frame timestamps, so it drifts off the downbeat. phase1bar wrap is never tracked. shuffleDance (:1584-1610) direct-assigns rates/phases/ease/pivots/jointLayer/handsOnKnees; phaseAtSnap (AudioReactor.kt:933-938) switches between independent cached phases per rate, so a rate change is an instantaneous phase discontinuity consumed the same frame (:2871, :3487, :3517).
3. **NO BLEND/MASKING** — greps for blend/crossfade/transition/ramp/spring/damp/smooth hit only light-estimation EMA, fig8 waveshaping, loudness envelopes. physicsAmount is anticipation phase-lead + groove skew, not a pose low-pass. Nothing masks the swap.
4. **NO PER-BAR JITTER** — fbmMod (:1506-1513) = 0.75+0.35s ≈ 0.4..1.1; phaseSlop (:2494) = ±0.05 at 0.1Hz. The golden-ratio hash EXISTS at :2598 but only gates glute-kick sync probability — reusable for the proposed jitter exactly as claimed. No bar-indexed amplitude jitter, half-time, or side-swap exists.

FEASIBLE under CLAUDE.md invariant 6 (zero-alloc render loop): bag = preallocated IntArray refilled in place; deferral = one per-model float (IMPROV block already gated on `snap.autoBpm && snap.bpm > 0f` at :2738, so phase1bar is always valid there); dip + jitter = pure scalar math folded into existing ampGain product (:2767); barIdx derivation pattern already exists (:2594-2596).

Qualifications (none refute the core claim):

- (a) "Variation is ONLY fBm + slop" is slightly overstated: when IMPROV+BPM-lock is on, a CHOREO layer (:2750-2770) adds accentGain (+22% within-bar, but IDENTICAL every bar), moodGain (0.8–1.25 over a 16-BAR arc — weakens the "visibly loops within ~2 bars" framing at the amplitude level), fillActive (last quarter of every 4th bar: amps 40%, rates forced to 8 — an existing bar-position structural variant, double-time rather than the proposed half-time), and musicalLevel (loudness-following, non-periodic with real music), plus bass/mid band gates, proxMacro, gazeGain. Also fBm itself is non-periodic (incommensurable sines, ~17–57s periods), so bars are never mathematically identical — the loop tell is in the repeating trajectory SHAPE and phase relationships, which the recommendation correctly targets via structure rather than amplitude.
- (b) Item 4's "left/right never mirror" is already substantially achieved for ARMS: 180° offset abduction sway (:3044-3045), 180° offset reach with independent per-arm fBm gains (reachGainL/R, phaseOff 11.3 vs 13.7, at :3053-3056), alternating elbows (:3102-3103). The per-side scalars would add little there; the bar-flipped reach offset and half-time bars remain genuinely new.
- (c) **Design caveat for layer 1:** shuffleDance is double-purposed — the manual DANCE button tap (scene/InputHandler.kt:1495) calls the same function, and the deterministic walk is documented intent for that path ("user can dial in a specific style"). The shuffle bag should apply on the IMPROV auto-fire path only (or be gated), preserving the manual deterministic walk; setDancePreset (:1619) already covers explicit picking.

---

## D6: Lower-body weight: joint-space pelvis sway with exact thigh counter-rotation, driven Foot joints, analytic squat root-drop, and a DC-drift heel pin

`category=grounding-ik · impact=high · effort=days · verdict=confirmed`
**Files:** `FilamentModelActivity.kt`, `scene/SkeletonRuntime.kt`, `GlesModelRenderer.kt`

### Spec

Real dancing is built on weight transfer and planted feet; ChloeVR currently has neither — and footskate is the #1 perceptual error (corrected motion is ALWAYS preferred, no tolerance band; the real passthrough floor gives an absolute reference).

**WEIGHT SHIFT**, returned as JOINT-space effects (the old world-space versions caused foot skating and were disabled):

1. Pelvis joint Z = ±5° at rate 2 (one full cycle per 2 beats = weight onto L foot on beat 1, R foot on beat 3), popWave-shaped to hang at extremes — pelvis Z is currently hard-zeroed and all "hip" motion lives at the WAIST above the legs, so the glutes barely rotate, directly undermining the twerk-class presets.
2. Re-activate the existing thigh counter path (runs every frame at FilamentModelActivity.kt:2959-2981, currently fed 0f) with `thighCounterDegZ = -pelvisSwayZ` exactly, so shins/feet stay vertical and planted while the pelvis tilts over the support leg — this kills the foot-pendulum that got sway zeroed originally.
3. Root joint X translation ±1.5cm on the same phase (CoM over the support foot).
4. Spine02 Z = −0.6 × pelvisZ so shoulders counter the hips (contrapposto in motion).
5. Re-enable the yaw→pitch contrapposto coupling (dead code preserved at :3714-3722) at ~0.15 gain.

**GROUNDING**, no full IK solver needed:

1. **FLAT FEET** — the rig ships L/R_Foot joints never driven; during squat the shank tilts (thigh +45° × s, calf −85° × s) so the sole rotates ~40° off the floor — add `footX = -(thighX + calfX)` via setJointEulerXYZ after the squat block (one line per leg, works on every rig).
2. **ANALYTIC ROOT DROP** — replace the fixed `0.12m * squatDepth` with exact geometry: at load compute Lthigh and Lcalf from bind-pose joint translations (already in SkeletonRuntime bindLocal); per frame `hipDrop = Lthigh*(1-cos thighX) + Lcalf*(1-cos(thighX + calfX))`, set Root Y = −hipDrop — heels stay exactly on the floor for any Tripo limb proportions (current constants are tuned to ONE rig and will float/clip on others).
3. **HEEL PIN done right** — the removed footAnchor system (SceneManager.kt:241-249) jelly-skated because it pulled the whole model back at strength 0.85 against world position; instead, at dance-arm record each Foot joint's posed world XZ via transformPointByJoint, per frame compute current posed foot XZ the same way, low-pass the error (one-pole, fc ~0.5Hz so beat-rate motion passes untouched), and subtract only the DC drift from Root X/Z, clamped ±3cm — kills the accepted ±17mm rudder arc and ±1cm thrust drift without fighting choreography.

Acceptance test on hardware: skinned heel markers drift < 8mm at full sway. Cost: 2 transformPointByJoint calls + a handful of trig per frame. Bonus: actual pelvis/glute rotation also re-aims the glute shader anchors' home region (pairs with D2).

### Verifier corrections & device traps

1. **DUAL THIGH WRITERS:** the stance block (:2976-2980) and the squat block (:3600-3604, using pelvisSwayZBob = 0f at :3580) both setJointEulerXYZ on the thighs; when bob is active the squat block overwrites the stance write (set, not compose — last write wins, see ownership comment at :3618-3621). Reactivated pelvis sway must feed its counter into BOTH sites or sway-during-bob loses the counter. Also the squat block writes thigh X (squat × 45°) — the counter Z must be merged into that same call, and the proposed `footX = -(thighX + calfX)` flat-foot correction must account for the thigh Z term too once sway is live.
2. **RESIDUAL TRANSLATION DRIFT:** the code's own analysis (:2823-2834, :2965-2969) says thigh counter-rotation cancels ORIENTATION only; thigh-origin translation arc is unavoidable in a rigid hierarchy, ~7mm/foot at 5° pelvis Z. That beat-rate 7mm passes through the proposed 0.5Hz DC-drift filter untouched, so the <8mm acceptance test passes only by the code's own estimate's margin — tight but consistent. The rec's phrase "feet stay vertical and planted" slightly overstates; "vertical, with sub-8mm drift" is accurate.
3. **transformPointByJoint returns posed MODEL-space (palette × bind point), not world:** to kill the ±17mm rudder arc (which comes from the WORLD yaw quaternion at :3846-3855, invisible to joint-space math), the heel-pin must compose the model world transform (m.rot/m.pos) onto the joint-space foot point before computing drift. Feasible, but the rec's "posed world XZ via transformPointByJoint" needs that extra step spelled out.
4. **Reset coordination:** when bob turns off, root is resetJointToBind (:3627) — a root-X sway translation writer needs coordination with that reset and with the squat block's own resetJointToBind(root) at :3611.
5. Historical nit: the killed pelvis sway was already joint-space (the world-space systems that were removed are the footAnchor and the Tier1-E world coupling), so the rec's framing "the old world-space versions caused foot skating" conflates two removals — the joint-space pelvis sway was ALSO killed, for translation-arc reasons the thigh counter only partially addresses (see caveat 2). The rec's line cites (2959-2981, 3714-3722, SceneManager 241-249) are all exact.

---

## D7: Per-joint rotational spring-lag layer (follow-through / overlapping action) on head, neck, spine, and forearms

`category=secondary-motion · impact=high · effort=days · verdict=confirmed`
**Files:** `FilamentModelActivity.kt`, `scene/SkeletonRuntime.kt`

### Spec

Every Tier-4 oscillator writes its target angle instantaneously, so all body parts arrive together. The codebase already contains the exact 2nd-order spring math needed (the scale-jiggle integrator at FilamentModelActivity.kt:3910-3915); generalize it from 3 scale axes to per-joint angle channels.

- **Implementation:** a small filter pass between the dance writes and `skel.update()` — for each filtered joint, dance code writes into a target buffer (`targetEuler[joint][axis]`) instead of localPose; the filter integrates `theta'' = omega^2*(target - theta) - 2*zeta*omega*theta'` per axis and calls the existing setJointEulerXYZ with the filtered value.
- **Per-joint tuning:** Head 4.0Hz ζ=0.40, Neck 4.5Hz ζ=0.45, Spine02 3.5Hz ζ=0.35, L/R_Forearm 6.0Hz ζ=0.50, L/R_Upperarm 7.0Hz ζ=0.6 (near pass-through so arms stay on-beat; hands/forearms whip last). Use the dt already computed for the jiggle spring, clamp 0.001–0.05s.
- **Three compounding wins:** (a) distal joints lag proximal by physics, producing the chain-whip overlapping action research identifies as the fluid-vs-rigid separator (~30–120ms differentiated lag falls out of the frequencies above); (b) underdamped overshoot gives free anticipation/settle on every beat accent; (c) it low-passes the worst close-stereo jitter source for free — IMPROV's instant mid-bar parameter swaps (see verifier note 2 for what it does NOT catch).
- Cost: ~8 joints × 3 axes scalar springs, zero-alloc with pre-sized FloatArrays per the render-loop rule.
- **Sequencing:** this layer is also a safety net for re-enabling JointDriveLayer (D9) — it softens transient artifacts while that work lands (see verifier note 3 for what it does NOT fix).

### Verifier corrections & device traps

The dt pattern `m.lastDanceNanos` + clamp 0.001–0.05 already exists at :3899-3902, but it currently lives inside the physicsAmount>0 branch and is consumed by the jiggle — the filter needs its own timestamp or a hoist.

1. **"All body parts arrive together — the mannequin tell" is overstated.** Static phase differentiation already exists: arm sway lags the hip cycle by 15% of cycle (:3044-3045), spine counter-twist gives "shoulders lag the hips by ~45%" (:3218+), the groove block staggers head/neck/spine/clavicle/arms across beatSin vs beatCos (quadrature) vs halfSin vs fastAccent (:3379-3479), plus a −45ms anticipation lead (:3127-3131) and per-arm fBm drift (:3053-3054). What is genuinely missing is DYNAMIC transient lag — no overshoot/settle, and phase offsets cannot low-pass parameter steps — so the rec's value claim stands, but pitch it as "transient follow-through," not "first phase differentiation."
2. **Win (c) is half-wrong:** the 200ms impact kick (:3751-3809) and the gaze bias are WHOLE-MODEL quaternion ops that bypass the skeleton entirely; a filter between joint writes and skel.update() will never see them. Only the IMPROV phase/rate jumps (and mid-track shuffles) get low-passed for the filtered joints.
3. **Sequencing rationale is partially wrong:** JointDriveLayer was disabled for the Mixamo-vs-Tripo bone-axis convention mismatch ("spinning bent-over", :3638-3646), which a spring layer does NOT fix; the documented "spazzing arms" double-drive risk is governed by the skip gate described at :3057-3062 — [D9 verification: that gate is comment-only; it does NOT exist in code and must be written as part of D9]. The spring layer is a nicety for D9, not a prerequisite fix for either failure mode.
4. **Implementation is more invasive than "a small filter pass":** the named joints have MULTIPLE writers with mixed set/compose semantics — Spine02 = setJointEulerZ counter-twist (:3302) then groove compose (:3411); forearms = 25–75° elbow set (:3118-3119) then groove compose (:3475-3478); upperarms = 60° drop set (:3076-3078) then groove compose (:3459-3472). A targetEuler[joint][axis] buffer requires converting compose right-multiplies into Euler-angle sums (exact for same-axis, approximate cross-axis at the large stance baseline angles). **Head/neck are the cleanest single-writer targets — start there.** Also needs snap/bypass handling for handsOnKnees instant pose overrides (55°/35° shoulders :3071-3073, 95° elbows :3115-3116) and the resetJointToBind branches (:3319-3320, :3400-3401, :3625-3627), or a 4–6Hz underdamped spring will ring on intentional pose snaps.

---

## D8: Layered gaze: head/neck aim with VOR stabilization, saccade-like glances, and Galaxy XR eye-tracked mutual-gaze response

`category=gaze-face · impact=high · effort=days · verdict=confirmed`
**Files:** `FilamentModelActivity.kt`, `scene/XrSensorPoller.kt`, `scene/SkeletonRuntime.kt`

### Spec

Current "gaze" rotates the WHOLE MODEL toward the viewer (yaw bias, tau=1.2s, FilamentModelActivity.kt:3158-3173) — she pivots like a turret; her face never independently finds you, and while the groove layer bobs her head ±1.4° the gaze direction bobs with it. At 1–3m, face-level eye contact is the strongest presence cue available. The rigs have no eye bones or morph targets (39 joints; loader parses no morphs), so implement the head-level 90%:

1. **GAZE DISTRIBUTION** — compute gaze error epsilon = yaw/pitch from current head-forward to dir-to-camera; write Head = clamp(0.60·epsilon, ±25° yaw / ±15° pitch) and Neck = clamp(0.25·epsilon, ±10°) via composeJointEulerXYZ (composing OVER the groove layer), fast slew tau=0.25s; keep the existing slow whole-body slew (tau=1.2s) to absorb the remainder — body lazily follows, face locks on (research: eyes/head lead, body follows).
2. **VOR COUNTER-ROTATION** — each frame, sum the dance-driven yaw/pitch the parent chain (model yaw + spine twist + neck groove) imposes at the head and counter-rotate the head by 70% of it, clamped ±20°: her face holds steady on you while the torso grooves underneath — exactly how real dancers spot. The values are all already computed in the dance loop (yawMagDeg, spine counter-twist, groove head terms) — no new math, just bookkeeping.
3. **GLANCE FSM** — Poisson glances (mean interval 4s, refractory 1.5s): 150ms eased head saccade to an offset target (±10–15° yaw, −10° pitch, biased downward/sideways), hold 0.3–0.8s, return in 200ms; suppress during fills/drops (snapshot already exposes phase4bar).
4. **MUTUAL GAZE** — XrSensorPoller already publishes the user's combined eye-gaze ray (gazeOriginX..gazeRotW, lines 57-64, disabled by default for perf); when eyeTrackingEnabled and the user's gaze ray passes within 0.15m of her head position, suppress glances (she holds eye contact while being looked at), and on gaze-arrival fire a one-shot 2° chin dip + 2° head tilt over 400ms (acknowledgment). Gate polling: enable eye tracking only when a dancer is < 3m.

### Verifier corrections & device traps

1. **Tripo rigs likely have NO Neck bone** — tools/mixamo_extract/extract_preset.py:49-50 states "Neck is skipped... no Tripo equivalent bone in the user's rig inspection; head motion still works via mixamorig:Head → Head". The proposed 0.25·epsilon neck stage will silently no-op on those rigs (code guards on idx >= 0); plan a head-only fallback that absorbs the neck share. Mixamo-named rigs do have mixamorig:Neck.
2. The "39 joints" figure could not be verified exactly anywhere in the repo, but the load-bearing premise (no eye bones, no morph targets in the loader) is verified.
3. **History flag on part 3 (glance FSM):** the comment at :3159 says the user explicitly rejected the prior saccade FSM in favor of locked tracking — but that FSM retargeted the WHOLE-MODEL yaw bias (a pan), which is materially different from the proposed head-level micro-glances; still, expect user sensitivity here and keep glances small/suppressible. (The removed FSM's orphaned state fields remain in scene/SceneManager.kt:183-189 — gazeState/gazeSaccade*/gazeFreezeEndsMs, written only to 0 at :1534-1543, never read — and can be repurposed.)
4. The groove block writes head/neck via setJointEulerXYZ (overwrite-bind), so the gaze-distribution compose must execute AFTER the groove write site (single known location, :3420-3435) — ordering is easy but mandatory, exactly as the rec says ("composing OVER the groove layer").
5. The "~±1.4°" head bob figure understates fills slightly (torsoGain clamp allows ~1.65× ⇒ ~2.3° peak), which if anything strengthens the case for VOR counter-rotation.
6. `gazeVoDipRad` (:3824-3833) is dead whole-model chin-dip code from the removed FSM (declared = 0f at :3164, never assigned) — the proposed mutual-gaze acknowledgment dip should NOT reuse it as-is; implement at head-joint level.
7. **Mutual-gaze gating:** eye tracking requires the Samsung runtime permission (debug HUD hints "Eyes: not tracked (need permission?)") and CLAUDE.md warns Samsung eye/face tracking type constants diverge from Khronos — the C++ path exists (xr_renderer.cpp ~1797, capability bit 1<<1) but has plausibly never been exercised in production since nothing ever sets `eyeTrackingEnabled` true (XrSensorPoller.kt:35, default false, no writers); budget on-device validation time for part 4. See also RENDERING_REALISM_PLAN.md R9 traps: runtime permission requests inside FilamentModelActivity kill OpenXR (:353-355) — grant via adb or request from MainActivity.

---

## D9: Motion-source roadmap: re-enable JointDriveLayer with baked Mixamo-to-Tripo axis-correction (gating the Tier-4 double-drive first), then bake EDGE clips on the 5090 into a beat-indexed ClipDriveLayer

`category=motion-generation · impact=transformative · effort=week-plus · verdict=confirmed`
**Files:** `tools/mixamo_extract/extract_preset.py`, `scene/JointDriveLayer.kt`, `FilamentModelActivity.kt`, `AudioReactor.kt`

### Spec

Two phases sharing the same retarget machinery and compose-onto-skeleton pattern; phase A is the prerequisite stepping stone for phase B.

**PHASE A — JointDriveLayer:** the ONLY real-mocap articulation in the app (~600 Mixamo-extracted two-harmonic Fourier joint drives across 31 presets: Spine01/02, clavicles, upper arms, forearms, head; 1.5–30° amplitudes) ships as dead data because layer.evaluate() is commented out (FilamentModelActivity.kt:3645-3646) — the drives are in Mixamo bone-local frames and read as "spinning bent-over" on Tripo rigs. Until fixed, all 40 presets share the SAME generic procedural arm/spine/head motion, and the rigid-body halves of the 31 Mixamo presets saturate the 15°/10°/4cm caps so the library collapses to near-identical motion. Fix in `tools/mixamo_extract/extract_preset.py` (path documented in its README:115-125):

1. Load a reference Tripo bind pose from the production GLB; per mapped bone compute `R_corr = R_bindTripoLocal^-1 * R_bindMixamoLocal` (rotation-only).
2. Transform the SAMPLED rotation track, not the coefficients: `R_tripo(t) = R_corr * R_mixamo(t) * R_corr^-1`, Euler-decompose in Tripo joint order, RE-FIT the two-harmonic Fourier per axis (conjugation mixes axes, refitting is mandatory).
3. Re-emit all 31 preset tables.
4. **CRITICAL runtime companion:** comments at :3057-3062 and :3100-3101 claim Tier-4 arm/elbow writes are skipped "when a JointDriveLayer is present" but NO such gate exists — add `if (m.jointLayer == null)` around the Tier-4 arm sway/reach/elbow blocks (:3036-3119) BEFORE uncommenting evaluate(), or you reproduce the spazzing-arms double-drive.

Keep the groove micro-layer composing on top (1–4° seasoning). Validate one preset on the BIKINI GIRL rig first.

**PHASE B — EDGE clips (the end-state):** every crowd-validated realistic virtual dancer — Dance Central, Wave, ChoreoMaster in production at NetEase — is mocap/ML-sourced with procedure layered on top; pure oscillators have a hard ceiling the Mixamo extraction already measured: raw clips carry 2–3× the motion the preset schema can hold.

1. **OFFLINE on the RTX 5090 laptop** (24GB > the 13GB jukemirlib needs): bake 10–30 EDGE clips (MIT code + AIST++ CC-BY-4.0 — the license-clean combo; explicitly AVOID FineDance-trained models like Lodge, whose license forbids pornographic use — production-poisoned for Ash Airfoil) for the actual production tracks/genres.
2. Retarget SMPL→Tripo 39-joint rig in Blender using the SAME axis-correction machinery from phase A (ship no SMPL assets — retarget transiently).
3. Export per-joint local quaternion tracks resampled to 8 samples/beat normalized to clip BPM, cut at bar boundaries into 2–4 bar nodes, stored as sidecar JSON or GLB extras (keep EDGE's heel/toe contact labels for the future foot-IK owner).
4. **RUNTIME:** a ClipDriveLayer sibling of JointDriveLayer that samples `track[barIdx*32 + floor(phaseInBar*32)]` with Catmull-Rom between samples — indexing by the existing epoch phase makes tempo-warping FREE (ChoreoGraph pattern), zero ML on device; transition at bar boundaries to the node with lowest end-pose distance within the current energy bucket (musicalLevel quantized to 3 buckets), 0.25-beat slerp blend.
5. **KEEP the procedural stack on top** (groove micro-layer, breath, fBm, onset-scaled impact kicks, gaze, musicalLevel gains) — the ChoreoMaster-validated architecture at Quest-mobile cost.

Removes the 1-bar periodicity ceiling permanently; per-song baking gives her moves that belong to THAT track.

### Verifier corrections & device traps

1. The insistence on RE-FITTING the Fourier coefficients after conjugation (rather than transforming coefficients) is technically necessary — JointDriveLayer.evaluate treats each axis as an independent sinusoid summed into per-joint X/Y/Z accumulators composed in Z-Y-X order, so a basis change that mixes axes cannot be expressed by per-axis coefficient rotation.
2. Minor count nit: actual JointDrive entries = 627 (rec said ~600 — fine); 31 JointDriveLayer instantiations confirmed exactly.
3. **The misleading docstrings in JointDriveLayer.kt (claiming calibration already baked — :23-25, :141-143) and the phantom-gate comments at :3057-3062/:3100-3101 should be treated as part of the fix scope** — they document the intended end-state, not current behavior, and will mislead future sessions if left unreconciled. (The extractor contains NO bind-pose loading, no correction quaternion, no conjugation — only the JOINT_MAP name retarget and per-axis FFT + rest-angle DC extraction; the README refutes its own docstrings at README.md:115-125, including the note that the BIKINI GIRL bind pose "hasn't been parsed into an extractor input yet".)
4. Line numbers all checked out exactly against the current working tree (3645-3646, 3057-3062, 3100-3101, README 115-125); the gate insertion range :3036-3119 correctly brackets the arm sway/reach (:3063-3079) and elbow (:3102-3120) writes.
5. Phase B specifics (EDGE licensing, Lodge/FineDance avoidance, 5090 VRAM headroom) are external-world claims not verifiable from the repo, but nothing in the codebase contradicts them; the in-repo prerequisites (39-joint Tripo rig naming via JOINT_MAP, bar-phase indexing, preset energy gains) all exist. Runtime machinery is ready: composeJointEulerXYZ right-multiply compose-on-top pattern, zero-alloc accumulator in JointDriveLayer.evaluate (:72-128), ar.phaseAtSnap + snap.musicalLevel provide the beat indexing Phase B needs; a pre-baked sampled-track ClipDriveLayer is compatible with invariant 6.

---

## D10: Helper-bone flesh chains: add breast/glute/hair bones in a scripted Blender post-process and drive them with VRM-spec verlet spring bones

`category=secondary-motion · impact=transformative · effort=week-plus · verdict=confirmed`
**Files:** `scene/SkeletonRuntime.kt`, `FilamentModelActivity.kt`, `GlesModelRenderer.kt`, `docs/TPOSE_PROMPT.md`

### Spec

The only flesh response today is whole-model scale wobble — flesh never moves relative to the skeleton, the single biggest gap between "animated figurine" and "body" for this content at 1m. The renderer has headroom: MAX_JOINTS_SHADER=64 (GlesModelRenderer.kt:28) vs 39 used (25 free slots), and the loader handles arbitrary joint counts in topo order.

- **PIPELINE (one scripted Blender pass per character, added to the TPOSE workflow):** append Breast_L/R (children of Spine02, at nipple depth), Glute_L/R (children of Pelvis), and 2–4 hair bones (chain under Head); assign weights by spherical vertex groups (radius ~9cm breasts, ~12cm glutes) blended 50/50 with the existing parent weights so the helper bone shares influence rather than owning it; export GLB — loader needs zero changes (verify <64 joints; names get an alias entry).
- **RUNTIME:** implement the published VRMC_springBone-1.0 verlet per joint: `nextTail = tail + (tail - prevTail)*(1 - drag) + dt*parentRot*boneAxis*stiffness + dt*gravity`; renormalize to bone length; convert tail direction to the joint's local rotation; evaluate strict root-to-leaf.
- **Parameters:** breasts drag 0.40 stiffness 0.65 gravity 0; glutes drag 0.50 stiffness 0.80 gravity 0; hair drag 0.30 stiffness 0.35 gravity 0.02, plus one sphere collider (r~10cm at Spine02) so hair clears the chest (collider pushout is in the spec pseudocode).
- **Leaf squash:** scale breast bones ±8% along the velocity axis with the inverse on orthogonal axes (volume cue, Boing Kit technique).
- Excite the chains from the skeleton the D7 spring-lag layer already animates — no extra coupling code.
- **Budget:** ≤10 spring bones; VRChat benchmarks ~0.66ms per 1000 transforms, so <0.05ms — irrelevant to the frame budget.
- **SUPERSEDES** the global scaleMul jiggle for flesh (keep scaleMul for squash-and-stretch only; drop its high-band ring gain to 0.25) and complements D2: shader spheres do beat-synced pops, bones do physical inertia.

### Verifier corrections & device traps

Minor inaccuracies that do not change the verdict:

1. MAX_JOINTS_SHADER is at GlesModelRenderer.kt line 28, not 25. (Both VERTEX_SHADER and SHADOW_VERTEX_SHADER declare `mat4 uJoint[64]`; palette upload clamps to min(skel.jointCount, 64) and zero-fills; 39 + 8 helpers = 47, well under 64.)
2. "Only flesh response is whole-model scale wobble" glosses over the existing glute shader-sphere push — a real, per-frame-driven, vertex-level flesh mechanism — though it is non-bone, non-inertial, bind-space-anchored, and recorded in-code as visually ineffective (:2707-2714: "the user saw NO glute shake at all"); the rec correctly treats it as the complementary D2 system.
3. "D7 spring-lag layer" as excitation source: the matching shipped code is the inertia-lag + damped-spring block at :3868-3925, and the excitation premise holds since Tier 4 + JointDriveLayer animate the proposed parent joints (Spine02, Pelvis, Head).

Implementation notes for the executor:

- (a) SkeletonRuntime's docstring mentions setJointQuat but only Euler setters exist — the spring layer needs a new quat-or-matrix local-pose writer plus a scale-capable write for the ±8% leaf squash.
- (b) SkeletonRuntime hard-crashes (`check(p < j)`, SkeletonRuntime.kt:59) on non-topo-sorted skins — Blender's GLB exporter emits DFS order so appended child bones are fine, but the Blender script should verify joint order in the exported skin.
- (c) Springs must evaluate before the single skel.update() per frame (or use the previous frame's globalPose) since palette composition is one forward pass.
- (d) Per CLAUDE.md, this targets Samsung Galaxy XR hardware and a separate Quest 3 port project exists — parameter tuning claims (drag/stiffness values, <0.05ms budget) are design estimates not verifiable from the repo, only that the joint-count and architecture headroom for them is real. Joint resolution is by NAME with explicit alias fallback chains (:2802-2818, :3005-3018, :3361), not positional index, so appended uniquely-named bones cannot break existing lookups.

---

## D11: Lighting-correct deformation bundle: weld-preserving tangents to re-enable normal maps on dancers, scale-compensated normal matrix, and a once-per-frame palette pre-pass (fixes one-frame shadow lag)

`category=deformation · impact=medium · effort=days · verdict=confirmed`
**Overlaps RENDERING_REALISM_PLAN.md R3 — implement once, satisfies both. See Cross-References.**
**Files:** `GlesModelRenderer.kt`, `FilamentModelActivity.kt`, `scene/SkeletonRuntime.kt`

### Spec

The hero assets render with the worst shading in the app. Three independent fixes:

1. **TANGENTS WITHOUT UNWELD** — normal maps are force-disabled on every skinned model because tangent generation goes through a 3× triangle-soup unweld that would break JOINTS_0/WEIGHTS_0 (the code's own TODO at GlesModelRenderer.kt:1300-1314 says compute per-vertex averaged tangents if bump detail becomes critical — at 1m in stereo it is critical). Implement the standard welded-accumulation pass at load: per triangle compute the Lengyel tangent from UV deltas, accumulate into the three welded vertices, Gram-Schmidt orthonormalize against the vertex normal, fix handedness (`w = sign(dot(cross(n,t),b))`). Upload as the existing TANGENT attribute; remove the hasNormalMap=false override — skin micro-detail (pores, fabric weave) returns to every dancer. **(Verifier note (a): the naive version of this historically produced black blotches on Tripo GLBs — must be handedness-aware; the shader side already exists; see below and R3.)**
2. **NORMAL MATRIX** — extractNormalMatrix takes the plain upper-left 3×3 of modelView (GlesModelRenderer.kt:2634-2637, no inverse-transpose), so the per-frame non-uniform jiggle/squash scale (scaleMulX != Y != Z, every dance frame per :3883-3921) skews lighting normals on every wobble cycle. Since the ONLY non-uniformity is the diagonal scaleMul, pass a 3-float compensation and multiply component-wise before normalize() — **use the corrected math in verifier note (b), not the rec's normalize(1/sx,1/sy,1/sz)**.
3. **PALETTE PRE-PASS** — skel.update() + the 4KB glBufferSubData run once per eye inside renderEye, while the shadow pass (which runs FIRST, :3993-3994 "// Shadow map (once, before both eyes)") binds the previous frame's palette: skinned shadows lag the body by one frame, a visible swim at close range under the PCF light (renderShadowMap GlesModelRenderer.kt:1643-1653 binds "the already-uploaded UBO"; renderEye :1789-1810 does skel.update() + 4096-byte upload per eye). Hoist palette update + upload into a single pre-pass before renderShadowMap — halves the per-frame palette cost (SkeletonRuntime.update() is a full hierarchy walk, currently 2× per frame per skinned model) and gives shadows the current pose. While in there, add the glute push block to the shadow VS (pairs with D2). **Bonus:** this also removes the `ByteBuffer.allocateDirect(4096)` per skinned model PER EYE PER FRAME at renderEye :1800-1802 — an existing violation of zero-alloc invariant 6 — and fixes frame 0's shadow pass binding a never-uploaded UBO (created with null data at :1434).

### Verifier corrections & device traps

- **(a) WELDED-ACCUMULATION HISTORY:** the exact algorithm the rec prescribes ALREADY EXISTS as dead code: `computeTangents()` at GlesModelRenderer.kt:2539-2578 (only referenced in comments). The Tier1.5 comment at :1326-1333 documents that this exact welded approach was the PRIOR implementation and produced the "war paint / confetti black-blotch" artifact on Tripo GLBs: at UV seams adjacent triangles' tangents have opposite signs, sums cancel to ~0, Gram-Schmidt fails, handedness flips per-fragment. The rec's "UV-seam smear is acceptable" framing understates this — on these assets the naive version fails as black blotches, not smear. The fix is still feasible but must be **handedness-aware**: bucket accumulation by per-triangle w-sign (splitting only the seam verts that disagree, copying their JOINTS_0/WEIGHTS_0 — a tiny, weight-safe duplication), or skip-on-sign-disagreement, or sidestep tangents entirely with a derivative-based screen-space cotangent frame in the fragment shader (zero loader changes, works skinned). Also: the cheapest first step is respecting baked TANGENTs when present — line :1312 disables normal maps even if the GLB shipped them (shader comment :2802-2803 says current Tripo rigs ship none, so moot today). R3's verifier additionally found a latent NaN trap in computeTangents (degenerate-UV triangles leave near-zero tangents that NaN after shader normalize — port the unit-tangent fallback from unweldWithTangents).
- **(b) MATH NIT ON FIX 2:** pre-multiplying the model-space normal component-wise by normalize(1/sx,1/sy,1/sz) before the plain matrix yields R·n (scale effect removed entirely), NOT the true inverse-transpose R·S⁻¹·n. The exact compensation is component-wise **1/s²** before the plain matrix (since S·(n/s²) = S⁻¹·n). For the ±few-percent jiggle amplitudes here the difference is second-order and either variant kills the visible skew, but "exact inverse-transpose for this case" as written in the source rec is incorrect. Trivial to do right.
- **(c)** "Visible swim at close range" from the one-frame shadow lag is a perceptual claim not verifiable from code alone (needs Galaxy XR hardware), but the staleness itself is structural fact, and the pre-pass is independently justified by the halved palette cost and by eliminating the per-eye ByteBuffer.allocateDirect render-loop allocation (invariant 6 violation).
- Line-number note: this audit and the rendering audit (R3) cite slightly different lines for the same dead code (computeTangents :2539-2578 here vs :2496-2537 in R3) — the file is being edited in parallel sessions; re-locate before editing.

---

## D12: Volume preservation for deep knee bends and future arm twist: drive the shipped twist bones now, dual-quaternion skinning when JointDriveLayer returns

`category=deformation · impact=medium · effort=week-plus · verdict=confirmed`
**Files:** `GlesModelRenderer.kt`, `FilamentModelActivity.kt`, `scene/SkeletonRuntime.kt`

### Spec

Plain 4-influence LBS with a −85° knee at full squat produces visible inner-knee collapse at 1m, and the moment D9 re-enables JointDriveLayer arm articulation, upper-arm candy-wrapper joins it.

- **PHASE 1 (days, do now):** the Tripo rig SHIPS ThighTwist01/02 and CalfTwist01/02 in its 39 joints (docs/TPOSE_PROMPT.md:85, NOTES.md:350-353) and no Kotlin code ever drives them (grep 'Twist' in kotlin: zero drivers). Add twist distribution in the dance loop: whenever a Y-axis (long-axis) rotation lands on Thigh/Calf (or Upperarm once drives return), move 50% to Twist01 and 25% to Twist02, leaving 25% at the parent — the standard rig technique that spreads the twist gradient along the limb instead of shearing it at one joint ring. Verify each twist bone's axis with a 10-minute debug slider before trusting signs (axis conventions were tuned on one rig).
- **PHASE 2 (the week+ part):** a DQS path in the vertex shader — CPU converts the 39 palette mat4s to dual quaternions (rotation-only IBMs make this exact, trivial cost), upload as uJointDqR/uJointDqD vec4 pairs (2KB vs 4KB UBO), shader does DLB with antipodality fix (flip sign when `dot(q0.r, qi.r) < 0`), normalize, transform. DQS bulges slightly at bends — for knees/glutes that error is in the flattering direction, opposite of LBS collapse. Keep the LBS path for any future beyond-rotation rigs and gate per-model.
- **Sequencing:** deliberately ranked below the motion work — collapse artifacts matter less than motion artifacts per the believability research — but it becomes prerequisite-quality before any close-up squat/twerk content ships as final footage.

### Verifier corrections & device traps

1. Citation imprecision — TPOSE_PROMPT.md:85 says only "+ twist bones" generically; the explicit ThighTwist01/02 / CalfTwist01/02 names live at NOTES.md:353 (NOTES.md:406 logs them as an unresolved open design question: "proportional-to-parent / independent / at-rest?").
2. **Rig contents are doc-attested only:** no GLB ships in the repo (models load from device /sdcard/RIGGED/), so neither the twist joints' presence among the 39 skin joints nor whether Tripo painted any skin weights onto them can be binary-verified — if they carry zero weights, Phase 1 driving them is a no-op; **verify with the recommended debug slider before investing.**
3. **Phase 1's leg trigger condition currently never fires:** Tier 4 writes only X-flex and Z-sway on Thigh/Calf (:2977/:2980/:3597-3603) — no Y-axis rotation lands on legs today, so leg twist redistribution has zero immediate visual effect; the immediate Phase-1 value is arm-side and only materializes when JointDriveLayer re-enables (its presets DO include Upperarm AXIS_Y drives, e.g. :753, :756). Relatedly, twist bones address torsion, not the −85° knee flexion collapse — that artifact is only addressed by the Phase 2 DQS half, so **the two phases are not substitutes**.
4. "D9" is this plan's work-item ID, not a repo reference (no matches in any repo .md at audit time).
5. UBO math checks out: 64 mat4 = 4KB vs 64 DQ vec4-pairs = 2KB.
6. The rotation-only-IBM exactness claim for mat4→DQ conversion is a property of the GLB and likewise unverifiable in-repo.

---

# Cross-References

## D11 ↔ R3 (RENDERING_REALISM_PLAN.md): one tangent implementation satisfies both

R3 ("Restore normal mapping on rigged dancers via indexed per-vertex tangent averaging + skinned TBN") and D11 fix 1 are the same work item discovered by two independent audits. **Implement once.** Merge the two verifiers' findings when doing so:

- R3's verifier: the entire shader side ALREADY EXISTS (skinned tangent transform `tan = normalize(S3 * tan)` gated on uHasNormalMap, Gram-Schmidt + w-handedness passthrough) — no shader edit needed; `computeTangents()` exists as dead code with ZERO call sites; minimal change is call-it-for-skinned-meshes + delete the `hasNormalMap = false` gate at :1310-1314; port the unit-tangent NaN fallback from unweldWithTangents; the gate is over-broad (fires even when a skinned GLB ships baked TANGENT).
- D11's verifier: the dead computeTangents is the PRIOR implementation that produced the "war paint / confetti black-blotch" artifact at UV seams on Tripo GLBs — the resurrection must be handedness-aware (bucket by per-triangle w-sign, split only disagreeing seam verts with JOINTS_0/WEIGHTS_0 copied), or use a screen-space cotangent frame instead.
- D11 fixes 2 (normal-matrix scale compensation) and 3 (palette pre-pass / shadow-lag / zero-alloc) are dance-audit-only — they do not appear in R3 and must not be dropped when the tangent work is folded into the rendering track.

## Audio-feed dependency: the Visualizer is the weakest link under D1/D4

The AudioReactor currently runs on `android.media.audiofx.Visualizer`: ~20Hz capture, 8-bit magnitudes, and it requires the RECORD_AUDIO **runtime** permission that the app never requests — the Visualizer dies silently and the whole beat system goes dark with no UI feedback (failure surfacing was added in the rendering session; root cause confirmed). Until the permission flow exists, grant manually:

```
adb shell pm grant com.ashairfoil.prism android.permission.RECORD_AUDIO
```

The planned replacement is a **Media3 AudioProcessor PCM tap** on the app's own playback chain: no permission at all, full-precision PCM at audio rate instead of 8-bit 20Hz captures. This directly upgrades:

- **D1** — epoch anchoring accuracy (the "lands ON the beat" bound) is currently limited by ~50ms Visualizer capture quantization; PCM-rate onset timing tightens it to a few ms.
- **D4** — BeatDetector's 43-slot history was designed for ~43Hz; at the Visualizer's ~20Hz it works but is starved. A PCM tap feeds it at design rate, and onset strength/tempo confidence stop being quantization-limited.
- **ChloeVibes haptics** — the embedded vibes engine (BeatDetector → EnvelopeProcessor/ClimaxEngine → Lovense) consumes the same FFT path, so the tap means **dance + lighting/strobe + haptics all share one beat clock** derived from one signal. Build the tap as part of the D4 milestone (it is the same plumbing surface: AudioReactor's FFT callback and FrameSnapshot).

---

# Execution Order

| Milestone | Items | Notes |
|---|---|---|
| **1. Quick wins** | D1 → D2 → D3 | Hours each. D1 first — it fixes the Float-precision grid bug AND gives D4's downbeat rotation a correct epoch-relative base. D2 is gizmo-first (mandatory). D3's idle layer is the cheapest believability win in the plan. |
| **2. Audio milestone** | D4 + Media3 PCM tap | One unit of work: same files, same callback surface. Land the tap first or alongside — D4's PLL/vote/build-drop all improve with the better signal. Keep Visualizer as fallback for external-audio mode. |
| **3. Motion-feel milestone** | D5, D6, D7 | D5 is self-contained (one file). D6 and D7 both touch the joint-write ordering in the dance loop — read D6 caveat 1 (dual thigh writers) and D7 caveat 4 (multiple writers, set-vs-compose) together before starting either. D7 starts head/neck only. |
| **4. Gaze** | D8 | After D7 so head springs and gaze composes have a settled ordering. Part 4 (mutual gaze) is Galaxy-XR-only and needs on-device permission validation — parts 1–3 ship without it. |
| **5. Tentpoles** | D9, then D10 | D9 Phase A is extractor work (Python, offline) + the runtime gate; Phase B's EDGE baking runs OFFLINE on the RTX 5090 laptop (Windows side) — zero on-device ML. D10 requires the Blender re-export pipeline; do after D9 Phase A proves the axis-correction machinery. D12 Phase 1's arm value also unlocks at D9 Phase A. |
| **6. Deformation cleanups** | D11, D12 | D11 fix 1 merges with rendering R3 (implement once); fixes 2–3 can land any time (fix 3 also clears a standing zero-alloc violation). D12 Phase 1 after D9; Phase 2 (DQS) before any close-up squat/twerk final footage. |

# Standing Rules (apply to every item above)

1. **Build after every change.** `gradlew assembleDebug` must pass for BOTH flavors (galaxyxr + quest) before moving on. Each broken APK costs 5+ minutes of sideloading. All dance/render code lives in `src/main` — one edit covers both flavors, and both must keep compiling.
2. **Verify on device.** Beat alignment, anticipation sign (D1 trap 2), glute anchor posing (D2), heel drift (D6 acceptance test), spring feel (D7), gaze behavior (D8) are all perceptual — adb logcat + headset, not code inspection. Never claim "platform limitation" without exhausting the API surface (CLAUDE.md Iron Rule).
3. **Zero-alloc render loop (Invariant 6).** Scratch FloatArrays, preallocated bags/buffers, no per-frame allocations. BeatDetector's Pair-returning process() stays on the Visualizer/audio thread; consumers read its @Volatile fields. D11 fix 3 removes the one known existing violation.
4. **41-float ControllerState contract.** Nothing in this plan touches it — keep it that way. Any change requires simultaneous updates to `openxr_input.h`, `openxr_input.cpp`, `jni_bridge.cpp`, `OpenXRInput.kt`, and `InputHandler.kt` (sequential, never parallel agents). Note the separate 41-float lightEstimateBuffer coincidence — different buffer, don't confuse them.
5. **No hand-tracking features. Controllers only.** D8's eye tracking (Galaxy XR) is the only sensor addition in this plan, and it stays gated, default-off, and poll-rate-limited per Invariant 7.
6. House rules: functions under 50 lines (the dance loop is already at the limit — new layers go in new files per Invariant 9, e.g. an `IdleLayer`/`SpringLagLayer`/`ClipDriveLayer` under `scene/`); fix the stale comments you touch (D9 note 3 makes this explicit scope); no debug code left behind — debug gizmos/sliders (D2, D12) are removed or flag-gated before commit.
