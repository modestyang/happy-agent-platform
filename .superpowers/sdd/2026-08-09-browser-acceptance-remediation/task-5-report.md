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
- Mute, explicit action skip, opening the exit confirmation, and unmount stop
  the active queue. Late utterance callbacks cannot restart it.
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

## Round 1 review remediation

The independent review found one Important exit boundary: pressing the top
exit control paused the clock but left the current utterance and FIFO queue
running until the user confirmed. It is fixed by stopping the engine before
the confirmation dialog opens. Cancelling that dialog resumes the timer; new
state transitions can speak again, while the old utterance's late `onend` is
ignored.

`stop()` now clears the consumed-ID session cache as well as queued/active
speech. This lets an explicit next/previous navigation announce a previously
visited action again. It does not cause ordinary React renders to replay a
cue, because cue requests still require a state transition. Empty cleanup no
longer calls browser `cancel()`, which avoids the StrictMode initial
effect-replay cancellation while preserving active-session cleanup.

The revised App-level coverage verifies:

- the first exit click immediately cancels and a stale `onend` cannot dequeue
  another cue;
- continuing from the dialog permits later action guidance and re-visiting an
  action announces it again;
- final exit cleanup does not duplicate cancel after the first stop;
- pause and resume each speak once through the real controls; and
- StrictMode has no initial cancel and still produces one start cue.

Review disposition after this round: Critical 0, Important 0, Minor 0.

## Verification

Run after the final implementation:

```text
npm --prefix frontend test -- voiceGuidance.test.ts App.test.tsx
```

Result after round 1: 2 files / 30 tests passed.

```text
npm --prefix frontend run typecheck
```

Result: passed.

Final round 1 verification:

```text
npm --prefix frontend test
npm --prefix frontend run build
git diff --check
```

Result: full frontend suite 8 files / 61 tests passed; production build and
diff check passed.

Browser/device voice verification is intentionally deferred to Task 7 because
this worktree has no browser binding.

`npm --prefix frontend run lint` is currently blocked by three pre-existing,
out-of-scope `@typescript-eslint/no-unused-vars` failures in the dirty
`frontend/src/components/MealRecordForm.test.tsx` (lines 15, 92, and 130).
Task 5 does not modify that file, so it is not included in this commit.
