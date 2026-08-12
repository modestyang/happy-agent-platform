# Admin Trace Search and Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Agent 管理后台 Trace 页面展示用户名、用户 ID、会话 ID，并支持统一搜索和固定高度的服务端分页列表。

**Architecture:** Fitness 基础设施提供只读用户目录，Agent Trace 仓储只接受用户 ID 或 UUID 标识进行分页查询，Starter 用例顺序编排两个 schema 并组装管理后台 DTO。前端通过 `query/page/size` 请求分页对象，列表内部滚动并使用上一页/下一页导航。

**Tech Stack:** Java 17、Spring Boot、Spring JDBC、PostgreSQL/Testcontainers、OpenAPI、React 18、TypeScript、Vitest、Testing Library、CSS。

## Global Constraints

- 不新增跨 schema JOIN、外键或事务；`agent` schema 不存储用户名。
- `agentbuilder/**` 不得依赖 `application/**`；跨边界编排只放在 `starter`。
- 先修改 `docs/architecture/openapi/admin-v1.yaml`，再修改 Java/TypeScript 接口实现。
- 用户名大小写不敏感包含匹配；用户 ID 和会话 ID 精确匹配。
- 搜索词最大 160 个字符；默认页大小为 10，最大 100。
- AI 回复一键复制不在本次范围内。
- 不新增、升级或降级依赖，不修改 migration 或 CI/CD。
- 工作区已有未提交改动；只修改列出的目标区域，不覆盖或清理用户改动。
- 未经用户明确要求不执行 `git add` 或 `git commit`。

---

### Task 1: Fitness 用户目录

**Files:**
- Create: `application/fitness/fitness-infrastructure/src/main/java/happy/jayden/yang/fitness/infrastructure/JdbcFitnessUserDirectory.java`
- Modify: `starter/src/main/java/happy/jayden/yang/fitness/FitnessExperienceConfig.java`
- Modify: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**
- Produces: `List<UUID> searchUserIds(String usernameQuery)`，按用户名包含匹配返回用户 ID。
- Produces: `Map<UUID, String> findUsernames(Set<UUID> userIds)`，批量返回 ID 到用户名映射。
- Consumes: `fitnessDataSource`，只查询 Fitness 拥有的 `users` 表。

- [x] **Step 1: 写用户名搜索和批量读取的失败测试**

```java
@Test
void searchesUsernamesCaseInsensitivelyAndTreatsWildcardsLiterally() {
  UUID alice = insertUser("AliceChen");
  UUID literal = insertUser("literal%_name");
  insertUser("literalXXname");

  assertEquals(List.of(alice), directory.searchUserIds("alice"));
  assertEquals(List.of(literal), directory.searchUserIds("%_"));
}

@Test
void readsUsernamesForConversationUserIdsInOneDirectoryCall() {
  UUID alice = insertUser("alice");
  UUID bob = insertUser("bob");

  assertEquals(Map.of(alice, "alice", bob, "bob"), directory.findUsernames(Set.of(alice, bob)));
  assertEquals(Map.of(), directory.findUsernames(Set.of()));
}
```

- [x] **Step 2: 运行测试并确认因类尚不存在而失败**

Run:

```bash
./mvnw -pl starter -am -Dtest=AdminWorkbenchIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，编译错误指向 `JdbcFitnessUserDirectory` 不存在。

- [x] **Step 3: 实现最小只读目录**

```java
public final class JdbcFitnessUserDirectory {
  private final JdbcTemplate jdbc;

  public JdbcFitnessUserDirectory(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
  }

  public List<UUID> searchUserIds(String usernameQuery) {
    String pattern = "%" + usernameQuery.replace("!", "!!").replace("%", "!%")
        .replace("_", "!_") + "%";
    return jdbc.query(
        "SELECT user_id FROM users WHERE username IS NOT NULL"
            + " AND username ILIKE ? ESCAPE '!' ORDER BY username, user_id",
        (rs, row) -> rs.getObject("user_id", UUID.class),
        pattern);
  }

