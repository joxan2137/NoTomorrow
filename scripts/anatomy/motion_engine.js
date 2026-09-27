// Reference implementation of the motion demo model: `MotionDemo.swift` and `MotionDemo.kt` port it line for line.
// Used by `motions.py --preview` to draw every pattern in a browser.
const L = { torso: 50, neck: 7, head: 10.5, upper: 29, fore: 26, thigh: 44, shin: 43, foot: 17 };
const rad = d => d * Math.PI / 180;
const dir = a => [Math.sin(rad(a)), Math.cos(rad(a))];            // limb direction: 0 = down, 90 = forward
const sub = (p, a, len) => [p[0] - dir(a)[0] * len, p[1] - dir(a)[1] * len];
const pair = v => Array.isArray(v) ? v : [v, v];
const BOX = [-30, -20, 260, 220];

function ik(hip, foot, bendForward) {
  // two-bone IK: knee position for thigh L.thigh, shin L.shin
  const dx = foot[0] - hip[0], dy = foot[1] - hip[1];
  let d = Math.hypot(dx, dy); const a = L.thigh, b = L.shin;
  d = Math.min(d, a + b - 0.01);
  const cosA = (a * a + d * d - b * b) / (2 * a * d);
  const base = Math.atan2(dy, dx);
  const off = Math.acos(Math.max(-1, Math.min(1, cosA)));
  // knee on the forward side (+x relative to hip→foot line)
  const ang = base + (bendForward ? -off : off);
  return [hip[0] + Math.cos(ang) * a, hip[1] + Math.sin(ang) * a];
}

function solve(p, view) {
  const [thighN, thighF] = pair(p.thigh), [shinN, shinF] = pair(p.shin), [footN, footF] = pair(p.foot ?? 90);
  const [upperN, upperF] = pair(p.upper), [foreN, foreF] = pair(p.fore);
  const front = view === 'front';
  const j = {};
  if (p.anchor === 'foot') {
    const knee = sub(p.at, shinN, L.shin);
    j.hip = sub(knee, thighN, L.thigh);
    // Front view: the near ankle sits under the near hip, 7 units right of the centre line.
    if (front) j.hip = [p.at[0] - 7, j.hip[1]];
  } else if (p.anchor === 'hand') {
    const elbow = sub(p.at, foreN, L.fore);
    const shoulder = sub(elbow, upperN, L.upper);
    j.hip = [shoulder[0] - Math.sin(rad(p.torso)) * L.torso, shoulder[1] + Math.cos(rad(p.torso)) * L.torso];
    if (front) j.hip = [shoulder[0] - 15, j.hip[1]];
  } else if (p.anchor === 'knee') {
    j.hip = sub(p.at, thighN, L.thigh);
  } else j.hip = p.at;
  const t = p.torso, n = t + (p.neck ?? 0);
  j.spine = [j.hip[0] + Math.sin(rad(t)) * L.torso, j.hip[1] - Math.cos(rad(t)) * L.torso];
  j.neck = [j.spine[0] + Math.sin(rad(n)) * L.neck, j.spine[1] - Math.cos(rad(n)) * L.neck];
  j.head = [j.neck[0] + Math.sin(rad(n)) * L.head, j.neck[1] - Math.cos(rad(n)) * L.head];
  // Shoulders and hips: one point in side view, ±offset in front view (near = +x).
  const sOff = front ? 15 : 0, hOff = front ? 7 : 0;
  const sides = [['n', 1, thighN, shinN, footN, upperN, foreN], ['f', -1, thighF, shinF, footF, upperF, foreF]];
  for (const [s, sign, th, sh, ft, up, fo] of sides) {
    const m = front ? sign : 1;             // front view mirrors the far side
    const hip = [j.hip[0] + sign * hOff, j.hip[1]];
    const lift = p.lift ?? 0;
    const sho = [j.spine[0] + sign * sOff, j.spine[1] + (front ? 4 : 0) - lift];
    j['hip' + s] = hip; j['shoulder' + s] = sho;
    if (s === 'f' && p.farFoot) {
      j.kneef = ik(hip, p.farFoot, true);
      j.anklef = p.farFoot;
    } else {
      j['knee' + s] = [hip[0] + m * Math.sin(rad(th)) * L.thigh, hip[1] + Math.cos(rad(th)) * L.thigh];
      j['ankle' + s] = [j['knee' + s][0] + m * Math.sin(rad(sh)) * L.shin, j['knee' + s][1] + Math.cos(rad(sh)) * L.shin];
    }
    j['toe' + s] = front ? [j['ankle' + s][0] + m * 4, j['ankle' + s][1] + 2] : [j['ankle' + s][0] + Math.sin(rad(ft)) * L.foot, j['ankle' + s][1] + Math.cos(rad(ft)) * L.foot];
    j['elbow' + s] = [sho[0] + m * Math.sin(rad(up)) * L.upper, sho[1] + Math.cos(rad(up)) * L.upper];
    j['wrist' + s] = [j['elbow' + s][0] + m * Math.sin(rad(fo)) * L.fore, j['elbow' + s][1] + Math.cos(rad(fo)) * L.fore];
  }
  return j;
}

