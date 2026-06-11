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
from bind_pose import (compute_corrections, euler_xyz_deg_to_matrices,
                       matrices_to_euler_xyz_deg, rotation_angle_deg)

# Tripo bind pose source for the per-bone axis correction (D9 Phase A).
# This is the PRODUCTION GLB the app loads — pulled from the headset's
# /sdcard/RIGGED/. The correction conjugates each Mixamo bone-local delta
# into the Tripo bone's local frame: D_tgt = C @ D_src @ C^T with
# C = W_tripoBind^-1 @ W_mixamoBind (world bind rotations, per bone).
TRIPO_BIND_GLB = r"C:\tmp\mixamo_inspect\ChloeVR_Bikini_FootPivot.glb"


# DancePreset runtime clamps (from FilamentModelActivity.kt:818-820).
YAW_DEG_MAX = 15.0
PITCH_DEG_MAX = 10.0
BOB_M_MAX = 0.04

# Rate snap grid — presets in the codebase use these values exclusively.
RATE_GRID = [1, 2, 4, 8, 16, 32]

# ── Phase 2 — per-joint articulation layer ──
# Bone retarget: Mixamo source naming → Tripo target rig naming.
# Philosophy: layer owns the upper body (where real Mixamo articulation lives);
# Tier 4 owns pelvis/waist/thigh/calf (hard-coded biomechanics).
# Mixamo has 3 spine bones (Spine, Spine1, Spine2); Tripo has 2 (Spine01, Spine02).
# We collapse Spine1→Spine01 and Spine2→Spine02 — mixamorig:Spine (just above
# pelvis) doesn't retarget cleanly and overlaps with Tier 4's Waist anyway.
# Neck is skipped for the same reason (no Tripo equivalent bone in the user's
# rig inspection; head motion still works via mixamorig:Head → Head).
JOINT_MAP = {
    "mixamorig:Spine1":      "Spine01",
    "mixamorig:Spine2":      "Spine02",
    "mixamorig:LeftShoulder":  "L_Clavicle",
    "mixamorig:RightShoulder": "R_Clavicle",
    "mixamorig:LeftArm":     "L_Upperarm",
    "mixamorig:RightArm":    "R_Upperarm",
    "mixamorig:LeftForeArm":  "L_Forearm",
    "mixamorig:RightForeArm": "R_Forearm",
    "mixamorig:Head":        "Head",
}

# Minimum amplitude (degrees) for a joint-axis drive to be emitted. Below this
# the motion is noise — emitting it just clutters the literal. Tuned by eye:
# on-device testing can revisit. 1.5° is roughly the threshold where a single
# joint rotation becomes visible on a 1m-tall character at typical VR distance.
MIN_JOINT_AMP_DEG = 1.5

# Minimum 2nd-harmonic amplitude (degrees) for the 2nd-harmonic term to be
# emitted in a JointDrive. Below this, the 2nd harmonic is noise and we set
# amp_2nd=0 so the runtime skips its sin() evaluation.
MIN_JOINT_H2_AMP_DEG = 0.6


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


def harmonic_amp_phase(signal, fps, f_fundamental, k):
    """Amplitude AND phase of the k-th harmonic at f_fundamental.

    Returns (amp_deg, phase_rad_cos). Same FFT convention as dominant_sine:
    signal ≈ amp * cos(2π * k*f * t + phase_rad_cos). Used for Phase 2
    JointDriveLayer 2nd-harmonic extraction.
    """
    N = len(signal)
    if N < 8 or f_fundamental <= 0:
        return 0.0, 0.0
    sig = signal - np.mean(signal)
    fft = np.fft.rfft(sig * np.hanning(N))
    freqs = np.fft.rfftfreq(N, d=1.0 / fps)
    target_f = k * f_fundamental
    idx = int(np.argmin(np.abs(freqs - target_f)))
    amp = float(np.abs(fft[idx]) * (2.0 / N) / 0.5)
    phase = float(np.angle(fft[idx]))
    return amp, phase


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


