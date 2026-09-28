"""3D equipment for the motion patterns' 2D props, rebuilt each frame around the posed body."""
import bpy, bmesh, math
from mathutils import Vector, Matrix
import scene3d, look

MATS = {}
def mat(name, color, rough=0.5, metal=0.0):
    if name in MATS: return MATS[name]
    m = bpy.data.materials.new(name); m.use_nodes = True
    b = m.node_tree.nodes['Principled BSDF']; b.inputs['Base Color'].default_value = (*color, 1)
    b.inputs['Roughness'].default_value = rough; b.inputs['Metallic'].default_value = metal
    MATS[name] = m; return m

def clear():
    for o in list(bpy.data.objects):
        if o.get('prop'): bpy.data.objects.remove(o, do_unlink=True)

def _obj(name, bm, material):
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    for p in me.polygons: p.use_smooth = True
    o = bpy.data.objects.new(name, me); o['prop'] = 1; bpy.context.scene.collection.objects.link(o)
    me.materials.append(material); return o

def cylinder(a, b, r, material, segs=24):
    a, b = Vector(a), Vector(b); d = b - a; L = d.length
    bm = bmesh.new(); bmesh.ops.create_cone(bm, cap_ends=True, segments=segs, radius1=r, radius2=r, depth=L)
    o = _obj('cyl', bm, material)
    o.matrix_world = Matrix.Translation((a + b) / 2) @ d.to_track_quat('Z', 'Y').to_matrix().to_4x4()
    return o

def box(center, size, material, rot=None, bevel=0.015):
    bm = bmesh.new(); bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts: v.co = Vector((v.co.x * size[0], v.co.y * size[1], v.co.z * size[2]))
    if bevel: bmesh.ops.bevel(bm, geom=bm.edges[:], offset=bevel, segments=3, affect='EDGES')
    o = _obj('box', bm, material)
    o.matrix_world = Matrix.Translation(center) @ (rot or Matrix()).to_4x4()
    for p in o.data.polygons: p.use_smooth = False
    return o

def joint(rig, name, sides):
    """World position of a solver joint name on the posed rig."""
    pb = rig.pose.bones; mw = rig.matrix_world
    s = sides.get(name[-1]) if name[-1] in 'nf' else None
    base = name[:-1] if s else name
    table = {'wrist': (f'hand.{s}', 'head'), 'elbow': (f'fore.{s}', 'head'), 'ankle': (f'foot.{s}', 'head'),
             'knee': (f'shin.{s}', 'head'), 'hip': (f'thigh.{s}', 'head'), 'shoulder': (f'upper.{s}', 'head'),
             'toe': (f'foot.{s}', 'tail'), 'spine': ('neck', 'head'), 'neck': ('head', 'head'), 'head': ('head', 'tail')}
    if base not in table: base, s = 'hip', None
    bone, end = table[base] if s or base in ('spine', 'neck', 'head') else ('pelvis', 'head')
    return mw @ getattr(pb[bone], end)

def dip_bars(pattern, prop):
    """A post under a hand-anchored pose is a dip station: parallel bars either side instead of one pole."""
    f = pattern['frames'][0]
    return f.get('anchor') == 'hand' and not isinstance(prop['from'], str) and abs(prop['from'][0] - f['at'][0]) < 4 \
        and abs(prop['from'][1] - f['at'][1]) < 6


