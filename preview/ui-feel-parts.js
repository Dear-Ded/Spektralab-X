// Skeuomorphic parts for preview/ui-feel.html, drawn the way the Compose code will draw them
// (gradients, round rects and paths on a canvas, one light from the upper left, contrast kept low).
// Keep in step with Dial.kt (CameraDial), FilmPack.kt (cassette), the histogram and curve editor.
const DPR = Math.max(2, devicePixelRatio || 1);
const canvasOf = (el, w, h) => { el.width = w * DPR; el.height = h * DPR; el.style.width = w + 'px'; el.style.height = h + 'px';
  const g = el.getContext('2d'); g.setTransform(DPR, 0, 0, DPR, 0, 0); return g; };
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const rgb = (c, a = 1) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;
const hex = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const rr = (g, x, y, w, h, r) => { g.beginPath(); g.roundRect(x, y, w, h, r); };
const hash = i => { const s = Math.sin(i * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); };

// A window cut into the top plate: a chamfer that catches the light along its lower edge and is in
// shadow along its upper one, then the recess's own shadow falling inward from the top.
function cutWindow(g, x, y, w, h, r) {
  const ch = g.createLinearGradient(0, y - 1.5, 0, y + h + 1.5);
  ch.addColorStop(0, 'rgba(0,0,0,.55)'); ch.addColorStop(0.5, 'rgba(0,0,0,.1)'); ch.addColorStop(1, 'rgba(255,248,236,.16)');
  rr(g, x - 1.5, y - 1.5, w + 3, h + 3, r + 1.5); g.fillStyle = ch; g.fill();
  rr(g, x, y, w, h, r); g.fillStyle = '#060606'; g.fill();
}
function recessShadow(g, x, y, w, h, r) {
  g.save(); rr(g, x, y, w, h, r); g.clip();
  const sh = g.createLinearGradient(0, y, 0, y + 10); sh.addColorStop(0, 'rgba(0,0,0,.7)'); sh.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = sh; g.fillRect(x, y, w, 10);
  const sl = g.createLinearGradient(x, 0, x + 6, 0); sl.addColorStop(0, 'rgba(0,0,0,.35)'); sl.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = sl; g.fillRect(x, y, 6, h);
  g.restore();
}
// Cover glass: one broad, soft reflection, brightest near the top.
function glass(g, x, y, w, h, r) {
  g.save(); rr(g, x, y, w, h, r); g.clip();
  const gl = g.createLinearGradient(x, y, x + w * 0.4, y + h);
  gl.addColorStop(0, 'rgba(255,255,255,.055)'); gl.addColorStop(0.45, 'rgba(255,255,255,.018)'); gl.addColorStop(0.46, 'rgba(255,255,255,0)');
  g.fillStyle = gl; g.fillRect(x, y, w, h); g.restore();
}
// A mark cut into metal and filled with paint, lit from the upper left: the cut's upper-left wall
// in shadow, its lower-right wall catching light, the paint a little uneven.
function engraved(g, x, y, w, h, paint, seed) {
  g.fillStyle = 'rgba(0,0,0,.72)'; g.fillRect(x - 0.45, y - 0.45, w + 0.45, h + 0.45);
  g.fillStyle = 'rgba(255,248,236,.18)'; g.fillRect(x + w, y + 0.6, 0.45, h);
  g.fillStyle = paint; g.fillRect(x, y, w, h);
  g.fillStyle = `rgba(0,0,0,${(0.12 * hash(seed)).toFixed(3)})`; g.fillRect(x, y + h * hash(seed + 3) * 0.8, w, h * 0.2);
}

