# Fitness Tool Loop and SAA Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow arbitrary non-contiguous training dates, remove the artificial three-focus-area limit, return correctable Tool failures to the model loop, and make SAA call configured OpenAI-compatible endpoints correctly with useful safe errors.

**Architecture:** Extend the neutral Tool contract with list bounds and a side-effect-free argument preflight while keeping `AgentToolHandler` functional-interface compatible. Both adapters classify model-correctable failures as structured Tool results and leave permission, budget, hook, timeout and infrastructure failures terminal. Fitness publishes a v2 save contract without model-supplied scope; approval/UI scope is derived as `DAY` or `MULTI_DAY`, with legacy `WEEK` events still readable.

**Tech Stack:** Java 17, Maven, Jackson, Reactor, AgentScope Java 2.0.2, Spring AI Alibaba 1.1.2.2/Spring AI 1.1.2, PostgreSQL integration tests, OpenAPI-generated TypeScript, React/Vitest.

**Spec:** `docs/superpowers/specs/2026-08-14-fitness-tool-loop-and-saa-runtime-design.md`

## Global Constraints

- A save request contains 1 to 31 unique training dates; dates need not be contiguous.
- `focusAreas` accepts any non-duplicated subset of the seven known concrete areas; `全身` remains exclusive and normalizes to no preference.
- Correctable input/domain failures enter the framework-native model loop and consume existing Tool budgets; no custom while-loop or hidden retry is added.
- Permission, approval, fail-closed hooks, call limits, timeout, interruption, Provider/database failure, output-schema failure and protocol failure remain terminal.
- Approved writes execute once; preflight never writes data.
- Existing approvals containing legacy `scope` and historic proposal events containing `WEEK` remain readable/executable.
- Do not add dependencies, database migrations or cross-schema access.
- Do not commit, publish an Agent version, deploy, or push without separate user authorization.

---

### Task 1: Neutral list constraints and side-effect-free Tool preflight

**Files:**
- Modify: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/tool/AgentToolParam.java`
- Modify: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/tool/AgentToolHandler.java`
- Create: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/tool/ToolInputException.java`
- Create: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/tool/ToolErrorResponse.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/tool/ToolSchemaGenerator.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/tool/ReflectiveAgentToolHandler.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/tool/DefaultToolRegistry.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/tool/SpringToolCatalogScannerTest.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/tool/DefaultToolRegistryTest.java`

**Interfaces:**
- Produces: `AgentToolParam.minItems()/maxItems()` with `-1` meaning absent.
- Produces: `AgentToolHandler.validate(Map<String,Object>) throws Exception`, a default no-op preserving the single abstract `invoke(...)`.
- Produces: reflective validation that maps nested DTOs without invoking the annotated Tool method.
- Produces: `ToolInputException` as the only cross-framework marker for model-correctable input/domain failures, and `ToolErrorResponse.invalidArgument(...)` as the shared safe JSON response.

- [ ] **Step 1: Write failing Schema and preflight tests**

Add a scanner fixture:

~~~java
record BoundedListRequest(
    @AgentToolParam(description = "values", minItems = 1, maxItems = 7)
        List<String> values) {}

assertEquals(1, property(requestSchema, "values").get("minItems"));
assertEquals(7, property(requestSchema, "values").get("maxItems"));
~~~

Add invalid annotation tests for bounds below `-1`, `minItems > maxItems`, and list constraints on a non-array. Add a registry test whose handler overrides `validate(...)` and assert secured validation does not increment the invoke counter.
Add core tests proving `ToolErrorResponse.invalidArgument(...)` emits exactly `ok=false`, `code=INVALID_ARGUMENT`, a bounded safe message and `retryable=true`, without cause/stack fields.

- [ ] **Step 2: Run RED tests**

~~~bash
./mvnw -q -pl agentbuilder-infrastructure -am \
  -Dtest=SpringToolCatalogScannerTest,DefaultToolRegistryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
~~~

Expected: compilation fails because the list annotations and preflight method do not exist.

- [ ] **Step 3: Implement list constraints and reflective preflight**

Add:

~~~java
int minItems() default -1;
int maxItems() default -1;
~~~

Keep the handler functional:

~~~java
default void validate(Map<String, Object> modelArguments) throws Exception {}
~~~

