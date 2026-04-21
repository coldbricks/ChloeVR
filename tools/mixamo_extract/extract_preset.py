"""Phase 1 extractor: Mixamo FBX clip → single DancePreset row.

Semantics locked in by reading FilamentModelActivity.kt:
    yawCycleMs = (60000/bpm) * 4 / yawRate         → yawRate = cycles per MEASURE
    yawDeg coerced to [0, 15] degrees
    pitchDeg coerced to [0, 10] degrees
    bobM coerced to [0, 0.04] meters
    phases are offsets into the cycle in [0,1]

Axis mapping (Mixamo Y-up world):
    Hips.Lcl Rotation Y → engine yaw
    Hips.Lcl Rotation X → engine pitch
    Hips.Lcl Rotation Z → engine roll (→ counter-roll measurement against Spine2.Z)
    Hips.Lcl Translation Y → engine bob

Beat inference:
    Bob fundamental frequency f_bob is assumed to be 1 cycle per beat
    (standard Mixamo authorial convention — a twerk drops once per beat).
    bpm = 60 * f_bob. All rate fields = round(4 * f_axis / f_bob).

Clamp-aware: if the extracted amplitude exceeds the runtime coerceIn cap,
we emit the cap value and log a warning so Phase 2 can revisit.
"""

import sys
import math
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import numpy as np
from fbx_anim import extract_animation


# DancePreset runtime clamps (from FilamentModelActivity.kt:818-820).
YAW_DEG_MAX = 15.0
PITCH_DEG_MAX = 10.0
BOB_M_MAX = 0.04

# Rate snap grid — presets in the codebase use these values exclusively.
RATE_GRID = [1, 2, 4, 8, 16, 32]


def resample_uniform(series, fps=60, unwrap_degrees=False):
    """Resample a non-uniform (t, v) series to a uniform fps grid.

    If unwrap_degrees is True, unwrap the Euler angle curve to remove the
    ±180deg jumps that happen when a spinning body crosses the discontinuity
    (e.g. Salsa/Samba spin moves give 700-1000deg peak-to-peak otherwise).
    """
    if not series:
        return np.zeros(0), 0.0
    t = np.array([p[0] for p in series], dtype=np.float64)
    v = np.array([p[1] for p in series], dtype=np.float64)
    if len(t) < 2:
        return v.copy(), 0.0
    if unwrap_degrees:
        # Unwrap in degree space: numpy.unwrap expects radians by default.
        v = np.degrees(np.unwrap(np.radians(v)))
    t0, t1 = t[0], t[-1]
    n = int(round((t1 - t0) * fps)) + 1
    ts = np.linspace(t0, t1, n)
    vs = np.interp(ts, t, v)
    return vs, t1 - t0


def dominant_sine(signal, fps, min_freq=0.3, max_freq=8.0):
    """Find the dominant sinusoid frequency+amplitude+phase in `signal`.

    DC is removed first. We search in [min_freq, max_freq] Hz to avoid
    the FFT's first bins (long-term drift) and Nyquist aliasing noise.

    Returns (f_hz, amplitude, phase_rad) where
        signal(t) ≈ amplitude * cos(2π f t + phase) + DC
    """
    N = len(signal)
    if N < 8:
        return 0.0, 0.0, 0.0
    sig = signal - np.mean(signal)
    # Hann window to clean spectral leakage for non-integer-cycle clips.
    win = np.hanning(N)
    fft = np.fft.rfft(sig * win)
    freqs = np.fft.rfftfreq(N, d=1.0 / fps)
    # Window gain correction for amplitude: sum(hann) / N = 0.5 → factor 2
    # Plus rFFT single-sided amplitude factor 2/N.
    mag = np.abs(fft) * (2.0 / N) / 0.5
    # Mask to our search band
    mask = (freqs >= min_freq) & (freqs <= max_freq)
    if not mask.any():
        return 0.0, 0.0, 0.0
    idx_band = np.where(mask)[0]
    peak_rel = int(np.argmax(mag[mask]))
    peak_idx = int(idx_band[peak_rel])
    f_peak = float(freqs[peak_idx])
    amp_peak = float(mag[peak_idx])
    phase_peak = float(np.angle(fft[peak_idx]))
    return f_peak, amp_peak, phase_peak


