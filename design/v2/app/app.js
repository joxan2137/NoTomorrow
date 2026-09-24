/* No Tomorrow v2 — interactive prototype (vanilla JS, no build step).
   Deep links: #today #train #fuel #progress #body #bro #workout #summary #ai #cant */
(() => {
'use strict';

// ─────────────────────────── environment ───────────────────────────

const params = new URLSearchParams(location.search);
let framedByProposal = false;
try { framedByProposal = !!(window.frameElement && window.frameElement.id === 'proto'); } catch (e) { /* cross-origin parent */ }
const EMBED = params.has('embed') || framedByProposal;
const SMALL = matchMedia('(max-width: 540px)').matches && !EMBED;
if (EMBED || SMALL) document.body.classList.add('bleed');
if (SMALL) document.body.classList.add('native');
const device = document.getElementById('device');
function fit() {
  if (document.body.classList.contains('bleed')) return;
  const s = Math.min(1, (innerHeight - 40) / 898, (innerWidth - 40) / 426);
  device.style.transform = `scale(${s})`;
}
addEventListener('resize', fit); fit();

// ─────────────────────────── helpers ───────────────────────────

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const NB = ' ';
const n0 = v => Math.round(v).toString().replace(/\B(?=(\d{3})+(?!\d))/g, NB);
const n1 = v => { const r = Math.round(v * 10) / 10; return Number.isInteger(r) ? n0(r) : r.toFixed(1); };
const pad = v => String(v).padStart(2, '0');
const mmss = s => `${Math.floor(s / 60)}:${pad(s % 60)}`;
const clock = s => s >= 3600 ? `${Math.floor(s / 3600)}:${pad(Math.floor(s / 60) % 60)}:${pad(s % 60)}` : mmss(s);
const e1rm = (kg, reps) => kg * (1 + reps / 30);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
function mulberry(seed) { return () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }

const P = {
  home: '<path d="M4 10.2 12 4l8 6.2V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z"/>',
  train: '<path d="M6.5 6.5v11M3.5 9v6M17.5 6.5v11M20.5 9v6M6.5 12h11"/>',
  fuel: '<path d="M7 3v5.5a2 2 0 0 0 4 0V3M9 3v18M16.5 21V3c-1.9 1.3-3 3.6-3 6.6 0 1.8.9 2.9 3 2.9"/>',
  progress: '<path d="M3.5 3.5v17h17"/><path d="m7.5 15 4-4.5 3 3 5.5-6.5"/>',
  bro: '<circle cx="9" cy="8" r="3.3"/><path d="M2.8 20c.4-3.4 3-5.6 6.2-5.6s5.8 2.2 6.2 5.6"/><path d="M15.5 4.9a3.2 3.2 0 0 1 0 6.2M17.3 14.6c2.2.6 3.6 2.6 3.9 5.4"/>',
  check: '<path d="M19.5 6.5 9.5 16.5 4.5 11.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  chevR: '<path d="m9.5 6 6 6-6 6"/>',
  chevL: '<path d="m14.5 6-6 6 6 6"/>',
  chevD: '<path d="m6 9.5 6 6 6-6"/>',
  x: '<path d="M17.5 6.5l-11 11M6.5 6.5l11 11"/>',
  camera: '<path d="M4.5 7.5h3l1.8-2.5h5.4l1.8 2.5h3a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18V9a1.5 1.5 0 0 1 1.5-1.5z"/><circle cx="12" cy="13" r="3.5"/>',
  barcode: '<path d="M3.5 7.5v-2a2 2 0 0 1 2-2h2M16.5 3.5h2a2 2 0 0 1 2 2v2M20.5 16.5v2a2 2 0 0 1-2 2h-2M7.5 20.5h-2a2 2 0 0 1-2-2v-2M8 8v8M11 8v8M13.5 8v8M16 8v8"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.3-4.3"/>',
  trophy: '<path d="M8 20.5h8M12 16v4.5M7 4h10v5.5a5 5 0 0 1-10 0z"/><path d="M7 6H4.5v1.5A3 3 0 0 0 7.2 10.5M17 6h2.5v1.5a3 3 0 0 1-2.7 3"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  more: '<circle cx="5.5" cy="12" r="1.3" fill="currentColor"/><circle cx="12" cy="12" r="1.3" fill="currentColor"/><circle cx="18.5" cy="12" r="1.3" fill="currentColor"/>',
  play: '<path d="M8 5.5v13l10.5-6.5z" fill="currentColor"/>',
  msg: '<path d="M20.5 11.5a8 8 0 0 1-11.7 7.1L4 20l1.3-4.4A8 8 0 1 1 20.5 11.5z"/>',
  sparkle: '<path d="M11 3.5 12.9 9 18.5 11l-5.6 2L11 18.5 9.1 13 3.5 11l5.6-2z"/><path d="M19 3v4M17 5h4"/>',
  target: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="4.8"/><circle cx="12" cy="12" r="1.4" fill="currentColor"/>',
  share: '<path d="M4.5 12.5v6a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5v-6M16 7l-4-4-4 4M12 3.5V15"/>',
  bolt: '<path d="M13 2.5 5 13.5h6l-1 8 8-11h-6z"/>',
  thermo: '<path d="M14 14.5V5a2 2 0 0 0-4 0v9.5a4 4 0 1 0 4 0z"/><path d="M12 11v6"/>',
  work: '<rect x="3.5" y="7.5" width="17" height="12" rx="2.5"/><path d="M9 7.5V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v1.5M3.5 12.5h17"/>',
  moon: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
  heart: '<path d="M12 20s-7.5-4.4-7.5-10A4.2 4.2 0 0 1 12 7.5 4.2 4.2 0 0 1 19.5 10c0 5.6-7.5 10-7.5 10z"/>',
  flame: '<path d="M12 22c4.1 0 7-2.8 7-6.9 0-3.3-2-5.5-3.5-7.1-.3 1.8-1.2 2.9-2.3 3.3.3-3.5-1.3-6.8-4.4-9.3.1 3-1.4 5.3-2.9 7.1S4 12.7 4 15.2C4 19.2 7.9 22 12 22z" fill="currentColor" stroke="none"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/>',
  up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
  down: '<path d="M12 5v14M6 13l6 6 6-6"/>',
  scale: '<rect x="3.5" y="3.5" width="17" height="17" rx="4.5"/><path d="M8.3 9.2a5.2 5.2 0 0 1 7.4 0L13.4 12a2 2 0 0 0-2.8 0z"/>',
};
const ic = (name, size = 22, sw = 2) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${P[name]}</svg>`;

// Catmull-Rom → cubic Bézier through points
function smooth(pts, t = 1 / 6) {
  if (pts.length < 2) return '';
  let d = `M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] || pts[i], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2] || p2;
    const c1 = [p1[0] + (p2[0] - p0[0]) * t, p1[1] + (p2[1] - p0[1]) * t];
    const c2 = [p2[0] - (p3[0] - p1[0]) * t, p2[1] - (p3[1] - p1[1]) * t];
    d += ` C${c1[0].toFixed(1)},${c1[1].toFixed(1)} ${c2[0].toFixed(1)},${c2[1].toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`;
  }
  return d;
}

function spark(vals, { w = 120, h = 36, color = 'var(--ember)', fill = true, id = 'sp' + Math.random().toString(36).slice(2, 7), dot = true } = {}) {
  const min = Math.min(...vals), max = Math.max(...vals), span = max - min || 1;
  const pts = vals.map((v, i) => [2 + i * (w - 4) / (vals.length - 1), 3 + (h - 6) * (1 - (v - min) / span)]);
  const line = smooth(pts);
  const last = pts[pts.length - 1];
  return `<svg class="spark" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" style="height:${h}px">
    <defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity="0.35"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient></defs>
    ${fill ? `<path d="${line} L${last[0]},${h} L${pts[0][0]},${h} Z" fill="url(#${id})"/>` : ''}
    <path d="${line}" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" vector-effect="non-scaling-stroke"/>
    ${dot ? `<circle cx="${last[0]}" cy="${last[1]}" r="2.6" fill="${color}"/>` : ''}
  </svg>`;
}

function niceBounds(min, max, step) {
  const lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step;
  return hi === lo ? [lo - step, hi + step] : [lo, hi];
}

// Line/area chart with y grid, x labels, PR dots and a glowing last point.
function lineChart(vals, { w = 330, h = 170, step = 5, xLabels = [], prs = [], unit = '', color = 'var(--ember)', raw = null, id = 'lc' } = {}) {
  const pl = 2, pr = 34, pt = 12, pb = 22;
  const all = raw ? vals.concat(raw.filter(v => v != null)) : vals;
  const [lo, hi] = niceBounds(Math.min(...all), Math.max(...all), step);
  const X = i => pl + i * (w - pl - pr) / (vals.length - 1);
  const Y = v => pt + (h - pt - pb) * (1 - (v - lo) / (hi - lo));
  const pts = vals.map((v, i) => [X(i), Y(v)]);
  const line = smooth(pts);
  const last = pts[pts.length - 1];
  const ticks = [lo, (lo + hi) / 2, hi];
  return `<svg class="chart" viewBox="0 0 ${w} ${h}">
    <defs>
      <linearGradient id="${id}g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity="0.34"/><stop offset="1" stop-color="${color}" stop-opacity="0"/></linearGradient>
      <filter id="${id}f" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="4"/></filter>
    </defs>
    ${ticks.map(t => `<line x1="${pl}" x2="${w - pr + 6}" y1="${Y(t)}" y2="${Y(t)}" stroke="rgba(255,255,255,0.07)" stroke-dasharray="${t === lo ? '' : '2 4'}"/><text x="${w - pr + 10}" y="${Y(t) + 3.5}">${n1(t)}${unit}</text>`).join('')}
    ${raw ? raw.map((v, i) => v == null ? '' : `<circle cx="${X(i)}" cy="${Y(v)}" r="2.2" fill="rgba(235,235,245,0.32)"/>`).join('') : ''}
    <path d="${line} L${last[0]},${h - pb} L${pts[0][0]},${h - pb} Z" fill="url(#${id}g)"/>
    <path class="draw" d="${line}" fill="none" stroke="${color}" stroke-width="2.5" stroke-linecap="round"/>
    ${prs.map(i => `<circle cx="${pts[i][0]}" cy="${pts[i][1]}" r="3.5" fill="var(--ground)" stroke="${color}" stroke-width="2"/>`).join('')}
    <circle cx="${last[0]}" cy="${last[1]}" r="9" fill="${color}" opacity="0.55" filter="url(#${id}f)"/>
    <circle cx="${last[0]}" cy="${last[1]}" r="4.5" fill="${color}" stroke="var(--ground)" stroke-width="2"/>
    ${xLabels.map(([i, t]) => `<text x="${X(i)}" y="${h - 5}" text-anchor="${i === 0 ? 'start' : i === vals.length - 1 ? 'end' : 'middle'}">${t}</text>`).join('')}
  </svg>`;
}

function ring(pct, { size = 88, sw = 9, color = 'var(--ember)', track = 'rgba(255,255,255,0.08)', segs = null, gap = 0, instant = false } = {}) {
  const r = (size - sw) / 2, c = 2 * Math.PI * r;
  let arcs;
  if (segs) {
    let off = 0;
    arcs = segs.map(([p, col]) => {
      const len = Math.max(0, p * c - gap);
      const el = `<circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="${col}" stroke-width="${sw}" stroke-linecap="round" stroke-dasharray="0 ${c}" data-dash="${len} ${c - len}" stroke-dashoffset="${-off}"/>`;
      off += p * c;
      return el;
    }).join('');
  } else {
    const len = clamp(pct, 0, 1) * c;
    arcs = len <= 0 ? '' : instant
      ? `<circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="${color}" stroke-width="${sw}" stroke-linecap="round" stroke-dasharray="${len} ${c - len}"/>`
      : `<circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="${color}" stroke-width="${sw}" stroke-linecap="round" stroke-dasharray="0 ${c}" data-dash="${len} ${c - len}"/>`;
  }
  return `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" style="transform:rotate(-90deg)"><circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="${track}" stroke-width="${sw}"/>${arcs}</svg>`;
}

const HEAT = ['#2E2E33', '#693927', '#98492B', '#CA592C', '#FF6A2B'];
const setsToLevel = n => n <= 0 ? 0 : n <= 3 ? 1 : n <= 6 ? 2 : n <= 9 ? 3 : 4;
function bodyMap(levels, { h = 220, view = 'both' } = {}) {
  const vb = view === 'front' ? '0 0 100 295' : '0 0 200 295';
  const w = Math.round(h * (view === 'front' ? 100 : 200) / 295);
  const polys = window.MUSCLES.map(([m, pts]) => {
    if (m === 'outline') return `<polygon points="${pts}" fill="rgba(255,255,255,0.05)" stroke="rgba(255,255,255,0.08)" stroke-width="0.6"/>`;
    const lvl = levels[m] || 0;
    return `<polygon points="${pts}" fill="${HEAT[lvl]}" stroke="#0A0A0B" stroke-width="0.8" stroke-linejoin="round"/>`;
  }).join('');
  return `<svg width="${w}" height="${h}" viewBox="${vb}" style="overflow:visible">${polys}</svg>`;
}

// ─────────────────────────── data ───────────────────────────

const ME = 'Kuba', BRO = 'Tomek';
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const TODAY = new Date(2026, 8, 24);

