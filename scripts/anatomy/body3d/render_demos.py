#!/usr/bin/env python3
"""Renders the 3D form demos: one looping clip per (movement pattern, primary muscles) used by an exercise without
photos. Needs Blender as a Python module (`pip install bpy`, 5.x) and ffmpeg (`pip install imageio-ffmpeg`).

  python3 render_demos.py --sheet out.png      # key poses of every clip on one contact sheet, to check framing
  python3 render_demos.py [--only name,...]    # render clips into both apps (MP4 + poster JPEG) and demos.json

The body is posed from the movement keyframes (../motion_patterns.json via solver.py; ../motions.py says which
exercise uses which), then the primary muscles glow on it (muscles3d.py). Clips ease from the start pose to the end pose,
hold, ease back and hold, in the studio look from look.py.
"""
import argparse, json, math, os, pathlib, shutil, subprocess, sys, tempfile
HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
ROOT = HERE.parents[2]
import bpy
from mathutils import Vector
from bpy_extras.object_utils import world_to_camera_view
import rig as R, scene3d, solver, muscles3d, props3d, look
sys.path.insert(0, str(HERE.parent))
from motions import build as build_motions

TARGETS = [ROOT / 'NoTomorrow/Resources/Demos', ROOT / 'android/app/src/main/assets/demos']
WIDTH, HEIGHT = 840, 560          # 3:2, the demo card's aspect
HALF_FRAMES = 24                  # 1 s at 24 fps from the start pose to the end pose
HOLD = 6                          # frames held at each end; the clip goes there, holds, comes back and holds
# Camera (yaw, pitch) per pattern; yaw 0 looks at the left side, 90 at the front, 270 at the back.
CAM = {'bench_press': (44, 22), 'incline_press': (46, 18), 'hip_thrust': (44, 16), 'push_up': (42, 14),
       'leg_curl_lying': (48, 22), 'crunch': (48, 18), 'reverse_hyper': (50, 14),
       'reverse_nordic': (44, 10),
       # back-of-body work, seen from behind at 3/4
       'nordic': (318, 10), 'pull_up': (310, 4), 'pulldown': (310, 6), 'shrug': (300, 8), 'deadlift': (320, 9),
       'rdl': (320, 9), 'glute_kickback': (320, 8), 'supported_row': (310, 12), 'bent_row': (40, 9),
       'seated_row': (320, 10), 'back_extension': (320, 14)}


def clips():
    motions = build_motions()
    library = {e['id']: e for e in json.loads((ROOT / 'android/app/src/main/assets/exercises.json').read_text())}
    patterns = json.loads((HERE.parent / 'motion_patterns.json').read_text())
    by_clip, exercise_clip = {}, {}
    for ex, pattern in sorted(motions['exercises'].items()):
        muscles = sorted(library[ex]['primaryMuscles'])
        name = pattern + '.' + '+'.join(m.replace(' ', '_') for m in muscles)
        by_clip[name] = (pattern, muscles, patterns[pattern]); exercise_clip[ex] = name
    return by_clip, exercise_clip


def place(pattern, rig, view):
    pose_at(pattern, rig, view, 0.0)


def pose_at(pattern, rig, view, t):
    j = scene3d.pose(rig, solver.mix(pattern['frames'][0], pattern['frames'][1], t), view)
    look.fix_arms(rig, pattern.get('supinate', False))
    props3d.build(pattern, rig, view, j)


def fit_camera(cam, pattern, name, rig, body, view):
    """Frames every pose of the motion, body and equipment, at ~84% of the picture."""
    pts = []
    for t in (0, 0.5, 1):
        pose_at(pattern, rig, view, t)
        dg = bpy.context.evaluated_depsgraph_get()
        for o in [body] + [o for o in bpy.data.objects if o.get('prop')]:
            e = o.evaluated_get(dg); me = e.to_mesh()
            pts += [e.matrix_world @ v.co for v in list(me.vertices)[::9]]; e.to_mesh_clear()
    yaw, pitch = CAM.get(name.split('.')[0], (50, 9) if view != 'front' else (72, 6))
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), max(0, min(p.z for p in pts))))
    hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    c, dist = (lo + hi) / 2, 3.0
    scn = bpy.context.scene; aspect = WIDTH / HEIGHT
    for _ in range(12):
        scene3d.frame(cam, c, dist, yaw, pitch); bpy.context.view_layer.update()
        uv = [world_to_camera_view(scn, cam, p) for p in pts]
        u0, u1 = min(q.x for q in uv), max(q.x for q in uv); v0, v1 = min(q.y for q in uv), max(q.y for q in uv)
        right = cam.matrix_world.to_3x3() @ Vector((1, 0, 0)); upv = cam.matrix_world.to_3x3() @ Vector((0, 1, 0))
        span = 2 * dist * math.tan(math.atan(18 / cam.data.lens))
        c = c + right * ((u0 + u1) / 2 - 0.5) * span + upv * ((v0 + v1) / 2 - 0.5) * span / aspect
        dist *= max((u1 - u0) / 0.9, (v1 - v0) / 0.88)
    look.face(yaw)


