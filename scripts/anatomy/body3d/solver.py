"""Python port of ../motion_engine.js (solve + mix), so the 3D renders pose exactly like the 2D reference."""
import math
L = dict(torso=50, neck=7, head=10.5, upper=29, fore=26, thigh=44, shin=43, foot=17)
def rad(d): return d * math.pi / 180
def dirv(a): return (math.sin(rad(a)), math.cos(rad(a)))
def sub(p, a, ln): d = dirv(a); return (p[0] - d[0] * ln, p[1] - d[1] * ln)
def pair(v): return tuple(v) if isinstance(v, (list, tuple)) else (v, v)
def ik(hip, foot, bend_forward):
    dx, dy = foot[0] - hip[0], foot[1] - hip[1]
    d = min(math.hypot(dx, dy), L['thigh'] + L['shin'] - 0.01); a, b = L['thigh'], L['shin']
    cos_a = (a * a + d * d - b * b) / (2 * a * d); base = math.atan2(dy, dx)
    off = math.acos(max(-1, min(1, cos_a))); ang = base + (-off if bend_forward else off)
    return (hip[0] + math.cos(ang) * a, hip[1] + math.sin(ang) * a)
def solve(p, view):
    thN, thF = pair(p['thigh']); shN, shF = pair(p['shin']); ftN, ftF = pair(p.get('foot', 90))
    upN, upF = pair(p['upper']); foN, foF = pair(p['fore'])
    front = view == 'front'; j = {}
    anchor = p.get('anchor')
    if anchor == 'foot':
        knee = sub(p['at'], shN, L['shin']); j['hip'] = sub(knee, thN, L['thigh'])
        if front: j['hip'] = (p['at'][0] - 7, j['hip'][1])
    elif anchor == 'hand':
        elbow = sub(p['at'], foN, L['fore']); sh = sub(elbow, upN, L['upper'])
        j['hip'] = (sh[0] - math.sin(rad(p['torso'])) * L['torso'], sh[1] + math.cos(rad(p['torso'])) * L['torso'])
        if front: j['hip'] = (sh[0] - 15, j['hip'][1])
    elif anchor == 'knee':
        j['hip'] = sub(p['at'], thN, L['thigh'])
    else: j['hip'] = tuple(p['at'])
    t = p['torso']; n = t + p.get('neck', 0)
    j['spine'] = (j['hip'][0] + math.sin(rad(t)) * L['torso'], j['hip'][1] - math.cos(rad(t)) * L['torso'])
    j['neck'] = (j['spine'][0] + math.sin(rad(n)) * L['neck'], j['spine'][1] - math.cos(rad(n)) * L['neck'])
    j['head'] = (j['neck'][0] + math.sin(rad(n)) * L['head'], j['neck'][1] - math.cos(rad(n)) * L['head'])
    sOff, hOff = (15, 7) if front else (0, 0)
    for s, sign, th, sh, ft, up, fo in (('n', 1, thN, shN, ftN, upN, foN), ('f', -1, thF, shF, ftF, upF, foF)):
        m = sign if front else 1
        hip = (j['hip'][0] + sign * hOff, j['hip'][1]); lift = p.get('lift', 0)
        sho = (j['spine'][0] + sign * sOff, j['spine'][1] + (4 if front else 0) - lift)
        j['hip' + s] = hip; j['shoulder' + s] = sho
        if s == 'f' and p.get('farFoot'):
            j['kneef'] = ik(hip, p['farFoot'], True); j['anklef'] = tuple(p['farFoot'])
        else:
            j['knee' + s] = (hip[0] + m * math.sin(rad(th)) * L['thigh'], hip[1] + math.cos(rad(th)) * L['thigh'])
            j['ankle' + s] = (j['knee' + s][0] + m * math.sin(rad(sh)) * L['shin'], j['knee' + s][1] + math.cos(rad(sh)) * L['shin'])
        j['toe' + s] = (j['ankle' + s][0] + m * 4, j['ankle' + s][1] + 2) if front else \
            (j['ankle' + s][0] + math.sin(rad(ft)) * L['foot'], j['ankle' + s][1] + math.cos(rad(ft)) * L['foot'])
        j['elbow' + s] = (sho[0] + m * math.sin(rad(up)) * L['upper'], sho[1] + math.cos(rad(up)) * L['upper'])
        j['wrist' + s] = (j['elbow' + s][0] + m * math.sin(rad(fo)) * L['fore'], j['elbow' + s][1] + math.cos(rad(fo)) * L['fore'])
    return j
def lerp(a, b, t):
    if isinstance(a, (list, tuple)) or isinstance(b, (list, tuple)):
        A, B = pair(a), pair(b); return [A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t]
    return a + (b - a) * t
def mix(p, q, t):
    o = dict(p)
    for k in ('torso', 'neck', 'lift', 'thigh', 'shin', 'foot', 'upper', 'fore'):
        if k in p or k in q:
            dflt = 90 if k == 'foot' else 0
            o[k] = lerp(p.get(k, dflt), q.get(k, dflt), t)
    if p.get('at') and q.get('at'): o['at'] = [p['at'][i] + (q['at'][i] - p['at'][i]) * t for i in (0, 1)]
    if p.get('farFoot') and q.get('farFoot'): o['farFoot'] = [p['farFoot'][i] + (q['farFoot'][i] - p['farFoot'][i]) * t for i in (0, 1)]
    return o


if __name__ == '__main__':
    # Checks the port against motion_joints.json: joints from motion_engine.js at t = 0.5 for a few patterns.
    import json, pathlib
    here = pathlib.Path(__file__).resolve().parent
    fixture = json.loads((here / 'motion_joints.json').read_text())
    patterns = json.loads((here.parent / 'motion_patterns.json').read_text())
    for name, joints in fixture.items():
        p = patterns[name]
        got = solve(mix(p['frames'][0], p['frames'][1], 0.5), p.get('view', 'side'))
        for k, v in joints.items():
            assert abs(got[k][0] - v[0]) < 0.01 and abs(got[k][1] - v[1]) < 0.01, (name, k, v, got[k])
    print(f'{len(fixture)} patterns match motion_engine.js')