const R = (kind, kg, reps, prev) => ({ kind, kg, reps, prev });
const ROUTINES = {
  pushA: {
    name: 'Push A', day: 'Today 18:00', muscles: { chest: 4, shoulders: 3, triceps: 3 }, last: 'Thu 17 Sep · 61 min · 5 820 kg',
    ex: [
      { id: 'bench', name: 'Bench Press', eq: 'Barbell', muscle: 'Chest', rest: 120, barbell: true, bestE1: 102, sets: [R('W', 60, 10, '60 × 10'), R('N', 82.5, 8, '80 × 8'), R('N', 82.5, 8, '80 × 8'), R('N', 82.5, 8, '80 × 7')], suggest: { from: 80, to: 82.5, last: '8 · 8 · 7' } },
      { id: 'ohp', name: 'Overhead Press', eq: 'Barbell', muscle: 'Shoulders', rest: 120, barbell: true, bestE1: 64.2, sets: [R('N', 50, 8, '50 × 8'), R('N', 50, 8, '50 × 8'), R('N', 50, 7, '50 × 7')] },
      { id: 'incline', name: 'Incline Dumbbell Press', eq: 'Dumbbell', muscle: 'Chest', rest: 90, bestE1: 41, sets: [R('N', 30, 10, '30 × 10'), R('N', 30, 10, '30 × 9'), R('N', 30, 9, '30 × 9')] },
      { id: 'pushdown', name: 'Triceps Pushdown', eq: 'Cable', muscle: 'Triceps', rest: 60, bestE1: 50, sets: [R('N', 35, 12, '35 × 12'), R('N', 35, 12, '35 × 12'), R('N', 35, 11, '35 × 10')] },
      { id: 'lateral', name: 'Lateral Raise', eq: 'Dumbbell', muscle: 'Shoulders', rest: 60, bestE1: 16, sets: [R('N', 10, 15, '10 × 15'), R('N', 10, 15, '10 × 14'), R('N', 10, 14, '10 × 13')] },
    ],
  },
  pullA: {
    name: 'Pull A', muscles: { lats: 4, 'middle back': 3, biceps: 3, 'lower back': 2, traps: 1, forearms: 1 }, last: 'Wed · 2 PRs',
    ex: [
      { id: 'deadlift', name: 'Deadlift', eq: 'Barbell', muscle: 'Lower back', rest: 180, barbell: true, bestE1: 186, sets: [R('N', 160, 5, '155 × 5')] },
      { id: 'row', name: 'Barbell Row', eq: 'Barbell', muscle: 'Middle back', rest: 120, barbell: true, bestE1: 107, sets: [R('N', 85, 8, '82.5 × 8')] },
      { id: 'pullup', name: 'Pull-up', eq: 'Bodyweight', muscle: 'Lats', rest: 120, bestE1: 0, sets: [R('N', 0, 10, '10')] },
      { id: 'facepull', name: 'Face Pull', eq: 'Cable', muscle: 'Shoulders', rest: 60, bestE1: 0, sets: [R('N', 25, 15, '25 × 15')] },
      { id: 'curl', name: 'Hammer Curl', eq: 'Dumbbell', muscle: 'Biceps', rest: 60, bestE1: 0, sets: [R('N', 16, 12, '16 × 12')] },
    ],
  },
  legs: {
    name: 'Legs', muscles: { quadriceps: 4, hamstrings: 3, glutes: 3, calves: 2, adductors: 1 }, last: 'Mon · 64 min',
    ex: [
      { id: 'squat', name: 'Squat', eq: 'Barbell', muscle: 'Quads', rest: 180, barbell: true, bestE1: 131, sets: [R('N', 110, 5, '107.5 × 5')] },
      { id: 'rdl', name: 'Romanian Deadlift', eq: 'Barbell', muscle: 'Hamstrings', rest: 120, bestE1: 0, sets: [R('N', 100, 8, '100 × 8')] },
      { id: 'legpress', name: 'Leg Press', eq: 'Machine', muscle: 'Quads', rest: 120, bestE1: 0, sets: [R('N', 200, 10, '200 × 10')] },
      { id: 'legcurl', name: 'Leg Curl', eq: 'Machine', muscle: 'Hamstrings', rest: 60, bestE1: 0, sets: [R('N', 45, 12, '45 × 12')] },
      { id: 'calf', name: 'Calf Raise', eq: 'Machine', muscle: 'Calves', rest: 60, bestE1: 0, sets: [R('N', 80, 15, '80 × 15')] },
    ],
  },
};

// sets per muscle this week before today's Push A
const WEEK_MUSCLES = { quadriceps: 10, hamstrings: 7, glutes: 7, calves: 4, adductors: 3, abductors: 2, lats: 8, 'middle back': 7, biceps: 5, traps: 3, 'lower back': 5, forearms: 3, abdominals: 2, shoulders: 2 };
const PUSH_ADDS = { chest: 6, shoulders: 6, triceps: 3 };

// e1RM histories: 78 weeks each, ending today
function series(seed, start, end, bumps = 0.9) {
  const rnd = mulberry(seed), n = 78, out = [];
  for (let i = 0; i < n; i++) {
    const p = i / (n - 1);
    const base = start + (end - start) * (1 - Math.pow(1 - p, 1.35));
    const plateau = Math.sin(i / 6.5) * 0.9;
    out.push(Math.round((base + plateau + (rnd() - 0.5) * 2 * bumps) * 2) / 2);
  }
  for (let i = 0; i < n - 1; i++) out[i] = Math.min(out[i], end - 0.5);
  out[n - 1] = end;
  return out;
}
const LIFTS = [
  { id: 'bench', name: 'Bench Press', routine: 'Push A', s: series(7, 71, 102), best: '85 × 6', bestWhen: '17 Sep' },
  { id: 'squat', name: 'Squat', routine: 'Legs', s: series(11, 96, 131, 1.3), best: '115 × 5', bestWhen: '19 Sep' },
  { id: 'deadlift', name: 'Deadlift', routine: 'Pull A', s: series(3, 124, 168, 1.6), best: '160 × 5', bestWhen: '23 Sep' },
  { id: 'ohp', name: 'Overhead Press', routine: 'Push A', s: series(5, 46, 64, 0.7), best: '55 × 5', bestWhen: '10 Sep' },
  { id: 'row', name: 'Barbell Row', routine: 'Pull A', s: series(13, 66, 92, 1), best: '85 × 8', bestWhen: '23 Sep' },
];
const RANGES = { '1M': 5, '3M': 14, '1Y': 53, 'All': 78 };
const RANGE_WORD = { '1M': 'in 1 month', '3M': 'in 3 months', '1Y': 'in a year', 'All': 'all time' };

// body weight: 90 days, ~70 % logged
const BW = (() => {
  const rnd = mulberry(42), raw = [], n = 90;
  for (let i = 0; i < n; i++) {
    const base = 84.7 - 2.3 * (i / (n - 1)) + Math.sin(i / 9) * 0.25;
    raw.push(rnd() < 0.72 || i === n - 1 ? Math.round((base + (rnd() - 0.5) * 1.1) * 10) / 10 : null);
  }
  raw[n - 1] = 82.4;
  const trend = []; let t = raw.find(v => v != null);
  raw.forEach(v => { if (v != null) t = t + 0.12 * (v - t); trend.push(Math.round(t * 100) / 100); });
  return { raw, trend };
})();

const VOLUME_WEEKS = [9.8, 10.4, 11.1, 10.2, 11.9, 12.3, 11.6];

const FOOD_COLORS = { p: 'var(--protein)', c: 'var(--carbs)', f: 'var(--fat)' };
const item = (name, q, k, p, c, f, extra = {}) => ({ name, q, k, p, c, f, ...extra });
const MEALS = {
  breakfast: { name: 'Breakfast', time: '07:40', items: [item('Skyr naturalny', '250 g', 158, 28, 10, 0), item('Oats', '60 g', 228, 8, 39, 4), item('Banana', '120 g', 107, 1.3, 27, 0.4)] },
  lunch: { name: 'Lunch', time: '13:10', items: [item('Chicken rice bowl', '420 g', 650, 48, 78, 14, { ai: true }), item('Olive oil', '1 tbsp · 13 g', 119, 0, 0, 13.5)] },
  snack: { name: 'Snack', time: '16:00', items: [item('Whey shake', '1 scoop · 30 g', 120, 24, 3, 1.5), item('Apple', '180 g', 94, 0.5, 25, 0.3)] },
  dinner: { name: 'Dinner', time: '', items: [] },
};
const GOAL = { k: 2600, p: 180, c: 300, f: 80 };

const AI_ITEMS = () => [
  { name: 'Salmon fillet, baked', g: 160, step: 10, per: { k: 206, p: 20, c: 0, f: 13.4 }, conf: 'hi', box: [36, 26, 34, 30] },
  { name: 'Boiled potatoes', g: 250, step: 10, per: { k: 86, p: 1.9, c: 19.6, f: 0.1 }, conf: 'hi', box: [14, 52, 28, 30] },
  { name: 'Mixed salad', g: 80, step: 10, per: { k: 20, p: 1.2, c: 3.6, f: 0.2 }, conf: 'md', box: [62, 56, 24, 26] },
  { name: 'Olive oil (cooking)', g: 10, step: 5, per: { k: 884, p: 0, c: 0, f: 100 }, conf: 'md', note: 'Added for you · oil is the most-missed item' },
];
const FOODS = [
  { name: 'Serek wiejski lekki', brand: 'Piątnica', g: 200, per: { k: 79, p: 12, c: 2.5, f: 2.5 } },
  { name: 'Chicken breast, grilled', brand: 'Generic · USDA', g: 150, per: { k: 165, p: 31, c: 0, f: 3.6 } },
  { name: 'Skyr naturalny', brand: 'Piątnica', g: 150, per: { k: 63, p: 11, c: 3.8, f: 0 } },
  { name: 'Twaróg półtłusty', brand: 'Mlekovita', g: 100, per: { k: 133, p: 18, c: 3.5, f: 5 } },
  { name: 'Protein bar Cookies', brand: 'Olimp', g: 64, per: { k: 367, p: 31, c: 31, f: 12 } },
  { name: 'Jasmine rice, cooked', brand: 'Generic · USDA', g: 200, per: { k: 129, p: 2.7, c: 28, f: 0.3 } },
  { name: 'Banana', brand: 'Generic · USDA', g: 120, per: { k: 89, p: 1.1, c: 23, f: 0.3 } },
];

// ─────────────────────────── state ───────────────────────────

const S = {
  tab: 'today',
  imIn: false,
  out: null,               // { reason, makeup }
  todayDone: null,         // summary of finished workout
  workout: null,
  rest: null,
  toastTimer: 0,
  fuelLeft: true,
  meals: MEALS,
  prog: { seg: 'lifts', range: '3M', lift: 'bench' },
  bw: BW,
  sheet: null,
  broLog: [
    { when: 'Wed 23', icon: 'x', tone: 'bad', title: `${BRO} missed Pull A`, quote: '“Sick, sorry bro. Saturday for sure.”' },
    { when: 'Mon 21', icon: 'check', tone: 'good', title: 'Legs · both showed up', meta: `You 17:58 · ${BRO} 18:03` },
    { when: 'Sat 19', icon: 'trophy', tone: 'ember', title: 'Legs · both showed up', meta: `${BRO} hit a squat PR · 140 × 3` },
    { when: 'Thu 17', icon: 'trophy', tone: 'ember', title: 'Push A · both showed up', meta: 'You hit a bench PR · 85 × 6' },
    { when: 'Wed 16', icon: 'check', tone: 'good', title: 'Pull A · both showed up', meta: `You 18:02 · ${BRO} 17:55` },
  ],
  history: [
    { week: 'This week', items: [
      { name: 'Pull A', when: 'Wed 23 Sep', min: 58, kg: 6840, prs: 2 },
      { name: 'Legs', when: 'Mon 21 Sep', min: 64, kg: 6640, prs: 0 },
    ] },
    { week: 'Last week', items: [
      { name: 'Legs', when: 'Sat 19 Sep', min: 66, kg: 6910, prs: 0 },
      { name: 'Push A', when: 'Thu 17 Sep', min: 61, kg: 5820, prs: 1 },
      { name: 'Pull A', when: 'Wed 16 Sep', min: 57, kg: 6480, prs: 0 },
      { name: 'Legs', when: 'Mon 14 Sep', min: 63, kg: 6590, prs: 0 },
    ] },
  ],
};

const totals = () => {
  const t = { k: 0, p: 0, c: 0, f: 0 };
  Object.values(S.meals).forEach(m => m.items.forEach(i => { t.k += i.k; t.p += i.p; t.c += i.c; t.f += i.f; }));
  return t;
};
const weekMuscles = () => {
  const m = { ...WEEK_MUSCLES };
  if (S.todayDone) Object.entries(PUSH_ADDS).forEach(([k, v]) => { m[k] = (m[k] || 0) + v; });
  return m;
};
const weekVolume = () => 13480 + (S.todayDone ? S.todayDone.volume : 0);

// ─────────────────────────── shell ───────────────────────────

const app = document.getElementById('app');
app.innerHTML = `
  <div class="tabs-host">
    <main class="screen" id="s-today"></main>
    <main class="screen" id="s-train"></main>
    <main class="screen fuel-screen" id="s-fuel"></main>
    <main class="screen" id="s-progress"></main>
    <main class="screen" id="s-bro"></main>
  </div>
  <div class="accessory" id="acc-fuel" style="display:none"></div>
  <div class="accessory" id="acc-mini" style="display:none"></div>
  <nav class="tabbar" id="tabbar">
    <div class="pill" id="tabpill"></div>
    ${[['today', 'home', 'Today'], ['train', 'train', 'Train'], ['fuel', 'fuel', 'Fuel'], ['progress', 'progress', 'Progress'], ['bro', 'bro', 'Bro']]
      .map(([id, icon, label]) => `<button data-tab="${id}" aria-label="${label}">${ic(icon, 24, id === 'train' ? 2.6 : 2)}<span>${label}</span>${id === 'bro' ? '<i class="badge" id="bro-badge"></i>' : ''}</button>`).join('')}
  </nav>
  <section class="cover" id="cover" aria-label="Workout"></section>
  <div class="rest glass" id="rest"></div>
  <div class="scrim" id="scrim"></div>
  <section class="sheet" id="sheet"></section>
  <div class="toast glass" id="toast"></div>
  <div class="edge-top"></div>
`;

