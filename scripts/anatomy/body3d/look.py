"""The demo clips' look: a clothed athlete (shorts, tank top, shoes) in a bright photo studio with a curved
backdrop, and arms rolled so the biceps faces the way the elbow bends (the 2D keyframes carry no twist)."""
import math
import bpy
from mathutils import Vector, Matrix, Quaternion
import rig as R, scene3d, muscles3d


def cloth_attr(body):
    """Smooth 0..1 fields for shorts, top and shoes on the rest-pose body, thresholded at 0.5 in the shader. The
    fields come from the (smooth) bone weights and position, so the hems cross triangles as clean lines."""
    J = R.J; knee_z = J['knee.L'][2]; names = {g.index: g.name for g in body.vertex_groups}
    sm = muscles3d.smooth; shorts, top, shoes = [], [], []
    for v in body.data.vertices:
        w = {}
        for g in v.groups:
            n = names.get(g.group, ''); w[n.split('.')[0]] = w.get(n.split('.')[0], 0) + g.weight
        p = v.co; trunk = w.get('pelvis', 0) + w.get('spine', 0) + w.get('chest', 0)
        legs = trunk + w.get('thigh', 0)
        shorts.append(sm(knee_z + 0.1, knee_z + 0.14, p.z) * (1 - sm(0.99, 1.0, p.z)) * min(1.0, legs * 1.6))
        neck = 1.43 - 0.55 * max(0.0, abs(p.x) - 0.055)
        arm_hole = 1 - sm(0.155, 0.175, abs(p.x) - 0.35 * max(0.0, 1.3 - p.z))
        top.append(sm(0.97, 0.985, p.z) * (1 - sm(neck - 0.01, neck + 0.01, p.z)) * arm_hole * min(1.0, (trunk + w.get('clavicle', 0)) * 1.4))
        shoes.append(1 - sm(0.095, 0.115, p.z))
    me = body.data
    for name, vals in (('shorts', shorts), ('top', top), ('shoes', shoes)):
        if name in me.attributes: me.attributes.remove(me.attributes[name])
        a = me.attributes.new(name, 'FLOAT', 'POINT'); a.data.foreach_set('value', vals)


