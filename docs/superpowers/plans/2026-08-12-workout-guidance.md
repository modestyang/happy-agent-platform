# Workout Guidance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add clock-driven exercise image playback, non-stacking countdown speech, per-second beat audio, and device-local voice style selection to the mobile workout flow.

**Architecture:** Keep workout state as the single time source. Pure helpers derive the current media frame and audio cue from state transitions; browser adapters provide Web Speech, Web Audio, and local persistence behind small interfaces. `WorkoutPlayer` only coordinates those modules, while `ExerciseVisual` owns preview carousel behavior.

**Tech Stack:** React 19, TypeScript 5.9, Vitest 3, Testing Library, Web Speech API, Web Audio API, existing CSS.

## Global Constraints

- Do not change database schemas, migrations, backend APIs, or OpenAPI contracts.
- Do not add or upgrade dependencies.
- Preserve all existing dirty-worktree changes, especially `ExpandableSurface variant="media"` in `ExerciseVisual.tsx`.
- Do not commit unless the user explicitly requests it.
- Muting controls both voice and beat audio; pausing never causes catch-up playback.

---

### Task 1: Clock-driven exercise frames

**Files:**
- Create: `frontend/src/workout/exerciseFrames.ts`
- Create: `frontend/src/workout/exerciseFrames.test.ts`
- Modify: `frontend/src/components/ExerciseVisual.tsx`
- Create: `frontend/src/components/ExerciseVisual.test.tsx`
- Modify: `frontend/src/App.tsx:281`
- Modify: `frontend/src/components/WorkoutPlayer.tsx:132-158`

**Interfaces:**
- Produces: `exerciseFrameStep(elapsedSeconds: number, frameCount: number): number`, returning a one-based ping-pong frame.
- Produces: `ExerciseVisual` props `autoPlay?: boolean` and existing controlled `step?: number`.
- Consumes: `session.remaining`, `current.seconds`, and `exercise.imageUrls`.

- [x] **Step 1: Write failing pure frame tests**

```ts
expect([0, 1, 2, 3, 4, 5].map((second) => exerciseFrameStep(second, 4)))
  .toEqual([1, 2, 3, 4, 3, 2]);
expect(exerciseFrameStep(8, 1)).toBe(1);
```

- [x] **Step 2: Run the pure test and verify RED**

Run: `npm --prefix frontend test -- src/workout/exerciseFrames.test.ts`

Expected: FAIL because `exerciseFrameStep` does not exist.

- [x] **Step 3: Implement the minimal frame helper**

```ts
export function exerciseFrameStep(elapsedSeconds: number, frameCount: number): number {
  if (frameCount <= 1) return 1;
  const last = frameCount - 1;
  const offset = Math.max(0, Math.floor(elapsedSeconds)) % (last * 2);
  return offset <= last ? offset + 1 : last * 2 - offset + 1;
}
```

- [x] **Step 4: Run the pure test and verify GREEN**

Run: `npm --prefix frontend test -- src/workout/exerciseFrames.test.ts`

Expected: PASS.

- [x] **Step 5: Write failing carousel component tests**

Use fake timers and four literal image URLs. Assert that `autoPlay` changes the accessible frame from step 1 to step 2 after 1500ms and that a controlled `step` does not create an independent timer.

- [x] **Step 6: Run the component test and verify RED**

Run: `npm --prefix frontend test -- src/components/ExerciseVisual.test.tsx`

Expected: FAIL because `autoPlay` and carousel controls do not exist.

- [x] **Step 7: Implement preview autoplay and connect state-driven workout frames**

Add a reduced-motion check, a 1500ms preview timeout, compact pagination buttons, `autoPlay` on plan/preview calls, and this controlled workout step:

```ts
const visualStep = exerciseFrameStep(
  session.phase === 'EXERCISE' ? current.seconds - session.remaining : 0,
  current.imageUrls?.length ?? 0,
);
```

- [x] **Step 8: Run component and frame tests and verify GREEN**

