#!/usr/bin/env python3
"""Renders the 3D muscles-worked body: a neutral front and back view, plus one glowing layer per muscle and view
(cropped to the muscle, with its offset in body3d.json) and a label map per view for tap hit-testing (muscle i drawn as grey (i + 1) × labelStep).
Needs Blender as a Python module (`pip install bpy`, 5.x) and Pillow.

  python3 render_body.py

The apps draw the neutral view, then the layers of the muscles to show (primary at full strength, secondary or
lighter weeks fainter). Layer pixels are premultiplied renders of that muscle glowing, so they stack cleanly.
"""
import json, math, pathlib, sys, tempfile
HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
ROOT = HERE.parents[2]
import bpy, mathutils
from PIL import Image
import numpy as np
import rig as R, scene3d, muscles3d

TARGETS = [ROOT / 'NoTomorrow/Resources/Body3D', ROOT / 'android/app/src/main/assets/body3d']
WIDTH, HEIGHT = 520, 1000
VIEWS = (('front', 90), ('back', 270))
# Label maps store muscle i as (i + 1) * LABEL_STEP, so a reader rounding to the nearest step survives any
# colour conversion the platform's PNG decoder applies to a grey image.
LABEL_STEP = 12


def main():
    body, rig = R.load()
    cam = scene3d.setup(WIDTH, HEIGHT); cam.data.lens = 85; cam.data.sensor_fit = 'VERTICAL'
    scn = bpy.context.scene; scn.render.film_transparent = True
    bpy.data.objects['floor'].hide_render = True
    nt = body.data.materials[0].node_tree; bsdf = nt.nodes['Principled BSDF']
    attr = nt.nodes.new('ShaderNodeAttribute'); attr.attribute_name = 'hot'
    mix = nt.nodes.new('ShaderNodeMix'); mix.data_type = 'RGBA'
    mix.inputs[6].default_value = (0.30, 0.30, 0.32, 1); mix.inputs[7].default_value = (1.0, 0.16, 0.02, 1)
    nt.links.new(attr.outputs['Fac'], mix.inputs[0]); nt.links.new(mix.outputs[2], bsdf.inputs['Base Color'])
    nt.links.new(mix.outputs[2], bsdf.inputs['Emission Color'])
    mul = nt.nodes.new('ShaderNodeMath'); mul.operation = 'MULTIPLY'; mul.inputs[1].default_value = 0.12
    nt.links.new(attr.outputs['Fac'], mul.inputs[0]); nt.links.new(mul.outputs[0], bsdf.inputs['Emission Strength'])
    # A second material that only shows the mask, for the layers' alpha and the label map.
    mask_mat = bpy.data.materials.new('mask'); mask_mat.use_nodes = True
    mt = mask_mat.node_tree; mt.nodes.clear()
    ma = mt.nodes.new('ShaderNodeAttribute'); ma.attribute_name = 'hot'
    em = mt.nodes.new('ShaderNodeEmission'); out = mt.nodes.new('ShaderNodeOutputMaterial')
    mt.links.new(ma.outputs['Fac'], em.inputs['Strength']); mt.links.new(em.outputs[0], out.inputs[0])
    skin = body.data.materials[0]
    bpy.context.view_layer.update()
    lights = [o for o in bpy.data.objects if o.type == 'LIGHT']; home = {o.name: o.matrix_world.copy() for o in lights}
    muscles3d.EDGE, muscles3d.RAMP = 0.55, 0.17
    vals = muscles3d.values(body)
    for t in TARGETS: t.mkdir(parents=True, exist_ok=True)
    tmp = pathlib.Path(tempfile.mkdtemp())
    meta = {'size': [WIDTH, HEIGHT], 'labelStep': LABEL_STEP, 'muscles': muscles3d.MUSCLES, 'layers': {}}

    def render(path, material, hot):
        muscles3d.write_attr(body, 'hot', hot)
        body.data.materials[0] = material
        if material is mask_mat:
            scn.view_settings.view_transform = 'Standard'; scn.view_settings.look = 'None'; scn.cycles.use_denoising = False
        else:
            scn.view_settings.view_transform = 'AgX'; scn.view_settings.look = 'AgX - Medium High Contrast'; scn.cycles.use_denoising = True
        scn.render.filepath = str(path); bpy.ops.render.render(write_still=True)
        return np.asarray(Image.open(path).convert('RGBA')).astype(np.float32) / 255

    for view, yaw in VIEWS:
        turn = mathutils.Matrix.Rotation(math.radians(yaw - 90), 4, 'Z')
        for o in lights: o.matrix_world = turn @ home[o.name]
        scene3d.frame(cam, (0, 0, 0.9), 6.6, yaw, 2)
        base = render(tmp / f'{view}.png', skin, [0.0] * len(vals))
        Image.fromarray((base * 255).round().astype(np.uint8)).save(TARGETS[0] / f'{view}.png', optimize=True)
        labels = np.zeros((HEIGHT, WIDTH), np.uint8); strength = np.zeros((HEIGHT, WIDTH), np.float32)
        for i, m in enumerate(muscles3d.MUSCLES):
            hot = muscles3d.mask(vals, {m})
            lit = render(tmp / f'{view}-{m}.png', skin, hot)
            k = render(tmp / f'{view}-{m}-mask.png', mask_mat, hot)[..., 0] * base[..., 3]
            better = k > np.maximum(strength, 0.35); labels[better] = (i + 1) * LABEL_STEP; strength = np.maximum(strength, k)
            ys, xs = np.nonzero(k > 0.02)
            if len(xs) == 0: continue
            x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
            layer = np.dstack([lit[..., :3], k])[y0:y1, x0:x1]
            Image.fromarray((layer * 255).round().astype(np.uint8)).save(TARGETS[0] / f'{view}-{m.replace(" ", "_")}.png', optimize=True)
            meta['layers'].setdefault(m, {})[view] = [int(x0), int(y0), int(x1 - x0), int(y1 - y0)]
            print(view, m, flush=True)
        Image.fromarray(labels).save(TARGETS[0] / f'{view}-labels.png', optimize=True)
    (TARGETS[0] / 'body3d.json').write_text(json.dumps(meta, indent=1) + '\n')
    for f in TARGETS[0].iterdir():
        (TARGETS[1] / f.name).write_bytes(f.read_bytes())


if __name__ == '__main__':
    main()
