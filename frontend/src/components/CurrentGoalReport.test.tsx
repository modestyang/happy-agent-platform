import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { CurrentGoalReport } from '../api/generated/public';
import { CurrentGoalReportCard } from './CurrentGoalReport';

const readyReport: CurrentGoalReport = {
  reportId: '11111111-1111-1111-1111-111111111111',
  goalId: '22222222-2222-2222-2222-222222222222',
  goalVersion: 1,
  state: 'READY',
  windowStart: '2026-07-01',
  windowEnd: '2026-08-09',
  conclusion: { summary: '节奏很稳，继续保持。', score: 86, grade: 'A' },
  metrics: [
    { key: 'GOAL_PROGRESS', label: '目标进度', value: 46, unit: '%', comparison: 8, trend: 'UP' },
    { key: 'WEIGHT', label: '当前体重', value: 124.8, unit: '斤', trend: 'DOWN' },
  ],
  weightTrend: [
    { weekStart: '2026-07-13', valueJin: null },
    { weekStart: '2026-07-20', valueJin: 127.2 },
    { weekStart: '2026-07-27', valueJin: 126.1 },
    { weekStart: '2026-08-03', valueJin: 124.8 },
  ],
  trainingVolume: [
    { weekStart: '2026-07-13', minutes: 0, sessions: 0 },
    { weekStart: '2026-07-20', minutes: 84, sessions: 2 },
    { weekStart: '2026-07-27', minutes: 120, sessions: 3 },
    { weekStart: '2026-08-03', minutes: 126, sessions: 3 },
  ],
  trainingStructure: [{ area: '下肢', percent: 60 }, { area: '核心', percent: 40 }],
  cardioPercent: 30,
  strengthPercent: 70,
  highlights: ['连续三周训练量上升', '体重下降节奏平稳'],
  weaknesses: ['第一周训练记录仍不足'],
  nextActions: [{ title: '生成下周计划', rationale: '把稳定节奏延续下去。', action: 'GENERATE_PLAN' }],
  computedThrough: '2026-08-09T10:00:00Z',
  updatedAt: '2026-08-09T10:00:01Z',
};

describe('CurrentGoalReportCard', () => {
  it('keeps the report card in a truthful holding state while the worker owns the lease', () => {
    render(
      <CurrentGoalReportCard
        report={{
          reportId: '11111111-1111-1111-1111-111111111111',
          goalId: '22222222-2222-2222-2222-222222222222',
          goalVersion: 1,
          state: 'QUEUED',
          windowStart: '2026-07-01',
          windowEnd: '2026-08-09',
          updatedAt: '2026-08-09T10:00:01Z',
        }}
        onRetry={vi.fn()}
        onGeneratePlan={vi.fn()}
        onOpenRecord={vi.fn()}
      />,
    );

    expect(screen.getByLabelText('报告生成中')).toBeInTheDocument();
    expect(screen.getByText('瘦瘦正在整理你的当前目标')).toBeInTheDocument();
  });

  it('renders deterministic trends with the fixed narrative report card and action', async () => {
    const onGeneratePlan = vi.fn();
    const user = userEvent.setup();
    render(<CurrentGoalReportCard report={readyReport} onRetry={vi.fn()} onGeneratePlan={onGeneratePlan} onOpenRecord={vi.fn()} />);

    expect(screen.getByRole('region', { name: '当前目标累计报告' })).toHaveTextContent('节奏很稳，继续保持。');
    expect(screen.getByText('86 分 · A')).toBeInTheDocument();
    expect(screen.getByText('较上期 +8%')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '四周体重趋势' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '四周训练量趋势' })).toBeInTheDocument();
    expect(screen.getByText(/数据积累中/)).toBeInTheDocument();
    expect(screen.getByText('训练部位覆盖次数占比')).toBeInTheDocument();
    expect(screen.getByText('力量 / 有氧按计划时长估算')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '当前目标累计报告' })).toHaveTextContent('连续三周训练量上升');

    await user.click(screen.getByRole('button', { name: '生成下周计划' }));
    expect(onGeneratePlan).toHaveBeenCalledOnce();
  });

  it('keeps failed generation honest and lets the user retry', async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    render(
      <CurrentGoalReportCard
        report={{
          reportId: '11111111-1111-1111-1111-111111111111',
          goalId: '22222222-2222-2222-2222-222222222222',
          goalVersion: 1,
          state: 'FAILED',
          windowStart: '2026-07-01',
          windowEnd: '2026-08-09',
          failure: { code: 'TASK_FAILED', message: '模型返回不是有效 JSON', retryable: true },
          updatedAt: '2026-08-09T10:00:01Z',
        }}
        onRetry={onRetry}
        onGeneratePlan={vi.fn()}
        onOpenRecord={vi.fn()}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('模型返回不是有效 JSON');
    await user.click(screen.getByRole('button', { name: '重试生成报告' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
