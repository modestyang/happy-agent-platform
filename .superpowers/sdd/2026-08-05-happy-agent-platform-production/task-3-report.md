# Task 3 report — DONE_WITH_CONCERNS

## RED/GREEN evidence

- **RED:** `./mvnw -q -pl starter -am -Dtest=DualSchemaIntegrationTest test` failed before implementation because `starter` had neither the Testcontainers/Spring JDBC dependencies nor `FitnessDataSourceConfig` / `AgentDataSourceConfig`.
- **GREEN (non-container gates):** `./mvnw -q -pl starter -am -DskipTests test` completed successfully after the implementation. `bash -n deploy/scripts/export-database.sh`, `bash -n deploy/scripts/verify-postgres-persistence.sh`, `docker compose -f deploy/docker-compose.yml config`, and `git diff --check` completed successfully.

## Migration and permission evidence

- One PostgreSQL 16 `happy_agent` instance is configured in `deploy/docker-compose.yml`; it binds `${POSTGRES_DATA_DIR:-/opt/happy-agent/data/postgres}` to the database data directory, so production defaults to `/opt/happy-agent/data/postgres` and local runs can override `POSTGRES_DATA_DIR`.
- `deploy/postgres/init.sql` creates only the `fitness_app` and `agent_app` non-superuser login roles, gives each ownership and `USAGE, CREATE` only on its own schema, revokes the other schema, and sets schema-local search paths.
- `FitnessDataSourceConfig` and `AgentDataSourceConfig` expose qualified data sources and transaction managers; both use Hikari `minimumIdle=0` and `maximumPoolSize=3`.
- Flyway uses `fitness_schema_history` in `fitness` and `agent_schema_history` in `agent`, disables clean, and migrates only its schema-local classpath location. No migration contains a cross-schema foreign key.
- `DualSchemaIntegrationTest` uses a real PostgreSQL 16 Testcontainer, confirms its own-schema access, and asserts permission denial for `fitness -> agent` and `agent -> fitness`; it also checks both independent Flyway histories.
- `deploy/scripts/verify-postgres-persistence.sh` inserts a probe row in each schema, restarts PostgreSQL, force-recreates the container without `-v`, then verifies both rows remain. `deploy/scripts/export-database.sh` writes a PostgreSQL custom-format dump.

## Commit

`feat: add isolated schemas on one postgres`

## Concerns

- Docker daemon was unavailable: `python3 -c '... subprocess.run(["docker", "version"], ..., timeout=5) ...'` ended with `TIMED OUT after 5 seconds: docker version`.
- The exact requested Maven test command initially stops in an upstream reactor module with `No tests matching pattern "DualSchemaIntegrationTest" were executed`; its first actionable invocation is `./mvnw -q -pl starter -am -Dtest=DualSchemaIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- That invocation reached Testcontainers' Unix-socket Docker discovery but could not complete while the daemon was unresponsive. Consequently the Testcontainers isolation assertions and the restart/recreate persistence script were **not executed** and are not reported as passing.
