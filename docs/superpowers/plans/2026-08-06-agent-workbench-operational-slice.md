# Agent Workbench Operational Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a database-backed `/admin` workbench for configuring and publishing the fitness Agent, while making mobile plan exercise media and copy columns equal height.

**Architecture:** `agentbuilder-service` owns workbench commands, DTOs, validation and the persistence port; `agentbuilder-infrastructure` owns PostgreSQL and AES-GCM credential storage; `starter` exposes authenticated thin HTTP adapters. The React application branches at the top-level path: `/admin` loads a desktop workbench, while existing fitness routes retain the mobile shell.

**Tech Stack:** Java 17, Spring Boot 3.5.8, PostgreSQL 16, Flyway, JdbcTemplate, AES-256-GCM, React 19, TypeScript, React Router, Lucide, Vitest, Testing Library, Vite.

## Global Constraints

- Modify only `/Users/modest/IdeaProjects/happy-agent-platform`; demo files remain read-only.
- Use one Spring Boot process and the existing `agent` schema/database.
- Never return or log Provider plaintext, ciphertext, IV, AAD or master-key path.
- A missing Provider or runtime is an explicit blocked state; never fabricate a successful probe, run or answer.
- All admin endpoints require the existing `FITNESS_SESSION` cookie.
- Published `agent_versions.configuration` is a complete immutable JSON snapshot.
- `/admin` follows the approved demo visual language without importing its mock repository.

---

### Task 1: Workbench database and service contract

**Files:**
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V4__agent_workbench.sql`
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminWorkbenchPort.java`
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminWorkbenchDtos.java`
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminWorkbenchService.java`
- Test: `agentbuilder/agentbuilder-service/src/test/java/happy/jayden/yang/agentbuilder/service/workbench/AdminWorkbenchServiceTest.java`

**Interfaces:**
- Produces `AdminWorkbenchPort.snapshot()`, `findDraft(String)`, `updateDraft(String, DraftUpdate, long)`, `saveCredential(String, char[])`, `publish(String)` and `run(UUID)`.
- Produces DTO records `WorkbenchSnapshot`, `AgentDraftView`, `ComponentView`, `ProviderView`, `RunView`, `ValidationView`, `DraftUpdate`, `CredentialUpdate`.

- [x] **Step 1: Write the failing service tests**

```java
@Test void validationBlocksAnUnconfiguredProvider() {
  var service = new AdminWorkbenchService(portWithUnconfiguredProvider());
  assertThat(service.validate("fitness.coach").errors())
      .contains("Provider 尚未配置 API Key");
}

@Test void publishDelegatesOnlyAfterValidationPasses() {
  var service = new AdminWorkbenchService(portWithReadyDraft());
  assertThat(service.publish("fitness.coach").publishedVersion()).isEqualTo(1);
}
```

- [x] **Step 2: Run RED**

Run: `./mvnw -q -pl agentbuilder-service -am -Dtest=AdminWorkbenchServiceTest test`  
Expected: FAIL because the workbench service package does not exist.

- [x] **Step 3: Implement closed records, validation and V4 schema**

The migration creates `agent_drafts`, `agent_component_projection`, `agent_provider_credentials`, `agent_runs` and `agent_run_events`. JSON columns use object/array checks; revisions are positive; provider ciphertext/IV/AAD are non-null only after configuration.

- [x] **Step 4: Run GREEN and commit**

Run: `./mvnw -q -pl agentbuilder-service -am test`  
Expected: all service/core tests pass.

```bash
git add agentbuilder
git commit -m "feat(agentbuilder): define workbench service contract"
```

### Task 2: JDBC workbench, encrypted Provider credentials and local seed

**Files:**
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStore.java`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/AdminWorkbenchLocalSeed.java`
- Create: `deploy/scripts/generate-secrets.sh`
- Modify: `.gitignore`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStoreTest.java`

**Interfaces:**
- Consumes the Task 1 port/records and `AesGcmCredentialCipher`.
- Produces PostgreSQL-backed optimistic draft updates, masked Provider state, immutable version publication and trace reads.

- [x] **Step 1: Write failing Testcontainers tests**

```java
@Test void credentialIsEncryptedAndNeverReturned() {
  store.saveCredential("bailian", "sk-secret".toCharArray());
  assertThat(mapper.writeValueAsString(store.snapshot())).doesNotContain("sk-secret");
  assertThat(store.snapshot().providers().getFirst().maskedCredential()).isEqualTo("••••••••");
}

@Test void staleRevisionCannotOverwriteDraft() {
  assertThatThrownBy(() -> store.updateDraft("fitness.coach", update, 99))
      .isInstanceOf(AdminWorkbenchPort.Conflict.class);
}
```

- [x] **Step 2: Run RED**

Run: `./mvnw -q -pl agentbuilder-infrastructure -am -Dtest=JdbcAdminWorkbenchStoreTest test`  
Expected: FAIL because the JDBC implementation does not exist.

- [x] **Step 3: Implement JDBC, encryption and idempotent local seed**

The seed inserts one fitness Agent plus component projection rows with `ON CONFLICT DO NOTHING`; Provider starts unconfigured. `saveCredential` creates a component-bound AES-GCM cipher and clears the received char buffer.

- [x] **Step 4: Run GREEN and commit**

Run: `./mvnw -q -pl agentbuilder-infrastructure -am test`  
Expected: repository, encryption and migration tests pass.

```bash
git add agentbuilder deploy .gitignore
git commit -m "feat(agentbuilder): persist workbench state securely"
```

### Task 3: Authenticated admin HTTP API

**Files:**
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchConfig.java`
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchController.java`
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchProblemHandler.java`
- Modify: `starter/src/main/resources/application-local.yml`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**
- Consumes Task 1 service and Task 2 JDBC store.
- Produces `/api/admin/workbench`, draft patch/validate/publish, Provider credential and run detail routes.