def ffmpeg():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--sheet'); ap.add_argument('--only'); ap.add_argument('--scale', type=float, default=1.0)
    args = ap.parse_args(sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else sys.argv[1:])
    by_clip, exercise_clip = clips()
    names = sorted(by_clip) if not args.only else args.only.split(',')
    body, rig = R.load()
    cam = scene3d.setup(int(WIDTH * args.scale), int(HEIGHT * args.scale)); cam.data.sensor_fit = 'HORIZONTAL'
    scn = bpy.context.scene
    # the denoiser hides the difference from 40 samples and 4 bounces at about half the time per frame
    scn.cycles.samples = 20; scn.cycles.adaptive_threshold = 0.04; scn.cycles.max_bounces = 3
    scn.render.use_persistent_data = True
    look.cloth_attr(body); look.studio(scn, body); look.backdrop(scn)
    vals = muscles3d.values(body)
    scn = bpy.context.scene
    tmp = pathlib.Path(tempfile.mkdtemp())
    sheet = []
    for name in names:
        pattern_name, muscles, pattern = by_clip[name]
        view = pattern.get('view', 'side')
        muscles3d.write_attr(body, 'hot', muscles3d.mask(vals, set(muscles)))
        rig.location = (0, 0, 0)
        fit_camera(cam, pattern, name, rig, body, view)
        if args.sheet:
            for t in (0.0, 1.0):
                pose_at(pattern, rig, view, t)
                scn.render.filepath = str(tmp / f'{name}_{t}.png'); bpy.ops.render.render(write_still=True)
                sheet.append((name, tmp / f'{name}_{t}.png'))
            continue
        frames = tmp / name; frames.mkdir()
        for i in range(HALF_FRAMES):
            t = (1 - math.cos(math.pi * i / (HALF_FRAMES - 1))) / 2
            pose_at(pattern, rig, view, t)
            scn.render.filepath = str(frames / f'{i:03d}.png'); bpy.ops.render.render(write_still=True)
        seq = list(range(HALF_FRAMES)) + [HALF_FRAMES - 1] * HOLD + list(range(HALF_FRAMES - 2, -1, -1)) + [0] * (HOLD + 1)
        for k, i in enumerate(seq): shutil.copy(frames / f'{i:03d}.png', frames / f'loop_{k:03d}.png')
        for target in TARGETS: target.mkdir(parents=True, exist_ok=True)
        out = TARGETS[0] / f'{name}.mp4'
        subprocess.run([ffmpeg(), '-y', '-loglevel', 'error', '-framerate', '24', '-i', str(frames / 'loop_%03d.png'),
                        '-c:v', 'libx264', '-profile:v', 'main', '-pix_fmt', 'yuv420p', '-crf', '26', '-preset', 'veryslow',
                        '-tune', 'animation', '-movflags', '+faststart', '-an', str(out)], check=True)
        subprocess.run([ffmpeg(), '-y', '-loglevel', 'error', '-i', str(frames / f'{HALF_FRAMES - 1:03d}.png'),
                        '-q:v', '4', str(TARGETS[0] / f'{name}.jpg')], check=True)
        for target in TARGETS[1:]:
            for ext in ('mp4', 'jpg'): shutil.copy(TARGETS[0] / f'{name}.{ext}', target / f'{name}.{ext}')
        print('clip', name, (out.stat().st_size + 511) // 1024, 'KB', flush=True)
    if args.sheet:
        from PIL import Image, ImageDraw
        ims = [(n, Image.open(p)) for n, p in sheet]; w, h = ims[0][1].size; cols = 4
        img = Image.new('RGB', (w * cols, h * ((len(ims) + cols - 1) // cols)))
        for k, (n, im) in enumerate(ims):
            img.paste(im, ((k % cols) * w, (k // cols) * h)); ImageDraw.Draw(img).text(((k % cols) * w + 6, (k // cols) * h + 4), n, fill=(230, 230, 230))
        img.save(args.sheet); return
    if not args.only:
        text = json.dumps({'clips': exercise_clip}, indent=1, sort_keys=True) + '\n'
        for target in TARGETS: (target / 'demos.json').write_text(text)


if __name__ == '__main__':
    main()