def phase_to_unit_sin(phase_rad):
    """FFT phase (radians, cos convention) → [0,1] cycle fraction for sin-based runtime.

    Phase 2 runtime (JointDriveLayer.evaluate) computes angle = sin(2π * ph1)
    rather than the stylized waveAt() used by Phase 1 rigid-body writes. Since
    sin(x) = cos(x - π/2), a cos-phase of φ corresponds to a sin-phase of
    φ + π/2 — i.e. the sin convention leads the cos convention by 0.25 cycle.

    Phase 1 used `phase_to_unit` unchanged, acknowledging the 0.25-cycle
    mismatch in NOTES.md as "best-guess alignment may be off". Phase 2 fixes
    it properly here because the runtime is pure sin.
    """
    return float((((phase_rad + math.pi / 2) / (2 * math.pi)) + 1.0) % 1.0)


def detrend(x):
    """Remove linear drift from a 1D series so centered oscillation remains."""
    n = len(x)
    if n < 4:
        return x
    t = np.arange(n)
    slope, intercept = np.polyfit(t, x, 1)
    return x - (slope * t + intercept)


def fit_axis_drive(vs, f_bob, fps, tgt_name, axis_idx):
    """Fit one decomposed angle track (degrees, unwrapped) into a JointDrive dict.

    Shared by the corrected (conjugated) and legacy paths. Returns None when
    both the oscillation and the rest offset are below MIN_JOINT_AMP_DEG.

    The rest offset is the time-averaged value of the unwrapped track —
    the Mixamo dancer's natural rest pose for this axis, now expressed in the
    TARGET bone's local frame. Wrapped into (-180, 180] because unwrap() can
    accumulate full turns on spin-through clips (ARMS HIP HOP / YMCA).
    """
    BPM_MIN, BPM_MAX = 60.0, 180.0
    if len(vs) < 8:
        return None
    rest_angle_deg = float(np.mean(vs))
    rest_angle_deg = ((rest_angle_deg + 180.0) % 360.0) - 180.0
    vd = detrend(vs)
    # Prefer a fundamental in the musical beat range; fall back to
    # unconstrained search so a slow spine undulation at 0.5 Hz still
    # emits a drive (rate will snap to 1 → one cycle per measure).
    f, a1, phi_cos = dominant_sine(vd, fps, min_freq=BPM_MIN / 60, max_freq=BPM_MAX / 60)
    if a1 < MIN_JOINT_AMP_DEG:
        f, a1, phi_cos = dominant_sine(vd, fps)
    if a1 < MIN_JOINT_AMP_DEG or f <= 0:
        # No oscillation above threshold — emit a rest-only drive if the rest
        # offset matters, else drop the axis entirely.
        if abs(rest_angle_deg) < MIN_JOINT_AMP_DEG:
            return None
        return {
            "jointName": tgt_name,
            "axis": axis_idx,
            "rate": 1,
            "phase_offset": 0.0,
            "amp_deg": 0.0,
            "amp_2nd_deg": 0.0,
            "phase_2nd_offset": 0.0,
            "rest_angle_deg": rest_angle_deg,
        }
    rate = snap_rate(4.0 * f / f_bob)
    phase_offset = phase_to_unit_sin(phi_cos)
    # 2nd harmonic — relative to the joint's own fundamental (at 2*f).
    a2, phi2_cos = harmonic_amp_phase(vd, fps, f, 2)
    if a2 < MIN_JOINT_H2_AMP_DEG:
        a2 = 0.0
        phi2 = 0.0
    else:
        phi2 = phase_to_unit_sin(phi2_cos)
    return {
        "jointName": tgt_name,
        "axis": axis_idx,
        "rate": rate,
        "phase_offset": phase_offset,
        "amp_deg": min(a1, 30.0),   # safety cap — any single joint past 30° reads as broken
        "amp_2nd_deg": a2,
        "phase_2nd_offset": phi2,
        "rest_angle_deg": rest_angle_deg,
    }


