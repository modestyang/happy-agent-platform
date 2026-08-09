# Task 5 report — queued workout voice guidance

## Scope

Implemented the Task 5 workout-voice remediation only:

- `frontend/src/workout/voiceGuidance.ts`
- `frontend/src/workout/voiceGuidance.test.ts`
- `frontend/src/components/WorkoutPlayer.tsx`
- `frontend/src/App.test.tsx`

No Admin, Agent Runtime, OpenAPI, deploy, or unrelated dirty-worktree file was
modified or staged by this task.

## TDD evidence

The first RED command was:

```text
npm --prefix frontend test -- voiceGuidance.test.ts
```

It failed because `./voiceGuidance` did not exist. The test introduced the
required public boundary before production code was created. The corresponding
GREEN command passes after implementation.

## Delivered behavior

- Pure transition mapping emits stable IDs for preparation, exercise/set,
  exercise countdown, rest/countdown, the next exercise, and completion.
- The browser engine unlocks with `speechSynthesis.resume()` in the Start
  button gesture, consumes each ID once, and plays a FIFO queue on `onend` or
  `onerror` without cancelling ordinary cues.
- Pause and resume receive one event cue each; their existing timer freeze and
  resume behavior remains intact.
- Mute, explicit action skip, confirmed exit/unmount, and cleanup stop the
  active queue. Late utterance callbacks cannot restart it.
- Unsupported Web Speech displays the existing concise notice in both preview
  and active-player states while the timer continues.
- `StrictMode` replays do not duplicate the start cue.

## Requirement and quality review

Reviewed the Task 5 plan and brief against the implementation and tests.

- Critical findings: 0
- Important findings: 0
- Minor findings: 0

The state reducer remains the timing authority; voice generation is a pure
transition mapping, and browser effects are confined to the engine/event
handlers. The player does not invoke `speechSynthesis.cancel()` during normal
state rendering.

## Verification

Run after the final implementation:

```text
npm --prefix frontend test -- voiceGuidance.test.ts App.test.tsx
```

Result: 2 files / 27 tests passed.

```text
npm --prefix frontend run typecheck
```

Result: passed.

Earlier full-suite and production-build evidence is retained below; both are
re-run before the Task 5 commit.

```text
npm --prefix frontend test
npm --prefix frontend run build
git diff --check
```

Browser/device voice verification is intentionally deferred to Task 7 because
this worktree has no browser binding.

`npm --prefix frontend run lint` is currently blocked by three pre-existing,
out-of-scope `@typescript-eslint/no-unused-vars` failures in the dirty
`frontend/src/components/MealRecordForm.test.tsx` (lines 15, 92, and 130).
Task 5 does not modify that file, so it is not included in this commit.
