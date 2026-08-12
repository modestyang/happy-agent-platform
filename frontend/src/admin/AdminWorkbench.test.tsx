import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AdminWorkbench } from './AdminWorkbench';

if (!Element.prototype.scrollTo) {
  Element.prototype.scrollTo = () => {};
}

type ComponentFixture = {
  type: string;
  componentKey: string;
  displayName: string;
  description: string;
  version: number;
  status: string;
  tags: string[];
  config: Record<string, unknown>;
};

type WorkbenchFixture = {
  overview: Record<string, unknown>;
  agents: Array<{
    agentKey: string;
    name: string;
    description: string;
    status: string;
    frameworkKey: string;
    providerKey: string;
    modelKey: string;
    promptKey: string;
    toolKeys: string[];
    skillKeys: string[];
    hookKeys: string[];
    memoryKey: string;
    temperature: number;
    maxToolCalls: number;
    publishedVersion: number;
    revision: number;
    updatedAt: string;
  }>;
  components: ComponentFixture[];
  providers: Array<{
    providerKey: string;
    displayName: string;
    endpoint: string;
    configured: boolean;
    maskedCredential: string;
    status: string;
  }>;
  runs: unknown[];
};

const snapshot: WorkbenchFixture = {
  overview: { agentCount: 1, platformStatus: 'NEEDS_CONFIGURATION', availableComponents: 2, configuredProviders: 0, runCount: 0 },
  agents: [{
    agentKey: 'fitness.coach', name: '花爷健身教练', description: '陪伴用户完成训练与饮食管理', status: 'DRAFT',
    frameworkKey: 'framework.agentscope', providerKey: 'provider.bailian', modelKey: 'model.qwen-plus',
    promptKey: 'prompt.fitness-coach', toolKeys: [], skillKeys: [], hookKeys: [], memoryKey: 'memory.session',
    temperature: 0.6, maxToolCalls: 8, publishedVersion: 0, revision: 1, updatedAt: '2026-08-06T01:00:00Z',
  }],
  components: [
    { type: 'FRAMEWORK', componentKey: 'framework.agentscope', displayName: 'AgentScope', description: 'Agent 运行框架适配器', version: 1, status: 'AVAILABLE', tags: ['Java'], config: {} },
    { type: 'MODEL', componentKey: 'model.qwen-plus', displayName: '通义千问 Plus', description: '通用对话模型', version: 1, status: 'AVAILABLE', tags: ['文本'], config: {} },
    { type: 'TOOL', componentKey: 'tool.fitness.plan', displayName: '健身计划工具', description: '读取和生成训练计划', version: 1, status: 'UNAVAILABLE', tags: ['健身'], config: { reason: '等待 Fitness Tool Bean 接线' } },
  ],
  providers: [{ providerKey: 'provider.bailian', displayName: '阿里云百炼', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1', configured: false, maskedCredential: '', status: 'AVAILABLE' }],
  runs: [],
};

function independentRoutes(source: WorkbenchFixture = snapshot): Record<string, unknown> {
  const component = (type: string) => source.components.filter((item) => item.type === type);
  return {
    '/api/admin/agents': source.agents,
    '/api/admin/providers': source.providers.map((item) => ({ ...item, protocol: 'OPENAI_COMPATIBLE', status: item.status === 'AVAILABLE' ? 'ACTIVE' : item.status, revision: 1, updatedAt: '2026-08-06T01:00:00Z' })),
    '/api/admin/models': component('MODEL').map((item) => ({ modelKey: item.componentKey, providerKey: String(item.config.providerKey ?? source.agents[0]?.providerKey ?? ''), modelId: item.componentKey.replace(/^model\./, ''), displayName: item.displayName, description: item.description, supportsStreaming: true, supportsToolCalling: true, supportsVision: false, status: item.status === 'AVAILABLE' ? 'ACTIVE' : 'DISABLED', revision: item.version, updatedAt: '2026-08-06T01:00:00Z' })),
    '/api/admin/prompts': component('PROMPT').map((item) => ({ promptKey: item.componentKey, displayName: item.displayName, description: item.description, template: String(item.config.template ?? ''), status: item.status === 'AVAILABLE' ? 'ACTIVE' : 'DISABLED', revision: item.version, updatedAt: '2026-08-06T01:00:00Z' })),
    '/api/admin/tools': component('TOOL').map((item) => ({ toolKey: item.componentKey, contractVersion: item.version, runtimeName: item.componentKey, displayName: item.displayName, description: item.description, whenToUse: '', whenNotToUse: '', sideEffect: 'READ_ONLY', riskLevel: 'LOW', requiredScopes: [], inputSchema: {}, outputSchema: {} })),
    '/api/admin/skills': component('SKILL').map((item) => ({ skillKey: item.componentKey, displayName: item.displayName, description: item.description, whenToUse: String(item.config.whenToUse ?? ''), whenNotToUse: String(item.config.whenNotToUse ?? ''), content: '', requiredToolKeys: item.config.requiredTools ?? [], runtimeReady: item.status === 'AVAILABLE', status: item.status === 'AVAILABLE' ? 'ACTIVE' : 'DISABLED', revision: item.version, updatedAt: '2026-08-06T01:00:00Z' })),
    '/api/admin/hooks': component('HOOK').map((item) => ({ hookKey: item.componentKey, displayName: item.displayName, description: item.description, phase: 'BEFORE_TOOL', mandatory: Boolean(item.config.mandatory), runtimeReady: item.status === 'AVAILABLE', status: item.status === 'AVAILABLE' ? 'ACTIVE' : 'DISABLED', revision: item.version, updatedAt: '2026-08-06T01:00:00Z' })),
    '/api/admin/frameworks': component('FRAMEWORK').map((item) => ({ frameworkKey: item.componentKey, displayName: item.displayName, description: item.description, capabilities: item.config, status: item.status === 'AVAILABLE' ? 'ACTIVE' : 'DISABLED', revision: item.version, updatedAt: '2026-08-06T01:00:00Z' })),
    '/api/admin/memories': component('MEMORY').map((item) => ({ memoryKey: item.componentKey, displayName: item.displayName, description: item.description, retentionHours: 24, maxTokens: 8000, status: item.status === 'AVAILABLE' ? 'ACTIVE' : 'DISABLED', revision: item.version, updatedAt: '2026-08-06T01:00:00Z' })),
    '/api/admin/runs': { items: source.runs, totalElements: source.runs.length, totalPages: source.runs.length ? 1 : 0, page: 0, size: 4 },
  };
}

function mockFetch(overrides: Record<string, unknown> = {}) {
  const legacySnapshot = overrides['/api/admin/workbench'] as typeof snapshot | undefined;
  const source = legacySnapshot ?? snapshot;
  const routes: Record<string, unknown> = {
    '/api/admin/auth/session': { username: 'admin' },
    ...independentRoutes(source),
    ...overrides,
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const path = url.startsWith('/api/admin/runs?') ? '/api/admin/runs' : url;
    const method = init?.method ?? 'GET';
    const payload = routes[`${method} ${path}`] ?? routes[path] ?? routes[url];
    if (payload !== undefined) return new Response(JSON.stringify(payload), { status: 200 });
    return new Response('{}', { status: 200 });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AdminWorkbench />
    </MemoryRouter>,
  );
}

describe('AdminWorkbench', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it('renders the operational overview from the database snapshot without fake success data', async () => {
    mockFetch();
    renderAt('/admin');

    expect(await screen.findByRole('heading', { name: 'Agent 工作台' })).toBeInTheDocument();
    expect(screen.getByText('花爷健身教练')).toBeInTheDocument();
    expect(screen.getByText('尚未配置运行依赖')).toBeInTheDocument();
    expect(screen.getByText('暂无真实 Run')).toBeInTheDocument();
    expect(screen.getByText('可用能力')).toBeInTheDocument();
  });

  it('presents Agent drafts as scannable configuration cards instead of a sparse table', async () => {
    mockFetch();
    renderAt('/admin/agents');

    expect(await screen.findByRole('heading', { name: 'Agent' })).toBeInTheDocument();
    expect(screen.getByText('花爷健身教练')).toBeInTheDocument();
    expect(screen.getByText('陪伴用户完成训练与饮食管理')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /进入配置/ })).toHaveAttribute('href', '/admin/agents/fitness.coach');
  });

  it('creates a persisted Agent draft from the Agent list instead of showing a placeholder action', async () => {
    const created = { ...snapshot.agents[0], agentKey: 'baby.food.coach', name: '辅食助手', description: '安排家庭辅食', revision: 1 };
    const fetchMock = mockFetch({ '/api/admin/agents': created });
    const user = userEvent.setup();
    renderAt('/admin/agents/new');

    await user.type(await screen.findByLabelText('Agent Key'), 'baby.food.coach');
    await user.type(screen.getByLabelText('新 Agent 名称'), '辅食助手');
    await user.type(screen.getByLabelText('新 Agent 说明'), '安排家庭辅食');
    expect(screen.getByLabelText('Agent Key')).toHaveValue('baby.food.coach');
    expect(screen.getByLabelText('新 Agent 名称')).toHaveValue('辅食助手');
    expect(screen.getByLabelText('新 Agent 说明')).toHaveValue('安排家庭辅食');
    fireEvent.submit(screen.getByLabelText('新建 Agent').querySelector('form')!);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/agents',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ agentKey: 'baby.food.coach', name: '辅食助手', description: '安排家庭辅食' }) }),
    ));
  });

  it('opens a dedicated page for creating an Agent instead of expanding a form inside the list', async () => {
    mockFetch();
    renderAt('/admin/agents');

    expect(await screen.findByRole('link', { name: /新建 Agent/ })).toHaveAttribute('href', '/admin/agents/new');
    expect(screen.queryByRole('region', { name: '新建 Agent' })).not.toBeInTheDocument();

    renderAt('/admin/agents/new');
    expect(await screen.findByRole('heading', { name: '创建 Agent' })).toBeInTheDocument();
    expect(screen.getByLabelText('Agent Key')).toBeInTheDocument();
  });

  it('searches paged conversations and shows all conversation identities', async () => {
    const conversation = {
      conversationId: 'conversation-1', userId: 'user-1', username: 'trace-alice', agentKey: 'fitness.coach', title: '明天怎么训练', status: 'ACTIVE',
      startedAt: '2026-08-10T08:00:00Z', lastMessageAt: '2026-08-10T08:01:00Z', messageCount: 2, runCount: 1,
    };
    const fetchMock = mockFetch({
      '/api/admin/traces/conversations?page=0&size=10': { items: [conversation], page: 0, size: 10, hasNext: true },
      '/api/admin/traces/conversations?query=alice&page=0&size=10': { items: [conversation], page: 0, size: 10, hasNext: true },
      '/api/admin/traces/conversations?query=alice&page=1&size=10': { items: [], page: 1, size: 10, hasNext: false },
      '/api/admin/traces/conversations/conversation-1': {
        conversation,
        messages: [
          { messageId: 'm1', conversationId: 'conversation-1', runId: 'run-1', role: 'USER', content: '明天怎么训练？', createdAt: '2026-08-10T08:00:00Z' },
          { messageId: 'm2', conversationId: 'conversation-1', runId: 'run-1', role: 'ASSISTANT', content: '## 全身训练\n- 深蹲 4 组', createdAt: '2026-08-10T08:01:00Z' },
        ],
        runs: [],
      },
    });
    const user = userEvent.setup();
    renderAt('/admin/traces');

    expect(await screen.findByRole('link', { name: 'Trace' })).toHaveAttribute('href', '/admin/traces');
    expect(screen.getByRole('heading', { name: '会话 Trace' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /明天怎么训练/ })).toBeInTheDocument();
    expect(screen.getAllByText('trace-alice').length).toBeGreaterThan(0);
    expect(screen.getAllByText('user-1').length).toBeGreaterThan(0);
    expect(screen.getAllByText('conversation-1').length).toBeGreaterThan(0);
    expect(screen.getByText('第 1 页')).toBeInTheDocument();

    const searchbox = screen.getByRole('searchbox', { name: '搜索会话' });
    await user.type(searchbox, 'alice');
    await user.click(screen.getByRole('button', { name: '搜索' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/traces/conversations?query=alice&page=0&size=10', expect.anything(),
    ));
    await waitFor(() => expect(screen.getByRole('button', { name: '下一页' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: '下一页' }));
    expect(await screen.findByText('未找到匹配会话')).toBeInTheDocument();
    expect(screen.getByText('第 2 页')).toBeInTheDocument();

    await user.clear(searchbox);
    await user.click(screen.getByRole('button', { name: '搜索' }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(
      ([input]) => String(input) === '/api/admin/traces/conversations?page=0&size=10',
    )).toHaveLength(2));
    expect(await screen.findByRole('button', { name: /明天怎么训练/ })).toBeInTheDocument();
  });

  it('opens model creation in a modal with compact capability declarations', async () => {
    mockFetch();
    const user = userEvent.setup();
    renderAt('/admin/models');

    await user.click(await screen.findByRole('button', { name: '新增模型' }));
    expect(screen.getByRole('dialog', { name: '新增模型' })).toBeInTheDocument();
    expect(screen.queryByText('支持流式输出')).not.toBeInTheDocument();
    expect(screen.getByText('模型能力声明')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '取消' })).toHaveClass('admin-secondary');
    expect(screen.getByRole('button', { name: '保存模型' })).toHaveClass('admin-primary');
  });

  it('creates prompts and skills from resource modals', async () => {
    const prompt = { promptKey: 'custom.prompt', displayName: '自定义提示词', description: '页面创建', template: '你好', status: 'ACTIVE', revision: 1, updatedAt: '2026-08-11T00:00:00Z' };
    const skill = { skillKey: 'custom.skill', displayName: '自定义技能', description: '页面创建', whenToUse: '需要时', whenNotToUse: '', content: '先读取资料。', requiredToolKeys: [], runtimeReady: true, status: 'ACTIVE', revision: 1, updatedAt: '2026-08-11T00:00:00Z' };
    const fetchMock = mockFetch({ 'POST /api/admin/prompts': prompt, 'POST /api/admin/skills': skill });
    const user = userEvent.setup();

    const promptView = renderAt('/admin/prompts');
    await user.click(await screen.findByRole('button', { name: '新增提示词' }));
    await user.type(screen.getByLabelText('Prompt Key'), 'custom.prompt');
    await user.type(screen.getByLabelText('提示词名称'), '自定义提示词');
    await user.type(screen.getByLabelText('提示词模板'), '你好');
    await user.click(screen.getByRole('button', { name: '保存提示词' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/admin/prompts', expect.objectContaining({ method: 'POST' })));
    promptView.unmount();

    renderAt('/admin/skills');
    await user.click(await screen.findByRole('button', { name: '新增技能' }));
    expect(screen.getByRole('dialog', { name: '新增技能' })).toBeInTheDocument();
    expect(screen.getByLabelText('Skill Key')).toBeInTheDocument();
  });

  it('renders a Run Trace as a conversation with collapsed execution details', async () => {
    mockFetch({
      '/api/admin/runs/run-1': {
        runId: 'run-1', agentKey: 'fitness.coach', agentVersion: 1, status: 'SUCCEEDED', startedAt: '2026-08-10T08:00:00Z',
        completedAt: '2026-08-10T08:00:02Z', durationMs: 2000, toolCalls: 1, promptTokens: 20, completionTokens: 30,
        costUsd: 0, modelKey: 'MiniMax-M3', frameworkKey: 'agentscope', errorCode: null, errorMessage: null,
        inputSummary: '明天怎么训练？', outputSummary: '## 全身训练\n- 深蹲 4 组',
        events: [
          { sequence: 1, type: 'TOOL_STARTED', title: '调用 Tool', detail: 'fitness.exercise.search', payload: {}, occurredAt: '2026-08-10T08:00:00Z' },
          { sequence: 2, type: 'TOOL_COMPLETED', title: 'Tool 返回', detail: 'fitness.exercise.search', payload: {}, occurredAt: '2026-08-10T08:00:01Z' },
          { sequence: 3, type: 'TOKEN', title: 'model output', detail: '重复正文', payload: {}, occurredAt: '2026-08-10T08:00:02Z' },
        ],
      },
    });
    renderAt('/admin/runs/run-1?from=trace');

    expect(await screen.findByText('明天怎么训练？')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '全身训练' })).toBeInTheDocument();
    expect(screen.getByText('执行过程')).toBeInTheDocument();
    expect(screen.queryByText('事件时间轴')).not.toBeInTheDocument();
    expect(screen.queryByText('重复正文')).not.toBeInTheDocument();
  });

  it('shows an error screen with retry when the workbench is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('network down'); }));
    renderAt('/admin');

    expect(await screen.findByText('工作台暂时无法打开')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新连接' })).toBeInTheDocument();
  });

  it('shows developer login when the administrator session is absent', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/admin/auth/session') {
        return new Response(JSON.stringify({ detail: 'Administrator authentication required', code: 'UNAUTHORIZED' }), { status: 401 });
      }
      return new Response('{}', { status: 200 });
    }));
    renderAt('/admin');

    expect(await screen.findByRole('heading', { name: '开发者登录' })).toBeInTheDocument();
    expect(screen.queryByText('前往移动端登录')).not.toBeInTheDocument();
  });

  it('keeps Tool metadata read-only and omits framework and memory menus', async () => {
    mockFetch();
    const user = userEvent.setup();
    renderAt('/admin/tools');

    expect(screen.queryByRole('link', { name: '框架' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '记忆' })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: /健身计划工具/ }));
    expect(screen.getByText('运行时注册能力')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /保存/ })).not.toBeInTheDocument();
  });

  it('edits a Skill with explicit trigger rules and a dirty save bar', async () => {
    mockFetch({
      '/api/admin/workbench': {
        ...snapshot,
        components: [
          ...snapshot.components,
          { type: 'SKILL', componentKey: 'fitness.plan.skill', displayName: '训练计划编排', description: '组合目标和训练记录。', version: 1, status: 'AVAILABLE', tags: ['计划'], config: { requiredTools: ['tool.fitness.plan'] } },
        ],
      },
      '/api/admin/skills/fitness.plan.skill': { skillKey: 'fitness.plan.skill', displayName: '训练计划编排', description: '组合目标和训练记录。', whenToUse: '需要新计划时', whenNotToUse: '', content: '', requiredToolKeys: ['tool.fitness.plan'], runtimeReady: true, status: 'ACTIVE', revision: 2, updatedAt: '2026-08-06T01:00:00Z' },
    });
    const user = userEvent.setup();
    renderAt('/admin/skills');

    await user.click(await screen.findByRole('button', { name: /训练计划编排/ }));
    await user.type(screen.getByLabelText('何时使用'), ' 用户要求制定计划');
    expect(screen.getByText('有未保存的修改')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '保存' }));
    expect(await screen.findByText('技能配置已保存')).toBeInTheDocument();
  });

  it('shows an unavailable required Tool in a Skill so it can be removed', async () => {
    mockFetch({
      '/api/admin/workbench': {
        ...snapshot,
        components: [
          ...snapshot.components,
          { type: 'SKILL', componentKey: 'fitness.plan.skill', displayName: '训练计划编排', description: '组合目标和训练记录。', version: 1, status: 'AVAILABLE', tags: ['计划'], config: { requiredTools: ['fitness.plan.generate'] } },
        ],
      },
    });
    const user = userEvent.setup();
    renderAt('/admin/skills');

    await user.click(await screen.findByRole('button', { name: /训练计划编排/ }));
    const staleTool = await screen.findByRole('button', { name: /未登记工具：fitness\.plan\.generate/ });
    expect(staleTool).toHaveClass('is-unavailable');

    await user.click(staleTool);
    expect(screen.queryByRole('button', { name: /未登记工具：fitness\.plan\.generate/ })).not.toBeInTheDocument();
  });

  it('keeps the mandatory safety Hook selected in the Agent editor', async () => {
    mockFetch({
      '/api/admin/workbench': {
        ...snapshot,
        agents: [{ ...snapshot.agents[0], hookKeys: ['fitness.safety'] }],
        components: [
          ...snapshot.components,
          { type: 'HOOK', componentKey: 'fitness.safety', displayName: '健身安全护栏', description: '急症先拦截', version: 1, status: 'AVAILABLE', tags: ['安全'], config: { mandatory: true } },
        ],
      },
    });
    renderAt('/admin/agents/fitness.coach');

    const safetyHook = await screen.findByRole('button', { name: /健身安全护栏/ });
    expect(safetyHook).toHaveAttribute('aria-pressed', 'true');
    expect(safetyHook).toBeDisabled();
  });

  it('shows a selected unavailable Tool so an outdated draft binding can be removed', async () => {
    mockFetch({
      '/api/admin/workbench': {
        ...snapshot,
        agents: [{ ...snapshot.agents[0], toolKeys: ['tool.fitness.plan', 'fitness.plan.generate'] }],
      },
    });
    const user = userEvent.setup();
    renderAt('/admin/agents/fitness.coach');

    const staleTool = await screen.findByRole('button', { name: /未登记工具：fitness\.plan\.generate/ });
    expect(staleTool).toHaveAttribute('aria-pressed', 'true');

    await user.click(staleTool);
    expect(screen.queryByRole('button', { name: /未登记工具：fitness\.plan\.generate/ })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /工具1 已选择/ })).toBeInTheDocument();
  });

  it('does not inject a fitness safety Hook into a generic Agent', async () => {
    mockFetch({
      '/api/admin/workbench': {
        ...snapshot,
        agents: [{ ...snapshot.agents[0], agentKey: 'baby.food', name: '辅食助手', hookKeys: [] }],
        components: [
          ...snapshot.components,
          { type: 'HOOK', componentKey: 'fitness.safety', displayName: '健身安全护栏', description: '急症先拦截', version: 1, status: 'AVAILABLE', tags: ['安全'], config: { mandatory: true } },
        ],
      },
    });
    renderAt('/admin/agents/baby.food');

    const safetyHook = await screen.findByRole('button', { name: /健身安全护栏/ });
    expect(safetyHook).toHaveAttribute('aria-pressed', 'false');
    expect(safetyHook).toBeEnabled();
  });

  it('makes the Agent system prompt visible and links to its maintenance page', async () => {
    mockFetch({
      '/api/admin/workbench': {
        ...snapshot,
        agents: [{ ...snapshot.agents[0], agentKey: 'baby.food', name: '辅食助手', promptKey: 'agent.default.prompt' }],
        components: [
          ...snapshot.components,
          { type: 'PROMPT', componentKey: 'agent.default.prompt', displayName: '通用系统提示词', description: '通用助手指令', version: 1, status: 'AVAILABLE', tags: ['通用'], config: { template: '你是一个可靠、清晰的通用 AI 助手。' } },
          { type: 'MEMORY', componentKey: 'memory.session', displayName: '默认会话记忆', description: '保存近期上下文', version: 1, status: 'AVAILABLE', tags: ['会话'], config: {} },
        ],
      },
    });
    renderAt('/admin/agents/baby.food');

    expect(await screen.findByLabelText('系统提示词')).toHaveValue('agent.default.prompt');
    expect(screen.getByText('当前系统提示词')).toBeInTheDocument();
    expect(screen.getByText('你是一个可靠、清晰的通用 AI 助手。')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '维护系统提示词' })).toHaveAttribute('href', '/admin/prompts');
  });

  it('saves a provider credential without echoing the secret', async () => {
    const fetchMock = mockFetch({
      '/api/admin/providers/provider.bailian/credential': { ...snapshot.providers[0], configured: true, maskedCredential: '••••••••' },
    });
    const user = userEvent.setup();
    renderAt('/admin/providers');

    await user.type(await screen.findByLabelText('阿里云百炼 API Key'), 'sk-test-secret');
    await user.click(screen.getByRole('button', { name: /保存密钥/ }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/providers/provider.bailian/credential',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify({ apiKey: 'sk-test-secret' }) }),
    ));
    expect(screen.queryByDisplayValue('sk-test-secret')).not.toBeInTheDocument();
    expect(await screen.findByText('密钥已加密保存')).toBeInTheDocument();
  });

  it('locks the playground until runtime dependencies are ready', async () => {
    mockFetch();
    renderAt('/admin/playground');

    expect(await screen.findByRole('heading', { name: /调试台/ })).toBeInTheDocument();
    expect(await screen.findByText('还有依赖未完成')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('完成前置准备后解锁')).toBeDisabled();
  });

  it('locks the playground when the published model is bound to another Provider', async () => {
    mockFetch({
      '/api/admin/workbench': {
        ...snapshot,
        agents: [{ ...snapshot.agents[0], publishedVersion: 4, status: 'ACTIVE' }],
        providers: [{ ...snapshot.providers[0], configured: true, maskedCredential: '••••••••' }],
        components: snapshot.components.map((item) => item.type === 'MODEL'
          ? { ...item, status: 'AVAILABLE', config: { providerKey: 'provider.other' } }
          : { ...item, status: 'AVAILABLE' }),
      },
    });
    renderAt('/admin/playground');

    expect(await screen.findByText(/模型未绑定当前 Provider/)).toBeInTheDocument();
    expect(screen.getByPlaceholderText('完成前置准备后解锁')).toBeDisabled();
  });

  it('lists every published Agent and streams the selected Agent key', async () => {
    const readySnapshot = {
      ...snapshot,
      agents: [
        { ...snapshot.agents[0], publishedVersion: 4, status: 'ACTIVE' },
        {
          ...snapshot.agents[0], agentKey: 'baby.food', name: '辅食助手', description: '为家庭提供辅食安排建议',
          promptKey: 'agent.default.prompt', publishedVersion: 2, status: 'ACTIVE',
        },
      ],
      providers: [{ ...snapshot.providers[0], configured: true, maskedCredential: '••••' }],
      components: snapshot.components.map((item) => item.type === 'MODEL'
        ? { ...item, status: 'AVAILABLE', config: { providerKey: 'provider.bailian' } }
        : { ...item, status: 'AVAILABLE' }),
    };
    const readyRoutes = independentRoutes(readySnapshot);
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/admin/auth/session') return new Response(JSON.stringify({ username: 'admin' }), { status: 200 });
      if (readyRoutes[url] !== undefined) return new Response(JSON.stringify(readyRoutes[url]), { status: 200 });
      if (url === '/api/v1/admin/playground/runs' && init?.method === 'POST') {
        return new Response(JSON.stringify({ runId: 'run-1', status: 'RUNNING' }), { status: 202 });
      }
      if (url === '/api/v1/admin/playground/runs/run-1/events') {
        const events = [
          { type: 'RUN_STATE', data: { status: 'RUNNING', summary: '正在整理建议' } },
          { type: 'TEXT_DELTA', data: { messageId: 'run-1', delta: '今天也要好好吃饭。' } },
          { type: 'STRUCTURED_COMPONENT', data: { block: { kind: 'CONFIRMATION', confirmationId: 'approval-admin', title: '保存计划', message: '保存调试计划？', confirmLabel: '保存', cancelLabel: '取消' } } },
          { type: 'COMPLETED', data: { status: 'SUCCEEDED' } },
        ];
        return new Response(events.map((event, index) => `id: ${index + 1}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join(''), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
      }
      if (url === '/api/v1/admin/playground/runs/run-1/approvals/approval-admin' && init?.method === 'POST') {
        return new Promise<Response>(() => undefined);
      }
      return new Response('{}', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/admin/playground');

    expect(await screen.findByRole('option', { name: /花爷健身教练/ })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('调试 Agent'), 'baby.food');
    expect(screen.getByRole('option', { name: /辅食助手/ })).toBeInTheDocument();
    const input = await screen.findByPlaceholderText('请输入测试问题');
    await user.type(input, '晚饭吃什么');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    expect(await screen.findByText('今天也要好好吃饭。')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: '保存' }));
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/playground/runs',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ agentKey: 'baby.food', target: { kind: 'PUBLISHED_VERSION', revision: 1 }, input: '晚饭吃什么', businessUserId: '00000000-0000-0000-0000-000000000000' }),
      }),
    );
  });
});