const RENDER = { today: renderToday, train: renderTrain, fuel: renderFuel, progress: renderProgress, bro: renderBro };

function render(tab, { animate = false } = {}) {
  const el = document.getElementById('s-' + tab);
  const top = el.scrollTop;
  el.innerHTML = RENDER[tab]();
  el.scrollTop = top;
  grow(el, animate);
}
function renderAll() { Object.keys(RENDER).forEach(t => render(t)); renderAccessories(); }

// bars/rings: grow from zero on request (tab enter), otherwise snap to value
function grow(root, animate = true) {
  const bars = $$('[data-w]', root), arcs = $$('[data-dash]', root);
  if (!animate) {
    bars.forEach(b => { b.style.transition = 'none'; b.style.width = b.dataset.w; });
    arcs.forEach(a => { a.style.transition = 'none'; a.setAttribute('stroke-dasharray', a.dataset.dash); });
    root.offsetWidth;
    bars.forEach(b => { b.style.transition = ''; });
    arcs.forEach(a => { a.style.transition = ''; });
    return;
  }
  root.offsetWidth;
  requestAnimationFrame(() => requestAnimationFrame(() => {
    bars.forEach(b => { b.style.width = b.dataset.w; });
    arcs.forEach(a => { a.setAttribute('stroke-dasharray', a.dataset.dash); });
  }));
}

function setTab(tab, push = true) {
  S.tab = tab;
  $$('.screen').forEach(s => s.classList.toggle('on', s.id === 's-' + tab));
  $$('#tabbar button').forEach((b, i) => {
    const on = b.dataset.tab === tab;
    b.classList.toggle('on', on);
    if (on) { const pill = $('#tabpill'); pill.style.width = b.offsetWidth + 'px'; pill.style.transform = `translateX(${b.offsetLeft - 4}px)`; }
  });
  render(tab, { animate: true });
  renderAccessories();
  if (push && !EMBED) history.replaceState(null, '', '#' + tab);
}

// ─────────────────────────── Today ───────────────────────────

function weekDays() {
  const done = !!S.todayDone, out = !!S.out;
  return [
    { dl: 'M', d: 21, st: 'done', k: 'k', t: 't' },
    { dl: 'T', d: 22, st: 'off' },
    { dl: 'W', d: 23, st: 'done', k: 'k', t: 'x' },
    { dl: 'T', d: 24, st: done ? 'done' : out ? 'missed' : 'planned', today: true, k: done ? 'k' : out ? 'x' : null, t: null },
    { dl: 'F', d: 25, st: S.out && S.out.makeup === 'Fri 25' ? 'planned' : 'off' },
    { dl: 'S', d: 26, st: 'planned' },
    { dl: 'S', d: 27, st: S.out && S.out.makeup === 'Sun 27' ? 'planned' : 'off' },
  ];
}

function weekStrip() {
  const days = weekDays();
  const doneCount = days.filter(d => d.st === 'done').length;
  const planned = days.filter(d => d.st !== 'off').length;
  return `
    <div class="week">${days.map(d => `
      <div class="day ${d.st} ${d.today ? 'today' : ''}">
        <span class="dl">${d.dl}</span>
        <div class="dd num">${d.st === 'done' ? ic('check', 18, 3) : d.d}</div>
        <div class="bros">${d.k ? `<i class="${d.k}"></i>` : ''}${d.t ? `<i class="${d.t}"></i>` : ''}</div>
      </div>`).join('')}
    </div>
    <div class="week-legend"><span><i style="background:var(--ember)"></i>You</span><span><i style="background:var(--good)"></i>${BRO}</span><span><i style="background:var(--bad)"></i>Missed</span><span style="margin-left:auto" class="num">${doneCount} of ${planned} this week</span></div>`;
}

