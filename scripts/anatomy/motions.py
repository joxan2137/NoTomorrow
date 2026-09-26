#!/usr/bin/env python3
"""Generates `motions.json`: the animated form demos for exercises that have no free-exercise-db photos.

A demo is a movement pattern ("squat", "seated_row") from `motion_patterns.json`: two key poses of an original
mannequin plus the equipment around it. The apps loop between the poses and colour the limbs that the exercise's
primary muscles move. `motion_engine.js` is the reference implementation of the pose maths both apps port.

    python3 scripts/anatomy/motions.py                 # writes both apps' motions.json
    python3 scripts/anatomy/motions.py --preview out.html   # every pattern at both poses, for a browser

Output: {"box": [x, y, w, h], "patterns": {name: pattern}, "exercises": {exercise id: pattern name}}.

Pose fields (angles in degrees; limbs 0 = hanging down, +90 = pointing forward, the figure faces +x; torso 0 = upright,
+90 = leaning fully forward):
  anchor   "foot" (near ankle at `at`), "knee", "hand" (near wrist at `at`) or "hip"
  torso, neck, lift (shoulders raised, units), thigh, shin, foot, upper, fore: a number, or [near, far]
  farFoot  [x, y]: the far foot stays planted there (two-bone IK) instead of following `thigh`/`shin`
A pattern may set "view": "front", where limb angles open away from the midline and the far side mirrors the near.
"""
import argparse
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
TARGETS = [ROOT / "NoTomorrow/Resources/motions.json", ROOT / "android/app/src/main/assets/motions.json"]
BOX = [-30, -20, 260, 220]

