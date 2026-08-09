# Task 5 独立只读审查 — queued workout voice guidance

审查对象：`AGENTS.md`、整改计划 Task 5、`task-5-brief.md`、提交方
`task-5-report.md`、`review-task5.diff`、提交范围 `3dd8f82..b8cf720`，以及
当前 `b8cf720` HEAD。既有脏工作区未纳入结论；本审查未修改交付代码。

## Verdict

- **SPEC：FAIL** — 0 Critical，1 Important，2 Minor。
- **QUALITY：FAIL** — 核心队列和 transition 映射设计正确，但退出边界与对应
  App 级验证缺失，不能接受“所有验收项已覆盖”的报告结论。
- **Overall：FAIL**。依约定，只有 0 Critical 且 0 Important 才可 PASS。

## Critical

无。

## Important

### I1 — 点按“退出训练”时没有停止已在播的语音或已排队的 cue

`WorkoutPlayer` 的顶部退出按钮只冻结计时并打开确认层
([`frontend/src/components/WorkoutPlayer.tsx:152`](frontend/src/components/WorkoutPlayer.tsx#L152))；它没有调用
`voiceEngine.stop()`。`stop()` 只发生在跳过动作
([:161](frontend/src/components/WorkoutPlayer.tsx#L161))、静音，以及确认后路由切换时的卸载 cleanup
([:84](frontend/src/components/WorkoutPlayer.tsx#L84)、[:163](frontend/src/components/WorkoutPlayer.tsx#L163))。

因此用户点按这个 aria-label 就是“退出训练”的控制后，确认弹层可见而计时已暂停，但当前
utterance 仍会继续；它的 `onend` 还会让 FIFO 继续播出已经排队的倒数/提示。只有用户最后确认
离开才取消。这不符合 brief 的“跳过动作、**退出训练**、用户静音时 stop/cancel”以及本任务的
退出边界；也会在确认层中继续制造与已暂停状态不一致的播报。

修复：在打开退出确认层的 handler 中先 `voiceEngine.current?.stop()`，并加 App 级测试：开始训练、
构造活跃/排队 utterance、仅点击顶部“退出训练”后立即断言 `cancel()` 一次且旧 utterance 的
`onend` 不会启动下一项。确认/继续的产品语义应明确为丢弃当前语音队列，而非继续排空它。

## Minor

### M1 — StrictMode 的 effect replay 会在没有用户取消动作时调用 cancel

卸载 cleanup 无条件调用 `stop()`（[:84](frontend/src/components/WorkoutPlayer.tsx#L84)），而 `stop()`
无条件调用浏览器 `cancel()`（[`frontend/src/workout/voiceGuidance.ts:99`](frontend/src/workout/voiceGuidance.ts#L99)）。
React StrictMode 在开发态会执行 effect setup → cleanup → setup，所以支持语音的初始挂载已经会产生
一次非用户触发的 `cancel()`。现有 StrictMode 测试只断言 `speak` 次数
([`frontend/src/App.test.tsx:279`](frontend/src/App.test.tsx#L279))，因此没有暴露该事实。

保留真实卸载 cleanup，但让 engine 在没有 active utterance 或队列时不调用 browser `cancel()`（或把
该状态显式处理），并在 StrictMode 测试中断言开始训练前没有 cancel、开始 cue 仍只播一次。

### M2 — App 测试没有实际覆盖暂停/继续的语音行为，也未覆盖首次退出点击

`App.test.tsx` 的“暂停训练”用例只验证 timer 和打卡，且它提供的 `speechSynthesis` 没有 `resume`
([`frontend/src/App.test.tsx:338`](frontend/src/App.test.tsx#L338))；依实现的支持检测，此时创建的是无语音
engine，故不会验证“训练暂停”“继续训练”各一次。另一条取消测试仅在点击确认层内的最终“退出训练”
之后检查 `cancel`（[:296](frontend/src/App.test.tsx#L296)），恰好遗漏 I1 的首次退出操作。

为避免 mock 掩盖组件接线，测试应保留可驱动 `onend`/`onerror` 的 utterance double，并在真实
`WorkoutPlayer` 路径上断言 pause/resume 的文本和次数、首次退出立即清队，以及离开并重新开始会使用
新会话 engine 而允许再次消费同一 cue id。

## 已核验的符合项

- `voiceCueForTransition` 覆盖准备、动作/组、动作倒数、两类休息及倒数、完成；ID 包含 exercise/set/rest
  上下文，短流程的 id 唯一性已经由 focused test 固定。
- 引擎通过 generation token 忽略 stop 后迟到的 `onend`/`onerror`，普通 cue FIFO 不调用 `cancel()`；
  Start 事件内先调用 `resume()`。
- React effect 只在 `session` transition 后请求 cue，不会在普通 render 内直接调 Web Speech；新 route
  挂载会新建 engine，所以离开再开始是新的 cue-consumption session。
- 不支持 Web Speech 时预览和训练页都有简短提示，定时 reducer、完成 POST 的 once guard 未被 Task 5 改坏。

## 独立只读验证

```text
npm --prefix frontend test -- voiceGuidance.test.ts App.test.tsx
# 2 files, 27 tests passed

npm --prefix frontend test
# 8 files, 58 tests passed

npm --prefix frontend run typecheck
# passed

npm --prefix frontend run build
# passed (Vite production build)

git diff --check 3dd8f82 b8cf720
# passed
```
