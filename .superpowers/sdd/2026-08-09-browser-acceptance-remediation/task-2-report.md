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
