# Task 4 — Structured current-goal report

## Delivered

- Added immutable fitness V12 migration for `current_goal_reports`, keyed by
  `(user_id, goal_id, goal_version)`, with durable `QUEUED`, `GENERATING`, `READY`,
  `STALE`, and `FAILED` states, lease/version fencing, objective snapshot,
  narrative, failure data, and timestamps.
- Added `goals.version` in V12 only. Objective body, meal, and workout records
  remain independent of a goal foreign key and are queried from the active
  goal's `created_at`/`startedAt` through an atomically captured observation
  time.
- Added public current-goal report GET/POST contract and generated public
  TypeScript types. POST only queues durable work and returns `202`, `Location`,
  and `Retry-After`; the scheduled worker claims the lease before invoking the
  independent report generation port.
- Implemented deterministic metrics, week-complete trend data, training
  structure, and strength/cardio ratios in the fitness service. The Agent port
  receives only these facts and can produce only conclusion, highlights,
  weaknesses, and next actions through strict JSON Schema.
- Implemented a dedicated runtime that reads the published Agent snapshot,
  verifies the Provider/Model binding, decrypts Provider credentials through
  the existing access boundary, applies timeouts, and fails closed for invalid
  JSON/HTML.
- Added the fixed report card to `/ai?report=current`: report entry from Home,
  holding state, READY/STALE evidence and charts, accumulating-data messaging,
  failure/retry, record action, and an explicit unavailable message when formal
  training-plan generation is not available. The pre-existing mutable chat
  runtime was not changed.

## Acceptance-remediation round 1

- Replaced the report card's one-shot poll with a cleanup-safe recursive poll:
  it keeps reading while the durable state is `QUEUED` or `GENERATING`, stops
  immediately at terminal states, and cancels pending work on unmount.
- Added V13 write watermarks. `body_records.created_at` and
  `workout_plans.updated_at` are maintained alongside the existing meal write
  time; the report uses the database observation watermark, the active-goal
  event window, and `event_at <= CURRENT_TIMESTAMP` to determine staleness.
  Future body and meal business timestamps now return HTTP 400.
- Made report metric responses use a dedicated `NON_NULL` wire type, so an
  unavailable comparison is absent rather than serialized as `null`.
- Publishing now persists the current-goal runtime's exact Provider and Model
  component snapshots plus encrypted credential key version/ciphertext/IV/AAD.
  Report generation reads only this immutable version payload and fails closed
  when it is incomplete; it never consults mutable component projections or
  the current provider credential.
- Clarified the report card's count-based body-area coverage and the
  planned-duration basis for strength/cardio ratios.

## Verification

- `node scripts/contracts/lint.mjs && node scripts/contracts/generate-types.mjs`
  — passed (103 fixture operations, 122 public schemas).
- `npm --prefix frontend test && npm --prefix frontend run typecheck && npm --prefix frontend run build`
  — passed (50 frontend tests, typecheck, production build).
- `./mvnw -pl starter -am test -Dspotless.check.skip=true`
  — passed (60 tests, no Surefire failures).
- `./mvnw -pl starter -am test -Dtest=DualSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.check.skip=true`
  — passed (3 tests, fitness migration history at V13).
- Focused report integration coverage passed for late in-window write
  watermarks, irrelevant pre-goal history, future-timestamp HTTP 400,
  absent metric comparisons, empty weeks/count-based structures, and immutable
  published runtime snapshots before and after re-publish.
- Targeted Spotless formatting was applied to all Task 4 Java sources and tests. The full
  reactor Spotless check remains blocked by pre-existing formatting violations in
  shared Task 3 test code and `FitnessTools`.
- Current-goal integration coverage verifies POST enqueue/no request-thread model call,
  READY facts, STALE after a newer objective record, FAILED then retry, no-active-goal
  404, ownership, expired-lease fencing, published snapshot binding, credential
  absence, and strict narrative validation.

## Browser acceptance

Attempted browser acceptance with the configured Browser integration. Its runtime
reported no available browser bindings, so no interactive screenshot was produced;
this was not substituted with a different browser automation surface.

## Scope hygiene

Only Task 4 files and precise hunks in pre-existing dirty shared files are staged
for the Task 4 commit. Existing Admin, AgentRuntime, and deployment changes remain
unstaged.