Run: `npm --prefix frontend test -- src/workout/exerciseFrames.test.ts src/components/ExerciseVisual.test.tsx`

Expected: PASS.

### Task 2: Expiring voice cues and local voice styles

**Files:**
- Modify: `frontend/src/workout/voiceGuidance.ts`
- Modify: `frontend/src/workout/voiceGuidance.test.ts`

**Interfaces:**
- Produces: `VoiceStyleKey = 'SYSTEM' | 'GENTLE_FEMALE' | 'MAGNETIC_MALE'`.
- Produces: `VOICE_STYLE_OPTIONS`, `readVoiceStyle(storage?)`, `saveVoiceStyle(style, storage?)`.
- Extends: `VoiceCue` with `policy: 'QUEUE' | 'LATEST'` and `VoiceEngine.setStyle(style)`.
- Extends: `VoiceUtterance` with `pitch` and `voice`; `SpeechSynthesisLike` with optional `getVoices()`.

- [x] **Step 1: Write failing tests for expiring countdown speech**

Start a long normal utterance, enqueue countdown 3, then countdown 2. End the long utterance and assert only `2` is spoken. Assert ordinary normal cues remain FIFO.

- [x] **Step 2: Run and verify RED**

Run: `npm --prefix frontend test -- src/workout/voiceGuidance.test.ts`

Expected: FAIL because cues have no expiry policy.

- [x] **Step 3: Implement latest-only cue replacement**

When a `LATEST` cue arrives, remove queued `LATEST` cues before adding it; never cancel a normal utterance solely to play a newer number. Change preparation text to the current digit only.

- [x] **Step 4: Run and verify GREEN**

Run: `npm --prefix frontend test -- src/workout/voiceGuidance.test.ts`

Expected: PASS.

- [x] **Step 5: Write failing preset and persistence tests**

With literal Chinese female, male, and neutral voice fixtures, assert style-specific voice selection, rate/pitch values, safe fallback, invalid persisted value fallback, and round-trip storage.

- [x] **Step 6: Run and verify RED**

Run: `npm --prefix frontend test -- src/workout/voiceGuidance.test.ts`

Expected: FAIL because style configuration does not exist.

- [x] **Step 7: Implement preset selection and storage**

Use `speech.getVoices?.()` at utterance creation time. Apply `GENTLE_FEMALE` with a slightly slower/higher voice and `MAGNETIC_MALE` with a slower/lower voice; fall back to another `zh-*` voice, then the browser default. Persist only the enum key in `localStorage`.

- [x] **Step 8: Run and verify GREEN**

Run: `npm --prefix frontend test -- src/workout/voiceGuidance.test.ts`

Expected: PASS.

### Task 3: Per-second beat engine

**Files:**
- Create: `frontend/src/workout/beatGuidance.ts`
- Create: `frontend/src/workout/beatGuidance.test.ts`
- Modify: `frontend/src/components/WorkoutPlayer.tsx`

**Interfaces:**
- Produces: `BeatKind = 'WORK' | 'REST' | 'ENDING'`.
- Produces: `beatForTransition(previous, current): BeatKind | undefined`.
- Produces: `BeatEngine` with `unlock()`, `play(kind)`, `mute(value)`, and `stop()`.

- [x] **Step 1: Write failing transition tests**

Assert no beat on phase entry, `WORK` during exercise seconds above 3, `REST` during rest above 3, `ENDING` for remaining 3/2/1, and no beat while state is unchanged.

- [x] **Step 2: Run and verify RED**

Run: `npm --prefix frontend test -- src/workout/beatGuidance.test.ts`

Expected: FAIL because the beat module does not exist.

- [x] **Step 3: Implement pure cue derivation and Web Audio adapter**

Generate a short oscillator tone with distinct frequency/gain envelopes per kind. Lazily create/resume `AudioContext` from the Start button gesture. Unsupported browsers return a no-op engine.

- [x] **Step 4: Run and verify GREEN**