// ─────────────────────────── CameraDial: a turned metal drum behind a window ───────────────────────────
// The drum's axis is upright, so a value at angle φ from the index sits at x = cx + R·sin φ; its marks
// narrow by cos φ and are lit by the light's angle; straight knurling on both rims turns with it.
export function dial(el, { lo = -3, hi = 3, step = 0.05, major = 1, value = 0, name = '曝光', unit = 'EV', onTick } = {}) {
  const W = 361, H = 110, top = 44, dh = 58, cx = W / 2, R = W * 0.6, stepPx = 104 * step / major;
  const g = canvasOf(el, W, H);
  let pos = (value - lo) / step, vel = 0, drag = null, last = Math.round(pos), press = 0;
  const steps = Math.round((hi - lo) / step);
  const fmt = v => (v < -0.0001 ? '−' : '+') + Math.abs(v).toFixed(2) + ' ' + unit;
  const dark = hex('#0b0a09'), lit = hex('#5f5b53');
  const ang = x => Math.asin(Math.max(-0.999, Math.min(0.999, (x - cx) / R)));
  const shade = th => Math.min(1, 0.1 + 0.82 * Math.max(0, Math.cos(th + 0.42)) * Math.cos(th) ** 0.5);
  function paint() {
    g.clearRect(0, 0, W, H);
    g.fillStyle = '#9E9A92'; g.font = '500 13px UiSans, UiCjk, sans-serif'; g.textBaseline = 'alphabetic'; g.fillText(name, 0, 22);
    g.fillStyle = '#ECE9E3'; g.font = '500 22px UiSans, sans-serif'; g.textAlign = 'center';
    g.fillText(fmt(lo + Math.round(pos) * step), cx, 24); g.textAlign = 'left';
    cutWindow(g, 0, top, W, dh, 12);
    g.save(); rr(g, 0, top, W, dh, 12); g.clip();
    // the drum, lit from the upper left round its upright axis
    const grad = g.createLinearGradient(0, 0, W, 0);
    for (let i = 0; i <= 28; i++) grad.addColorStop(i / 28, rgb(mix(dark, lit, shade(ang(W * i / 28)))));
    g.fillStyle = grad; g.fillRect(0, top, W, dh);
    // turning marks from the lathe, very faint; the face a touch lighter toward the top
    for (let y = top + 1; y < top + dh; y += 1.25) { g.fillStyle = hash(y) > 0.5 ? 'rgba(255,250,240,.022)' : 'rgba(0,0,0,.05)'; g.fillRect(0, y, W, 0.45); }
    const vg = g.createLinearGradient(0, top, 0, top + dh);
    vg.addColorStop(0, 'rgba(255,248,236,.13)'); vg.addColorStop(0.22, 'rgba(255,248,236,.02)'); vg.addColorStop(0.62, 'rgba(0,0,0,0)'); vg.addColorStop(1, 'rgba(0,0,0,.32)');
    g.fillStyle = vg; g.fillRect(0, top, W, dh);
    // where the light meets the metal: a soft band, not a stripe
    const sx = cx + R * Math.sin(-0.4), sp = g.createLinearGradient(sx - 55, 0, sx + 55, 0);
    sp.addColorStop(0, 'rgba(255,250,240,0)'); sp.addColorStop(0.5, 'rgba(255,250,240,.085)'); sp.addColorStop(1, 'rgba(255,250,240,0)');
    g.fillStyle = sp; g.fillRect(0, top, W, dh);
    // straight knurling: each tooth a lit face and a shaded one, narrowing round the drum
    const kA = 0.034, rot = ((pos * stepPx / R) % kA + kA) % kA;
    for (let a = -1.6; a <= 1.6; a += kA) {
      const ph = a - rot; if (Math.abs(ph) > 1.48) continue;
      const x = cx + R * Math.sin(ph), x2 = cx + R * Math.sin(ph + kA * 0.5), x3 = cx + R * Math.sin(ph + kA);
      const l = Math.max(0, Math.cos(ph + 0.42)) * Math.cos(ph);
      for (const y0 of [top + 1.5, top + dh - 5.5]) {
        g.fillStyle = `rgba(255,248,236,${(0.025 + 0.1 * l).toFixed(3)})`; g.fillRect(x, y0, Math.max(0.3, x2 - x), 4);
        g.fillStyle = `rgba(0,0,0,${(0.16 + 0.2 * Math.cos(ph)).toFixed(3)})`; g.fillRect(x2, y0, Math.max(0.3, x3 - x2), 4);
      }
    }
    g.fillStyle = 'rgba(0,0,0,.5)'; g.fillRect(0, top + 5.5, W, 0.6); g.fillRect(0, top + dh - 6.1, W, 0.6);
    // the scale, engraved and filled
    const first = Math.max(0, Math.floor(pos - R * 1.45 / stepPx)), lastI = Math.min(steps, Math.ceil(pos + R * 1.45 / stepPx));
    for (let i = first; i <= lastI; i++) {
      const ph = (i - pos) * stepPx / R; if (Math.abs(ph) > 1.42) continue;
      const x = cx + R * Math.sin(ph), c = Math.cos(ph), val = lo + i * step;
      const isMaj = Math.abs(val / major - Math.round(val / major)) < 1e-6, isHalf = !isMaj && Math.abs(val * 2 / major - Math.round(val * 2 / major)) < 1e-6;
      const len = isMaj ? 17 : isHalf ? 11 : 7, w = Math.max(0.35, (isMaj ? 1.5 : 1.05) * c);
      const light = (0.5 + 0.5 * Math.max(0, Math.cos(ph + 0.42))) * c ** 1.2;
      engraved(g, x - w / 2, top + 9, w, len, `rgba(236,232,224,${((isMaj ? 0.94 : isHalf ? 0.72 : 0.5) * light).toFixed(3)})`, i);
      if (isMaj) {
        const t = val > 0 ? '+' + val : val < 0 ? '−' + -val : '0';
        g.save(); g.translate(x, top + dh - 12); g.scale(c, 1); g.font = '500 11.5px UiMono, sans-serif'; g.textAlign = 'center';
        g.fillStyle = `rgba(0,0,0,${(0.7 * c).toFixed(3)})`; g.fillText(t, -0.45, -0.45);
        g.fillStyle = `rgba(255,248,236,${(0.16 * c).toFixed(3)})`; g.fillText(t, 0.45, 0.45);
        g.fillStyle = `rgba(236,232,224,${(0.86 * light).toFixed(3)})`; g.fillText(t, 0, 0);
        g.restore();
      }
    }
    // the drum turning away from the window at both ends: into its own shadow, not cut off
    const ends = g.createLinearGradient(0, 0, W, 0);
    ends.addColorStop(0, 'rgba(6,6,6,.78)'); ends.addColorStop(0.08, 'rgba(6,6,6,.3)'); ends.addColorStop(0.22, 'rgba(6,6,6,0)');
    ends.addColorStop(0.78, 'rgba(6,6,6,0)'); ends.addColorStop(0.92, 'rgba(6,6,6,.38)'); ends.addColorStop(1, 'rgba(6,6,6,.85)');
    g.fillStyle = ends; g.fillRect(0, top, W, dh);
    if (press) { g.fillStyle = `rgba(255,246,230,${(0.04 * press).toFixed(3)})`; g.fillRect(0, top, W, dh); }
    g.restore();
    recessShadow(g, 0, top, W, dh, 12);
    // the index: a red line printed under the glass, and a small steel fin above the window
    g.fillStyle = 'rgba(214,108,82,.22)'; g.fillRect(cx - 1.8, top + 3, 3.6, 30);
    g.fillStyle = '#E2735A'; g.fillRect(cx - 0.6, top + 3, 1.2, 30);
    const fin = g.createLinearGradient(cx - 6, 0, cx + 6, 0);
    fin.addColorStop(0, '#3a3834'); fin.addColorStop(0.45, '#c9c4ba'); fin.addColorStop(0.55, '#8c877e'); fin.addColorStop(1, '#2c2a27');
    g.fillStyle = fin; g.beginPath(); g.moveTo(cx - 6, top - 9); g.lineTo(cx + 6, top - 9); g.lineTo(cx + 1, top - 2.5); g.lineTo(cx - 1, top - 2.5); g.closePath(); g.fill();
    g.fillStyle = 'rgba(0,0,0,.5)'; g.fillRect(cx - 6, top - 2.6, 12, 0.7);
    g.fillStyle = '#D2694F'; g.fillRect(cx - 0.6, top - 8, 1.2, 5);
    glass(g, 0, top, W, dh, 12);
  }
  const clampPos = p => Math.max(0, Math.min(steps, p));
  const tick = () => { const n = Math.round(pos); if (n !== last) { last = n; onTick?.(); } };
  function settle() {
    if (drag !== null) return;
    if (Math.abs(vel) > 0.02) { pos = clampPos(pos + vel); vel *= 0.92; tick(); paint(); requestAnimationFrame(settle); return; }
    const d = Math.round(pos) - pos;
    press = Math.max(0, press - 0.12);
    if (Math.abs(d) > 0.01 || press > 0) { pos += d * 0.3; paint(); requestAnimationFrame(settle); } else { pos = Math.round(pos); paint(); }
  }
  el.style.touchAction = 'pan-y'; el.style.cursor = 'grab';
  el.addEventListener('pointerdown', e => { drag = e.clientX; vel = 0; press = 1; paint(); el.setPointerCapture(e.pointerId); });
  el.addEventListener('pointermove', e => {
    if (drag === null) return;
    const k = el.getBoundingClientRect().width / W, dx = (e.clientX - drag) / k; drag = e.clientX;
    const d = -dx / stepPx; pos = clampPos(pos + d); vel = vel * 0.6 + d * 0.4; tick(); paint();
  });
  const up = () => { if (drag === null) return; drag = null; requestAnimationFrame(settle); };
  el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
  document.fonts?.ready.then(paint); paint();
}

