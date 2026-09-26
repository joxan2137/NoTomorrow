"""Builds a rigged, posable copy of Blender Studio's realistic male base mesh ("Human Base Meshes" v1.4.1, CC0,
https://www.blender.org/download/demo-files/). The bundle is downloaded into ~/.cache/notomorrow-body3d on first use;
nothing from it is committed except the renders."""
import bpy, mathutils, math, os, pathlib, urllib.request, zipfile
from mathutils import Vector
BUNDLE_URL = 'https://download.blender.org/demo/asset-bundles/human-base-meshes/human-base-meshes-bundle-v1.4.1.zip'
CACHE = pathlib.Path(os.environ.get('BODY3D_CACHE', pathlib.Path.home() / '.cache/notomorrow-body3d'))
BUNDLE = str(CACHE / 'human-base-meshes-bundle-v1.4.1/human_base_meshes_bundle.blend')
RIGGED = str(CACHE / 'rigged.blend')


def ensure_bundle():
    if os.path.exists(BUNDLE): return
    CACHE.mkdir(parents=True, exist_ok=True)
    archive = CACHE / 'bundle.zip'
    urllib.request.urlretrieve(BUNDLE_URL, archive)
    zipfile.ZipFile(archive).extractall(CACHE)
CX = -2.2643

# Rest joints (metres, body centred on x=0, front = -y, up = z). Left side has x < 0.
J = {
 'root': (0, 0.0, 0.93), 'spine1': (0, 0.005, 1.06), 'chest': (0, 0.0, 1.22), 'neck': (0, 0.0, 1.43),
 'head': (0, -0.02, 1.53), 'headtop': (0, -0.02, 1.70),
}
for s, m in (('L', -1), ('R', 1)):
    J.update({
     f'hip.{s}': (m*0.09, 0.0, 0.90), f'knee.{s}': (m*0.12, -0.005, 0.49), f'ankle.{s}': (m*0.15, 0.035, 0.085),
     f'toe.{s}': (m*0.17, -0.12, 0.02),
     f'clav.{s}': (m*0.02, -0.02, 1.40), f'shoulder.{s}': (m*0.175, 0.0, 1.385), f'elbow.{s}': (m*0.31, 0.0, 1.075),
     f'wrist.{s}': (m*0.378, -0.075, 0.865), f'knuckle.{s}': (m*0.405, -0.10, 0.795), f'tip.{s}': (m*0.43, -0.115, 0.712),
     f'thumb0.{s}': (m*0.372, -0.105, 0.848), f'thumb1.{s}': (m*0.376, -0.178, 0.80),
    })
BONES = [  # name, head, tail, parent
 ('pelvis', 'root', 'spine1', None), ('spine', 'spine1', 'chest', 'pelvis'), ('chest', 'chest', 'neck', 'spine'),
 ('neck', 'neck', 'head', 'chest'), ('head', 'head', 'headtop', 'neck'),
]
for s in 'LR':
    BONES += [
     (f'thigh.{s}', f'hip.{s}', f'knee.{s}', 'pelvis'), (f'shin.{s}', f'knee.{s}', f'ankle.{s}', f'thigh.{s}'),
     (f'foot.{s}', f'ankle.{s}', f'toe.{s}', f'shin.{s}'),
     (f'clavicle.{s}', f'clav.{s}', f'shoulder.{s}', 'chest'), (f'upper.{s}', f'shoulder.{s}', f'elbow.{s}', f'clavicle.{s}'),
     (f'fore.{s}', f'elbow.{s}', f'wrist.{s}', f'upper.{s}'), (f'hand.{s}', f'wrist.{s}', f'knuckle.{s}', f'fore.{s}'),
     (f'fingers.{s}', f'knuckle.{s}', f'tip.{s}', f'hand.{s}'), (f'thumb.{s}', f'thumb0.{s}', f'thumb1.{s}', f'hand.{s}'),
    ]

def build(subdiv=1):
    ensure_bundle()
    bpy.ops.wm.open_mainfile(filepath=BUNDLE)
    body = bpy.data.objects['GEO-body_male_realistic']
    keep = {body.name, body.name + '.eye.L', body.name + '.eye.R'}
    for o in list(bpy.data.objects):
        if o.name not in keep: bpy.data.objects.remove(o, do_unlink=True)
    for c in list(bpy.data.collections):
        if not c.objects: bpy.data.collections.remove(c)
    scene = bpy.context.scene
    for o in bpy.data.objects:
        if o.name not in scene.collection.all_objects: scene.collection.objects.link(o)
    bpy.context.view_layer.objects.active = body
    mr = [m for m in body.modifiers if m.type == 'MULTIRES'][0]
    mr.levels = subdiv; mr.render_levels = subdiv
    for o in bpy.data.objects: o.select_set(o == body)
    bpy.ops.object.modifier_apply(modifier=mr.name)
    # centre
    for o in bpy.data.objects:
        if o.parent is None: o.location.x -= CX
    bpy.context.view_layer.update()
    for o in [body] + list(body.children):
        mw = o.matrix_world.copy(); o.parent = None; o.matrix_world = mw
    arm_data = bpy.data.armatures.new('rig'); rig = bpy.data.objects.new('rig', arm_data)
    scene.collection.objects.link(rig)
    bpy.context.view_layer.objects.active = rig; rig.select_set(True)
    bpy.ops.object.mode_set(mode='EDIT')
    eb = {}
    for name, h, t, p in BONES:
        b = arm_data.edit_bones.new(name); b.head = J[h]; b.tail = J[t]
        if p: b.parent = eb[p]; b.use_connect = False
        eb[name] = b
    bpy.ops.object.mode_set(mode='OBJECT')
    for o in bpy.data.objects: o.select_set(o in (body, rig))
    bpy.context.view_layer.objects.active = rig
    bpy.ops.object.parent_set(type='ARMATURE_AUTO')
    for eye in [o for o in bpy.data.objects if '.eye.' in o.name]:
        bpy.data.objects.remove(eye, do_unlink=True)   # the renders hide them anyway
    return body, rig


def load():
    """Opens the rigged scene, building and caching it on first use."""
    if not os.path.exists(RIGGED):
        build(); bpy.ops.wm.save_as_mainfile(filepath=RIGGED)
    bpy.ops.wm.open_mainfile(filepath=RIGGED)
    return bpy.data.objects['GEO-body_male_realistic'], bpy.data.objects['rig']

