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

Remaining work intentionally left open: a production OSS presigned-PUT adapter and persistent idempotency implementation for all three POST endpoints; the HTTP inference code is present but still lacks its required local HTTP-server request-body test and a full image/job lifecycle endpoint test.