  public Map<UUID, String> findUsernames(Set<UUID> userIds) {
    if (userIds.isEmpty()) return Map.of();
    String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
    return jdbc.query(
        "SELECT user_id, username FROM users WHERE user_id IN (" + placeholders + ")",
        rs -> {
          Map<UUID, String> result = new LinkedHashMap<>();
          while (rs.next()) result.put(rs.getObject("user_id", UUID.class), rs.getString("username"));
          return Map.copyOf(result);
        },
        userIds.toArray());
  }
}
```

在 `FitnessExperienceConfig` 中使用 `fitnessDataSource` 注册该 bean，不把 SQL 移到 Starter。

- [x] **Step 4: 重跑测试并确认通过**

Run: 与 Step 2 相同。

Expected: PASS，且 Testcontainers 没有 SQL 警告。

- [x] **Step 5: 检查本任务局部差异**

```bash
git diff --check -- application/fitness/fitness-infrastructure/src/main starter/src/main/java/happy/jayden/yang/fitness/FitnessExperienceConfig.java starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java
git diff -- application/fitness/fitness-infrastructure/src/main starter/src/main/java/happy/jayden/yang/fitness/FitnessExperienceConfig.java starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java
```

Expected: 只包含用户目录、测试和 bean 接线；不提交。

### Task 2: Agent 会话筛选与分页仓储

**Files:**
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/WorkspaceDtos.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepository.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepositoryTest.java`

**Interfaces:**
- Produces: `ConversationPage(List<ConversationSummary> items, int page, int size, boolean hasNext)`。
- Produces: `listRecentConversationSummaries(int page, int size)`。
- Produces: `listConversationSummariesByIdentifier(UUID identifier, int page, int size)`，匹配 `user_id` 或 `conversation_id`。
- Produces: `listConversationSummariesByUserIds(Set<UUID> userIds, int page, int size)`。

- [x] **Step 1: 写三种查询和 `hasNext` 的失败测试**

```java
@Test
void pagesRecentConversationsWithAStableHasNextSignal() {
  insertConversation(UUID.randomUUID(), UUID.randomUUID(), "2026-08-12T09:00:00Z");
  insertConversation(UUID.randomUUID(), UUID.randomUUID(), "2026-08-12T10:00:00Z");

  var first = repository.listRecentConversationSummaries(0, 1);
  var second = repository.listRecentConversationSummaries(1, 1);

  assertEquals(1, first.items().size());
  assertTrue(first.hasNext());
  assertFalse(second.hasNext());
}

@Test
void matchesAnIdentifierAgainstUserIdOrConversationId() {
  UUID userId = UUID.randomUUID();
  UUID conversationId = insertConversation(userId, UUID.randomUUID(), "2026-08-12T10:00:00Z");

  assertEquals(conversationId,
      repository.listConversationSummariesByIdentifier(userId, 0, 10).items().get(0).conversationId());
  assertEquals(conversationId,
      repository.listConversationSummariesByIdentifier(conversationId, 0, 10).items().get(0).conversationId());
}

@Test
void matchesOnlyResolvedUsernameUserIds() {
  UUID matchingUser = UUID.randomUUID();
  UUID expected = insertConversation(matchingUser, UUID.randomUUID(), "2026-08-12T10:00:00Z");
  insertConversation(UUID.randomUUID(), UUID.randomUUID(), "2026-08-12T11:00:00Z");

  assertEquals(List.of(expected), repository
      .listConversationSummariesByUserIds(Set.of(matchingUser), 0, 10)
      .items().stream().map(ConversationSummary::conversationId).toList());
  assertTrue(repository.listConversationSummariesByUserIds(Set.of(), 0, 10).items().isEmpty());
}
```

- [x] **Step 2: 运行仓储测试并确认 API 缺失失败**

