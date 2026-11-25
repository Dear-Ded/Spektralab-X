# 暗房助手 UI contract (for codex)

The UI is on branch `ui-iteration`: `Assistant.kt` (panel, contract), `AssistantLight.kt` (light),
`AssistantSettings.kt` (settings sheets). It does **not** contain a state machine, a request, a
render or a fake result. It reads one `AssistantView` and calls one `AssistantPort`. Your session
(`DraftState`, `AdviceSessionController`, `AdvisorRuntime`, the preference store) is the only
implementation. Provide it with
`CompositionLocalProvider(LocalAssistant provides port, LocalAssistantSettings provides settingsPort)`
around `EditorScreen` and `SettingsScreen`, in internal builds only. With no port there is no entry:
`assistantBuildAllowed` is `BuildConfig.DEBUG`.

## State: `AssistantView` ← `DraftState` + `AdvisorRuntimeState`

| AssistantView | From |
|---|---|
| `configured` | `AdvisorRuntimeState.requestSelection != null` |
| `service`, `model` | the selected endpoint's display name, `ModelBinding` model id (shown before anything is sent) |
| `phase` | `READY` → Ready; `GENERATING` → Generating; `RENDERING` → Rendering; `PREVIEWING` → Previewing; `COMPARING`, `RESTORING`, `PREPARING_EXPORT`, `COMMITTING` → Busy; `APPLIED` → Applied; `SAVED` → Saved; `FAILED` → Failed; `IDLE` → no panel |
| `showingCurrent` | `showingEntry` |
| `changes` | for each `AdviceParameter` whose value differs between `entry.edit.look` and `draft.edit.look`: `AssistantChange(AssistantParam.of(id), before, after)` (ids are the registry's, `layer2.*`) |
| `statistics` | `includeStatistics` |
| `cinematicReady` | statistics granted **and** a preview that can be analysed (your rule) |
| `question` | `AdviceResult.Question` → prompt, option labels (the model's question only; never the scope) |
| `scope` | `DraftState.scopePrompt` → `AssistantScope(id, text)`: a custom goal, follow-up or typed answer waiting for its scope. The panel asks in place: 会调整整张照片：… [只改一部分] [调整整张] |
| `notice` | `AdviceNotice` (NO_CHANGE, UNSUPPORTED, QUESTION_LIMIT, CANCELLED, CINEMATIC_PREVIEW_NOT_ANALYZED, CINEMATIC_NO_ADJUSTMENT), same order |
| `error` | `AdviceError` / `AdvisorNetworkError` folded: CONFIGURATION_REQUIRED → Configuration; NETWORK_AUTH, AUTH → Auth; NETWORK_ADDRESS, NOT_FOUND, REDIRECT → Address; NETWORK_RATE_LIMIT, RATE_LIMIT → RateLimit; NETWORK_TIMEOUT, TIMEOUT → Timeout; NETWORK_FAILED, TRANSPORT, SERVER, HTTP → Network; PROVIDER_REFUSED → Refused; INVALID_JSON, SCHEMA_MISMATCH, INVALID_STATUS_PAYLOAD, UNKNOWN_PARAMETER, DUPLICATE_PARAMETER, OUT_OF_RANGE, LOCK_CONFLICT, NO_EFFECT, STALE_RESULT, PROVIDER_INCOMPLETE, PROVIDER_INVALID, OUTPUT_TOO_LARGE, TOO_LARGE, INVALID_RESPONSE → Invalid; RENDER_FAILED → Render; SAVE_FAILED → Save; PREFERENCES_UNAVAILABLE, PREFERENCE_CONFLICT → Preferences |
| `canApply` | `DraftState.canApply` |
| `strength`, `canSetStrength` | `DraftState.strengthPercent`, `DraftState.canSetStrength` |
| `local` | the shown draft's origin is `AdviceOrigin.Local` (提亮 / 柔和): the panel says 本地示例 and never shows the model's name |
| `lightFrom` | `draft.directionalLight?.brightSide` (LEFT/RIGHT/TOP/BOTTOM → Left/Right/Top/Bottom), as measured |

## Actions: `AssistantPort` → your calls

| Port | Session |
|---|---|
| `open()` | `begin()` |
| `close()` | `discard()` then end the session (restore the formal edit, `endPreview`) |
| `generate(goal)` | `generate(text)`; during a shown draft it is the follow-up |
| `cinematic()` | `generateCinematicRecommendation()` |
| `directional()` | `generateDirectionalLightRecommendation()`; the panel calls it only once 画面统计 is on (it asks first, inline) |
| `setStrength(p)` | `setStrength(p)`; the dial keeps the finger's value and sends the latest one as soon as `canSetStrength` is true again |
| `cancel()` | `cancelRequest()` |
| `statistics(on)` | `includeStatistics(on)` (this round only; reset on `begin`) |
| `showCurrent(b)` | `compare(showEntry = b)` |
| `adjust(param, v)` | `adjust(listOf(ParameterChange(param.id, v)))` merged with the draft's other changes |
| `apply()` / `discard()` | `apply()` / `discard()` |
| `undo()` | the editor's one-step undo of the applied commit |
| `retrySave()` | `retrySave()` (must not apply again) |
| `answer(text)` | `requestAnswerScope(text)`, or the model question's option |
| `confirmScope(id, whole)` | `resolveScope(id, wholePhoto = whole)`; ignore an `id` that is not the pending prompt's. After 只改一部分 the panel words `UNSUPPORTED` as 暂时只能调整整张照片；只改一部分请用蒙版 |
| `tryPreset(Brighten/Soften)` | `propose(...)` with your fixed offline changes (not a model) |
| `openSettings()` | open 设置 → 暗房助手 |

Settings (`AssistantSettingsPort`): `settings` ← `AdvisorRuntimeState` (`loading`, `saving`,
`testing`, catalog services and models, selected endpoint and binding, a key stored, `probePassed`,
`AdvisorSettingsError` folded as above; an address that is not https is `Address`, worded 需要 https 地址);
`AssistantPreferences.rulesActive`: true only once confirmed rules are applied to edits (until then the
switch shows off and explains); `save(service, address, model, key)` →
`AdvisorRuntime.save(expected, endpoint, binding, secret)` (null key keeps the stored one; wipe the
array); `testConnection` / `cancelTest`; `preferences`, `learn`, `useRules`, `clearPreferences` →
your preference store.

Notices for a directional request arrive as `CINEMATIC_NO_ADJUSTMENT` / `CINEMATIC_PREVIEW_NOT_ANALYZED`; the panel
words them by the button the user pressed (没有明确的光向 / 预览还不能分析). A directional draft has no scalar
`changes`; the panel shows it by `strength` and `lightFrom`, and offers no follow-up field on it.

## What the UI guarantees

- Nothing is sent by opening, typing, choosing a model, or ticking 画面统计; only 生成草稿, 电影光影,
  a follow-up, an answer or 测试连接 call the port.
- The photo, its zoom and its crop never move; the panel replaces the 调整 controls only.
- The light shows only `Generating` (正在生成建议: round the goal field and its buttons) and `Rendering` (正在预览调整:
  round the photo's edges, outside it, after 250 ms so a strength step does not flicker). It leaves in 600 ms when
  the draft lands and in 200 ms on cancel, failure or a new photo.
  It is never on the photo and never in an export; it stops on any other phase; it is still with
  system animations off.
- Export while a draft is shown uses the formal edit (your `PREPARING_EXPORT`); the UI does not export.

## Status

| | |
|---|---|
| UI | done: panel, settings sheets, light, entry gate, 15 Compose tests on a fake session, screenshots 80–87 |
| Backend connected | **not yet**: needs the `AssistantPort` / `AssistantSettingsPort` implementation above |
| Real-model effect verified | **not yet** |