function heroToday() {
  const w = S.workout;
  if (w && !w.summary) {
    const done = w.ex.reduce((a, e) => a + e.sets.filter(s => s.done).length, 0);
    const all = w.ex.reduce((a, e) => a + e.sets.length, 0);
    return `
    <section class="card hero pressable" data-act="open-workout">
      <div class="flex between center"><span class="eyebrow ember"><span class="live-dot" style="background:var(--ember);margin-right:7px;vertical-align:0"></span>Workout in progress</span><span class="cap ink2">${w.name}</span></div>
      <div class="next-time"><span class="display" data-bind="elapsed">${clock(Math.floor((Date.now() - w.startedAt) / 1000))}</span>
        <div style="padding-bottom:8px"><div class="hl">${w.ex[w.cur].name}</div><div class="sub ink2">${done} of ${all} sets</div></div></div>
      <div class="bar mt16"><i data-w="${Math.round(done / all * 100)}%" style="background:var(--ember)"></i></div>
      <div class="btn-row mt16"><button class="btn ember" data-act="open-workout">${ic('play', 18)} Resume</button></div>
    </section>`;
  }
  if (S.todayDone) {
    const d = S.todayDone;
    return `
    <section class="card hero">
      <div class="flex between center"><span class="eyebrow good">${ic('check', 12, 3.2).replace('<svg', '<svg style="display:inline;vertical-align:-1px;margin-right:4px"')}Done today</span><span class="cap ink2">Push A</span></div>
      <div class="next-time"><span class="display">${d.min}<span style="font-size:40px"> min</span></span>
        <div style="padding-bottom:8px"><div class="hl num">${n0(d.volume)} kg</div><div class="sub ink2">${d.sets} sets · ${d.prs.length} PR${d.prs.length === 1 ? '' : 's'}</div></div></div>
      <div class="hair mt16"></div>
      <div class="duo-row"><div class="avatars"><span class="avatar sm me ring-good">K</span><span class="avatar sm bro ring-good">T</span></div>
        <div class="grow"><div class="sub bold">Both showed up</div><div class="fn ink2">Next: Sat 26 · 18:00 · Legs</div></div></div>
    </section>`;
  }
  if (S.out) {
    return `
    <section class="card">
      <div class="flex between center"><span class="eyebrow" style="color:#FF6B88">You're out today</span><span class="cap ink2">Push A</span></div>
      <div class="t2 mt12">${BRO} knows.</div>
      <div class="sub ink2 mt4">${S.out.reason ? S.out.reason + ' · ' : ''}${S.out.makeup === 'skip' ? 'No make-up day' : `Make-up on ${S.out.makeup} at 18:00`}</div>
      <div class="btn-row mt16"><button class="btn secondary" data-act="undo-out">${ic('undo', 18)} I can make it after all</button></div>
    </section>`;
  }
  const both = S.imIn;
  return `
    <section class="card hero">
      <div class="flex between center"><span class="eyebrow ember">Next session</span><span class="chip quiet" style="height:26px">Push A · ~60 min</span></div>
      <div class="next-time"><span class="display">18:00</span>
        <div style="padding-bottom:8px"><div class="hl">Today</div><div class="sub ink2">in 8 h 19 min</div></div></div>
      <div class="preview-ex">${ROUTINES.pushA.ex.slice(0, 3).map(e => `<span class="chip">${e.name}</span>`).join('')}<span class="chip">+2</span></div>
      <div class="hair mt16"></div>
      <div class="duo-row">
        <div class="avatars"><span class="avatar sm me ${both ? 'ring-good' : 'ring-ember'}">K</span><span class="avatar sm bro ring-good">T</span></div>
        <div class="grow"><div class="sub bold">${both ? 'Both in. See you at 18:00.' : `${BRO}'s in. You?`}</div><div class="fn ink2">${both ? `${BRO} gets a push now` : `${BRO} confirmed at 12:40`}</div></div>
        <span class="live-dot"></span>
      </div>
      ${both
        ? `<div class="btn-row"><button class="btn ember" style="flex:2.2" data-act="start" data-r="pushA">${ic('play', 18)} Start workout</button><button class="btn secondary" data-act="cant">Can't</button></div>`
        : `<div class="btn-row"><button class="btn primary" data-act="imin">I'm in</button><button class="btn secondary" data-act="cant">Can't make it</button></div>`}
    </section>`;
}

function renderToday() {
  const t = totals();
  const left = GOAL.k - t.k;
  const bwVals = S.bw.trend.slice(-30);
  const bwNow = S.bw.raw[S.bw.raw.length - 1];
  const bwRate = (S.bw.trend[S.bw.trend.length - 1] - S.bw.trend[S.bw.trend.length - 29]) / 4;
  const days = [6640, 0, 6840, S.todayDone ? S.todayDone.volume : 0, 0, 0, 0];
  const dmax = Math.max(...days, 7000);
  return `
    <header class="hdr">
      <div><span class="eyebrow">Thursday, 24 September</span><h1 class="lt">Today</h1></div>
      <div class="hdr-actions"><button class="streak pressable" data-act="toast" data-msg="6 weeks in a row hitting every planned session">${ic('flame', 16)}6</button><button class="avatar me pressable" data-act="toast" data-msg="Settings live behind your avatar">K</button></div>
    </header>
    ${weekStrip()}
    <div class="mt20">${heroToday()}</div>

    <div class="section-h"><span class="hl">Fuel</span><button class="link" data-tab="fuel">${n0(t.k)} / ${n0(GOAL.k)} kcal ${ic('chevR', 16)}</button></div>
    <section class="card pressable" data-tab="fuel" style="padding:16px">
      <div class="fuel-strip">
        <div style="position:relative;width:88px;height:88px;flex:none">${ring(0, { size: 88, sw: 9, segs: [[t.p * 4 / GOAL.k, 'var(--protein)'], [t.c * 4 / GOAL.k, 'var(--carbs)'], [t.f * 9 / GOAL.k, 'var(--fat)']], gap: 3 })}
          <div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center"><span class="display" style="font-size:28px">${n0(Math.max(0, left))}</span><span class="eyebrow" style="font-size:9px;margin-top:2px">kcal left</span></div></div>
        <div class="macro-mini">
          ${[['Protein', t.p, GOAL.p, 'var(--protein)'], ['Carbs', t.c, GOAL.c, 'var(--carbs)'], ['Fat', t.f, GOAL.f, 'var(--fat)']].map(([l, v, g, col]) => `
          <div class="m"><span class="ink2">${l}</span><div class="bar"><i data-w="${Math.min(100, v / g * 100)}%" style="background:${col}"></i></div><span class="num" style="font-weight:600">${Math.round(v)}<span class="ink3">/${g}</span></span></div>`).join('')}
        </div>
      </div>
      ${S.meals.dinner.items.length ? '' : `<div class="flex center gap8 mt12 fn" style="color:var(--protein)">${ic('bolt', 14)}<span>${Math.round(GOAL.p - t.p)} g protein to go · dinner after training</span></div>`}
    </section>

    <div class="row2 mt12">
      <section class="tile pressable" data-act="go-body">
        <div class="eyebrow">Body weight</div>
        <div class="flex center gap6 mt8" style="align-items:baseline"><span class="display" style="font-size:34px">${n1(bwNow)}</span><span class="fn ink2">kg</span></div>
        <div class="fn ink2 mt4 num">${bwRate < 0 ? '↓' : '↑'} ${Math.abs(bwRate).toFixed(1)} kg / week</div>
        <div class="mt8">${spark(bwVals, { h: 32, color: 'rgba(235,235,245,0.55)', dot: true })}</div>
      </section>
      <section class="tile pressable" data-tab="progress">
        <div class="eyebrow">Volume · week</div>
        <div class="flex center gap6 mt8" style="align-items:baseline"><span class="display" style="font-size:34px">${n1(weekVolume() / 1000)}</span><span class="fn ink2">t</span></div>
        <div class="fn mt4 num" style="color:${S.todayDone ? 'var(--good)' : 'var(--ink-2)'}">${S.todayDone ? `↑ ${Math.round((weekVolume() / 11600 - 1) * 100)}% vs last week` : '2 of 4 sessions in'}</div>
        <div class="flex gap6 mt8" style="align-items:flex-end;height:32px">${days.map((v, i) => `<i style="flex:1;border-radius:3px;height:${Math.max(4, v / dmax * 32)}px;background:${i === 3 ? 'var(--ember)' : v ? 'rgba(255,255,255,0.28)' : 'rgba(255,255,255,0.08)'}"></i>`).join('')}</div>
      </section>
    </div>

    <div class="section-h"><span class="hl">Last session</span><button class="link" data-tab="train">History ${ic('chevR', 16)}</button></div>
    ${lastSession()}
  `;
}

function lastSession() {
  const d = S.todayDone;
  const head = d
    ? `<div class="li"><div class="lead">${ic('train', 20, 2.6)}</div><div class="grow"><div class="title">Push A</div><div class="meta num">Today · ${d.min} min · ${n0(d.volume)} kg</div></div>${d.prs.length ? `<span class="chip ember">${ic('trophy', 14)} ${d.prs.length} PR${d.prs.length > 1 ? 's' : ''}</span>` : ''}</div>`
    : `<div class="li"><div class="lead">${ic('train', 20, 2.6)}</div><div class="grow"><div class="title">Pull A</div><div class="meta">Wed 23 Sep · 58 min · 6${NB}840 kg</div></div><span class="chip ember">${ic('trophy', 14)} 2 PRs</span></div>`;
  const prs = d
    ? d.prs.map(p => [`${p.ex} · ${n1(p.kg)} kg × ${p.reps}`, `Best estimated 1RM · ${n1(p.e1)} kg (+${n1(p.d)})`])
    : [['Deadlift · 160 kg × 5', 'Heaviest set ever · +5 kg'], ['Barbell Row · 85 kg × 8', 'Best estimated 1RM · 107.7 kg']];
  return `<section class="list">${head}${prs.map(([t, m]) => `<div class="li"><div class="lead" style="background:var(--ember-tint);color:var(--ember)">${ic('trophy', 18)}</div><div class="grow"><div class="title">${t}</div><div class="meta">${m}</div></div></div>`).join('')}</section>`;
}

// ─────────────────────────── Train ───────────────────────────

function renderTrain() {
  const r = ROUTINES.pushA;
  const w = S.workout;
  const hero = S.todayDone ? `
    <section class="card">
      <div class="flex between center"><span class="eyebrow good">Done today</span><span class="cap ink2">Next · Sat 26 · 18:00</span></div>
      <div class="flex between mt12" style="align-items:center"><div><div class="t2">Legs</div><div class="sub ink2 mt4">5 exercises · 15 sets · ~65 min</div></div>${bodyMap(levelsFrom(ROUTINES.legs.muscles), { h: 86 })}</div>
    </section>` : `
    <section class="card hero">
      <div class="flex between center"><span class="eyebrow ember">${w && !w.summary ? 'In progress' : 'Up next · Today 18:00'}</span><button class="icon-btn" style="width:32px;height:32px" data-act="toast" data-msg="Edit routine: reorder, swap, set targets">${ic('more', 18)}</button></div>
      <div class="flex between mt8" style="align-items:center;gap:8px">
        <div><div class="t2" style="font-size:28px;line-height:34px">${r.name}</div><div class="sub ink2 mt4">5 exercises · 16 sets · ~60 min</div></div>
        ${bodyMap(levelsFrom(r.muscles), { h: 92 })}
      </div>
      <div class="mt12">${r.ex.map((e, i) => `
        <div class="flex center gap10" style="padding:7px 0;${i ? 'border-top:1px solid var(--line)' : ''}">
          <span class="num ink3" style="width:16px;font-size:13px;font-weight:700">${i + 1}</span>
          <span class="sub grow" style="font-weight:500">${e.name}</span>
          <span class="fn ink2 num">${e.sets.filter(s => s.kind !== 'W').length} × ${e.sets[e.sets.length - 1].reps}</span>
          <span class="fn num nowrap" style="min-width:58px;text-align:right;${e.suggest ? 'color:var(--ember-hi);font-weight:600' : 'color:var(--ink-2)'}">${e.suggest ? '↑ ' : ''}${n1(e.sets[e.sets.length - 1].kg)} kg</span>
        </div>`).join('')}</div>
      <button class="btn ember block mt16" data-act="${w && !w.summary ? 'open-workout' : 'start'}" data-r="pushA">${ic('play', 18)} ${w && !w.summary ? 'Resume workout' : 'Start workout'}</button>
      <div class="fn ink3 mt12" style="text-align:center">Last time · ${r.last}</div>
    </section>`;

  return `
    <header class="hdr">
      <div><span class="eyebrow">Week 39 · ${S.todayDone ? 3 : 2} of 4 done</span><h1 class="lt">Train</h1></div>
      <div class="hdr-actions"><button class="icon-btn" data-act="toast" data-msg="New routine">${ic('plus', 20)}</button></div>
    </header>
    <div class="mt12">${hero}</div>

    <div class="section-h"><span class="hl">Routines</span><span class="link">3</span></div>
    <div class="hscroll">
      ${['pullA', 'legs', 'pushA'].filter(k => S.todayDone ? k !== 'legs' : k !== 'pushA').map(k => {
        const rr = ROUTINES[k];
        return `<section class="tile routine-card">
          <div class="flex between" style="align-items:flex-start"><div><div class="t3">${rr.name}</div><div class="fn ink2 mt4">${rr.ex.length} exercises</div></div>${bodyMap(levelsFrom(rr.muscles), { h: 64 })}</div>
          <div class="fn ink2" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${rr.ex.map(e => e.name).join(' · ')}</div>
          <div class="flex between center" style="margin-top:auto"><span class="cap ink3">${rr.last}</span><button class="play pressable" data-act="start" data-r="${k}" aria-label="Start ${rr.name}">${ic('play', 18)}</button></div>
        </section>`;
      }).join('')}
      <button class="tile routine-card" style="width:150px;align-items:center;justify-content:center;box-shadow:inset 0 0 0 1.5px rgba(255,255,255,0.1);background:transparent;color:var(--ink-2)" data-act="start-empty">
        <span class="icon-btn">${ic('plus', 20)}</span><span class="sub bold" style="color:var(--ink)">Empty workout</span><span class="fn">Log as you go</span>
      </button>
    </div>

    <div class="section-h"><span class="hl">History</span><span class="link">${S.history.reduce((a, g) => a + g.items.length, 0)} sessions</span></div>
    ${S.history.map(g => `
      <div class="week-label"><span>${g.week}</span><span class="num">${n1(g.items.reduce((a, i) => a + i.kg, 0) / 1000)} t</span></div>
      <section class="list">${g.items.map(i => `
        <div class="li pressable">
          <div class="lead" style="flex-direction:column;gap:0;width:44px"><span class="cap ink3" style="font-size:10px;letter-spacing:.06em;text-transform:uppercase">${i.when.slice(0, 3)}</span><span class="hl num" style="line-height:18px">${i.when.split(' ')[1]}</span></div>
          <div class="grow"><div class="title">${i.name}</div><div class="meta num">${i.min} min · ${n0(i.kg)} kg</div></div>
          ${i.prs ? `<span class="chip ember">${ic('trophy', 14)} ${i.prs}</span>` : ''}
          <span class="chev">${ic('chevR', 18)}</span>
        </div>`).join('')}
      </section>`).join('')}
  `;
}

function levelsFrom(m) { const o = {}; Object.entries(m).forEach(([k, v]) => { o[k] = v; }); return o; }

// ─────────────────────────── Workout cover ───────────────────────────

function startWorkout(key) {
  const r = ROUTINES[key] || { name: 'Workout', ex: [] };
  S.workout = {
    key, name: r.name, startedAt: Date.now(), cur: 0, minimized: false, summary: false,
    ex: r.ex.map(e => ({ ...e, sets: e.sets.map(s => ({ ...s, done: false, pr: 0 })), suggestOn: !!e.suggest })),
  };
  S.rest = null;
  openCover();
}

function openCover() {
  const w = S.workout; if (!w) return;
  w.minimized = false;
  renderCover();
  $('#cover').classList.add('on');
  $('#tabbar').classList.add('hidden');
  renderAccessories();
  renderRest();
}
function minimize() {
  S.workout.minimized = true;
  $('#cover').classList.remove('on');
  $('#tabbar').classList.remove('hidden');
  renderRest();
  render(S.tab);
  renderAccessories();
}

function recomputePRs(e) {
  let best = e.bestE1;
  e.sets.forEach(s => {
    s.pr = 0;
    if (s.done && s.kind !== 'W' && s.kg > 0) {
      const v = e1rm(s.kg, s.reps);
      if (best > 0 && v > best + 0.05) { s.pr = v - best; best = v; }
    }
  });
  e.best = best;
}

function plates(kg) {
  let side = (kg - 20) / 2; const out = [];
  [25, 20, 15, 10, 5, 2.5, 1.25].forEach(p => { while (side >= p - 1e-9) { out.push(p); side -= p; } });
  return out;
}
const PLATE_STYLE = { 25: ['#E5484D', 34], 20: ['#3E7BFA', 34], 15: ['#F5C04A', 30], 10: ['#30A46C', 26], 5: ['#E6E6EA', 21], 2.5: ['#56565C', 17], 1.25: ['#9A9AA2', 13] };

function exCard(e, i) {
  const w = S.workout;
  const cur = i === w.cur;
  const doneN = e.sets.filter(s => s.done).length;
  if (!cur) {
    const top = e.sets[e.sets.length - 1];
    const pct = doneN / e.sets.length;
    return `<section class="ex-card collapsed pressable" data-act="focus-ex" data-i="${i}">
      <div style="position:relative;width:36px;height:36px">${ring(pct, { size: 36, sw: 4, color: pct === 1 ? 'var(--good)' : 'var(--ember)', instant: true })}
        <span style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:700" class="num">${pct === 1 ? ic('check', 14, 3) : `${doneN}/${e.sets.length}`}</span></div>
      <div class="grow"><div class="hl">${e.name}</div><div class="fn ink2 num">${e.sets.length} sets · ${(top.kg ? n1(top.kg) + ' kg × ' : '')}${top.reps}${e.sets.some(s => s.pr) ? ' · <span style="color:var(--ember)">PR</span>' : ''}</div></div>
      <span class="chev">${ic('chevR', 18)}</span>
    </section>`;
  }
  const nextIdx = e.sets.findIndex(s => !s.done);
  const curSet = e.sets[nextIdx === -1 ? e.sets.length - 1 : nextIdx];
  let n = 0;
  return `<section class="ex-card cur" id="ex-${i}">
    <div class="ex-head">
      <div class="grow"><div class="ex-name">${e.name}</div><div class="fn ink2 mt4">${e.eq} · ${e.muscle} · best e1RM <span class="num">${n1(e.best || e.bestE1)} kg</span></div></div>
      <button class="icon-btn" style="width:36px;height:36px" data-act="toast" data-msg="Swap exercise, reorder, rest time, notes">${ic('more', 18)}</button>
    </div>
    ${e.suggest && e.suggestOn ? `<div class="suggest">${ic('up', 18, 2.4).replace('<svg', '<svg style="color:var(--ember)"')}<div class="grow">You hit <b>${e.suggest.last}</b> at ${e.suggest.from} kg last time. Today's sets start at <b>${e.suggest.to} kg</b>.</div><button class="btn xs secondary" data-act="suggest-undo" data-i="${i}">Undo</button></div>` : ''}
    <div class="set-head"><span>Set</span><span>Previous</span><span>kg</span><span>Reps</span><span></span></div>
    ${e.sets.map((s, si) => {
      const label = s.kind === 'W' ? 'W' : String(++n);
      const isCur = si === nextIdx;
      return `<div class="set-row ${s.done ? 'done' : ''} ${isCur ? 'cur' : ''}" data-i="${i}" data-si="${si}">
        <span class="n ${s.kind === 'W' ? 'w' : ''}">${label}</span>
        <span class="prev">${s.prev}</span>
        <input type="number" inputmode="decimal" step="2.5" value="${s.kg}" data-f="kg" aria-label="Weight, set ${label}">
        <input type="number" inputmode="numeric" value="${s.reps}" data-f="reps" aria-label="Reps, set ${label}">
        <button class="tick" data-act="tick" data-i="${i}" data-si="${si}" aria-label="Complete set ${label}">${ic('check', 20, 3)}</button>
        ${s.pr ? `<span class="pr-badge">${ic('trophy', 10, 2.6)}PR +${n1(s.pr)} e1RM</span>` : ''}
      </div>`;
    }).join('')}
    <button class="add-set" data-act="add-set" data-i="${i}">${ic('plus', 18)} Add set</button>
    ${e.barbell && curSet.kg > 20 ? (() => {
      const ps = plates(curSet.kg);
      return `<div class="plates"><div class="bar-viz"><span class="sleeve" style="width:14px"></span><span class="collar"></span>${ps.map(p => `<span class="plate" style="background:${PLATE_STYLE[p][0]};height:${PLATE_STYLE[p][1]}px"></span>`).join('')}<span class="sleeve" style="width:22px"></span></div><div class="grow"><div style="color:var(--ink);font-weight:600" class="num">${n1(curSet.kg)} kg</div><div class="num">per side ${ps.join(' + ')}</div></div></div>`;
    })() : ''}
  </section>`;
}

function renderCover() {
  const w = S.workout; if (!w) return;
  const el = $('#cover');
  const sc = $('.scroll', el);
  const top = sc ? sc.scrollTop : 0;
  if (w.summary) { el.innerHTML = summaryHTML(); grow(el); return; }
  el.innerHTML = `
    <div class="wk-top">
      <button class="icon-btn" data-act="minimize" aria-label="Minimize">${ic('chevD', 22)}</button>
      <div class="grow"><div class="wk-title">${w.name}</div><div class="wk-clock"><i></i><span data-bind="elapsed">${clock(Math.floor((Date.now() - w.startedAt) / 1000))}</span></div></div>
      <button class="btn sm primary" data-act="finish">Finish</button>
    </div>
    <div class="rail">${w.ex.map((e, i) => {
      const pct = e.sets.filter(s => s.done).length / e.sets.length * 100;
      return `<button class="${i === w.cur ? 'cur' : ''}" data-act="focus-ex" data-i="${i}"><b><i style="width:${pct}%"></i></b><span>${e.name.split(' ')[0]}</span></button>`;
    }).join('')}</div>
    <div class="scroll">
      <div class="bro-live"><span class="avatar xs bro">T</span><div class="grow"><b>${BRO}</b> <span class="ink2">started Legs · 6 min ago</span></div><span class="live-dot"></span></div>
      ${w.ex.map((e, i) => exCard(e, i)).join('')}
      <div style="padding:4px 12px 0"><button class="btn ghost block" data-act="toast" data-msg="Exercise picker: search 876 exercises, EN/PL">${ic('plus', 18)} Add exercise</button></div>
    </div>`;
  const nsc = $('.scroll', el); nsc.scrollTop = top;
}

function summaryHTML() {
  const w = S.workout;
  const sum = workoutSummary();
  return `
    <div class="wk-top"><span style="width:40px"></span><div class="grow"><div class="wk-title">Workout saved</div></div><button class="icon-btn" data-act="share" aria-label="Share">${ic('share', 20)}</button></div>
    <div class="scroll">
      <div class="summary-hero">
        <div class="medal">${ic('trophy', 44, 2)}</div>
        <div class="eyebrow ember">${w.name} · Thursday</div>
        <div class="display mt12">${sum.min}<span style="font-size:48px"> min</span></div>
        <div class="sub ink2 mt8">${sum.prs.length ? `${sum.prs.length} new record${sum.prs.length > 1 ? 's' : ''}. That's how it's done.` : 'Every set logged. Same time Saturday.'}</div>
      </div>
      <div class="stat3">
        <div class="tile"><div class="eyebrow">Volume</div><div class="v">${n0(sum.volume)}<span class="fn ink2"> kg</span></div></div>
        <div class="tile"><div class="eyebrow">Sets</div><div class="v">${sum.sets}</div></div>
        <div class="tile"><div class="eyebrow">Records</div><div class="v" style="color:var(--ember)">${sum.prs.length}</div></div>
      </div>
      ${sum.prs.length ? `<div class="section-h"><span class="hl">Records</span></div>
      <section class="list">${sum.prs.map(p => `<div class="li"><div class="lead" style="background:var(--ember-tint);color:var(--ember)">${ic('trophy', 18)}</div><div class="grow"><div class="title">${p.ex} · ${n1(p.kg)} kg × ${p.reps}</div><div class="meta">Best estimated 1RM · ${n1(p.e1)} kg (+${n1(p.d)})</div></div></div>`).join('')}</section>` : ''}
      <div class="section-h"><span class="hl">Worked</span><span class="link">this session</span></div>
      <section class="card flex center" style="justify-content:space-around">${bodyMap(levelsFrom(PUSH_ADDS_LVL), { h: 170 })}
        <div class="muscle-list">${[['Chest', 6], ['Shoulders', 6], ['Triceps', 3]].map(([m, v]) => `<div class="m"><span>${m}</span><span class="num ink2">${v} sets</span><div class="bar"><i data-w="${v / 6 * 100}%" style="background:var(--ember)"></i></div></div>`).join('')}</div>
      </section>
      <section class="card mt12 flex center gap12" style="padding:14px 16px"><div class="avatars"><span class="avatar sm me">K</span><span class="avatar sm bro">T</span></div><div class="grow fn ink2">${BRO} sees this in your shared log. He's still on Legs.</div></section>
      <div class="pad mt16 btn-row"><button class="btn secondary" data-act="share">${ic('share', 18)} Share</button><button class="btn primary" data-act="done">Done</button></div>
    </div>`;
}
const PUSH_ADDS_LVL = { chest: 3, shoulders: 3, triceps: 2 };

function workoutSummary() {
  const w = S.workout;
  let volume = 0, sets = 0; const prs = [];
  w.ex.forEach(e => e.sets.forEach(s => {
    if (!s.done) return;
    sets++;
    if (s.kind !== 'W') volume += s.kg * s.reps;
    if (s.pr) prs.push({ ex: e.name, kg: s.kg, reps: s.reps, e1: e1rm(s.kg, s.reps), d: s.pr });
  }));
  const min = Math.max(1, Math.round((Date.now() - w.startedAt) / 60000));
  return { volume, sets, prs, min: w.demoMin || min };
}

function tick(i, si, btn) {
  const w = S.workout, e = w.ex[i], s = e.sets[si];
  s.done = !s.done;
  recomputePRs(e);
  if (s.done) {
    if (s.pr) {
      showToast('pr', `<div class="grow"><div class="hl">New record · ${e.name}</div><div class="fn ink2 num">${n1(s.kg)} kg × ${s.reps} → e1RM ${n1(e1rm(s.kg, s.reps))} kg (+${n1(s.pr)})</div></div>`, 3200);
      burst(btn);
    }
    const nextIdx = e.sets.findIndex(x => !x.done);
    let nextLabel;
    if (nextIdx === -1) {
      const ni = w.ex.findIndex((x, k) => k > i && x.sets.some(y => !y.done));
      if (ni !== -1) { w.cur = ni; nextLabel = w.ex[ni].name; }
      else nextLabel = 'Finish when ready';
    } else {
      const ns = e.sets[nextIdx];
      nextLabel = `${n1(ns.kg)} kg × ${ns.reps}`;
    }
    startRest(s.kind === 'W' ? 60 : e.rest, nextLabel);
  }
  renderCover();
  const row = $(`.set-row[data-i="${i}"][data-si="${si}"] .tick`);
  if (row && s.done) row.classList.add('pop');
}

function burst(anchor) {
  const phone = $('#phone');
  const pr = phone.getBoundingClientRect();
  const r = anchor ? anchor.getBoundingClientRect() : { left: pr.left + pr.width / 2, top: pr.top + 200, width: 0, height: 0 };
  const scale = pr.width / phone.offsetWidth;
  const b = document.createElement('div');
  b.className = 'spark-burst';
  b.style.left = ((r.left - pr.left + r.width / 2) / scale) + 'px';
  b.style.top = ((r.top - pr.top + r.height / 2) / scale) + 'px';
  const cols = ['#FF6A2B', '#FF8A55', '#FFC29C', '#F5C04A', '#fff'];
  for (let k = 0; k < 22; k++) {
    const a = Math.random() * Math.PI * 2, d = 40 + Math.random() * 70;
    const i = document.createElement('i');
    i.style.background = cols[k % cols.length];
    i.style.setProperty('--dx', Math.cos(a) * d + 'px');
    i.style.setProperty('--dy', Math.sin(a) * d - 20 + 'px');
    i.style.setProperty('--rot', (Math.random() * 540 - 270) + 'deg');
    i.style.animationDelay = Math.random() * 0.08 + 's';
    b.appendChild(i);
  }
  phone.appendChild(b);
  setTimeout(() => b.remove(), 1200);
}

// rest timer
function startRest(sec, next) { S.rest = { end: Date.now() + sec * 1000, total: sec, next }; renderRest(); }
function renderRest() {
  const el = $('#rest');
  const w = S.workout;
  const show = S.rest && w && !w.minimized && !w.summary;
  el.classList.toggle('on', !!show);
  if (!S.rest) return;
  const left = Math.max(0, Math.ceil((S.rest.end - Date.now()) / 1000));
  const size = 50, sw = 5, r = (size - sw) / 2, c = 2 * Math.PI * r;
  el.innerHTML = `
    <div class="ringwrap"><svg width="${size}" height="${size}" style="transform:rotate(-90deg)"><circle cx="25" cy="25" r="${r}" fill="none" stroke="rgba(255,255,255,0.12)" stroke-width="${sw}"/><circle id="rest-arc" cx="25" cy="25" r="${r}" fill="none" stroke="var(--ember)" stroke-width="${sw}" stroke-linecap="round" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - left / S.rest.total)}"/></svg><span>${ic('clock', 18)}</span></div>
    <div class="grow"><div class="t num" id="rest-t">${mmss(left)}</div><div class="cap ink2" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">Next · ${S.rest.next}</div></div>
    <button class="icon-btn" data-act="rest-adj" data-d="-15" aria-label="Minus 15 seconds" style="font-size:13px;font-weight:700;width:42px;height:42px">−15</button>
    <button class="icon-btn" data-act="rest-adj" data-d="15" aria-label="Plus 15 seconds" style="font-size:13px;font-weight:700;width:42px;height:42px">+15</button>
    <button class="btn xs primary" style="height:44px;border-radius:22px;padding:0 16px;font-size:15px" data-act="rest-skip">Skip</button>`;
}
function updateRest() {
  if (!S.rest) return;
  const left = Math.max(0, Math.ceil((S.rest.end - Date.now()) / 1000));
  const t = $('#rest-t'); if (t) t.textContent = mmss(left);
  const arc = $('#rest-arc');
  if (arc) { const c = +arc.getAttribute('stroke-dasharray'); arc.setAttribute('stroke-dashoffset', c * (1 - left / S.rest.total)); }
  const mini = $('#mini-rest'); if (mini) mini.textContent = `Rest ${mmss(left)}`;
  if (left <= 0) {
    const next = S.rest.next;
    S.rest = null; renderRest(); renderAccessories();
    if (S.workout && !S.workout.minimized) showToast('ok', `<div class="grow"><div class="hl">Rest's over</div><div class="fn ink2">${next}</div></div>`, 2600, ic('bolt', 22));
  }
}

