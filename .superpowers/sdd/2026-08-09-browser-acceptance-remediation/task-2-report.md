# Task 2 report — meal photo recognition

## RED / GREEN

- RED: `MealRecordForm.test.tsx` first failed because `MealRecordForm` did not exist.
- GREEN: the focused Vitest suite now covers upload → editable recognition candidates → confirmed save, failure retaining preview → manual fallback, and invalid file rejection (3/3 passed).

## Contract and integration

- The public OpenAPI contract keeps the existing media-ticket, recognition-job, and meal-record resources. It constrains image MIME/size, fixed job states, editable candidate fields (`name`, `estimatedKcal`, `confidence`), and required `recognitionJobId` for confirmation.
- Types were regenerated; contract lint validates 101 fixture operations.
- `V4__meal_recognition.sql` owns media/jobs in `fitness`, leaves meals independent of goals, and adds no cross-schema relation. `DualSchemaIntegrationTest` asserts the absence of `goal_id` and Agent-to-fitness foreign keys.
- The local adapter persists an uploaded file only after exact length/SHA-256 verification; no image bytes are stored in PostgreSQL. Missing visual configuration produces a durable `DEPENDENCY_NOT_CONFIGURED` failure rather than fabricated foods.

## Verification summary

- `npm --prefix frontend test -- MealRecordForm.test.tsx`: 3 passed.
- `npm --prefix frontend run typecheck`: passed.
- `node scripts/contracts/lint.mjs`: 101 operations validated.
- `node scripts/contracts/generate-types.mjs`: public/admin generated.
- `./mvnw -pl starter -am test -DskipITs=false`: completed; starter reports show 16 tests, 0 failures/errors; upstream module suites passed too.
- `git diff --check`: passed.

## Fix round 1

- Public endpoints now live exclusively below `/api/v1/app`; the legacy controller no longer exposes its accidental `/api/app/v1/app/...` duplicates.
- Recognition submission only enqueues. A scheduled worker atomically claims one durable `QUEUED` row (`FOR UPDATE SKIP LOCKED`), moves it to `RUNNING`, invokes the runtime with the stored owner, and accepts only a `RUNNING` → terminal transition.
- The V1 response adapter emits the public JSON shape: nested `failure`, `mealRecordId`, aggregate `nutrition`, and timestamps. Tickets require the explicit `MEAL_RECOGNITION` purpose.
- `V5__meal_recognition_integrity.sql` stores and validates ticket expiry, enforces manual versus recognition confirmation linkage, and prevents confirming the same job twice. Local file uploads are opt-in (`happy.fitness.local-media.enabled=true`); absent a configured production signer the request returns dependency-not-configured instead of writing local disk.
- The UI now polls the server-issued job id only, clears intervals on unmount/state change, and keeps the image preview when the user selects manual fallback.
- Dual-schema coverage now expects five fitness migrations and asserts recognition tables have no foreign key outside `fitness`.

## Final remediation — lifecycle and idempotency race

- The production configuration and HTTP inference adapter landed in the preceding Task 2 commits. This round audited `V6__fitness_idempotency_keys.sql`: its `(user_id, operation, idempotency_key)` uniqueness is now used inside a transaction. On a unique-key race the controller rolls back its attempted resource write, reads the committed idempotency row, and returns the saved response for an equal request hash or `409` for a different hash. It never returns a transient `500` or creates a second resource.
- Added a real Spring MVC → application service → JDBC → durable worker lifecycle test. It uses only a test-profile fake recognition port; ticket, upload, job, meal confirmation, persistence, and worker execution are the application’s real paths. It verifies `ticket → PUT → 202 QUEUED → explicit worker → GET SUCCEEDED → edited confirmation → GET meal-records`.
- The lifecycle test also verifies ticket owner, expiry, SHA-256, and MIME rejection; mandatory `Idempotency-Key` on all three POSTs; equal-payload replay with the original resource; different-payload `409`; the `MealRecord` JSON contract; and a nested recognition failure response. It proves no inference call occurs on the HTTP request thread before the worker is explicitly run.
- Added a two-request barrier concurrency integration test. Both requests reach the V6 unique-key contention point; both receive the same `201` JSON response while the database contains exactly one media object and one idempotency row.

## Final verification

- `./mvnw -pl starter -am test -Dtest=FitnessExperienceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`: 6 passed.
- `./mvnw -pl starter -am test -Dtest=FitnessV1IdempotencyConcurrencyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`: 1 passed.
- `./mvnw -pl starter -am test -DskipITs=false`: Task 2 Surefire reports are green (`FitnessExperienceIntegrationTest` 6, the concurrency test 1, and `MealRecognitionRuntimeTest` 1; all have 0 failures/errors). The aggregate run is blocked by an already-dirty, unstaged `DualSchemaIntegrationTest`: it expects five fitness migrations while the current V1–V6 directory applies six. That non-Task-2 change is deliberately not modified or staged here.
- `npm --prefix frontend test -- MealRecordForm.test.tsx`: 3 passed; `npm --prefix frontend run typecheck`: passed.
- `node scripts/contracts/lint.mjs`: 101 operations validated. `node scripts/contracts/generate-types.mjs` succeeded; a pre-existing dirty `admin-v1.yaml` also changes generated `frontend/src/api/generated/admin.ts`, so that unrelated file is intentionally excluded from this Task 2 commit.
- `git diff --check`: passed.

