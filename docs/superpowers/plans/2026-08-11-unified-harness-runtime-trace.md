# Unified Harness Runtime and Trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every published Agent through its selected real framework, emit one replayable block-level event stream, and persist/render the resulting trace for both the playground and fitness chat.

**Architecture:** `agentbuilder-core` owns a framework-neutral reply, block and event contract. AgentScope Java 2.0.2 and Spring AI Alibaba 1.1.2.3 translate their own streams into that contract. The service layer resolves one immutable published snapshot and writes the same events to the existing trace/SSE stores; controllers only select the authenticated caller and expose the stream.

**Tech Stack:** Java 17, Reactor, AgentScope Java 2.0.2, Spring AI Alibaba 1.1.2.3, Spring AI 1.1.2, PostgreSQL JSONB, SSE, React/TypeScript.

## Global Constraints

- Use AgentScope Java `agentscope-core` and `agentscope-harness` `2.0.2`; do not import AgentScope Python APIs.
- Use Spring AI Alibaba Agent Framework `1.1.2.3`, retaining Spring AI `1.1.2`; do not use `2.0.0-M1.1`.
- A `replyId` identifies a complete assistant reply. Its ordered events must reconstruct exactly one persisted reply, including tool and confirmation state.
- Core has no framework, JDBC, SSE, Spring, provider-key or fitness-schema dependency.
- The published Agent snapshot is the only runtime configuration source. Provider credentials and trusted context never enter trace payloads.
- Tool/Skill/Hook/Agent are independent runtime concepts, not a generic component abstraction.
- Keep the existing single Agent baseline migration; do not create incremental Agent migrations.
- Use focused tests for each changed boundary, not a full unrelated regression suite after each edit.

---

### Task 1: Upgrade framework coordinates and establish the neutral reply protocol