# Exercise id -> pattern, for every bundled exercise without photos. Anything not listed shows no demo.
EXERCISES = {
    "nt_bulgarian_split_squat": "split_squat_elevated",
    "nt_dumbbell_hip_thrust": "hip_thrust",
    "nt_single_leg_hip_thrust": "hip_thrust",
    "nt_b_stance_romanian_deadlift": "rdl",
    "nt_belt_squat": "machine_squat",
    "nt_pendulum_squat": "machine_squat",
    "nt_assisted_nordic_curl": "nordic",
    "nt_reverse_nordic_curl": "reverse_nordic",
    "nt_seated_dumbbell_calf_raise": "seated_calf_raise",
    "nt_tibialis_raise": "tibialis_raise",
    "nt_cable_glute_kickback": "glute_kickback",
    "nt_standing_cable_hip_abduction": "hip_abduction",
    "nt_suitcase_carry": "carry",
    "nt_half_kneeling_landmine_press": "landmine_press",
    "nt_single_arm_landmine_row": "bent_row",
    "nt_chest_supported_dumbbell_row": "supported_row",
    "nt_cable_y_raise": "y_raise",
    "nt_bayesian_cable_curl": "curl",
    "nt_cable_crunch_kneeling": "cable_crunch",
    "nt_incline_push_up_on_bench": "push_up",
    "nt_hs_iso_lateral_bench_press": "bench_press",
    "nt_hs_iso_lateral_horizontal_bench_press": "bench_press",
    "nt_hs_iso_lateral_incline_press": "incline_press",
    "nt_hs_iso_lateral_super_incline_press": "incline_press",
    "nt_hs_iso_lateral_decline_press": "chest_press_seated",
    "nt_hs_iso_lateral_wide_chest": "chest_press_seated",
    "nt_hs_iso_lateral_chest_press_chest_back": "chest_press_seated",
    "nt_hs_iso_lateral_row_chest_back": "seated_row",
    "nt_hs_iso_lateral_shoulder_press": "shoulder_press_seated",
    "nt_hs_behind_the_neck_press": "shoulder_press_seated",
    "nt_hs_super_fly": "fly",
    "nt_hs_lateral_raise": "lateral_raise",
    "nt_hs_lying_rear_delt_fly": "rear_delt_fly",
    "nt_hs_seated_dip": "dip",
    "nt_hs_iso_lateral_row": "seated_row",
    "nt_hs_iso_lateral_high_row": "seated_row",
    "nt_hs_iso_lateral_low_row": "seated_row",
    "nt_hs_iso_lateral_dy_row": "seated_row",
    "nt_hs_iso_lateral_t_bar_row": "bent_row",
    "nt_hs_iso_lateral_front_lat_pulldown": "pulldown",
    "nt_hs_iso_lateral_wide_pulldown": "pulldown",
    "nt_hs_iso_lateral_behind_neck_pulldown": "pulldown",
    "nt_hs_pullover": "pullover",
    "nt_hs_shrug": "shrug",
    "nt_hs_seated_biceps": "curl",
    "nt_hs_ab_oblique_crunch": "crunch",
    "nt_hs_iso_lateral_leg_press": "leg_press",
    "nt_hs_linear_leg_press": "leg_press",
    "nt_hs_super_squat_press": "machine_squat",
    "nt_hs_linear_hack_squat": "machine_squat",
    "nt_hs_v_squat": "machine_squat",
    "nt_hs_reverse_v_squat": "machine_squat",
    "nt_hs_belt_squat": "machine_squat",
    "nt_hs_pendulum_x_squat": "machine_squat",
    "nt_hs_iso_lateral_leg_extension": "leg_extension",
    "nt_hs_iso_lateral_leg_curl": "leg_curl_lying",
    "nt_hs_iso_lateral_kneeling_leg_curl": "leg_curl_lying",
    "nt_hs_seated_leg_curl": "leg_curl_seated",
    "nt_hs_seated_calf_raise": "seated_calf_raise",
    "nt_hs_super_horizontal_calf": "calf_raise",
    "nt_hs_tibia_dorsi_flexion": "tibialis_raise",
    "nt_hs_glute_drive": "hip_thrust",
    "nt_hs_glute_ham_raise": "nordic",
    "nt_hs_gb_jammer": "landmine_press",
    "nt_hs_gb_multi_squat": "machine_squat",
    "nt_hs_gb_squat_lunge": "lunge",
    "nt_hs_gb_incline_press": "incline_press",
    "nt_hs_gb_high_row": "seated_row",
    "nt_hs_gb_combo_decline": "chest_press_seated",
    "nt_hs_gb_deadlift": "deadlift",
    "nt_hs_mts_chest_press": "chest_press_seated",
    "nt_hs_mts_incline_press": "incline_press",
    "nt_hs_mts_decline_press": "chest_press_seated",
    "nt_hs_mts_shoulder_press": "shoulder_press_seated",
    "nt_hs_mts_front_pulldown": "pulldown",
    "nt_hs_mts_row": "seated_row",
    "nt_hs_mts_high_row": "seated_row",
    "nt_hs_mts_biceps_curl": "curl",
    "nt_hs_mts_triceps_extension": "pushdown",
    "nt_hs_mts_leg_extension": "leg_extension",
    "nt_hs_mts_kneeling_leg_curl": "leg_curl_lying",
    "nt_hs_mts_ab_crunch": "crunch",
    "nt_hs_select_chest_press": "chest_press_seated",
    "nt_hs_select_pec_fly": "fly",
    "nt_hs_select_rear_delt": "rear_delt_fly",
    "nt_hs_select_shoulder_press": "shoulder_press_seated",
    "nt_hs_select_lateral_raise": "lateral_raise",
    "nt_hs_select_lat_pulldown": "pulldown",
    "nt_hs_select_seated_row": "seated_row",
    "nt_hs_select_biceps_curl": "curl",
    "nt_hs_select_triceps_extension": "pushdown",
    "nt_hs_select_assisted_dip": "dip",
    "nt_hs_select_assisted_chin_up": "pull_up",
    "nt_hs_select_ab_crunch": "crunch",
    "nt_hs_select_back_extension": "back_extension",
    "nt_hs_reverse_hyper": "reverse_hyper",
    "nt_hs_select_leg_extension": "leg_extension",
    "nt_hs_select_leg_curl": "leg_curl_lying",
    "nt_hs_select_seated_leg_curl": "leg_curl_seated",
    "nt_hs_select_seated_leg_press": "leg_press",
    "nt_hs_select_standing_calf": "calf_raise",
    "nt_hs_select_horizontal_calf": "calf_raise",
    "nt_hs_select_hip_abduction": "hip_abduction",
    "nt_hs_select_hip_adduction": "hip_adduction",
    "nt_hs_select_hip_glute": "glute_kickback",
    "Kettlebell_Overhead_Triceps_Extension": "overhead_press",
}


def build():
    patterns = json.loads((HERE / "motion_patterns.json").read_text())
    ids = {r["id"] for r in json.loads((ROOT / "NoTomorrow/Resources/exercises.json").read_text())}
    for exercise, pattern in EXERCISES.items():
        assert exercise in ids, exercise
        assert pattern in patterns, pattern
    used = set(EXERCISES.values())
    return {"box": BOX, "patterns": {k: v for k, v in sorted(patterns.items()) if k in used}, "exercises": dict(sorted(EXERCISES.items()))}


def preview(path):
    motions = build()
    script = f"""
const {{ render }} = require({json.dumps(str(HERE / 'motion_engine.js'))});
const m = {json.dumps(motions)};
let out = '<html><body style="margin:0;background:#1C1C1E;font:11px sans-serif;color:#888;display:grid;grid-template-columns:repeat(6,262px)">';
for (const k in m.patterns) for (const t of [0, 1])
  out += `<div><svg width="260" height="220" viewBox="${{m.box.join(' ')}}">${{render(m.patterns[k], t, [])}}</svg><div>${{k}} ${{t}}</div></div>`;
require('fs').writeFileSync({json.dumps(str(path))}, out + '</body></html>');
"""
    subprocess.run(["node", "-e", script], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview")
    args = parser.parse_args()
    if args.preview:
        preview(pathlib.Path(args.preview).resolve())
        return
    text = json.dumps(build(), separators=(",", ":")) + "\n"
    for target in TARGETS:
        target.write_text(text)
        print(f"wrote {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