Make `ToolSchemaGenerator.applyConstraints` require `type=array` and emit valid bounds. Refactor `ReflectiveAgentToolHandler` so `validate` and `invoke` share argument mapping, but only `invoke` calls `Method.invoke`. Replace the secured registry lambda with an anonymous handler forwarding `validate` while applying scopes only to `invoke`.

`ToolInputException` wraps only input Schema, argument mapping, DTO preflight and explicitly model-correctable handler `IllegalArgumentException` failures. `ToolErrorResponse` owns the shared JSON shape so the adapters cannot drift. Output encoding, missing context, scopes, budgets and infrastructure exceptions must never be wrapped with this marker.

- [ ] **Step 4: Run the Step 2 command and require GREEN.**

---

### Task 2: Fitness plan and focus-area contracts

**Files:**
- Modify: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/agent/FitnessTools.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessDtos.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessApplicationService.java`
- Modify: `application/fitness/fitness-service/src/main/java/happy/jayden/yang/fitness/service/FitnessAgentQueryService.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessToolsTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessAgentQueryServiceTest.java`
- Test: `starter/src/test/java/happy/jayden/yang/fitness/FitnessExperienceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 annotations/preflight.
- Produces: `fitness.plan.save@2` with `request.days` 1..31 and no model-visible `scope`.
- Produces: `SaveTrainingPlanRequest(UUID approvalId, List<TrainingPlanDayInput> days)`.

- [ ] **Step 1: Write failing contract tests**

Assert v2, absence of `scope`, `days minItems=1/maxItems=31` and `focusAreas maxItems=7`. Invoke candidates with four known areas and assert all four reach the service. Retain rejection tests for duplicates, unknown values and mixed `全身`.

- [ ] **Step 2: Write failing save tests**

Save dates `start`, `start.plusDays(2)` and `start.plusDays(4)` without scope and assert three plan IDs. Reject empty/32/duplicate dates, invalid date range, title/minutes, and invalid exercise lists.

- [ ] **Step 3: Run RED**

~~~bash
./mvnw -q -pl starter -am \
  -Dtest=FitnessToolsTest,FitnessAgentQueryServiceTest,FitnessExperienceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
~~~

Expected: failures on v1 Schema, four-area rejection and DAY/WEEK validation.

- [ ] **Step 4: Implement the v2 contract**

Use:

~~~java
public record SavePlanToolRequest(
    @AgentToolParam(description = "确认后由服务端注入的确认记录 ID", required = false)
        UUID approvalId,
    @AgentToolParam(
        description = "1 到 31 个任意训练日期；日期不可重复，不要求连续",
        minItems = 1,
        maxItems = 31)
        List<ToolPlanDay> days) {
  public SavePlanToolRequest {
    days = validateAndSortDays(days);
  }
}
~~~

The compact constructor validates model-only fields and sorts dates. Update the Tool description/example to a valid request without `approvalId/scope`. Remove `scope` from the service DTO, replace the 1/7 rule with an explicit 1..31 rule, replace continuity with date uniqueness, and retain date/content/exercise ownership and transaction checks. Remove the three-area service check and annotate `focusAreas maxItems=7`.

- [ ] **Step 5: Run the Step 3 command and require GREEN.**

---

### Task 3: AgentScope correctable-error loop

**Files:**
- Modify: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/AgentScopeRuntimeBridge.java`
- Test: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/test/java/happy/jayden/yang/agentbuilder/framework/adapter/agentscope/AgentScopeAdapterContractTest.java`

**Interfaces:**
- Consumes: Task 1 preflight.
- Consumes: shared `ToolInputException` and `ToolErrorResponse` from Task 1.
- Produces: the shared error JSON in `ToolResultBlock` state `ERROR`.

- [ ] **Step 1: Write a failing correction-loop test**

The scripted model first sends invalid input, observes an ERROR Tool result, sends corrected input and returns final text. Assert two model turns, `TOOL_FAILED`, later success and terminal `RUN_COMPLETED`. Keep terminal tests for scopes, hooks, duplicate IDs, budgets, timeout, missing trusted context and `IllegalStateException("database unavailable")`.

- [ ] **Step 2: Run RED**

~~~bash
./mvnw -q -pl agentscope-adapter -am \
  -Dtest=AgentScopeAdapterContractTest test
