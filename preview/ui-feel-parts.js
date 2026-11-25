// Skeuomorphic parts for preview/ui-feel.html, drawn the way the Compose code will draw them
// (gradients, round rects and paths on a canvas, one light from the upper left). Keep in step with
// Dial.kt (CameraDial), FilmPack.kt (cassette, paper box) and the Material keys in Pill.kt.
const DPR = Math.max(2, devicePixelRatio || 1);
const canvasOf = (el, w, h) => { el.width = w * DPR; el.height = h * DPR; el.style.width = w + 'px'; el.style.height = h + 'px';
  const g = el.getContext('2d'); g.setTransform(DPR, 0, 0, DPR, 0, 0); return g; };
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const rgb = (c, a = 1) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;
const hex = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const rr = (g, x, y, w, h, r) => { g.beginPath(); g.roundRect(x, y, w, h, r); };

// ─────────────────────────── CameraDial: a turned metal drum in a window ───────────────────────────
// The drum's axis is upright, so a value at angle φ from the index sits at x = cx + R·sin φ, its
// marks narrowed by cos φ and lit by the light's angle; knurling on both rims turns with it; the
// window is cut into the panel (shadow under its top edge, a lit lip at the bottom).
export function dial(el, { lo = -3, hi = 3, step = 0.05, major = 1, value = 0, name = '曝光', unit = 'EV', onTick } = {}) {
  const W = 361, H = 104, top = 40, dh = 58, cx = W / 2, R = W * 0.56, stepPx = 104 * step / major;
  const g = canvasOf(el, W, H);
  let pos = (value - lo) / step, vel = 0, drag = null, last = Math.round(pos);
  const steps = Math.round((hi - lo) / step);
  const fmt = v => (v < -0.0001 ? '−' : '+') + Math.abs(v).toFixed(2) + ' ' + unit;   // as the app: +0.00 EV
  const body0 = hex('#0d0c0b'), body1 = hex('#57544d');
  function paint() {
    g.clearRect(0, 0, W, H);
    // name and readout
    g.fillStyle = '#9E9A92'; g.font = '500 13px UiSans, UiCjk, sans-serif'; g.textBaseline = 'alphabetic'; g.fillText(name, 0, 22);
    const v = lo + Math.round(pos) * step;
    g.fillStyle = '#ECE9E3'; g.font = '500 22px UiSans, sans-serif'; g.textAlign = 'center'; g.fillText(fmt(v), cx, 26); g.textAlign = 'left';
    // the window, cut into the panel
    rr(g, 0, top, W, dh, 12); g.fillStyle = '#070707'; g.fill();
    g.save(); rr(g, 2, top + 2, W - 4, dh - 4, 10); g.clip();
    // the drum: diffuse light from the upper left round an upright cylinder
    const grad = g.createLinearGradient(2, 0, W - 2, 0);
    for (let i = 0; i <= 24; i++) {
      const x = 2 + (W - 4) * i / 24, s = Math.max(-0.999, Math.min(0.999, (x - cx) / R)), th = Math.asin(s);
      const diff = Math.max(0, Math.cos(th + 0.38)), amb = 0.16;
      grad.addColorStop(i / 24, rgb(mix(body0, body1, Math.min(1, amb + 0.78 * diff * Math.cos(th) ** 0.6))));
    }
    g.fillStyle = grad; g.fillRect(0, top, W, dh);
    // lathe rings, very faint
    for (let y = top + 3; y < top + dh; y += 1.5) { g.fillStyle = (y | 0) % 3 ? 'rgba(255,250,240,.018)' : 'rgba(0,0,0,.05)'; g.fillRect(0, y, W, 0.5); }
    // top light, bottom falling away
    const vg = g.createLinearGradient(0, top, 0, top + dh);
    vg.addColorStop(0, 'rgba(255,248,236,.16)'); vg.addColorStop(0.18, 'rgba(255,248,236,.03)'); vg.addColorStop(0.6, 'rgba(0,0,0,0)'); vg.addColorStop(1, 'rgba(0,0,0,.35)');
    g.fillStyle = vg; g.fillRect(0, top, W, dh);
    // a soft specular band where the light meets the drum
    const sx = cx + R * Math.sin(-0.36), sp = g.createLinearGradient(sx - 40, 0, sx + 40, 0);
    sp.addColorStop(0, 'rgba(255,250,240,0)'); sp.addColorStop(0.5, 'rgba(255,250,240,.075)'); sp.addColorStop(1, 'rgba(255,250,240,0)');
    g.fillStyle = sp; g.fillRect(0, top, W, dh);
    // knurling on both rims
    const kA = 0.055, rot = (pos * stepPx / R) % kA;
    for (let a = -1.5; a <= 1.5; a += kA) {
      const ph = a - rot; if (Math.abs(ph) > 1.45) continue;
      const x = cx + R * Math.sin(ph), c = Math.cos(ph), w = Math.max(0.35, 1.5 * c);
      for (const y0 of [top + 2, top + dh - 7]) {
        g.fillStyle = `rgba(255,248,236,${(0.16 * c * Math.max(0, Math.cos(ph + 0.4))).toFixed(3)})`; g.fillRect(x, y0, w * 0.55, 5);
        g.fillStyle = `rgba(0,0,0,${(0.55 * c).toFixed(3)})`; g.fillRect(x + w * 0.6, y0, w * 0.5, 5);
      }
    }
    // the marks: cut into the metal (a shadow), filled with paint
    const first = Math.max(0, Math.floor(pos - R * 1.4 / stepPx)), lastI = Math.min(steps, Math.ceil(pos + R * 1.4 / stepPx));
    for (let i = first; i <= lastI; i++) {
      const ph = (i - pos) * stepPx / R; if (Math.abs(ph) > 1.4) continue;
      const x = cx + R * Math.sin(ph), c = Math.cos(ph), val = lo + i * step;
      const isMaj = Math.abs(val / major - Math.round(val / major)) < 1e-6, isHalf = !isMaj && Math.abs(val * 2 / major - Math.round(val * 2 / major)) < 1e-6;
      const len = isMaj ? 18 : isHalf ? 12 : 8, w = Math.max(0.4, (isMaj ? 1.6 : 1.1) * c), y0 = top + 7;
      const lit = (0.55 + 0.45 * Math.max(0, Math.cos(ph + 0.38))) * c ** 1.3;
      g.fillStyle = `rgba(0,0,0,${(0.7 * c).toFixed(3)})`; g.fillRect(x + 0.6, y0 + 0.7, w, len);
      g.fillStyle = `rgba(238,234,226,${((isMaj ? 0.95 : 0.55) * lit).toFixed(3)})`; g.fillRect(x, y0, w, len);
      if (isMaj) {
        g.save(); g.translate(x, top + dh - 14); g.scale(c, 1);
        g.font = '500 11px UiMono, sans-serif'; g.textAlign = 'center';
        g.fillStyle = `rgba(0,0,0,${(0.6 * c).toFixed(3)})`; g.fillText(val > 0 ? '+' + val : val < 0 ? '−' + -val : '0', 0.6, 0.8);
        g.fillStyle = `rgba(238,234,226,${(0.82 * lit).toFixed(3)})`; g.fillText(val > 0 ? '+' + val : val < 0 ? '−' + -val : '0', 0, 0);
        g.restore();
      }
    }
    // the drum's ends falling into the dark
    const ends = g.createLinearGradient(0, 0, W, 0);
    ends.addColorStop(0, 'rgba(5,5,5,.92)'); ends.addColorStop(0.12, 'rgba(5,5,5,.25)'); ends.addColorStop(0.3, 'rgba(5,5,5,0)');
    ends.addColorStop(0.7, 'rgba(5,5,5,0)'); ends.addColorStop(0.88, 'rgba(5,5,5,.3)'); ends.addColorStop(1, 'rgba(5,5,5,.94)');
    g.fillStyle = ends; g.fillRect(0, top, W, dh);
    g.restore();
    // the cut: shadow under the top edge, a lit lip along the bottom
    g.save(); rr(g, 0, top, W, dh, 12); g.clip();
    const sh = g.createLinearGradient(0, top, 0, top + 8); sh.addColorStop(0, 'rgba(0,0,0,.75)'); sh.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = sh; g.fillRect(0, top, W, 8); g.restore();
    g.strokeStyle = 'rgba(255,248,236,.07)'; g.lineWidth = 1; g.beginPath(); g.moveTo(12, top + dh + 0.5); g.lineTo(W - 12, top + dh + 0.5); g.stroke();
    // the index: a fine warm line on the cover glass and its mark above
    g.fillStyle = '#D2694F'; g.beginPath(); g.moveTo(cx - 5, top - 7); g.lineTo(cx + 5, top - 7); g.lineTo(cx, top - 1); g.fill();
    g.fillStyle = 'rgba(210,105,79,.18)'; g.fillRect(cx - 2, top + 2, 4, 26);
    g.fillStyle = 'rgba(232,120,92,.95)'; g.fillRect(cx - 0.6, top + 2, 1.2, 26);
    // cover glass: one soft reflection across the top half
    g.save(); rr(g, 2, top + 2, W - 4, dh - 4, 10); g.clip();
    const gl = g.createLinearGradient(0, top, 0, top + dh * 0.5); gl.addColorStop(0, 'rgba(255,255,255,.05)'); gl.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = gl; g.fillRect(0, top, W, dh * 0.5); g.restore();
  }
  const clampPos = p => Math.max(0, Math.min(steps, p));
  const tick = () => { const n = Math.round(pos); if (n !== last) { last = n; onTick?.(); } };
  function fling() {
    if (drag !== null) return;
    if (Math.abs(vel) > 0.02) { pos = clampPos(pos + vel); vel *= 0.92; tick(); paint(); requestAnimationFrame(fling); return; }
    const target = Math.round(pos); const d = target - pos;
    if (Math.abs(d) > 0.01) { pos += d * 0.3; paint(); requestAnimationFrame(fling); } else { pos = target; paint(); }
  }
  el.style.touchAction = 'pan-y'; el.style.cursor = 'grab';
  el.addEventListener('pointerdown', e => { drag = e.clientX; vel = 0; el.setPointerCapture(e.pointerId); });
  el.addEventListener('pointermove', e => {
    if (drag === null) return;
    const k = el.getBoundingClientRect().width / W, dx = (e.clientX - drag) / k; drag = e.clientX;
    const d = -dx / stepPx; pos = clampPos(pos + d); vel = vel * 0.6 + d * 0.4; tick(); paint();
  });
  const up = () => { if (drag === null) return; drag = null; requestAnimationFrame(fling); };
  el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
  document.fonts?.ready.then(paint); paint();
}

