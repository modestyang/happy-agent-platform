# Agent Conversation and Trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist contextual AI conversations and expose user-scoped conversation Trace in the developer workbench.

**Architecture:** Add conversation/message persistence beside the existing Agent run repository. The fitness runtime owns conversation resolution and prompt composition; the admin API only reads persisted conversation data and the frontend presents the read model.

**Tech Stack:** Java 21, Spring MVC/JdbcTemplate, PostgreSQL/Flyway, React/TypeScript/Vitest.

## Global Constraints

- Preserve the independent developer and fitness authentication sessions.
- Do not modify `/Users/modest/IdeaProjects/fitness`.
- Use latest pre-release schema and seed data; no compatibility/backfill path.
- Limit prompt history to the latest 20 user/assistant messages.

---

### Task 1: Persist conversations and message turns

**Files:**
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V10__agent_conversations.sql`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/WorkspaceDtos.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepository.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/.../JdbcRunTraceRepositoryTest.java`

- [ ] Write a failing repository test that creates a user turn, resolves the same conversation within 24 hours, and returns ordered message history.
- [ ] Run the focused test and confirm it fails because the conversation API/schema is absent.
- [ ] Add the two tables, run linkage, repository DTOs, and parameterized repository methods.
- [ ] Re-run the focused test and confirm it passes.

### Task 2: Feed persisted history into the Agent runtime

**Files:**
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/AgentRuntimeConversation.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`

- [ ] Write a failing runtime integration test proving an earlier assistant/user turn is persisted and supplied on a subsequent call.
- [ ] Run it and confirm the current request contains only one user message.
- [ ] Resolve conversations, write terminal messages, link Runs, and assemble the bounded history before the current enriched request.
- [ ] Re-run the focused test and confirm it passes.

### Task 3: Add workbench conversation Trace browser

**Files:**
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchController.java`
- Modify: `frontend/src/admin/api.ts`
- Modify: `frontend/src/admin/AdminWorkbench.tsx`
- Create: `frontend/src/admin/pages/ConversationTracePage.tsx`
- Modify: `frontend/src/admin/admin.css`
- Test: `frontend/src/admin/AdminWorkbench.test.tsx`

- [ ] Write a failing frontend test for the Trace nav and user conversation search.
- [ ] Run it and confirm the Trace route/menu does not exist.
- [ ] Add protected conversation-list/detail endpoints and the user-id → conversation → Run trace flow.
- [ ] Re-run frontend tests and confirm the new test passes.

### Task 4: Verify the full chain

- [ ] Run focused Java repository/runtime tests and frontend tests.
- [ ] Run backend module tests and frontend typecheck.
- [ ] Start the local application against the acceptance database, make two safe test conversations, and verify the Trace UI shows their messages and links to a Run.