```bash
./mvnw -pl agentbuilder/agentbuilder-infrastructure -am -Dtest=JdbcRunTraceRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，缺少 `ConversationPage` 和新的筛选方法。

- [x] **Step 3: 增加分页 DTO 和共享分页查询**

```java
public record ConversationPage(
    List<ConversationSummary> items, int page, int size, boolean hasNext) {
  public ConversationPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    if (page < 0) throw new IllegalArgumentException("page");
    if (size < 1 || size > 100) throw new IllegalArgumentException("size");
  }
}
```

三个 public 方法只构造不同的 `WHERE` 和参数，统一调用 private 方法。Private 方法读取 `size + 1` 条、按 `last_message_at DESC, conversation_id DESC` 稳定排序，删除第 `size + 1` 条后设置 `hasNext=true`。空用户 ID 集合直接返回空页，不执行 `IN ()` SQL。

```java
public ConversationPage listRecentConversationSummaries(int page, int size) {
  return listConversationSummaries("", new ArrayList<>(), page, size);
}

public ConversationPage listConversationSummariesByIdentifier(
    UUID identifier, int page, int size) {
  return listConversationSummaries(
      " WHERE c.user_id=? OR c.conversation_id=?",
      new ArrayList<>(List.of(identifier, identifier)), page, size);
}

public ConversationPage listConversationSummariesByUserIds(
    Set<UUID> userIds, int page, int size) {
  if (userIds.isEmpty()) return new ConversationPage(List.of(), page, size, false);
  String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
  return listConversationSummaries(
      " WHERE c.user_id IN (" + placeholders + ")",
      new ArrayList<>(userIds), page, size);
}

private ConversationPage listConversationSummaries(
    String where, List<Object> args, int page, int size) {
  new ConversationPage(List.of(), page, size, false); // validates page and size
  args.add(size + 1);
  args.add(page * size);
  List<ConversationSummary> rows = jdbc.query(
      CONVERSATION_SUMMARY_SELECT + where
          + " ORDER BY c.last_message_at DESC, c.conversation_id DESC LIMIT ? OFFSET ?",
      (rs, row) -> mapConversationSummary(rs), args.toArray());
  boolean hasNext = rows.size() > size;
  List<ConversationSummary> items = hasNext ? rows.subList(0, size) : rows;
  return new ConversationPage(items, page, size, hasNext);
}
```

- [x] **Step 4: 重跑仓储测试并确认通过**

Run: 与 Step 2 相同。

Expected: PASS，包括既有 Run/Trace 测试。

- [x] **Step 5: 检查 AgentBuilder 边界差异**

```bash
git diff --check -- agentbuilder/agentbuilder-infrastructure/src/main agentbuilder/agentbuilder-infrastructure/src/test
git diff -- agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/WorkspaceDtos.java agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepository.java agentbuilder/agentbuilder-infrastructure/src/test/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcRunTraceRepositoryTest.java
```

Expected: AgentBuilder 只接触 UUID 和分页，不出现 Fitness 类型或用户名 SQL；不提交。

### Task 3: OpenAPI 与 Starter 会话 Trace 用例

**Files:**
- Modify: `docs/architecture/openapi/admin-v1.yaml`
- Modify: `scripts/contracts/fixtures/admin-coverage.json`
- Modify: `frontend/src/api/generated/admin.ts`
- Create: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminConversationTraceService.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchConfig.java`
- Modify: `starter/src/main/java/happy/jayden/yang/agentbuilder/AdminWorkbenchController.java`
- Modify: `starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `JdbcFitnessUserDirectory`。
- Consumes: Task 2 的三个分页查询方法。
- Produces: `GET /api/admin/traces/conversations?query=&page=0&size=10`。
- Produces: `ConversationPageView(items, page, size, hasNext)`，其中 item 是包含 `username` 的扁平会话摘要。

- [x] **Step 1: 先修改 OpenAPI 契约并让 coverage 校验失败**

为列表接口增加 `query` 参数并把响应 schema 改为：

```yaml
WorkbenchConversationPage:
  type: object
  additionalProperties: false
  required: [items, page, size, hasNext]
  properties:
    items:
      type: array
      items:
        $ref: "#/components/schemas/WorkbenchConversationSummary"
    page: { type: integer, minimum: 0 }
    size: { type: integer, minimum: 1, maximum: 100 }
    hasNext: { type: boolean }
