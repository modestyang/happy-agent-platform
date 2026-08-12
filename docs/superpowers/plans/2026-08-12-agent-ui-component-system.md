# Agent UI Component System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复移动端首页密度、聊天横向溢出和旧品牌名称，并交付可复用的表格、确认卡、趋势/媒体放大及 `AiContentBlock` 渲染基础层。

**Architecture:** 保留现有 OpenAPI `AiContentBlock` 作为唯一结构化内容契约，在 React 前端增加受控 renderer registry。Markdown 表格、审批卡、趋势图和媒体共用一组本地 UI primitives；所有 Action 由调用方白名单回调提供，不解释 Agent 下发的 URL、脚本或 Tool key。

**Tech Stack:** React 19、TypeScript 5.9、ReactMarkdown、remark-gfm、Vitest、Testing Library、现有 CSS。

## Global Constraints

- 不新增或升级依赖。
- 不修改 OpenAPI 契约、Tool 清单、Tool 入参、Tool 返回或业务查询逻辑。
- 不新增数据库 migration；仅允许修正现有预生产基线中的品牌种子文本。
- 用户可见 AI 名称统一为“花爷”，技术键 `fitness.coach` 保持不变。
- 聊天页面不得横向滚动；表格和代码块只能在自身 viewport 横向滚动。
- 本轮不部署、不跑全量测试或完整验收，不创建 commit。
- 保留当前工作区已有未提交改动，只修改本计划列出的相关文件。

---

### Task 1: 通用可放大表面与 Markdown 表格

**Files:**
- Create: `frontend/src/components/ContentSurface.tsx`
- Create: `frontend/src/components/ContentSurface.test.tsx`
- Modify: `frontend/src/components/ChatMarkdown.tsx`
- Modify: `frontend/src/components/ChatMarkdown.test.tsx`
- Modify: `frontend/src/app.css`

**Interfaces:**
- Produces: `SurfaceCard`, `ExpandableSurface`, `DataTable`。
- `ExpandableSurface` consumes `label`, `title`, `children`, optional `expandedChildren` and returns an inline surface plus an accessible dialog.
- `DataTable` consumes React table children from ReactMarkdown and owns the only horizontal table viewport.

- [x] **Step 1: Write the failing interaction tests**

```tsx
it('opens an accessible dialog and restores focus after Escape', async () => {
  const user = userEvent.setup();
  render(<ExpandableSurface label="体重趋势" title="体重趋势详情"><div>曲线</div></ExpandableSurface>);
  const trigger = screen.getByRole('button', { name: '放大查看体重趋势' });
  await user.click(trigger);
  expect(screen.getByRole('dialog', { name: '体重趋势详情' })).toBeInTheDocument();
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(trigger).toHaveFocus();
});

it('renders a GFM table in a local scroll viewport with an expand action', () => {
  render(<ChatMarkdown text={'| 动作 | 说明 |\n| --- | --- |\n| 深蹲 | 膝盖跟随脚尖方向 |'} />);
  expect(screen.getByRole('region', { name: '可横向滑动的表格' })).toContainElement(screen.getByRole('table'));
  expect(screen.getByRole('button', { name: '放大查看表格' })).toBeInTheDocument();
});
```

- [x] **Step 2: Run RED**

Run: `npm --prefix frontend test -- src/components/ContentSurface.test.tsx src/components/ChatMarkdown.test.tsx`

Expected: FAIL because `ContentSurface` and the table renderer do not exist.

- [x] **Step 3: Implement the minimal primitives**

```tsx
export function ExpandableSurface({ label, title, children, expandedChildren = children }: Props) {
  const [open, setOpen] = useState(false);
  // Focus close on open; Escape closes; cleanup restores body overflow and trigger focus.
  return <div className="expandable-surface">
    <div className="expandable-surface__inline">{children}</div>
    <button aria-label={`放大查看${label}`} onClick={() => setOpen(true)}><Maximize2 /></button>
    {open && createPortal(<div className="surface-dialog-backdrop" onMouseDown={closeFromBackdrop}><section role="dialog" aria-modal="true" aria-label={title}><header><strong>{title}</strong><button ref={closeRef} aria-label={`关闭${title}`} onClick={close}><X /></button></header><div>{expandedChildren}</div></section></div>, document.body)}
  </div>;
}

export function DataTable({ children }: { children?: ReactNode }) {
  if (!children) return <SurfaceCard><p>暂无表格数据</p></SurfaceCard>;
  const table = <table>{children}</table>;
  return <ExpandableSurface label="表格" title="表格详情" expandedChildren={<div className="data-table-viewport data-table-viewport--expanded">{table}</div>}>
    <div className="data-table-viewport" role="region" aria-label="可横向滑动的表格" tabIndex={0}>{table}</div>
  </ExpandableSurface>;
}
```

