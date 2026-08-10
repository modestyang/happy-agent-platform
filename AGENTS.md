# AGENTS.md

This file provides guidance to the AI agent when working with code in this repository.

## Build & run

- Maven multi-module (Java 17) + a separate `frontend` npm project. Use `./mvnw`.
- `starter` is the only executable module; its boot jar uses the `exec` classifier
  (`starter/target/starter-0.0.1-SNAPSHOT-exec.jar`).
- Full local stack: `deploy/local-run.sh` (calls `deploy/local-up.sh` first). It generates
  secrets into `deploy/.local/`, starts PostgreSQL via Docker, builds, and runs Vite at
  `http://127.0.0.1:5173` (the only local frontend entry point).
  Backend logs go to `deploy/.local/backend.log`, not stdout.
- Backend alone requires `FITNESS_DB_PASSWORD` and `AGENT_DB_PASSWORD` (see
  `deploy/.local/app.env`) and profile `local`. Agent credential master key is read from
  `deploy/secrets/agent-master-key` (gitignored).
- Frontend commands need the prefix: `npm --prefix frontend run <script>`.
- Vite dev proxies `/api` to `http://127.0.0.1:8080`.

## Tests

- Integration tests use Testcontainers, so Docker must be running. They mount
  `deploy/postgres/init.sh` and `init.sql` from the repo — keep those paths intact.
- CI only runs `./mvnw -DskipTests compile` and `npm --prefix frontend run typecheck`.
  Tests are **not** in CI; run `./mvnw test` and `npm --prefix frontend test` locally.
- `architecture-tests` holds ArchUnit module-boundary gates; run it after any cross-module change.

## Code style

- Java is formatted by Spotless with google-java-format (2-space indent). Run
  `./mvnw spotless:apply`. `.editorconfig` says `indent_size = 4` for Java — google-java-format wins.
- Design docs under `docs/` are written in Chinese; commit messages are English
  Conventional Commits with a module scope, e.g. `feat(fitness): ...`, `fix(frontend): ...`.

## Contract-first API workflow

- `docs/architecture/openapi/public-v1.yaml` (mobile `/api/app/**`) and `admin-v1.yaml`
  (`/admin/**`) are the source of truth. Never add or change an endpoint in Java/TS first.
- `frontend/src/api/generated/*.ts` is generated **and committed**. After editing OpenAPI:
  `node scripts/contracts/lint.mjs && node scripts/contracts/generate-types.mjs`
  then verify `git diff --exit-code frontend/src/api/generated`.
- Coverage fixtures in `scripts/contracts/fixtures/` must be updated alongside new operations,
  or the lint script fails.

## Architecture invariants

Full rules in `docs/architecture/module-boundaries.md`. The ones easiest to violate:

- `agentbuilder/**` must never depend on `application/**`. Agents reach fitness data only via
  the `FitnessTools` Spring bean, never a fitness repository or the `fitness` schema.
- One PostgreSQL database, two owned schemas (`fitness`, `agent`) with separate DataSources and
  separate Flyway runs (`agentFlyway` is `@DependsOn("fitnessFlyway")`). No cross-schema
  foreign keys, transactions, or queries. Fitness migrations remain append-only. Before the first
  production release, the Agent schema keeps one self-contained `V1__agent_baseline.sql`: fold
  Agent schema corrections into V1 and do not accumulate V2, V3, and later development migrations.
  Freeze Agent migration history and switch it to append-only only when production starts.
- Controllers only in `starter`; they do auth mapping, DTO conversion, and HTTP status mapping.
  Transactions begin in service use cases.
- Two independent bearer boundaries: mobile requires audience `happy-agent-public-v1` + scope
  `USER`; admin requires `happy-agent-admin-v1` + scope `AGENT_ADMIN`. Tokens are never shared
  or downgraded between them.
- Errors are `application/problem+json` with the `Problem` schema. Versioned resources use strong
  ETags: missing `If-Match` → 428, mismatch → 412.
- No fake/stub runtimes or "return placeholder when config is missing" fallbacks in production code.