def harmonic_amp(signal, fps, f_fundamental, k):
    """Amplitude of the k-th harmonic (k=2 → 2f) at the given fundamental.

    Uses direct bin pick around the expected bin; no window compensation
    subtlety — we only use this for a *ratio* against the fundamental.
    """
    N = len(signal)
    if N < 8 or f_fundamental <= 0:
        return 0.0
    sig = signal - np.mean(signal)
    fft = np.fft.rfft(sig * np.hanning(N))
    freqs = np.fft.rfftfreq(N, d=1.0 / fps)
    target_f = k * f_fundamental
    idx = int(np.argmin(np.abs(freqs - target_f)))
    return float(np.abs(fft[idx]) * (2.0 / N) / 0.5)


def sharpness_triangle_vs_sine(signal, fps, f_fund, amp_fund, phase_rad):
    """How triangle-like is the signal? 0 = pure sine, 1 = triangle/square.

    A perfect sine has 0 odd-harmonic content beyond the fundamental.
    A perfect triangle has odd harmonics with 1/k² decay.
    We use amp(3f)/amp(f) as a cheap proxy — sine gives 0, triangle gives ≈1/9.
    Scale so that 1/9 → sharpness = 1.
    """
    if amp_fund <= 1e-6 or f_fund <= 0:
        return 0.0
    a3 = harmonic_amp(signal, fps, f_fund, 3)
    ratio = a3 / amp_fund
    # Triangle: 1/9 ≈ 0.111. Clip to [0,1].
    return float(min(ratio / 0.111, 1.0))


def complexity_second_harmonic(signal, fps, f_fund, amp_fund):
    """Ratio of 2nd-harmonic amplitude to fundamental, clipped [0,1].

    In DancePreset this goes into yawCmplx/pitCmplx/bobCmplx — a measure of
    how much the motion doubles-up within one cycle (e.g. figure-8 vs plain
    yaw). Existing hand-tuned TWERK uses 0.5-0.9 here.
    """
    if amp_fund <= 1e-6 or f_fund <= 0:
        return 0.0
    a2 = harmonic_amp(signal, fps, f_fund, 2)
    return float(min(a2 / amp_fund, 1.0))


def snap_rate(cycles_per_measure):
    """Snap a continuous rate to the nearest grid value {1,2,4,8,16,32}."""
    if cycles_per_measure <= 0:
        return 1
    return min(RATE_GRID, key=lambda r: abs(math.log(r / max(cycles_per_measure, 0.01))))


def phase_to_unit(phase_rad):
    """FFT phase (radians) → [0,1] cycle fraction."""
    # FFT's rfft convention: component is at angle φ means signal = cos(2π f t + φ).
    # Our preset uses phase as an offset into the cycle, range [0, 1).
    return float(((phase_rad / (2 * math.pi)) + 1.0) % 1.0)


