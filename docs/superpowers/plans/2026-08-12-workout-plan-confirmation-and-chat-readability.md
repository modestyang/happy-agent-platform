# Workout Plan Confirmation and Chat Readability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show trusted exercise names in workout-plan confirmations, accept “全身” as an unfiltered candidate-query alias, raise AI chat typography to the approved mobile sizes, and present progress as an expanded pre-reply state that collapses when the reply starts.

**Architecture:** Keep approval execution arguments immutable and ID-only, but derive a separate display proposal from trusted candidate/detail Tool results already present in the same Run. Normalize “全身” inside Fitness service before the existing SQL filter, scope typography overrides to the AI page, and derive progress disclosure state from whether the current message already has reply content without changing public contracts or persisted messages.

**Tech Stack:** Java 17, Maven, JUnit 5, Mockito, Testcontainers, React 19, TypeScript, Vitest, Testing Library, CSS.

## Global Constraints

- Do not add or upgrade dependencies.
- Do not change OpenAPI endpoints, database migrations, CI/CD, or deployment scripts.
- Do not query the fitness schema from `agentbuilder/**`; only consume existing framework-neutral Run events.
- Do not put model-provided display names into frozen approval arguments.
- Do not commit or push; repository rules require an explicit user request.
- Preserve all existing user changes and avoid unrelated refactors.

---

### Task 1: Normalize the “全身” candidate-query alias

**Files:**
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessAgentQueryServiceTest.java`
- Modify: `starter/src/test/java/happy/jayden/yang/fitness/FitnessToolsTest.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessAgentQueryService.java`
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`

**Interfaces:**
- Consumes: `FitnessAgentQueryService.exerciseCandidates(UUID, List<String>, ExerciseImpactLevel, Integer)`.
- Produces: `focusAreas=["全身"]` maps to the existing empty focus list; mixed `全身` plus concrete areas throws a precise `IllegalArgumentException`.

- [x] **Step 1: Add failing service tests**

Add a test that captures the existing `ExerciseCandidateFilter`:

```java
@Test
void exerciseCandidatesTreatWholeBodyAsNoFocusWithoutRelaxingHardLimits() {
  when(store.findExerciseCandidates(any()))
      .thenReturn(new ExerciseCandidatePage(List.of(), 0, 0, List.of()));

  queries.exerciseCandidates(
      USER_ID, List.of(" 全身 "), ExerciseImpactLevel.LOW, Integer.valueOf(1));

  var filter = ArgumentCaptor.forClass(ExerciseCandidateFilter.class);
  verify(store).findExerciseCandidates(filter.capture());
  assertEquals(List.of(), filter.getValue().focusAreas());
  assertEquals(ExerciseImpactLevel.LOW, filter.getValue().maxImpactLevel());
}
```

Extend the validation test:

```java
var mixed = assertThrows(
    IllegalArgumentException.class,
    () -> queries.exerciseCandidates(USER_ID, List.of("全身", "核心"), null, 1));
assertEquals("全身不能与具体部位同时使用", mixed.getMessage());
```

- [x] **Step 2: Run the service test and verify RED**

Run:

```bash
./mvnw -pl starter -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=FitnessAgentQueryServiceTest test
```

Expected: the standalone “全身” test fails with `未知目标部位 全身`.

- [x] **Step 3: Implement the minimal normalization**

In `focusAreas(...)`, trim and reject null/blank/duplicates first, then add this exact branch before validating concrete labels:

```java
if (normalized.contains("全身")) {
  if (normalized.size() != 1) {
    throw new IllegalArgumentException("全身不能与具体部位同时使用");
  }
  return List.of();
}
```

Keep `TARGET_AREAS` unchanged so “全身” never becomes a database target area.

- [x] **Step 4: Clarify the Tool schema and lock it with a test**

Change `ExerciseCandidateRequest.focusAreas` description to:

```java
@AgentToolParam(
    description = "优先目标部位，最多 3 个；可用臀腿、核心、胸部、背部、肩部、手臂、心肺；全身必须单独使用并表示无部位偏好；可省略",
    required = false)
```

In `FitnessToolsTest`, assert the generated candidate input schema contains `全身必须单独使用`.

- [x] **Step 5: Run both tests and verify GREEN**

Run:

```bash
./mvnw -pl starter -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=FitnessAgentQueryServiceTest,FitnessToolsTest test
```