## Review closure evidence

- Critical — deployable inference: the existing runtime/HTTP adapter and its focused runtime test remain wired through the durable worker; the lifecycle test exercises the worker boundary with a controlled test port.
- Critical — public endpoint paths: the lifecycle test invokes only `/api/v1/app/...` and exercises ticket, upload, job, meal, and list endpoints through MVC.
- Critical — asynchronous durable processing: the test asserts `QUEUED` and zero fake-port calls before `runOne()`, then `SUCCEEDED` only after the worker claims the stored job.
- Critical — response contract: the test asserts `mealRecordId`, edited item/nutrition fields, list page shape, and nested recognition failure fields.
- Important — upload safety: local-upload validation is covered for owner, expiry, MIME, and exact bytes/SHA; the existing signed-upload adapter remains the production path.
- Important — persistence/schema boundaries: V4/V5 are unchanged and V6 contention is covered. The unrelated, currently dirty dual-schema count assertion must be updated from five to six by its owner before the aggregate reactor is fully green.
- Important — idempotency semantics: all three POST operations have missing-key, replay, and conflict assertions; the new barrier test covers the database race.
- Important — client lifecycle: the focused `MealRecordForm` suite still passes its upload/edit/failure/manual-fallback cases, while the new server lifecycle test covers the job states it consumes.

## Release closure — 2026-08-09

- The previously unstaged `FitnessProviderCredentialAccess`, polling client method, OSS completion
  contract, durable worker test, and strengthened dual-schema test are included with the Task 2
  source set. The release no longer relies on a worktree-only credential reader or a browser API
  method absent from the committed client.
- Production direct OSS uploads now require a separate authenticated completion request. The
  server performs a signed OSS `HEAD` and compares content type, byte count, and SHA-256 metadata
  before changing the media row to `UPLOADED`; the worker obtains the same bytes through a signed
  OSS `GET` and verifies them again. The presigned `PUT` now signs the required
  `x-oss-meta-sha256` header as well as `Content-Type`.
- The browser supplies distinct stable `Idempotency-Key` values for ticket creation, recognition
  job creation, and meal confirmation. The service-owned transaction stores each resource and its
  replay response atomically; integration coverage exercises concurrent equal-key ticket, job,
  and meal requests plus changed-payload conflicts.
- Runtime throwables become durable `RUNTIME_ERROR` terminal states. The JDBC claim query also
  reclaims a `RUNNING` job whose lease timestamp is older than five minutes. Local JSON parsing
  rejects non-schema fields, coercions, invalid item counts, strings, bounds, and confidences.
- `DualSchemaIntegrationTest` asserts all six fitness migrations and joins PostgreSQL constraint
  metadata to prove neither schema has a foreign key into the other. Local upload tickets now use
  a relative `/api/v1/app/media-uploads/{mediaId}` URL for Vite proxy compatibility.

### Verification after takeover

- Initial typecheck found two `useRef` calls missing their required `undefined` initializer; both
  were corrected. Initial lifecycle coverage still expected the obsolete public `TASK_FAILED`
  alias, while the public response correctly exposes persisted `TIMEOUT`; the assertion now
  reflects the contract and retryability.
- `npm --prefix frontend run typecheck` and `npm --prefix frontend test -- MealRecordForm.test.tsx`: passed (3 tests).
- `./mvnw -pl starter -am test -Dtest=FitnessExperienceIntegrationTest,FitnessV1IdempotencyConcurrencyIntegrationTest,MealRecognitionRuntimeTest,MealRecognitionWorkerTest,OssPresignedMediaUploadPortTest -Dsurefire.failIfNoSpecifiedTests=false`: passed (14 tests).
- `./mvnw -pl starter -am test -Dtest=OssPresignedMediaUploadPortTest -Dsurefire.failIfNoSpecifiedTests=false`: passed (2 tests) after the metadata-signature regression test.

## Independent review remediation

- Production OSS object URLs are now HTTPS virtual-hosted URLs
  (`https://{bucket}.{endpoint}/{key}`) for browser `PUT` tickets and server-side `HEAD`/`GET`.
  The canonical resource remains `/{bucket}/{key}` and the regression test asserts the exact
  virtual-hosted signed URL. Direct HTTP is accepted only for the loopback in-process test
  server; every non-loopback endpoint is rejected unless it is HTTPS.
