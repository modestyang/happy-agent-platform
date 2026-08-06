import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';

describe('App', () => {
  const dashboard = {
    user: { id: 'user-1', nickname: '小秦' },
    goal: { id: 'goal-1', name: '轻盈计划', startWeightJin: 130, currentWeightJin: 124.8, targetWeightJin: 116, status: 'ACTIVE', progressPercent: 46 },
    bodyRecords: [{ id: 'body-1', weightJin: 124.8, waistCm: 70, recordedAt: '2026-08-05' }],
    meals: [{ id: 'meal-1', occurredAt: '2026-08-05T12:00:00Z', mealType: 'LUNCH', items: [{ name: '鸡胸肉藜麦碗', estimatedKcal: 468 }] }],
    mealRecommendations: [
      { id: 'rec-breakfast', recommendationDate: '2026-08-06', mealType: 'BREAKFAST', items: [{ name: '燕麦酸奶莓果杯', estimatedKcal: 360 }], reason: '上午能量更稳定', status: 'READY', generatedAt: '2026-08-06T05:30:00+08:00' },
      { id: 'rec-lunch', recommendationDate: '2026-08-06', mealType: 'LUNCH', items: [{ name: '番茄牛肉荞麦面', estimatedKcal: 480 }, { name: '清炒时蔬', estimatedKcal: 110 }], reason: '午餐保留主食更耐饿', status: 'READY', generatedAt: '2026-08-06T05:30:00+08:00' },
      { id: 'rec-dinner', recommendationDate: '2026-08-06', mealType: 'DINNER', items: [{ name: '香煎鸡胸南瓜碗', estimatedKcal: 420 }], reason: '晚餐清淡但不空腹', status: 'READY', generatedAt: '2026-08-06T05:30:00+08:00' },
    ],
    plan: { id: 'plan-1', title: '上肢力量 · B', estimatedMinutes: 42, status: 'PLANNED', exercises: [{ id: 'squat', name: '哑铃深蹲', targetArea: '下肢', sets: 4, seconds: 40, steps: ['站稳，核心收紧', '髋部向后坐', '脚跟发力站起'], errors: ['膝盖内扣', '弓背借力'] }] },
    exercises: [{ id: 'squat', name: '哑铃深蹲', targetArea: '下肢', sets: 4, seconds: 40, steps: ['站稳，核心收紧'], errors: [], illustrationMode: 'DIAGRAM', imageUrls: [] }],
    completedWorkoutCount: 3,
    report: { status: 'READY', score: 82, conclusion: '体重趋势稳定下降', metrics: [{ label: '近 14 天', value: '下降 1.6 斤', comparison: '较上期更稳定' }], actions: ['本周保持 4 次训练', '晚餐补足蛋白质'] },
    ai: { configured: false, reason: 'DEPENDENCY_NOT_CONFIGURED' },
  };

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.useRealTimers();
    window.localStorage.clear();
    window.history.replaceState({}, '', '/');
  });

  function mockFetch(overrides: Record<string, unknown> = {}) {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (overrides[path] !== undefined) return new Response(JSON.stringify(overrides[path]), { status: 200 });
      if (path === '/api/app/bootstrap') return new Response(JSON.stringify(dashboard), { status: 200 });
      if (path === '/api/local/login') return new Response(JSON.stringify({ username: '小秦' }), { status: 200 });
      if (path === '/api/app/ai/messages') return new Response(JSON.stringify({ code: 'DEPENDENCY_NOT_CONFIGURED' }), { status: 503 });
      return new Response('{}', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  it('shows the bootstrap home with the four entry blocks and tab navigation', async () => {
    vi.setSystemTime(new Date('2026-08-06T10:00:00+08:00'));
    mockFetch();
    render(<App />);

    expect(await screen.findByText('124.8')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '训练' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '饮食' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '饮食' })).toHaveTextContent('午餐');
    expect(screen.getByRole('button', { name: '饮食' })).toHaveTextContent('番茄牛肉荞麦面');
    expect(screen.getByRole('button', { name: '饮食' })).toHaveTextContent('约 590 kcal');
    expect(screen.getByRole('button', { name: '训练' })).toHaveTextContent('上肢力量 · B');
    expect(screen.getByRole('button', { name: '记录' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '报告' })).toBeInTheDocument();
    expect(screen.queryByText('今天的节奏')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '计划' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '今天' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '瘦瘦' })).toHaveClass('nav-link--ai');
    expect(screen.getByRole('link', { name: '动作' })).toBeInTheDocument();
  });

  it('uses the modern sans-serif display stack for the mobile app', async () => {
    mockFetch();
    render(<App />);

    expect(await screen.findByRole('heading', { name: '今天，慢慢变好' })).toBeInTheDocument();
    const loadedCss = readFileSync('src/app.css', 'utf8');
    expect(loadedCss).toContain('Avenir Next');
    expect(loadedCss).not.toMatch(/Hannotate SC|Yuanti SC|FZLanTingHeiS-DB-GB/);
    expect(loadedCss).toContain('letter-spacing: -.025em;');
    expect(loadedCss).toMatch(/\.primary, \.soft-button \{[^}]*font-weight: 700;/);
  });

  it('opens today meal recommendations instead of the meal record drawer', async () => {
    vi.setSystemTime(new Date('2026-08-06T10:00:00+08:00'));
    mockFetch();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('button', { name: '饮食' }));

    expect(await screen.findByRole('heading', { name: '今天吃什么' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: '记录抽屉' })).not.toBeInTheDocument();
    expect(screen.getByText('早餐')).toBeInTheDocument();
    expect(screen.getByText('午餐')).toBeInTheDocument();
    expect(screen.getByText('晚餐')).toBeInTheDocument();
    expect(screen.getByText('下一餐')).toBeInTheDocument();
    expect(screen.getByText('番茄牛肉荞麦面')).toBeInTheDocument();
    expect(screen.getByText('约 590 kcal')).toBeInTheDocument();
  });

  it('shows an honest empty state when today has no meal recommendation', async () => {
    mockFetch({ '/api/app/bootstrap': { ...dashboard, mealRecommendations: [] } });
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    expect(screen.getByRole('button', { name: '饮食' })).toHaveTextContent('今日建议尚未生成');
    await user.click(screen.getByRole('button', { name: '饮食' }));
    expect(await screen.findByRole('heading', { name: '今天还没有饮食建议' })).toBeInTheDocument();
  });

  it('offers AI generation only for a future date without a plan', async () => {
    vi.setSystemTime(new Date('2026-08-06T08:00:00+08:00'));
    const fetchMock = mockFetch();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '计划' }));
    const calendar = await screen.findByRole('region', { name: '本周日期' });
    await user.click(within(calendar).getByRole('button', { name: /周三/ }));
    expect(screen.getByRole('heading', { name: '无训练计划' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /AI 生成训练计划/ })).not.toBeInTheDocument();
    await user.click(within(calendar).getByRole('button', { name: /周五/ }));
    expect(screen.getByRole('link', { name: /AI 生成训练计划/ })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: /AI 生成训练计划/ }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/app/ai/messages', expect.objectContaining({ method: 'POST' })));
    const calls = fetchMock.mock.calls as unknown as [RequestInfo | URL, RequestInit?][];
    const aiCall = calls.find(([path]) => path === '/api/app/ai/messages');
    expect(JSON.parse(String(aiCall?.[1]?.body)).message).toContain('训练计划');
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
    expect(await screen.findByText('124.8')).toBeInTheDocument();
  });

  it('submits body and meal records from the one-layer record drawer', async () => {
    const fetchMock = mockFetch();
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('124.8');
    await user.click(screen.getByRole('button', { name: '记录' }));
    await user.clear(screen.getByLabelText('体重 (斤)'));
    await user.type(screen.getByLabelText('体重 (斤)'), '123.6');
    await user.clear(screen.getByLabelText('腰围 (cm)'));
    await user.type(screen.getByLabelText('腰围 (cm)'), '69');
    await user.click(screen.getByRole('button', { name: '保存身材记录' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/app/body-records', expect.objectContaining({ method: 'POST', body: JSON.stringify({ weightJin: 123.6, waistCm: 69 }) })));

    await user.click(await screen.findByRole('button', { name: '记录' }));
    await user.click(screen.getByRole('button', { name: '饮食记录' }));
    await user.type(screen.getByLabelText('吃了什么'), '牛肉面');
    await user.type(screen.getByLabelText('热量 (kcal)'), '520');
    await user.click(screen.getByRole('button', { name: '保存饮食记录' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/app/meals', expect.objectContaining({ method: 'POST', body: JSON.stringify({ mealType: 'BREAKFAST', items: [{ name: '牛肉面', estimatedKcal: 520 }] }) })));
  });

  it('keeps the record drawer inside the app focus flow and closes it with Escape', async () => {
    mockFetch();
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('124.8');

    const trigger = screen.getByRole('button', { name: '记录' });
    await user.click(trigger);
    expect(screen.getByRole('dialog', { name: '记录抽屉' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '关闭记录' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: '记录抽屉' })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('keeps the current page mounted while refreshing after a record is saved', async () => {
    let bootstrapCalls = 0;
    let resolveRefresh: ((response: Response) => void) | undefined;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/app/bootstrap') {
        bootstrapCalls += 1;
        if (bootstrapCalls === 1) return new Response(JSON.stringify(dashboard), { status: 200 });
        return new Promise<Response>((resolve) => { resolveRefresh = resolve; });
      }
      return new Response('{}', { status: 200 });
    }));
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('button', { name: '记录' }));
    await user.click(screen.getByRole('button', { name: '保存身材记录' }));
    await waitFor(() => expect(bootstrapCalls).toBe(2));

    expect(screen.getByRole('heading', { name: '今天，慢慢变好' })).toBeInTheDocument();
    resolveRefresh?.(new Response(JSON.stringify(dashboard), { status: 200 }));
  });

  it('completes a workout and makes AI dependency failures explicit', async () => {
    const fetchMock = mockFetch();
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '计划' }));
    await user.click(await screen.findByRole('button', { name: '完成本次训练' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/app/workouts/plan-1/complete', expect.objectContaining({ method: 'POST', body: JSON.stringify({ completionRatio: 1 }) })));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([path]) => path === '/api/app/bootstrap')).toHaveLength(2));

    await user.click(screen.getByRole('link', { name: '瘦瘦' }));
    await user.click(await screen.findByRole('button', { name: /今天怎么练/ }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/app/ai/messages', expect.objectContaining({ method: 'POST', body: JSON.stringify({ message: '根据我的计划，告诉我今天怎么练' }) })));
    expect(await screen.findByText(/瘦瘦还没接上大模型/)).toBeInTheDocument();
  });

  it('opens an immersive workout player and starts synchronized voice guidance', async () => {
    const speak = vi.fn();
    const cancel = vi.fn();
    class Utterance {
      text: string;
      lang = '';
      rate = 1;
      constructor(text: string) { this.text = text; }
    }
    vi.stubGlobal('SpeechSynthesisUtterance', Utterance);
    vi.stubGlobal('speechSynthesis', { speak, cancel });
    mockFetch();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '计划' }));
    await user.click(await screen.findByRole('button', { name: '开始跟练' }));

    expect(await screen.findByRole('heading', { name: '上肢力量 · B' })).toBeInTheDocument();
    expect(screen.getByText('训练预览')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: '主导航' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '开始训练' }));
    expect(screen.getByText('准备开始')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(speak).toHaveBeenCalled();
    expect(speak.mock.calls.at(-1)?.[0]).toMatchObject({ text: expect.stringContaining('3') });
  });

  it('pauses the workout clock and records completion exactly once', async () => {
    const shortDashboard = {
      ...dashboard,
      plan: { ...dashboard.plan, estimatedMinutes: 1, exercises: [{ ...dashboard.plan.exercises[0], sets: 1, seconds: 2 }] },
    };
    const speak = vi.fn();
    class Utterance { text: string; constructor(text: string) { this.text = text; } }
    vi.stubGlobal('SpeechSynthesisUtterance', Utterance);
    vi.stubGlobal('speechSynthesis', { speak, cancel: vi.fn() });
    const fetchMock = mockFetch({ '/api/app/bootstrap': shortDashboard });
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '计划' }));
    await user.click(await screen.findByRole('button', { name: '开始跟练' }));
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole('button', { name: '开始训练' }));
    await act(async () => { vi.advanceTimersByTime(3000); });
    expect(screen.getByRole('timer')).toHaveAttribute('aria-label', '剩余 2 秒');

    fireEvent.click(screen.getByRole('button', { name: '暂停训练' }));
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(screen.getByRole('timer')).toHaveAttribute('aria-label', '剩余 2 秒');
    fireEvent.click(screen.getByRole('button', { name: '继续训练' }));
    await act(async () => { vi.advanceTimersByTime(2000); await Promise.resolve(); });

    expect(screen.getByRole('heading', { name: /今天的训练/ })).toBeInTheDocument();
    const completionCalls = (fetchMock.mock.calls as unknown as [RequestInfo | URL, RequestInit?][]).filter(([path]) => path === '/api/app/workouts/plan-1/complete');
    expect(completionCalls).toHaveLength(1);
    expect(completionCalls[0]?.[1]).toEqual(expect.objectContaining({ method: 'POST', body: JSON.stringify({ completionRatio: 1 }) }));
  });

  it('never completes an empty plan or a mismatched workout route', async () => {
    const emptyDashboard = { ...dashboard, plan: { ...dashboard.plan, exercises: [] }, exercises: [] };
    window.history.replaceState({}, '', '/workout/plan-1');
    let fetchMock = mockFetch({ '/api/app/bootstrap': emptyDashboard });
    render(<App />);
    expect(await screen.findByRole('heading', { name: '这次训练暂时不可用' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([path]) => path === '/api/app/workouts/plan-1/complete')).toBe(false);

    cleanup();
    window.history.replaceState({}, '', '/workout/not-the-current-plan');
    fetchMock = mockFetch();
    render(<App />);
    expect(await screen.findByRole('heading', { name: '这次训练暂时不可用' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([path]) => String(path).includes('/complete'))).toBe(false);
  });

  it('turns the AI welcome capabilities into a focused conversation', async () => {
    mockFetch();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '瘦瘦' }));
    const capabilities = await screen.findByRole('region', { name: '瘦瘦快捷能力' });
    expect(within(capabilities).getByRole('button', { name: /今天怎么练/ })).toBeInTheDocument();
    expect(within(capabilities).getByRole('button', { name: /今晚吃什么/ })).toBeInTheDocument();
    expect(within(capabilities).getByRole('button', { name: /帮我记一餐/ })).toBeInTheDocument();
    expect(within(capabilities).getByRole('button', { name: /看看最近状态/ })).toBeInTheDocument();

    await user.click(within(capabilities).getByRole('button', { name: /今晚吃什么/ }));
    expect(screen.queryByRole('region', { name: '瘦瘦快捷能力' })).not.toBeInTheDocument();
    expect(await screen.findByRole('button', { name: '换一个选择' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: '今天' }));
    await user.click(screen.getByRole('link', { name: '瘦瘦' }));
    expect(await screen.findByText('结合我今天的记录，推荐今晚吃什么')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '瘦瘦快捷能力' })).not.toBeInTheDocument();
  });

  it('does not append an old AI response after starting a new conversation', async () => {
    let resolveAi: ((response: Response) => void) | undefined;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/app/bootstrap') return new Response(JSON.stringify(dashboard), { status: 200 });
      if (path === '/api/app/ai/messages') return new Promise<Response>((resolve) => { resolveAi = resolve; });
      return new Response('{}', { status: 200 });
    }));
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '瘦瘦' }));
    await user.click(await screen.findByRole('button', { name: /今天怎么练/ }));
    await user.click(screen.getByRole('button', { name: '新建会话' }));
    resolveAi?.(new Response(JSON.stringify({ message: '旧会话迟到的回复' }), { status: 200 }));

    await waitFor(() => expect(screen.getByRole('region', { name: '瘦瘦快捷能力' })).toBeInTheDocument());
    expect(screen.queryByText('旧会话迟到的回复')).not.toBeInTheDocument();
  });

  it('expires the current AI conversation after 24 hours', async () => {
    window.localStorage.setItem('happy-fitness-ai-session:user-1', JSON.stringify({
      updatedAt: Date.now() - 24 * 60 * 60 * 1000 - 1,
      messages: [{ role: 'user', content: '已经过期的问题' }],
    }));
    mockFetch();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '瘦瘦' }));
    expect(await screen.findByRole('region', { name: '瘦瘦快捷能力' })).toBeInTheDocument();
    expect(screen.queryByText('已经过期的问题')).not.toBeInTheDocument();
  });

  it('navigates to the exercise library and exposes its four-step detail', async () => {
    mockFetch();
    render(<App />);
    await screen.findByText('124.8');
    fireEvent.click(screen.getByRole('link', { name: '动作' }));
    expect(await screen.findByRole('searchbox', { name: '搜索动作' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '全部' })).toBeInTheDocument();
    await userEvent.setup().type(screen.getByRole('searchbox', { name: '搜索动作' }), '不存在的动作');
    expect(screen.getByRole('heading', { name: '没有找到这个动作' })).toBeInTheDocument();
    await userEvent.setup().clear(screen.getByRole('searchbox', { name: '搜索动作' }));
    await userEvent.setup().click(await screen.findByRole('button', { name: /哑铃深蹲/ }));

    expect(screen.getByText('动作步骤 1')).toBeInTheDocument();
    expect(screen.getByText('动作步骤 4')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '常见错误' })).toBeInTheDocument();
    expect(screen.getByText('暂无特别提醒，保持动作稳定即可。')).toBeInTheDocument();
  });

  it('shows the focused profile dashboard and keeps logout functional', async () => {
    const fetchMock = mockFetch();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '我的' }));
    expect(await screen.findByRole('heading', { name: '坚持足迹' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '运动点亮图' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '体重 / 体脂趋势' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'AI 教练语气' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '个人偏好' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '历史记录' })).toBeInTheDocument();
    expect(screen.getByText('训练历史').parentElement).toHaveTextContent('3 次');
    await user.click(screen.getByRole('button', { name: '轻松逗趣' }));
    await user.click(screen.getByRole('link', { name: '今天' }));
    await user.click(screen.getByRole('link', { name: '我的' }));
    expect(screen.getByRole('button', { name: '轻松逗趣' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: '退出登录' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/local/logout', expect.objectContaining({ method: 'POST' })));
  });

  it('counts check-in dates using the user local day instead of UTC slices', async () => {
    const localDayDashboard = {
      ...dashboard,
      bodyRecords: [{ id: 'body-1', weightJin: 124.8, waistCm: 70, recordedAt: '2026-08-05T16:30:00Z' }],
      meals: [{ id: 'meal-1', occurredAt: '2026-08-06T00:30:00+08:00', mealType: 'BREAKFAST', items: [{ name: '早餐', estimatedKcal: 320 }] }],
    };
    mockFetch({ '/api/app/bootstrap': localDayDashboard });
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '我的' }));
    expect((await screen.findByRole('heading', { name: '坚持足迹' })).parentElement?.parentElement).toHaveTextContent('1天');
  });
});