Expected: both classes pass with zero failures.

### Task 2: Derive a trusted display proposal without changing frozen arguments

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntimeTest.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntime.java`

**Interfaces:**
- Consumes: ordered `List<RunEvent>` and frozen `fitness.plan.save` arguments.
- Produces: `PendingApproval(toolKey, title, arguments, proposal)` where `arguments` remain execution truth and `proposal` is display-only.

- [x] **Step 1: Add a failing Runtime integration test**

Create a test adapter that emits one trusted candidate result followed by a `fitness_plan_save` confirmation for two IDs:

```java
new RunEvent(
    2,
    RunEvent.Type.TOOL_RESULT,
    now,
    Map.of(
        "toolName", "fitness_exercise_candidates_query",
        "result", Map.of(
            "candidates", List.of(
                Map.of("exerciseId", knownId.toString(), "name", "深蹲"))))),
new RunEvent(
    3,
    RunEvent.Type.CONFIRMATION_REQUIRED,
    now,
    Map.of(
        "toolName", "fitness_plan_save",
        "arguments", Map.of(
            "request", Map.of(
                "scope", "DAY",
                "days", List.of(Map.of(
                    "scheduledFor", "2026-08-13",
                    "title", "全身循环训练",
                    "estimatedMinutes", 20,
                    "exerciseIds", List.of(knownId.toString(), unknownId.toString()))))))
```

Bind a test Tool with key `fitness.plan.save` and runtime name `fitness_plan_save`. After `startStreaming(...)`, assert:

```java
var approvalEvent = traces.streamEventsAfter(started.runId(), 0).stream()
    .filter(event -> event.type().equals("APPROVAL"))
    .findFirst()
    .orElseThrow();
var proposal = map(approvalEvent.data().get("proposal"));
var day = map(((List<?>) proposal.get("days")).get(0));
var exercises = (List<?>) day.get("exercises");
assertEquals("深蹲", map(exercises.get(0)).get("name"));
assertEquals("动作 2", map(exercises.get(1)).get("name"));

var stored = traces.findApproval(started.runId(), approvalId).orElseThrow();
var frozenRequest = map(stored.arguments().get("request"));
var frozenDay = map(((List<?>) frozenRequest.get("days")).get(0));
assertFalse(frozenDay.containsKey("exercises"));
assertEquals(List.of(knownId.toString(), unknownId.toString()), frozenDay.get("exerciseIds"));
```

Also assert no proposal exercise has a UUID in its `name` field.

- [x] **Step 2: Run the Runtime test and verify RED**

Run:

```bash
./mvnw -pl agentbuilder/agentbuilder-infrastructure -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PublishedAgentPlaygroundRuntimeTest test
```

Expected: proposal has no `exercises` list because current `planProposal(...)` returns frozen request data unchanged.

- [x] **Step 3: Carry a separate display proposal through pending approval**

Change the record to:

```java
private record PendingApproval(
    String toolKey,
    String title,
    Map<String, Object> arguments,
    Map<String, Object> proposal) {}
```

In `pendingApproval(...)`, derive `proposal` from the same Run events and frozen arguments. In `waitForApproval(...)`, continue passing only `pending.arguments()` to `requestApproval(...)`, and add `pending.proposal()` only to the stream payload.

- [x] **Step 4: Extract trusted names and build the display days**

Add focused helpers in `PublishedAgentPlaygroundRuntime`:

```java
private Map<String, String> trustedExerciseNames(
    RunRequest request, List<RunEvent> events, long confirmationSequence)
private void collectExerciseNames(JsonNode node, Map<String, String> names)
private Map<String, Object> planProposal(
    Map<String, Object> arguments, Map<String, String> exerciseNames)
```

`trustedExerciseNames(...)` must:

- derive accepted runtime names only from Tool bindings whose keys are exactly `fitness.exercise.candidates.query` or `fitness.exercise.details.query`;
- accept only matching `TOOL_RESULT` events before the confirmation event; an unbound same-name event must fall back to `动作 N`;
- convert `result` with the existing Jackson mapper;
- recursively visit objects/arrays;
- accept only textual, UUID-parseable `exerciseId` plus nonblank textual `name`;
- preserve the first trusted name with `putIfAbsent`.

`planProposal(...)` must copy scope/day metadata, replace display `exerciseIds` with `exercises`, and use `动作 ${index + 1}` when the name map has no entry. It must never mutate `arguments` or the nested maps stored in the approval record.

- [x] **Step 5: Run the Runtime test and verify GREEN**

Run:

```bash
./mvnw -pl agentbuilder/agentbuilder-infrastructure -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PublishedAgentPlaygroundRuntimeTest test
```

Expected: trusted/fallback labels pass and frozen arguments remain ID-only.

### Task 3: Add the frontend UUID safety net and approved chat typography

**Files:**
- Modify: `frontend/src/components/AgentRunMessage.test.tsx`
- Modify: `frontend/src/components/AgentRunMessage.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/app.css`

**Interfaces:**
- Consumes: current and historical approval proposal shapes.
- Produces: readable labels for both shapes and AI-page-scoped computed typography.

- [x] **Step 1: Add a failing legacy proposal test**

In `AgentRunMessage.test.tsx`, reduce an `APPROVAL` event whose day has only `exerciseIds`:

```tsx
const completed = applyAgentRunEvent(
  { role: 'assistant', content: '' },
  {
    type: 'APPROVAL',
    data: {
      approvalId: 'approval-legacy',
      status: 'REQUESTED',
      title: '保存训练计划',
      proposal: {
        scope: 'DAY',
        days: [{
          scheduledFor: '2026-08-13',
          title: '全身循环训练',
          estimatedMinutes: 20,
          exerciseIds: ['60000000-0000-0000-0000-000000000001'],
        }],
      },
    },
  },
);
render(<AgentRunMessage message={completed} onDecision={vi.fn()} />);
expect(screen.getByText(/动作 1/)).toBeInTheDocument();
expect(screen.queryByText(/60000000-0000/)).not.toBeInTheDocument();
```

- [x] **Step 2: Add a failing computed-style test**

In `App.test.tsx`, load the real CSS and render representative chat elements under `.ai-page`. Assert:

```tsx
expect(getComputedStyle(messageBody).fontSize).toBe('16px');
expect(getComputedStyle(messageBody).lineHeight).toBe('1.65');
expect(getComputedStyle(progress).fontSize).toBe('14px');
expect(getComputedStyle(approvalButton).fontSize).toBe('14px');
expect(getComputedStyle(promptButton).fontSize).toBe('14px');
expect(getComputedStyle(sessionNote).fontSize).toBe('11px');
expect(getComputedStyle(composerInput).fontSize).toBe('16px');
```

- [x] **Step 3: Run the frontend tests and verify RED**

Run:

```bash
npm --prefix frontend test -- src/components/AgentRunMessage.test.tsx src/App.test.tsx
```

Expected: legacy proposal displays the UUID and computed chat sizes remain 12/10/8px.

- [x] **Step 4: Implement the frontend fallback**

Replace the `exerciseIds` fallback in `normalizeProposal(...)` with index-based labels:

```ts
exercises.push(...day.exerciseIds
  .filter((id): id is string => typeof id === 'string')
  .map((exerciseId, index) => ({ exerciseId, name: `动作 ${index + 1}` })));
```

When an `exercises[]` item has an ID but a blank name, apply the same index-based fallback instead of using the ID as `name`.

- [x] **Step 5: Implement scoped typography**

Update the existing rules and add higher-specificity AI-page overrides:

```css
.message-body { font-size: 16px; line-height: 1.65; }
.ai-page .run-progress { font-size: 14px; line-height: 1.55; }
.ai-page .run-approval header strong,
.ai-page .run-approval header small,
.ai-page .run-approval article b,
.ai-page .run-approval article small,
.ai-page .confirmation-card__message,
.ai-page .run-approval button { font-size: 14px; }
.ai-page .prompt-row button { font-size: 14px; }
.ai-page .surface-card__head small,
.ai-page .data-table-hint,
.ai-page .session-note { font-size: 11px; line-height: 1.45; }
```

Keep `.composer input` at `16px`, and do not change `.ai-head` or `.nav-link` typography.

- [x] **Step 6: Run the frontend tests and verify GREEN**

Run:

```bash
npm --prefix frontend test -- src/components/AgentRunMessage.test.tsx src/App.test.tsx
```

Expected: both files pass with zero failures.

### Task 4: Format and verify the complete change

**Files:**
- Verify all files listed above plus the design/plan/progress artifacts.

**Interfaces:**
- Consumes: completed Tasks 1–3.
- Produces: fresh evidence for formatting, regression behavior, module boundaries, compilation, and production frontend build.

- [x] **Step 1: Apply the repository formatter**

Run:

```bash
./mvnw spotless:apply
```

Review `git diff --stat` immediately afterward to ensure no unrelated files were reformatted.

- [x] **Step 2: Run focused RED/GREEN regression suites again**

Run:

```bash
./mvnw -pl starter -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=FitnessAgentQueryServiceTest,FitnessToolsTest test
./mvnw -pl agentbuilder/agentbuilder-infrastructure -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PublishedAgentPlaygroundRuntimeTest test
npm --prefix frontend test -- src/components/AgentRunMessage.test.tsx src/App.test.tsx
```

- [x] **Step 3: Run full frontend verification**

Run:

```bash
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
```

- [x] **Step 4: Run backend and architecture verification**

Run:

```bash
./mvnw test
./mvnw -pl architecture-tests -am test
./mvnw -DskipTests compile
```

Docker must be running for Testcontainers. If an unrelated baseline failure appears, rerun the failing test alone and report it without weakening or skipping the suite.

- [x] **Step 5: Inspect the final diff**

Run:

```bash
git diff --check
git status --short
git diff --stat
git diff -- application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessAgentQueryService.java application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntime.java frontend/src/components/AgentRunMessage.tsx frontend/src/app.css
```

Confirm line-by-line that all three user requirements are covered and no public contract, migration, dependency, CI/CD, deployment, commit, or push change exists.

### Task 5: Move progress before the reply and collapse it when output starts

**Files:**
- Modify: `frontend/src/components/AgentRunMessage.test.tsx`
- Modify: `frontend/src/components/AgentRunMessage.tsx`

**Interfaces:**
- Consumes: `AgentRunUiMessage.content`, `blocks`, `approval`, and filtered business progress stages.
- Produces: an expanded progress disclosure while the run has no reply output, then a collapsed disclosure before all reply content once output starts.

- [x] **Step 1: Add the failing component behavior test**

Render a progress-only message, assert the disclosure is open, then rerender with the first reply text and assert it is closed and precedes that text in DOM order:

```tsx
const progressOnly: AgentRunUiMessage = {
  role: 'assistant',
  content: '',
  progress: ['正在理解你的需求', '正在查看相关记录'],
};
const { rerender } = render(<AgentRunMessage message={progressOnly} />);
const progress = screen.getByText('处理进度').closest('details') as HTMLDetailsElement;
expect(progress).toHaveAttribute('open');

rerender(<AgentRunMessage message={{ ...progressOnly, content: '训练建议开始输出' }} />);
const reply = screen.getByText('训练建议开始输出');
expect(progress).not.toHaveAttribute('open');
expect(progress.compareDocumentPosition(reply) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
```

- [x] **Step 2: Run the test and verify RED**

Run:

```bash
npm --prefix frontend test -- src/components/AgentRunMessage.test.tsx
```

Expected: the progress-only disclosure lacks `open`, and after reply output the disclosure follows the reply in DOM order.

- [x] **Step 3: Implement the minimal derived disclosure behavior**

In `AgentRunMessage`, derive whether output has begun and render the progress block before all reply surfaces:

```tsx
const replyStarted = Boolean(message.content.trim() || message.blocks?.length || approval);

{progress.length > 0 && <details className="run-progress" open={!replyStarted}>...</details>}
<ChatMarkdown text={message.content} className={markdownClassName} />
```

Keep Markdown, structured blocks, and the legacy confirmation card in their existing relative order.

- [x] **Step 4: Run focused frontend verification and verify GREEN**

Run:

```bash
npm --prefix frontend test -- src/components/AgentRunMessage.test.tsx src/App.test.tsx
npm --prefix frontend run typecheck
npm --prefix frontend run lint
git diff --check
```

Expected: all commands exit successfully with no test failures or lint/type errors.

- [x] **Step 5: Reload the deployed page and verify the real stream**

Use a fresh AI conversation at `http://127.0.0.1:5173/ai`: submit a plan request, observe expanded stages before any answer text, then verify `处理进度` collapses above the first answer paragraph as streaming begins. Reject any generated save confirmation so acceptance does not create a plan.
