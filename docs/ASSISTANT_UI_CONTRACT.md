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
| `question` | `AdviceResult.Question` → prompt, option labels |
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
| `answer(text)` | `answer(text)` |
| `tryPreset(Brighten/Soften)` | `propose(...)` with your fixed offline changes (not a model) |
| `openSettings()` | open 设置 → 暗房助手 |

Settings (`AssistantSettingsPort`): `settings` ← `AdvisorRuntimeState` (`loading`, `saving`,
`testing`, catalog services and models, selected endpoint and binding, a key stored, `probePassed`,
`AdvisorSettingsError` folded as above); `save(service, address, model, key)` →
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
| Backend connected | Internal builds: both ports are bound to the session, settings and local preference store. The Release entry remains disabled. |
| Real-model effect verified | An installed internal build generated a directional draft through Gemini and exported its local render on a synthetic fixture. This covers that provider and example only. See the [candidate ledger](DARKROOM_ASSISTANT_ENGINEERING_ROUTE.md#d5-work-ledger). |
| Remember a specific adjustment | Open: `learn`, `useRules`, `clearPreferences` and `apply` do not confirm a rule. Before wiring this feature, the UI owner must provide a distinct user confirmation carrying the reviewed parameter, mode, value or offset and scope. Dismissing the review must leave rules unchanged. |

### 给前端的接线需求：记住这一调整（待实现）

在内部版本提供一次独立的「记住这一调整」确认操作。它保存的是以后怎么调，不是再次应用当前照片，也不依赖开启「学习」。展示当前参数、规则含义和适用范围；用户确认后，把刚刚看到的同一条规则交给后端。普通应用、导出、回答模型问题、取消和返回都不发送这个事件。

| 用户确认的内容 | 接线约定 |
| --- | --- |
| 参数 | 传现有注册表 ID，例如 `layer2.saturation`；展示值和单位来自同一份注册表。不要把翻译后的名称作标识。 |
| 调整方式 | `fixed` 传固定的存储目标；`offset` 传相对未个性化基底的增量；`disabled` 表示不准自动修改这个参数，没有数值。没有可核实的原基底时，不从当前照片倒算偏移。 |
| 适用范围 | 默认当前可确认的最窄范围，例如准确的风格 ID 和版本。如果没有合适的范围，由用户选择；不自动扩大成全局。 |
| 保存结果 | 后端本地写入成功后才显示已记住。取消不写入；写入失败不改变照片或导出，不显示成功。此操作不请求模型、不发送照片或规则。 |

联调验收：饱和度原基底为 `18`，用户为当前风格明确确认偏移 `-5`，新基底草稿应为 `13`。在这个草稿上继续调到 `10`，就保持 `10`，不再减一次；换到另一个风格不生效。关闭学习仍可主动确认；单独打开「使用已确认的规则」不会创建规则。前端负责确认交互，后端负责保存、求值、权限和草稿；正式版入口不变。