In `ChatMarkdown`, map ReactMarkdown's `table` node to `DataTable` and normalize old assistant brand text before parsing.

- [x] **Step 4: Run GREEN and refactor**

Run: `npm --prefix frontend test -- src/components/ContentSurface.test.tsx src/components/ChatMarkdown.test.tsx`

Expected: PASS. Keep the table as a real semantic `<table>` and verify raw HTML remains inert.

---

### Task 2: Renderer registry 与共享确认卡

**Files:**
- Create: `frontend/src/components/AiContentRenderer.tsx`
- Create: `frontend/src/components/AiContentRenderer.test.tsx`
- Modify: `frontend/src/components/ContentSurface.tsx`
- Modify: `frontend/src/components/AgentRunMessage.tsx`
- Modify: `frontend/src/components/AgentRunMessage.test.tsx`

**Interfaces:**
- Produces: `RenderContext`, `AiContentRenderer`, `ConfirmationViewModel`, `ConfirmationCard`。
- `AiContentRenderer` consumes `AiContentBlock | unknown` and a `RenderContext` with optional `onConfirm`/`onCancel` callbacks.
- Current `RunApproval` is adapted to `ConfirmationViewModel`; the existing approval API handler signature remains unchanged.

- [x] **Step 1: Write failing registry and approval tests**

```tsx
it('dispatches trusted content blocks and safely degrades unknown blocks', () => {
  const { rerender } = render(<AiContentRenderer block={{ kind: 'TEXT', markdown: '**保持节奏**' }} />);
  expect(screen.getByText('保持节奏')).toBeInTheDocument();
  rerender(<AiContentRenderer block={{ kind: 'UNKNOWN', html: '<script>bad()</script>' }} />);
  expect(screen.getByText('暂不支持这类内容')).toBeInTheDocument();
  expect(document.querySelector('script')).toBeNull();
});

it('routes confirmation only through registered callbacks', async () => {
  const onConfirm = vi.fn();
  render(<AiContentRenderer block={{ kind: 'CONFIRMATION', confirmationId: 'c1', title: '保存计划', message: '保存到本周计划？', confirmLabel: '保存', cancelLabel: '取消' }} context={{ onConfirm }} />);
  await userEvent.click(screen.getByRole('button', { name: '保存' }));
  expect(onConfirm).toHaveBeenCalledWith('c1');
});
```

Extend the existing `AgentRunMessage` test so its proposal details remain visible and clicking “确认并保存” still calls `onDecision('approval-1', 'APPROVE')` through the shared card.

- [x] **Step 2: Run RED**

Run: `npm --prefix frontend test -- src/components/AiContentRenderer.test.tsx src/components/AgentRunMessage.test.tsx`

Expected: FAIL because registry and shared confirmation model are missing.

- [x] **Step 3: Implement typed dispatch and the approval adapter**

```tsx
export type RenderContext = {
  onConfirm?: (confirmationId: string) => void;
  onCancel?: (confirmationId: string) => void;
};

export function AiContentRenderer({ block, context = {} }: { block: AiContentBlock | unknown; context?: RenderContext }) {
  if (!isContentBlock(block)) return <SurfaceCard><p>暂不支持这类内容</p></SurfaceCard>;
  switch (block.kind) {
    case 'TEXT': return <ChatMarkdown text={block.markdown} />;
    case 'BODY_TREND': return <BodyTrendCard block={block} />;
    case 'CONFIRMATION': return <ConfirmationCard model={confirmationModel(block)} onConfirm={() => context.onConfirm?.(block.confirmationId)} onCancel={() => context.onCancel?.(block.confirmationId)} />;
    default: return <SurfaceCard><p>暂不支持这类内容</p></SurfaceCard>;
  }
}
```

Adapt `RunApproval` to the same `ConfirmationCard`, passing proposal rows through the card's controlled detail slot.

- [x] **Step 4: Run GREEN**

Run: `npm --prefix frontend test -- src/components/AiContentRenderer.test.tsx src/components/AgentRunMessage.test.tsx`

