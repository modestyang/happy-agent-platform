import { act, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { CurrentGoalReport } from '../api/generated/public';
import { useCurrentGoalReportPolling } from './CurrentGoalReportPolling';

const queued: CurrentGoalReport = {
  reportId: '11111111-1111-1111-1111-111111111111',
  goalId: '22222222-2222-2222-2222-222222222222',
  goalVersion: 1,
  state: 'QUEUED',
  windowStart: '2026-08-01',
  windowEnd: '2026-08-09',
  updatedAt: '2026-08-09T10:00:00Z',
};

const generating: CurrentGoalReport = { ...queued, state: 'GENERATING' };

const failed: CurrentGoalReport = {
  ...queued,
  state: 'FAILED',
  failure: { code: 'TASK_FAILED', message: '模型暂不可达', retryable: true },
};

const ready: CurrentGoalReport = {
  ...queued,
  state: 'READY',
  conclusion: { summary: '已完成', score: 90, grade: 'A' },
  metrics: [],
  weightTrend: [],
  trainingVolume: [],
  trainingStructure: [],
  cardioPercent: 0,
  strengthPercent: 0,
  highlights: ['一', '二'],
  weaknesses: ['三'],
  nextActions: [],
  computedThrough: '2026-08-09T10:00:00Z',
};

function PollingHarness({ initial, read }: { initial: CurrentGoalReport; read: () => Promise<CurrentGoalReport> }) {
  const [report, setReport] = useState(initial);
  useCurrentGoalReportPolling(report, read, setReport, 1000);
  return <output>{report.state}</output>;
}

describe('useCurrentGoalReportPolling', () => {
  afterEach(() => vi.useRealTimers());

  it('keeps polling QUEUED until a later READY response', async () => {
    vi.useFakeTimers();
    const read = vi.fn().mockResolvedValueOnce({ ...queued }).mockResolvedValueOnce(ready);
    render(<PollingHarness initial={queued} read={read} />);

    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(read).toHaveBeenCalledTimes(1);
    expect(screen.getByText('QUEUED')).toBeInTheDocument();

    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(read).toHaveBeenCalledTimes(2);
    expect(screen.getByText('READY')).toBeInTheDocument();

    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('keeps polling GENERATING until a later FAILED response', async () => {
    vi.useFakeTimers();
    const read = vi.fn().mockResolvedValueOnce({ ...generating }).mockResolvedValueOnce(failed);
    render(<PollingHarness initial={generating} read={read} />);

    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

    expect(read).toHaveBeenCalledTimes(2);
    expect(screen.getByText('FAILED')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('cancels a pending poll when the card unmounts', async () => {
    vi.useFakeTimers();
    const read = vi.fn().mockResolvedValue(queued);
    const view = render(<PollingHarness initial={queued} read={read} />);

    view.unmount();
    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

    expect(read).not.toHaveBeenCalled();
  });
});