// ─────────────────────────── accessories: fuel bar + workout mini bar ───────────────────────────

function renderAccessories() {
  const fuel = $('#acc-fuel'), mini = $('#acc-mini');
  const onFuel = S.tab === 'fuel' && !(S.workout && !S.workout.minimized);
  fuel.style.display = onFuel ? '' : 'none';
  if (onFuel && !fuel.innerHTML) {
    fuel.innerHTML = `<div class="fuel-actions glass">
      <button class="main" data-act="ai">${ic('sparkle', 20)}AI photo</button>
      <button data-act="barcode">${ic('barcode', 22)}Barcode</button>
      <button data-act="search">${ic('search', 22)}Search</button>
    </div>`;
  }
  const w = S.workout;
  const showMini = w && w.minimized && !w.summary;
  mini.style.display = showMini ? '' : 'none';
  mini.style.bottom = onFuel ? `calc(var(--safe-bottom) + 138px)` : '';
  if (showMini) {
    const done = w.ex.reduce((a, e) => a + e.sets.filter(s => s.done).length, 0);
    const all = w.ex.reduce((a, e) => a + e.sets.length, 0);
    mini.innerHTML = `<button class="minibar glass" style="width:100%" data-act="open-workout">
      <div class="ringwrap">${ring(done / all, { size: 38, sw: 4, instant: true })}<span class="num">${done}/${all}</span></div>
      <div class="grow" style="text-align:left"><div class="sub bold">${w.name} · <span class="num" data-bind="elapsed" style="color:var(--ember)">${clock(Math.floor((Date.now() - w.startedAt) / 1000))}</span></div><div class="fn ink2" ${S.rest ? 'id="mini-rest"' : ''}>${S.rest ? `Rest ${mmss(Math.max(0, Math.ceil((S.rest.end - Date.now()) / 1000)))}` : w.ex[w.cur].name}</div></div>
      <span class="icon-btn" style="background:var(--ink);color:var(--ground)">${ic('play', 16)}</span>
    </button>`;
  }
  const screenFuel = $('#s-fuel');
  screenFuel.style.paddingBottom = showMini ? 'calc(var(--safe-bottom) + 268px)' : '';
  $$('.screen:not(.fuel-screen)').forEach(s => { s.style.paddingBottom = showMini ? 'calc(var(--safe-bottom) + 190px)' : ''; });
}

// ─────────────────────────── Fuel ───────────────────────────

function macroThumb(i) {
  const kp = i.p * 4, kc = i.c * 4, kf = i.f * 9;
  const dom = kp >= kc && kp >= kf ? 'p' : kc >= kf ? 'c' : 'f';
  const col = FOOD_COLORS[dom];
  const initials = i.name.split(/[\s,]+/).slice(0, 2).map(w => w[0]).join('').toUpperCase();
  return `<span class="thumb" style="background:color-mix(in srgb, ${col} 16%, transparent);color:${col}">${initials}</span>`;
}

function renderFuel() {
  const t = totals();
  const left = GOAL.k - t.k;
  const heatLevels = [4, 3, 4, (() => { const r = t.k / GOAL.k; return r < 0.25 ? 1 : r < 0.5 ? 2 : r < 0.85 ? 3 : 4; })(), null, null, null];
  const meals = Object.entries(S.meals);
  return `
    <header class="hdr">
      <div><span class="date-nav"><button data-act="toast" data-msg="Swipe or tap to step through days">${ic('chevL', 16, 2.4)}</button><span class="eyebrow" style="margin:0">Thu, 24 Sep</span><button style="opacity:.35">${ic('chevR', 16, 2.4)}</button></span><h1 class="lt">Fuel</h1></div>
      <div class="hdr-actions"><span class="streak" style="background:linear-gradient(180deg,rgba(94,184,255,.22),rgba(94,184,255,.08));box-shadow:inset 0 0 0 1px rgba(94,184,255,.3);color:#8ECFFF">${ic('target', 16)}11 days</span></div>
    </header>

    <section class="card hero mt12" style="background:radial-gradient(120% 85% at 100% 0%, rgba(94,184,255,0.16), transparent 60%), linear-gradient(180deg,#1C1D20,#171719 70%);box-shadow:inset 0 1px 0 rgba(255,255,255,0.1), inset 0 0 0 1px rgba(255,255,255,0.05)">
      <div class="fuel-hero">
        <div class="ring-host" data-act="flip-kcal">
          ${ring(0, { size: 150, sw: 13, segs: [[t.p * 4 / GOAL.k, 'var(--protein)'], [t.c * 4 / GOAL.k, 'var(--carbs)'], [t.f * 9 / GOAL.k, 'var(--fat)']], gap: 5 })}
          <div class="center"><span class="display">${n0(S.fuelLeft ? Math.max(0, left) : t.k)}</span><span class="fn ink2">${S.fuelLeft ? 'kcal left' : 'kcal eaten'}</span></div>
        </div>
        <div class="macro-rows">
          ${[['Protein', t.p, GOAL.p, 'var(--protein)'], ['Carbs', t.c, GOAL.c, 'var(--carbs)'], ['Fat', t.f, GOAL.f, 'var(--fat)']].map(([l, v, g, col]) => `
          <div class="m"><div class="top"><span><i class="dot" style="background:${col}"></i>${l}</span><span class="num"><b>${Math.round(v)}</b><span class="ink3"> / ${g} g</span></span></div><div class="bar"><i data-w="${Math.min(100, v / g * 100)}%" style="background:${col}"></i></div></div>`).join('')}
          <div class="fn ink3 num">${n0(t.k)} eaten · ${n0(GOAL.k)} goal</div>
        </div>
      </div>
    </section>

    <div class="section-h"><span class="hl">This week</span><button class="link" data-act="toast" data-msg="Full heatmap: every day scored against your goal">History ${ic('chevR', 16)}</button></div>
    <div class="pad">
      <div class="heat">${['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => {
        const lv = heatLevels[i];
        return `<div class="c ${lv == null ? 'future' : ''} ${i === 3 ? 'today' : ''}" style="${lv != null ? `background:${HEAT[lv]}` : ''}">${d}</div>`;
      }).join('')}</div>
    </div>

    <div class="section-h"><span class="hl">Meals</span><span class="link">${meals.reduce((a, [, m]) => a + m.items.length, 0)} items</span></div>
    ${meals.map(([key, m]) => {
      const k = m.items.reduce((a, i) => a + i.k, 0);
      if (!m.items.length) {
        return `<section class="card flush meal">
          <div class="meal-h"><span class="t3" style="font-size:17px">${m.name}</span><span class="kcal ink3">Nothing yet</span></div>
          <button class="meal-empty" data-act="ai" style="width:calc(100% - 24px)"><span class="plus">${ic('plus', 18)}</span><span style="text-align:left"><span style="color:var(--ink);font-weight:600">Post-workout dinner</span><br><span class="num">Aim for ${Math.round(GOAL.p - t.p)} g protein · ${n0(Math.max(0, left))} kcal left</span></span></button>
        </section>`;
      }
      const mp = m.items.reduce((a, i) => a + i.p * 4, 0), mc = m.items.reduce((a, i) => a + i.c * 4, 0), mf = m.items.reduce((a, i) => a + i.f * 9, 0), mt = mp + mc + mf || 1;
      return `<section class="card flush meal">
        <div class="meal-h"><span class="t3" style="font-size:17px">${m.name}</span><span class="cap ink3">${m.time}</span><span class="kcal">${n0(k)} <span class="fn ink3">kcal</span></span></div>
        <div class="meal-bar"><i style="flex:${mp / mt};background:var(--protein)"></i><i style="flex:${mc / mt};background:var(--carbs)"></i><i style="flex:${mf / mt};background:var(--fat)"></i></div>
        ${m.items.map(i => `<div class="food">${macroThumb(i)}<div class="grow"><div class="name">${i.name}${i.ai ? '<span class="ai-tag">AI</span>' : ''}</div><div class="q num">${i.q} · P ${Math.round(i.p)} · C ${Math.round(i.c)} · F ${Math.round(i.f)}</div></div><span class="k">${n0(i.k)}</span></div>`).join('')}
        <div style="height:6px"></div>
      </section>`;
    }).join('')}
  `;
}