Expected: PASS with no arbitrary HTML, URL or Tool execution path.

---

### Task 3: 首页趋势与选择性视觉放大

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/components/MiniVisuals.tsx`
- Modify: `frontend/src/components/ExerciseVisual.tsx`
- Create: `frontend/src/components/ExerciseVisual.test.tsx`
- Modify: `frontend/src/components/CurrentGoalReport.tsx`
- Modify: `frontend/src/components/CurrentGoalReport.test.tsx`
- Modify: `frontend/src/components/MealRecordForm.tsx`
- Modify: `frontend/src/components/MealRecordForm.test.tsx`
- Modify: `frontend/src/app.css`

**Interfaces:**
- `WeightSparkline` remains the chart renderer used by homepage and body pages.
- Non-compact `ExerciseVisual` wraps its real image or SVG fallback in `ExpandableSurface`; compact mode remains a plain visual.
- Report trends and meal preview use the same `ExpandableSurface` dialog behavior.

- [x] **Step 1: Write failing consumer tests**

Add literal behavior assertions:

```tsx
expect(await screen.findByRole('region', { name: '首页体重趋势' })).toHaveTextContent('体重变化');
expect(screen.getByRole('button', { name: '放大查看体重趋势' })).toBeInTheDocument();

render(<ExerciseVisual exercise={{ name: '深蹲', targetArea: '下肢' }} />);
expect(screen.getByRole('button', { name: '放大查看深蹲动作示意' })).toBeInTheDocument();

render(<ExerciseVisual compact exercise={{ name: '深蹲', targetArea: '下肢' }} />);
expect(screen.queryByRole('button', { name: '放大查看深蹲动作示意' })).not.toBeInTheDocument();
```

Update report and meal tests to assert their respective “放大查看…” buttons open a dialog containing the visual.

- [x] **Step 2: Run RED**

Run: `npm --prefix frontend test -- src/App.test.tsx src/components/ExerciseVisual.test.tsx src/components/CurrentGoalReport.test.tsx src/components/MealRecordForm.test.tsx`

Expected: FAIL on the missing homepage trend and missing expand controls.

- [x] **Step 3: Implement selective wrapping**

In `HomePage`, insert after the goal card:

```tsx
<section className="home-trend" aria-label="首页体重趋势">
  <header><div><small>最近记录</small><strong>体重变化</strong></div><span>{latestWeight ? `${latestWeight} 斤` : '等待记录'}</span></header>
  <ExpandableSurface label="体重趋势" title="体重趋势详情"><WeightSparkline records={data.bodyRecords} /></ExpandableSurface>
</section>
```

Use the same explicit pattern for each supported visual:

```tsx
const visual = <div className="exercise-visual__content" role="img" aria-label={`${exercise.name}第${step}步动作示意`}>{media}</div>;
return compact ? visual : <ExpandableSurface label={`${exercise.name}动作示意`} title={`${exercise.name}动作示意详情`}>{visual}</ExpandableSurface>;

<ExpandableSurface label="四周体重趋势" title="四周体重趋势详情"><WeightTrendChart report={report} /></ExpandableSurface>
<ExpandableSurface label="四周训练量趋势" title="四周训练量趋势详情"><TrainingTrendChart report={report} /></ExpandableSurface>

{previewUrl && <ExpandableSurface label="饮食照片" title="饮食照片详情"><img className="meal-photo-preview" src={previewUrl} alt="已选择的饮食照片" /></ExpandableSurface>}
```

Do not wrap mascot, icon, `BodyActivation` or compact exercise thumbnails.

- [x] **Step 4: Run GREEN**

Run: `npm --prefix frontend test -- src/App.test.tsx src/components/ExerciseVisual.test.tsx src/components/CurrentGoalReport.test.tsx src/components/MealRecordForm.test.tsx`

Expected: PASS; compact action-library visuals still have no nested expand button.

---

### Task 4: 首页/聊天宽度与“花爷”品牌修复

**Files:**
- Modify: `frontend/src/app.css`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/components/ChatMarkdown.test.tsx`
- Modify: `frontend/src/components/CurrentGoalReport.tsx`
- Modify: `frontend/src/components/CurrentGoalReport.test.tsx`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStore.java`
- Modify: `agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql`
- Modify: related Agent infrastructure fixtures that assert the user-visible draft or prompt name.

**Interfaces:**
- `.home-page` uses natural-height rows and vertical scroll only when content truly exceeds the viewport.
- `.home-actions` uses two content-density rows, never `1fr` rows.
- `.ai-scroll`, `.conversation`, `.message`, `.message-body`, `.md` are width-bounded; only `.data-table-viewport` and `.md pre` use `overflow-x: auto`.
- `ChatMarkdown` normalizes historical assistant output; user messages remain raw text because `App.tsx` renders them as `<span>`.

- [x] **Step 1: Write failing layout and brand tests**

```tsx
expect(getComputedStyle(homePage).overflowY).toBe('auto');
expect(getComputedStyle(homeActions).gridTemplateRows).not.toContain('1fr');
expect(getComputedStyle(messageBody).minWidth).toBe('0px');
expect(getComputedStyle(aiScroll).overflowX).toBe('hidden');