def corrected_bone_tracks(rot, correction, fps):
    """Conjugate a bone's sampled Lcl Rotation track into the Tripo local frame.

    rot: {"X": [(t, v), ...], ...} raw FBX curve samples (degrees).
    correction: (C, rest_inv) from bind_pose.compute_corrections.

    Per 60fps sample: D_src = Rz·Ry·Rx of the curve Eulers (bind-relative on
    Mixamo exports — Lcl Rotation defaults are absent, verified);
    D_tgt = C @ D_src @ C^T; decompose back to euler-xyz in the runtime's
    Rz·Ry·Rx convention; unwrap each output track.

    Returns {"X": ndarray, "Y": ndarray, "Z": ndarray} or None if too short.
    """
    c_mat, rest_inv = correction
    series = {ax: rot.get(ax) for ax in ("X", "Y", "Z")}
    present = [s for s in series.values() if s]
    if not present:
        return None
    t0 = min(s[0][0] for s in present)
    t1 = max(s[-1][0] for s in present)
    n = int(round((t1 - t0) * fps)) + 1
    if n < 8:
        return None
    ts = np.linspace(t0, t1, n)
    tracks = {}
    for ax, s in series.items():
        if s:
            tt = np.array([p[0] for p in s], dtype=np.float64)
            vv = np.array([p[1] for p in s], dtype=np.float64)
            vv = np.degrees(np.unwrap(np.radians(vv)))
            tracks[ax] = np.interp(ts, tt, vv)
        else:
            tracks[ax] = np.zeros(n)
    d_src = euler_xyz_deg_to_matrices(tracks["X"], tracks["Y"], tracks["Z"])
    if not np.allclose(rest_inv, np.eye(3), atol=1e-9):
        # Nonzero Lcl Rotation default: rebase curves to bind-relative first.
        d_src = np.einsum('ij,njk->nik', rest_inv, d_src)
    d_tgt = np.einsum('ij,njk,lk->nil', c_mat, d_src, c_mat)
    xs, ys, zs = matrices_to_euler_xyz_deg(d_tgt)
    return {
        "X": np.degrees(np.unwrap(np.radians(xs))),
        "Y": np.degrees(np.unwrap(np.radians(ys))),
        "Z": np.degrees(np.unwrap(np.radians(zs))),
    }


def extract_joint_drives(anim, f_bob, fps, corrections=None, verbose=False):
    """Extract per-joint articulation drives for the Phase 2 JointDriveLayer.

    Walks every bone in JOINT_MAP. With a correction table (D9 Phase A), the
    bone's full rotation track is conjugated into the Tripo bone-local frame
    BEFORE per-axis Fourier fitting — this is what moves Mixamo elbow flex
    (forearm Z) onto the Tripo flex axis (forearm X) instead of gull-winging.
    Without a correction entry the legacy uncorrected per-axis path runs
    (loud warning — those drives are in the wrong frame on Tripo rigs).

    Returns a list of dicts ready for Kotlin emission:
        [{jointName, axis, rate, phase_offset, amp_deg, amp_2nd_deg,
          phase_2nd_offset, rest_angle_deg}, ...]
    """
    if f_bob <= 0:
        return []
    drives = []
    for src_name, tgt_name in JOINT_MAP.items():
        bone = anim.get(src_name)
        if not bone:
            if verbose:
                print(f"  {src_name}: not in clip, skipping")
            continue
        rot = bone.get("Lcl Rotation", {})
        if not rot:
            continue
        correction = (corrections or {}).get(src_name)
        if correction is not None:
            tracks = corrected_bone_tracks(rot, correction, fps)
            if tracks is None:
                continue
            for axis_name, axis_idx in (("X", 0), ("Y", 1), ("Z", 2)):
                d = fit_axis_drive(tracks[axis_name], f_bob, fps, tgt_name, axis_idx)
                if d is not None:
                    drives.append(d)
        else:
            print(f"  [!] {src_name}: NO axis correction — emitting in Mixamo "
                  f"frame (WRONG on Tripo rigs)")
            for axis_name, axis_idx in (("X", 0), ("Y", 1), ("Z", 2)):
                raw = rot.get(axis_name)
                if not raw:
                    continue
                vs, _ = resample_uniform(raw, fps, unwrap_degrees=True)
                d = fit_axis_drive(vs, f_bob, fps, tgt_name, axis_idx)
                if d is not None:
                    drives.append(d)
    return drives


