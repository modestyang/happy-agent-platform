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

## Self-review / concern

The runtime resolves Agent-owned vision/model/provider configuration and records explicit failures without fake food. It deliberately does **not** decrypt credentials and execute the Bailian-compatible HTTP JSON-schema request yet; configured visual models therefore resolve to `MODEL_RUNTIME_UNAVAILABLE`. This keeps production from falsely reporting recognition, but means actual configured-model inference remains an explicit follow-up.