render(<ChatMarkdown text="瘦瘦 AI 花爷陪你继续" />);
expect(screen.getByText('花爷陪你继续')).toBeInTheDocument();
expect(screen.queryByText(/瘦瘦/)).not.toBeInTheDocument();
```

Change the report holding-state assertion to `花爷正在整理你的当前目标`.

- [x] **Step 2: Run RED**

Run: `npm --prefix frontend test -- src/App.test.tsx src/components/ChatMarkdown.test.tsx src/components/CurrentGoalReport.test.tsx`

Expected: FAIL on old CSS layout rules and old name strings.

- [x] **Step 3: Implement CSS bounds and brand source changes**

Use these essential CSS constraints:

```css
.home-page { grid-template-rows: auto auto auto auto; align-content: start; overflow-x: hidden; overflow-y: auto; }
.home-actions { grid-template-rows: repeat(2, minmax(128px, auto)); }
.ai-scroll { overflow-x: hidden; }
.conversation, .message, .message-body, .md { min-width: 0; max-width: 100%; }
.message-body { overflow-wrap: anywhere; }
.md pre, .data-table-viewport { max-width: 100%; overflow-x: auto; }
```

Replace user-visible “瘦瘦系统提示词”“瘦瘦健身教练”“瘦瘦 AI 花爷” with “花爷系统提示词”“花爷健身教练”“花爷” in the Agent baseline and Java fallback; preserve all technical keys and Tool/Skill lists.

- [x] **Step 4: Run GREEN**

Run: `npm --prefix frontend test -- src/App.test.tsx src/components/ChatMarkdown.test.tsx src/components/CurrentGoalReport.test.tsx`

Expected: PASS with natural home rows and no old user-visible brand in rendered UI.

---

### Task 5: 定向验证与简要代码审查

**Files:**
- Modify only files required to fix issues found by the checks below.

**Interfaces:**
- No new interface; this task validates all prior deliverables together.

- [x] **Step 1: Run the focused frontend suite**

Run:

```bash
npm --prefix frontend test -- \
  src/App.test.tsx \
  src/components/ContentSurface.test.tsx \
  src/components/ChatMarkdown.test.tsx \
  src/components/AiContentRenderer.test.tsx \
  src/components/AgentRunMessage.test.tsx \
  src/components/ExerciseVisual.test.tsx \
  src/components/CurrentGoalReport.test.tsx \
  src/components/MealRecordForm.test.tsx
```

Expected: all listed tests pass.

- [x] **Step 2: Run static checks**

Run:

```bash
npm --prefix frontend run typecheck
npm --prefix frontend run lint
git diff --check
```

Expected: exit code 0. Do not run deploy scripts or the complete Maven/Vitest acceptance suite.

- [x] **Step 3: Review only this change set**

Run:

```bash
git diff -- frontend/src/App.tsx frontend/src/app.css frontend/src/components \
  agentbuilder/agentbuilder-infrastructure/src/main/java/happy/jayden/yang/agentbuilder/infrastructure/workbench/JdbcAdminWorkbenchStore.java \
  agentbuilder/agentbuilder-infrastructure/src/main/resources/db/agent/V1__agent_baseline.sql
```

Check for nested interactive elements, lost table semantics, unsafe actions, page-level horizontal overflow, duplicate dialog labels, broken focus restoration, compact-thumbnail regressions, accidental Tool changes and unrelated edits.

- [x] **Step 4: Record outcome**

Update `task_plan.md` and `progress.md` with the exact commands and results. Report explicitly that deployment and full acceptance were not run.
