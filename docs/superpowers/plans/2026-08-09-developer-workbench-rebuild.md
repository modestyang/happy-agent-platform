# 开发者 Agent 工作台重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent 工作台建立独立管理员登录边界，并重做结合真实调试能力的组件管理体验。

**Architecture:** 在 `agent` schema 中持久化单管理员账户和会话，控制器通过 Agent Builder 认证端口而非健身服务认证 `/api/admin/**`。React 工作台先解析管理员会话再渲染，组件中心采用“可筛选卡片列表→详情编辑→底部保存条”的分层交互；调试台和运行追踪继续连接既有真实 Runtime/Trace。

**Tech Stack:** Java 17、Spring Boot、Flyway/PostgreSQL、React 19、React Router、Vitest、Testing Library、Lucide。

## Global Constraints

- `agentbuilder/**` 不得依赖 `application/fitness/**`；`/api/admin/**` 不读取 `FITNESS_SESSION`。
- 管理员 Cookie 名为 `AGENT_ADMIN_SESSION`，HttpOnly、SameSite=Lax、path=/；密码只存 BCrypt hash。
- Agent 配置、调试和追踪必须读取真实 PostgreSQL/运行时，不能添加 Mock 成功回退。
- 迁移只新增 Agent schema 的 `V9__admin_sessions.sql`，不修改现有迁移。
- 前端使用参考页的空间和编辑模式，不复制其演示数据或删除调试能力。

---

### Task 1: 独立管理员认证边界