- [x] **Step 1: Write failing authenticated API tests**

```java
@Test void workbenchRequiresSessionAndReturnsDatabaseSeed() throws Exception {
  mvc.perform(get("/api/admin/workbench")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/workbench").cookie(loginCookie()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.agents[0].agentKey").value("fitness.coach"));
}
```

- [x] **Step 2: Run RED**

Run: `./mvnw -q -pl starter -am -Dtest=AdminWorkbenchIntegrationTest test`  
Expected: FAIL with 404 because the controller does not exist.

- [x] **Step 3: Implement thin controller and stable errors**

Validate the session through the existing Fitness application service before every operation. Map invalid session to 401, validation failure to 422, missing row to 404 and stale revision to 409. Credential payload is accepted as `char[]` and cleared after persistence.

- [x] **Step 4: Run GREEN and commit**

Run: `./mvnw -q -pl starter -am -Dtest='AdminWorkbenchIntegrationTest,FitnessExperienceIntegrationTest' test`  
Expected: both workbench and existing fitness journeys pass.

```bash
git add starter
git commit -m "feat(starter): expose authenticated workbench API"
```

### Task 4: Desktop Agent workbench UI

**Files:**
- Create: `frontend/src/admin/AdminWorkbench.tsx`
- Create: `frontend/src/admin/AdminWorkbench.test.tsx`
- Create: `frontend/src/admin/admin.css`
- Create: `frontend/src/admin/types.ts`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes Task 3 APIs only through `api.admin.*`.
- Produces `/admin` views for overview, Agent configuration, component catalog, Provider, runs and Playground.

- [ ] **Step 1: Write failing route and behavior tests**

```tsx
it('renders database workbench and saves a revised Agent draft', async () => {
  render(<App />);
  expect(await screen.findByRole('heading', { name: 'Agent 工作台' })).toBeVisible();
  await user.click(screen.getByRole('button', { name: '编辑 Agent' }));
  await user.clear(screen.getByLabelText('Agent 名称'));
  await user.type(screen.getByLabelText('Agent 名称'), '瘦瘦教练');
  await user.click(screen.getByRole('button', { name: '保存草稿' }));
  expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/draft'), expect.objectContaining({ method: 'PATCH' }));
});
```

- [ ] **Step 2: Run RED**

Run: `npm --prefix frontend test -- --run src/admin/AdminWorkbench.test.tsx`  
Expected: FAIL because `/admin` and the component do not exist.

- [ ] **Step 3: Implement the demo-aligned visual system and real states**

Use a narrow icon sidebar, white rounded shell, off-white/blue-grey canvas, navy type and sparse semantic accents. The Provider form clears its secret input after save. Advanced bindings remain collapsible. Loading, empty, unavailable, conflict and success states are distinct.

- [ ] **Step 4: Run GREEN and commit**

Run: `npm --prefix frontend test -- --run src/admin/AdminWorkbench.test.tsx && npm --prefix frontend run typecheck`  
Expected: route and management interaction tests pass.

```bash
git add frontend
git commit -m "feat(frontend): build database-backed agent workbench"
```

### Task 5: Equal-height plan cards

**Files:**
- Modify: `frontend/src/app.css`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Keeps the existing Plan page DOM and changes only layout behavior.
- Produces equal top/bottom edges for `.exercise-visual` and `.plan-exercise__copy` at phone widths.

- [ ] **Step 1: Add the failing layout contract assertion**

Assert every plan item still has exactly one media region and one always-visible copy region; use browser inspection as the visual red/green gate.

- [ ] **Step 2: Verify the current 390px screenshot is RED**

Open `/plan` at 390 × 844 and record that the image bottom ends above the right error panel.

- [ ] **Step 3: Implement equal-height grid stretch**

Set the card to `align-items: stretch`; remove the fixed aspect ratio from the compact visual; set the visual wrapper to `height: 100%` while preserving SVG `object-fit: contain`/centered drawing.

- [ ] **Step 4: Run tests and commit**

Run: `npm --prefix frontend test -- --run src/App.test.tsx`  
Expected: all mobile UI tests pass and 390px browser inspection shows aligned bottoms.

```bash
git add frontend
git commit -m "fix(frontend): align plan exercise columns"
```

### Task 6: Full verification and acceptance record

**Files:**
- Create: `docs/acceptance/agent-workbench-operational-slice.md`
- Modify: `progress.md`, `task_plan.md`

**Interfaces:**
- Produces reproducible local credentials, URLs, test evidence and known runtime dependency states.

- [ ] **Step 1: Run the full automated suite**

```bash
npm --prefix frontend test -- --run
npm --prefix frontend run lint
npm --prefix frontend run build
./mvnw verify
git diff --check
```

- [ ] **Step 2: Run API and browser acceptance**

Log in, read `/api/admin/workbench`, save a harmless draft description revision, reload and verify persistence; inspect `/admin` at 1280 and 1440 widths and `/plan` at 390 width. Do not save or fabricate a Provider key.

- [ ] **Step 3: Write acceptance evidence and commit**

```bash
git add docs task_plan.md progress.md
git commit -m "docs: record agent workbench acceptance"
```
