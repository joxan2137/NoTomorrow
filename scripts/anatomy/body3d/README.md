# 3D exercise demos and muscle view

Both apps show pre-rendered images and clips from these scripts, so no 3D engine ships in either app.

| Script | Writes |
| --- | --- |
| `render_demos.py` | `Demos/<clip>.mp4` + `<clip>.jpg` poster and `demos.json` (exercise id → clip) |
| `render_body.py` | `Body3D/front.png`, `back.png`, a glowing layer per muscle and view, label maps, `body3d.json` |

Both write into `NoTomorrow/Resources/` and `android/app/src/main/assets/` (lower-case folder names on Android).

## Setup

```sh
python3 -m pip install bpy imageio-ffmpeg pillow numpy   # bpy is Blender 5.x as a Python module
python3 render_body.py                                    # a few minutes on 4 CPU cores
python3 render_demos.py --sheet /tmp/sheet.png            # key poses of every clip, to check framing
python3 render_demos.py                                   # every clip, about an hour
python3 render_demos.py --only squat.glutes+quadriceps    # one clip
python3 solver.py                                         # checks the pose port against motion_engine.js
```

The first run downloads the body mesh bundle (about 50 MB) into `~/.cache/notomorrow-body3d` (or
`$BODY3D_CACHE`), rigs it and caches `rigged.blend` there.

## Asset

The body is `GEO-body_male_realistic` from Blender Studio's **Human Base Meshes** bundle v1.4.1,
released under **CC0 1.0** (public domain):
<https://www.blender.org/download/demo-files/#assets>. Nothing else is borrowed: the rig (`rig.py`),
equipment (`props3d.py`), muscle regions (`muscles3d.py`) and poses (`../motion_patterns.json`) are
the app's own.

## How it fits together

- `rig.py` builds a simple armature for the mesh (automatic weights) and caches the result.
- `solver.py` ports `../motion_engine.js`, so each pattern's two key poses solve to the same 2D joints;
  `scene3d.py` aims the bones at them and adds IK for bar grips.
- `muscles3d.py` describes each muscle as soft volumes on the rest-pose body (ellipsoids on the trunk,
  bands of limb capsules limited to skin that moves with that limb), written to a `hot` vertex
  attribute the shader turns ember.
- Clips are 31 eased frames played forward then back (60 frames at 24 fps), H.264 with no audio track.
- The muscle view's label maps store muscle `i` as grey `(i + 1) × labelStep` so the apps can round
  away any colour conversion when they read a tapped pixel.