**Files:**
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/auth/AdminAuthPort.java`
- Create: `agentbuilder/agentbuilder-service/src/main/java/happy/jayden/yang/agentbuilder/service/auth/AdminAuthService.java`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/auth/JdbcAdminAuthStore.java`
- Create: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V9__admin_sessions.sql`
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminAuthController.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchController.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchConfig.java`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**
- Produces `AdminAuthService.login(LoginRequest): SessionView`, `authenticate(String): AdminPrincipal`, `logout(String): void`.
- Produces `POST /api/admin/auth/login`, `POST /api/admin/auth/logout`, `GET /api/admin/auth/session`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void adminSessionIsIndependentFromFitnessSession() throws Exception {
  mvc.perform(get("/api/admin/workbench")).andExpect(status().isUnauthorized());
  Cookie admin = adminLogin("admin", "admin123");
  mvc.perform(get("/api/admin/workbench").cookie(admin)).andExpect(status().isOk());
  mvc.perform(post("/api/local/login").contentType(APPLICATION_JSON)
      .content("{\"username\":\"user\",\"password\":\"demo123\"}"))
      .andExpect(status().isOk());
  mvc.perform(get("/api/admin/workbench").cookie(admin)).andExpect(status().isOk());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl starter -am -Dtest=AdminWorkbenchIntegrationTest#adminSessionIsIndependentFromFitnessSession test`

Expected: FAIL because `/api/admin/auth/login` and `AGENT_ADMIN_SESSION` do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
public record AdminPrincipal(UUID accountId, String username) {}
public record LoginRequest(String username, char[] password) {}
public interface AdminAuthPort {
  Optional<Account> findByUsername(String username);
  String createSession(UUID accountId, Instant expiresAt);
  Optional<AdminPrincipal> findSession(String token, Instant now);
  void removeSession(String token);
}
```

Seed a BCrypt-hashed `admin / admin123` account in V9 only when absent. Have every `/api/admin/**` controller endpoint use `AdminAuthService.authenticate` and issue/expire only `AGENT_ADMIN_SESSION`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl starter -am -Dtest=AdminWorkbenchIntegrationTest test`

Expected: PASS, including unauthenticated 401, independent admin Cookie, draft update, credential save and publish.

- [ ] **Step 5: Commit**

```bash
git add agentbuilder starter docs/architecture/openapi/admin-v1.yaml frontend/src/api/generated
git commit -m "feat(admin): add isolated developer authentication"
```

### Task 2: 管理员登录与 API 契约

**Files:**
- Modify: `docs/architecture/openapi/admin-v1.yaml`
- Modify: `frontend/src/admin/api.ts`
- Modify: `frontend/src/admin/AdminWorkbench.tsx`
- Test: `frontend/src/admin/AdminWorkbench.test.tsx`

**Interfaces:**
- Consumes `/api/admin/auth/session` and `/api/admin/auth/login` from Task 1.
- Produces a login page only when administrator session is absent.

- [ ] **Step 1: Write the failing test**

```tsx
it('shows developer login when the administrator session is absent', async () => {
  mockFetch({ '/api/admin/auth/session': problem(401, 'Administrator authentication required') });
  renderAt('/admin');
  expect(await screen.findByRole('heading', { name: '开发者登录' })).toBeInTheDocument();
  expect(screen.queryByText('前往移动端登录')).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm --prefix frontend test -- AdminWorkbench.test.tsx`

Expected: FAIL because bootstrap directly requests the workbench and shows generic load error.

- [ ] **Step 3: Write minimal implementation**

```ts
export const admin = {
  session: () => request<AdminSession>('/api/admin/auth/session'),
  login: (username: string, password: string) => request<AdminSession>('/api/admin/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request<void>('/api/admin/auth/logout', { method: 'POST' }),
};
```

Use the existing Request wrapper with `credentials: 'include'`; upon login, load the real workbench snapshot; show a clear session-expired state only for actual admin 401 responses.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm --prefix frontend test -- AdminWorkbench.test.tsx && npm --prefix frontend run typecheck`

Expected: PASS and no TypeScript errors.

- [ ] **Step 5: Commit**

```bash
git add docs/architecture/openapi/admin-v1.yaml frontend/src/admin
git commit -m "feat(admin-ui): add developer login flow"
```

### Task 3: 组件中心的卡片与详情编辑体验

**Files:**
- Modify: `frontend/src/admin/AdminWorkbench.tsx`
- Modify: `frontend/src/admin/pages/ComponentType.tsx`
- Modify: `frontend/src/admin/admin.css`
- Test: `frontend/src/admin/AdminWorkbench.test.tsx`

**Interfaces:**
- Consumes real `WorkbenchSnapshot.components` and component PATCH endpoint.
- Produces cards for `PROMPT`、`SKILL`、`TOOL`, and persistent editor saves.

- [ ] **Step 1: Write the failing test**

```tsx
it('edits a Skill with trigger rules and enabled Tool dependencies', async () => {
  mockFetch({ '/api/admin/workbench': skillSnapshot });
  renderAt('/admin/skills');
  await user.click(await screen.findByRole('button', { name: /训练计划编排/ }));
  await user.type(screen.getByLabelText('何时使用'), '用户要求制定计划');
  expect(screen.getByText('有未保存的修改')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '保存' }));
  expect(await screen.findByText('已保存')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm --prefix frontend test -- AdminWorkbench.test.tsx`

Expected: FAIL because the component editor does not expose the reference-page editing hierarchy or dirty save bar.

- [ ] **Step 3: Write minimal implementation**

Implement a searchable and state-filtered card grid. Detail editors must render variable chips and preview for Prompt; trigger/avoid conditions, Markdown edit/preview and enabled Tool chips for Skill; read-only registration banner, risk and JSON Schema tables for Tool. Use an anchored bottom save bar and PATCH only real editable fields.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm --prefix frontend test -- AdminWorkbench.test.tsx && npm --prefix frontend run lint && npm --prefix frontend run build`

Expected: PASS, no lint warnings and production build succeeds.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/admin
git commit -m "feat(admin-ui): redesign component management"
```

### Task 4: 真实调试与运行追踪复验

**Files:**
- Modify: `frontend/src/admin/pages/PlaygroundPage.tsx`
- Modify: `frontend/src/admin/pages/RunTracePage.tsx`
- Modify: `frontend/src/admin/pages/Overview.tsx`
- Modify: `frontend/src/admin/admin.css`
- Test: `frontend/src/admin/AdminWorkbench.test.tsx`
- Test: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**
- Consumes published Agent configuration, `/api/app/ai/messages` runtime call and `/api/admin/runs/**` trace data.
- Produces explicit runtime gate, final answer and trace navigation.

- [ ] **Step 1: Write the failing test**

```tsx
it('renders a real playground answer and opens its persisted run trace', async () => {
  mockFetch({ '/api/admin/workbench': publishedSnapshot, '/api/app/ai/messages': { message: '今晚吃鸡胸南瓜碗。', runId: 'run-1' } });
  renderAt('/admin/playground');
  await user.type(await screen.findByLabelText('测试问题'), '晚餐吃什么');
  await user.click(screen.getByRole('button', { name: '运行调试' }));
  expect(await screen.findByText('今晚吃鸡胸南瓜碗。')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看运行追踪' })).toHaveAttribute('href', '/admin/runs/run-1');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm --prefix frontend test -- AdminWorkbench.test.tsx`

Expected: FAIL if the response/run link is missing or runtime readiness is inferred from fake UI state.

- [ ] **Step 3: Write minimal implementation**

Keep release and Provider gates server-derived. Render model/skill/tool/hook trace events from persisted run detail; show only real output and a clear non-ready explanation when configuration is incomplete.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl starter -am -Dtest=AdminWorkbenchIntegrationTest test && npm --prefix frontend test -- AdminWorkbench.test.tsx`

Expected: backend and frontend workbench tests PASS.

- [ ] **Step 5: Commit**

```bash
git add starter frontend/src/admin
git commit -m "feat(admin-ui): preserve runtime debugging and traces"
```

### Task 5: 完整验证与本地验收环境

**Files:**
- Modify: `task_plan.md`
- Modify: `findings.md`
- Modify: `progress.md`

- [ ] **Step 1: Run contract and architecture verification**

Run: `node scripts/contracts/lint.mjs && ./mvnw -pl architecture-tests test`

Expected: admin contract includes independent authentication and architecture tests PASS.

- [ ] **Step 2: Run complete build verification**

Run: `./mvnw verify -q && npm --prefix frontend test && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run build`

Expected: every command exits 0.

- [ ] **Step 3: Verify in browser**

Open `/admin` with no admin Cookie, log in with `admin/admin123`, edit a Prompt and a Skill, inspect a Tool, run a ready Agent once, then open the persisted trace. In another tab log in/out as `user/demo123` and verify the workbench remains authenticated.

- [ ] **Step 4: Commit verification log**

```bash
git add task_plan.md findings.md progress.md
git commit -m "docs: record developer workbench verification"
```