function lerp(a, b, t) {
  if (Array.isArray(a) || Array.isArray(b)) { const A = pair(a), B = pair(b); return [A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t]; }
  return a + (b - a) * t;
}
function mix(p, q, t) {
  const o = { ...p };
  for (const k of ['torso', 'neck', 'lift', 'thigh', 'shin', 'foot', 'upper', 'fore']) if (k in p || k in q) o[k] = lerp(p[k] ?? (k === 'foot' ? 90 : 0), q[k] ?? (k === 'foot' ? 90 : 0), t);
  if (p.at && q.at) o.at = [p.at[0] + (q.at[0] - p.at[0]) * t, p.at[1] + (q.at[1] - p.at[1]) * t];
  if (p.farFoot && q.farFoot) o.farFoot = [p.farFoot[0] + (q.farFoot[0] - p.farFoot[0]) * t, p.farFoot[1] + (q.farFoot[1] - p.farFoot[1]) * t];
  return o;
}
const W = { upper: 11, fore: 9, thigh: 16, shin: 12, foot: 7, neck: 9 };
function capsule(a, b, w, c) { return `<line x1="${a[0].toFixed(1)}" y1="${a[1].toFixed(1)}" x2="${b[0].toFixed(1)}" y2="${b[1].toFixed(1)}" stroke="${c}" stroke-width="${w}" stroke-linecap="round"/>`; }
const C = { near: '#9A9AA2', far: '#55555C', hot: '#FF6A2B', hotFar: '#B0532C', prop: '#3A3A40', metal: '#77777F' };
function propSvg(pr, j, layer) {
  if ((pr.layer || 'back') !== layer) return '';
  const pt = v => typeof v === 'string' ? j[v] : v;
  switch (pr.type) {
    case 'floor': return capsule([-20, 200], [220, 200], 2, C.prop);
    case 'pad': return capsule(pt(pr.from), pt(pr.to), pr.width || 8, C.prop);
    case 'post': return capsule(pt(pr.from), pt(pr.to), 5, C.prop);
    case 'plate': { const p = pt(pr.at); const o = pr.offset || [0, 0]; const q = [p[0] + o[0], p[1] + o[1]]; return `<circle cx="${q[0]}" cy="${q[1]}" r="${pr.r || 16}" fill="#26262A" stroke="${C.metal}" stroke-width="3"/><circle cx="${q[0]}" cy="${q[1]}" r="2.5" fill="${C.metal}"/>`; }
    case 'dumbbell': { const p = pt(pr.at); return `<rect x="${p[0] - 9}" y="${p[1] - 5}" width="18" height="10" rx="3" fill="${C.metal}"/>`; }
    case 'cable': { const p = pt(pr.from); return capsule(p, pr.to, 1.5, C.metal) + `<circle cx="${pr.to[0]}" cy="${pr.to[1]}" r="4" fill="${C.prop}"/>`; }
    case 'footplate': {
      const a = j[pr.at], b = j[pr.at.replace('ankle', 'toe')];
      const v = [b[0] - a[0], b[1] - a[1]];
      const k = j[pr.at.replace('ankle', 'knee')];
      const sd = [a[0] - k[0], a[1] - k[1]]; const sl = Math.hypot(sd[0], sd[1]);
      const o = [sd[0] / sl * 6, sd[1] / sl * 6];
      return capsule([a[0] - v[0] * 0.6 + o[0], a[1] - v[1] * 0.6 + o[1]], [b[0] + v[0] * 0.5 + o[0], b[1] + v[1] * 0.5 + o[1]], 6, C.prop);
    }
    case 'bar': { const p = pt(pr.at); return `<circle cx="${p[0]}" cy="${p[1]}" r="3.5" fill="${C.metal}"/>`; }
  }
  return '';
}
function render(pattern, t, hot) {
  const f = pattern.frames;
  const pose = mix(f[0], f[1], t);
  const view = pattern.view || 'side';
  const j = solve(pose, view);
  const col = (seg, s) => hot.includes(seg) ? (s === 'f' && view === 'side' ? C.hotFar : C.hot) : (s === 'f' && view === 'side' ? C.far : C.near);
  let out = '';
  for (const pr of pattern.props || []) out += propSvg(pr, j, 'back');
  const limbs = s => capsule(j['hip' + s], j['knee' + s], W.thigh, col('thigh', s)) + capsule(j['knee' + s], j['ankle' + s], W.shin, col('shin', s)) + capsule(j['ankle' + s], j['toe' + s], W.foot, s === 'f' && view === 'side' ? C.far : C.near);
  const arm = s => capsule(j['shoulder' + s], j['elbow' + s], W.upper, col('upper', s)) + capsule(j['elbow' + s], j['wrist' + s], W.fore, col('fore', s));
  const trunk = () => {
    const tc = hot.includes('torso') ? C.hot : C.near;
    const mid = [j.hip[0] * 0.45 + j.spine[0] * 0.55, j.hip[1] * 0.45 + j.spine[1] * 0.55];
    if (view === 'front') return capsule([j.hip[0] - 5, j.hip[1] - 2], [j.spine[0] - 7, j.spine[1] + 6], 20, tc) + capsule([j.hip[0] + 5, j.hip[1] - 2], [j.spine[0] + 7, j.spine[1] + 6], 20, tc) + capsule(j.shoulderf, j.shouldern, 12, tc);
    return capsule(j.hip, mid, 21, tc) + capsule(mid, j.spine, 24, tc);
  };
  out += limbs('f') + arm('f');
  for (const pr of pattern.props || []) out += propSvg(pr, j, 'middle');
  out += trunk() + capsule(j.spine, j.neck, W.neck, C.near) + `<circle cx="${j.head[0].toFixed(1)}" cy="${j.head[1].toFixed(1)}" r="${L.head}" fill="${C.near}"/>`;
  out += limbs('n') + arm('n');
  for (const pr of pattern.props || []) out += propSvg(pr, j, 'front');
  return out;
}
module.exports = { render, solve, mix, L, BOX };
