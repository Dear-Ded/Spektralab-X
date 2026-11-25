// Spektralab-X · project site. Bundled by tools/site/build.mjs into site.min.js.
import Lenis from 'lenis';
import { hero } from './hero.js';
import { strip } from './strip.js';

const doc = document.documentElement;
doc.classList.add('js');
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
const REPO = 'Dear-Ded/Spektralab-X';

// progress of a tall, pinned section: 0 when its top reaches the top of the view, 1 when its end does
const progress = el => {
  const r = el.getBoundingClientRect();
  return clamp(-r.top / Math.max(1, r.height - innerHeight));
};
const seen = el => { const r = el.getBoundingClientRect(); return r.bottom > -innerHeight * 0.2 && r.top < innerHeight * 1.2; };

// ── smooth scrolling ────────────────────────────────────────
const lenis = reduced ? null : new Lenis({ lerp: 0.085, wheelMultiplier: 0.9, autoRaf: false });
const scrollToY = y => lenis ? lenis.scrollTo(y, { duration: 1.4 }) : scrollTo({ top: y, behavior: reduced ? 'auto' : 'smooth' });
$$('a[href^="#"]').forEach(a => a.addEventListener('click', e => {
  const el = document.getElementById(a.hash.slice(1));
  if (!el) return;
  e.preventDefault();
  scrollToY(el.getBoundingClientRect().top + scrollY - (a.hash === '#top' ? 0 : 0));
  history.replaceState(null, '', a.hash);
}));

// ── nav ─────────────────────────────────────────────────────
const nav = $('[data-nav]');
let lastY = 0;
const links = new Map($$('.nav__links a').map(a => [a.hash.slice(1), a]));
const navTick = () => {
  const y = scrollY;
  nav.classList.toggle('is-solid', y > innerHeight * 0.6);
  nav.classList.toggle('is-hidden', y > innerHeight && y > lastY + 2);
  if (y < lastY - 2) nav.classList.remove('is-hidden');
  lastY = y;
  let cur = '';
  links.forEach((a, id) => { const el = document.getElementById(id); if (el && el.getBoundingClientRect().top < innerHeight * 0.5) cur = id; });
  links.forEach((a, id) => a.classList.toggle('is-active', id === cur));
};

// ── 1 hero ──────────────────────────────────────────────────
const heroEl = $('[data-hero]');
const heroMedia = $('.hero__media', heroEl);
const heroGl = hero(heroEl, { reduced });
const heroTick = now => {
  heroGl.tick(now);
  if (reduced) return;
  const y = scrollY / innerHeight;                     // the print sinks back as you leave it
  if (y < 1.2) heroMedia.style.transform = `translate3d(0, ${y * 18}vh, 0) scale(${1 + y * 0.08})`;
};

// ── 2 statement: read in the light, one character at a time ─
const words = $('[data-words]');
const chars = [];
if (words) {
  const text = words.textContent;
  words.textContent = '';
  for (const ch of text) { const s = document.createElement('span'); s.className = 'w'; s.textContent = ch; words.append(s); chars.push(s); }
  words.setAttribute('aria-label', text);
}
const wordsTick = () => {
  if (!words || reduced) return;
  const r = words.getBoundingClientRect();
  const p = clamp((innerHeight * 0.85 - r.top) / (r.height + innerHeight * 0.45));
  const head = p * (chars.length + 12);
  chars.forEach((c, i) => c.style.setProperty('--o', (0.14 + 0.86 * clamp((head - i) / 12)).toFixed(3)));
};

// ── 3 films: the strip ──────────────────────────────────────
const films = $('[data-films]');
const filmItems = $$('[data-films-list] li', films);
const filmName = $('[data-films-name]', films), filmSpec = $('[data-films-spec]', films), filmBg = $('[data-films-bg]', films);
const frames = filmItems.map(li => ({ id: li.dataset.film, edge: li.dataset.edge, src: `site/img/strip-dunes-${li.dataset.film}.webp` }));
const showFilm = i => {
  filmItems.forEach((li, j) => li.classList.toggle('is-on', j === i));
  const li = filmItems[i];
  filmName.textContent = li.dataset.name; filmSpec.textContent = li.dataset.spec;
  filmBg.style.backgroundImage = `url(${frames[i].src})`;
};
const filmScrollTo = i => {
  const top = films.getBoundingClientRect().top + scrollY;
  scrollToY(top + (films.offsetHeight - innerHeight) * (i / (frames.length - 1)));
};
filmItems.forEach((li, i) => $('button', li).addEventListener('click', () => filmScrollTo(i)));
showFilm(0);
let stripGl = null;
if (reduced) doc.classList.add('no-gl');
else {
  // build the strip when it is about to be seen
  new IntersectionObserver(async ([e], o) => {
    if (!e.isIntersecting) return;
    o.disconnect();
    stripGl = await strip(films, frames, { onFrame: showFilm }).catch(() => null);
    if (!stripGl) doc.classList.add('no-gl');
  }, { rootMargin: '100% 0px' }).observe(films);
  // dragging the strip scrolls the page, so the strip and the page never disagree
  const glc = $('[data-films-gl]', films);
  let dx = null;
  glc.addEventListener('pointerdown', e => { if (e.pointerType === 'mouse') { dx = e.clientX; glc.setPointerCapture(e.pointerId); } });
  glc.addEventListener('pointermove', e => {
    if (dx === null) return;
    const span = films.offsetHeight - innerHeight;
    lenis?.scrollTo(scrollY - (e.clientX - dx) * span / (innerWidth * 1.2), { immediate: true });
    dx = e.clientX;
  });
  const up = () => { dx = null; };
  glc.addEventListener('pointerup', up); glc.addEventListener('pointercancel', up);
}

