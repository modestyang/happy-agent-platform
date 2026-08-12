import { StrictMode } from 'react';
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
  const queuedGoalReport = {
    reportId: '11111111-1111-1111-1111-111111111111',
    goalId: 'goal-1',
    goalVersion: 1,
    state: 'QUEUED',
    windowStart: '2026-07-01',
    windowEnd: '2026-08-11',
    updatedAt: '2026-08-11T10:00:00Z',
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
      if (path === '/api/v1/app/ai/sessions') return new Response(JSON.stringify({ sessionId: 'server-session-id', status: 'ACTIVE', agentKey: 'fitness.coach', createdAt: new Date().toISOString() }), { status: 201 });
      if (path === '/api/v1/app/ai/sessions/server-session-id/messages') return new Response(JSON.stringify({ code: 'DEPENDENCY_NOT_CONFIGURED' }), { status: 503 });
      if (path.startsWith('/api/v1/app/workout-plans?')) return new Response(JSON.stringify({ items: [], page: { hasMore: false } }), { status: 200 });
      return new Response('{}', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  async function advanceVisibleSeconds(seconds: number) {
    for (let second = 0; second < seconds; second += 1) {
      await act(async () => {
        vi.advanceTimersByTime(1000);
        await Promise.resolve();
      });
    }
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
    expect(screen.getByRole('region', { name: '首页体重趋势' })).toHaveTextContent('体重变化');
    expect(screen.getByRole('button', { name: '放大查看体重趋势' })).toHaveClass('expandable-surface__media-trigger');
    expect(screen.queryByText('今天的节奏')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '计划' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '今天' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '花爷' })).toHaveClass('nav-link--ai');
    expect(screen.getByRole('link', { name: '动作' })).toBeInTheDocument();
  });

  it('keeps home actions at content density instead of stretching them on tall screens', () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/app.css', 'utf8');
    document.head.append(style);

    try {
      render(<section className="page home-page"><header /><section className="goal-card" /><section className="home-actions">{['训练', '饮食', '记录', '报告'].map((label) => <button className="home-action" key={label}>{label}</button>)}</section></section>);

      const homePage = screen.getByText('训练').closest('.home-page') as HTMLElement;
      const homeActions = screen.getByText('训练').closest('.home-actions') as HTMLElement;
      expect(getComputedStyle(homePage).overflowY).toBe('auto');
      expect(getComputedStyle(homePage).display).toBe('grid');
      expect(getComputedStyle(homeActions).gridTemplateRows).not.toContain('1fr');
      expect(getComputedStyle(screen.getByText('训练')).minHeight).toBe('128px');
      expect(screen.getAllByRole('button')).toHaveLength(4);
    } finally {
      style.remove();
    }
  });

  it('bounds every chat flex layer and hides page-level horizontal overflow', () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/app.css', 'utf8');
    document.head.append(style);

    try {
      render(<section className="page ai-page"><div className="ai-scroll"><section className="conversation"><div className="message message--assistant"><span aria-hidden="true" /><div className="message-body"><div className="md">很长的回复内容</div></div></div></section></div></section>);

      const aiScroll = document.querySelector('.ai-scroll') as HTMLElement;
      const conversation = document.querySelector('.conversation') as HTMLElement;
      const message = document.querySelector('.message') as HTMLElement;
      const messageBody = document.querySelector('.message-body') as HTMLElement;
      const markdown = document.querySelector('.md') as HTMLElement;
      expect(getComputedStyle(aiScroll).overflowX).toBe('hidden');
      expect(getComputedStyle(conversation).minWidth).toBe('0px');
      expect(getComputedStyle(message).minWidth).toBe('0px');
      expect(getComputedStyle(messageBody).minWidth).toBe('0px');
      expect(getComputedStyle(messageBody).maxWidth).toBe('100%');
      expect(getComputedStyle(markdown).minWidth).toBe('0px');
      expect(getComputedStyle(markdown).maxWidth).toBe('100%');
    } finally {
      style.remove();
    }
  });

  it.each([
    ['当前目标卡片', '查看目标进度'],
    ['报告卡片', '报告'],
  ])('opens an independent report page from the %s', async (_entry, buttonName) => {
    mockFetch({ '/api/v1/app/reports/current-goal': queuedGoalReport });
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('button', { name: buttonName }));

    expect(await screen.findByRole('heading', { name: '当前目标报告' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/report/current');
    expect(screen.getByRole('region', { name: '当前目标累计报告' })).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: '问花爷' })).not.toBeInTheDocument();
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

  it('keeps mobile form controls at a focus-safe font size', () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/app.css', 'utf8');
    document.head.append(style);

    try {
      render(<main className="phone"><div className="onboarding-card"><label>目标体重<input aria-label="目标体重" /></label></div></main>);

      expect(getComputedStyle(screen.getByLabelText('目标体重')).fontSize).toBe('16px');
    } finally {
      style.remove();
    }
  });

  it('keeps the AI composer at a stable mobile text scale', () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/app.css', 'utf8');
    document.head.append(style);

    try {
      render(<main className="phone"><section className="page ai-page"><form className="composer"><input aria-label="问花爷" /></form></section></main>);
      const input = screen.getByRole('textbox', { name: '问花爷' });
      const page = input.closest('.ai-page');
      const pageStyle = page ? getComputedStyle(page) : undefined;

      expect(getComputedStyle(input).fontSize).toBe('16px');
      expect(pageStyle?.webkitTextSizeAdjust || pageStyle?.getPropertyValue('text-size-adjust')).toBe('100%');
    } finally {
      style.remove();
    }
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

  it('automatically regenerates persisted English meal recommendations in Chinese', async () => {
    const englishDashboard = {
      ...dashboard,
      mealRecommendations: dashboard.mealRecommendations.map((recommendation) => ({
        ...recommendation,
        items: [{ name: 'Greek yogurt', estimatedKcal: 320 }],
        reason: 'High protein meal',
      })),
    };
    const fetchMock = mockFetch({ '/api/app/bootstrap': englishDashboard });
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('Greek yogurt');
    await user.click(screen.getByRole('button', { name: '饮食' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/app/meal-plans/daily/generate',
      expect.objectContaining({ method: 'POST' }),
    ));
  });

  it('shows an honest empty state when today has no meal recommendation', async () => {
    const fetchMock = mockFetch({ '/api/app/bootstrap': { ...dashboard, mealRecommendations: [] } });
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    expect(screen.getByRole('button', { name: '饮食' })).toHaveTextContent('今日建议尚未生成');
    await user.click(screen.getByRole('button', { name: '饮食' }));
    expect(await screen.findByRole('heading', { name: '今天还没有饮食建议' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '立即生成三餐建议' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/app/meal-plans/daily/generate',
      expect.objectContaining({ method: 'POST' }),
    ));
    expect(screen.getByText('正在生成三餐建议…')).toBeInTheDocument();
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

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/v1/app/ai/sessions/server-session-id/messages', expect.objectContaining({ method: 'POST' })));
    const calls = fetchMock.mock.calls as unknown as [RequestInfo | URL, RequestInit?][];
    const aiCall = calls.find(([path]) => path === '/api/v1/app/ai/sessions/server-session-id/messages');
    expect(JSON.parse(String(aiCall?.[1]?.body)).text).toContain('训练计划');
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

  it('shows first setup after bootstrap requires onboarding and reloads the dashboard after submission', async () => {
    const onboarding = {
      user: { id: 'user-1', nickname: '新用户' },
      onboarding: { state: 'REQUIRED' },
      goal: null,
      bodyRecords: [],
      meals: [],
      mealRecommendations: [],
      plan: null,
      exercises: [],
      completedWorkoutCount: 0,
      report: null,
      ai: { configured: false, reason: '请在 Agent 工作台配置模型 Provider' },
    };
    let bootstrapCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/app/bootstrap') {
        bootstrapCalls += 1;
        return new Response(JSON.stringify(bootstrapCalls === 1 ? onboarding : dashboard), { status: 200 });
      }
      if (path === '/api/app/first-setup') return new Response('{}', { status: 201 });
      return new Response('{}', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByRole('heading', { name: '开始你的第一个目标' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('当前体重（斤）'), '128.6');
    await user.type(screen.getByLabelText('目标体重（斤）'), '118');
    await user.type(screen.getByLabelText('目标日期'), '2026-12-31');
    await user.click(screen.getByRole('button', { name: '女性' }));
    await user.click(screen.getByRole('button', { name: '新手' }));
    await user.click(screen.getByRole('button', { name: '家里' }));
    await user.click(screen.getByRole('button', { name: '保存并开始' }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/app/first-setup',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            weightJin: 128.6,
            targetWeightJin: 118,
            targetDate: '2026-12-31',
            trainingProfile: {
              biologicalSex: 'FEMALE',
              experienceLevel: 'BEGINNER',
              trainingVenues: ['HOME'],
              availableEquipment: [],
              trainingWeekdays: [],
              sessionMinutes: 30,
              trainingRestrictions: [],
              coachingTone: 'WARM_DIRECT',
              nutritionPreferences: [],
            },
          }),
        }),
      ),
    );
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
    await user.type(screen.getByLabelText('食物名称 1'), '牛肉面');
    await user.type(screen.getByLabelText('热量 1'), '520');
    await user.click(screen.getByRole('button', { name: '保存饮食记录' }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/app/meal-records',
        expect.objectContaining({
          method: 'POST',
          body: expect.stringContaining('"source":"MANUAL"'),
        }),
      ),
    );
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

    await user.click(screen.getByRole('link', { name: '花爷' }));
    await user.click(await screen.findByRole('button', { name: /今天怎么练/ }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/v1/app/ai/sessions/server-session-id/messages', expect.objectContaining({ method: 'POST' })));
    expect(await screen.findByText(/花爷还没接上大模型/)).toBeInTheDocument();
  });

  it('submits an AI request when the browser lacks crypto.randomUUID', async () => {
    const fetchMock = mockFetch();
    vi.stubGlobal('crypto', {});
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '花爷' }));
    await user.click(await screen.findByRole('button', { name: /今天怎么练/ }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/app/ai/sessions/server-session-id/messages',
        expect.objectContaining({ method: 'POST' }),
      ),
    );
    expect(await screen.findByText(/花爷还没接上大模型/)).toBeInTheDocument();
  });

  it('creates an AI session on the backend before sending through its id', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/app/bootstrap') return new Response(JSON.stringify(dashboard), { status: 200 });
      if (path === '/api/v1/app/ai/sessions') {
        return new Response(JSON.stringify({
          sessionId: 'server-session-id', status: 'ACTIVE', agentKey: 'fitness.coach', createdAt: new Date().toISOString(),
        }), { status: 201 });
      }
      if (path === '/api/v1/app/ai/sessions/server-session-id/messages') {
        return new Response(JSON.stringify({ code: 'DEPENDENCY_NOT_CONFIGURED' }), { status: 503 });
      }
      return new Response('{}', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '花爷' }));
    await user.click(await screen.findByRole('button', { name: /今天怎么练/ }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/app/ai/sessions/server-session-id/messages',
      expect.objectContaining({ method: 'POST' }),
    ));
    const paths = fetchMock.mock.calls.map(([path]) => String(path));
    expect(paths.indexOf('/api/v1/app/ai/sessions')).toBeLessThan(
      paths.indexOf('/api/v1/app/ai/sessions/server-session-id/messages'),
    );
    expect(paths).not.toContain('/api/v1/app/ai/runs');
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
    const resume = vi.fn();
    vi.stubGlobal('speechSynthesis', { speak, cancel, resume });
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
    expect(resume).toHaveBeenCalledTimes(1);
    expect(speak).toHaveBeenCalled();
    expect(speak.mock.calls.at(-1)?.[0]).toMatchObject({ text: expect.stringContaining('3') });
    expect(cancel).not.toHaveBeenCalled();
  });

  it('rotates plan previews and drives workout frames from elapsed exercise seconds', async () => {
    const visualDashboard = {
      ...dashboard,
      exercises: [{
        ...dashboard.exercises[0],
        imageUrls: ['/squat-1.png', '/squat-2.png', '/squat-3.png', '/squat-4.png'],
      }],
    };
    mockFetch({ '/api/app/bootstrap': visualDashboard });
    render(<App />);

    await screen.findByText('124.8');
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole('link', { name: '计划' }));
    expect(screen.getByRole('img', { name: '哑铃深蹲第1步动作示意' })).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1500));
    expect(screen.getByRole('img', { name: '哑铃深蹲第2步动作示意' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '开始跟练' }));
    fireEvent.click(screen.getByRole('button', { name: '开始训练' }));
    await advanceVisibleSeconds(3);
    expect(screen.getByRole('img', { name: '哑铃深蹲第1步动作示意' })).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByRole('img', { name: '哑铃深蹲第2步动作示意' })).toBeInTheDocument();
  });

  it('shows each preparation number for a full second instead of catching up after a delayed timer', async () => {
    mockFetch();
    render(<App />);

    await screen.findByText('124.8');
    fireEvent.click(screen.getByRole('link', { name: '计划' }));
    fireEvent.click(screen.getByRole('button', { name: '开始跟练' }));
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole('button', { name: '开始训练' }));

    act(() => vi.advanceTimersByTime(2500));
    expect(screen.getByRole('timer')).toHaveAttribute('aria-label', '剩余 2 秒');
    act(() => vi.advanceTimersByTime(999));
    expect(screen.getByRole('timer')).toHaveAttribute('aria-label', '剩余 2 秒');
    act(() => vi.advanceTimersByTime(1));
    expect(screen.getByRole('timer')).toHaveAttribute('aria-label', '剩余 1 秒');
  });

  it('plays one beat per visible second and silences beats with the workout mute control', async () => {
    const oscillatorStart = vi.fn();
    class AudioContextDouble {
      currentTime = 0;
      destination = {};
      state = 'suspended';
      resume = vi.fn();
      createOscillator() {
        return {
          frequency: { setValueAtTime: vi.fn() },
          connect: vi.fn(),
          start: oscillatorStart,
          stop: vi.fn(),
          onended: undefined as (() => void) | undefined,
        };
      }
      createGain() {
        return {
          gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() },
          connect: vi.fn(),
        };
      }
    }
    vi.stubGlobal('AudioContext', AudioContextDouble);
    mockFetch();
    render(<App />);

    await screen.findByText('124.8');
    fireEvent.click(screen.getByRole('link', { name: '计划' }));
    fireEvent.click(screen.getByRole('button', { name: '开始跟练' }));
    vi.useFakeTimers();
    fireEvent.click(screen.getByRole('button', { name: '开始训练' }));
    act(() => vi.advanceTimersByTime(1000));
    expect(oscillatorStart).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: '关闭声音' }));
    act(() => vi.advanceTimersByTime(3000));
    expect(oscillatorStart).toHaveBeenCalledTimes(1);
  });

  it('loads and persists the selected local workout voice style', async () => {
    window.localStorage.setItem('happy-agent.workout-voice-style', 'MAGNETIC_MALE');
    mockFetch();
    render(<App />);

    await screen.findByText('124.8');
    fireEvent.click(screen.getByRole('link', { name: '计划' }));
    fireEvent.click(screen.getByRole('button', { name: '开始跟练' }));
    const style = screen.getByRole('combobox', { name: '语音风格' });
    expect(style).toHaveValue('MAGNETIC_MALE');

    fireEvent.change(style, { target: { value: 'GENTLE_FEMALE' } });
    expect(window.localStorage.getItem('happy-agent.workout-voice-style')).toBe('GENTLE_FEMALE');
  });

  it('keeps timing active with a concise unsupported-voice notice after training starts', async () => {
    mockFetch();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '计划' }));
    await user.click(await screen.findByRole('button', { name: '开始跟练' }));
    expect(screen.getByText(/当前浏览器不支持语音播报/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '开始训练' }));
    expect(screen.getByRole('timer')).toHaveAttribute('aria-label', '剩余 3 秒');
    expect(screen.getByText(/当前浏览器不支持语音播报/)).toBeInTheDocument();
  });

  it('does not duplicate a start cue when React StrictMode replays effects', async () => {
    const speak = vi.fn();
    const cancel = vi.fn();
    class Utterance { text: string; constructor(text: string) { this.text = text; } }
    vi.stubGlobal('SpeechSynthesisUtterance', Utterance);
    vi.stubGlobal('speechSynthesis', { speak, cancel, resume: vi.fn() });
    mockFetch();
    const user = userEvent.setup();
    render(<StrictMode><App /></StrictMode>);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '计划' }));
    await user.click(await screen.findByRole('button', { name: '开始跟练' }));
    expect(cancel).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '开始训练' }));

    expect(speak).toHaveBeenCalledTimes(1);
  });

  it('speaks pause and resume once each through the real player controls', async () => {
    const speak = vi.fn();
    class Utterance {
      text: string;
      onend?: () => void;
      constructor(text: string) { this.text = text; }
    }
    vi.stubGlobal('SpeechSynthesisUtterance', Utterance);
    vi.stubGlobal('speechSynthesis', { speak, cancel: vi.fn(), resume: vi.fn() });
    mockFetch();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '计划' }));
    await user.click(await screen.findByRole('button', { name: '开始跟练' }));
    await user.click(screen.getByRole('button', { name: '开始训练' }));
    await user.click(screen.getByRole('button', { name: '暂停训练' }));

    const initialCue = speak.mock.calls[0]?.[0] as Utterance;
    initialCue.onend?.();
    expect(speak.mock.calls[1]?.[0]).toMatchObject({ text: '训练暂停' });

    await user.click(screen.getByRole('button', { name: '继续训练' }));
    const pauseCue = speak.mock.calls[1]?.[0] as Utterance;
    pauseCue.onend?.();
    expect(speak.mock.calls[2]?.[0]).toMatchObject({ text: '继续训练' });
    expect(speak.mock.calls.map(([utterance]) => (utterance as Utterance).text).filter((text) => text === '训练暂停')).toHaveLength(1);
    expect(speak.mock.calls.map(([utterance]) => (utterance as Utterance).text).filter((text) => text === '继续训练')).toHaveLength(1);
  });

  it('stops speech immediately on exit, then allows a cancelled workout to continue with revisited action cues', async () => {
    const speak = vi.fn();
    const cancel = vi.fn();
    class Utterance { text: string; constructor(text: string) { this.text = text; } }
    vi.stubGlobal('SpeechSynthesisUtterance', Utterance);
    vi.stubGlobal('speechSynthesis', { speak, cancel, resume: vi.fn() });
    const multiExerciseDashboard = {
      ...dashboard,
      plan: {
        ...dashboard.plan,
        exercises: [
          ...dashboard.plan.exercises,
          { ...dashboard.plan.exercises[0], id: 'bridge', name: '臀桥' },
        ],
      },
      exercises: [
        ...dashboard.exercises,
        { ...dashboard.exercises[0], id: 'bridge', name: '臀桥' },
      ],
    };
    mockFetch({ '/api/app/bootstrap': multiExerciseDashboard });
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '计划' }));
    await user.click(await screen.findByRole('button', { name: '开始跟练' }));
    await user.click(screen.getByRole('button', { name: '开始训练' }));
    expect(cancel).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: '下一个动作' }));
    expect(cancel).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: '关闭声音' }));
    expect(cancel).toHaveBeenCalledTimes(2);
    await user.click(screen.getByRole('button', { name: '打开声音' }));
    await user.click(screen.getByRole('button', { name: '上一个动作' }));
    expect(cancel).toHaveBeenCalledTimes(2);
    expect(speak.mock.calls.at(-1)?.[0]).toMatchObject({ text: '哑铃深蹲，第 1 组' });

    await user.click(screen.getByRole('button', { name: '退出训练' }));
    expect(cancel).toHaveBeenCalledTimes(3);
    const stoppedUtterance = speak.mock.calls.at(-1)?.[0] as { onend?: () => void };
    const callsBeforeLateEnd = speak.mock.calls.length;
    stoppedUtterance.onend?.();
    expect(speak).toHaveBeenCalledTimes(callsBeforeLateEnd);

    await user.click(within(screen.getByRole('dialog', { name: '退出训练确认' })).getByRole('button', { name: '继续跟练' }));
    await user.click(screen.getByRole('button', { name: '下一个动作' }));
    expect(speak.mock.calls.at(-1)?.[0]).toMatchObject({ text: '臀桥，第 1 组' });

    await user.click(screen.getByRole('button', { name: '退出训练' }));
    expect(cancel).toHaveBeenCalledTimes(4);
    await user.click(within(screen.getByRole('dialog', { name: '退出训练确认' })).getByRole('button', { name: '退出训练' }));
    expect(cancel).toHaveBeenCalledTimes(4);
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
    await advanceVisibleSeconds(3);
    expect(screen.getByRole('timer')).toHaveAttribute('aria-label', '剩余 2 秒');

    fireEvent.click(screen.getByRole('button', { name: '暂停训练' }));
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(screen.getByRole('timer')).toHaveAttribute('aria-label', '剩余 2 秒');
    fireEvent.click(screen.getByRole('button', { name: '继续训练' }));
    await advanceVisibleSeconds(2);

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
    await user.click(screen.getByRole('link', { name: '花爷' }));
    const capabilities = await screen.findByRole('region', { name: '花爷快捷能力' });
    expect(within(capabilities).getByRole('button', { name: /今天怎么练/ })).toBeInTheDocument();
    expect(within(capabilities).getByRole('button', { name: /今晚吃什么/ })).toBeInTheDocument();
    expect(within(capabilities).getByRole('button', { name: /帮我记一餐/ })).toBeInTheDocument();
    expect(within(capabilities).getByRole('button', { name: /看看最近状态/ })).toBeInTheDocument();

    await user.click(within(capabilities).getByRole('button', { name: /今晚吃什么/ }));
    expect(screen.queryByRole('region', { name: '花爷快捷能力' })).not.toBeInTheDocument();
    expect(await screen.findByRole('button', { name: '换一个选择' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: '今天' }));
    await user.click(screen.getByRole('link', { name: '花爷' }));
    expect(await screen.findByText('结合我今天的记录，推荐今晚吃什么')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '花爷快捷能力' })).not.toBeInTheDocument();
  });

  it('follows a new AI reply to the bottom of the conversation', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/app/bootstrap') return new Response(JSON.stringify(dashboard), { status: 200 });
      if (path === '/api/v1/app/ai/sessions') {
        return new Response(JSON.stringify({ sessionId: 'server-session-id', status: 'ACTIVE', agentKey: 'fitness.coach', createdAt: new Date().toISOString() }), { status: 201 });
      }
      if (path === '/api/v1/app/ai/sessions/server-session-id/messages') {
        return new Response(JSON.stringify({ runId: 'run-1', sessionId: 'server-session-id', status: 'RUNNING', eventStreamUrl: '/run-1/events', result: [], createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }), { status: 202 });
      }
      if (path === '/run-1/events') {
        return new Response('data: {"type":"TEXT_DELTA","data":{"delta":"收到，继续保持"}}\n\n', { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
      }
      return new Response('{}', { status: 200 });
    }));
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '花爷' }));
    const input = await screen.findByRole('textbox', { name: '问花爷' });
    const scrollContainer = input.closest('.ai-page')?.querySelector<HTMLElement>('.ai-scroll');
    expect(scrollContainer).toBeInstanceOf(HTMLElement);
    Object.defineProperty(scrollContainer, 'scrollHeight', { configurable: true, value: 900 });
    if (scrollContainer) scrollContainer.scrollTop = 0;

    await user.type(input, '给我一个建议');
    await user.click(screen.getByRole('button', { name: '发送' }));

    expect(await screen.findByText('收到，继续保持')).toBeInTheDocument();
    await waitFor(() => expect(scrollContainer?.scrollTop).toBe(900));
  });

  it('does not append an old AI response after starting a new conversation', async () => {
    let resolveAi: ((response: Response) => void) | undefined;
    let createdSessions = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/app/bootstrap') return new Response(JSON.stringify(dashboard), { status: 200 });
      if (path === '/api/v1/app/ai/sessions') {
        createdSessions += 1;
        return new Response(JSON.stringify({ sessionId: `server-session-${createdSessions}`, status: 'ACTIVE', agentKey: 'fitness.coach', createdAt: new Date().toISOString() }), { status: 201 });
      }
      if (path === '/api/v1/app/ai/sessions/server-session-1/messages') return new Promise<Response>((resolve) => { resolveAi = resolve; });
      return new Response('{}', { status: 200 });
    }));
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('124.8');
    await user.click(screen.getByRole('link', { name: '花爷' }));
    await user.click(await screen.findByRole('button', { name: /今天怎么练/ }));
    await user.click(screen.getByRole('button', { name: '新建会话' }));
    resolveAi?.(new Response(JSON.stringify({ runId: 'old-run', sessionId: 'old-session', status: 'RUNNING', eventStreamUrl: '/old-events', result: [], createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }), { status: 202 }));

    await waitFor(() => expect(screen.getByRole('region', { name: '花爷快捷能力' })).toBeInTheDocument());
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
    await user.click(screen.getByRole('link', { name: '花爷' }));
    expect(await screen.findByRole('region', { name: '花爷快捷能力' })).toBeInTheDocument();
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
    expect(screen.getByRole('heading', { name: '训练档案' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '历史记录' })).toBeInTheDocument();
    expect(screen.getByText('训练历史').parentElement).toHaveTextContent('3 次');
    await user.click(screen.getByRole('button', { name: '轻松逗趣' }));
    await user.click(screen.getByRole('button', { name: '保存训练档案' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/app/training-profile',
      expect.objectContaining({ method: 'PUT', body: expect.stringContaining('"coachingTone":"LIGHT_HEARTED"') }),
    ));

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