// ─────────────────────────── a backlit readout: the histogram ───────────────────────────
// A meter's window: smoked glass with an etched scale, the counts lit from behind in grey (no colour
// next to a photo being judged), the clipped ends as small lamps that light only when there is clipping.
function lumaHist(img, n = 96) {
  const c = document.createElement('canvas'), w = 320, h = Math.round(img.naturalHeight * w / img.naturalWidth);
  c.width = w; c.height = h; const x = c.getContext('2d'); x.drawImage(img, 0, 0, w, h);
  const d = x.getImageData(0, 0, w, h).data, bins = new Float32Array(n);
  let lo = 0, hi = 0;
  for (let i = 0; i < d.length; i += 4) {
    const l = 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
    bins[Math.min(n - 1, (l / 256 * n) | 0)]++; if (l < 2) lo++; if (l > 253) hi++;
  }
  const max = Math.max(...bins.slice(1, n - 1)); return { bins: [...bins].map(b => Math.min(1, b / max)), lo: lo / (d.length / 4), hi: hi / (d.length / 4) };
}
export function histogram(el, img) {
  const W = 361, H = 92, g = canvasOf(el, W, H), x0 = 0, y0 = 4, w = W, h = 84, r = 10;
  const { bins, lo, hi } = lumaHist(img);
  cutWindow(g, x0, y0, w, h, r);
  g.save(); rr(g, x0, y0, w, h, r); g.clip();
  const bg = g.createLinearGradient(0, y0, 0, y0 + h); bg.addColorStop(0, '#121211'); bg.addColorStop(1, '#0a0a09'); g.fillStyle = bg; g.fillRect(x0, y0, w, h);
  const px = x0 + 14, pw = w - 28, py = y0 + 12, ph = h - 26;
  // the etched scale: quarter lines, ticks along the base
  for (let i = 0; i <= 4; i++) { const x = px + pw * i / 4; g.fillStyle = 'rgba(255,248,236,.07)'; g.fillRect(x, py, 0.6, ph); g.fillStyle = 'rgba(0,0,0,.5)'; g.fillRect(x + 0.6, py, 0.5, ph); }
  for (let i = 0; i <= 20; i++) { const x = px + pw * i / 20; g.fillStyle = 'rgba(255,248,236,.16)'; g.fillRect(x, py + ph + 2, 0.6, i % 5 ? 2 : 4); }
  // the counts, lit from behind: a soft glow, then the bars themselves
  const bw = pw / bins.length;
  g.save(); g.filter = 'blur(3px)'; g.fillStyle = 'rgba(232,228,220,.22)';
  bins.forEach((b, i) => g.fillRect(px + i * bw, py + ph * (1 - b), bw, ph * b)); g.restore();
  bins.forEach((b, i) => {
    const bar = g.createLinearGradient(0, py + ph * (1 - b), 0, py + ph);
    bar.addColorStop(0, 'rgba(240,236,228,.92)'); bar.addColorStop(1, 'rgba(200,196,188,.55)');
    g.fillStyle = bar; g.fillRect(px + i * bw + 0.15, py + ph * (1 - b), bw - 0.3, ph * b);
  });
  g.restore();
  recessShadow(g, x0, y0, w, h, r);
  // clipping lamps, off unless clipped
  for (const [cxl, on] of [[x0 + 7, lo > 0.005], [x0 + w - 7, hi > 0.005]]) {
    const lamp = g.createRadialGradient(cxl - 0.6, y0 + 8.4, 0.2, cxl, y0 + 9, 2.6);
    lamp.addColorStop(0, on ? '#fff6ea' : '#3a3936'); lamp.addColorStop(1, on ? 'rgba(240,232,220,.4)' : '#141413');
    g.fillStyle = lamp; g.beginPath(); g.arc(cxl, y0 + 9, 2.4, 0, 7); g.fill();
  }
  glass(g, x0, y0, w, h, r);
}