def extract(fbx_path, clip_name, verbose=True, corrections=None):
    want = {"mixamorig:Hips", "mixamorig:Spine2"} | set(JOINT_MAP.keys())
    anim, _, _ = extract_animation(fbx_path, bone_filter=want)
    hips = anim.get("mixamorig:Hips")
    if not hips:
        raise RuntimeError("mixamorig:Hips animation not found")

    # D9 Phase A: per-bone axis-correction table. Computed from THIS clip's
    # own skeleton (Mixamo rigs are identical across clips, but per-clip is
    # self-consistent and costs ~1s) against the production Tripo GLB.
    if corrections is None:
        if Path(TRIPO_BIND_GLB).exists():
            corrections = compute_corrections(TRIPO_BIND_GLB, fbx_path, JOINT_MAP)
        else:
            print(f"[!] TRIPO_BIND_GLB missing ({TRIPO_BIND_GLB}) — drives will "
                  f"be emitted UNCORRECTED in the Mixamo frame")
            corrections = {}

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

    # Phase 2 — per-joint articulation drives. Upper body joints only; Tier 4
    # keeps authority over pelvis/waist/thigh/calf. Empty list = pure rigid-body
    # preset (current Phase 1 behaviour). For clips where we can't resolve
    # any upper-body joints, this naturally degrades to Phase 1.
    joint_drives = extract_joint_drives(anim, f_bob, fps, corrections=corrections,
                                        verbose=verbose)

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
        "jointDrives": joint_drives,
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
        if joint_drives:
            axis_c = ("X", "Y", "Z")
            print(f"Phase 2 joint drives ({len(joint_drives)}):")
            for d in joint_drives:
                a2 = f" +H2 {d['amp_2nd_deg']:.1f}deg@{d['phase_2nd_offset']:.2f}" if d["amp_2nd_deg"] > 0 else ""
                rest = f" rest={d['rest_angle_deg']:+6.1f}deg"
                print(f"  {d['jointName']:>13s}.{axis_c[d['axis']]}  rate={d['rate']:2d} "
                      f"amp={d['amp_deg']:5.1f}deg phase={d['phase_offset']:.2f}{a2}{rest}")
        else:
            print("Phase 2 joint drives: NONE above threshold "
                  "(either a Hips-only clip or noise-only upper body).")
        print()
        print("--- DancePreset Kotlin literal ---")
        print(_format_kotlin(preset))

    return preset


def _format_kotlin(p):
    head = (
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
        f' {p["bobSharp"]:.2f}f, {p["bobCmplx"]:.2f}f'
    )
    drives = p.get("jointDrives") or []
    if not drives:
        return head + "),"
    # Multi-line layer literal — positional DancePreset arg #22.
    axis_c = ("X", "Y", "Z")
    lines = [head + ","]
    lines.append("            JointDriveLayer(arrayOf(")
    for d in drives:
        lines.append(
            f'                JointDrive("{d["jointName"]}", JointDriveLayer.AXIS_{axis_c[d["axis"]]},'
            f' {d["rate"]:d}, {d["phase_offset"]:.3f}f, {d["amp_deg"]:.2f}f,'
            f' {d["amp_2nd_deg"]:.2f}f, {d["phase_2nd_offset"]:.3f}f,'
            f' {d["rest_angle_deg"]:.2f}f),'
        )
    lines.append("            ))),")
    return "\n".join(lines)


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
