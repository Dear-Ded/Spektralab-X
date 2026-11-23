/* Spektralab-X · project site. No dependencies. */
(() => {
  'use strict';
  const doc = document.documentElement;
  doc.classList.add('js');
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const REPO = 'Dear-Ded/Spektralab-X';

  /* ── nav: solid after the hero title, active section link ── */
  const nav = $('[data-nav]');
  const onScroll = () => nav.classList.toggle('is-solid', scrollY > 24);
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  const links = new Map($$('.nav__links a').map(a => [a.hash.slice(1), a]));
  const spy = new IntersectionObserver(entries => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      links.forEach(a => a.classList.remove('is-active'));
      const a = links.get(e.target.id);
      if (a) a.classList.add('is-active');
    }
  }, { rootMargin: '-45% 0px -50% 0px' });
  links.forEach((_, id) => { const el = document.getElementById(id); if (el) spy.observe(el); });

  /* ── reveal on scroll, siblings stagger ── */
  const io = new IntersectionObserver(entries => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      e.target.classList.add('is-in');
      io.unobserve(e.target);
    }
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
  $$('.reveal, .spectrum').forEach(el => {
    const sibs = $$(':scope > .reveal', el.parentElement);
    const i = sibs.indexOf(el);
    if (i > 0) el.style.setProperty('--d', `${Math.min(i, 6) * 0.07}s`);
    io.observe(el);
  });

  /* ── before / after ── */
  const stage = $('[data-stage]');
  if (stage) {
    const range = $('[data-range]', stage);
    const before = $('[data-before]', stage);
    const after = $('[data-after]', stage);
    let pos = 50, dragging = false, introDone = reduced;

    const set = p => {
      pos = Math.max(0, Math.min(100, p));
      stage.style.setProperty('--pos', pos + '%');
      range.value = pos;
      stage.dataset.near = pos < 14 ? 'l' : pos > 86 ? 'r' : '';
    };
    const fromEvent = e => {
      const r = stage.getBoundingClientRect();
      return ((e.clientX - r.left) / r.width) * 100;
    };
    stage.addEventListener('pointerdown', e => {
      if (e.button !== 0) return;
      dragging = true; introDone = true;
      stage.classList.add('is-drag');
      stage.setPointerCapture(e.pointerId);
      set(fromEvent(e));
    });
    stage.addEventListener('pointermove', e => { if (dragging) set(fromEvent(e)); });
    const end = () => { dragging = false; stage.classList.remove('is-drag'); };
    stage.addEventListener('pointerup', end);
    stage.addEventListener('pointercancel', end);
    stage.addEventListener('click', () => range.focus({ preventScroll: true }));
    range.addEventListener('input', () => { introDone = true; set(+range.value); });

    // one gentle sweep the first time it is seen, so people know it moves
    const sweep = () => {
      if (introDone) return;
      const t0 = performance.now(), D = 1800;
      const frame = t => {
        if (introDone) return;
        const k = Math.min(1, (t - t0) / D);
        set(50 + Math.sin(k * Math.PI * 2) * 22 * (1 - k));
        if (k < 1) requestAnimationFrame(frame); else introDone = true;
      };
      requestAnimationFrame(frame);
    };
    new IntersectionObserver((es, o) => {
      if (es[0].isIntersecting) { setTimeout(sweep, 650); o.disconnect(); }
    }, { threshold: 0.5 }).observe(stage);

    // scenes
    const tabs = $$('[data-scene]');
    const metaEl = $('[data-scene-meta]');
    const filmEl = $('[data-film-label]', stage);
    const show = btn => {
      if (btn.getAttribute('aria-selected') === 'true') return;
      tabs.forEach(t => t.setAttribute('aria-selected', String(t === btn)));
      const s = btn.dataset.scene;
      const film = btn.dataset.film;
      const slug = film.toLowerCase().replace(/^(kodak|fujifilm)\s+/, '').replace(/[^a-z0-9]/g, '');
      const a = new Image(), b = new Image();
      a.src = `site/img/${s}-${slug}.webp`;
      b.src = `site/img/${s}-neutral.webp`;
      stage.classList.add('is-swapping');
      Promise.all([a.decode(), b.decode()]).catch(() => {}).then(() => {
        setTimeout(() => {
          after.src = a.src; before.src = b.src;
          after.alt = `胶片冲印：${film}`;
          if (a.naturalWidth) stage.style.aspectRatio = `${a.naturalWidth} / ${a.naturalHeight}`;
          filmEl.textContent = film;
          metaEl.textContent = btn.dataset.meta;
          requestAnimationFrame(() => stage.classList.remove('is-swapping'));
        }, reduced ? 0 : 220);
      });
    };
    tabs.forEach((t, i) => {
      t.addEventListener('click', () => show(t));
      t.addEventListener('keydown', e => {
        const d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
        if (!d) return;
        const n = tabs[(i + d + tabs.length) % tabs.length];
        n.focus(); show(n);
      });
    });
    set(50);
  }

  /* ── film switcher ── */
  const films = $('[data-films]');
  if (films) {
    const btns = $$('[data-film-btn]', films);
    const imgs = $$('[data-film-img]', films);
    const edge = [$('[data-edge-name]', films), $('[data-edge-name2]', films)];
    const pick = btn => {
      const id = btn.dataset.filmBtn;
      btns.forEach(b => b.setAttribute('aria-selected', String(b === btn)));
      imgs.forEach(im => im.classList.toggle('is-on', im.dataset.filmImg === id));
      edge.forEach(el => { if (el) el.textContent = btn.dataset.edge; });
    };
    btns.forEach((b, i) => {
      b.addEventListener('click', () => pick(b));
      b.addEventListener('mouseenter', () => { if (matchMedia('(hover: hover)').matches) pick(b); });
      b.addEventListener('keydown', e => {
        const d = (e.key === 'ArrowDown' || e.key === 'ArrowRight') ? 1 : (e.key === 'ArrowUp' || e.key === 'ArrowLeft') ? -1 : 0;
        if (!d) return;
        e.preventDefault();
        const n = btns[(i + d + btns.length) % btns.length];
        n.focus(); pick(n);
      });
    });
  }

  /* ── live release + stars (falls back to the static text) ── */
  const fmtDate = iso => {
    const d = new Date(iso);
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  };
  const fmtSize = b => b > 0 ? ` · ${(b / 1048576).toFixed(0)} MB` : '';
  fetch(`https://api.github.com/repos/${REPO}/releases?per_page=1`, { headers: { Accept: 'application/vnd.github+json' } })
    .then(r => r.ok ? r.json() : Promise.reject(r.status))
    .then(([rel]) => {
      if (!rel) return;
      const ver = String(rel.tag_name || '').replace(/^v/, '');
      if (ver) $$('[data-release-version]').forEach(el => { el.textContent = ver; });
      if (rel.published_at) $$('[data-release-date]').forEach(el => { el.textContent = fmtDate(rel.published_at); });
      const apk = (rel.assets || []).find(a => /arm64-v8a\.apk$/i.test(a.name));
      if (apk) {
        $$('[data-apk-link]').forEach(a => { a.href = apk.browser_download_url; });
        $$('[data-apk-meta]').forEach(el => { el.textContent = fmtSize(apk.size); });
      }
    })
    .catch(() => {});
  fetch(`https://api.github.com/repos/${REPO}`)
    .then(r => r.ok ? r.json() : Promise.reject(r.status))
    .then(repo => {
      const el = $('[data-stars]');
      if (el && typeof repo.stargazers_count === 'number') {
        const n = repo.stargazers_count;
        el.textContent = n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);
        el.hidden = false;
      }
    })
    .catch(() => {});
})();