def extract(fbx_path, clip_name, verbose=True):
    want = {"mixamorig:Hips", "mixamorig:Spine2"}
    anim, _, _ = extract_animation(fbx_path, bone_filter=want)
    hips = anim.get("mixamorig:Hips")
    if not hips:
        raise RuntimeError("mixamorig:Hips animation not found")

    fps = 60
    rx_raw = hips["Lcl Rotation"]["X"]
    ry_raw = hips["Lcl Rotation"]["Y"]
    rz_raw = hips["Lcl Rotation"]["Z"]
    ty_raw = hips["Lcl Translation"]["Y"]

    # Unwrap rotations so characters that spin through full turns (Salsa,
    # Samba) don't produce fake 360deg jumps that corrupt FFT amplitudes.
    rx, dur = resample_uniform(rx_raw, fps, unwrap_degrees=True)
    ry, _ = resample_uniform(ry_raw, fps, unwrap_degrees=True)
    rz, _ = resample_uniform(rz_raw, fps, unwrap_degrees=True)
    ty, _ = resample_uniform(ty_raw, fps)  # translation: don't unwrap

    # Detrend long-term drift (subtract linear fit) so bob/yaw centers stable.
    def detrend(x):
        n = len(x)
        if n < 4:
            return x
        t = np.arange(n)
        slope, intercept = np.polyfit(t, x, 1)
        return x - (slope * t + intercept)

    rx_d = detrend(rx)
    ry_d = detrend(ry)
    rz_d = detrend(rz)
    ty_d = detrend(ty)

    # Beat detection. Restrict search to musical BPM range 60-180 so that
    # long-period dance envelopes (the 30-cycle House "wave") don't dominate
    # the faster per-beat bob. If nothing in the band, fall back to wider.
    BPM_MIN, BPM_MAX = 60.0, 180.0
    f_bob, amp_bob, phase_bob = dominant_sine(ty_d, fps, min_freq=BPM_MIN/60, max_freq=BPM_MAX/60)
    if amp_bob < 0.3:
        # Bob signal too weak at beat frequencies — try yaw and roll in-band.
        f_yaw_tmp, amp_yaw_tmp, _ = dominant_sine(ry_d, fps, min_freq=BPM_MIN/60, max_freq=BPM_MAX/60)
        f_roll_tmp, amp_roll_tmp, _ = dominant_sine(rz_d, fps, min_freq=BPM_MIN/60, max_freq=BPM_MAX/60)
        if max(amp_yaw_tmp, amp_roll_tmp) < 0.5:
            # Nothing in the 60-180 BPM band — fall back to full-spectrum bob.
            f_bob, amp_bob, phase_bob = dominant_sine(ty_d, fps)
            if amp_bob < 0.3:
                # Try full-spectrum on yaw/roll as last resort.
                f_yaw_tmp, amp_yaw_tmp, _ = dominant_sine(ry_d, fps)
                f_roll_tmp, amp_roll_tmp, _ = dominant_sine(rz_d, fps)
                if max(amp_yaw_tmp, amp_roll_tmp) < 1.0:
                    raise RuntimeError(
                        f"No cyclic motion detected. Probably a freeze/pose clip - skipping."
                    )
                f_bob = f_yaw_tmp if amp_yaw_tmp >= amp_roll_tmp else f_roll_tmp
        else:
            f_bob = f_yaw_tmp if amp_yaw_tmp >= amp_roll_tmp else f_roll_tmp
    if f_bob <= 0:
        raise RuntimeError("No dominant bob frequency detected")
    bpm = 60.0 * f_bob

    # Per-axis fundamentals.
    f_yaw, amp_yaw, phase_yaw = dominant_sine(ry_d, fps)
    f_pit, amp_pit, phase_pit = dominant_sine(rx_d, fps)
    f_roll, amp_roll, phase_roll = dominant_sine(rz_d, fps)

    # Rate = cycles per measure (4 beats). Bob defines the beat.
    yaw_rate = snap_rate(4.0 * f_yaw / f_bob) if f_yaw > 0 else 1
    pit_rate = snap_rate(4.0 * f_pit / f_bob) if f_pit > 0 else 1
    bob_rate = snap_rate(4.0 * f_bob / f_bob)  # = 4 (bob is 1 cycle/beat by definition)

    # Amplitudes — the fundamental FFT amplitude captures only the smooth
    # sinusoidal baseline, not the full swing. For real mocap, p2p/2 is
    # closer to what the eye sees. Use robust percentile envelope (2/98)
    # to reject isolated spikes from the peak-detection.
    def envelope_amp(x):
        lo, hi = np.percentile(x, 2), np.percentile(x, 98)
        return float((hi - lo) / 2.0)

    yaw_deg_raw = envelope_amp(ry_d)
    pit_deg_raw = envelope_amp(rx_d)
    bob_m_raw = envelope_amp(ty_d) / 100.0  # cm -> m
    yaw_deg = min(yaw_deg_raw, YAW_DEG_MAX)
    pit_deg = min(pit_deg_raw, PITCH_DEG_MAX)
    bob_m = min(bob_m_raw, BOB_M_MAX)
    yaw_clamped = yaw_deg_raw > YAW_DEG_MAX
    pit_clamped = pit_deg_raw > PITCH_DEG_MAX
    bob_clamped = bob_m_raw > BOB_M_MAX

    # Phases (0..1 fraction into cycle).
    yaw_phase = phase_to_unit(phase_yaw)
    pit_phase = phase_to_unit(phase_pit)
    bob_phase = phase_to_unit(phase_bob)

    # Complexity (2nd harmonic).
    yaw_cmplx = complexity_second_harmonic(ry_d, fps, f_yaw, amp_yaw)
    pit_cmplx = complexity_second_harmonic(rx_d, fps, f_pit, amp_pit)
    bob_cmplx = complexity_second_harmonic(ty_d, fps, f_bob, amp_bob)

    # Sharpness (triangle vs sine).
    yaw_sharp = sharpness_triangle_vs_sine(ry_d, fps, f_yaw, amp_yaw, phase_yaw)
    pit_sharp = sharpness_triangle_vs_sine(rx_d, fps, f_pit, amp_pit, phase_pit)
    bob_sharp = sharpness_triangle_vs_sine(ty_d, fps, f_bob, amp_bob, phase_bob)

    # Counter-roll: how much does the upper body counter the hip roll?
    # Engine semantic (from FilamentModelActivity comments at line 666): a gain
    # of 0 means shoulders roll WITH hips (rigid body); gain of 1 means
    # shoulders stay locked while hips roll under them. Since twerk has
    # a stable upper body, ideal gain is high (near 1).
    # Measurement: compare Spine2 envelope to Hips envelope. If Spine2 barely
    # rolls while Hips roll a lot, shoulders are stable → high counter gain.
    spine2 = anim.get("mixamorig:Spine2", {})
    sp_rot = spine2.get("Lcl Rotation", {})
    hips_roll_env = envelope_amp(rz_d)
    if "Z" in sp_rot and hips_roll_env > 1e-6:
        sp_z, _ = resample_uniform(sp_rot["Z"], fps)
        sp_z_env = envelope_amp(detrend(sp_z))
        # stability_ratio = 1 means shoulders perfectly stable (full counter);
        # 0 means shoulders roll exactly with hips (no counter).
        follow_ratio = min(sp_z_env / hips_roll_env, 1.0)
        counter_roll_gain = float(max(0.0, 1.0 - follow_ratio))
    else:
        counter_roll_gain = 0.35  # safe default

    # Ease: classify waveform. High sharpness on yaw (primary axis) → LINEAR.
    ease = "LINEAR" if yaw_sharp > 0.5 else "SINE"

    # Pivots: Phase 1 uses existing TWERK defaults — empirical pivot detection
    # requires body-height normalization that belongs in Phase 2.
    pitch_pivot = 0.95
    roll_pivot = 0.95
    counter_roll_pivot = 0.50
    physics = 0.90

    preset = {
        "name": clip_name,
        "yawDeg": yaw_deg, "yawRate": yaw_rate, "yawPhase": yaw_phase,
        "pitchDeg": pit_deg, "pitchRate": pit_rate, "pitchPhase": pit_phase,
        "bobM": bob_m, "yRate": bob_rate, "yPhase": bob_phase,
        "ease": ease,
        "physics": physics,
        "pitchPivot": pitch_pivot, "rollPivot": roll_pivot,
        "counterRollPivot": counter_roll_pivot, "counterRollGain": counter_roll_gain,
        "yawSharp": yaw_sharp, "yawCmplx": yaw_cmplx,
        "pitSharp": pit_sharp, "pitCmplx": pit_cmplx,
        "bobSharp": bob_sharp, "bobCmplx": bob_cmplx,
    }

    # Diagnostic: peak-to-peak vs fundamental amplitude tells us how much of
    # the motion lives OUTSIDE the single-sinusoid approximation. High ratio
    # (p2p >> 2*A1) means the schema ceiling is the real limiter, not the fit.
    def p2p(x):
        return float(np.max(x) - np.min(x))

    if verbose:
        print(f"=== {clip_name} ===")
        print(f"Clip: {dur:.2f}s at {fps}fps ({len(rx)} samples)")
        print(f"Raw peak-to-peak (Hips bone, after detrend):")
        print(f"  ty: {p2p(ty_d):6.2f}cm    rx: {p2p(rx_d):6.2f}deg    ry: {p2p(ry_d):6.2f}deg    rz: {p2p(rz_d):6.2f}deg")
        print(f"Detected fundamentals (A1 amplitude of the dominant sinusoid):")
        print(f"  bob   (ty): {f_bob:5.2f}Hz  A1={amp_bob:6.2f}cm   phase={phase_to_unit(phase_bob):.3f}")
        print(f"  yaw   (ry): {f_yaw:5.2f}Hz  A1={amp_yaw:6.2f}deg  phase={phase_to_unit(phase_yaw):.3f}")
        print(f"  pitch (rx): {f_pit:5.2f}Hz  A1={amp_pit:6.2f}deg  phase={phase_to_unit(phase_pit):.3f}")
        print(f"  roll  (rz): {f_roll:5.2f}Hz  A1={amp_roll:6.2f}deg  phase={phase_to_unit(phase_roll):.3f}")
        a2_ty = harmonic_amp(ty_d, fps, f_bob, 2)
        a2_ry = harmonic_amp(ry_d, fps, f_yaw, 2) if f_yaw > 0 else 0.0
        a2_rx = harmonic_amp(rx_d, fps, f_pit, 2) if f_pit > 0 else 0.0
        print(f"2nd-harmonic energy (relative to A1):")
        print(f"  ty A2/A1={a2_ty/max(amp_bob,1e-6):.2f}   ry A2/A1={a2_ry/max(amp_yaw,1e-6):.2f}   rx A2/A1={a2_rx/max(amp_pit,1e-6):.2f}")
        print(f"Inferred tempo: {bpm:.1f} BPM (assumes bob = 1 cycle/beat)")
        print(f"Rates (cycles/measure): yaw={yaw_rate}, pitch={pit_rate}, bob={bob_rate}")
        clamp_notes = []
        if yaw_clamped:
            clamp_notes.append(f"yaw {yaw_deg_raw:.1f}deg -> {YAW_DEG_MAX}deg")
        if pit_clamped:
            clamp_notes.append(f"pitch {pit_deg_raw:.1f}deg -> {PITCH_DEG_MAX}deg")
        if bob_clamped:
            clamp_notes.append(f"bob {bob_m_raw*100:.1f}cm -> {BOB_M_MAX*100}cm")
        if clamp_notes:
            print(f"[!] Runtime caps hit (Phase 1 schema ceiling): {', '.join(clamp_notes)}")
        print(f"Counter-roll: gain={counter_roll_gain:.2f} (from Spine2.Z/Hips.Z)")
        print(f"Ease classification: {ease} (yaw sharpness {yaw_sharp:.2f})")
        print()
        print("--- DancePreset Kotlin literal ---")
        print(_format_kotlin(preset))

    return preset