// ─────────────────────────── Progress ───────────────────────────

function renderProgress() {
  const seg = S.prog.seg;
  return `
    <header class="hdr">
      <div><span class="eyebrow ember">${S.todayDone && S.todayDone.prs.length ? 'New PR today' : '4 PRs this month'}</span><h1 class="lt">Progress</h1></div>
      <div class="hdr-actions"><div class="seg"><button class="${seg === 'lifts' ? 'on' : ''}" data-act="seg" data-v="lifts">Lifts</button><button class="${seg === 'body' ? 'on' : ''}" data-act="seg" data-v="body">Body</button></div></div>
    </header>
    ${seg === 'lifts' ? progressLifts() : progressBody()}`;
}

function liftSeries(l) {
  const s = l.s.slice();
  if (l.id === 'bench' && S.todayDone && S.todayDone.prs.length) s[s.length - 1] = Math.max(s[s.length - 1], ...S.todayDone.prs.filter(p => p.ex === 'Bench Press').map(p => Math.round(p.e1 * 2) / 2));
  return s;
}

function xLabelsFor(n) {
  const out = [];
  const idx = [0, Math.round((n - 1) / 3), Math.round(2 * (n - 1) / 3), n - 1];
  idx.forEach((i, k) => {
    const d = new Date(TODAY); d.setDate(d.getDate() - (n - 1 - i) * 7);
    out.push([i, k === 3 ? 'Now' : n <= 6 ? `${d.getDate()} ${MONTHS[d.getMonth()]}` : MONTHS[d.getMonth()] + (n > 53 ? ` ’${String(d.getFullYear()).slice(2)}` : '')]);
  });
  return out;
}