// ─────────────────────────── a 135 cassette: printed tin, felt lip, the leader ───────────────────────────
const KIND = {
  negative: { body: '#D8C39B', dark: '#9C8358', band: '#8A6A3C', ink: '#2A2016', film: [176, 104, 52], edge: 'C-41' },
  slide:    { body: '#A7B8C6', dark: '#6E8397', band: '#3E5468', ink: '#152230', film: [70, 76, 84], edge: 'E-6' },
  cine:     { body: '#4A4843', dark: '#262522', band: '#B9A27A', ink: '#E9E2D2', film: [40, 34, 30], edge: 'ECN-2' },
  mono:     { body: '#E4E0D6', dark: '#ABA698', band: '#3A3834', ink: '#1D1B18', film: [70, 70, 68], edge: 'B&W' },
};
const cyl = (g, x, w, stops, y, h) => {   // a vertical cylinder lit from the left: base colour stops across
  const gr = g.createLinearGradient(x, 0, x + w, 0); stops.forEach(([t, c]) => gr.addColorStop(t, c)); g.fillStyle = gr; g.fillRect(x, y, w, h);
};
function dxBits(speed) { const n = +speed || 100; const v = (Math.imul(n, 2654435761) ^ 0x5a5) >>> 0; return [...Array(12)].map((_, i) => i % 6 === 0 || ((v >>> i) & 1) === 1); }
export function cassette(el, kindName, speed, chosen) {
  const g = canvasOf(el, 40, 48), k = KIND[kindName];
  let lift = chosen ? 1 : 0, target = lift;
  function paint() {
    g.clearRect(0, 0, 40, 48);
    const u = 1, cw = 21, ch = 33, x = 3, y = 48 - ch - 5 - 5 * lift;
    // contact shadow, soft, smaller as it lifts
    g.save(); g.filter = 'blur(1.6px)'; g.fillStyle = `rgba(0,0,0,${0.55 - 0.2 * lift})`;
    g.beginPath(); g.ellipse(x + cw / 2 + 2, 45, cw / 2 + 3 - lift, 2.4, 0, 0, 7); g.fill(); g.restore();
    // the leader out of the felt lip: full width for a little, then the tongue (half width), rounded
    const L = 5 + 11 * lift, fy = y + 5, fh = ch - 10, fx = x + cw - 1;
    if (L > 0.5) {
      g.save();
      const f = k.film;
      g.beginPath(); g.moveTo(fx, fy); g.lineTo(fx + 2.5, fy);
      g.bezierCurveTo(fx + 5, fy, fx + 5, fy + fh * 0.5, fx + 7, fy + fh * 0.5);
      g.lineTo(fx + L - 1.5, fy + fh * 0.5); g.quadraticCurveTo(fx + L, fy + fh * 0.5, fx + L, fy + fh * 0.62);
      g.lineTo(fx + L, fy + fh - 1.2); g.quadraticCurveTo(fx + L, fy + fh, fx + L - 1.2, fy + fh); g.lineTo(fx, fy + fh); g.closePath();
      const fg = g.createLinearGradient(0, fy, 0, fy + fh);
      fg.addColorStop(0, rgb(mix(f, [255, 255, 255], 0.12), 0.94)); fg.addColorStop(0.5, rgb(f, 0.92)); fg.addColorStop(1, rgb(mix(f, [0, 0, 0], 0.35), 0.95));
      g.fillStyle = fg; g.fill();
      g.clip();
      g.fillStyle = 'rgba(255,255,255,.16)'; g.fillRect(fx, fy + fh * 0.5 + 0.4, L, 0.5);   // light on the base's edge
      g.fillStyle = 'rgba(0,0,0,.45)';                                                       // perforations along the tongue's bottom edge
      for (let px = fx + 2.2; px < fx + L - 1; px += 2.6) { rr(g, px, fy + fh - 2.4, 1.3, 1.6, 0.4); g.fill(); }
      g.restore();
    }
    // the felt light trap, black velvet
    g.fillStyle = '#0c0b0a'; rr(g, x + cw - 2.2, y + 3, 3.2, ch - 6, 1); g.fill();
    g.fillStyle = 'rgba(255,255,255,.05)'; g.fillRect(x + cw - 1.6, y + 4, 0.5, ch - 8);
    // the shell: printed tin round an upright cylinder
    const b = hex(k.body), d = hex(k.dark);
    g.save(); rr(g, x, y + 3, cw - 1.5, ch - 6, 1.6); g.clip();
    cyl(g, x, cw - 1.5, [[0, rgb(mix(d, [0, 0, 0], 0.45))], [0.14, rgb(d)], [0.3, rgb(mix(b, [255, 255, 255], 0.12))], [0.36, rgb(mix(b, [255, 255, 255], 0.32))],
      [0.42, rgb(b)], [0.72, rgb(d)], [1, rgb(mix(d, [0, 0, 0], 0.55))]], y + 3, ch - 6);
    // the band, shaded as the shell is
    g.globalCompositeOperation = 'multiply';
    const bd = hex(k.band); g.fillStyle = rgb(bd); g.fillRect(x, y + ch * 0.6, cw, ch * 0.13);
    g.globalCompositeOperation = 'source-over';
    // DX contacts: bare metal and paint, narrowing toward the shell's edges
    const bits = dxBits(speed);
    for (let i = 0; i < 12; i++) {
      const cxx = x + 2 + (i % 6) * 2.6, t = (cxx - x) / (cw - 1.5) * 2 - 1, c = Math.sqrt(Math.max(0.05, 1 - t * t));
      const cy = y + 6 + ((i / 6) | 0) * 2.6;
      if (bits[i]) { const mg = g.createLinearGradient(0, cy, 0, cy + 2); mg.addColorStop(0, '#efeae0'); mg.addColorStop(1, '#a29c90'); g.fillStyle = mg; }
      else g.fillStyle = 'rgba(18,17,15,.92)';
      rr(g, cxx, cy, 2 * c, 2, 0.35); g.fill();
    }
    // the speed, printed
    if (speed) {
      g.font = `700 7px UiSans, sans-serif`; g.textAlign = 'center'; g.fillStyle = k.ink;
      g.save(); g.translate(x + (cw - 1.5) * 0.46, y + ch * 0.48); g.scale(0.92, 1); g.fillText(speed, 0, 0); g.restore();
    }
    // the light's reflection, soft
    const hl = g.createLinearGradient(x + cw * 0.26, 0, x + cw * 0.44, 0);
    hl.addColorStop(0, 'rgba(255,255,255,0)'); hl.addColorStop(0.5, 'rgba(255,255,255,.28)'); hl.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = hl; g.fillRect(x, y, cw, ch);
    g.restore();
    // crimped end caps: turned steel
    const cap = [[0, '#1e1d1b'], [0.18, '#4b4943'], [0.34, '#b9b5ac'], [0.42, '#d9d5cc'], [0.6, '#77736b'], [1, '#1c1b19']];
    for (const cy of [y, y + ch - 3.6]) {
      g.save(); rr(g, x - 0.7, cy, cw + 0.4, 3.6, 1.4); g.clip(); cyl(g, x - 0.7, cw + 0.4, cap, cy, 3.6);
      g.fillStyle = 'rgba(255,255,255,.22)'; g.fillRect(x - 0.7, cy + 0.3, cw + 0.4, 0.5);
      g.fillStyle = 'rgba(0,0,0,.35)'; g.fillRect(x - 0.7, cy + 3.0, cw + 0.4, 0.6); g.restore();
    }
    // the spool's knob, ridged
    g.save(); rr(g, x + 6.5, y - 3.4, 7.5, 4, 1.2); g.clip();
    cyl(g, x + 6.5, 7.5, [[0, '#1b1a18'], [0.35, '#8b877e'], [0.5, '#a8a49b'], [1, '#1d1c1a']], y - 3.4, 4);
    g.fillStyle = 'rgba(0,0,0,.35)'; for (let r = 0; r < 4; r++) g.fillRect(x + 7.5 + r * 1.7, y - 3.4, 0.5, 4);
    g.restore();
    g.fillStyle = 'rgba(0,0,0,.4)'; g.fillRect(x, y + 3, cw - 1.5, 0.6);
  }
  const step = () => { lift += (target - lift) * 0.18; paint(); if (Math.abs(target - lift) > 0.003) requestAnimationFrame(step); else { lift = target; paint(); } };
  document.fonts?.ready.then(paint); paint();
  return { set(on) { target = on ? 1 : 0; requestAnimationFrame(step); } };
}
