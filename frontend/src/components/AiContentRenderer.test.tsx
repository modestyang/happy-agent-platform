import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { AiContentRenderer } from './AiContentRenderer';

describe('AiContentRenderer', () => {
  it('degrades unknown runtime blocks without rendering their payload as markup', () => {
    render(<AiContentRenderer block={{ kind: 'UNKNOWN', html: '<script>bad()</script>' } as never} />);

    expect(screen.getByText('暂不支持这类内容')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
  });

  it('routes confirmation actions only through registered callbacks', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<AiContentRenderer
      block={{ kind: 'CONFIRMATION', confirmationId: 'confirmation-1', title: '保存计划', message: '保存到本周计划？', confirmLabel: '保存', cancelLabel: '取消' }}
      context={{ onConfirm, onCancel }}
    />);

    await user.click(screen.getByRole('button', { name: '保存' }));
    expect(onConfirm).toHaveBeenCalledWith('confirmation-1');
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('disables confirmation actions when no trusted callback is registered', () => {
    render(<AiContentRenderer block={{ kind: 'CONFIRMATION', confirmationId: 'confirmation-2', title: '保存计划', message: '保存到本周计划？', confirmLabel: '保存', cancelLabel: '取消' }} />);

    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '取消' })).toBeDisabled();
  });

  it('disables only the confirmation currently submitting', () => {
    render(<AiContentRenderer
      block={{ kind: 'CONFIRMATION', confirmationId: 'confirmation-3', title: '保存计划', message: '保存到本周计划？', confirmLabel: '保存', cancelLabel: '取消' }}
      context={{
        confirmationStates: { 'confirmation-3': { deciding: true, status: 'REQUESTED' } },
        onConfirm: vi.fn(),
        onCancel: vi.fn(),
      }}
    />);

    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '取消' })).toBeDisabled();
  });

  it('renders a typed body trend with the shared expandable chart', () => {
    render(<AiContentRenderer block={{
      kind: 'BODY_TREND',
      metric: 'WEIGHT',
      unit: '斤',
      points: [
        { measuredAt: '2026-08-01T08:00:00+08:00', value: 126.2 },
        { measuredAt: '2026-08-08T08:00:00+08:00', value: 124.8 },
      ],
    }} />);

    expect(screen.getByRole('img', { name: '体重趋势，共 2 条记录' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '放大查看体重趋势' })).toHaveClass('expandable-surface__media-trigger');
  });

  it.each(['__proto__', 'constructor'])('rejects prototype metric key %s as unsupported content', (metric) => {
    render(<AiContentRenderer block={{
      kind: 'BODY_TREND',
      metric,
      unit: '斤',
      points: [{ measuredAt: '2026-08-08T08:00:00+08:00', value: 124.8 }],
    } as never} />);

    expect(screen.getByText('暂不支持这类内容')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});
