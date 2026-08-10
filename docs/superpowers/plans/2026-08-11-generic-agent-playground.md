# Generic Agent Playground Implementation Plan

> **For agentic workers:** Execute inline in the current `main` workspace; do not create commits unless the user explicitly requests one.

**Goal:** Allow every published Agent to be selected and streamed from the admin playground while preserving the specialized Fitness Agent runtime.

**Architecture:** The controller delegates run creation to a small dispatcher. `fitness.coach` remains on `FitnessAgentRunService`; other keys run through `PublishedAgentPlaygroundRuntime`, which reads immutable release snapshots and persists the same Run/SSE/Trace records.

**Tech Stack:** Java 17, Spring Boot, JDBC, PostgreSQL, React 19, TypeScript, Vitest, MockMvc.

## Global Constraints

- Stay on the user-authorized `main` working tree and preserve unrelated uncommitted changes.
- Do not add dependencies or database migrations.
- Only published Agent versions are selectable and runnable.
- Run focused tests, typecheck, Spotless, then restart the local services once.

---

### Task 1: Published Agent selection

**Files:**
- Modify: `frontend/src/admin/pages/PlaygroundPage.tsx`
- Test: `frontend/src/admin/AdminWorkbench.test.tsx`

- [ ] Add a failing UI test whose fixture includes `fitness.coach` and a second published Agent and assert both options are visible.
- [ ] Run the focused Vitest file and confirm the second Agent is absent.
- [ ] Remove the hard-coded Agent Key filter while retaining the published-version filter and default selection preference.
- [ ] Re-run the focused test and confirm it passes.

### Task 2: Generic persisted streaming runtime

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntime.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntimeTest.java`

- [ ] Add a failing runtime test using a real PostgreSQL test database and a controlled OpenAI-compatible SSE server.
- [ ] Assert a non-Fitness published Agent creates the correct Run and conversation, persists visible text deltas, completes successfully, and writes the assistant message.
- [ ] Implement asynchronous generic execution from the immutable publication snapshot with durable stream events and failure recording.
- [ ] Re-run the focused infrastructure test and confirm it passes.

### Task 3: Controller dispatch

**Files:**
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminPlaygroundRunService.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminPlaygroundV1Controller.java`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminPlaygroundV1ControllerTest.java`

- [ ] Add failing tests showing `fitness.coach` uses the Fitness runner and another published key uses the generic runner.
- [ ] Implement the dispatcher and remove the controller hard-code.
- [ ] Keep the existing SSE reader and Fitness approval path unchanged.
- [ ] Run the focused starter tests and confirm both branches pass.

### Task 4: Verification and local restart

- [ ] Run focused frontend and backend tests plus frontend typecheck.
- [ ] Run `./mvnw spotless:apply` and `./mvnw spotless:check`.
- [ ] Stop the current Vite/backend session and start `./deploy/local-run.sh` once.
- [ ] Verify ports 5173/8080/5432, backend startup log, and the actual published Agent list.
