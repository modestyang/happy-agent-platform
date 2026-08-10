# AI Streaming and Plan Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream both AI chats, render safe Markdown and public execution progress, persist confirmed day/week training plans, and close the three acceptance leftovers.

**Architecture:** Contract-first Run/SSE endpoints persist replayable events in the agent schema. A frozen approval payload resumes an idempotent `fitness.plan.save` tool, which writes only through the Fitness service and its own schema transaction. Both React chats share a stream consumer and Markdown renderer.

**Tech Stack:** Java 17, Spring MVC `SseEmitter`, PostgreSQL/Flyway, React 19, TypeScript, `react-markdown`, `remark-gfm`, Vitest, Testcontainers.

## Global Constraints

- Work in the current `main` checkout because the user explicitly requested `main`; preserve all existing uncommitted MiniMax work.
- Never persist or emit raw model chain-of-thought; expose only public progress summaries and Tool lifecycle events.
- Never overwrite completed workout plans.
- Agent Builder never queries the fitness schema; writes go through the registered Fitness Tool and Fitness service.
- Do not commit; the user did not request a commit.
- Run focused RED/GREEN checks per task, then one combined verification and browser smoke pass.

---

### Task 1: Contract-first streaming and approval API

**Files:**
- Modify: `docs/architecture/openapi/public-v1.yaml`
- Modify: `docs/architecture/openapi/admin-v1.yaml`
- Modify: `scripts/contracts/fixtures/public-coverage.json`
- Modify: `scripts/contracts/fixtures/admin-coverage.json`
- Regenerate: `frontend/src/api/generated/public.ts`
- Regenerate: `frontend/src/api/generated/admin.ts`

**Interfaces:**
- Produces `CreateAgentRunResponse { runId, sessionId?, status }`.
- Produces replayable `AgentRunEvent` variants including public progress, text delta, Tool, plan proposal, approval, error and completed.
- Produces `POST .../runs/{runId}/approvals/{approvalId}` with `{ decision: "APPROVE" | "REJECT" }` and idempotency key.

- [ ] Add failing contract fixtures for public/admin Run creation, SSE and approval decisions.
- [ ] Run `node scripts/contracts/lint.mjs` and verify the missing operations/schemas fail.
- [ ] Add exact operations and closed schemas to both OpenAPI documents.
- [ ] Run `node scripts/contracts/lint.mjs && node scripts/contracts/generate-types.mjs`.
- [ ] Verify generated types match the documents with `git diff --exit-code frontend/src/api/generated || true`, inspecting only expected generated changes.

### Task 2: Durable Run events and approval state

**Files:**
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V12__streaming_run_approvals.sql`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepository.java`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/RunEventStream.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepositoryTest.java`

**Interfaces:**
- Produces `appendStreamEvent(runId, type, payload)` and `eventsAfter(runId, lastEventId)`.
- Produces `requestApproval(runId, userId, toolKey, arguments)` and atomic `decideApproval(...)` returning one of `CLAIMED`, `ALREADY_DECIDED`, `NOT_FOUND`.

- [ ] Write Testcontainers tests proving ordered replay, user/run binding, single approval claim, repeated identical decision idempotency and conflicting decision rejection.
- [ ] Run the focused infrastructure test and confirm RED because tables/methods do not exist.
- [ ] Add the V8 tables/indexes and minimal repository records/methods.
- [ ] Run the focused test and confirm GREEN.

### Task 3: Fitness plan proposal and save Tool

**Files:**
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessDtos.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessPorts.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessApplicationService.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessStore.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`

**Interfaces:**
- Adds `TrainingPlanProposal(scope, days)` and `TrainingPlanDay(date, title, estimatedMinutes, exercises)`.
- Adds `FitnessStore.saveTrainingPlan(userId, approvalId, proposal)`.
- Registers `fitness.plan.save` as `WRITE`, approval `ALWAYS`, scope `fitness.write`; input contains only frozen plan content plus approval ID and never a user ID.

- [ ] Write integration tests for one-day save, seven-day save, duplicate approval idempotency, replacement of planned rows and preservation of completed rows.
- [ ] Run the focused integration tests and confirm RED on the missing service/tool behavior.
- [ ] Add validation: DAY contains exactly one local date, WEEK exactly seven consecutive dates, 1-12 exercises per day, positive bounded minutes, existing exercise IDs only.
- [ ] Implement one fitness transaction that deletes only replaceable planned rows and inserts the frozen proposal; persist the approval idempotency result.
- [ ] Register the save Tool and grant `fitness.write` only after approved resume.
- [ ] Run the focused integration tests and confirm GREEN.

### Task 4: Unified asynchronous Agent Run execution

