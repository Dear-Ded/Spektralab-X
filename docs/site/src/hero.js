// The opening print: the photo comes up from its colour negative the way a print comes up
// in the developer tray -- unevenly, as the chemistry reaches it. When it has come up, every
// pixel is the engine's render as is: no colour conversion, no grain, nothing on top.
import { WebGLRenderer, Scene, OrthographicCamera, Mesh, PlaneGeometry, ShaderMaterial, Texture, Vector2, LinearFilter, NoColorSpace } from 'three';

const vert = /* glsl */`
varying vec2 vUv;
void main() { vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }`;

const frag = /* glsl */`
precision highp float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uView, uImg, uPointer;
uniform float uIn, uDev, uTime, uZoom;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
float fbm(vec2 p) { float v = 0.0, a = 0.5; for (int i = 0; i < 5; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; } return v; }

void main() {
  // object-fit: cover, a touch of zoom so the pointer can move the print a little
  float va = uView.x / uView.y, ia = uImg.x / uImg.y;
  vec2 s = va > ia ? vec2(1.0, ia / va) : vec2(va / ia, 1.0);
  vec2 uv = (vUv - 0.5) * s / uZoom + 0.5 + uPointer * (1.0 - 1.0 / uZoom) * 0.5;
  vec3 pos = texture2D(uTex, vec2(uv.x, 1.0 - uv.y)).rgb;

  // colour negative on a light box: inverted, under the orange mask
  vec3 neg = (1.0 - pos) * vec3(0.96, 0.60, 0.36) + vec3(0.03, 0.012, 0.0);

  // the developer front: noisy, slow at first, the dark tones of the print coming up first
  float lum = dot(pos, vec3(0.2126, 0.7152, 0.0722));
  float n = fbm(vUv * vec2(3.2, 2.2) + vec2(0.0, uTime * 0.05)) * 0.55 + (lum - 0.5) * 0.22;
  float k = smoothstep(n - 0.10, n + 0.10, uDev * 1.55 - 0.32);

  vec3 col = mix(neg, pos, k);
  float wip = 1.0 - k;                              // only where the print is still coming up
  float g = hash(vUv * uView + fract(uTime) * 91.7) - 0.5;
  col += g * 0.06 * wip;                            // grain while it develops
  col += vec3(1.0, 0.72, 0.45) * k * wip * 0.55;    // a warm edge along the front
  col *= uIn;                                       // the room light coming up
  gl_FragColor = vec4(col, 1.0);
}`;

export function hero(root, { reduced, onReady }) {
  const img = root.querySelector('[data-hero-img]');
  const canvas = root.querySelector('[data-hero-gl]');
  const done = () => { root.classList.add('is-ready'); onReady?.(); };
  if (reduced) { done(); return { tick() {} }; }

  let renderer;
  try { renderer = new WebGLRenderer({ canvas, antialias: false, alpha: false, powerPreference: 'high-performance' }); }
  catch { done(); return { tick() {} }; }
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));

  const u = {
    uTex: { value: null }, uView: { value: new Vector2(1, 1) }, uImg: { value: new Vector2(3, 2) },
    uPointer: { value: new Vector2(0, 0) }, uIn: { value: 0 }, uDev: { value: 0 }, uTime: { value: 0 }, uZoom: { value: 1.035 },
  };
  const scene = new Scene();
  scene.add(new Mesh(new PlaneGeometry(2, 2), new ShaderMaterial({ vertexShader: vert, fragmentShader: frag, uniforms: u })));
  const cam = new OrthographicCamera(-1, 1, 1, -1, 0, 1);

  const size = () => {
    const w = root.clientWidth, h = root.clientHeight;
    renderer.setSize(w, h, false);
    u.uView.value.set(w, h);
  };
  addEventListener('resize', size);
  size();

  const target = new Vector2(0, 0);
  addEventListener('pointermove', e => {
    if (e.pointerType !== 'mouse') return;
    target.set((e.clientX / innerWidth - 0.5) * 2, (e.clientY / innerHeight - 0.5) * -2);
  }, { passive: true });

  let t0 = 0, started = false, ready = false, visible = true;
  // on a slow connection the words do not wait for the photo
  setTimeout(() => { if (!started && !ready) { ready = true; done(); } }, 1200);
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; }).observe(root);

  // the texture is a bitmap of whichever file srcset chose; if the browser swaps the file
  // later (a wider window), a new texture is made rather than writing into the old one
  let shownSrc = '';
  const upload = async () => {
    if (!img.currentSrc || img.currentSrc === shownSrc) return;
    await img.decode();
    const bmp = await createImageBitmap(img);
    shownSrc = img.currentSrc;
    const tex = new Texture(bmp);
    tex.colorSpace = NoColorSpace;                   // sample the sRGB bytes as they are
    tex.minFilter = tex.magFilter = LinearFilter;
    tex.generateMipmaps = false;
    tex.flipY = false;                               // ImageBitmaps ignore flipY; the shader flips
    tex.needsUpdate = true;
    u.uTex.value?.dispose();
    u.uTex.value = tex;
    u.uImg.value.set(bmp.width, bmp.height);
  };
  const start = () => {
    document.documentElement.classList.add('gl-hero');
    started = true; t0 = performance.now();
  };
  img.addEventListener('load', () => { upload().catch(() => {}); });
  (img.complete && img.naturalWidth ? Promise.resolve() : img.decode()).then(upload).then(start, done);

  const ease = x => x < 0 ? 0 : x > 1 ? 1 : 1 - Math.pow(1 - x, 3);
  const even = x => x < 0 ? 0 : x > 1 ? 1 : x * x * (3 - 2 * x);
  return {
    tick(now) {
      if (!started || !visible || !u.uTex.value) return;
      const t = (now - t0) / 1000;
      u.uIn.value = ease(t / 0.9);
      u.uDev.value = even((t - 0.6) / 3.4);
      u.uTime.value = t;
      u.uPointer.value.lerp(target, 0.05);
      if (!ready && t > 2.6) { ready = true; done(); }
      renderer.render(scene, cam);
    },
  };
}