```

并在 `WorkbenchConversationSummary.required` 和 `properties` 中加入 `username: {type: string, minLength: 1}`。

Run:

```bash
node scripts/contracts/lint.mjs
```

Expected: FAIL，coverage fixture 仍期待旧数组 schema 或旧 query 参数集合。

- [x] **Step 2: 更新 coverage fixture 并生成类型**

将 `adminListRecentConversations` fixture 改为 `WorkbenchConversationPage`，精确参数改为 `query/page/size`，然后运行：

```bash
node scripts/contracts/lint.mjs
node scripts/contracts/generate-types.mjs
```

Expected: lint PASS；`frontend/src/api/generated/admin.ts` 生成 `WorkbenchConversationPage`、`username` 和 `query` 参数类型。

- [x] **Step 3: 写 Starter 集成失败测试**

```java
@Test
void searchesAndPagesConversationsByUsernameUserIdOrConversationId() throws Exception {
  UUID userId = insertFitnessUser("trace-alice");
  UUID conversationId = insertAgentConversation(userId, "早餐怎么安排");
  Cookie session = adminLogin("admin", "admin123");

  mvc.perform(get("/api/admin/traces/conversations")
          .queryParam("query", "alice").queryParam("page", "0").queryParam("size", "10")
          .cookie(session))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].username").value("trace-alice"))
      .andExpect(jsonPath("$.items[0].userId").value(userId.toString()))
      .andExpect(jsonPath("$.items[0].conversationId").value(conversationId.toString()))
      .andExpect(jsonPath("$.page").value(0))
      .andExpect(jsonPath("$.size").value(10))
      .andExpect(jsonPath("$.hasNext").value(false));

  mvc.perform(get("/api/admin/traces/conversations").queryParam("query", userId.toString()).cookie(session))
      .andExpect(jsonPath("$.items[0].conversationId").value(conversationId.toString()));
  mvc.perform(get("/api/admin/traces/conversations").queryParam("query", conversationId.toString()).cookie(session))
      .andExpect(jsonPath("$.items[0].conversationId").value(conversationId.toString()));
}
```

同时增加 `query` 超过 160 字符返回 400 的断言：

```java
mvc.perform(get("/api/admin/traces/conversations")
        .queryParam("query", "x".repeat(161)).cookie(session))
    .andExpect(status().isBadRequest());
```

- [x] **Step 4: 运行集成测试并确认旧控制器响应失败**

```bash
./mvnw -pl starter -am -Dtest=AdminWorkbenchIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，旧接口返回数组且不接受搜索编排。

- [x] **Step 5: 实现 Starter 编排和响应 DTO**

```java
public ConversationPageView conversations(String rawQuery, int page, int size) {
  String query = rawQuery == null ? "" : rawQuery.trim();
  validate(query, page, size);
  ConversationPage result;
  UUID identifier = parseUuid(query);
  if (query.isEmpty()) {
    result = traces.listRecentConversationSummaries(page, size);
  } else if (identifier != null) {
    result = traces.listConversationSummariesByIdentifier(identifier, page, size);
  } else {
    result = traces.listConversationSummariesByUserIds(users.searchUserIds(query), page, size);
  }
  Set<UUID> ids = result.items().stream().map(ConversationSummary::userId).collect(toSet());
  Map<UUID, String> usernames = users.findUsernames(ids);
  List<ConversationView> items = result.items().stream()
      .map(item -> ConversationView.from(item, usernames.getOrDefault(item.userId(), "用户名不可用")))
      .toList();
  return new ConversationPageView(items, result.page(), result.size(), result.hasNext());
}
```