def build(pattern, rig, view, j2d):
    clear()
    front = view == 'front'
    sides = {'n': 'R', 'f': 'L'} if front else {'n': 'L', 'f': 'R'}
    steel = mat('steel', (0.62, 0.62, 0.66), 0.28, 1.0)
    black = mat('rubber', (0.035, 0.035, 0.04), 0.6)
    pad = mat('pad', (0.07, 0.07, 0.08), 0.45)
    frame = mat('frame', (0.16, 0.16, 0.18), 0.35, 0.6)
    def world(v):
        return joint(rig, v, sides) if isinstance(v, str) else scene3d.to3(v, view)
    def overhand():
        """Both hands close on a bar with the index fingers toward each other; returns the point between the fists."""
        wl, wr = (rig.matrix_world @ rig.pose.bones[f'hand.{S}'].head for S in 'LR')
        look.hold(rig, 'L', wr - wl); look.hold(rig, 'R', wl - wr)
        return (look.grip(rig, 'L')[0] + look.grip(rig, 'R')[0]) / 2
    def lateral(p, half):
        return (Vector((p.x - half, p.y, p.z)), Vector((p.x + half, p.y, p.z))) if not front else (Vector((p.x, p.y - half, p.z)), Vector((p.x, p.y + half, p.z)))
    for pr in pattern.get('props', []):
        t = pr['type']
        if t == 'floor': continue
        if t == 'pad':
            a, b = world(pr['from']), world(pr['to'])
            w = pr.get('width', 8) * scene3d.K
            mid = (a + b) / 2; d = b - a; L = d.length
            if front:
                rot = Vector((d.x, 0, d.z)).to_track_quat('X', 'Z').to_matrix()
                box(mid, (L + w, 0.34, w * 1.2), pad, rot)
            else:
                rot = Vector((0, d.y, d.z)).to_track_quat('Y', 'Z').to_matrix()
                box(mid, (0.34, L + w, w * 1.2), pad, rot)
        elif t == 'post' and dip_bars(pattern, pr):
            h = joint(rig, 'wristn', sides)
            for S, x in (('L', -0.27), ('R', 0.27)):
                bar = Vector((x, h.y, h.z - 0.02))
                cylinder(bar + Vector((0, -0.32, 0)), bar + Vector((0, 0.32, 0)), 0.02, frame)
                for dy in (-0.26, 0.26):
                    cylinder(bar + Vector((0, dy, 0)), Vector((x, h.y + dy, 0.0)), 0.025, frame)
                scene3d.reach(rig, S, bar + Vector((0, 0, 0.03)), Vector((0, 1, -0.2))); look.hold(rig, S)
        elif t == 'post':
            a, b = world(pr['from']), world(pr['to'])
            if isinstance(pr['from'], str) and pr['from'].startswith('wrist'):   # a landmine bar held at its end
                S = sides[pr['from'][-1]]; look.hold(rig, S); a = look.grip(rig, S)[0]
                cylinder(a + (a - b).normalized() * 0.06, a - (a - b).normalized() * 0.1, 0.022, steel)
            cylinder(a, b, 0.028, frame)
            base_c = Vector((b.x, b.y, 0.012)); box(base_c, (0.36, 0.08, 0.024) if not front else (0.08, 0.36, 0.024), frame, bevel=0.005)
        elif t in ('plate',):
            p = world(pr['at']); o = pr.get('offset', [0, 0]); p = p + (scene3d.to3((o[0], 200 - o[1]), view) - scene3d.to3((0, 200), view))
            if isinstance(pr['at'], str) and pr['at'].startswith('wrist') and not front:
                for S, x in (('L', -0.26), ('R', 0.26)):
                    scene3d.reach(rig, S, Vector((x, p.y, p.z)), Vector((0, 0.2, -1)))
            if pr['at'] == 'spine' and not front:
                p = p + Vector((0, 0.07, -0.02))   # racked on the upper back, behind the neck
                for S, x in (('L', -0.3), ('R', 0.3)):
                    scene3d.reach(rig, S, Vector((x, p.y, p.z)), Vector((0, 0.6, -1)))
            if pr.get('hold') and not front:
                for S, x in (('L', -0.3), ('R', 0.3)):
                    scene3d.reach(rig, S, Vector((x, p.y, p.z + 0.03)), Vector((0, 0.3, 1)))
                overhand()
            if isinstance(pr['at'], str) and (pr['at'].startswith('wrist') or pr['at'] == 'spine') and not front:
                c = overhand()
                if pr['at'] != 'spine': p = c
            r = pr.get('r', 16) * scene3d.K * 1.25
            a, b = lateral(p, 0.9)
            cylinder(a, b, 0.014, steel)
            for e in (0.62, 0.66):
                for sgn in (-1, 1):
                    c = lateral(p, e)[0 if sgn < 0 else 1]; c2 = lateral(p, e + 0.035)[0 if sgn < 0 else 1]
                    cylinder(c, c2, r, black, 48)
        elif t == 'dumbbell':
            if pr is not next(q for q in pattern['props'] if q['type'] == 'dumbbell'): continue   # one per hand
            for S in 'LR':
                look.hold(rig, S)
                c, axis, _ = look.grip(rig, S)
                a, b = c - axis * 0.075, c + axis * 0.075
                cylinder(a, b, 0.016, steel)
                for q, sgn in ((a, -1), (b, 1)):
                    cylinder(q, q + axis * sgn * 0.045, 0.05, black, 32)
        elif t == 'bar':
            c = overhand(); half = pr.get('half', 0.34)
            a, b = lateral(c, half); cylinder(a, b, 0.016, steel)
            if pr.get('uprights'):
                for q in (a, b):
                    cylinder(q, Vector((q.x, q.y, 0.0)), 0.025, frame)
                    box(Vector((q.x, q.y, 0.012)), (0.08, 0.36, 0.024) if not front else (0.36, 0.08, 0.024), frame, bevel=0.005)
        elif t == 'handles':
            # machine handles: a grip in each fist on a lever that swings about a pivot beside the seat
            piv = scene3d.to3(pr['pivot'], view)
            for S in 'LR':
                look.hold(rig, S)
                g, axis, _ = look.grip(rig, S)
                cylinder(g - axis * 0.06, g + axis * 0.06, 0.017, black, 24)
                end = g - axis * 0.06 if (g - axis * 0.06).z < (g + axis * 0.06).z else g + axis * 0.06
                q = Vector((g.x, piv.y, piv.z)) if not front else Vector((piv.x, g.y, piv.z))
                cylinder(end, q, 0.02, frame); cylinder(q + Vector((0, 0, -0.03)), q + Vector((0, 0, 0.03)), 0.035, frame)
        elif t == 'cable':
            a = world(pr['from']); b = scene3d.to3(pr['to'], view)
            if isinstance(pr['from'], str) and pr['from'].startswith('wrist') and front:
                S = sides[pr['from'][-1]]; look.hold(rig, S); a = look.grip(rig, S)[0]
            elif isinstance(pr['from'], str) and pr['from'].startswith('wrist'):
                # side view: both hands share one handle, a bar for an overhand grip or a short grip otherwise
                if pr.get('grip') == 'overhand': a = overhand()
                else:
                    for S in 'LR': look.hold(rig, S)
                    a = (look.grip(rig, 'L')[0] + look.grip(rig, 'R')[0]) / 2
                gl, gr = look.grip(rig, 'L')[0], look.grip(rig, 'R')[0]
                half = max(0.07, (gr - gl).length / 2 + 0.06)
                h1, h2 = lateral(a, half); cylinder(h1, h2, 0.015, frame)
            cylinder(a, b, 0.005, steel, 12)
            cylinder(b + Vector((0, 0, -0.04)), b + Vector((0, 0, 0.04)), 0.05, frame, 32)
        elif t == 'footplate':
            s = pr['at'][-1]
            a = joint(rig, 'ankle' + s, sides); toe = joint(rig, 'toe' + s, sides)
            knee = joint(rig, 'knee' + s, sides)
            sole = (a - knee).normalized()
            c = (a + toe) / 2 + sole * 0.05
            d = toe - a
            rot = Vector((0, d.y, d.z)).to_track_quat('Y', 'Z').to_matrix() if not front else None
            box(c, (0.5, 0.34, 0.025), frame, rot)
