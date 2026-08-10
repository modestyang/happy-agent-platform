# Agent Workbench Resource Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the monolithic workbench snapshot and generic component projection with independent personal-workbench resources, add editable OpenAI-compatible Providers and Models, and expose bound tools to the model behind mandatory write approval.

**Architecture:** The admin UI consumes small resource-specific `/api/v1/admin/**` endpoints backed by simple typed tables. Code-registered Tools are projected directly from the runtime scanner, while mutable definitions use dedicated repositories. The OpenAI-compatible streaming client supports tool-call deltas; read tools execute with read scopes, and write tools freeze arguments into a durable approval before trusted execution.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, PostgreSQL/Flyway, OpenAPI 3.1, React 19, TypeScript, Vitest, Testcontainers.

## Global Constraints

- Work on the current `main` worktree and preserve all existing uncommitted changes.
- Do not commit or push; the user did not request either operation.
- Provider and Model support create, update, enable, and disable; never physical delete.
- Provider protocol is exactly `OPENAI_COMPATIBLE` in this version.
- Credentials remain AES-256-GCM encrypted and never appear in API responses or logs.
- Public workbench code must not expose a generic Component DTO, route, store, or page.
- Tool model arguments never contain trusted user/run/permission/operation fields.
- Final Agent migration set contains one current baseline and no accumulated V2-V12 files.
- Add JavaDoc to public boundaries and WHY comments only where the invariant is not obvious.

---

### Task 1: Independent Agent Schema Baseline and Data Upgrade

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql`
- Remove at final squash: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V2__component_catalogs.sql` through `V12__streaming_run_approvals.sql`
- Modify: `starter/src/test/java/happy/jayden/yang/config/AgentFlywayMigrationCompatibilityTest.java`
- Modify: `AGENTS.md`

**Interfaces:**
- Produces typed tables `agent_providers`, `agent_models`, `agent_prompts`, `agent_skills`, `agent_hooks`, `agent_frameworks`, and `agent_memories`.
- Preserves `agent_drafts`, published versions, auth sessions, runs, traces, conversations, stream events, and approvals.

- [ ] Write a migration compatibility test asserting a clean database reaches Agent version `1`, the independent tables exist, `agent_component_projection` does not exist, and seeded Model rows reference valid Provider rows.
- [ ] Run `./mvnw -q -pl starter -am -Dtest=AgentFlywayMigrationCompatibilityTest test` and verify RED because the old chain and projection still exist.
- [ ] Build a temporary upgrade SQL used only against the current local database to create/backfill independent tables without dropping data.
- [ ] Consolidate the final clean schema into `V1__agent_baseline.sql`; exclude unused typed catalog tables and generic projection tables.
- [ ] Remove Agent V2-V12 migration files only after the clean-baseline Testcontainers test passes.
- [ ] Add the development-stage squash rule to `AGENTS.md`, explicitly limiting it to pre-release migration history.

### Task 2: Typed Resource DTOs, Ports, and JDBC Stores

**Files:**
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminResourceDtos.java`
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminResourcePort.java`
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminResourceService.java`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminResourceStore.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminResourceStoreTest.java`
- Test: `agentbuilder/agentbuilder-service/src/test/java/happy/jayden/yang/agentbuilder/service/workbench/AdminResourceServiceTest.java`

**Interfaces:**
- `listProviders()`, `createProvider(ProviderCreate)`, `updateProvider(key, revision, ProviderUpdate)`.
- `listModels(Optional<String> providerKey)`, `createModel(ModelCreate)`, `updateModel(key, revision, ModelUpdate)`.
- Dedicated list/get/update methods for Prompt, Skill, Hook, Framework, and Memory.
- `overview()` and `playgroundAgents()` return small read models.

- [ ] Write failing JDBC tests for Provider creation, encrypted credential masking, Provider–Model FK enforcement, Provider-filtered Model listing, and disable-without-delete.
- [ ] Write failing service tests for normalized keys/endpoints, duplicate conflict, disabled Provider/Model publication rejection, and no generic config map on Provider/Model DTOs.
- [ ] Implement focused records with explicit fields and JavaDoc explaining resource ownership.
- [ ] Implement JDBC stores with one mapper per resource; isolate malformed rows to that resource request.
- [ ] Run the two focused test classes and verify GREEN.

### Task 3: Contract-First Independent Admin APIs

**Files:**
- Modify: `docs/architecture/openapi/admin-v1.yaml`
- Modify: `scripts/contracts/fixtures/admin-coverage.json`
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminResourcesV1Controller.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchProblemHandler.java`
- Modify generated: `frontend/src/api/generated/admin.ts`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**
- Resource endpoints from the approved design under `/api/v1/admin`.
- PATCH operations require strong `If-Match`; missing is 428 and mismatch is 412.
- There is no DELETE operation for Provider or Model.

- [ ] Add exact OpenAPI operations and typed schemas for overview, Agents, Providers, Models, Prompts, Tools, Skills, Hooks, Frameworks, Memories, and playground-ready Agents.
- [ ] Update coverage fixtures and run `node scripts/contracts/lint.mjs` to verify the new operations are covered.
- [ ] Generate TypeScript types with `node scripts/contracts/generate-types.mjs`.
- [ ] Write failing MockMvc tests proving each list endpoint succeeds independently when a different resource row is malformed.
- [ ] Implement authenticated resource controllers and strong ETag handling.
- [ ] Run the focused integration test and contract lint until GREEN.

### Task 4: Runtime Tool Catalog as the Tool Page Source

**Files:**
- Modify: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/tool/ToolRegistry.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/tool/DefaultToolRegistry.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/tool/SpringToolCatalogScanner.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchConfig.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/tool/DefaultToolRegistryTest.java`