控制器增加 `@RequestParam(value = "query", required = false) String query`，默认 size 改为 10，并只负责认证和调用 `AdminConversationTraceService`。`AdminWorkbenchConfig` 显式注册该服务。

- [x] **Step 6: 重跑 Starter 集成测试和契约检查**

```bash
./mvnw -pl starter -am -Dtest=AdminWorkbenchIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
node scripts/contracts/lint.mjs
node scripts/contracts/generate-types.mjs
```

Expected: PASS；再次生成类型不产生新的内容变化。

- [x] **Step 7: 检查本任务局部差异**

```bash
git diff --check -- docs/architecture/openapi/admin-v1.yaml scripts/contracts/fixtures/admin-coverage.json frontend/src/api/generated/admin.ts starter/src/main starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java
git diff -- docs/architecture/openapi/admin-v1.yaml scripts/contracts/fixtures/admin-coverage.json starter/src/main/java/happy/jayden/yang/agentbuilder starter/src/test/java/happy/jayden/yang/agentbuilder/AdminWorkbenchIntegrationTest.java
```

Expected: 契约、编排和测试一致；不修改 migration；不提交。

### Task 4: Trace 搜索分页界面

**Files:**
- Modify: `frontend/src/admin/api.ts`
- Modify: `frontend/src/admin/pages/ConversationTracePage.tsx`
- Create: `frontend/src/admin/pages/conversation-trace.css`
- Modify: `frontend/src/admin/AdminWorkbench.test.tsx`

**Interfaces:**
- Consumes: `ConversationPage`，字段为 `items/page/size/hasNext`。
- Produces: `admin.listConversations(query: string, page: number, size: number)`。
- Produces: 标注为“搜索会话”的表单，以及“上一页 / 第 N 页 / 下一页”分页导航。

- [x] **Step 1: 把既有 Trace 前端测试改成期望新交互**

```tsx
it('searches paged conversations and shows all conversation identities', async () => {
  const conversation = {
    conversationId: 'conversation-1', userId: 'user-1', username: 'trace-alice',
    agentKey: 'fitness.coach', title: '明天怎么训练', status: 'ACTIVE',
    startedAt: '2026-08-10T08:00:00Z', lastMessageAt: '2026-08-10T08:01:00Z',
    messageCount: 2, runCount: 1,
  };
  const fetchMock = mockFetch({
    '/api/admin/traces/conversations?page=0&size=10': { items: [conversation], page: 0, size: 10, hasNext: true },
    '/api/admin/traces/conversations?query=alice&page=0&size=10': { items: [conversation], page: 0, size: 10, hasNext: true },
    '/api/admin/traces/conversations?query=alice&page=1&size=10': { items: [], page: 1, size: 10, hasNext: false },
    '/api/admin/traces/conversations/conversation-1': {
      conversation, messages: [], runs: [],
    },
  });
  const user = userEvent.setup();
  renderAt('/admin/traces');

  expect(await screen.findByText('trace-alice')).toBeInTheDocument();
  expect(screen.getByText('user-1')).toBeInTheDocument();
  expect(screen.getAllByText('conversation-1').length).toBeGreaterThan(0);
  await user.type(screen.getByRole('searchbox', { name: '搜索会话' }), 'alice');
  await user.click(screen.getByRole('button', { name: '搜索' }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    '/api/admin/traces/conversations?query=alice&page=0&size=10', expect.anything()));
  expect(screen.getByText('第 1 页')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '下一页' }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    '/api/admin/traces/conversations?query=alice&page=1&size=10', expect.anything()));
  expect(screen.getByText('未找到匹配会话')).toBeInTheDocument();

  const searchbox = screen.getByRole('searchbox', { name: '搜索会话' });
  await user.clear(searchbox);
  await user.click(screen.getByRole('button', { name: '搜索' }));
  await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith(
    '/api/admin/traces/conversations?page=0&size=10', expect.anything()));
});
```

