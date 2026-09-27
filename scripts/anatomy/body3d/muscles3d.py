"""Muscle regions on the rest-pose body as soft anatomical volumes (ellipsoids around the trunk, sectors of
limb capsules), so a highlight reads as the muscle's shape with a soft edge rather than a painted patch."""
import math
from mathutils import Vector
MUSCLES = ['abdominals', 'abductors', 'adductors', 'biceps', 'calves', 'chest', 'forearms', 'glutes', 'hamstrings', 'lats',
           'lower back', 'middle back', 'neck', 'quadriceps', 'shoulders', 'tibialis anterior', 'traps', 'triceps']

# Where a trunk volume starts fading, as a fraction of its radius, and how far a limb band fades in and out along
# the limb (as a fraction of its length); the still body view uses softer edges than the clips.
EDGE = 0.7
RAMP = 0.1

def smooth(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t)

def ellipsoid(c, r, facing=None, face_min=0.0):
    c, r = Vector(c), Vector(r)
    def f(p, n):
        d = math.sqrt(((p.x - c.x) / r.x) ** 2 + ((p.y - c.y) / r.y) ** 2 + ((p.z - c.z) / r.z) ** 2)
        v = 1 - smooth(EDGE, 1.0, d)
        if facing is not None: v *= smooth(face_min - 0.25, face_min + 0.1, n.dot(Vector(facing)))
        return v
    return f

def mirror(c):
    return [(c[0], c[1], c[2]), (-c[0], c[1], c[2])]

def limb(head, tail, t0, t1, sector, width=0.9, radius=0.17, bones=()):
    """A band of a limb between t0..t1 along head→tail, on the side `sector` (a unit direction in the rest pose,
    x taken as 'outward' for the limb's side), within `width` (cosine margin) of it. When `bones` is given, only
    skin the rig moves with those bones counts, so an arm's band never spills onto the ribs beside it."""
    head, tail = Vector(head), Vector(tail); ax = tail - head; L2 = ax.dot(ax)
    out = 1 if head.x > 0 else -1
    sec = Vector((sector[0] * out, sector[1], sector[2])).normalized()
    def f(p, n):
        t = (p - head).dot(ax) / L2
        along = smooth(t0 - RAMP, t0 + RAMP * 0.6, t) * (1 - smooth(t1 - RAMP * 0.6, t1 + RAMP, t))
        radial = p - (head + ax * t)
        if radial.length > radius: return 0.0
        radial.normalize()
        return along * smooth(1 - width - 0.2, 1 - width + 0.15, radial.dot(sec))
    f.bones = bones
    return f

