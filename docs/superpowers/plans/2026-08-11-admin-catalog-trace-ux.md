# Admin Catalog and Conversation Trace UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace disruptive inline catalog forms with consistent modals, add real Prompt/Skill creation, and make Trace a conversation-first view with grouped execution details.

**Architecture:** Keep Provider, Model, Prompt, Skill, Conversation, and Run as independent resources. Extend the existing resource service/store with Prompt and Skill create operations, make recent conversation lookup user-independent, then build focused React components for modal forms and conversation-style execution rendering. Reuse existing tables and Run Trace data; no migration or new dependency is required.

**Tech Stack:** Java 17, Spring Boot, JDBC, PostgreSQL, OpenAPI, React 19, TypeScript, React Router, Vitest, Testing Library, CSS.

## Global Constraints

- Work on the existing `main` worktree and preserve all unrelated uncommitted changes.
- Do not add or modify a database migration.
- Do not add or upgrade dependencies.
- Provider, Model, Prompt, Skill, Tool, Hook, Agent, Conversation, and Run remain independent resources.
- Streaming is fixed on and absent from the model form.
- Tool calling and image input are model capability declarations, not runtime feature toggles.
- UUID is not exposed in Trace UI.
- Do not commit or push unless the user explicitly asks.

---

### Task 1: Contract and backend creation operations

**Files:**
- Modify: `docs/architecture/openapi/admin-v1.yaml`
- Modify: `scripts/contracts/fixtures/admin-coverage.json`
- Modify: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminResourceDtos.java`
- Modify: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminResourcePort.java`
- Modify: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminResourceService.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminResourceStore.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminResourcesController.java`
- Test: `agentbuilder/agentbuilder-service/src/test/java/happy/jayden/yang/agentbuilder/service/workbench/AdminResourceServiceTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**
- Produces `PromptDefinition createPrompt(PromptCreate request)`.
- Produces `SkillDefinition createSkill(SkillCreate request)`.
- `PromptCreate` fields: `promptKey`, `displayName`, `description`, `template`.
- `SkillCreate` fields: `skillKey`, `displayName`, `description`, `whenToUse`, `whenNotToUse`, `content`, `requiredToolKeys`.

- [ ] Add failing service tests proving blank keys are rejected before the port and valid requests are delegated.
- [ ] Add failing integration tests for `POST /api/admin/prompts`, `POST /api/admin/skills`, duplicate key conflict, and missing required Tool.
- [ ] Add OpenAPI operations and fixture coverage before Java endpoints.
- [ ] Add create DTOs and port/service methods with nonblank key/name/template/content validation.
- [ ] Insert into `agent_prompts` and `agent_skills` with revision `1`, status `ACTIVE`, and current timestamps; map unique violations to the existing resource conflict exception.
- [ ] Validate every `requiredToolKey` against `ToolRegistry.descriptors()` before Skill insertion and derive `runtimeReady=true` only when all are available.
- [ ] Add controller POST methods returning `201 Created` and the created definition.
- [ ] Run contract lint, type generation, service tests, and the focused integration tests.

### Task 2: Recent conversation contract without UUID