function progressLifts() {
  const L = LIFTS.find(l => l.id === S.prog.lift);
  const full = liftSeries(L);
  const n = RANGES[S.prog.range];
  const vis = full.slice(-n);
  const delta = vis[vis.length - 1] - vis[0];
  // PR points: new all-time highs inside the visible window (last 3)
  const prIdx = []; let hi = Math.max(...full.slice(0, full.length - n));
  vis.forEach((v, i) => { if (v > hi) { hi = v; if (i < vis.length - 1) prIdx.push(i); } });
  const muscles = weekMuscles();
  const levels = {}; Object.entries(muscles).forEach(([k, v]) => { levels[k] = setsToLevel(v); });
  const top = Object.entries(muscles).sort((a, b) => b[1] - a[1]).slice(0, 6);
  const vols = VOLUME_WEEKS.concat([weekVolume() / 1000]);
  const vmax = Math.max(...vols);
  return `
    <div class="hscroll mt12">${LIFTS.map(l => `<button class="chip ${l.id === S.prog.lift ? '' : 'quiet'}" style="${l.id === S.prog.lift ? 'background:var(--ink);color:var(--ground)' : ''}" data-act="lift" data-v="${l.id}">${l.name}</button>`).join('')}</div>
    <section class="card hero lift-hero mt12">
      <div class="top"><div><div class="eyebrow">Estimated 1RM</div>
        <div class="big-num"><span class="display">${n1(full[full.length - 1])}</span><span class="unit">kg</span></div></div>
        <span class="chip ${delta > 0 ? 'good' : 'quiet'}" style="margin-top:2px">${delta > 0 ? ic('up', 14, 2.6) : ''}${delta > 0 ? '+' : ''}${n1(delta)} kg ${RANGE_WORD[S.prog.range]}</span></div>
      ${lineChart(vis, { w: 330, h: 170, step: 5, xLabels: xLabelsFor(vis.length), prs: prIdx.slice(-3), id: 'lift' })}
      <div class="range">${Object.keys(RANGES).map(r => `<button class="${r === S.prog.range ? 'on' : ''}" data-act="range" data-v="${r}">${r}</button>`).join('')}</div>
    </section>
    <div class="row2 mt12">
      <div class="tile"><div class="eyebrow">Best set</div><div class="hl num mt8" style="font-size:20px">${L.best}</div><div class="fn ink2 mt4">${L.bestWhen}</div></div>
      <div class="tile"><div class="eyebrow">Next milestone</div><div class="hl num mt8" style="font-size:20px">${Math.ceil((full[full.length - 1] + 0.1) / 10) * 10} kg</div><div class="fn ink2 mt4">~${Math.max(1, Math.round((Math.ceil((full[full.length - 1] + 0.1) / 10) * 10 - full[full.length - 1]) / Math.max(0.3, (full[full.length - 1] - full[full.length - 14]) / 13)))} weeks at this pace</div></div>
    </div>

    <div class="section-h"><span class="hl">Muscles this week</span><span class="link">${Object.values(muscles).reduce((a, b) => a + b, 0)} sets</span></div>
    <section class="card">
      <div class="flex center" style="gap:14px">
        <div class="body-map">${bodyMap(levels, { h: 196 })}</div>
        <div class="muscle-list grow">${top.map(([m, v]) => `<div class="m"><span style="text-transform:capitalize">${m}</span><span class="num ink2">${v}</span><div class="bar"><i data-w="${Math.min(100, v / 12 * 100)}%" style="background:${HEAT[Math.max(1, setsToLevel(v))]}"></i></div></div>`).join('')}</div>
      </div>
      <div class="flex between center mt12"><div class="muscle-legend">Sets per muscle ${HEAT.map(c => `<i style="background:${c}"></i>`).join('')} 10+</div></div>
      ${muscles.chest ? '' : `<div class="suggest" style="margin:12px 0 0">${ic('bolt', 16)}<div class="grow">Chest and triceps haven't been hit this week. <b>Push A tonight</b> fixes that.</div></div>`}
    </section>

    <div class="section-h"><span class="hl">Weekly volume</span><span class="link num">${n1(vols[vols.length - 1])} t this week</span></div>
    <section class="card">
      <div class="vol-bars">${vols.map((v, i) => `<div class="${i === vols.length - 1 ? 'cur' : ''}"><i style="height:${v / vmax * 78}%"></i><span>${i === vols.length - 1 ? 'Now' : 'W' + (32 + i)}</span></div>`).join('')}</div>
    </section>

    <div class="section-h"><span class="hl">All lifts</span><span class="link">e1RM · 3 months</span></div>
    <section class="list">${LIFTS.map(l => {
      const s = liftSeries(l), d = s[s.length - 1] - s[s.length - 14];
      return `<button class="li lift-li pressable" style="width:100%;text-align:left" data-act="lift" data-v="${l.id}"><div class="grow"><div class="title">${l.name}</div><div class="meta">${l.routine}</div></div>${spark(s.slice(-14), { w: 72, h: 30, fill: false })}<div style="text-align:right;min-width:64px"><div class="hl num">${n1(s[s.length - 1])} kg</div><div class="delta ${d > 0 ? '' : 'flat'}">${d > 0 ? '+' : ''}${n1(d)}</div></div></button>`;
    }).join('')}</section>`;
}

function progressBody() {
  const raw = S.bw.raw, trend = S.bw.trend;
  const n = S.prog.bwRange === '1M' ? 30 : 90;
  const vr = raw.slice(-n), vt = trend.slice(-n);
  const now = vt[vt.length - 1];
  const rate = (now - trend[trend.length - 29]) / 4;
  const xl = [[0, n === 30 ? '25 Aug' : '26 Jun'], [Math.round((n - 1) / 2), n === 30 ? '9 Sep' : '10 Aug'], [n - 1, 'Now']];
  const logged = raw.slice(-28).filter(v => v != null).length;
  const recent = raw.map((v, i) => [v, i]).filter(([v]) => v != null).slice(-5).reverse();
  return `
    <section class="card hero mt12">
      <div class="top flex between" style="align-items:flex-start"><div><div class="eyebrow">Body weight · trend</div>
        <div class="big-num"><span class="display">${n1(now)}</span><span class="unit">kg</span></div></div>
        <span class="chip quiet num" style="margin-top:2px">${ic(rate < 0 ? 'down' : 'up', 14, 2.6)} ${Math.abs(rate).toFixed(1)} kg / week</span></div>
      ${lineChart(vt, { w: 330, h: 170, step: 1, xLabels: xl, raw: vr, id: 'bw', color: 'var(--ember)' })}
      <div class="range">${['1M', '3M'].map(r => `<button class="${(S.prog.bwRange || '3M') === r ? 'on' : ''}" data-act="bwrange" data-v="${r}">${r}</button>`).join('')}</div>
      <button class="btn primary block mt16" data-act="logweight">${ic('plus', 18)} Log weight</button>
    </section>
    <div class="row2 mt12">
      <div class="tile"><div class="eyebrow">Consistency</div><div class="hl num mt8" style="font-size:20px">${logged} of 28 days</div><div class="bar mt8"><i data-w="${logged / 28 * 100}%" style="background:var(--ink)"></i></div></div>
      <div class="tile"><div class="eyebrow">Goal · 80 kg</div><div class="hl num mt8" style="font-size:20px">~${Math.round((now - 80) / Math.abs(rate))} weeks</div><div class="fn ink2 mt4">at this pace</div></div>
    </div>
    <div class="section-h"><span class="hl">Recent</span><span class="link">Dots = weigh-ins · line = trend</span></div>
    <section class="list">${recent.map(([v, i]) => {
      const d = new Date(TODAY); d.setDate(d.getDate() - (raw.length - 1 - i));
      return `<div class="li"><div class="grow"><div class="title" style="font-weight:500">${['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d.getDay()]}, ${d.getDate()} ${MONTHS[d.getMonth()]}</div></div><span class="hl num">${n1(v)} kg</span></div>`;
    }).join('')}</section>`;
}

// ─────────────────────────── Bro ───────────────────────────

function renderBro() {
  const out = !!S.out, done = !!S.todayDone, inn = S.imIn || done || (S.workout && !S.workout.summary);
  const days = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
  const K = ['done-k', '', 'done-k', done ? 'done-k' : out ? 'miss' : inn ? 'in' : 'pending', S.out && S.out.makeup === 'Fri 25' ? 'plan' : '', 'plan', ''];
  const T = ['done-t', '', 'miss', 'in', '', 'plan', ''];
  const cellInner = c => c === 'done-k' || c === 'done-t' ? ic('check', 14, 3.2) : c === 'miss' ? ic('x', 12, 3) : c === 'in' ? 'IN' : '';
  const together = 8 + (done ? 1 : 0);
  return `
    <header class="hdr">
      <div><span class="eyebrow">${ME} & ${BRO}</span><h1 class="lt">Bro</h1></div>
      <div class="hdr-actions"><span class="streak">${ic('flame', 16)}6 wk</span></div>
    </header>

    <section class="card hero mt12">
      <div class="flex between" style="align-items:flex-end">
        <div><div class="eyebrow ember">September together</div><div class="flex mt8" style="align-items:baseline;gap:4px"><span class="display" style="font-size:64px">${together}</span><span class="display ink3" style="font-size:36px">/ 11</span></div></div>
        <div class="avatars" style="margin-bottom:6px"><span class="avatar me ring-ember">K</span><span class="avatar bro ring-good">T</span></div>
      </div>
      <div class="fn ink2 mt8">Sessions you both showed up for. Best month yet was 10.</div>
      <div class="board mt16">
        <span></span>${days.map((d, i) => `<span class="dl ${i === 3 ? 'today' : ''}">${d}</span>`).join('')}
        <span class="who"><span class="avatar xs me">K</span>You</span>${K.map(c => `<span class="cell ${c}">${cellInner(c)}</span>`).join('')}
        <span class="who"><span class="avatar xs bro">T</span>${BRO}</span>${T.map(c => `<span class="cell ${c}">${cellInner(c)}</span>`).join('')}
      </div>
    </section>

    ${done ? '' : out ? `
    <section class="card mt12" style="padding:16px">
      <div class="flex center gap12"><span class="avatar sm me" style="opacity:.5">K</span><div class="grow"><div class="sub bold">You're out today</div><div class="fn ink2">${BRO} got your heads-up${S.out.makeup !== 'skip' ? ` · make-up ${S.out.makeup}` : ''}</div></div><button class="btn xs secondary" data-act="undo-out">Undo</button></div>
    </section>` : `
    <section class="card mt12" style="padding:16px">
      <div class="flex center gap12">
        <span class="avatar sm bro ring-good">T</span>
        <div class="grow"><div class="sub bold">${inn ? 'Both in for 18:00' : `${BRO}'s in for 18:00`}</div><div class="fn ink2">Push A · he confirmed at 12:40</div></div>
        ${inn ? `<span class="chip good">${ic('check', 14, 3)} You're in</span>` : `<button class="btn sm primary" data-act="imin">I'm in</button>`}
      </div>
    </section>`}

    <div class="section-h"><span class="hl">Heads-up to ${BRO}</span></div>
    <div class="headsup">
      <button data-act="cant"><span class="ic" style="background:var(--bad-tint);color:#FF6B88">${ic('x', 16, 2.6)}</span>Can't make it</button>
      <button data-act="send" data-msg="15 min late"><span class="ic" style="background:rgba(245,192,74,.14);color:var(--carbs)">${ic('clock', 16)}</span>15 min late</button>
      <button data-act="send" data-msg="Let's go!"><span class="ic" style="background:var(--ember-tint);color:var(--ember)">${ic('flame', 16)}</span>Let's go</button>
      <button data-act="send" data-msg="Custom message"><span class="ic" style="background:rgba(255,255,255,.08)">${ic('msg', 16)}</span>Custom</button>
    </div>

    <div class="section-h"><span class="hl">Head to head</span><span class="link">September</span></div>
    <section class="card">
      <div class="flex between center" style="margin-bottom:14px"><span class="flex center gap8 fn bold"><span class="avatar xs me">K</span>You</span><span class="flex center gap8 fn bold">${BRO}<span class="avatar xs bro">T</span></span></div>
      <div class="vs">${[['Sessions', 9 + (done ? 1 : 0), 8, ''], ['Volume', 48.2 + (done ? (S.todayDone.volume / 1000) : 0), 51.0, ' t'], ['PRs', 4 + (done ? S.todayDone.prs.length : 0), 3, ''], ['Protein days', 11, 7, '']].map(([l, a, b, u]) => `
        <div class="r"><div class="lbl"><b class="num" style="${a >= b ? 'color:var(--ember-hi)' : ''}">${n1(a)}${u}</b><span class="ink2">${l}</span><b class="num" style="${b > a ? 'color:var(--good)' : ''}">${n1(b)}${u}</b></div>
        <div class="split"><i style="flex:${a}"></i><i style="flex:${b}"></i></div></div>`).join('')}</div>
    </section>

    <div class="section-h"><span class="hl">Log</span><span class="link">Since 12 Aug · 18 sessions · 2 missed</span></div>
    <section class="list">${S.broLog.map(l => `
      <div class="log-item">
        <span class="ico" style="background:${l.tone === 'bad' ? 'var(--bad-tint)' : l.tone === 'ember' ? 'var(--ember-tint)' : l.tone === 'you' ? 'rgba(255,255,255,.08)' : 'var(--good-tint)'};color:${l.tone === 'bad' ? '#FF6B88' : l.tone === 'ember' ? 'var(--ember)' : l.tone === 'you' ? 'var(--ink)' : 'var(--good)'}">${ic(l.icon, 16, 2.6)}</span>
        <div class="grow"><div class="sub bold">${l.title}</div>${l.quote ? `<div class="quote">${l.quote}</div>` : ''}${l.meta ? `<div class="fn ink2 mt4">${l.meta}</div>` : ''}</div>
        <span class="when">${l.when}</span>
      </div>`).join('')}
    </section>`;
}

// ─────────────────────────── sheets ───────────────────────────

function openSheet(type, data = {}) {
  S.sheet = { type, ...data };
  renderSheet();
  $('#scrim').classList.add('on');
  requestAnimationFrame(() => $('#sheet').classList.add('on'));
}
function closeSheet() {
  $('#sheet').classList.remove('on');
  $('#scrim').classList.remove('on');
  clearTimeout(S.sheetTimer);
  S.sheet = null;
}

function renderSheet() {
  const sh = S.sheet, el = $('#sheet');
  if (!sh) return;
  const wasOn = el.classList.contains('on');
  el.className = 'sheet' + (sh.type === 'ai' || sh.type === 'search' ? ' tall' : '') + (wasOn ? ' on' : '');
  const head = (title, sub = '') => `<div class="grab"></div><div class="sh"><div><div class="t3">${title}</div>${sub ? `<div class="fn ink2 mt4">${sub}</div>` : ''}</div><button class="icon-btn" data-act="close-sheet" aria-label="Close">${ic('x', 18, 2.4)}</button></div>`;

  if (sh.type === 'cant') {
    sh.reason = sh.reason ?? 'Work'; sh.makeup = sh.makeup ?? 'Fri 25';
    el.innerHTML = head("Can't make it today?", `${BRO} gets a heads-up right away. No guilt trip.`) + `<div class="sb">
      <div class="eyebrow mt8">Reason</div>
      <div class="opt-grid mt8">${[['Sick', 'thermo'], ['Work', 'work'], ['Tired', 'moon'], ['Family', 'heart']].map(([r, i]) => `<button class="opt ${sh.reason === r ? 'on' : ''}" data-act="cant-reason" data-v="${r}"><span class="e">${ic(i, 18)}</span>${r}</button>`).join('')}</div>
      <div class="eyebrow mt20">Make it up</div>
      <div class="day-chips mt8">${[['Fri', 25], ['Sun', 27], ['Mon', 28], ['Tue', 29]].map(([d, n]) => `<button class="${sh.makeup === d + ' ' + n ? 'on' : ''}" data-act="cant-day" data-v="${d} ${n}">${d}<b class="num">${n}</b></button>`).join('')}<button class="${sh.makeup === 'skip' ? 'on' : ''}" data-act="cant-day" data-v="skip" style="width:72px">Skip<b style="font-size:13px">—</b></button></div>
      <div class="field mt16">${ic('msg', 18)}<input placeholder="Add a note (optional)" maxlength="80"></div>
      <button class="btn primary block mt16" data-act="cant-send">Tell ${BRO}</button>
      <div class="fn ink3 mt12" style="text-align:center">It lands in your shared log as missed${sh.makeup !== 'skip' ? `, with ${sh.makeup} as the make-up` : ''}.</div>
    </div>`;
  }

  if (sh.type === 'ai') {
    const found = sh.phase === 'result';
    const items = sh.items;
    const tot = items.reduce((a, i) => ({ k: a.k + i.per.k * i.g / 100, p: a.p + i.per.p * i.g / 100, c: a.c + i.per.c * i.g / 100, f: a.f + i.per.f * i.g / 100 }), { k: 0, p: 0, c: 0, f: 0 });
    el.innerHTML = head('AI estimate', 'Dinner · photo from camera') + `<div class="sb">
      <div class="photo ${found ? 'found' : ''}">
        <span class="potato" style="left:22%;top:56%"></span><span class="potato" style="left:30%;top:66%;transform:scale(.9)"></span><span class="potato" style="left:19%;top:70%;transform:scale(.8)"></span>
        <span class="salmon"></span>
        <span class="leaf" style="left:64%;top:60%"></span><span class="leaf" style="left:70%;top:66%;transform:rotate(60deg)"></span><span class="leaf" style="left:62%;top:70%;transform:rotate(-40deg)"></span><span class="leaf" style="left:72%;top:58%;transform:rotate(120deg) scale(.8)"></span>
        <div class="scanline"></div>
        ${items.slice(0, 3).map((i, k) => `<div class="box" style="left:${i.box[0]}%;top:${i.box[1]}%;width:${i.box[2]}%;height:${i.box[3]}%;transition-delay:${k * 0.15}s"><span>${i.name.split(',')[0].split(' (')[0]}</span></div>`).join('')}
      </div>
      ${found ? `
        <div class="flex between center mt16"><div><div class="display" style="font-size:40px">${n0(tot.k)} <span class="fn ink2" style="font-family:var(--font);font-weight:500">kcal</span></div></div>
          <div class="chips"><span class="chip" style="background:rgba(94,184,255,.14);color:var(--protein)">P ${Math.round(tot.p)}</span><span class="chip" style="background:rgba(245,192,74,.14);color:var(--carbs)">C ${Math.round(tot.c)}</span><span class="chip" style="background:rgba(177,140,255,.14);color:var(--fat)">F ${Math.round(tot.f)}</span></div></div>
        <div class="mt8">${items.map((i, k) => `
          <div class="ai-item"><div class="grow"><div class="sub bold flex center gap6">${i.name}<span class="conf ${i.conf}">${i.conf === 'hi' ? 'SURE' : 'CHECK'}</span></div><div class="fn ink2 num">${n0(i.per.k * i.g / 100)} kcal${i.note ? ` · <span style="color:var(--carbs)">${i.note}</span>` : ''}</div></div>
          <div class="stepper"><button data-act="ai-g" data-k="${k}" data-d="-1">${ic('minus', 16)}</button><span>${i.g} g</span><button data-act="ai-g" data-k="${k}" data-d="1">${ic('plus', 16)}</button></div></div>`).join('')}</div>
        <div class="btn-row mt16"><button class="btn secondary" style="flex:0 0 auto" data-act="toast" data-msg="Describe what's off and the estimate is redone">${ic('sparkle', 18)} Fix</button><button class="btn primary" data-act="ai-add">Add ${items.length} to Dinner</button></div>
        <div class="disclaimer">AI estimates are approximate. Photo-based estimates typically differ from actual values by roughly a third and tend to underestimate large or high-fat portions. Always check the portion.</div>
      ` : `<div class="flex center gap10 mt20" style="justify-content:center;color:var(--ink-2)"><span class="live-dot" style="background:var(--ember)"></span>Finding foods and portions…</div>`}
    </div>`;
  }

  if (sh.type === 'search') {
    const q = (sh.q || '').toLowerCase();
    const list = FOODS.filter(f => !q || (f.name + ' ' + f.brand).toLowerCase().includes(q));
    el.innerHTML = head('Add to Dinner') + `<div class="sb">
      <div class="field">${ic('search', 18)}<input id="food-q" placeholder="Search 37 000 Polish products" value="${sh.q || ''}" autocomplete="off"></div>
      <div class="eyebrow mt16">${q ? `${list.length} results` : 'Usual at dinner time'}</div>
      <div class="mt8">${list.map((f, k) => `
        <div class="food" style="padding:10px 0">${macroThumb({ name: f.name, p: f.per.p, c: f.per.c, f: f.per.f })}
          <div class="grow"><div class="name">${f.name}</div><div class="q num">${f.brand} · ${f.g} g · ${n0(f.per.k * f.g / 100)} kcal · P ${Math.round(f.per.p * f.g / 100)}</div></div>
          <button class="icon-btn" data-act="food-add" data-k="${FOODS.indexOf(f)}" aria-label="Add ${f.name}">${ic('plus', 18)}</button></div>`).join('')}</div>
      ${list.length ? '' : '<div class="sub ink2 mt16">Nothing here. Try the barcode, or read the label with AI.</div>'}
    </div>`;
    const inp = $('#food-q'); if (inp && sh.focus) { inp.focus(); inp.setSelectionRange(inp.value.length, inp.value.length); }
  }

  if (sh.type === 'barcode') {
    const f = FOODS[0];
    el.innerHTML = head('Scan barcode') + `<div class="sb">
      <div class="photo" style="height:180px;background:radial-gradient(90% 90% at 50% 40%, #2a2a2e, #111)">
        <div style="position:absolute;inset:30px 50px;border-radius:18px;box-shadow:0 0 0 999px rgba(0,0,0,.35)"></div>
        <svg style="position:absolute;inset:0;margin:auto" width="200" height="90" viewBox="0 0 200 90">${Array.from({ length: 34 }, (_, k) => `<rect x="${12 + k * 5.2}" y="10" width="${[1, 2, 3][(k * 7) % 3]}" height="62" fill="#e8e8ea"/>`).join('')}<text x="100" y="86" fill="#e8e8ea" font-size="10" text-anchor="middle" font-family="monospace">5 900820 000431</text></svg>
        ${sh.phase === 'result' ? '' : '<div class="scanline" style="animation-duration:1.1s"></div>'}
      </div>
      ${sh.phase === 'result' ? `
        <div class="flex center gap12 mt16">${macroThumb({ name: f.name, p: f.per.p, c: f.per.c, f: f.per.f })}<div class="grow"><div class="hl">${f.name}</div><div class="fn ink2">${f.brand} · Open Food Facts</div></div><span class="chip good">${ic('check', 14, 3)} Found</span></div>
        <div class="stat3" style="margin:14px 0 0;grid-template-columns:repeat(4,1fr)">${[['kcal', 158, 'var(--ink)'], ['P', 24, 'var(--protein)'], ['C', 5, 'var(--carbs)'], ['F', 5, 'var(--fat)']].map(([l, v, c]) => `<div class="tile" style="padding:10px"><div class="eyebrow">${l}</div><div class="v" style="font-size:20px;color:${c}">${v}</div></div>`).join('')}</div>
        <div class="seg mt16" style="display:flex"><button style="flex:1">100 g</button><button class="on" style="flex:1">Tub · 200 g</button><button style="flex:1">Custom</button></div>
        <button class="btn primary block mt16" data-act="food-add" data-k="0">Add to Dinner</button>` : '<div class="flex center gap10 mt20" style="justify-content:center;color:var(--ink-2)"><span class="live-dot"></span>Point at a barcode</div>'}
    </div>`;
  }

  if (sh.type === 'logweight') {
    sh.v = sh.v ?? 82.3;
    el.innerHTML = head('Log weight', 'Thursday, 24 September · morning') + `<div class="sb">
      <div class="flex center" style="justify-content:center;gap:18px;margin:18px 0 8px">
        <button class="icon-btn" style="width:56px;height:56px;border-radius:28px" data-act="bw-adj" data-d="-0.1">${ic('minus', 24)}</button>
        <div style="text-align:center;min-width:150px"><span class="display" style="font-size:88px">${n1(sh.v)}</span><div class="sub ink2">kg</div></div>
        <button class="icon-btn" style="width:56px;height:56px;border-radius:28px" data-act="bw-adj" data-d="0.1">${ic('plus', 24)}</button>
      </div>
      <div class="fn ink3" style="text-align:center">Trend ${n1(S.bw.trend[S.bw.trend.length - 1])} kg · single weigh-ins bounce, the line doesn't</div>
      <button class="btn primary block mt20" data-act="bw-save">Save</button>
    </div>`;
  }
}

// ─────────────────────────── toast ───────────────────────────

function showToast(kind, html, ms = 2400, icon) {
  const t = $('#toast');
  t.className = `toast glass ${kind}`;
  t.innerHTML = `<span class="ic">${icon || (kind === 'pr' ? ic('trophy', 22) : ic('check', 20, 3))}</span>${html}`;
  requestAnimationFrame(() => t.classList.add('on'));
  clearTimeout(S.toastTimer);
  S.toastTimer = setTimeout(() => t.classList.remove('on'), ms);
}
const note = msg => showToast('ok', `<div class="grow sub bold">${msg}</div>`, 2200, ic('sparkle', 20));

// ─────────────────────────── events ───────────────────────────

app.addEventListener('click', ev => {
  const t = ev.target.closest('[data-act], [data-tab]');
  if (!t || !app.contains(t)) return;
  if (t.dataset.act) act(t, ev);
  else setTab(t.dataset.tab);
});

function act(a, ev) {
  const d = a.dataset;
  const w = S.workout;
  switch (d.act) {
    case 'toast': note(d.msg); break;
    case 'imin':
      S.imIn = true; renderAll();
      showToast('ok', `<div class="grow"><div class="hl">You're in</div><div class="fn ink2">${BRO} gets a push. Both in for 18:00.</div></div>`, 2400);
      break;
    case 'cant': openSheet('cant'); break;
    case 'cant-reason': S.sheet.reason = d.v; renderSheet(); break;
    case 'cant-day': S.sheet.makeup = d.v; renderSheet(); break;
    case 'cant-send':
      S.out = { reason: S.sheet.reason, makeup: S.sheet.makeup }; S.imIn = false;
      S.broLog.unshift({ when: 'Today', icon: 'x', tone: 'you', title: 'You can’t make Push A', meta: `${S.out.reason}${S.out.makeup !== 'skip' ? ` · make-up ${S.out.makeup} 18:00` : ''}` });
      closeSheet(); renderAll();
      showToast('ok', `<div class="grow"><div class="hl">Sent to ${BRO}</div><div class="fn ink2">${S.out.makeup !== 'skip' ? `Make-up on ${S.out.makeup} is on both schedules` : 'Logged as missed'}</div></div>`);
      break;
    case 'undo-out':
      S.out = null; S.broLog = S.broLog.filter(l => l.when !== 'Today'); renderAll(); break;
    case 'send':
      S.broLog.unshift({ when: 'Today', icon: 'msg', tone: 'you', title: `You: “${d.msg}”`, meta: `Sent to ${BRO} · just now` });
      render('bro'); showToast('ok', `<div class="grow"><div class="hl">Sent to ${BRO}</div><div class="fn ink2">“${d.msg}”</div></div>`, 2000);
      break;
    case 'start':
      if (w && !w.summary) { openCover(); break; }
      startWorkout(d.r); break;
    case 'start-empty': note('Empty workout: add exercises as you go'); break;
    case 'open-workout': openCover(); break;
    case 'minimize': minimize(); break;
    case 'focus-ex': w.cur = +d.i; renderCover(); { const c = $('#ex-' + d.i); if (c) c.scrollIntoView({ block: 'start', behavior: 'smooth' }); } break;
    case 'tick': tick(+d.i, +d.si, a); break;
    case 'add-set': { const e = w.ex[+d.i]; const l = e.sets[e.sets.length - 1]; e.sets.push({ kind: 'N', kg: l.kg, reps: l.reps, prev: '—', done: false, pr: 0 }); renderCover(); break; }
    case 'suggest-undo': { const e = w.ex[+d.i]; e.suggestOn = false; e.sets.forEach(s => { if (s.kind !== 'W' && !s.done && s.kg === e.suggest.to) s.kg = e.suggest.from; }); renderCover(); break; }
    case 'rest-adj': S.rest.end += (+d.d) * 1000; S.rest.total = Math.max(S.rest.total + (+d.d > 0 ? +d.d : 0), 1); updateRest(); break;
    case 'rest-skip': S.rest = null; renderRest(); break;
    case 'finish': {
      const any = w.ex.some(e => e.sets.some(s => s.done));
      if (!any) { showToast('ok', '<div class="grow"><div class="hl">Log a set first</div><div class="fn ink2">Tap the check next to a set when it’s done.</div></div>', 2400, ic('check', 20, 3)); break; }
      S.rest = null; renderRest();
      w.summary = true; renderCover();
      setTimeout(() => burst($('.medal')), 350);
      break;
    }
    case 'share': note('Shares a summary card to your bro or stories'); break;
    case 'done': {
      const sum = workoutSummary();
      S.todayDone = sum;
      S.history[0].items.unshift({ name: w.name, when: 'Thu 24 Sep', min: sum.min, kg: Math.round(sum.volume), prs: sum.prs.length });
      S.broLog.unshift({ when: 'Today', icon: sum.prs.length ? 'trophy' : 'check', tone: sum.prs.length ? 'ember' : 'good', title: `${w.name} · both showed up`, meta: sum.prs.length ? `You hit ${sum.prs.length} PR${sum.prs.length > 1 ? 's' : ''} · ${sum.prs[0].ex} ${n1(sum.prs[0].kg)} × ${sum.prs[0].reps}` : `${sum.min} min · ${n0(sum.volume)} kg` });
      S.workout = null; S.rest = null;
      $('#cover').classList.remove('on'); $('#tabbar').classList.remove('hidden');
      renderRest(); renderAll(); setTab('today');
      break;
    }
    case 'flip-kcal': S.fuelLeft = !S.fuelLeft; render('fuel'); break;
    case 'ai':
      openSheet('ai', { phase: 'scan', items: AI_ITEMS() });
      S.sheetTimer = setTimeout(() => { if (S.sheet && S.sheet.type === 'ai') { S.sheet.phase = 'result'; renderSheet(); } }, 1700);
      break;
    case 'ai-g': { const it = S.sheet.items[+d.k]; it.g = Math.max(0, it.g + (+d.d) * it.step); renderSheet(); break; }
    case 'ai-add': {
      const items = S.sheet.items.filter(i => i.g > 0).map(i => item(i.name, `${i.g} g`, Math.round(i.per.k * i.g / 100), i.per.p * i.g / 100, i.per.c * i.g / 100, i.per.f * i.g / 100, { ai: true }));
      S.meals.dinner.items.push(...items); S.meals.dinner.time = '19:30';
      const k = items.reduce((a, i) => a + i.k, 0);
      closeSheet(); render('fuel', { animate: true }); render('today');
      showToast('ok', `<div class="grow"><div class="hl">Added to Dinner</div><div class="fn ink2 num">${items.length} items · ${n0(k)} kcal · marked as AI estimates</div></div>`);
      break;
    }
    case 'barcode':
      openSheet('barcode', { phase: 'scan' });
      S.sheetTimer = setTimeout(() => { if (S.sheet && S.sheet.type === 'barcode') { S.sheet.phase = 'result'; renderSheet(); } }, 1400);
      break;
    case 'search': openSheet('search', { q: '' }); break;
    case 'food-add': {
      const f = FOODS[+d.k];
      S.meals.dinner.items.push(item(f.name, `${f.g} g`, Math.round(f.per.k * f.g / 100), f.per.p * f.g / 100, f.per.c * f.g / 100, f.per.f * f.g / 100));
      if (!S.meals.dinner.time) S.meals.dinner.time = '19:30';
      closeSheet(); render('fuel', { animate: true }); render('today');
      showToast('ok', `<div class="grow"><div class="hl">${f.name}</div><div class="fn ink2 num">Added to Dinner · ${n0(f.per.k * f.g / 100)} kcal</div></div>`);
      break;
    }
    case 'seg': S.prog.seg = d.v; render('progress', { animate: true }); break;
    case 'lift': S.prog.lift = d.v; render('progress', { animate: true }); $('#s-progress').scrollTo({ top: 0, behavior: 'smooth' }); break;
    case 'range': S.prog.range = d.v; render('progress'); break;
    case 'bwrange': S.prog.bwRange = d.v; render('progress'); break;
    case 'go-body': S.prog.seg = 'body'; setTab('progress'); break;
    case 'logweight': openSheet('logweight'); break;
    case 'bw-adj': S.sheet.v = Math.round((S.sheet.v + (+d.d)) * 10) / 10; renderSheet(); break;
    case 'bw-save': {
      const v = S.sheet.v; S.bw.raw[S.bw.raw.length - 1] = v;
      let t = S.bw.trend[S.bw.trend.length - 2]; S.bw.trend[S.bw.trend.length - 1] = Math.round((t + 0.12 * (v - t)) * 100) / 100;
      closeSheet(); render('progress'); render('today');
      showToast('ok', `<div class="grow"><div class="hl num">${n1(v)} kg saved</div><div class="fn ink2">Also written to Apple Health</div></div>`);
      break;
    }
    case 'close-sheet': closeSheet(); break;
  }
}

$('#scrim').addEventListener('click', closeSheet);
app.addEventListener('input', ev => {
  const inp = ev.target;
  if (inp.id === 'food-q') { S.sheet.q = inp.value; S.sheet.focus = true; renderSheet(); return; }
  const row = inp.closest('.set-row');
  if (row && S.workout) {
    const s = S.workout.ex[+row.dataset.i].sets[+row.dataset.si];
    const v = parseFloat(inp.value);
    if (!Number.isNaN(v)) s[inp.dataset.f] = v;
  }
});

// ─────────────────────────── clock ───────────────────────────

setInterval(() => {
  const w = S.workout;
  if (w) { const txt = clock(Math.floor((Date.now() - w.startedAt) / 1000)); $$('[data-bind="elapsed"]').forEach(e => { e.textContent = txt; }); }
  updateRest();
}, 250);

// ─────────────────────────── boot + deep links ───────────────────────────

function seedMidWorkout() {
  startWorkout('pushA');
  const w = S.workout;
  w.startedAt = Date.now() - (17 * 60 + 42) * 1000;
  const e = w.ex[0];
  e.sets[0].done = true; e.sets[1].done = true; recomputePRs(e);
  S.rest = { end: Date.now() + 84 * 1000, total: 120, next: '82.5 kg × 8' };
  renderCover(); renderRest();
}

renderAll();
const hash = location.hash.replace('#', '') || 'today';
const tabOf = { pr: 'train', today: 'today', train: 'train', fuel: 'fuel', progress: 'progress', body: 'progress', bro: 'bro', workout: 'train', summary: 'today', ai: 'fuel', cant: 'today', search: 'fuel', 'done-today': 'today', minibar: 'fuel' };
if (hash === 'body') S.prog.seg = 'body';
requestAnimationFrame(() => {
  setTab(tabOf[hash] || 'today', false);
  if (hash === 'workout') seedMidWorkout();
  if (hash === 'summary') { seedMidWorkout(); const w = S.workout; w.ex[0].sets[2].done = true; w.ex[0].sets[3].done = true; w.ex[1].sets.forEach(s => { s.done = true; }); w.ex[2].sets.forEach(s => { s.done = true; }); recomputePRs(w.ex[0]); w.demoMin = 58; S.rest = null; w.summary = true; renderRest(); renderCover(); }
  if (hash === 'done-today') { seedMidWorkout(); const w = S.workout; w.demoMin = 58; w.ex.forEach(e => e.sets.forEach(s => { s.done = true; })); recomputePRs(w.ex[0]); w.summary = true; act({ dataset: { act: 'done' } }); }
  if (hash === 'minibar') { seedMidWorkout(); minimize(); }
  if (hash === 'pr') { seedMidWorkout(); const e = S.workout.ex[0]; e.sets[1].done = false; recomputePRs(e); S.rest = null; renderRest(); renderCover(); setTimeout(() => { const b = $('.set-row[data-si="1"] .tick'); tick(0, 1, b); }, 300); }
  if (hash === 'ai') act({ dataset: { act: 'ai' } });
  if (hash === 'cant') openSheet('cant');
  if (hash === 'search') openSheet('search', { q: '' });
});

// let the overview page drive the embedded prototype
addEventListener('message', ev => {
  const m = ev.data || {};
  if (m.type === 'nt-tab' && RENDER[m.tab]) { if (S.workout && !S.workout.minimized && !S.workout.summary) minimize(); closeSheet(); setTab(m.tab); }
  if (m.type === 'nt-workout') { closeSheet(); setTab('train'); if (!S.workout || S.workout.summary) startWorkout('pushA'); else openCover(); }
  if (m.type === 'nt-ai') { if (S.workout && !S.workout.minimized) minimize(); setTab('fuel'); act({ dataset: { act: 'ai' } }); }
});
})();