// ─────────────────────────── the curve: a datasheet's graph under glass ───────────────────────────
// Etched graph paper (quarters and tenths), the photo's histogram faint behind it as a datasheet's
// shading, the curve drawn like ink, its points steel pins with a highlight and a shadow on the glass.
function monotone(pts) {   // Fritsch-Carlson, so the curve never overshoots between points
  const n = pts.length, d = [], m = [];
  for (let i = 0; i < n - 1; i++) d.push((pts[i + 1][1] - pts[i][1]) / (pts[i + 1][0] - pts[i][0]));
  m[0] = d[0]; m[n - 1] = d[n - 2];
  for (let i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
  for (let i = 0; i < n - 1; i++) { if (d[i] === 0) { m[i] = m[i + 1] = 0; continue; } const a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b; if (s > 9) { const t = 3 / Math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; } }
  return x => { let i = 0; while (i < n - 2 && x > pts[i + 1][0]) i++; const h = pts[i + 1][0] - pts[i][0], t = (x - pts[i][0]) / h, t2 = t * t, t3 = t2 * t;
    return (2 * t3 - 3 * t2 + 1) * pts[i][1] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * pts[i + 1][1] + (t3 - t2) * h * m[i + 1]; };
}
export function curve(el, img) {
  const W = 361, H = 220, g = canvasOf(el, W, H), x0 = 0, y0 = 6, w = W, h = 206, r = 12;
  const { bins } = lumaHist(img, 120);
  const px = x0 + 16, pw = w - 32, py = y0 + 14, ph = h - 28;
  let pts = [[0, 0], [0.28, 0.22], [0.72, 0.8], [1, 1]], drag = -1;
  const X = u => px + u * pw, Y = v => py + (1 - v) * ph;
  function paint() {
    g.clearRect(0, 0, W, H);
    cutWindow(g, x0, y0, w, h, r);
    g.save(); rr(g, x0, y0, w, h, r); g.clip();
    const bg = g.createLinearGradient(0, y0, 0, y0 + h); bg.addColorStop(0, '#141413'); bg.addColorStop(1, '#0c0c0b'); g.fillStyle = bg; g.fillRect(x0, y0, w, h);
    // the photo's tones, faint, as a datasheet shades its curve
    const bw = pw / bins.length; g.fillStyle = 'rgba(236,232,224,.075)';
    bins.forEach((b, i) => g.fillRect(px + i * bw, py + ph * (1 - b * 0.9), bw + 0.2, ph * b * 0.9));
    // etched graph paper: tenths faint, quarters firmer
    for (let i = 0; i <= 20; i++) { const f = i / 20, maj = i % 5 === 0, a = maj ? 0.1 : 0.04;
      g.fillStyle = `rgba(255,248,236,${a})`; g.fillRect(X(f), py, 0.6, ph); g.fillRect(px, Y(f), pw, 0.6);
      g.fillStyle = `rgba(0,0,0,${a * 4})`; g.fillRect(X(f) + 0.6, py, 0.5, ph); g.fillRect(px, Y(f) + 0.6, pw, 0.5); }
    // the identity, dotted, for reference
    g.setLineDash([2, 4]); g.strokeStyle = 'rgba(236,232,224,.18)'; g.lineWidth = 1; g.beginPath(); g.moveTo(X(0), Y(0)); g.lineTo(X(1), Y(1)); g.stroke(); g.setLineDash([]);
    // the curve: ink with a faint glow under the glass
    const f = monotone(pts);
    const path = () => { g.beginPath(); for (let i = 0; i <= 160; i++) { const u = i / 160, v = Math.max(0, Math.min(1, f(u))); i ? g.lineTo(X(u), Y(v)) : g.moveTo(X(u), Y(v)); } };
    g.save(); g.filter = 'blur(2.5px)'; path(); g.strokeStyle = 'rgba(240,236,228,.35)'; g.lineWidth = 3; g.stroke(); g.restore();
    path(); g.strokeStyle = '#EEEAE2'; g.lineWidth = 1.6; g.lineJoin = 'round'; g.stroke();
    g.restore();
    recessShadow(g, x0, y0, w, h, r);
    glass(g, x0, y0, w, h, r);
    // the points: steel pins standing on the glass
    pts.forEach(([u, v], i) => {
      const x = X(u), y = Y(v), rad = i === drag ? 8 : 6.5;
      g.save(); g.filter = 'blur(2px)'; g.fillStyle = 'rgba(0,0,0,.6)'; g.beginPath(); g.ellipse(x + 1.5, y + 2.5, rad, rad * 0.8, 0, 0, 7); g.fill(); g.restore();
      const pin = g.createRadialGradient(x - rad * 0.38, y - rad * 0.42, rad * 0.08, x, y, rad);
      pin.addColorStop(0, '#ffffff'); pin.addColorStop(0.25, '#e4e0d7'); pin.addColorStop(0.75, '#8d887f'); pin.addColorStop(1, '#4a4741');
      g.fillStyle = pin; g.beginPath(); g.arc(x, y, rad, 0, 7); g.fill();
      g.strokeStyle = 'rgba(0,0,0,.45)'; g.lineWidth = 0.6; g.stroke();
    });
  }
  const at = e => { const r = el.getBoundingClientRect(), k = r.width / W; return [((e.clientX - r.left) / k - px) / pw, 1 - ((e.clientY - r.top) / k - py) / ph]; };
  el.style.touchAction = 'none'; el.style.cursor = 'pointer';
  el.addEventListener('pointerdown', e => {
    const [u, v] = at(e); let best = -1, bd = 0.08;
    pts.forEach(([a, b], i) => { const d = Math.hypot((a - u) * pw / ph, b - v); if (d < bd) { bd = d; best = i; } });
    if (best < 0) { pts.push([Math.max(0.02, Math.min(0.98, u)), Math.max(0, Math.min(1, v))]); pts.sort((a, b) => a[0] - b[0]); best = pts.findIndex(p => p[0] === Math.max(0.02, Math.min(0.98, u))); }
    drag = best; el.setPointerCapture(e.pointerId); paint();
  });
  el.addEventListener('pointermove', e => {
    if (drag < 0) return; let [u, v] = at(e); v = Math.max(0, Math.min(1, v));
    if (drag === 0) u = 0; else if (drag === pts.length - 1) u = 1; else u = Math.max(pts[drag - 1][0] + 0.03, Math.min(pts[drag + 1][0] - 0.03, u));
    pts[drag] = [u, v]; paint();
  });
  const up = () => { drag = -1; paint(); };
  el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
  paint();
}

// ─────────────────────────── a 135 cassette: printed tin, felt lip, the leader ───────────────────────────
const KIND = {
  negative: { body: '#D8C39B', dark: '#9C8358', band: '#8A6A3C', ink: '#2A2016', film: [176, 104, 52] },
  slide:    { body: '#A7B8C6', dark: '#6E8397', band: '#3E5468', ink: '#152230', film: [70, 76, 84] },
  cine:     { body: '#4A4843', dark: '#262522', band: '#B9A27A', ink: '#E9E2D2', film: [40, 34, 30] },
  mono:     { body: '#E4E0D6', dark: '#ABA698', band: '#3A3834', ink: '#1D1B18', film: [70, 70, 68] },
};
const cyl = (g, x, w, stops, y, h) => { const gr = g.createLinearGradient(x, 0, x + w, 0); stops.forEach(([t, c]) => gr.addColorStop(t, c)); g.fillStyle = gr; g.fillRect(x, y, w, h); };
function dxBits(speed) { const n = +speed || 100; const v = (Math.imul(n, 2654435761) ^ 0x5a5) >>> 0; return [...Array(12)].map((_, i) => i % 6 === 0 || ((v >>> i) & 1) === 1); }
export function cassette(el, kindName, speed, chosen) {
  const g = canvasOf(el, 40, 48), k = KIND[kindName];
  let lift = chosen ? 1 : 0, target = lift;
  function paint() {
    g.clearRect(0, 0, 40, 48);
    const cw = 21, ch = 33, x = 3, y = 48 - ch - 5 - 5 * lift;
    g.save(); g.filter = 'blur(1.6px)'; g.fillStyle = `rgba(0,0,0,${0.55 - 0.2 * lift})`;
    g.beginPath(); g.ellipse(x + cw / 2 + 2, 45, cw / 2 + 3 - lift, 2.4, 0, 0, 7); g.fill(); g.restore();
    const L = 5 + 11 * lift, fy = y + 5, fh = ch - 10, fx = x + cw - 1;
    if (L > 0.5) {
      g.save(); const f = k.film;
      g.beginPath(); g.moveTo(fx, fy); g.lineTo(fx + 2.5, fy);
      g.bezierCurveTo(fx + 5, fy, fx + 5, fy + fh * 0.5, fx + 7, fy + fh * 0.5);
      g.lineTo(fx + L - 1.5, fy + fh * 0.5); g.quadraticCurveTo(fx + L, fy + fh * 0.5, fx + L, fy + fh * 0.62);
      g.lineTo(fx + L, fy + fh - 1.2); g.quadraticCurveTo(fx + L, fy + fh, fx + L - 1.2, fy + fh); g.lineTo(fx, fy + fh); g.closePath();
      const fg = g.createLinearGradient(0, fy, 0, fy + fh);
      fg.addColorStop(0, rgb(mix(f, [255, 255, 255], 0.12), 0.94)); fg.addColorStop(0.5, rgb(f, 0.92)); fg.addColorStop(1, rgb(mix(f, [0, 0, 0], 0.35), 0.95));
      g.fillStyle = fg; g.fill(); g.clip();
      g.fillStyle = 'rgba(255,255,255,.16)'; g.fillRect(fx, fy + fh * 0.5 + 0.4, L, 0.5);
      g.fillStyle = 'rgba(0,0,0,.45)';
      for (let px = fx + 2.2; px < fx + L - 1; px += 2.6) { rr(g, px, fy + fh - 2.4, 1.3, 1.6, 0.4); g.fill(); }
      g.restore();
    }
    g.fillStyle = '#0c0b0a'; rr(g, x + cw - 2.2, y + 3, 3.2, ch - 6, 1); g.fill();
    g.fillStyle = 'rgba(255,255,255,.05)'; g.fillRect(x + cw - 1.6, y + 4, 0.5, ch - 8);
    const b = hex(k.body), d = hex(k.dark);
    g.save(); rr(g, x, y + 3, cw - 1.5, ch - 6, 1.6); g.clip();
    cyl(g, x, cw - 1.5, [[0, rgb(mix(d, [0, 0, 0], 0.45))], [0.14, rgb(d)], [0.3, rgb(mix(b, [255, 255, 255], 0.12))], [0.36, rgb(mix(b, [255, 255, 255], 0.32))],
      [0.42, rgb(b)], [0.72, rgb(d)], [1, rgb(mix(d, [0, 0, 0], 0.55))]], y + 3, ch - 6);
    g.globalCompositeOperation = 'multiply'; g.fillStyle = rgb(hex(k.band)); g.fillRect(x, y + ch * 0.6, cw, ch * 0.13); g.globalCompositeOperation = 'source-over';
    const bits = dxBits(speed);
    for (let i = 0; i < 12; i++) {
      const cxx = x + 2 + (i % 6) * 2.6, t = (cxx - x) / (cw - 1.5) * 2 - 1, c = Math.sqrt(Math.max(0.05, 1 - t * t)), cy = y + 6 + ((i / 6) | 0) * 2.6;
      if (bits[i]) { const mg = g.createLinearGradient(0, cy, 0, cy + 2); mg.addColorStop(0, '#efeae0'); mg.addColorStop(1, '#a29c90'); g.fillStyle = mg; } else g.fillStyle = 'rgba(18,17,15,.92)';
      rr(g, cxx, cy, 2 * c, 2, 0.35); g.fill();
    }
    if (speed) { g.font = '700 7px UiSans, sans-serif'; g.textAlign = 'center'; g.fillStyle = k.ink; g.save(); g.translate(x + (cw - 1.5) * 0.46, y + ch * 0.48); g.scale(0.92, 1); g.fillText(speed, 0, 0); g.restore(); }
    const hl = g.createLinearGradient(x + cw * 0.26, 0, x + cw * 0.44, 0);
    hl.addColorStop(0, 'rgba(255,255,255,0)'); hl.addColorStop(0.5, 'rgba(255,255,255,.28)'); hl.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = hl; g.fillRect(x, y, cw, ch); g.restore();
    const cap = [[0, '#1e1d1b'], [0.18, '#4b4943'], [0.34, '#b9b5ac'], [0.42, '#d9d5cc'], [0.6, '#77736b'], [1, '#1c1b19']];
    for (const cy of [y, y + ch - 3.6]) {
      g.save(); rr(g, x - 0.7, cy, cw + 0.4, 3.6, 1.4); g.clip(); cyl(g, x - 0.7, cw + 0.4, cap, cy, 3.6);
      g.fillStyle = 'rgba(255,255,255,.22)'; g.fillRect(x - 0.7, cy + 0.3, cw + 0.4, 0.5); g.fillStyle = 'rgba(0,0,0,.35)'; g.fillRect(x - 0.7, cy + 3.0, cw + 0.4, 0.6); g.restore();
    }
    g.save(); rr(g, x + 6.5, y - 3.4, 7.5, 4, 1.2); g.clip();
    cyl(g, x + 6.5, 7.5, [[0, '#1b1a18'], [0.35, '#8b877e'], [0.5, '#a8a49b'], [1, '#1d1c1a']], y - 3.4, 4);
    g.fillStyle = 'rgba(0,0,0,.35)'; for (let r = 0; r < 4; r++) g.fillRect(x + 7.5 + r * 1.7, y - 3.4, 0.5, 4); g.restore();
    g.fillStyle = 'rgba(0,0,0,.4)'; g.fillRect(x, y + 3, cw - 1.5, 0.6);
  }
  const step = () => { lift += (target - lift) * 0.18; paint(); if (Math.abs(target - lift) > 0.003) requestAnimationFrame(step); else { lift = target; paint(); } };
  document.fonts?.ready.then(paint); paint();
  return { set(on) { target = on ? 1 : 0; requestAnimationFrame(step); } };
}