def _format_kotlin(p):
    return (
        f'DancePreset("{p["name"]}",'
        f' {p["yawDeg"]:.2f}f, {p["yawRate"]:d}, {p["yawPhase"]:.3f}f,'
        f' {p["pitchDeg"]:.2f}f, {p["pitchRate"]:d}, {p["pitchPhase"]:.3f}f,'
        f' {p["bobM"]:.4f}f, {p["yRate"]:d}, {p["yPhase"]:.3f}f,'
        f' SceneManager.DanceEase.{p["ease"]},'
        f' {p["physics"]:.2f}f,'
        f' {p["pitchPivot"]:.2f}f, {p["rollPivot"]:.2f}f,'
        f' {p["counterRollPivot"]:.2f}f, {p["counterRollGain"]:.2f}f,'
        f' {p["yawSharp"]:.2f}f, {p["yawCmplx"]:.2f}f,'
        f' {p["pitSharp"]:.2f}f, {p["pitCmplx"]:.2f}f,'
        f' {p["bobSharp"]:.2f}f, {p["bobCmplx"]:.2f}f),'
    )


def folder_mode(folder):
    """Extract every *.fbx in `folder`, print a DancePreset table block.

    Clip name is derived from filename: "Dancing Twerk (1).fbx" -> "TWERK (MIXAMO)".
    Re-runs the single-clip extractor per file and concatenates Kotlin literals.
    """
    folder = Path(folder)
    fbxs = sorted(folder.glob("*.fbx"))
    if not fbxs:
        print(f"No .fbx files in {folder}")
        return
    import re
    rows = []
    skipped = []
    seen_names = set()
    seen_values = {}  # Kotlin-literal-body -> first name that produced it
    for fbx in fbxs:
        stem = fbx.stem.strip()
        m = re.match(r'^(.*?)\s*\((\d+)\)$', stem)
        if m:
            base_name = f"{m.group(1).upper()} {m.group(2)}"
        else:
            base_name = stem.upper()
        # Compact verbose Mixamo names but preserve "Dancing" when it's the only word.
        compact = re.sub(r"\s+(DANCING|DANCE)\b", "", base_name).strip()
        compact = " ".join(compact.split())
        if not compact:  # e.g. "Dancing" or "Dancing (1)" -> keep the original
            compact = base_name
        name = f"{compact} MIXAMO"
        unique = name
        idx = 2
        while unique in seen_names:
            unique = f"{name} v{idx}"
            idx += 1
        seen_names.add(unique)
        try:
            preset = extract(str(fbx), unique, verbose=True)
            kotlin = _format_kotlin(preset)
            # Byte-identical dedupe: two downloads of the same FBX produce identical numbers.
            # Strip the name, compare the value tail only.
            value_tail = kotlin.split(",", 1)[1] if "," in kotlin else kotlin
            if value_tail in seen_values:
                print(f"[=] {fbx.name}: duplicate of {seen_values[value_tail]} - dropping")
                print()
                continue
            seen_values[value_tail] = unique
            rows.append(kotlin)
            print()
        except Exception as e:
            skipped.append((fbx.name, str(e)))
            print(f"[!] {fbx.name}: SKIPPED - {e}")
            print()
    if skipped:
        print("Skipped clips:")
        for fname, why in skipped:
            print(f"  {fname}: {why}")
    print("\n" + "=" * 70)
    print("Kotlin preset block (paste into dancePresets list):")
    print("=" * 70)
    for row in rows:
        print("        " + row)


if __name__ == "__main__":
    if len(sys.argv) > 1 and Path(sys.argv[1]).is_dir():
        folder_mode(sys.argv[1])
    else:
        fbx = sys.argv[1] if len(sys.argv) > 1 else r"C:\tmp\mixamo_inspect\twerk.fbx"
        name = sys.argv[2] if len(sys.argv) > 2 else "TWERK (MIXAMO)"
        extract(fbx, name)
