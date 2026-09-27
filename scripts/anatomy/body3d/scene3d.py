"""Poses the rigged base mesh from the 2D motion solver and renders it."""
import bpy, math, json, sys
from mathutils import Vector, Matrix, Quaternion
import solver

K = 0.0094           # metres per solver unit
LAT = {'upper': 0.20, 'fore': 0.10, 'thigh': 0.05, 'shin': 0.02, 'foot': 0.12}

def to3(p, view):
    # solver: x forward (side) / lateral (front), y down. 3D: forward = -y, up = z.
    if view == 'front': return Vector((p[0] * K, 0, (200 - p[1]) * K))
    return Vector((0, -p[0] * K, (200 - p[1]) * K))

def seg_dir(j, a, b, view, lateral=0.0):
    d = to3(j[b], view) - to3(j[a], view)
    if d.length < 1e-6: d = Vector((0, 0, -1))
    d.normalize()
    if lateral: d = (d + Vector((lateral, 0, 0))).normalized()
    return d

def aim(rig, name, direction, order_heads):
    pb = rig.pose.bones[name]; bone = pb.bone
    rest = bone.matrix_local.copy()
    rest_dir = (rest.to_3x3() @ Vector((0, 1, 0))).normalized()
    q = rest_dir.rotation_difference(direction)
    head = order_heads(name)
    m = Matrix.Translation(head) @ q.to_matrix().to_4x4() @ Matrix.Translation(-rest.translation) @ rest
    pb.matrix = m
    bpy.context.view_layer.update()

def pose(rig, p, view):
    j = solver.solve(p, view)
    for pb in rig.pose.bones: pb.matrix_basis = Matrix()
    bpy.context.view_layer.update()
    front = view == 'front'
    # Sides: in side view the near limb faces the camera (left side, x < 0); front view near = right (x > 0).
    sides = {'n': 'R', 'f': 'L'} if front else {'n': 'L', 'f': 'R'}
    def posed_head(name):
        pb = rig.pose.bones[name]
        if pb.parent is None: return pb.bone.head_local.copy()
        par = pb.parent
        return par.matrix @ par.bone.matrix_local.inverted() @ pb.bone.head_local
    torso = seg_dir(j, 'hip', 'spine', view); neck = seg_dir(j, 'spine', 'head', view)
    for b in ('pelvis', 'spine', 'chest'): aim(rig, b, torso, posed_head)
    for b in ('neck', 'head'): aim(rig, b, neck, posed_head)
    for s, S in sides.items():
        out = -1 if S == 'L' else 1
        lat = (lambda seg: 0.0) if front else (lambda seg: LAT[seg] * out)
        thigh_dir = seg_dir(j, 'hip' + s, 'knee' + s, view); shin_dir = seg_dir(j, 'knee' + s, 'ankle' + s, view)
        bend = max(0.0, 1 - thigh_dir.dot(shin_dir))          # 0 straight .. 1 at a right angle
        flex = 0.0 if front else max(0.0, -thigh_dir.y) * min(1.0, bend) * (1.0 if shin_dir.z < -0.5 else 0.0)
        # thighs reaching forward over a planted foot (squat, lunge): knees track out over the toes
        aim(rig, f'thigh.{S}', seg_dir(j, 'hip' + s, 'knee' + s, view, lat('thigh') + (0 if front else out * 0.32 * flex)), posed_head)
        aim(rig, f'shin.{S}', seg_dir(j, 'knee' + s, 'ankle' + s, view, lat('shin') - (0 if front else out * 0.12 * flex)), posed_head)
        foot = seg_dir(j, 'ankle' + s, 'toe' + s, view, lat('foot')) if not front else Vector((out * 0.35, -0.95, -0.2)).normalized()
        aim(rig, f'foot.{S}', foot, posed_head)
        aim(rig, f'upper.{S}', seg_dir(j, 'shoulder' + s, 'elbow' + s, view, lat('upper')), posed_head)
        fore = seg_dir(j, 'elbow' + s, 'wrist' + s, view, lat('fore'))
        aim(rig, f'fore.{S}', fore, posed_head)
        aim(rig, f'hand.{S}', fore, posed_head)
    # Anchor: move the rig so the anchor joint sits where the solver put it.
    anchor = p.get('anchor')
    key = {'foot': 'ankle', 'hand': 'wrist', 'knee': 'knee'}.get(anchor)
    if key:
        S = sides['n']; bone = {'ankle': f'shin.{S}', 'wrist': f'fore.{S}', 'knee': f'thigh.{S}'}[key]
        pb = rig.pose.bones[bone]; cur = rig.matrix_world @ pb.tail
        target = to3(j[key + 'n'], view)
    else:
        cur = rig.matrix_world @ rig.pose.bones['pelvis'].head; target = to3(j['hip'], view)
    delta = target - cur
    if front: delta.y = 0
    else: delta.x = 0
    rig.location += delta
    bpy.context.view_layer.update()
    return j