- [x] **Step 2: 运行前端测试并确认旧页面失败**

```bash
npm --prefix frontend test -- --run src/admin/AdminWorkbench.test.tsx
```

Expected: FAIL，缺少 searchbox、分页响应解析、身份字段和分页按钮。

- [x] **Step 3: 更新手写 API 类型和 URL 生成**

```ts
export type ConversationSummary = {
  conversationId: string;
  userId: string;
  username: string;
  agentKey: string;
  title: string;
  status: string;
  startedAt: string;
  lastMessageAt: string;
  messageCount: number;
  runCount: number;
};

export type ConversationPage = {
  items: ConversationSummary[];
  page: number;
  size: number;
  hasNext: boolean;
};

listConversations: (query: string, page: number, size: number) => {
  const search = new URLSearchParams();
  if (query) search.set('query', query);
  search.set('page', String(page));
  search.set('size', String(size));
  return request<ConversationPage>(`/api/admin/traces/conversations?${search.toString()}`);
},
```

- [x] **Step 4: 实现搜索、自动选择和分页状态**

`ConversationTracePage` 保存 `queryInput/query/page/result/selected/detail/loading/error`。所有请求都调用 `load(nextQuery, nextPage)`；搜索提交传 `queryInput.trim(), 0`，翻页传已提交的 `query`，刷新传当前 `query/page`。成功时选中 `items[0]` 并加载详情；空页清除 `selected/detail`。

```tsx
const PAGE_SIZE = 10;
const [queryInput, setQueryInput] = useState('');
const [query, setQuery] = useState('');
const [result, setResult] = useState<ConversationPage>({ items: [], page: 0, size: PAGE_SIZE, hasNext: false });
const [selected, setSelected] = useState<ConversationSummary>();

async function load(nextQuery: string, nextPage: number) {
  setLoading(true);
  setError('');
  try {
    const next = await admin.listConversations(nextQuery, nextPage, PAGE_SIZE);
    setQuery(nextQuery);
    setResult(next);
    const first = next.items[0];
    setSelected(first);
    setDetail(first ? await admin.conversationTrace(first.conversationId) : undefined);
  } catch (caught) {
    setError(caught instanceof Error ? caught.message : '最近会话加载失败');
  } finally {
    setLoading(false);
  }
}

function submitSearch(event: FormEvent) {
  event.preventDefault();
  void load(queryInput.trim(), 0);
}
```

表单和分页的核心结构：

```tsx
<form className="admin-trace-search" role="search" onSubmit={submitSearch}>
  <label><Search /><input type="search" aria-label="搜索会话"
    placeholder="搜索用户名、用户 ID 或会话 ID"
    value={queryInput} onChange={(event) => setQueryInput(event.target.value)} /></label>
  <button className="admin-primary" disabled={loading}>搜索</button>
</form>

<footer className="admin-conversation-pagination" aria-label="会话分页">
  <button disabled={loading || page === 0} onClick={() => void load(query, page - 1)}>上一页</button>
  <span>第 {page + 1} 页</span>
  <button disabled={loading || !result.hasNext} onClick={() => void load(query, page + 1)}>下一页</button>
</footer>
```

列表项和详情头部都从 `selected`/item 展示 `username`、完整 `userId` 和完整 `conversationId`。

- [x] **Step 5: 调整桌面等高和移动端布局**

将列表分成 header、`.admin-conversation-list__items` 和分页 footer 三行；桌面端列表和详情使用相同 `clamp` 高度，items/messages 内部滚动：

