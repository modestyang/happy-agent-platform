# Task 6 — Personal Agent Workbench

## Delivered

- Registry double gate: a Skill/Hook must be `AVAILABLE` in the catalog and have an in-process
  handler; startup reconciles catalog status without overwriting manual metadata.
- Fitness runtime: deterministic `fitness.safety`, Tool-backed meal/plan skills, scope-guarded
  Tool calls, immutable published provider/model/credential snapshots, and real Run/Trace writes.
- Personal workbench: Agent draft/release flow, Provider endpoint and encrypted credential editing,
  Provider→Model selection linkage, Prompt/Skill/Hook details, read-only Tools, and real Playground
  Trace links. Framework and memory remain Agent fields rather than standalone menus.
- The scope intentionally excludes knowledge/RAG, analytics, and a standalone runs dashboard.

## Verification

- `mvn clean -pl starter -am test -Dtest=FitnessSafetyHookTest,FitnessSkillRegistryTest,FitnessExperienceIntegrationTest#chatSafetyHookBlocksBeforeCredentialRetrievalOrAnyModelRequestAndPersistsTrace -Dsurefire.failIfNoSpecifiedTests=false`
- `./mvnw -pl agentbuilder/agentbuilder-service -am test -Dtest=AdminWorkbenchServiceTest`
- `npm --prefix frontend test -- --run src/admin/AdminWorkbench.test.tsx`
- `npm --prefix frontend run build`
- `node scripts/contracts/lint.mjs`
- `git diff --check`

The integration test publishes a real snapshot, sends an acute-symptom message, verifies zero
model HTTP requests and zero Tool-registry resolution, then verifies a `CANCELLED` run with a
`RUN_BLOCKED` trace event.