def setup(width=480, height=360):
    s = bpy.context.scene
    s.render.engine = 'CYCLES'; s.cycles.device = 'CPU'; s.cycles.samples = 24; s.cycles.use_denoising = True
    s.cycles.max_bounces = 4
    s.render.resolution_x, s.render.resolution_y = width, height
    s.render.film_transparent = False; s.view_settings.exposure = -0.6
    s.view_settings.view_transform = 'AgX'; s.view_settings.look = 'AgX - Medium High Contrast'
    world = bpy.data.worlds.new('w'); s.world = world; world.use_nodes = True
    bg = world.node_tree.nodes['Background']; bg.inputs[0].default_value = (0.012, 0.012, 0.014, 1); bg.inputs[1].default_value = 1
    body = bpy.data.objects['GEO-body_male_realistic']
    mat = bpy.data.materials.new('skin'); mat.use_nodes = True
    bsdf = mat.node_tree.nodes['Principled BSDF']
    bsdf.inputs['Base Color'].default_value = (0.42, 0.42, 0.44, 1); bsdf.inputs['Roughness'].default_value = 0.55
    body.data.materials.clear(); body.data.materials.append(mat)
    for o in bpy.data.objects:
        if '.eye.' in o.name: o.hide_render = True
    for poly in body.data.polygons: poly.use_smooth = True
    def light(name, loc, energy, size, color=(1, 1, 1)):
        # Point lights: an area light only lights the side it faces, which cut a hard line across the floor.
        ld = bpy.data.lights.new(name, 'POINT'); ld.energy = energy * 1.6; ld.shadow_soft_size = size / 2; ld.color = color
        lo = bpy.data.objects.new(name, ld); s.collection.objects.link(lo); lo.location = loc
        lo.rotation_euler = (Vector((0, 0, 0.9)) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
    light('key', (-2.4, -2.0, 3.2), 380, 3.0)
    light('fill', (2.2, -1.8, 1.2), 110, 3.5, (0.8, 0.88, 1.0))
    light('rim', (0.4, 3.0, 2.4), 520, 1.6, (1.0, 0.8, 0.65))
    floor = bpy.data.meshes.new('floor'); fo = bpy.data.objects.new('floor', floor); s.collection.objects.link(fo)
    import bmesh; bm = bmesh.new(); bmesh.ops.create_grid(bm, x_segments=4, y_segments=4, size=20); bm.to_mesh(floor)
    fm = bpy.data.materials.new('floor'); fm.use_nodes = True
    fb = fm.node_tree.nodes['Principled BSDF']; fb.inputs['Base Color'].default_value = (0.02, 0.02, 0.022, 1); fb.inputs['Roughness'].default_value = 0.9
    floor.materials.append(fm)
    cam = bpy.data.cameras.new('cam'); cam.lens = 50; co = bpy.data.objects.new('cam', cam); s.collection.objects.link(co); s.camera = co
    return co

def frame(cam, center, dist, yaw_deg, pitch_deg=8):
    yaw, pitch = math.radians(yaw_deg), math.radians(pitch_deg)
    d = Vector((-math.cos(pitch) * math.cos(yaw), -math.cos(pitch) * math.sin(yaw), math.sin(pitch)))
    cam.location = Vector(center) + d * dist
    cam.rotation_euler = (Vector(center) - cam.location).to_track_quat('-Z', 'Y').to_euler()

def reach(rig, S, target, pole=Vector((0, 0.35, -1))):
    """Two-bone IK: bends the arm on side S so the wrist lands on `target` (world), elbow toward `pole`."""
    mw = rig.matrix_world; inv = mw.inverted()
    pb = rig.pose.bones
    up, fo = pb[f'upper.{S}'], pb[f'fore.{S}']
    a, b = up.bone.length, fo.bone.length
    sh = mw @ up.head; t = Vector(target); d = t - sh; dist = min(d.length, a + b - 1e-4); dn = d.normalized()
    cos_a = (a * a + dist * dist - b * b) / (2 * a * dist); alpha = math.acos(max(-1, min(1, cos_a)))
    perp = (pole - dn * pole.dot(dn)).normalized()
    elbow = sh + dn * math.cos(alpha) * a + perp * math.sin(alpha) * a
    def posed_head(name):
        p = pb[name]; par = p.parent
        return par.matrix @ par.bone.matrix_local.inverted() @ p.bone.head_local
    to_local = lambda v: (inv.to_3x3() @ v).normalized()
    aim(rig, f'upper.{S}', to_local(elbow - sh), posed_head)
    fdir = to_local((sh + dn * dist) - elbow)
    aim(rig, f'fore.{S}', fdir, posed_head); aim(rig, f'hand.{S}', fdir, posed_head)


def curl(rig, S, fingers=95, thumb=45):
    """Closes the hand on side S: fingers and thumb swing toward the palm (palm faces the body in the rest pose)."""
    pb = rig.pose.bones
    out = -1 if S == 'L' else 1
    hand = pb[f'hand.{S}']
    rot = (hand.matrix @ hand.bone.matrix_local.inverted()).to_3x3()
    palm = (rot @ Vector((-out, -0.25, 0))).normalized()
    def posed_head(name):
        p = pb[name]; par = p.parent
        return par.matrix @ par.bone.matrix_local.inverted() @ p.bone.head_local
    for bone, ang in ((f'fingers.{S}', fingers), (f'thumb.{S}', thumb)):
        b = pb[bone]
        d = (rot @ (b.bone.tail_local - b.bone.head_local)).normalized()
        axis = d.cross(palm).normalized()
        new = Quaternion(axis, math.radians(ang)) @ d
        aim(rig, bone, new, posed_head)