~~~

Expected: invalid input still terminates the Run.

- [ ] **Step 3: Implement correctable results**

Override `checkPermissions`: valid preflight returns passthrough so ASK/ALLOW rules remain authoritative; a `ToolInputException` returns ALLOW so `callAsync` can deliver the error without prompting. In `callAsync`, reserve budget first; wrap only input Schema/preflight and handler-thrown `IllegalArgumentException` as `ToolInputException`, return `ToolResultBlock.error(ToolErrorResponse.invalidArgument(error).json()).withIdAndName(...)`, emit `TOOL_FAILED`, and do not fail the sink. Encode the handler output after that boundary so an output `InvalidToolValueException` remains terminal. All other terminal classes keep the current fail path.

- [ ] **Step 4: Run the Step 2 command and require GREEN.**

---

### Task 4: SAA correctable-error loop

**Files:**
- Modify: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaToolCallback.java`
- Modify: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaRuntimeBridge.java`
- Test: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/test/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaAdapterContractTest.java`

**Interfaces:**
- Consumes: Task 1 preflight, `ToolInputException` and shared `ToolErrorResponse`.
- Produces: correctable failures as normal SAA Tool response JSON.

- [ ] **Step 1: Write a failing ChatModel correction-loop test**

The fixture sends invalid arguments, reads `ToolResponseMessage`, sends corrected arguments and finishes. Assert one failed Tool result, later success, final completion and no `RUN_FAILED`. Preserve terminal tests for scopes, budgets, hooks, timeout and infrastructure.

- [ ] **Step 2: Run RED**

~~~bash
./mvnw -q -pl spring-ai-alibaba-adapter -am \
  -Dtest=SpringAiAlibabaAdapterContractTest test
~~~

Expected: callback fails the bridge sink.

- [ ] **Step 3: Implement correction semantics**

In `prepare`, reject trusted spoofing, reserve budget, emit start, validate Schema and `handler.validate`, then evaluate approval. Wrap only these failures and handler-thrown `IllegalArgumentException` as `ToolInputException`; keep output encoding outside that wrapper. Catch only `ToolInputException` in `call`, emit `TOOL_FAILED` and return `ToolErrorResponse.invalidArgument(error).json()` without `failure.accept` or throw. Mark model-visible failed Tool blocks `ERROR`; terminal exceptions retain fail-and-throw.

- [ ] **Step 4: Run the Step 2 command and require GREEN.**

---

### Task 5: SAA endpoint and safe Provider errors

**Files:**
- Modify: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaOpenAiModelFactory.java`
- Modify: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/main/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaAdapter.java`
- Create: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/test/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaOpenAiModelFactoryTest.java`
- Test: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/test/java/happy/jayden/yang/agentbuilder/framework/adapter/springai/SpringAiAlibabaAdapterContractTest.java`

**Interfaces:**
- Produces: package-private `OpenAiEndpoint(String baseUrl, String completionsPath)`.
- Produces: MODEL failures containing safe Provider category and HTTP status only.

- [ ] **Step 1: Write failing endpoint tests**

~~~java
assertEquals(
    new OpenAiEndpoint(
        "https://dashscope.aliyuncs.com",
        "/compatible-mode/v1/chat/completions"),
    endpoint(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1")));
assertEquals(
    new OpenAiEndpoint("https://api.minimaxi.com", "/v1/chat/completions"),
    endpoint(URI.create("https://api.minimaxi.com/v1")));
~~~

Also cover host-only OpenAI, and reject query, fragment, user-info, non-HTTPS and blank host. Add a nested `WebClientResponseException.NotFound` mapper test requiring MODEL + HTTP 404 without response body.

- [ ] **Step 2: Run RED**

~~~bash
./mvnw -q -pl spring-ai-alibaba-adapter -am \
  -Dtest=SpringAiAlibabaOpenAiModelFactoryTest,SpringAiAlibabaAdapterContractTest test
~~~

Expected: no endpoint decomposition and generic framework failure.

- [ ] **Step 3: Implement decomposition and error mapping**

Configure both `baseUrl` and `completionsPath` on `OpenAiApi.Builder`. Preserve origin and existing path prefix, appending `/chat/completions` exactly once when endpoint ends in `/v1`; otherwise use OpenAI default `/v1/chat/completions`. Traverse the full cause chain for Spring HTTP/transport exceptions; expose status/category only, never response body, headers, credential or stack.

- [ ] **Step 4: Run the Step 2 command and require GREEN.**

---

### Task 6: Approval compatibility and proposal UI contract

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntime.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/PublishedAgentPlaygroundRuntimeTest.java`
- Modify: `docs/architecture/openapi/public-v1.yaml`
- Modify: `docs/architecture/openapi/admin-v1.yaml`
- Modify coverage fixtures only if contract lint requires them.
- Regenerate: `frontend/src/api/generated/public.ts`
- Regenerate: `frontend/src/api/generated/admin.ts`
- Modify: `frontend/src/components/AgentRunMessage.tsx`
- Modify: `frontend/src/components/AgentRunMessage.test.tsx`