import rig as R
J = {k: Vector(v) for k, v in R.J.items()}
VOL = {m: [] for m in MUSCLES}
for s in 'LR':
    m = -1 if s == 'L' else 1
    hip, knee, ankle = J[f'hip.{s}'], J[f'knee.{s}'], J[f'ankle.{s}']
    sho, elb, wri = J[f'shoulder.{s}'], J[f'elbow.{s}'], J[f'wrist.{s}']
    VOL['chest'].append(ellipsoid((m * 0.1, -0.08, 1.283), (0.118, 0.095, 0.08), (0, -1, 0), 0.05))
    VOL['shoulders'].append(ellipsoid((m * 0.19, -0.005, 1.345), (0.07, 0.085, 0.105)))
    VOL['biceps'].append(limb(sho, elb, 0.32, 0.9, (0.0, -1, 0), 0.55, bones=(f'upper.{s}', f'fore.{s}')))
    VOL['triceps'].append(limb(sho, elb, 0.26, 0.92, (0.15, 1, 0), 0.62, bones=(f'upper.{s}', f'fore.{s}')))
    VOL['forearms'].append(limb(elb, wri, 0.0, 0.72, (0, -1, 0), 1.6, bones=(f'fore.{s}', f'upper.{s}')))
    VOL['abdominals'].append(ellipsoid((m * 0.105, -0.05, 1.05), (0.065, 0.09, 0.12), (m * 0.6, -0.8, 0), 0.0))
    VOL['lats'].append(ellipsoid((m * 0.12, 0.06, 1.2), (0.085, 0.11, 0.16), (m * 0.55, 0.8, 0), -0.2))
    VOL['middle back'].append(ellipsoid((m * 0.055, 0.105, 1.275), (0.045, 0.06, 0.085), (0, 1, 0), 0.2))
    VOL['lower back'].append(ellipsoid((m * 0.04, 0.095, 1.03), (0.04, 0.06, 0.1), (0, 1, 0), 0.2))
    VOL['glutes'].append(ellipsoid((m * 0.085, 0.1, 0.89), (0.105, 0.11, 0.125), (m * 0.3, 1, 0), 0.2))
    VOL['abductors'].append(ellipsoid((m * 0.16, 0.0, 0.925), (0.045, 0.08, 0.07), (m, 0, 0), 0.3))
    VOL['quadriceps'].append(limb(hip, knee, 0.12, 0.95, (0.5, -1, 0), 0.62, bones=(f'thigh.{s}', f'shin.{s}')))
    VOL['hamstrings'].append(limb(hip, knee, 0.14, 0.88, (0.0, 1, 0), 0.62, bones=(f'thigh.{s}', f'shin.{s}')))
    VOL['adductors'].append(limb(hip, knee, 0.06, 0.6, (-1, -0.15, 0), 0.6, bones=(f'thigh.{s}',)))
    VOL['calves'].append(limb(knee, ankle, 0.04, 0.62, (0.0, 1, 0), 0.8, bones=(f'shin.{s}', f'thigh.{s}')))
    VOL['tibialis anterior'].append(limb(knee, ankle, 0.1, 0.78, (0.45, -1, 0), 0.35, bones=(f'shin.{s}', f'thigh.{s}')))
    VOL['neck'].append(ellipsoid((m * 0.035, -0.035, 1.475), (0.035, 0.05, 0.075), (0, -1, 0), -0.3))
VOL['abdominals'].append(ellipsoid((0, -0.095, 1.08), (0.1, 0.09, 0.17), (0, -1, 0), 0.0))
VOL['traps'].append(ellipsoid((0, 0.06, 1.405), (0.165, 0.09, 0.075), (0, 0.6, 0.8), -0.3))
VOL['traps'].append(ellipsoid((0, 0.1, 1.3), (0.075, 0.07, 0.17), (0, 1, 0), 0.3))

def values(body):
    """Per vertex: {muscle: 0..1} for the muscles that touch it."""
    names = {g.index: g.name for g in body.vertex_groups}
    out = []
    for v in body.data.vertices:
        p, n = v.co, v.normal
        w = {names[g.group]: g.weight for g in v.groups if g.group in names}
        d = {}
        for m, fs in VOL.items():
            best = max(f(p, n) * (smooth(0.2, 0.6, sum(w.get(b, 0) for b in f.bones)) if getattr(f, 'bones', ()) else 1)
                       for f in fs)
            if best > 0.02: d[m] = best
        out.append(d)
    return out

def labels(vals, threshold=0.5):
    return [max(d, key=d.get) if d and max(d.values()) >= threshold else None for d in vals]

def mask(vals, wanted):
    """Soft mask: a vertex glows by its strongest wanted muscle, dimmed where another muscle dominates it."""
    out = []
    for d in vals:
        if not d: out.append(0.0); continue
        w = max((d.get(m, 0) for m in wanted), default=0)
        other = max((v for k, v in d.items() if k not in wanted), default=0)
        out.append(max(0.0, w - max(0.0, other - w) * 0.8))
    return out

def write_attr(body, name, values_):
    me = body.data
    if name in me.attributes: me.attributes.remove(me.attributes[name])
    a = me.attributes.new(name, 'FLOAT', 'POINT'); a.data.foreach_set('value', values_)