- Server-side `HEAD` and `GET` set both connect and read timeouts (10 seconds), close response
  streams, and disconnect in all cases, so a stalled object store cannot leave the scheduled
  worker permanently blocked.
- Recognition claims carry the durable `updated_at` instant returned by the claim statement.
  The terminal update predicates on that instant as well as `RUNNING`; a stale worker that has
  been superseded by the five-minute reclaimer cannot overwrite the new worker's result. The
  integration test creates exactly that interleaving and proves only the recovering worker wins.
- A clean-source Testcontainers run exposed that the Task 2 commit contains four Agent migrations
  while unrelated dirty Admin work adds V5--V7. The dual-schema test therefore asserts the
  required six Fitness migrations and real PostgreSQL cross-schema FK metadata, without coupling
  Task 2 to those excluded Agent migrations.
- Changed-payload key reuse now maps to the public `IDEMPOTENCY_CONFLICT` problem code, including
  the unique-key race fallback, and the MVC concurrency test asserts it.

## Final clean-source verification

- Clean test source commit: `b9ac708444a31bf8e3ae619bc2e9dbad189aec5c`. A fresh detached
  worktree at `/tmp/happy-agent-task2-release-final` started clean at that exact SHA. The final
  release commit adds this verification report only; all executable source and tests are identical.
- `./mvnw clean -q && ./mvnw -DskipTests compile`: passed from a clean reactor (all 15 modules
  recompiled as required).
- `./mvnw -q test`: passed from that clean worktree. The focused Task 2 suite also passed all 18
  tests: dual schema (3), recognition lifecycle (8), idempotency concurrency (2), runtime (2),
  worker (1), and OSS adapter (2).
- `npm --prefix frontend ci`, `node scripts/contracts/lint.mjs`, and
  `node scripts/contracts/generate-types.mjs` passed; lint validates 97 clean-source fixture
  operations and generated public/admin clients have no diff.
- A temporary minimal Task 2 TypeScript config that includes `api.ts`, `MealRecordForm.tsx`, its
  test, and test setup passed `tsc`; the focused Vitest run passed all 3 form tests.
- The required full frontend commands were also run from the clean worktree. They are not release
  gates for Task 2 because the committed baseline has unrelated failures: `ComponentType.tsx`
  imports missing Admin modules (`../api` and `../components/PageHeading`), `App.test.tsx`
  imports `node:fs` without Node typings, and full Vitest reports 1 legacy App expectation plus 8
  AdminWorkbench expectation mismatches. `ComponentType.tsx` and its missing imports are already
  present in baseline `fa26821` (from `9adf2ad`); the excluded dirty Admin worktree files supply
  the absent modules. No Task 2 typecheck or `MealRecordForm` test failed.

## Final review remediation round 1

- Recognition job failures now use the dedicated `RecognitionFailureCode` contract rather than
  widening the shared `ProblemCode`. Its complete persisted runtime/worker set is
  `DEPENDENCY_NOT_CONFIGURED`, `DEPENDENCY_UNAVAILABLE`, `TIMEOUT`,
  `INVALID_MODEL_RESPONSE`, and `RUNTIME_ERROR`; regenerated public TypeScript types reflect the
  schema. A wire-adapter contract test asserts a real `TIMEOUT` response and the recognition-only
  OpenAPI failure reference/enum.
- The Bailian strict JSON schema and the local parser both limit candidate names to 120 Unicode
  code points. Focused tests accept 120 emoji code points and reject 121, while the request-shape
  test asserts that the provider receives `maxLength: 120`.
- A terminal-failure retry now replaces both ticket and job idempotency keys before re-uploading.
  The browser test clicks `重试` and proves two distinct tickets/keys and a new job/media id; the
  MVC lifecycle test completes a failed `TIMEOUT` attempt followed by a new ticket/upload/job that
  reaches `SUCCEEDED`.
- The remaining `MealRecord.createdAt` Minor is intentionally not synthesized. The existing
  `meals` table has no `created_at` column, so it is deferred to Task 3's coordinated V7 migration
  and DTO/query update rather than adding a fabricated timestamp or mutating historical V2.

### Round 1 verification

- `node scripts/contracts/lint.mjs`: passed (102 operations);
  `node scripts/contracts/generate-types.mjs`: public client regenerated.
- `npm --prefix frontend run typecheck` and
  `npm --prefix frontend test -- MealRecordForm.test.tsx`: passed (4 tests).
- `./mvnw -pl starter -am test -Dtest=FitnessExperienceIntegrationTest,FitnessV1RecognitionContractTest,MealRecognitionRuntimeTest,MealRecognitionWorkerTest,OssPresignedMediaUploadPortTest,DualSchemaIntegrationTest,FitnessV1IdempotencyConcurrencyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`:
  passed (22 tests; zero failures/errors).