def studio(scn, body):
    """Lights, exposure and the body's material: skin and clothes from the fields above, with the worked muscles
    (the 'hot' attribute) tinting ember through them."""
    world = scn.world.node_tree.nodes['Background']; world.inputs[0].default_value = (0.2, 0.205, 0.215, 1)
    scn.view_settings.exposure = -1.5; scn.view_settings.look = 'AgX - Medium High Contrast'
    fb = bpy.data.materials['floor'].node_tree.nodes['Principled BSDF']
    fb.inputs['Base Color'].default_value = (0.5, 0.51, 0.53, 1); fb.inputs['Roughness'].default_value = 0.8
    for o in [o for o in bpy.data.objects if o.type == 'LIGHT']: bpy.data.objects.remove(o)
    def soft(name, loc, energy, size, color=(1, 1, 1)):
        ld = bpy.data.lights.new(name, 'AREA'); ld.energy = energy; ld.size = size; ld.color = color
        lo = bpy.data.objects.new(name, ld); scn.collection.objects.link(lo); lo.location = loc
        lo.rotation_euler = (Vector((0, 0, 1.0)) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
    soft('key', (-2.2, -2.6, 3.0), 900, 2.5, (1.0, 0.97, 0.93))
    soft('fill', (2.6, -1.8, 1.6), 300, 3.0, (0.9, 0.94, 1.0))
    soft('rim', (0.6, 2.8, 2.6), 700, 1.5)
    nt = body.data.materials[0].node_tree; nodes = nt.nodes; links = nt.links
    for n in list(nodes):
        if n.type not in ('BSDF_PRINCIPLED', 'OUTPUT_MATERIAL'): nodes.remove(n)
    bsdf = nodes['Principled BSDF']
    def layer(prev, attr, rgb):
        n = nodes.new('ShaderNodeAttribute'); n.attribute_name = attr
        edge = nodes.new('ShaderNodeMapRange'); edge.interpolation_type = 'SMOOTHSTEP'
        edge.inputs['From Min'].default_value = 0.45; edge.inputs['From Max'].default_value = 0.55
        links.new(n.outputs['Fac'], edge.inputs['Value'])
        m = nodes.new('ShaderNodeMix'); m.data_type = 'RGBA'; m.inputs[7].default_value = (*rgb, 1)
        links.new(edge.outputs['Result'], m.inputs[0])
        if isinstance(prev, tuple): m.inputs[6].default_value = (*prev, 1)
        else: links.new(prev, m.inputs[6])
        return m.outputs[2]
    color = layer((0.36, 0.22, 0.15), 'shorts', (0.035, 0.035, 0.04))
    color = layer(color, 'top', (0.09, 0.10, 0.12))
    color = layer(color, 'shoes', (0.75, 0.75, 0.76))
    hot = nodes.new('ShaderNodeAttribute'); hot.attribute_name = 'hot'
    mix = nodes.new('ShaderNodeMix'); mix.data_type = 'RGBA'; mix.inputs[7].default_value = (1.0, 0.25, 0.05, 1)
    k = nodes.new('ShaderNodeMath'); k.operation = 'MULTIPLY'; k.inputs[1].default_value = 1.0
    links.new(hot.outputs['Fac'], k.inputs[0]); links.new(k.outputs[0], mix.inputs[0])
    links.new(color, mix.inputs[6]); links.new(mix.outputs[2], bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = 0.6
    links.new(mix.outputs[2], bsdf.inputs['Emission Color'])
    em = nodes.new('ShaderNodeMath'); em.operation = 'MULTIPLY'; em.inputs[1].default_value = 0.6
    links.new(hot.outputs['Fac'], em.inputs[0]); links.new(em.outputs[0], bsdf.inputs['Emission Strength'])
    bsdf.inputs['Subsurface Weight'].default_value = 0.05


def backdrop(scn):
    """A curved photo-studio sweep behind the floor, so there is no horizon line."""
    import bmesh
    me = bpy.data.meshes.new('sweep'); bm = bmesh.new(); prof = []
    for i in range(25):
        a = math.pi / 2 * i / 24; prof.append((4.0 + 1.5 * math.sin(a), 1.5 - 1.5 * math.cos(a)))
    prof = [(y, z) for y, z in [(-6.0, 0.0)] + [(y, z) for y, z in prof] + [(5.5, 8.0)]]
    rows = [[bm.verts.new((x, y, z)) for y, z in prof] for x in (-12.0, 12.0)]
    for i in range(len(prof) - 1): bm.faces.new((rows[0][i], rows[0][i + 1], rows[1][i + 1], rows[1][i]))
    bm.to_mesh(me); o = bpy.data.objects.new('sweep', me); scn.collection.objects.link(o)
    for poly in me.polygons: poly.use_smooth = True
    me.materials.append(bpy.data.materials['floor'])
    bpy.data.objects['floor'].hide_render = True
    bpy.context.view_layer.update()
    for ob in [o] + [o for o in bpy.data.objects if o.type == 'LIGHT']: ob['home'] = [list(r) for r in ob.matrix_world]


# The sweep and lights are built for a camera at yaw 58 (looking toward +y); face() turns them for another yaw.
HOME_YAW = 58


def face(yaw):
    turn = Matrix.Rotation(math.radians(yaw - HOME_YAW), 4, 'Z')
    for ob in bpy.data.objects:
        if 'home' in ob: ob.matrix_world = turn @ Matrix([list(r) for r in ob['home']])
    bpy.context.view_layer.update()


def twist(rig, bone, want, rest_front):
    """Rolls a posed bone about its own axis so its rest-pose `rest_front` side points toward `want`."""
    pb = rig.pose.bones[bone]; M = pb.matrix.copy(); r = M.to_3x3()
    axis = (r @ Vector((0, 1, 0))).normalized()
    cur = r @ pb.bone.matrix_local.to_3x3().inverted() @ Vector(rest_front)
    cur = (cur - axis * cur.dot(axis)); want = (want - axis * want.dot(axis))
    if cur.length < 1e-4 or want.length < 1e-4: return
    cur.normalize(); want.normalize()
    ang = math.atan2(axis.dot(cur.cross(want)), cur.dot(want))
    head = M.translation
    pb.matrix = Matrix.Translation(head) @ Quaternion(axis, ang).to_matrix().to_4x4() @ Matrix.Translation(-head) @ M
    bpy.context.view_layer.update()


def fix_arms(rig, supinate=False):
    """Rolls each bent arm so the biceps faces the forearm; with `supinate`, also turns the palm toward the
    shoulder (curls). Then relaxes the hands."""
    pb = rig.pose.bones
    for S in 'LR':
        up = pb[f'upper.{S}']; fo = pb[f'fore.{S}']; hd = pb[f'hand.{S}']
        fore_dir = (fo.matrix.to_3x3() @ Vector((0, 1, 0))).normalized()
        fore_m = fo.matrix.copy()
        up_dir = (up.matrix.to_3x3() @ Vector((0, 1, 0))).normalized()
        bend = fore_dir - up_dir * fore_dir.dot(up_dir)
        if bend.length > 0.15:                       # a bent elbow: the biceps faces the way the forearm swings
            twist(rig, f'upper.{S}', bend, (0, -1, 0))
            # restore the forearm and hand in the world, then turn the palm up toward the shoulder
            fo.matrix = fore_m; bpy.context.view_layer.update()
            if supinate: twist(rig, f'fore.{S}', -up_dir, (0, -1, 0))
            hd.matrix = Matrix.Translation(fo.matrix @ Vector((0, fo.bone.length, 0)) - fo.matrix.translation) @ fo.matrix
            bpy.context.view_layer.update()
        scene3d.curl(rig, S, 35, 20)   # relaxed; look.hold closes a hand on a handle


# How far the handle's centre sits from the hand bone: into the palm, and back from the knuckles toward the wrist.
GRIP_PALM, GRIP_BACK = 0.03, 0.015


def _hand_rest(rig, S):
    """The hand's rest-pose knuckles, pointing direction, palm normal and index-side direction (armature space)."""
    b = rig.pose.bones[f'hand.{S}'].bone
    out = -1 if S == 'L' else 1
    d = (b.tail_local - b.head_local).normalized()
    palm = Vector((-out, -0.25, 0)); palm = (palm - d * palm.dot(d)).normalized()
    index = d.cross(palm).normalized()
    thumb = rig.pose.bones[f'thumb.{S}'].bone.head_local - b.head_local
    if index.dot(thumb) < 0: index = -index
    return b.tail_local, d, palm, index


def grip(rig, S):
    """Where a handle held in hand S sits (world), with the hand's index-side and palm directions."""
    hb = rig.pose.bones[f'hand.{S}']; mw = rig.matrix_world
    delta = hb.matrix @ hb.bone.matrix_local.inverted(); rot = (mw @ delta).to_3x3()
    knuckle, d, palm, index = _hand_rest(rig, S)
    centre = mw @ delta @ (knuckle + palm * GRIP_PALM - d * GRIP_BACK)
    return centre, (rot @ index).normalized(), (rot @ palm).normalized()


def hold(rig, S, index_want=None):
    """Closes hand S round a handle. With `index_want`, first rolls the forearm and hand so the index-finger
    side points that way (toward the other hand for an overhand bar grip)."""
    if index_want is not None:
        knuckle, d, palm, index = _hand_rest(rig, S)
        twist(rig, f'fore.{S}', Vector(index_want), index)
        twist(rig, f'hand.{S}', Vector(index_want), index)
    scene3d.curl(rig, S, 105, 60)