// ── 4 develop: neutral decode → print ───────────────────────
const dev = $('[data-develop]');
const devStage = $('[data-develop-stage]', dev);
const scenes = $$('[data-scene]', dev);
const devFilm = $('[data-develop-film]', dev), devMeta = $('[data-develop-meta]', dev), devRange = $('[data-develop-range]', dev);
let devScene = 0, devManual = null;
const setScene = i => {
  if (i === devScene) return;
  devScene = i;
  scenes.forEach((s, j) => s.classList.toggle('is-on', j === i));
  devFilm.textContent = scenes[i].dataset.film; devMeta.textContent = scenes[i].dataset.meta;
};
devRange.addEventListener('input', () => { devManual = 100 - +devRange.value; });
const devTick = () => {
  if (reduced) return;
  const p = progress(dev) * scenes.length;
  const i = Math.min(scenes.length - 1, Math.floor(p));
  setScene(i);
  const k = p - i;                                     // hold the decode, wipe, hold the print
  const w = clamp((k - 0.16) / 0.56);
  const x = devManual ?? (100 - (w * w * (3 - 2 * w)) * 100);
  devStage.style.setProperty('--x', x.toFixed(2) + '%');
  if (x <= 0.2 || x >= 99.8) devStage.dataset.edge = ''; else delete devStage.dataset.edge;
};
addEventListener('wheel', () => { devManual = null; }, { passive: true });
addEventListener('touchmove', () => { devManual = null; }, { passive: true });

// ── 5 halation: the loupe ───────────────────────────────────
const hal = $('[data-halation]');
const halMedia = $('.halation__media', hal), halImg = $('.halation__img', hal);
const loupe = $('[data-loupe]', hal), lview = $('[data-loupe-view]', hal), ltag = $('[data-loupe-tag]', hal);
const LOUPE = { cinestill800t: 'CineStill 800T', portra400: 'Portra 400' };
const loupeImg = {};
let lfilm = 'cinestill800t', lx = 0.74, ly = 0.24;   // where the halation is: the sky behind the eaves
const loadLoupe = f => loupeImg[f] ??= new Promise(res => { const im = new Image(); im.onload = () => res(im); im.src = `site/img/loupe-temple-${f}.webp`; });
const placeLoupe = async () => {
  const im = await loadLoupe(lfilm);
  const W = halMedia.clientWidth, H = halMedia.clientHeight;
  const s = Math.max(W / im.naturalWidth, H / im.naturalHeight);   // object-fit: cover
  const dw = im.naturalWidth * s, dh = im.naturalHeight * s, ox = (W - dw) / 2, oy = (H - dh) / 2;
  const z = Math.max(1.6, Math.min(3, 1 / s));                    // as close as the render allows
  const px = lx * W, py = ly * H, d = loupe.offsetWidth;
  loupe.style.setProperty('--lx', px + 'px'); loupe.style.setProperty('--ly', py + 'px');
  lview.style.backgroundImage = `url(${im.src})`;
  lview.style.backgroundSize = `${dw * z}px ${dh * z}px`;
  lview.style.backgroundPosition = `${d / 2 - (px - ox) * z}px ${d / 2 - (py - oy) * z}px`;
  ltag.textContent = `${LOUPE[lfilm]} · ${z.toFixed(1)}×`;
};
halImg.addEventListener('load', placeLoupe);
addEventListener('resize', placeLoupe);
halMedia.addEventListener('pointermove', e => {
  if (e.pointerType === 'touch' && !e.buttons) return;
  const r = halMedia.getBoundingClientRect();
  lx = clamp((e.clientX - r.left) / r.width); ly = clamp((e.clientY - r.top) / r.height);
  placeLoupe();
});
halMedia.addEventListener('pointerdown', e => {
  const r = halMedia.getBoundingClientRect();
  lx = clamp((e.clientX - r.left) / r.width); ly = clamp((e.clientY - r.top) / r.height);
  placeLoupe();
});
$$('[data-loupe-film]', hal).forEach(b => b.addEventListener('click', () => {
  lfilm = b.dataset.loupeFilm;
  $$('[data-loupe-film]', hal).forEach(o => o.setAttribute('aria-pressed', String(o === b)));
  halImg.srcset = `site/img/full-temple-${lfilm}-1280.webp 1280w, site/img/full-temple-${lfilm}.webp 2560w`;
  halImg.src = `site/img/full-temple-${lfilm}.webp`;
  halImg.alt = `Fujifilm X100F 拍摄的寺庙屋檐与过曝天空，${LOUPE[lfilm]} 冲印`;
  placeLoupe();
}));
new IntersectionObserver(([e], o) => { if (e.isIntersecting) { o.disconnect(); loadLoupe('portra400'); placeLoupe(); } }, { rootMargin: '50% 0px' }).observe(hal);

