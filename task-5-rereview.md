# Task 5 限定复审 — exit voice-queue fix1

审查范围严格限于原独立审查 `task-5-review.md`、整改提交
`b8cf720..66fcd81`、其 `review-task5-fix1.diff`，以及当前 `66fcd81` HEAD。
这是只读复审，未修改交付代码；既有脏工作区也不在本次范围内。

## Verdict

- **SPEC：PASS** — 0 Critical，0 Important，0 Minor。
- **QUALITY：PASS** — 修复关闭了原退出边界和 StrictMode/组件级验证缺口，未观察到新的队列、
  计时、打卡或 UI 回归。
- **Overall：PASS** — 满足 0 Critical / 0 Important 的验收门槛。

## 原审查问题的关闭情况

| 原问题 | 状态 | 复核证据 |
| --- | --- | --- |
| I1：首次点按顶部“退出训练”没有取消队列 | **已关闭** | 顶部 handler 现在先执行 `voiceEngine.current?.stop()`，再暂停/打开确认层（`frontend/src/components/WorkoutPlayer.tsx:152`）。App 测试在首次点击后即断言第三次 `cancel`，并调用旧 utterance 的 `onend` 后断言没有新 `speak`（`frontend/src/App.test.tsx:368-373`）。 |
| M1：StrictMode effect replay 无动作也调用 cancel | **已关闭** | `stop()` 仅在 `speaking || queue.length > 0` 时调用 browser `cancel()`（`frontend/src/workout/voiceGuidance.ts:99-105`）。StrictMode 路径在开始前断言没有 `cancel`，开始 cue 仍恰好一次（`frontend/src/App.test.tsx:279-296`）；空 engine cleanup 也有单元测试。 |
| M2：pause/resume 和首次退出仅由 mock 间接覆盖 | **已关闭** | pause/resume App 测试通过真实控件推动队列，主动触发前一 utterance 的 `onend`，并分别断言“训练暂停”“继续训练”各一次（`frontend/src/App.test.tsx:298-327`）。退出测试同时覆盖首次取消、迟到回调、继续跟练、最终确认。 |

## 新增边界核验

- `stop()` 清空队列、递增 generation，并清理 consumed ID；因此显式 skip/exit 后的迟到
  `onend`/`onerror` 无法续播旧队列，而用户随后回访已到过的动作可重新播报。单元测试和
  App 级“下一动作 → 静音/恢复 → 上一动作”路径均验证了该行为。
- 普通 React render 未调用 `stop()`；cue 仍只从 `voiceCueForTransition(previous, current, currentExercise)`
  的 session transition effect 进入 engine（`WorkoutPlayer.tsx:77-82`）。因此清 consumed 的显式
  操作不会造成 render/StrictMode 重播。
- 继续跟练关闭确认层后恢复原暂停状态；下一次有效动作 transition 可以发声。最终确认路由卸载时
  engine 已停止，idle cleanup 不会重复 `cancel`，App 测试断言取消总数保持不变。
- 完成 POST 的 `completedPosted` once guard、timer reducer 和不支持语音的提示路径未被 fix1 改动；
  全量前端回归保持通过。

## 独立只读验证

```text
npm --prefix frontend test -- voiceGuidance.test.ts App.test.tsx
# 2 files, 30 tests passed

npm --prefix frontend test
# 8 files, 61 tests passed

npm --prefix frontend run typecheck
# passed

npm --prefix frontend run build
# passed (Vite production build)

git diff --check b8cf720 66fcd81
# passed
```
