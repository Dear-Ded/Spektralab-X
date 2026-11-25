// Six frames of 35 mm film in the dark, one RAW through six stocks. Each frame is the
// engine's render on its own piece of film (rebate, sprocket holes, edge print); the strip
// bends through space and the camera travels along it as the page scrolls.
import {
  WebGLRenderer, Scene, PerspectiveCamera, Mesh, BufferGeometry, Float32BufferAttribute, MeshBasicMaterial,
  CanvasTexture, SRGBColorSpace, CatmullRomCurve3, Vector3, Fog, DoubleSide, LinearMipmapLinearFilter,
} from 'three';

// 35 mm: a 36 x 24 frame on 35 mm stock, 38 mm pitch
const FW = 36, FH = 24, SH = 35, PITCH = 38;
const PX = 1200 / FW;                                // texture px per mm

function frameTexture(img, edge, n) {
  const w = Math.round(PITCH * PX), h = Math.round(SH * PX);
  const c = document.createElement('canvas'); c.width = w; c.height = h;
  const g = c.getContext('2d');
  // film base, a little lighter toward the middle like backlit acetate
  const base = g.createLinearGradient(0, 0, 0, h);
  base.addColorStop(0, '#120c08'); base.addColorStop(.5, '#1d140d'); base.addColorStop(1, '#120c08');
  g.fillStyle = base; g.fillRect(0, 0, w, h);
  // the image
  const fx = (w - FW * PX) / 2, fy = (h - FH * PX) / 2;
  g.drawImage(img, fx, fy, FW * PX, FH * PX);
  // sprocket holes (KS perforation, 4.75 mm pitch): punched through, so transparent
  g.globalCompositeOperation = 'destination-out';
  const hw = 1.98 * PX, hh = 2.79 * PX, r = 0.5 * PX, pitch = 4.75 * PX;
  for (const y of [1.0 * PX, h - 1.0 * PX - hh]) {
    for (let x = pitch / 2 - hw / 2; x < w; x += pitch) {
      g.beginPath(); g.roundRect(x, y, hw, hh, r); g.fill();
    }
  }
  g.globalCompositeOperation = 'source-over';
  // edge print, burnt in like the latent image on the rebate
  g.fillStyle = 'rgba(255, 168, 64, .82)';
  g.font = `500 ${Math.round(1.55 * PX)}px "Barlow Semi Condensed", sans-serif`;
  g.textBaseline = 'middle';
  const ey = h - (h - FH * PX) / 4 + 0.15 * PX;
  g.fillText(edge, fx + 1.2 * PX, ey);
  g.textAlign = 'right';
  g.fillText(`▸ ${n}   ${n}A`, fx + FW * PX - 1.2 * PX, ey);
  const t = new CanvasTexture(c);
  t.colorSpace = SRGBColorSpace;
  t.anisotropy = 8;
  t.minFilter = LinearMipmapLinearFilter;
  return t;
}

// a plane bent along the curve: x runs along the strip (mm from the strip's start), y across it
function bentFrame(curve, len, x0, segs = 28) {
  const pos = [], uv = [], idx = [];
  const up = new Vector3(), p = new Vector3(), tan = new Vector3(), side = new Vector3();
  const world = new Vector3(0, 1, 0);
  for (let i = 0; i <= segs; i++) {
    const s = (x0 + PITCH * i / segs) / len;
    curve.getPointAt(s, p); curve.getTangentAt(s, tan);
    side.crossVectors(tan, world).normalize();      // the film's face looks along side
    up.crossVectors(side, tan).normalize();
    for (const j of [0, 1]) {
      const y = (j - 0.5) * SH;
      pos.push(p.x + up.x * y, p.y + up.y * y, p.z + up.z * y);
      uv.push(i / segs, j);
    }
  }
  for (let i = 0; i < segs; i++) { const a = i * 2; idx.push(a, a + 2, a + 1, a + 1, a + 2, a + 3); }
  const geo = new BufferGeometry();
  geo.setAttribute('position', new Float32BufferAttribute(pos, 3));
  geo.setAttribute('uv', new Float32BufferAttribute(uv, 2));
  geo.setIndex(idx);
  return geo;
}

export async function strip(root, frames, { onFrame }) {
  const canvas = root.querySelector('[data-films-gl]');
  let renderer;
  try { renderer = new WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'high-performance' }); }
  catch { return null; }
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.outputColorSpace = SRGBColorSpace;

  await document.fonts?.load('500 20px "Barlow Semi Condensed"').catch(() => {});
  const imgs = await Promise.all(frames.map(f => new Promise((res, rej) => {
    const im = new Image(); im.decoding = 'async'; im.onload = () => res(im); im.onerror = rej; im.src = f.src;
  })));

  const N = frames.length, GAP = 10, len = N * PITCH + (N - 1) * GAP;
  // a long, lazy S through the dark, falling away from the viewer at both ends
  const pts = [];
  for (let i = 0; i <= 12; i++) {
    const t = i / 12, x = (t - 0.5) * len * 0.92;
    pts.push(new Vector3(x, Math.sin(t * Math.PI * 1.6) * 7, -Math.cos((t - 0.5) * Math.PI * 1.15) * 46 + 28));
  }
  const curve = new CatmullRomCurve3(pts, false, 'centripetal');
  const L = curve.getLength();

  const scene = new Scene();
  scene.fog = new Fog(0x050505, 90, 240);
  const cam = new PerspectiveCamera(32, 1, 1, 600);
  const centres = [];
  frames.forEach((f, i) => {
    const x0 = i * (PITCH + GAP) * (L / len);
    const mat = new MeshBasicMaterial({ map: frameTexture(imgs[i], f.edge, 12 + i * 2), side: DoubleSide, transparent: true, alphaTest: 0.5 });
    scene.add(new Mesh(bentFrame(curve, L, x0), mat));
    centres.push((x0 + PITCH / 2) / L);
  });

  const size = () => {
    const w = root.clientWidth, h = innerHeight;
    renderer.setSize(w, h, false);
    cam.aspect = w / h;
    // keep one frame about 60 % of the narrower side
    cam.fov = w < h ? 46 : 30;
    cam.updateProjectionMatrix();
  };
  addEventListener('resize', size);
  size();

  const p = new Vector3(), tan = new Vector3(), side = new Vector3(), eye = new Vector3(), look = new Vector3();
  let shown = -1, f = 0;
  return {
    // progress 0..1 through the section; t in seconds
    tick(progress, t) {
      const pos = Math.max(0, Math.min(1, progress)) * (N - 1);
      f += (pos - f) * 0.09;
      const i0 = Math.floor(f), k = f - i0;
      const s = centres[i0] + (centres[Math.min(N - 1, i0 + 1)] - centres[i0]) * k;
      curve.getPointAt(s, p); curve.getTangentAt(s, tan);
      side.crossVectors(tan, new Vector3(0, 1, 0)).normalize();
      const breathe = Math.sin(t * 0.5) * 1.2;
      const dist = cam.aspect < 1 ? 78 : 96;
      eye.copy(p).addScaledVector(side, dist).addScaledVector(tan, -14).add(new Vector3(0, 6 + breathe, 0));
      look.copy(p).addScaledVector(tan, 4).add(new Vector3(0, cam.aspect < 1 ? 4 : 7, 0));   // the strip sits below the heading
      cam.position.copy(eye); cam.lookAt(look);
      renderer.render(scene, cam);
      const near = Math.round(f);
      if (near !== shown) { shown = near; onFrame?.(near); }
    },
  };
}
