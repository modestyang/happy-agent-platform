import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AgentRunMessage, applyAgentRunEvent, parseSseFrames, type AgentRunUiMessage } from './AgentRunMessage';

describe('AgentRunMessage', () => {
  it('parses fragmented durable SSE frames without losing markdown deltas', () => {
    const first = parseSseFrames('', 'id: 1\nevent: TEXT_DELTA\ndata: {"type":"TEXT_DELTA","data":{"delta":"## 计');
    expect(first.events).toEqual([]);
    const second = parseSseFrames(first.remainder, '划"}}\n\nid: 2\nevent: COMPLETED\ndata: {"type":"COMPLETED","data":{"status":"SUCCEEDED"}}\n\n');
    expect(second.events.map((event) => event.type)).toEqual(['TEXT_DELTA', 'COMPLETED']);
    expect(second.events[0]?.data.delta).toBe('## 计划');
  });

  it('shows only the latest progress stage before output and removes it when the reply starts', () => {
    const progressOnly: AgentRunUiMessage = {
      role: 'assistant',
      content: '',
      progress: ['正在理解你的需求', '正在查看相关记录'],
    };
    const { rerender } = render(<AgentRunMessage message={progressOnly} />);

    expect(screen.getByRole('status')).toHaveTextContent('正在查看相关记录');
    expect(screen.queryByText('正在理解你的需求')).not.toBeInTheDocument();
    expect(screen.queryByText('处理进度')).not.toBeInTheDocument();

    rerender(<AgentRunMessage message={{ ...progressOnly, content: '训练建议开始输出' }} />);

    expect(screen.getByText('训练建议开始输出')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByText('正在查看相关记录')).not.toBeInTheDocument();
  });

  it('removes execution progress when a proposal is ready for confirmation', () => {
    const decide = vi.fn();
    const message: AgentRunUiMessage = {
      role: 'assistant',
      content: '## 你的计划',
      progress: ['正在整理建议'],
      approval: {
        approvalId: 'approval-1',
        status: 'REQUESTED',
        title: '保存当天训练计划',
        proposal: { scope: 'DAY', days: [{ date: '2026-08-10', title: '全身训练', estimatedMinutes: 30, exercises: [{ exerciseId: 'e1', name: '深蹲' }] }] },
      },
    };
    render(<AgentRunMessage message={message} onDecision={decide} />);
    expect(screen.queryByText('正在整理建议')).not.toBeInTheDocument();
    expect(screen.queryByText('处理进度')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认并保存' }));
    expect(decide).toHaveBeenCalledWith('approval-1', 'APPROVE');
  });

  it('uses readable fallback labels instead of exercise ids in historical approvals', () => {
    const exerciseId = '60000000-0000-0000-0000-000000000001';
    const completed = applyAgentRunEvent(
      { role: 'assistant', content: '' },
      {
        type: 'APPROVAL',
        data: {
          approvalId: 'approval-legacy',
          status: 'REQUESTED',
          title: '保存训练计划',
          proposal: {
            scope: 'WEEK',
            days: [
              {
                scheduledFor: '2026-08-13',
                title: '历史计划',
                estimatedMinutes: 20,
                exerciseIds: [exerciseId],
              },
              {
                scheduledFor: '2026-08-14',
                title: '空名称计划',
                estimatedMinutes: 20,
                exercises: [{ exerciseId, name: '  ' }],
              },
            ],
          },
        },
      },
    );

    render(<AgentRunMessage message={completed} onDecision={vi.fn()} />);

    expect(screen.getAllByText(/动作 1/)).toHaveLength(2);
    expect(screen.queryByText(new RegExp(exerciseId))).not.toBeInTheDocument();
  });

  it('renders arbitrary multi-day proposals while keeping legacy week labels', () => {
    const multiDay = applyAgentRunEvent(
      { role: 'assistant', content: '' },
      {
        type: 'APPROVAL',
        data: {
          approvalId: 'approval-multi',
          status: 'REQUESTED',
          title: '保存训练计划',
          proposal: {
            scope: 'MULTI_DAY',
            days: [{ scheduledFor: '2026-08-15', title: '周三训练', estimatedMinutes: 25, exercises: [] }],
          },
        },
      },
    );

    render(<AgentRunMessage message={multiDay} onDecision={vi.fn()} />);

    expect(screen.getByText('多个训练日')).toBeInTheDocument();
    expect(screen.getByText(/2026-08-15 · 周三训练/)).toBeInTheDocument();
  });

  it('hides provider thinking and reduces internal events to fixed business stages', () => {
    const events = [
      { type: 'RUN_STATE', data: { status: 'RUNNING', summary: '已建立运行上下文' } },
      { type: 'RUN_EVENT', data: { eventType: 'SKILL_LOADED', skillKey: 'fitness.plan.skill' } },
      { type: 'RUN_EVENT', data: { eventType: 'BLOCK_STARTED', blockId: 'think-1', type: 'THINKING' } },
      { type: 'RUN_EVENT', data: { eventType: 'BLOCK_DELTA', blockId: 'think-1', delta: 'I should inspect tool arguments' } },
      { type: 'RUN_EVENT', data: { eventType: 'TOOL_STARTED', toolKey: 'fitness.exercise.search' } },
      { type: 'RUN_EVENT', data: { eventType: 'MODEL_CALL_COMPLETED' } },
    ];
    const completed = events.reduce(
      (message, event) => applyAgentRunEvent(message, event),
      { role: 'assistant', content: '' } as AgentRunUiMessage,
    );

    expect(completed.progress).toEqual([
      '正在理解你的需求',
      '正在查看相关记录',
      '正在整理建议',
    ]);
    render(<AgentRunMessage message={completed} />);
    expect(screen.queryByText('思考过程')).not.toBeInTheDocument();
    expect(screen.queryByText(/fitness\./)).not.toBeInTheDocument();
    expect(screen.queryByText('I should inspect tool arguments')).not.toBeInTheDocument();
  });

  it('does not duplicate text when an adapter emits both block and legacy deltas', () => {
    const blockStarted = applyAgentRunEvent(
      { role: 'assistant', content: '' },
      { type: 'RUN_EVENT', data: { eventType: 'BLOCK_STARTED', blockId: 'text-1', type: 'TEXT' } },
    );
    const blockDelta = applyAgentRunEvent(
      blockStarted,
      { type: 'RUN_EVENT', data: { eventType: 'BLOCK_DELTA', blockId: 'text-1', delta: '训练计划已准备好' } },
    );
    const completed = applyAgentRunEvent(
      blockDelta,
      { type: 'TEXT_DELTA', data: { delta: '训练计划已准备好' } },
    );

    expect(completed.content).toBe('训练计划已准备好');
  });

  it('renders structured content blocks projected by the existing event contract', () => {
    const completed = applyAgentRunEvent(
      { role: 'assistant', content: '' },
      { type: 'STRUCTURED_COMPONENT', data: { messageId: 'message-1', block: { kind: 'TEXT', markdown: '**保持节奏**' } } },
    );

    render(<AgentRunMessage message={completed} />);

    expect(screen.getByText('保持节奏')).toBeInTheDocument();
  });

  it('keeps typed trend blocks when reducing structured component events', () => {
    const completed = applyAgentRunEvent(
      { role: 'assistant', content: '' },
      { type: 'STRUCTURED_COMPONENT', data: { messageId: 'message-2', block: { kind: 'BODY_TREND', metric: 'WAIST', unit: 'cm', points: [{ measuredAt: '2026-08-08T08:00:00+08:00', value: 71.2 }] } } },
    );

    render(<AgentRunMessage message={completed} />);

    expect(screen.getByRole('img', { name: '腰围趋势，共 1 条记录' })).toBeInTheDocument();
  });

  it('adapts structured confirmation actions to the trusted approval handler', () => {
    const decide = vi.fn();
    const completed = applyAgentRunEvent(
      { role: 'assistant', content: '' },
      { type: 'STRUCTURED_COMPONENT', data: { messageId: 'message-3', block: { kind: 'CONFIRMATION', confirmationId: 'approval-2', title: '保存计划', message: '保存到本周计划？', confirmLabel: '保存', cancelLabel: '取消' } } },
    );

    render(<AgentRunMessage message={completed} onDecision={decide} />);
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    expect(decide).toHaveBeenCalledWith('approval-2', 'APPROVE');
  });

  it('shows the safe fallback for unknown structured component events', () => {
    const completed = applyAgentRunEvent(
      { role: 'assistant', content: '' },
      { type: 'STRUCTURED_COMPONENT', data: { messageId: 'message-4', block: { kind: 'REMOTE_WIDGET', html: '<script>bad()</script>' } } },
    );

    render(<AgentRunMessage message={completed} />);

    expect(screen.getByText('暂不支持这类内容')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
  });

  it('locks a structured confirmation while its decision is submitting', () => {
    const message: AgentRunUiMessage = {
      role: 'assistant',
      content: '',
      blocks: [{ kind: 'CONFIRMATION', confirmationId: 'approval-3', title: '保存计划', message: '保存到本周计划？', confirmLabel: '保存', cancelLabel: '取消' }],
      deciding: true,
      decidingApprovalId: 'approval-3',
    };

    render(<AgentRunMessage message={message} onDecision={vi.fn()} />);

    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '取消' })).toBeDisabled();
  });

  it('replaces matching structured confirmation actions with one terminal status', () => {
    const requested = applyAgentRunEvent(
      { role: 'assistant', content: '' },
      { type: 'STRUCTURED_COMPONENT', data: { block: { kind: 'CONFIRMATION', confirmationId: 'approval-4', title: '保存计划', message: '保存到本周计划？', confirmLabel: '保存', cancelLabel: '取消' } } },
    );
    const approved = applyAgentRunEvent(requested, {
      type: 'APPROVAL',
      data: { approvalId: 'approval-4', status: 'APPROVED', title: '保存计划' },
    });

    render(<AgentRunMessage message={approved} onDecision={vi.fn()} />);

    expect(screen.getAllByText('已确认并保存')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '保存' })).not.toBeInTheDocument();
  });

  it('keeps a valid legacy approval when a matching structured confirmation is malformed', () => {
    const message: AgentRunUiMessage = {
      role: 'assistant',
      content: '',
      blocks: [{ kind: 'CONFIRMATION', confirmationId: 'approval-5' }],
      approval: { approvalId: 'approval-5', status: 'REQUESTED', title: '保存训练计划' },
    };

    render(<AgentRunMessage message={message} onDecision={vi.fn()} />);

    expect(screen.getByText('暂不支持这类内容')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认并保存' })).toBeEnabled();
  });
});
