import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AgentRunMessage, parseSseFrames, type AgentRunUiMessage } from './AgentRunMessage';

describe('AgentRunMessage', () => {
  it('parses fragmented durable SSE frames without losing markdown deltas', () => {
    const first = parseSseFrames('', 'id: 1\nevent: TEXT_DELTA\ndata: {"type":"TEXT_DELTA","data":{"delta":"## 计');
    expect(first.events).toEqual([]);
    const second = parseSseFrames(first.remainder, '划"}}\n\nid: 2\nevent: COMPLETED\ndata: {"type":"COMPLETED","data":{"status":"SUCCEEDED"}}\n\n');
    expect(second.events.map((event) => event.type)).toEqual(['TEXT_DELTA', 'COMPLETED']);
    expect(second.events[0]?.data.delta).toBe('## 计划');
  });

  it('keeps execution progress collapsed and asks before saving a proposal', () => {
    const decide = vi.fn();
    const message: AgentRunUiMessage = {
      role: 'assistant',
      content: '## 你的计划',
      progress: ['正在整理可执行的建议'],
      approval: {
        approvalId: 'approval-1',
        status: 'REQUESTED',
        title: '保存当天训练计划',
        proposal: { scope: 'DAY', days: [{ date: '2026-08-10', title: '全身训练', estimatedMinutes: 30, exercises: [{ exerciseId: 'e1', name: '深蹲' }] }] },
      },
    };
    render(<AgentRunMessage message={message} onDecision={decide} />);
    expect(screen.getByText('思考与执行').closest('details')).not.toHaveAttribute('open');
    fireEvent.click(screen.getByRole('button', { name: '确认并保存' }));
    expect(decide).toHaveBeenCalledWith('approval-1', 'APPROVE');
  });
});