**Interfaces:**
- Add `List<ToolDescriptor> descriptors()` returning an immutable list without handlers.
- Admin Tool DTO maps descriptor fields explicitly and remains read-only.

- [ ] Write a failing registry test asserting `fitness.plan.save` appears with `WRITE`, `MEDIUM`, idempotent, `fitness.write`, and its generated schemas.
- [ ] Expose an immutable descriptor manifest from the registry without exposing handlers.
- [ ] Remove Tool seed/projection dependence from workbench startup.
- [ ] Run focused Tool scanner/registry tests and verify GREEN.

### Task 5: Model-Visible Tool Calls with Durable Approval

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/StreamingChatClient.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/AgentRuntimeConversation.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessAgentRunService.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepository.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/StreamingChatClientTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`

**Interfaces:**
- Streaming result emits text deltas and completed OpenAI-compatible tool calls containing id, name, and validated argument JSON.
- `AiStreamListener` receives `onApprovalRequested(toolCallId, toolKey, arguments, summary)`.
- Approval execution reads only server-frozen arguments.

- [ ] Write a failing streaming parser test with fragmented `delta.tool_calls[].function.arguments` chunks.
- [ ] Write a failing integration test proving the model request includes bound Tool schemas, `fitness.plan.save` does not write before confirmation, and repeated confirmation writes once.
- [ ] Implement tool schema serialization and fragmented tool-call accumulation while preserving visible text filtering.
- [ ] Resolve Agent-bound tools from `ToolRegistry`; execute READ tools with `fitness.read` and append tool results for the next model turn.
- [ ] For WRITE/MEDIUM tools, validate arguments then create WAITING_APPROVAL instead of invoking the handler.
- [ ] Execute frozen arguments only from the trusted approval endpoint with `fitness.write` and `approval.execute`.
- [ ] Remove the text-intent heuristic as the primary plan approval path.
- [ ] Run streaming and fitness integration tests until GREEN.

### Task 6: Split the Admin Frontend by Resource

**Files:**
- Modify: `frontend/src/admin/api.ts`
- Modify: `frontend/src/admin/pages/Overview.tsx`
- Modify: `frontend/src/admin/pages/AgentList.tsx`
- Modify: `frontend/src/admin/pages/AgentEditor.tsx`
- Modify: `frontend/src/admin/pages/Providers.tsx`
- Create: `frontend/src/admin/pages/Models.tsx`
- Create: `frontend/src/admin/pages/Prompts.tsx`
- Create: `frontend/src/admin/pages/Tools.tsx`
- Create: `frontend/src/admin/pages/Skills.tsx`
- Create: `frontend/src/admin/pages/Hooks.tsx`
- Modify: `frontend/src/admin/pages/PlaygroundPage.tsx`
- Modify: `frontend/src/admin/AdminWorkbench.tsx`
- Remove: `frontend/src/admin/pages/ComponentType.tsx`
- Test: `frontend/src/admin/AdminWorkbench.test.tsx`

**Interfaces:**
- API namespaces are `admin.providers`, `admin.models`, `admin.prompts`, `admin.tools`, `admin.skills`, `admin.hooks`, `admin.frameworks`, `admin.memories`, `admin.agents`, and `admin.overview`.
- Agent editor reloads Model options from `/providers/{providerKey}/models` when Provider changes.

- [ ] Write failing tests asserting no rendered page calls `/api/admin/workbench` and a failed Model request does not prevent Tool or Trace pages from rendering.
- [ ] Write failing Provider/Model UI tests for create, edit, disable, masked credential, and dependent Model selection.
- [ ] Replace generic Workbench types with generated resource DTOs and focused API methods.
- [ ] Implement independent pages; shared visual primitives may be reused, but resource state and forms remain typed and separate.
- [ ] Update Agent Editor to load resources independently and show section-local retry errors.
- [ ] Update Playground to request only playable Agent readiness.
- [ ] Run Admin frontend tests, typecheck, and lint until GREEN.

### Task 7: Documentation, Baseline Squash, and Local Data Alignment

**Files:**
- Modify: `docs/architecture/module-boundaries.md`
- Modify: `AGENTS.md`
- Modify: `progress.md`
- Modify: `findings.md`

- [ ] Add an architecture section mapping each page to its resource endpoint and explaining the Tool approval trust boundary.
- [ ] Add JavaDoc/WHY comments to every new public boundary and review touched complex methods for missing invariant explanations.
- [ ] Back up the local Agent schema without printing credential data.
- [ ] Apply the tested temporary data upgrade, squash Agent migrations to final V1, and use Flyway repair/baseline alignment so local data remains intact.
- [ ] Restart the local stack once after the final build.

### Task 8: Final Verification and Browser Acceptance

- [ ] Run `node scripts/contracts/lint.mjs`.
- [ ] Run `npm --prefix frontend test`, typecheck, lint, and build.
- [ ] Run `./mvnw test` and `./mvnw spotless:check`.
- [ ] Run `git diff --check`.
- [ ] In the real admin UI, create a temporary OpenAI-compatible Provider and Model, verify Provider→Model filtering, then disable them without deletion.
- [ ] Verify `fitness.plan.save` appears on the Tool page and in the Agent binding UI with approval-required metadata.
- [ ] Run a real plan request, confirm no write before approval, approve, and verify the saved plan and Trace.
- [ ] Verify a deliberately isolated resource failure does not take down unrelated pages.
- [ ] Record the engineering lessons and any remaining non-blocking risks in the final acceptance report.