```css
@media (min-width:861px){
  .admin-conversation-list,.admin-conversation-detail{height:clamp(520px,calc(100vh - 250px),720px)}
}
.admin-conversation-list{display:grid;grid-template-rows:auto minmax(0,1fr) auto}
.admin-conversation-list__items{min-height:0;overflow:auto}
.admin-conversation-detail{display:flex;min-height:0;flex-direction:column}
.admin-conversation-messages{min-height:0;max-height:none;flex:1;overflow:auto}
.admin-conversation-pagination{display:flex;align-items:center;justify-content:space-between}
```

在现有 `max-width:860px` media query 中恢复 `height:auto`，避免移动端双栏高度约束。

- [x] **Step 6: 重跑前端测试和类型检查**

```bash
npm --prefix frontend test -- --run src/admin/AdminWorkbench.test.tsx
npm --prefix frontend run typecheck
```

Expected: PASS，无 React act warning、无 TypeScript 错误。

- [x] **Step 7: 检查前端局部差异**

```bash
git diff --check -- frontend/src/admin/api.ts frontend/src/admin/pages/ConversationTracePage.tsx frontend/src/admin/admin.css frontend/src/admin/AdminWorkbench.test.tsx
git diff -- frontend/src/admin/api.ts frontend/src/admin/pages/ConversationTracePage.tsx frontend/src/admin/admin.css frontend/src/admin/AdminWorkbench.test.tsx
```

Expected: 不包含复制按钮或剪贴板逻辑；不提交。

### Task 5: 全量格式化与验证

**Files:**
- Modify only if formatter changes target Java files: Task 1–3 中列出的 Java 文件。

**Interfaces:**
- Consumes: Tasks 1–4 的最终代码。
- Produces: 格式化、契约、模块边界、后端和前端验证证据。

- [x] **Step 1: 运行 Java 格式化并复查差异**

```bash
./mvnw spotless:apply
git diff --check
```

Expected: Spotless 成功，且没有空白错误；若 Spotless 触及范围外用户文件，立即检查并只保留目标文件的必要格式变化。

- [x] **Step 2: 运行契约与生成检查**

```bash
node scripts/contracts/lint.mjs
node scripts/contracts/generate-types.mjs
git diff --check -- docs/architecture/openapi/admin-v1.yaml scripts/contracts/fixtures/admin-coverage.json frontend/src/api/generated/admin.ts
```

Expected: lint 和生成命令成功，第二次生成无新增变化。

- [x] **Step 3: 运行后端相关测试和架构测试**

```bash
./mvnw -pl application/fitness/fitness-infrastructure,agentbuilder/agentbuilder-infrastructure,starter,architecture-tests -am test
```

Expected: PASS。Docker 不可用时，记录被跳过或无法启动的 Testcontainers 测试名称，不声称其通过。

验证结果：Trace 相关 `JdbcRunTraceRepositoryTest` 7/7、`AdminWorkbenchIntegrationTest` 9/9 通过，ArchUnit 7/7 通过。组合命令被既有 Fitness 改动中的 4 个无关失败阻断：迁移历史仍断言 13 但工作区已有 V14/V15，以及 3 个餐食异步状态/版本断言失败。

- [x] **Step 4: 运行前端测试和类型检查**

```bash
npm --prefix frontend test -- --run
npm --prefix frontend run typecheck
```

Expected: PASS，无未处理异常、warning 或类型错误。

验证结果：TypeScript 检查通过，Trace 页面定向测试 21/21 通过。前端全量测试为 127/128，通过项之外仅既有 `MealRecommendationPage.test.ts` 的反馈错误提示断言失败，隔离运行可稳定复现且相关组件已有本任务前的未提交改动。

- [ ] **Step 5: 最终范围审计**

```bash
git status --short
git diff --stat
git diff --check
```

Expected: 清楚区分本次 Trace 改动与进入任务前已有的用户改动；不 stage、不 commit、不修改 migration/CI/依赖。
