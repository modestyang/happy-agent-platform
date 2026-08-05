import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';

describe('App', () => {
  const dashboard = {
    user: { username: '小秦', preferences: { trainingDays: 4, style: '直接、有分寸' } },
    goal: { id: 'goal-1', currentWeight: 62.4, targetWeight: 58, progress: 46, deadline: '2026-09-20' },
    bodyRecords: [{ weight: 62.4, recordedAt: '2026-08-05' }],
    meals: [{ name: '鸡胸肉藜麦碗', calories: 468, mealType: '午餐' }],
    plan: { id: 'plan-1', title: '上肢力量 · B', durationMinutes: 42, totalSets: 12, exercises: [{ id: 'squat', name: '哑铃深蹲', sets: 4, reps: 10, steps: ['站稳，核心收紧', '髋部向后坐', '脚跟发力站起'], mistakes: ['膝盖内扣', '弓背借力'], voiceCue: '吸气下蹲，脚跟发力站起。' }] },
    exercises: [{ id: 'squat', name: '哑铃深蹲', category: '下肢', summary: '臀腿基础力量' }],
    report: { conclusion: '体重趋势稳定下降', evidence: '近 14 天下降 0.8kg', action: '本周保持 4 次训练与晚餐蛋白质。' },
    ai: { configured: false, messages: [] },
  };

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.replaceState({}, '', '/');
  });

  function mockFetch(overrides: Record<string, unknown> = {}) {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/app/bootstrap') return new Response(JSON.stringify(dashboard), { status: 200 });
      if (path === '/api/local/login') return new Response(JSON.stringify({ username: '小秦' }), { status: 200 });
      if (path === '/api/app/ai/messages') return new Response(JSON.stringify({ code: 'DEPENDENCY_NOT_CONFIGURED' }), { status: 503 });
      if (overrides[path] !== undefined) return new Response(JSON.stringify(overrides[path]), { status: 200 });
      return new Response('{}', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  it('shows the bootstrap home with the four entry blocks and tab navigation', async () => {
    mockFetch();
    render(<App />);

    expect(await screen.findByText('62.4')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /今日训练/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /今日饮食/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /我要记录/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /我的报告/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '计划' })).toBeInTheDocument();
  });

  it('logs in with the supplied credentials then loads the app', async () => {
    const fetchMock = mockFetch();
    fetchMock.mockImplementationOnce(async () => new Response('{}', { status: 401 }));
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText('用户名'), 'qin');
    await user.type(screen.getByLabelText('密码'), 'secret');
    await user.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/local/login', expect.objectContaining({ method: 'POST', body: JSON.stringify({ username: 'qin', password: 'secret' }) })));
    expect(await screen.findByText('62.4')).toBeInTheDocument();
  });

  it('submits body and meal records from the one-layer record drawer', async () => {
    const fetchMock = mockFetch();
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('62.4');
    await user.click(screen.getByRole('button', { name: /我要记录/ }));
    await user.clear(screen.getByLabelText('体重 (kg)'));
    await user.type(screen.getByLabelText('体重 (kg)'), '61.8');
    await user.click(screen.getByRole('button', { name: '保存身材记录' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/app/body-records', expect.objectContaining({ method: 'POST', body: JSON.stringify({ weight: 61.8 }) })));

    await user.click(await screen.findByRole('button', { name: /我要记录/ }));
    await user.click(screen.getByRole('button', { name: '饮食记录' }));
    await user.type(screen.getByLabelText('吃了什么'), '牛肉面');
    await user.click(screen.getByRole('button', { name: '保存饮食记录' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/app/meals', expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: '牛肉面', calories: 0 }) })));
  });

  it('completes a workout and makes AI dependency failures explicit', async () => {
    const fetchMock = mockFetch();
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('62.4');
    await user.click(screen.getByRole('link', { name: '计划' }));
    await user.click(await screen.findByRole('button', { name: '完成本次训练' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/app/workouts/plan-1/complete', expect.objectContaining({ method: 'POST' })));

    await user.click(screen.getByRole('link', { name: 'AI花爷' }));
    await user.click(await screen.findByRole('button', { name: /今天怎么练/ }));
    expect(await screen.findByText(/AI 服务尚未配置/)).toBeInTheDocument();
  });

  it('navigates to the exercise library and exposes its four-step detail', async () => {
    mockFetch();
    render(<App />);
    await screen.findByText('62.4');
    fireEvent.click(screen.getByRole('link', { name: '动作库' }));
    await userEvent.setup().click(await screen.findByRole('button', { name: /哑铃深蹲/ }));

    expect(screen.getByText('动作步骤 1')).toBeInTheDocument();
    expect(screen.getByText('动作步骤 4')).toBeInTheDocument();
  });
});
