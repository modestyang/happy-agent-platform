# Happy Agent Platform Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-grade modular monolith that hosts the AI fitness application and a reusable Agent Builder workbench on one Spring Boot process and one PostgreSQL database, with complete mobile/admin frontends, durable Agent runs, formal testing, review, deployment and acceptance evidence.

**Architecture:** The repository is a Maven/React monorepo. `application/*` owns business capabilities, `agentbuilder/*` owns framework-neutral Agent control-plane contracts and two framework adapters, and `starter` is the only executable module. One PostgreSQL database contains isolated `fitness` and `agent` schemas; business tools are local Spring beans converted by the selected framework adapter. React mobile and admin routes are bundled into the single application JAR and exposed through a small Nginx TLS container.

**Tech Stack:** JDK 17, Spring Boot 3.5.8, AgentScope Java stable line, Spring AI Alibaba 1.1.2.2, Project Reactor, PostgreSQL 16, Flyway, Jackson, Nimbus JOSE JWT, React 19, TypeScript, Vite, TanStack Query, Vitest, Playwright, Docker Compose, Nginx, Alibaba Bailian and OSS.

## Global Constraints

- New code lives only in `/Users/modest/IdeaProjects/happy-agent-platform`; `/Users/modest/IdeaProjects/fitness` is read-only reference material.
- Maven `groupId` and Java package root are exactly `happy.jayden.yang`.
- Root project and private GitHub repository are exactly `happy-agent-platform`.
- Production has one Spring Boot JVM, one PostgreSQL 16 instance/database with `fitness` and `agent` schemas, and one Nginx ingress; Redis, MinIO, message queues and internal Fitness HTTP are forbidden.
- `agentbuilder-framework-adapter` contains `agentscope-adapter` and `spring-ai-alibaba-adapter`; framework-specific types must not escape those modules.
- Tools belong to application infrastructure modules. Agent Builder must never contain `agentbuilder-tool-<application>` modules.
- Tools, Skills, Hooks, Frameworks, Providers, Models, Memory Policies, Prompts, Output Schemas and Evaluation Suites have typed catalogs, complete display/model metadata, immutable versions, defaults, compatibility and usage-impact queries.
- Optional configuration uses versioned defaults and sparse overrides. Published versions store a fully resolved immutable configuration and checksums.
- Main/runtime source sets contain no Fake model, Fake media, mocked success or demo repository. Test doubles may exist only under test source sets.
- External dependency absence produces explicit `DEPENDENCY_NOT_CONFIGURED`/`DEPENDENCY_UNAVAILABLE` states; it never creates fabricated data.
- PostgreSQL is the fact source for sessions, runs, jobs, idempotency and leases. In-memory state may only accelerate reads or hold live SSE connections.
- All writes require authentication, authorization, validation and idempotency; optimistic writes use ETag/If-Match and return 428/412/409 correctly.
- Formal delivery requires TDD evidence, two independent code-review rounds with every Critical/Important finding closed, full automated tests, restart persistence, 2C4G smoke and an acceptance report.
- Production deployment builds on GitHub Actions, never on the 2C4G ECS.

---

## File and module map

```text
application/fitness/fitness-common          domain records, commands and errors
application/fitness/fitness-service         use cases and ports
application/fitness/fitness-infrastructure  JDBC, OSS, schedulers and local Agent Tools
agentbuilder/agentbuilder-core              component contracts, snapshots and runtime SPI
agentbuilder/agentbuilder-service           workbench and run use cases
agentbuilder/agentbuilder-infrastructure    JDBC, encryption and external tool adapters
agentbuilder/agentbuilder-framework-adapter/agentscope-adapter
agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter
starter                                     only Boot app, controllers, security and configuration
frontend                                    one React app with /app and /admin route groups
deploy                                      Compose, Nginx, secrets and deployment scripts
docs                                        product, architecture, API, review and acceptance artifacts
```

---

### Task 1: Repository, build graph and enforceable module boundaries

**Files:**
- Create: `pom.xml`
- Create: `.editorconfig`, `.gitattributes`, `.gitignore`
- Create: `.mvn/wrapper/**`, `mvnw`, `mvnw.cmd`
- Create: parent POMs and child `pom.xml` files under every module in the file map
- Create: `starter/src/main/java/happy/jayden/yang/StarterApplication.java`
- Create: `architecture-tests/src/test/java/happy/jayden/yang/architecture/ModuleBoundaryTest.java`
- Create: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/vite.config.ts`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces Maven coordinates for every later task and the only `@SpringBootApplication` class.
- Produces a React/Vite test/build entry point; no product page is introduced yet.

- [ ] **Step 1: Write the architecture RED test**

```java
@AnalyzeClasses(packages = "happy.jayden.yang")
class ModuleBoundaryTest {
  @ArchTest static final ArchRule agentCoreIsApplicationAgnostic =
      noClasses().that().resideInAPackage("..agentbuilder.core..")
          .should().dependOnClassesThat().resideInAPackage("..application..");