// ── 6 the app ───────────────────────────────────────────────
const app = $('[data-app]');
const phone = $('[data-app-phone]', app);
const shots = $$('[data-app-shot]', app), steps = $$('[data-app-step]', app);
let appStep = 0;
const appTick = () => {
  if (reduced) return;
  const p = progress(app);
  const i = Math.min(steps.length - 1, Math.floor(p * steps.length));
  if (i !== appStep) {
    appStep = i;
    shots.forEach((s, j) => s.classList.toggle('is-on', j === i));
    steps.forEach((s, j) => s.classList.toggle('is-on', j === i));
  }
  phone.style.setProperty('--ry', (-20 + p * 34).toFixed(2) + 'deg');
  phone.style.setProperty('--rx', (8 - p * 10).toFixed(2) + 'deg');
  phone.style.setProperty('--rz', (2 - p * 3).toFixed(2) + 'deg');
};

// ── reveal and count ───────────────────────────────────────
$$('.proof__item, .more h2, .more__list li, .download__copy > *, .statement__sub').forEach((el, i, all) => {
  el.classList.add('rv');
  const sib = [...el.parentElement.children].indexOf(el);
  el.style.setProperty('--d', `${Math.min(sib, 6) * 0.08}s`);
});
const io = new IntersectionObserver(es => es.forEach(e => {
  if (!e.isIntersecting) return;
  e.target.classList.add('is-in'); io.unobserve(e.target);
  const n = $('[data-count]', e.target);
  if (n && !reduced) {
    const to = +n.dataset.count, t0 = performance.now();
    const f = t => { const k = clamp((t - t0) / 1400); n.textContent = Math.round(to * (1 - Math.pow(1 - k, 4))); if (k < 1) requestAnimationFrame(f); };
    requestAnimationFrame(f);
  }
}), { rootMargin: '0px 0px -10% 0px' });
$$('.rv').forEach(el => io.observe(el));

// ── one loop ────────────────────────────────────────────────
const loop = now => {
  lenis?.raf(now);
  navTick();
  if (seen(heroEl)) heroTick(now);
  wordsTick();
  if (stripGl && seen(films)) stripGl.tick(progress(films), now / 1000);
  if (seen(dev)) devTick();
  if (seen(app)) appTick();
  requestAnimationFrame(loop);
};
requestAnimationFrame(loop);

// ── the latest release (the static text stays if this fails) ─
const fmtDate = iso => { const d = new Date(iso), p = n => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`; };
// tags are v0.3.02-alpha (shown "alpha v0.3.02") or, before 0.3, v0.2.00-alpha-8
const fmtTag = tag => { const m = /^v(\d+\.\d+\.\d+)-alpha$/.exec(tag); return m ? `alpha v${m[1]}` : tag.replace(/^v/, ''); };
fetch(`https://api.github.com/repos/${REPO}/releases?per_page=1`, { headers: { Accept: 'application/vnd.github+json' } })
  .then(r => r.ok ? r.json() : Promise.reject(r.status))
  .then(([rel]) => {
    if (!rel) return;
    if (rel.tag_name) $$('[data-release-version]').forEach(el => { el.textContent = fmtTag(rel.tag_name); });
    if (rel.published_at) $$('[data-release-date]').forEach(el => { el.textContent = fmtDate(rel.published_at); });
    const apk = (rel.assets || []).find(a => /arm64-v8a\.apk$/i.test(a.name));
    if (apk) {
      $$('[data-apk-link]').forEach(a => { a.href = apk.browser_download_url; });
      $$('[data-apk-meta]').forEach(el => { el.textContent = `${Math.round(apk.size / 1048576)} MB`; });
    }
  })
  .catch(() => {});