**Interfaces:**
- Produces: new proposal scope `MULTI_DAY`; frontend continues accepting historic `WEEK`.

- [ ] **Step 1: Write failing Runtime tests**

Assert one day derives `DAY`, three dates derive `MULTI_DAY` and are sorted. Invalid input creates no confirmation; corrected input creates exactly one. Legacy stored input with `scope=WEEK` is cleaned, receives `approvalId` and invokes v2 once.

- [ ] **Step 2: Write failing OpenAPI/frontend tests**

Add `MULTI_DAY` to both proposal enums while retaining `WEEK` for history. Render `MULTI_DAY` as `多个训练日`, `DAY` as `当天` and `WEEK` as `未来 7 天`.

- [ ] **Step 3: Run RED**

~~~bash
./mvnw -q -pl agentbuilder-infrastructure -am \
  -Dtest=PublishedAgentPlaygroundRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix frontend test -- AgentRunMessage.test.tsx
~~~

- [ ] **Step 4: Implement compatibility**

Canonicalize the nested Fitness plan request before freezing: remove model-supplied/legacy `scope`, sort `days` by date, then derive proposal scope from the sorted set. In `approvedArguments`, remove a legacy nested `scope` before injecting `approvalId`. Update OpenAPI sources before generation, then update normalizer/labels. UI never submits plan content.

- [ ] **Step 5: Generate and validate contracts**

~~~bash
node scripts/contracts/lint.mjs
node scripts/contracts/generate-types.mjs
npm --prefix frontend run typecheck
~~~

Inspect generated diffs and require only intended unions.

- [ ] **Step 6: Run the Step 3 commands and require GREEN.**

---

### Task 7: Integrated verification and handoff

**Files:**
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`
- Verify all Task 1-6 changes.

- [ ] **Step 1: Run targeted backend suites serially**

~~~bash
./mvnw -q -pl agentbuilder-infrastructure,agentscope-adapter,spring-ai-alibaba-adapter,starter -am \
  -Dtest=SpringToolCatalogScannerTest,DefaultToolRegistryTest,FitnessToolsTest,FitnessAgentQueryServiceTest,FitnessExperienceIntegrationTest,AgentScopeAdapterContractTest,SpringAiAlibabaAdapterContractTest,SpringAiAlibabaOpenAiModelFactoryTest,PublishedAgentPlaygroundRuntimeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
~~~

- [ ] **Step 2: Run architecture/frontend/contract gates**

~~~bash
./mvnw -q -pl architecture-tests -am test
npm --prefix frontend test -- AgentRunMessage.test.tsx
npm --prefix frontend run typecheck
npm --prefix frontend run lint
node scripts/contracts/lint.mjs
~~~

- [ ] **Step 3: Run formatting/diff gates**

~~~bash
./mvnw -q spotless:apply
./mvnw -q spotless:check
git diff --check
git status --short
~~~

If formatting changes Java, rerun affected suites.

- [ ] **Step 4: Review safety and scope**

Inspect the complete diff. Confirm no Secret, `.env`, credential, response body, migration, dependency, deployment script, generated cache or unrelated file is included. Record exact RED/GREEN commands in `progress.md` and root causes in `findings.md`.

- [ ] **Step 5: Report without commit or deployment**

Report behavior and evidence, plus the need to republish the Agent for the new Tool Schema. Do not stage, commit, publish, deploy or push without explicit authorization.
