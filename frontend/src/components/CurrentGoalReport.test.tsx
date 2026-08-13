import { readFileSync } from 'node:fs';

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
    { key: 'CURRENT_MONTH_WORKOUT_COUNT', label: '本月训练', value: 8, unit: '次', trend: 'UP' },
    { key: 'CURRENT_MONTH_WORKOUT_MINUTES', label: '本月时长', value: 320, unit: '分钟', trend: 'UP' },
    { key: 'BODY_RECORD_COUNT', label: '身体记录', value: 6, unit: '次', trend: 'STABLE' },
    { key: 'MEAL_RECORD_COUNT', label: '饮食记录', value: 42, unit: '餐', trend: 'STABLE' },
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
    expect(screen.getByText('花爷正在整理你的当前目标')).toBeInTheDocument();
  });

  it('renders an evidence-based editorial report without presenting the model score as a fact', async () => {
    const onGeneratePlan = vi.fn();
    const user = userEvent.setup();
    render(<CurrentGoalReportCard report={readyReport} onRetry={vi.fn()} onGeneratePlan={onGeneratePlan} onOpenRecord={vi.fn()} />);

    expect(screen.getByRole('region', { name: '当前目标累计报告' })).toHaveTextContent('节奏很稳，继续保持。');
    expect(screen.queryByText('86 分 · A')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '本月概览' })).toBeInTheDocument();
    expect(screen.getByText('本月训练').closest('article')).toHaveTextContent('8次');
    expect(screen.getByText('本月时长').closest('article')).toHaveTextContent('320分钟');
    expect(screen.getByRole('heading', { name: '趋势与结构' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '分析结论' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '行动建议' })).toBeInTheDocument();
    expect(screen.getByText('较上期 +8%')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '四周体重趋势' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '四周训练量趋势' })).toBeInTheDocument();
    expect(screen.getByText(/数据积累中/)).toBeInTheDocument();
    expect(screen.getByText('训练部位覆盖次数占比')).toBeInTheDocument();
    expect(screen.getByText('力量 / 有氧按计划时长估算')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '当前目标累计报告' })).toHaveTextContent('连续三周训练量上升');
    expect(screen.getByRole('contentinfo')).toHaveTextContent('未记录训练强度与力量训练日');
    expect(screen.getByRole('link', { name: 'WHO 成人身体活动指南' })).toHaveAttribute('href', 'https://www.who.int/europe/news-room/fact-sheets/item/physical-activity');

    await user.click(screen.getByRole('button', { name: '生成下周计划' }));
    expect(onGeneratePlan).toHaveBeenCalledOnce();
  });

  it('uses the page canvas instead of wrapping the report in another small card', () => {
    const style = document.createElement('style');
    style.textContent = readFileSync('src/app.css', 'utf8');
    document.head.append(style);

    try {
      render(<CurrentGoalReportCard report={readyReport} onRetry={vi.fn()} onGeneratePlan={vi.fn()} onOpenRecord={vi.fn()} />);

      const report = screen.getByRole('region', { name: '当前目标累计报告' });
      expect(getComputedStyle(report).borderTopWidth).toBe('0px');
      expect(getComputedStyle(report).borderRadius).toBe('0px');
      expect(getComputedStyle(report).paddingTop).toBe('0px');
      expect(getComputedStyle(report).backgroundColor).toBe('rgba(0, 0, 0, 0)');
      expect(Number.parseFloat(getComputedStyle(screen.getByRole('contentinfo')).fontSize)).toBeGreaterThanOrEqual(12);
    } finally {
      style.remove();
    }
  });

  it('opens report trends in the shared visual dialog', async () => {
    const user = userEvent.setup();
    render(<CurrentGoalReportCard report={readyReport} onRetry={vi.fn()} onGeneratePlan={vi.fn()} onOpenRecord={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: '放大查看四周体重趋势' }));
    const dialog = screen.getByRole('dialog', { name: '四周体重趋势详情' });
    expect(dialog).toHaveClass('surface-dialog--media');
    expect(dialog.querySelector('header')).toBeNull();
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