**Files:**
- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/pom.xml`
- Modify: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/pom.xml`
- Modify: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/runtime/RunEvent.java`
- Create: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/runtime/AssistantReply.java`
- Create: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/runtime/ResponseBlock.java`
- Create/Test: `agentbuilder/agentbuilder-core/src/test/java/happy/jayden/yang/agentbuilder/core/runtime/AssistantReplyTest.java`

**Consumes:** Existing `RunRequest`, `RunResult`, `ResolvedTool` and persistent trace sequence.

**Produces:** `AssistantReply`, typed text/thinking/tool/media/hint blocks, stable `replyId`/`blockId`/`toolCallId`, and a `RunEvent` lifecycle containing `REPLY_*`, `MODEL_CALL_*`, `BLOCK_*`, `TOOL_*`, `SKILL_*`, `HOOK_*`, `CONTEXT_ASSEMBLED`, `MEMORY_*` and confirmation events.

- [ ] Write tests that replay `REPLY_STARTED`, block start/delta/completion, tool result and `REPLY_ENDED` into one `AssistantReply`; assert order, IDs, accumulated text and terminal state.
- [ ] Run the core test and confirm it fails because reply/block contracts do not exist.
- [ ] Add the sealed block hierarchy and immutable reply accumulator; replace unstructured event names with the expanded lifecycle while retaining `RUN_*` terminal events for run ownership.
- [ ] Upgrade AgentScope to `2.0.2` with `agentscope-harness`, and SAA to `1.1.2.3`.
- [ ] Run `./mvnw -pl agentbuilder/agentbuilder-core test` and `./mvnw -pl agentbuilder/agentbuilder-framework-adapter -am -DskipTests compile`.

### Task 2: Move AgentScope onto HarnessAgent and native event translation

**Files:**
- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/AgentScopeAdapter.java`
- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/AgentScopeRuntimeBridge.java`
- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/OpenAiAgentScopeModelTransport.java`
- Test: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/test/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/AgentScopeAdapterTest.java`

**Consumes:** Task 1 reply/event protocol and the existing published Tool, Skill, Hook and Memory inputs in `RunRequest`.

**Produces:** A real `HarnessAgent.streamEvents()` flow that maps native block, model-call, permission and terminal events without flattening `Msg` to text.

- [ ] Add a failing adapter test for native thinking/text/tool blocks and a confirmation-required ToolCall; assert `replyId`, block lifecycle, tool input/output and `RUN_WAITING_APPROVAL`.
- [ ] Replace 1.x `ReActAgent.stream(... StreamOptions)` construction with 2.0.2 `HarnessAgent` construction and `streamEvents()` subscription after compiling against actual 2.0.2 APIs.
- [ ] Map native event IDs where supplied; generate deterministic IDs only when the framework omits them. Mark native thinking with `NATIVE` fidelity and emit a degradation event only when no reasoning boundary exists.
- [ ] Execute published Skills through the Harness Skill mechanism, emit discovery/load events, and map PermissionEngine outcomes to frozen ToolCall states.
- [ ] Run the adapter test class and compile the adapter module.

### Task 3: Update the SAA bridge to complete NodeOutput translation

**Files:**
- Modify: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaAdapter.java`
- Modify: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaRuntimeBridge.java`
- Modify: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaToolCallback.java`
- Test: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/test/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaAdapterTest.java`

**Consumes:** Task 1 protocol and SAA `ReactAgent.stream(...): Flux<NodeOutput>` / `StreamingOutput` lifecycle.

**Produces:** Model boundaries, explicit reasoning metadata, text, ToolCall, ToolResult and finish reason events with transparent reasoning degradation.

- [ ] Add failing tests for `reasoningContent`, normal text, streamed ToolCall arguments, ToolResult and an SAA stream with no explicit reasoning.
- [ ] Change the runtime from `streamMessages()` text extraction to `NodeOutput`/`StreamingOutput.outputType` consumption.
- [ ] Emit Thinking blocks only for explicit metadata or explicit provider markup. Preserve normal pre-tool text as text with phase metadata and emit `REASONING_UNAVAILABLE` when differentiation is unavailable.
- [ ] Emit Hook, Skill and Tool event boundaries using the shared IDs and include safe input/output payloads.
- [ ] Run the SAA adapter test class and compile its module.

### Task 4: Replace direct HTTP generic execution with the framework registry service

**Files:**
- Modify: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/workbench/AdminWorkbenchService.java`
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/runtime/PublishedAgentRunService.java`
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/runtime/AgentRunSnapshotPort.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntime.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchConfig.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminPlaygroundRunService.java`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminPlaygroundRunServiceTest.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntimeTest.java`

**Consumes:** Core `AgentFrameworkAdapter`, published snapshot and existing `JdbcRunTraceRepository` run/conversation APIs.

**Produces:** One generic execution service that selects the registered adapter from `frameworkKey`; no generic route directly constructs `StreamingChatClient`.

- [ ] Add a failing test that runs a non-fitness published Agent with an injected framework adapter and verifies its Skill and framework key reached the adapter.
- [ ] Resolve an immutable published snapshot to `RunRequest` with bounded conversation history, model endpoint/credential, tools, skills, hooks and trusted context.
- [ ] Persist `RUN_STARTED` and every normalized event in order, rebuild the final `AssistantReply`, save the final assistant text, and map terminal state to `agent_runs`.
- [ ] Register both adapters in `starter`; fail published Agent validation if its framework is not registered or cannot support its bound capabilities.
- [ ] Leave `fitness.coach` on the fitness-specific route only until Task 5 moves its streaming conversation through the same normalizer.
- [ ] Run the two targeted generic-playground tests.

### Task 5: Unify fitness chat, Trace persistence and SSE/frontend reconstruction

**Files:**
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessAgentRunService.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepository.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessV1Controller.java`
- Modify: `frontend/src/components/AgentRunMessage.tsx`
- Modify: `frontend/src/admin/components/traceEvents.ts`
- Modify: `frontend/src/admin/components/TraceConversation.tsx`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`
- Test: `frontend/src/components/AgentRunMessage.test.tsx`
- Test: `frontend/src/admin/components/traceEvents.test.ts`

**Consumes:** Normalized events and final replies from Tasks 1–4.

**Produces:** Identical SSE and stored trace interpretation for app chat and playground, including collapsed thinking, Markdown text, Tool cards, Skill/Hook/context sections and plan-save confirmation.

- [ ] Add failing reducer tests that reconstruct an assistant reply from SSE events and preserve a tool card across reconnect/replay.
- [ ] Store complete normalized payloads in JSONB while keeping `title`/`detail` as derived list projections; retrieve the full payload in trace detail.
- [ ] Replace hand-written `TEXT_DELTA`/plan proposal events with normalized events. `fitness.plan.save` must enter `ASKING`, freeze tool arguments, and resume only after user confirmation.
- [ ] Update the two frontends to reduce by `replyId + blockId`, render Markdown and collapsed Thinking, and expose tool/confirmation state rather than raw event text.
- [ ] Run focused backend fitness test and two frontend test files.

### Task 6: Focused end-to-end verification and independent code review

**Files:**
- Test: existing targeted adapter, playground, fitness and frontend tests from Tasks 1–5
- Review: modified runtime, adapter and trace files

**Consumes:** Completed implementation.

**Produces:** Evidence that both frameworks are real runtime paths and a separate code-review report with only actionable issues.

- [ ] Build the changed Maven modules once, then run the two adapter suites, generic playground suite, fitness streaming suite and frontend reducer/chat suites.
- [ ] Exercise one AgentScope Agent and one SAA Agent against the local UI/API, verifying streamed text, final reply, Skill loading and persisted Trace.
- [ ] Dispatch an independent CR Agent with the changed-file list and acceptance rules. Address only confirmed defects it finds.
- [ ] Run the exact failing test for each CR fix, re-run the affected suite, then report the result and remaining limitations.
