import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AdminWorkbench } from './AdminWorkbench';

if (!Element.prototype.scrollTo) {
  Element.prototype.scrollTo = () => {};
}

const snapshot = {
  overview: { agentCount: 1, platformStatus: 'NEEDS_CONFIGURATION', availableComponents: 2, configuredProviders: 0, runCount: 0 },
  agents: [{
    agentKey: 'fitness.coach', name: '瘦瘦健身教练', description: '陪伴用户完成训练与饮食管理', status: 'DRAFT',
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

function mockFetch(overrides: Record<string, unknown> = {}) {
  const routes: Record<string, unknown> = {
    '/api/admin/workbench': snapshot,
    ...overrides,
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const path = url.startsWith('/api/admin/runs?') ? '/api/admin/runs' : url;
    const payload = routes[path] ?? routes[url];
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
    expect(screen.getByText('瘦瘦健身教练')).toBeInTheDocument();
    expect(screen.getByText('尚未配置运行依赖')).toBeInTheDocument();
    expect(screen.getByText('暂无真实 Run')).toBeInTheDocument();
    expect(screen.getByText('等待 Fitness Tool Bean 接线')).toBeInTheDocument();
  });

  it('shows an error screen with retry when the workbench is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('network down'); }));
    renderAt('/admin');

    expect(await screen.findByText('工作台暂时无法打开')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重新连接' })).toBeInTheDocument();
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

  it('saves a provider credential without echoing the secret', async () => {
    const fetchMock = mockFetch({
      '/api/admin/providers/provider.bailian/credential': { ...snapshot.providers[0], configured: true, maskedCredential: '••••••••' },
    });
    const user = userEvent.setup();
    renderAt('/admin/providers');

    await user.type(await screen.findByLabelText('API Key'), 'sk-test-secret');
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

  it('clears the model detail while the skills catalog is loading', async () => {
    let snapshotRequests = 0;
    let resolveSkillsSnapshot: ((value: Response) => void) | undefined;
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input) !== '/api/admin/workbench') return Promise.resolve(new Response('{}', { status: 200 }));
      snapshotRequests += 1;
      if (snapshotRequests < 3) return Promise.resolve(new Response(JSON.stringify(snapshot), { status: 200 }));
      return new Promise<Response>((resolve) => { resolveSkillsSnapshot = resolve; });
    }));
    const user = userEvent.setup();
    renderAt('/admin/models');

    await user.click(await screen.findByRole('button', { name: /通义千问 Plus/ }));
    expect(screen.getByRole('heading', { name: '通义千问 Plus' })).toBeInTheDocument();

    await user.click(screen.getByRole('link', { name: '技能' }));

    expect(await screen.findByText('正在拉取组件目录…')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '通义千问 Plus' })).not.toBeInTheDocument();

    resolveSkillsSnapshot?.(new Response(JSON.stringify(snapshot), { status: 200 }));
    expect(await screen.findByText('选择左侧组件')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '通义千问 Plus' })).not.toBeInTheDocument();
  });

  it('shows loading synchronously when the selected component belongs to the previous route', async () => {
    let snapshotRequests = 0;
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input) !== '/api/admin/workbench') return Promise.resolve(new Response('{}', { status: 200 }));
      snapshotRequests += 1;
      if (snapshotRequests < 3) return Promise.resolve(new Response(JSON.stringify(snapshot), { status: 200 }));
      return new Promise<Response>(() => {});
    }));
    const user = userEvent.setup();
    renderAt('/admin/models');

    await user.click(await screen.findByRole('button', { name: /通义千问 Plus/ }));
    fireEvent.click(screen.getByRole('link', { name: '技能' }));

    expect(screen.getByText('正在拉取组件目录…')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '通义千问 Plus' })).not.toBeInTheDocument();
  });

  it('keeps the skills route when the earlier model catalog request resolves late', async () => {
    let snapshotRequests = 0;
    let resolveModelSnapshot: ((value: Response) => void) | undefined;
    let resolveSkillsSnapshot: ((value: Response) => void) | undefined;
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input) !== '/api/admin/workbench') return Promise.resolve(new Response('{}', { status: 200 }));
      snapshotRequests += 1;
      if (snapshotRequests === 1) return Promise.resolve(new Response(JSON.stringify(snapshot), { status: 200 }));
      return new Promise<Response>((resolve) => {
        if (snapshotRequests === 2) resolveModelSnapshot = resolve;
        else resolveSkillsSnapshot = resolve;
      });
    }));
    renderAt('/admin/models');

    expect(await screen.findByText('正在拉取组件目录…')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('link', { name: '技能' }));
    resolveModelSnapshot?.(new Response(JSON.stringify(snapshot), { status: 200 }));

    await waitFor(() => expect(screen.getByRole('heading', { name: '技能' })).toBeInTheDocument());
    expect(screen.queryByText('通义千问 Plus')).not.toBeInTheDocument();
    resolveSkillsSnapshot?.(new Response(JSON.stringify(snapshot), { status: 200 }));
    expect(await screen.findByText('选择左侧组件')).toBeInTheDocument();
  });

  it('shows the catalog error and retries loading the current route', async () => {
    let snapshotRequests = 0;
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input) !== '/api/admin/workbench') return Promise.resolve(new Response('{}', { status: 200 }));
      snapshotRequests += 1;
      if (snapshotRequests === 1) return Promise.resolve(new Response(JSON.stringify(snapshot), { status: 200 }));
      if (snapshotRequests === 2) return Promise.reject(new TypeError('catalog offline'));
      return Promise.resolve(new Response(JSON.stringify(snapshot), { status: 200 }));
    }));
    const user = userEvent.setup();
    renderAt('/admin/skills');

    expect(await screen.findByText('组件目录加载失败')).toBeInTheDocument();
    expect(screen.getByText('后端服务连接失败，请确认后端服务已启动并可访问')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '重新加载' }));

    expect(await screen.findByText('选择左侧组件')).toBeInTheDocument();
    expect(snapshotRequests).toBe(3);
  });

  it('sends a playground message through the real AI endpoint and renders the reply', async () => {
    const readySnapshot = {
      ...snapshot,
      agents: [{ ...snapshot.agents[0], publishedVersion: 4, status: 'ACTIVE' }],
      providers: [{ ...snapshot.providers[0], configured: true, maskedCredential: '••••' }],
      components: snapshot.components.map((item) => item.type === 'MODEL'
        ? { ...item, status: 'AVAILABLE', config: { providerKey: 'provider.bailian' } }
        : { ...item, status: 'AVAILABLE' }),
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/admin/workbench') return new Response(JSON.stringify(readySnapshot), { status: 200 });
      if (url === '/api/app/ai/messages' && init?.method === 'POST') {
        return new Response(JSON.stringify({ message: '今天也要好好吃饭。' }), { status: 200 });
      }
      return new Response('{}', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderAt('/admin/playground');

    const input = await screen.findByPlaceholderText('请输入测试问题');
    await user.type(input, '晚饭吃什么');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    expect(await screen.findByText('今天也要好好吃饭。')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/app/ai/messages',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ message: '晚饭吃什么' }) }),
    );
  });
});