**Files:**
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/AgentRuntimeConversation.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntime.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/StreamingChatClient.java`
- Create: `starter/src/main/java/happy/jayden/yang/fitness/FitnessAgentRunService.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/StreamingChatClientTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessAgentRunServiceTest.java`

**Interfaces:**
- `createRun(userId, message, mode)` returns immediately.
- `resumeApproval(userId, runId, approvalId, decision)` is idempotent.
- Runtime emits public progress and visible text incrementally while aggregating final assistant content.

- [ ] Add RED tests for per-chunk visible text, `<think>` boundary splitting without leakage, public progress order, proposal-to-WAITING_APPROVAL, approve-to-save and reject-without-save.
- [ ] Extend the stream parser so chunks are classified as reasoning/internal versus visible text; discard raw reasoning from persisted/output channels.
- [ ] Execute Runs on the application executor and append every visible delta before completion.
- [ ] Freeze a plan proposal and pause before invoking `fitness.plan.save`; resume only from the claimed approval.
- [ ] Make the Playground use the same `fitness.coach` service path and event semantics; retain tool-free handling for other agents.
- [ ] Run focused runtime tests and confirm GREEN.

### Task 5: Spring controllers and SSE replay

**Files:**
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessV1Controller.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminPlaygroundController.java`
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/AgentRunSseControllerSupport.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchControllerIntegrationTest.java`

**Interfaces:**
- Implements the exact Task 1 operations with session ownership checks and `SseEmitter` replay from `Last-Event-ID`.
- SSE closes on `COMPLETED`/terminal error and sends heartbeat comments while active.

- [ ] Add MockMvc RED tests that message submission returns before model completion, SSE replays in order, unauthorized users cannot read/decide another Run, and repeated approve is idempotent.
- [ ] Implement public/admin Run creation, event stream and decision endpoints.
- [ ] Keep legacy synchronous endpoints only as temporary compatibility wrappers if tests still consume them; the two pages must stop using them.
- [ ] Run focused controller integration tests and confirm GREEN.

### Task 6: Shared stream and Markdown UI

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Modify: `frontend/src/components/ChatMarkdown.tsx`
- Create: `frontend/src/components/AgentRunMessage.tsx`
- Create: `frontend/src/hooks/useAgentRunStream.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/admin/pages/PlaygroundPage.tsx`
- Modify: `frontend/src/app.css`
- Modify: `frontend/src/admin/admin.css`
- Test: `frontend/src/components/ChatMarkdown.test.tsx`
- Test: `frontend/src/hooks/useAgentRunStream.test.tsx`
- Test: `frontend/src/App.test.tsx`
- Test: `frontend/src/admin/AdminApp.test.tsx`

**Interfaces:**
- `useAgentRunStream` accumulates deltas by Run, reconnects with last event ID and exposes `progress`, `proposal`, `approvalStatus`, `status`, `error`.
- `AgentRunMessage` renders Markdown, collapsed public progress and confirm/cancel buttons.

- [ ] Install approved dependencies with `npm --prefix frontend install react-markdown remark-gfm`.
- [ ] Add RED tests for GFM tables/lists/code/link safety, incremental text, collapsed progress, DAY/WEEK confirmation card, approval button locking and reconnect deduplication.
- [ ] Replace the hand-written parser; do not enable raw HTML.
- [ ] Implement the shared hook and message component using fetch streaming so authenticated cookie/error responses remain observable.
- [ ] Switch fitness AI and Playground from blocking message endpoints to Run/SSE.
- [ ] Add responsive styles matching each existing visual system and run focused Vitest tests to GREEN.

### Task 7: P1 meal empty state and P3 recent Trace

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/admin/pages/ConversationTracePage.tsx`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/admin/api.ts`
- Test: `frontend/src/App.test.tsx`
- Test: `frontend/src/admin/AdminApp.test.tsx`

**Interfaces:**
- Meal empty state calls existing `generateDailyMealPlan(date, idempotencyKey)` and reuses current polling.
- Runs page calls `listRuns({ size })` on load; UUID becomes an optional filter.

- [ ] Add RED tests for generate/loading/retry/success meal states and default recent Runs without a UUID.
- [ ] Wire the existing meal generation operation and inline retry UI.
- [ ] Load recent Runs by default and retain optional user/agent/status filters without introducing advanced search infrastructure.
- [ ] Run the focused frontend tests and confirm GREEN.

### Task 8: Combined verification and real-page smoke

**Files:**
- Update: `task_plan.md`
- Update: `findings.md`
- Update: `progress.md`

**Interfaces:**
- Produces one concise handoff with targeted evidence; no new full acceptance report.

- [ ] Run contract lint/type generation consistency.
- [ ] Run focused Maven tests for Agent infrastructure, Fitness integration and starter controllers.
- [ ] Run `npm --prefix frontend test`, `npm --prefix frontend run typecheck`, targeted ESLint and `./mvnw spotless:apply` for touched Java.
- [ ] Run `git diff --check` and inspect `git status` for accidental secrets or unrelated changes.
- [ ] Restart the local stack once, then use real pages to verify streamed Markdown in both chats, collapsed execution progress, DAY and WEEK proposal confirmation, meal generation entry and recent Trace list.
- [ ] Record only defects discovered during this smoke pass; fix them with focused RED/GREEN and repeat the affected smoke path, not the entire acceptance suite.