Run: `npm --prefix frontend test -- src/workout/beatGuidance.test.ts`

Expected: PASS.

- [x] **Step 5: Connect beat lifecycle to `WorkoutPlayer`**

Derive beats in the same session-transition effect as speech. Unlock both engines on Start, apply mute to both, and stop both on skip, exit, completion, and unmount.

- [x] **Step 6: Run focused workout tests**

Run: `npm --prefix frontend test -- src/workout/beatGuidance.test.ts src/workout/voiceGuidance.test.ts src/App.test.tsx`

Expected: PASS.

### Task 4: One-second display boundary and style UI

**Files:**
- Modify: `frontend/src/components/WorkoutPlayer.tsx`
- Modify: `frontend/src/App.test.tsx` only if no focused component seam can cover the behavior
- Modify: `frontend/src/app.css`

**Interfaces:**
- Consumes: `VOICE_STYLE_OPTIONS`, `readVoiceStyle`, `saveVoiceStyle`, and `voiceEngine.setStyle`.
- Produces: accessible `select` labelled `语音风格` on the training preview.

- [x] **Step 1: Write failing player behavior tests**

Assert countdown remains at 3 after 999ms, becomes 2 at 1000ms, and does not advance while paused. Select `磁性男声`, remount the player, and assert the persisted option remains selected.

- [x] **Step 2: Run and verify RED**

Run: `npm --prefix frontend test -- src/App.test.tsx`

Expected: FAIL because the style control and one-shot scheduling behavior are absent.

- [x] **Step 3: Implement one-shot scheduling and style control**

Replace the active-phase `setInterval` with a single 1000ms `setTimeout` whose cleanup runs on every relevant state or pause change. Add the compact style selector and persist changes locally.

- [x] **Step 4: Run and verify GREEN**

Run: `npm --prefix frontend test -- src/App.test.tsx`

Expected: PASS.

### Task 5: Regression and mobile verification

**Files:**
- Modify: `frontend/src/app.css` only for issues observed during visual verification

- [x] **Step 1: Run focused tests**

Run: `npm --prefix frontend test -- src/workout/exerciseFrames.test.ts src/components/ExerciseVisual.test.tsx src/workout/voiceGuidance.test.ts src/workout/beatGuidance.test.ts src/App.test.tsx`

Expected: PASS with zero failures.

Result: 5 test files, 56 tests passed.

- [x] **Step 2: Run full frontend checks**

Run: `npm --prefix frontend test`

Run: `npm --prefix frontend run typecheck`

Run: `npm --prefix frontend run lint`

Run: `npm --prefix frontend run build`

Expected: all commands exit 0.

Result: typecheck, ESLint, and production build passed. The full Vitest run reached 127/128 passing; the remaining failure is the pre-existing `MealRecommendationPage.test.tsx` retry-state expectation against concurrently modified meal recommendation behavior, and reproduces when run alone. It is outside this workout task and was not changed here.

- [x] **Step 3: Run formatting and diff checks**

Run: `git diff --check`

Expected: exit 0.

Result: `git diff --check` exited 0.

- [x] **Step 4: Verify the 390px mobile flow in the local app**

Confirm plan image rotation, workout image guidance, exact preparation timing, audible beat cadence, mute/pause behavior, and voice-style persistence. Record any environment limitation honestly.

Result: the signed-in local account had no current or selectable workout plan. No plan was created because that would mutate acceptance data. Media rotation, one-second scheduling, beat/mute behavior, and style persistence are covered by App/component integration tests; real-device audio quality remains a manual acceptance item once a plan exists.

- [x] **Step 5: Review scope**

Check `git diff --name-only` and confirm no database, migration, OpenAPI, backend, or unrelated UI files were modified by this task.

Result: this task changed only workout-related frontend modules/tests, plan rendering usage, shared exercise visual behavior/styles, and its design/progress documents. It did not change a database migration, backend service, dependency, or API contract. The shared worktree contains unrelated concurrent changes, which were preserved.