  @ArchTest static final ArchRule onlyStarterBoots =
      classes().that().areAnnotatedWith(SpringBootApplication.class)
          .should().resideInAPackage("happy.jayden.yang");
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl architecture-tests -am test`  
Expected: FAIL because the Maven reactor and packages do not exist.

- [ ] **Step 3: Create the nested Maven reactor and minimal Boot class**

The root POM lists only nested parent modules, uses Java 17, imports Spring Boot 3.5.8, pins all dependency/plugin versions and configures Surefire, JaCoCo and Spotless. Library modules produce plain JARs; only `starter` applies Spring Boot repackage.

- [ ] **Step 4: Add frontend build/test scripts and a CI compile gate**

`package.json` must expose `test`, `build`, `lint`, `typecheck` and `e2e`. CI initially runs Maven compilation plus frontend typecheck; later tasks extend the workflow.

- [ ] **Step 5: Run GREEN**

Run: `./mvnw -q clean verify && npm --prefix frontend ci && npm --prefix frontend run typecheck`  
Expected: all modules build, boundary tests pass and only `starter` is executable.

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "build: create modular monolith foundation"
```

### Task 2: Product, architecture and API contracts as executable sources of truth

**Files:**
- Create: `docs/product/product-design.md`
- Create: `docs/product/feature-checklist.md`
- Create: `docs/architecture/module-boundaries.md`
- Create: `docs/architecture/data-model.md`
- Create: `docs/architecture/openapi/public-v1.yaml`
- Create: `docs/architecture/openapi/admin-v1.yaml`
- Create: `scripts/contracts/lint.mjs`
- Create: `scripts/contracts/generate-types.mjs`
- Create: `frontend/src/api/generated/public.ts`, `frontend/src/api/generated/admin.ts`
- Test: `scripts/contracts/fixtures/*.json`

**Interfaces:**
- Produces all HTTP DTOs and status/error contracts consumed by backend controllers and frontend repositories.
- Freezes the full approved fitness and Agent workbench scope; no implementation task may invent an endpoint outside these files.

- [ ] **Step 1: Write RED contract fixtures**

Fixtures must cover the 2×2 home dashboard, preferences, goals, workout/exercise details, manual records, daily meals and feedback, recognition jobs, current-goal report states, AI sessions/SSE, typed component catalogs, defaults, draft evaluation/publish and run traces.

- [ ] **Step 2: Run RED**

Run: `node scripts/contracts/lint.mjs`  
Expected: FAIL because the OpenAPI files and schemas do not exist.

- [ ] **Step 3: Write closed OpenAPI schemas and the error/header matrix**

Every write documents `Idempotency-Key`; every optimistic update documents `If-Match`, 428 and 412; asynchronous creation documents 202 plus `Location`; Problem Details has closed codes including `DEPENDENCY_NOT_CONFIGURED`, `CURRENT_GOAL_NOT_FOUND`, `SAFETY_GATE_FAILED` and `FRAMEWORK_NOT_SUPPORTED`.

- [ ] **Step 4: Generate TypeScript types and make fixture validation GREEN**

Run: `node scripts/contracts/lint.mjs && node scripts/contracts/generate-types.mjs && git diff --exit-code frontend/src/api/generated`  
Expected: fixtures validate and generated types are reproducible.

- [ ] **Step 5: Commit**

```bash
git add docs scripts frontend/src/api/generated
git commit -m "docs: freeze product and API contracts"
```

### Task 3: One PostgreSQL database, two schemas and restart-safe infrastructure

**Files:**
- Create: `deploy/docker-compose.yml`
- Create: `deploy/postgres/init.sql`
- Create: `starter/src/main/java/happy/jayden/yang/config/FitnessDataSourceConfig.java`
- Create: `starter/src/main/java/happy/jayden/yang/config/AgentDataSourceConfig.java`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V1__fitness_baseline.sql`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql`
- Create: `starter/src/test/java/happy/jayden/yang/config/DualSchemaIntegrationTest.java`
- Create: `deploy/scripts/export-database.sh`

**Interfaces:**
- Produces qualified `fitnessDataSource`/`fitnessTransactionManager` and `agentDataSource`/`agentTransactionManager` beans.
- Produces schema-local Flyway histories and a PostgreSQL lease/idempotency foundation.

- [ ] **Step 1: Write the PostgreSQL RED test**

```java
@Testcontainers
class DualSchemaIntegrationTest {
  @Test void rolesCannotReadTheOtherSchema() {
    assertThatThrownBy(() -> fitnessJdbc.queryForObject("select count(*) from agent.agent_versions", Long.class))
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> agentJdbc.queryForObject("select count(*) from fitness.users", Long.class))
        .hasMessageContaining("permission denied");
  }
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl starter -am -Dtest=DualSchemaIntegrationTest test`  
Expected: FAIL because schemas, roles and data sources do not exist.

- [ ] **Step 3: Implement one database with two roles, pools and Flyway instances**

Use `minimumIdle=0`, `maximumPoolSize=3`; set schema search paths explicitly; use two different Flyway history tables; disable `clean` in production. Do not create cross-schema foreign keys.

- [ ] **Step 4: Verify persistence across container recreation**

Run a test script that inserts one row per schema, runs `docker compose restart postgres`, then recreates the container without `-v` and verifies both rows remain under `/opt/happy-agent/data/postgres` or the local test bind mount.

- [ ] **Step 5: Run GREEN and Commit**

Run: `./mvnw -q -pl starter -am test && docker compose -f deploy/docker-compose.yml config`  
Expected: migrations, isolation and restart checks pass.

```bash
git add application agentbuilder starter deploy
git commit -m "feat: add isolated schemas on one postgres"
```

### Task 4: Framework-neutral component model, defaults and immutable snapshots

**Files:**
- Create: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/component/**`
- Create: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/defaults/**`
- Create: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/version/AgentVersionSnapshot.java`
- Test: `agentbuilder/agentbuilder-core/src/test/java/happy/jayden/yang/agentbuilder/core/**`

**Interfaces:**
- Produces `ComponentKey`, `ComponentVersion`, `ComponentStatus`, `FrameworkRef`, `ProviderRef`, `ModelBinding`, `ToolBinding`, `SkillBinding`, `HookBinding`, `MemoryPolicyRef`, `PromptRef`, `OutputSchemaRef`, `EvaluationSuiteRef`.
- Produces `EffectiveConfigResolver.resolve(PlatformLimits, ComponentDefaults, ApplicationDefaults, AgentOverrides): ResolvedAgentConfig`.

- [ ] **Step 1: Write RED sealed-model tests**

```java
@Test void sparseOverridesResolveAndPublishedSnapshotDoesNotFollowLaterDefaults() {
  var resolved = resolver.resolve(limits, componentDefaults, appDefaults, overridesWithOnlyTemperature());
  assertThat(resolved.timeout()).isEqualTo(Duration.ofSeconds(30));
  assertThat(resolved.temperature()).isEqualTo(new BigDecimal("0.6"));
  var snapshot = AgentVersionSnapshot.publish(resolved, components);
  appDefaults.changeTimeout(Duration.ofSeconds(60));
  assertThat(snapshot.resolvedConfig().timeout()).isEqualTo(Duration.ofSeconds(30));
}
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl agentbuilder-core -Dtest='*Component*Test,*EffectiveConfig*Test' test`  
Expected: FAIL because component/default types do not exist.

- [ ] **Step 3: Implement typed components, no EAV aggregate**

Every component has a typed record plus common read-model metadata. `null` never means two things: absent override uses `Optional`, explicit reset is a separate command. Hard security limits are not overridable.

- [ ] **Step 4: Implement canonical JSON and SHA-256 checksum**

Sort maps and bindings deterministically. Snapshot checksum includes framework/adapter, provider/model, prompt, tools, skills, hooks, memory, output schema, evaluation suite, defaults version and resolved limits.

- [ ] **Step 5: Run GREEN and Commit**

Run: `./mvnw -q -pl agentbuilder-core test`  
Expected: component validation, default provenance and immutability tests pass.

```bash
git add agentbuilder/agentbuilder-core
git commit -m "feat: define versioned agent component model"
```

### Task 5: Code-discovered Tool Catalog with complete model and admin metadata

**Files:**
- Create: `agentbuilder/agentbuilder-core/src/main/java/happy/jayden/yang/agentbuilder/core/tool/AgentTool.java`
- Create: `.../AgentToolParam.java`, `ToolDescriptor.java`, `AgentToolHandler.java`, `AgentToolContributor.java`, `ToolExecutionContext.java`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/tool/SpringToolCatalogScanner.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/.../SpringToolCatalogScannerTest.java`

**Interfaces:**
- Produces `ToolDescriptor` with key/version/runtimeName/displayName/description/usage rules/tags/input-output schemas/risk/scopes/timeouts/idempotency/source/checksum/lifecycle.
- Produces `ToolRegistry.resolve(List<ToolBinding>): ResolvedToolSet`.

- [ ] **Step 1: Write a RED metadata completeness test**

```java
@AgentTool(key="fitness.query_workout_history", version=1,
 displayName="查询训练历史", description="查询当前用户已完成的训练记录",
 whenToUse="生成训练分析时", whenNotToUse="修改训练计划时",
 sideEffect=READ_ONLY, idempotent=true, risk=LOW)
ToolResult history(@AgentToolParam(name="request", description="查询范围") HistoryRequest request,
                   ToolExecutionContext context) { return ToolResult.empty(); }

assertThat(scanner.scan(bean).inputSchema()).contains("description", "required");
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl agentbuilder-infrastructure -am -Dtest=SpringToolCatalogScannerTest test`  
Expected: FAIL because scanner and annotations do not exist.

- [ ] **Step 3: Implement annotation scanning and strict schema generation**

Reject missing display/model descriptions, duplicate runtime names, invalid parameter descriptions and checksum drift without a contract-version increment. Tool context parameters are excluded from model-visible schema.

- [ ] **Step 4: Implement lifecycle/impact and deploy manifest rules**

Persist metadata only, never bytecode. A build manifest lists available `(toolKey, contractVersion, checksum)` tuples; deployment preflight fails if a published Agent references an absent tuple.

- [ ] **Step 5: Run GREEN and Commit**

Run: `./mvnw -q -pl agentbuilder-infrastructure -am test`  
Expected: scanner, strict schema, duplicate, old-version and manifest tests pass.

```bash
git add agentbuilder
git commit -m "feat: add complete tool catalog"
```

### Task 6: Skills, Hooks, Providers, Models, memory, prompts and evaluation catalogs

**Files:**
- Create typed packages under `agentbuilder/agentbuilder-core/src/main/java/.../component/{skill,hook,provider,model,memory,prompt,output,evaluation}`
- Create use cases under `agentbuilder/agentbuilder-service/src/main/java/.../catalog/**`
- Test corresponding core/service tests.

**Interfaces:**
- Produces `SkillDefinition`, `HookDefinition`, `ProviderVersion`, `ModelDefinition`, `MemoryPolicyVersion`, `PromptVersion`, `OutputSchemaVersion`, `EvaluationSuiteVersion`.
- Produces `CompatibilityValidator.validate(AgentDraft): ValidationReport` and `ImpactQuery.affectedBy(ComponentRef)`.

- [ ] **Step 1: Write RED compatibility tests**

Tests prove: a Skill missing a required Tool cannot publish; a mandatory security Hook cannot be disabled; a model without tool support cannot publish an Agent with Tools; an adapter without Skill capability cannot accept Skills; secrets never appear in provider responses.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl agentbuilder-service -am -Dtest='*Catalog*Test,*Compatibility*Test' test`  
Expected: FAIL because the typed catalogs do not exist.

- [ ] **Step 3: Implement typed catalogs and defaults**

Skills store versioned Markdown/resources/checksums and progressive disclosure metadata; V1 rejects executable Java/Python/Shell content. Hooks declare phases/order/config/failure policy and mandatory flags. Providers use immutable encrypted credential versions and masked responses. Models declare modalities, context and feature capabilities.

- [ ] **Step 4: Implement draft binding and effective-config preview**

The admin service returns both sparse overrides and `ResolvedAgentConfig` with field-level provenance. Default-profile changes create a new profile version and never mutate published snapshots.

- [ ] **Step 5: Run GREEN and Commit**

Run: `./mvnw -q -pl agentbuilder-service -am test`  
Expected: all component, compatibility, secret redaction, default and impact tests pass.

```bash
git add agentbuilder
git commit -m "feat: add typed agent component catalogs"
```

### Task 7: JDBC component repositories, encrypted credentials and versioned default profiles

**Files:**
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V2__component_catalogs.sql`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/catalog/**`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/security/AesGcmCredentialCipher.java`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/.../{CatalogRepositoryIntegrationTest,AesGcmCredentialCipherTest}.java`

**Interfaces:**
- Implements every typed repository port introduced by Tasks 4–6 without an EAV component table.
- Produces `CredentialCipher.encrypt(char[]): EncryptedSecret` and `DefaultProfileRepository.findActive(ApplicationKey): DefaultProfileVersion`.

- [ ] **Step 1: Write RED repository and encryption tests**

Insert two versions of a Prompt and Default Profile, assert immutable history and active-version lookup; persist a Provider credential and assert neither JDBC reads nor JSON serialization contain plaintext. Assert optimistic updates reject a stale revision.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl agentbuilder-infrastructure -am -Dtest='*CatalogRepositoryIntegrationTest,AesGcmCredentialCipherTest' test`  
Expected: FAIL because migrations and adapters do not exist.

- [ ] **Step 3: Add typed schema tables and JDBC adapters**

Use one table per aggregate family, JSONB only for each type's validated config/schema payload, `(component_key, version)` unique keys, `revision` optimistic locks, lifecycle constraints and indexes for application/status/tag. AES-256-GCM uses a master key supplied only by `HAPPY_AGENT_MASTER_KEY_FILE`; each ciphertext has a random 96-bit IV and authenticated component/version metadata.

- [ ] **Step 4: Run GREEN and Commit**

Run: `./mvnw -q -pl agentbuilder-infrastructure -am test`  
Expected: repository, concurrency, encryption, redaction and migration tests pass.

```bash
git add agentbuilder
git commit -m "feat: persist versioned agent catalogs securely"
```

### Task 8: Framework runtime SPI and AgentScope adapter

**Files:**
- Create: `agentbuilder/agentbuilder-core/src/main/java/.../runtime/{AgentFrameworkAdapter,FrameworkCapabilities,RunRequest,RunEvent,RunResult}.java`
- Create: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/main/java/.../agentscope/**`
- Test: `agentbuilder/agentbuilder-framework-adapter/agentscope-adapter/src/test/java/.../AgentScopeAdapterContractTest.java`

**Interfaces:**
- Produces `AgentFrameworkAdapter.key()`, `capabilities()`, `validate(ResolvedAgentConfig)` and `run(RunRequest): Flux<RunEvent>`.
- Converts framework-neutral Tool/Skill/Hook/Memory contracts to AgentScope Toolkit, SkillBox and runtime events; AgentScope classes remain package-private to this module.

- [ ] **Step 1: Write the RED adapter contract**

The contract test supplies one tool, one Markdown Skill, mandatory Hooks and a scripted model transport. It asserts ordered `RUN_STARTED`, model delta, tool started/result and `RUN_COMPLETED` events; trusted `ToolExecutionContext` is not present in model arguments; cancellation closes the framework run.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl agentscope-adapter -am -Dtest=AgentScopeAdapterContractTest test`  
Expected: FAIL because SPI and adapter do not exist.

- [ ] **Step 3: Implement the AgentScope conversion boundary**

Pin an officially released AgentScope Java version after resolving it from Maven Central. Adapt Bailian through its OpenAI-compatible endpoint, translate Tool schemas strictly, load Skills progressively, execute Hooks in deterministic order and map errors to neutral `RunFailureCode` values.

- [ ] **Step 4: Run GREEN and boundary checks**

Run: `./mvnw -q -pl agentscope-adapter -am test && ./mvnw -q -pl architecture-tests -am test`  
Expected: the shared contract passes and no AgentScope type escapes the adapter.

- [ ] **Step 5: Commit**

```bash
git add agentbuilder architecture-tests
git commit -m "feat: add agentscope runtime adapter"
```

### Task 9: Spring AI Alibaba adapter with framework-parity tests

**Files:**
- Create: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/main/java/.../springai/**`
- Create: `agentbuilder/agentbuilder-core/src/testFixtures/java/.../runtime/FrameworkAdapterContract.java`
- Test: `agentbuilder/agentbuilder-framework-adapter/spring-ai-alibaba-adapter/src/test/java/.../SpringAiAlibabaAdapterContractTest.java`
- Modify: both adapter POMs to consume the shared contract fixture.

**Interfaces:**
- Implements the exact `AgentFrameworkAdapter` SPI from Task 8 through ReactAgent, ToolCallback, ToolContext, SkillsAgentHook and neutral RunEvents.
- Produces a shared conformance report for both registered adapters.

- [ ] **Step 1: Extract and run the RED parity contract**

The fixture asserts capability declaration, strict tool schema, Skill activation, mandatory Hook ordering, streaming event ordering, structured-output validation, cancellation and failure normalization. Run it against an empty SAA adapter and confirm failure.

- [ ] **Step 2: Implement SAA conversions without leaking framework types**

Use Spring AI Alibaba 1.1.2.2. Bind tools as ToolCallbacks, trusted values through ToolContext, Skills through SkillsAgentHook, and reject unsupported capability combinations during publish validation rather than mid-run.

- [ ] **Step 3: Run both adapters and Commit**

Run: `./mvnw -q -pl agentscope-adapter,spring-ai-alibaba-adapter -am test`  
Expected: the same framework-neutral contract passes for both adapters.

```bash
git add agentbuilder
git commit -m "feat: add spring ai alibaba adapter parity"
```

### Task 10: Durable Agent sessions, runs, checkpoints, SSE and recovery

**Files:**
- Create: `agentbuilder/agentbuilder-service/src/main/java/.../run/**`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V3__sessions_runs.sql`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/.../run/**`
- Test: `agentbuilder/agentbuilder-infrastructure/src/test/java/.../{RunLifecycleIntegrationTest,RunRecoveryIntegrationTest}.java`

**Interfaces:**
- Produces `RunService.start(StartRunCommand): RunHandle`, `cancel(RunId)`, `approveTool(ApprovalCommand)` and `events(RunId, afterSequence): Flux<PersistedRunEvent>`.
- Sessions expire after 24 hours of inactivity by default; history remains queryable by the Agent platform even when the fitness UI hides it.

- [ ] **Step 1: Write RED lifecycle and crash-recovery tests**

Assert idempotent start creates one run, every event has an increasing sequence, SSE resumes after `Last-Event-ID`, concurrent run limit is two, approval pauses and resumes, cancel is durable, expired sessions are closed, and a simulated JVM restart marks abandoned non-resumable runs failed while resumable checkpoints continue once.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl agentbuilder-infrastructure -am -Dtest='Run*IntegrationTest' test`  
Expected: FAIL because run tables and services do not exist.

- [ ] **Step 3: Implement PostgreSQL-owned state machines**

Use explicit session/run/event/checkpoint/approval/idempotency/lease tables, transactionally append events, `SELECT ... FOR UPDATE SKIP LOCKED` leases, heartbeat ownership and monotonic sequences. Live Reactor sinks only fan out already-persisted events.

- [ ] **Step 4: Run GREEN and Commit**

Run: `./mvnw -q -pl agentbuilder-infrastructure -am test`  
Expected: lifecycle, reconnect, concurrency, approval, expiry and restart cases pass.

```bash
git add agentbuilder
git commit -m "feat: add durable agent run lifecycle"
```

### Task 11: Agent workbench use cases, evaluation, publish and rollback

**Files:**
- Create: `agentbuilder/agentbuilder-service/src/main/java/.../workbench/**`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V4__agents_evaluations.sql`
- Test: `agentbuilder/agentbuilder-service/src/test/java/.../{AgentDraftServiceTest,PublishWorkflowTest,EvaluationServiceTest}.java`

**Interfaces:**
- Produces draft create/update/preview/evaluate/publish/rollback use cases and immutable `AgentVersionSnapshot` persistence.
- Publish consumes a successful Evaluation Suite result, complete compatibility report and build Tool manifest.

- [ ] **Step 1: Write RED workflow tests**

Assert a stale ETag fails, invalid component compatibility blocks evaluation, evaluation records dataset/case/model/latency/cost/result evidence, publish is impossible before a required suite passes, published snapshots cannot mutate, rollback changes only the active pointer, and framework changes require a new draft.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl agentbuilder-service -am -Dtest='*DraftServiceTest,*PublishWorkflowTest,*EvaluationServiceTest' test`  
Expected: FAIL because workbench use cases do not exist.

- [ ] **Step 3: Implement the formal release state machine**

States are `DRAFT → VALIDATED → EVALUATED → PUBLISHED → SUPERSEDED`; failure returns a structured report without advancing state. Publish stores resolved values, provenance, component checksums, adapter build and evaluation evidence in one transaction.

- [ ] **Step 4: Run GREEN and Commit**

Run: `./mvnw -q -pl agentbuilder-service -am test`  
Expected: all release, concurrency and immutability tests pass.

```bash
git add agentbuilder
git commit -m "feat: add governed agent publishing workflow"
```

### Task 12: Fitness domain, authentication and durable user-owned records

**Files:**
- Create: `application/fitness/fitness-common/src/main/java/.../fitness/domain/**`
- Create: `application/fitness/fitness-service/src/main/java/.../fitness/{auth,goal,body,meal,workout,exercise,preference}/**`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V2__fitness_domain.sql`
- Create: `application/fitness/fitness-infrastructure/src/main/java/.../fitness/persistence/**`
- Test: service unit tests and `FitnessRepositoryIntegrationTest.java`.

**Interfaces:**
- Produces use cases for users/preferences, sequential goals, body measurements/photos, meal records/feedback, plans/workouts/completions/manual exercises and exercise-library details.
- Body, meal and workout facts always reference the user; goal association is optional and never controls their lifetime.

- [ ] **Step 1: Write RED domain invariants**

Assert only one active goal, a completed/expired goal remains immutable and a new goal may start; body record requires weight or waist; meal recognition results remain editable; a workout action replacement must reference the action library and only replaces one item; four ordered exercise images are required for a published exercise; all write commands reject a mismatched user.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl fitness-service,fitness-infrastructure -am test`  
Expected: FAIL because domain and repositories do not exist.

- [ ] **Step 3: Implement domain-first services and JDBC ports**

Store weight as kilograms and waist as centimeters using fixed-scale decimals; timestamps are UTC and presentation uses user timezone. Passwords use Argon2id. Histories are append-only with correction metadata rather than destructive overwrite.

- [ ] **Step 4: Run GREEN and Commit**

Run: `./mvnw -q -pl fitness-infrastructure -am test`  
Expected: all invariants, isolation, persistence and optimistic concurrency tests pass.

```bash
git add application
git commit -m "feat: add durable fitness domain"
```

### Task 13: Fitness Agent jobs for meals, recognition and current-goal report

**Files:**
- Create: `application/fitness/fitness-service/src/main/java/.../fitness/orchestration/**`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V3__agent_jobs.sql`
- Create: `application/fitness/fitness-infrastructure/src/main/java/.../fitness/scheduler/**`
- Test: `DailyMealGenerationIntegrationTest.java`, `FoodRecognitionIntegrationTest.java`, `GoalReportIntegrationTest.java`.

**Interfaces:**
- Produces durable job commands and status queries for daily meal generation, food-photo recognition and one current-goal cumulative report.
- Invokes a published Agent through `AgentRunPort`; application services never import adapter-specific types.

- [ ] **Step 1: Write RED job tests**

At 05:30 user-local time, one lease gathers the previous six days plus preferences/body facts and generates breakfast/lunch/dinner once. A missing plan exposes a manual trigger. Recognition starts after upload and updates a closed editable food result. Report remains `HOLDING` until AI conclusion/highlights/weaknesses/actions and deterministic metrics/charts are all persisted. Unconfigured/unavailable dependencies produce explicit states and no fabricated content.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl fitness-infrastructure -am -Dtest='*MealGenerationIntegrationTest,*RecognitionIntegrationTest,*GoalReportIntegrationTest' test`  
Expected: FAIL because jobs, leases and result models do not exist.

- [ ] **Step 3: Implement leased orchestration and checksums**

Persist input windows, prompt/model/Agent version, source-data checksum, attempts and terminal result. Limit concurrent Agent runs to two, recognition to one and background workers to two. Re-run only when explicitly requested or source checksum changes under the use-case rule.

- [ ] **Step 4: Run GREEN and Commit**

Run: `./mvnw -q -pl fitness-infrastructure -am test`  
Expected: schedules, leases, idempotency, data provenance and failure-state tests pass.

```bash
git add application
git commit -m "feat: orchestrate fitness agent jobs durably"
```

### Task 14: Fitness local Agent Tools and trusted execution context

**Files:**
- Create: `application/fitness/fitness-infrastructure/src/main/java/.../fitness/agent/FitnessToolContributor.java`
- Create: `application/fitness/fitness-infrastructure/src/main/java/.../fitness/agent/FitnessTools.java`
- Test: `application/fitness/fitness-infrastructure/src/test/java/.../fitness/agent/FitnessToolsTest.java`

**Interfaces:**
- Registers read tools for user profile/preferences/current goal/body/meal/workout history and guarded command tools for plans, meals, goals and reports.
- Calls fitness application services in-process; no HTTP, bearer token or `agentbuilder-tool-fitness` module exists.

- [ ] **Step 1: Write RED Tool safety tests**

Assert complete descriptor metadata, strict schemas, user ID exclusion from model-visible parameters, context-scoped reads, idempotent operation IDs, risk/approval flags on writes and refusal when requested data is outside the trusted user's scope.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl fitness-infrastructure -am -Dtest=FitnessToolsTest test`  
Expected: FAIL because contributors do not exist.

- [ ] **Step 3: Implement local annotated Tools and register manifests**

Use the Task 5 annotations and return compact structured results with source timestamps. Route every write through the same command services and authorization rules used by HTTP controllers.

- [ ] **Step 4: Run GREEN and Commit**

Run: `./mvnw -q -pl fitness-infrastructure -am test`  
Expected: catalog, scoping, authorization and application-service delegation tests pass.

```bash
git add application
git commit -m "feat: expose fitness capabilities as local tools"
```

### Task 15: OSS media storage, lifecycle and production seed assets

**Files:**
- Create: `application/fitness/fitness-service/src/main/java/.../fitness/media/**`
- Create: `application/fitness/fitness-infrastructure/src/main/java/.../fitness/media/AliyunOssMediaStore.java`
- Create: `application/fitness/fitness-infrastructure/src/main/resources/db/fitness/V4__media.sql`
- Create: `deploy/seed/media/**` and `deploy/scripts/upload-seed-media.sh`
- Test: `MediaLifecycleIntegrationTest.java`.

**Interfaces:**
- Produces signed upload intent, upload confirmation, private download URL and orphan cleanup use cases.
- Stores OSS object keys and metadata only; database never stores image blobs or public permanent URLs.

- [ ] **Step 1: Write RED lifecycle tests**

Assert MIME/size/owner/purpose constraints, confirmation requires an OSS HEAD match, recognition cannot read unconfirmed media, signed downloads expire, deleted records create cleanup work, and missing OSS configuration returns `DEPENDENCY_NOT_CONFIGURED`.

- [ ] **Step 2: Run RED and implement the OSS adapter**

Run: `./mvnw -q -pl fitness-infrastructure -am -Dtest=MediaLifecycleIntegrationTest test` and confirm missing adapter failure. Implement time-limited pre-signed PUT/GET, deterministic object prefixes and retryable cleanup records.

- [ ] **Step 3: Add licensed/original four-step seed assets and manifest**

The seed manifest records exercise key, exactly four ordered object keys, dimensions, SHA-256 and license/source. Upload is explicit and idempotent; no runtime placeholder images are allowed.

- [ ] **Step 4: Run GREEN and Commit**

Run: `./mvnw -q -pl fitness-infrastructure -am test && bash -n deploy/scripts/upload-seed-media.sh`  
Expected: lifecycle and script validation pass.

```bash
git add application deploy
git commit -m "feat: add private oss media lifecycle"
```

### Task 16: Public/admin REST APIs, security, idempotency and database seed profile

**Files:**
- Create: `starter/src/main/java/.../{controller,security,web}/**`
- Create: `starter/src/main/resources/application.yml`, `application-local.yml`, `application-prod.yml`
- Create: `starter/src/main/resources/db/seed/R__local_test_data.sql`
- Test: `starter/src/test/java/.../{PublicApiContractTest,AdminApiContractTest,SecurityIntegrationTest,IdempotencyIntegrationTest}.java`

**Interfaces:**
- Implements both OpenAPI files from Task 2 without response-shape divergence.
- Produces short-lived access JWTs, rotating refresh sessions, RBAC (`USER`, `AGENT_ADMIN`) and RFC 9457 Problem Details.

- [ ] **Step 1: Write RED generated-contract tests**

For every operation, assert route/method/content type/schema/status headers. Assert unauthenticated writes are 401, wrong role is 403, duplicate idempotency keys replay the original result, a changed payload conflicts, missing `If-Match` is 428 and stale revision is 412. Admin secrets are write-only and masked.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl starter -am -Dtest='*ApiContractTest,*SecurityIntegrationTest,*IdempotencyIntegrationTest' test`  
Expected: FAIL because controllers and filters do not exist.

- [ ] **Step 3: Implement controllers as thin use-case adapters**

Generate JWT signing material through `deploy/scripts/generate-secrets.sh`; production reads secret files, never committed values. Rate-limit login and Agent start per account without Redis using PostgreSQL counters. Seed data is enabled only by the `local-seed` profile and includes one user, one admin, preferences, sequential goal history, 8 weeks of measurements/workouts/meals, exercise catalog/images, component catalogs and one published fitness Agent.

- [ ] **Step 4: Run GREEN and Commit**

Run: `./mvnw -q -pl starter -am verify`  
Expected: generated schema, security, idempotency and seed-profile tests pass.

```bash
git add starter deploy
git commit -m "feat: expose secured public and admin APIs"
```

### Task 17: Production mobile web application

**Files:**
- Create: `frontend/src/app/**`, `frontend/src/components/mobile/**`, `frontend/src/api/publicRepository.ts`
- Create: `frontend/src/styles/{tokens.css,mobile.css}`
- Test: `frontend/src/app/**/*.test.tsx`, `frontend/e2e/mobile.spec.ts`.

**Interfaces:**
- Consumes only generated public API types and `publicRepository`; components never import mock data.
- Produces responsive `/app/**` routes for 390–430 px primary width with accessible desktop fallback.

- [ ] **Step 1: Write RED component and Playwright journeys**

Cover login; warm minimal home with legible active-goal card and 2×2 actions; time-aware meal card with generate/like/dislike reason; one-level record drawer for body/food and photo recognition lock; calendar/manual workout/action replacement; exercise library/detail with four ordered images, always-visible steps and errors; speech timer pause/resume/persist/auto-check; current-goal-only holding/report conclusion→evidence→action; AI first-entry shortcuts, bottom-fixed input, active-session restoration; simplified profile/history/preferences/style.

- [ ] **Step 2: Run RED**

Run: `npm --prefix frontend test -- --run && npm --prefix frontend run e2e -- --project=mobile`  
Expected: FAIL because routes and components do not exist.

- [ ] **Step 3: Implement a human-designed warm visual system**

Use cream background, black typography, restrained orange/lilac/blue/green blocks, solid cards, generous rhythm and rounded geometry derived from the approved references. Avoid glassmorphism, neon gradients, excessive badges, generic AI sparkle copy and dashboard density. Use system Chinese fonts, minimum 16px body text, 44px touch targets, focus states, reduced-motion support and safe-area insets.

- [ ] **Step 4: Implement real repository states**

Every screen handles loading, empty, unavailable, recoverable error and success. Service worker/offline UI may cache static assets but never fabricates successful writes. Timer state uses local persistence only as a recovery aid and reconciles completion with the server.

- [ ] **Step 5: Run GREEN, visual snapshots and Commit**

Run: `npm --prefix frontend run lint && npm --prefix frontend run typecheck && npm --prefix frontend test -- --run && npm --prefix frontend run e2e -- --project=mobile`  
Expected: all journeys and 390/430 px snapshots pass without horizontal overflow or accessibility violations.

```bash
git add frontend
git commit -m "feat: build production fitness mobile app"
```

### Task 18: Production Agent Builder admin workbench

**Files:**
- Create: `frontend/src/admin/**`, `frontend/src/components/admin/**`, `frontend/src/api/adminRepository.ts`
- Create: `frontend/src/styles/admin.css`
- Test: `frontend/src/admin/**/*.test.tsx`, `frontend/e2e/admin.spec.ts`.

**Interfaces:**
- Consumes only generated admin API types.
- Produces `/admin/**` catalogs, defaults, providers/models, Agent editing, evaluation, publish, versions, sessions/runs/traces and playground.

- [ ] **Step 1: Write RED workbench journeys**

Assert admin can search/view typed Tools/Skills/Hooks/Frameworks/Providers/Models/Memory/Prompts/Outputs/Evaluations; inspect schema/risk/version/impact; edit sparse defaults with provenance/reset; add and probe a masked Bailian credential; create a draft in Basic mode; expand Advanced bindings; preview resolved config; evaluate; publish; rollback; inspect streaming run trace and approvals. Failed dependencies and validation remain visible and never look successful.

- [ ] **Step 2: Run RED**

Run: `npm --prefix frontend test -- --run && npm --prefix frontend run e2e -- --project=admin`  
Expected: FAIL because admin routes do not exist.

- [ ] **Step 3: Implement a calm desktop information architecture**

Use a narrow icon/text sidebar, 12-column content grid, off-white canvas, white cards, navy text and sparse coral/blue semantic accents based on the approved reference. Basic mode shows only name/framework/provider/model/prompt/tools/skills; optional properties inherit defaults and Advanced sections remain collapsed.

- [ ] **Step 4: Run GREEN, visual snapshots and Commit**

Run: `npm --prefix frontend run lint && npm --prefix frontend run typecheck && npm --prefix frontend test -- --run && npm --prefix frontend run e2e -- --project=admin`  
Expected: desktop 1280/1440 px journeys, keyboard navigation and accessibility snapshots pass.

```bash
git add frontend
git commit -m "feat: build production agent workbench"
```

### Task 19: Single-JAR packaging and 2C4G deployment pipeline

**Files:**
- Modify: `frontend/vite.config.ts`, `starter/pom.xml`, `.github/workflows/ci.yml`
- Create: `.github/workflows/deploy.yml`
- Create: `starter/src/main/java/.../web/SpaFallbackController.java`
- Create: `deploy/{Dockerfile,nginx/nginx.conf,env/.env.example}`
- Create: `deploy/scripts/{generate-secrets.sh,deploy-release.sh,preflight.sh,rollback.sh}`
- Test: `starter/src/test/java/.../web/SpaPackagingIntegrationTest.java`, `deploy/tests/deployment.bats`.

**Interfaces:**
- Produces one immutable application image/JAR containing `/app` and `/admin` assets plus PostgreSQL/Nginx Compose deployment.
- GitHub Actions builds and signs artifacts; ECS only pulls, migrates and starts a selected release.

- [ ] **Step 1: Write RED packaging and deployment tests**

Assert deep links return the SPA, API paths never fall through, secrets files are required and permission-checked, migration/preflight occurs before traffic switch, failed health restores the previous image, Tool manifest compatibility blocks unsafe deploy, and database volumes map explicitly to `/opt/happy-agent/data/postgres`.

- [ ] **Step 2: Run RED**

Run: `./mvnw -q -pl starter -am -Dtest=SpaPackagingIntegrationTest test && bats deploy/tests/deployment.bats`  
Expected: FAIL because packaging and scripts do not exist.

- [ ] **Step 3: Implement resource-bounded production files**

Application uses `-Xms256m -Xmx1300m -XX:MaxMetaspaceSize=256m -Xss512k`; PostgreSQL limit is 768 MiB; Nginx target is 64 MiB. Logs bind to `/opt/happy-agent/logs` with rotation. Do not configure Redis, monitoring alerts, automated backup, Maven or Node on ECS.

- [ ] **Step 4: Extend CI and manual deploy**

CI runs backend/frontend/contracts/E2E/image scans and uploads the JAR/image digest/SBOM/test reports. `workflow_dispatch` deploy accepts environment and immutable image digest, connects with GitHub environment secrets and executes preflight/health/rollback.

- [ ] **Step 5: Run GREEN and Commit**

Run: `./mvnw -q clean verify && npm --prefix frontend run build && docker compose -f deploy/docker-compose.yml config && bats deploy/tests/deployment.bats`  
Expected: SPA packaging, Compose schema and deployment lifecycle tests pass.

```bash
git add .github deploy frontend starter
git commit -m "build: package and deploy single-node platform"
```

### Task 20: Full-system verification, restart persistence and formal test data

**Files:**
- Create: `tests/system/**`, `tests/performance/k6-smoke.js`, `tests/security/**`
- Create: `scripts/acceptance/run-all.sh`
- Create: `docs/testing/test-matrix.md`

**Interfaces:**
- Produces machine-readable JUnit/Playwright/k6/ZAP/dependency/secret-scan results and one reproducible acceptance command.

- [ ] **Step 1: Add failing end-to-end acceptance assertions**

The suite provisions a clean bind-mounted PostgreSQL, runs migrations/seeds, performs the mobile and admin journeys through Nginx, restarts and recreates application/PostgreSQL containers without deleting volumes, and verifies accounts, component versions, sessions, runs, body/meal/workout facts and reports survive. It also asserts there are no main-source Fake/Mock/demo repositories or leaked credential/JWT values.

- [ ] **Step 2: Run RED and close every failure**

Run: `bash scripts/acceptance/run-all.sh`  
Expected before fixes: at least one unmet integrated assertion. Fix production behavior or the fixture responsible; do not weaken an assertion to fit implementation.

- [ ] **Step 3: Execute the 2C4G smoke profile**

Under Compose CPU/memory limits, run two simultaneous Agent streams, one recognition job, home/API traffic and scheduled workers. Assert no OOM/restart, p95 non-Agent API latency below 500 ms on the local host, durable event order and graceful 30-second shutdown.

- [ ] **Step 4: Run GREEN and Commit**

Run: `bash scripts/acceptance/run-all.sh`  
Expected: exit 0 with archived reports and no Critical/High security findings.

```bash
git add tests scripts docs/testing
git commit -m "test: add full production acceptance suite"
```

### Task 21: Two independent CR rounds, remediation and acceptance handoff

**Files:**
- Create: `docs/reviews/cr-round-1.md`, `docs/reviews/cr-round-2.md`
- Create: `docs/acceptance/acceptance-report.md`
- Create: `docs/operations/{configuration-checklist.md,runbook.md}`
- Modify: product/architecture/API documents when review exposes a verified mismatch.

**Interfaces:**
- Produces traceable finding IDs with severity, evidence, owner commit and re-review verdict.
- Produces the final account/key/configuration checklist and reproducible local/production operation steps.

- [ ] **Step 1: Dispatch independent CR round 1**

Review the complete branch for product completeness, modular boundaries, API semantics, authorization/tenant scoping, Agent safety, concurrency, transactions, secret handling, accessibility, responsive aesthetics, deployment recovery and test honesty. Record every finding; Critical/Important findings block acceptance.

- [ ] **Step 2: Fix findings test-first and obtain scoped re-review**

For each behavioral defect, add a failing regression test, reproduce it, implement the fix, run the affected module and full regression suite, then link the fixing commit and reviewer verdict in `cr-round-1.md`.

- [ ] **Step 3: Dispatch a fresh independent CR round 2**

Use a reviewer that did not implement or perform round 1. Review the full resulting branch rather than only the fix diff; emphasize integration gaps, UI polish, deployability and false-positive tests. Repeat the test-first fix and scoped re-review cycle until no Critical/Important finding remains.

- [ ] **Step 4: Generate and verify the formal acceptance report**

`acceptance-report.md` maps every feature checklist item and architecture constraint to an automated test, manual visual evidence or an explicit external credential prerequisite. `configuration-checklist.md` lists Alibaba Bailian provider/base URL/API key, OSS endpoint/bucket/access credentials, domain/TLS, PostgreSQL password, master encryption key and generated JWT key paths; it contains no secret values.

- [ ] **Step 5: Run the final evidence gate and publish GitHub history**

Run: `bash scripts/acceptance/run-all.sh && git status --short`  
Expected: all suites exit 0 and the working tree contains only the intentional review/acceptance documents before their final commit. Create the private GitHub repository if absent, push the reviewed branch, open a pull request with both CR records and merge only after CI succeeds.

```bash
git add docs
git commit -m "docs: publish reviewed acceptance evidence"
```