**Files:**
- Modify: `docs/architecture/openapi/admin-v1.yaml`
- Modify: `scripts/contracts/fixtures/admin-coverage.json`
- Modify: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminWorkbenchPort.java`
- Modify: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminWorkbenchService.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStore.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchController.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStoreTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**
- `GET /api/admin/conversations` takes only optional `limit`, default `30`, maximum `100`.
- Produces recent `ConversationSummary` ordered by `lastMessageAt DESC`.

- [ ] Write failing store and integration tests showing the endpoint returns conversations across users without a user ID and in descending activity order.
- [ ] Replace required-user lookup with `recentConversations(int limit)` through port, service, store, and controller.
- [ ] Keep conversation detail authorization on the independent admin boundary.
- [ ] Update OpenAPI and fixture coverage, regenerate types, and run focused tests.

### Task 3: Reusable modal and model catalog interaction

**Files:**
- Create: `frontend/src/admin/components/AdminModal.tsx`
- Modify: `frontend/src/admin/pages/Models.tsx`
- Modify: `frontend/src/admin/admin.css`
- Modify: `frontend/src/admin/AdminWorkbench.test.tsx`

**Interfaces:**
- `AdminModal` props: `open`, `title`, `description`, `busy`, `onClose`, `children`, `footer`.
- Model creation always sends `supportsStreaming: true`.

- [ ] Add failing UI tests proving “新增模型” opens `role=dialog`, the existing model cards remain in the same list, no streaming control is rendered, and capability declarations are under a collapsed disclosure.
- [ ] Add a failing test proving model enable/disable is inside a card action menu rather than the footer metadata row.
- [ ] Implement accessible modal close behavior, consistent modal footer, and shared primary/secondary/danger button styles.
- [ ] Move the model form into the modal; use a compact `<details>` section for Tool Calling and Image Input capability declarations.
- [ ] Move enable/disable into a top-right overflow menu and keep Provider/Model ID alone in the card footer.
- [ ] Run the admin frontend test, TypeScript, and ESLint.

### Task 4: Prompt and Skill creation modals

**Files:**
- Modify: `frontend/src/admin/api.ts`
- Modify: `frontend/src/admin/pages/ComponentType.tsx`
- Create: `frontend/src/admin/components/ResourceCreateModal.tsx`
- Modify: `frontend/src/admin/admin.css`
- Modify: `frontend/src/admin/AdminWorkbench.test.tsx`

**Interfaces:**
- Adds `admin.createPrompt(payload)` and `admin.createSkill(payload)`.
- `ResourceCreateModal` supports only `PROMPT` and `SKILL`, receives Tool definitions for Skill dependency selection, and returns the created resource to the list.

- [ ] Add failing tests for visible “新增提示词/新增技能” actions, modal fields, cancel behavior, POST payloads, and immediate appearance of created cards.
- [ ] Add typed create methods to the admin API.
- [ ] Implement Prompt fields: key, name, description, template.
- [ ] Implement Skill fields: key, name, description, when to use, when not to use, content, required Tool multi-select.
- [ ] Reuse `AdminModal` and the unified button/form styles; keep Tool and Hook creation unavailable.
- [ ] Run the focused frontend tests, TypeScript, and ESLint.

### Task 5: Conversation-first Trace and grouped execution details

**Files:**
- Create: `frontend/src/admin/components/ExecutionDetails.tsx`
- Create: `frontend/src/admin/components/TraceConversation.tsx`
- Create: `frontend/src/admin/components/traceEvents.ts`
- Create: `frontend/src/admin/components/traceEvents.test.ts`
- Modify: `frontend/src/admin/pages/ConversationTracePage.tsx`
- Modify: `frontend/src/admin/pages/RunTracePage.tsx`
- Modify: `frontend/src/admin/api.ts`
- Modify: `frontend/src/admin/admin.css`
- Modify: `frontend/src/admin/AdminWorkbench.test.tsx`

**Interfaces:**
- `groupTraceEvents(events: TraceEvent[]): ExecutionStage[]` merges matching Tool start/completion events and drops raw TOKEN presentation.
- `TraceConversation` renders a user message, Markdown Agent message, lightweight run metadata, and optional `ExecutionDetails` disclosure.
- `admin.listConversations()` takes no user ID.

- [ ] Write failing pure tests for Tool event merging, TOKEN suppression, approval grouping, completion, and failure grouping.
- [ ] Write failing component tests proving UUID controls and the recent-runs card are absent, recent conversations load automatically, messages use chat semantics, Markdown renders, and execution details are collapsed by default.
- [ ] Implement event grouping by semantic stage while retaining sequence order and readable fallback handling for unknown events.
- [ ] Rebuild Conversation Trace as recent conversation list plus selected chat; lazily fetch Run Trace when an Agent message’s execution disclosure is opened.
- [ ] Rebuild standalone Run Trace with the shared conversation component; move status/model/duration/token metadata into a compact line and remove raw metric cards/time axis.
- [ ] Run Trace pure/component tests, TypeScript, and ESLint.

### Task 6: Unified verification and real-page acceptance

**Files:**
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`
- Modify: `docs/acceptance/2026-08-10-full-personal-app-acceptance.md`

- [ ] Run `./mvnw -q test` and confirm exit code `0`.
- [ ] Run frontend full Vitest, TypeScript, ESLint, and production build.
- [ ] Run Spotless, contract lint/generation idempotence, Architecture Tests, and `git diff --check`.
- [ ] Restart the local stack from the current source and verify 5173/8080 single listeners.
- [ ] Use the real admin pages to create a temporary Prompt and Skill, inspect model modal/menu, open recent conversation, and inspect grouped Run execution.
- [ ] Record any remaining personal-app usability gaps without expanding into enterprise design.

