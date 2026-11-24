# Front-end iteration, 2026-11-24 (branch `ui-iteration`)

Built on `ui-sound` (main at alpha v0.3.01 + real camera sounds + the first develop smoothing).
Scope: the owner's request to absorb and evolve codex's 暗房助手 brief, and to iterate the whole
front end: develop effects, motion, colour, type, component sizes. Rules of
`docs/UI_POLISH_2026-11-23.md` still hold (no decoration controls, text only for names, values,
states and errors; 48 dp targets; camera-like; break nothing). Internal build only: no public entry
for the assistant, no release, no merge to main.

## Research (summary)

- **Development, physically.** Every point of the frame changes at once along exponential
  curves; tone sets when and how fast (most-exposed first), not a front sweeping down. E-6 on a
  light box: milky raw stock, a faint negative ghost, near-opaque dark, then the slide clears with
  the thin (bright) parts first. Uneven development is low-frequency and fades out. Reference shader:
  gl-transitions `PhotoDevelop.glsl` (MIT): luminance-keyed onset, flat start, saturation ramp,
  lands exactly on the image. Noise must shift *time*, never threshold a mask (that makes fronts).
- **Light effects.** React Bits Aurora (simplex noise, soft hem, OKLab stops: cheap), Liquid Chrome
  (domain warp + studio lighting: expensive), Prism (100-step raymarch: expensive), Silk
  (nested sines: cheapest). Borrow structure, not look: a low-saturation "veil" (Aurora method,
  value noise, ~3 samples/px), a quiet edge glow (border-beam / glow core+bloom), dither always,
  frame loop only while active, static frame when animations are off, gradient-brush fallback
  below API 33. Haze (Compose blur) for frosted surfaces.
- **Audit of the current app** (CI screenshots of `ui-sound`): Aura uses Google brand colours
  (blue/teal/red/yellow/green), off-palette; hint text left in the grading pad, curves, light
  table, rolls, queue, paste and border pages; border chips carry counts; paper and border steps
  are text cards; About renders on white.

## Checklist

Legend: [ ] open, [x] done (commit), [-] dropped (reason).

### A. Foundations
- [ ] A1 Palette v2: our own darkroom light (ivory, safelight amber, light-box grey) at low chroma,
      replacing Aura's brand colours; check contrast.
- [ ] A2 Type: a comfortable face with character for titles and values (OFL, subset), tabular
      figures; tune the scale.
- [ ] A3 Motion: one place for springs and durations; system animation scale 0 → static states.
- [ ] A4 Light engine `DarkroomLight`: AGSL veil + edge glow, gradient fallback < API 33, runs only
      while a real state drives it, dithered, never on the photo or in export.

### B. Development (intro and save)
- [ ] B1 Per-pixel density model in AGSL: tone-keyed onset, noise shifts time only, dark phase
      capped, overlapping baths, ends exactly on the slide.
- [ ] B2 Below API 33: a low-resolution CPU density pass drawn over the image.
- [ ] B3 Preview parity and a luminance-curve check (no kinks).
- [ ] B4 Save: grow → develop → glow → flight hand over without snaps; skipping eases too.

### C. 暗房助手 (darkroom assistant), from codex's brief, evolved
- [ ] C1 UI contract `AssistantUi` (state + actions) that codex's DraftState /
      AdviceSessionController / AdvisorRuntime map onto; no second state machine, no fake results.
- [ ] C2 Entry: text button 暗房助手 in the Adjust header next to 重置, internal builds only.
- [ ] C3 Panel replaces the tool area; photo, zoom and crop never move. Configured: goal field,
      生成草稿, 电影光影. Not configured: 提亮 / 柔和 (fixed, offline) + 配置服务.
- [ ] C4 Before sending: service · model shown; 画面统计 off by default, this round only.
- [ ] C5 Generating: light flows by the field and the button; 取消 always there; no fake progress.
- [ ] C6 Rendering: light moves below the photo; the old preview stays until the new one is ready.
- [ ] C7 Draft ready: 当前 / 建议 instant switch; changed parameters with before → after on the
      existing dials for fine-tuning; 应用 (one-step undo) / 放弃; export keeps the formal edit.
- [ ] C8 Question, no-change, failure, save-failure states shown as the backend reports them;
      retrying a save never applies twice.
- [ ] C9 Settings → 暗房助手 (service, model, key, test) and → 本地偏好 (learn, use, clear).
- [ ] C10 Compose tests on a fake: no entry in public builds, unconfigured, statistics consent,
      generate + cancel, compare, discard, apply + undo, failure + retry.

### D. Whole front end
- [ ] D1 Remove the remaining hint text; change layouts where a page needed it.
- [ ] D2 Editor header and empty state: warm light, calm, striking.
- [ ] D3 Paper step and border page: picture cards like the film cards.
- [ ] D4 Light table, loupe, rolls: chips, rating bar, empty space.
- [ ] D5 Settings, About, sheets (changes, paste, queue, update, import): one surface style.
- [ ] D6 Size, spacing, radius pass (8 dp grid, 48 dp targets).
- [ ] D7 Motion pass: tab and segment switches, panel resizing, sheets, selection, press states.

### E. Verification and hand-over
- [ ] E1 Screenshot tests updated, CI (screenshots + android) green.
- [ ] E2 Hand-over: files, tests, the interfaces codex must provide, and three states
      (UI done / backend connected / real-model effect verified).
