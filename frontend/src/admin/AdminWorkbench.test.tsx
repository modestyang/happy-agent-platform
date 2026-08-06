import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AdminWorkbench } from './AdminWorkbench';

const snapshot = {
  overview: { agentCount: 1, platformStatus: 'NEEDS_CONFIGURATION', availableComponents: 5, configuredProviders: 0, runCount: 0 },
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

function mockFetch() {
  const revised = { ...snapshot.agents[0], name: '花爷健身教练', revision: 2 };
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/admin/workbench') return new Response(JSON.stringify(snapshot), { status: 200 });
    if (path === '/api/admin/agents/fitness.coach/draft') return new Response(JSON.stringify(revised), { status: 200 });
    if (path === '/api/admin/agents/fitness.coach/validate') return new Response(JSON.stringify({ valid: false, errors: ['Provider 阿里云百炼尚未配置凭据'], warnings: [] }), { status: 200 });
    if (path === '/api/admin/providers/provider.bailian/credential') return new Response(JSON.stringify({ ...snapshot.providers[0], configured: true, maskedCredential: '••••••••' }), { status: 200 });
    return new Response('{}', { status: 200 });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('AdminWorkbench', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it('renders a database-backed operational overview without fake success data', async () => {
    mockFetch();
    render(<AdminWorkbench />);

    expect(await screen.findByRole('heading', { name: 'Agent 工作台' })).toBeInTheDocument();
    expect(screen.getByText('瘦瘦健身教练')).toBeInTheDocument();
    expect(screen.getByText('需要完成配置')).toBeInTheDocument();
    expect(screen.getByText('暂无运行记录')).toBeInTheDocument();
  });

  it('edits a draft with optimistic concurrency and shows validation blockers', async () => {
    const fetchMock = mockFetch();
    const user = userEvent.setup();
    render(<AdminWorkbench />);

    await user.click(await screen.findByRole('button', { name: 'Agent 配置' }));
    const input = screen.getByLabelText('Agent 名称');
    await user.clear(input);
    await user.type(input, '花爷健身教练');
    await user.click(screen.getByRole('button', { name: '保存草稿' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/admin/agents/fitness.coach/draft', expect.objectContaining({ method: 'PATCH', headers: expect.objectContaining({ 'Content-Type': 'application/json', 'If-Match': '1' }) })));
    await user.click(screen.getByRole('button', { name: '检查发布条件' }));
    expect(await screen.findByText('Provider 阿里云百炼尚未配置凭据')).toBeInTheDocument();
  });

  it('filters the component catalog and exposes unavailable reasons', async () => {
    mockFetch();
    const user = userEvent.setup();
    render(<AdminWorkbench />);

    await user.click(await screen.findByRole('button', { name: '组件中心' }));
    await user.click(screen.getByRole('button', { name: '工具1' }));
    const catalog = screen.getByRole('region', { name: '组件目录' });
    expect(within(catalog).getByText('健身计划工具')).toBeInTheDocument();
    expect(within(catalog).getByText('等待 Fitness Tool Bean 接线')).toBeInTheDocument();
    expect(within(catalog).queryByText('AgentScope')).not.toBeInTheDocument();
  });

  it('saves a provider credential without echoing the secret', async () => {
    const fetchMock = mockFetch();
    const user = userEvent.setup();
    render(<AdminWorkbench />);

    await user.click(await screen.findByRole('button', { name: '模型服务' }));
    await user.type(screen.getByLabelText('API Key'), 'sk-test-secret');
    await user.click(screen.getByRole('button', { name: '保存密钥' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/admin/providers/provider.bailian/credential', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ apiKey: 'sk-test-secret' }) })));
    expect(screen.queryByDisplayValue('sk-test-secret')).not.toBeInTheDocument();
    expect(await screen.findByText('密钥已安全保存')).toBeInTheDocument();
  });
});
